package org.json_kula.valem.api.effects;

import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;

/**
 * Generic runtime SSRF guard for server-effect egress. The URL is spec-provided (model-specific,
 * authored by the LLM/user), so the guard needs no prior knowledge of it: it validates scheme and
 * resolves the host, rejecting loopback / private / link-local / multicast / unique-local / shared /
 * cloud-metadata targets. This is one-time policy, not a per-URL allowlist.
 *
 * <p>{@code allowPrivate} relaxes the <em>address</em> checks for local development and integration
 * tests (e.g. a stub server on {@code 127.0.0.1}); it defaults to {@code false} in production. It no
 * longer relaxes the scheme (audit SEC-5): cleartext {@code http} is a separate, independently-gated
 * concern ({@code allowInsecureHttp}), so enabling private-IP access for a local stub does not
 * silently permit cleartext egress to public hosts.
 *
 * <p>An optional {@code allowedHosts} allowlist (empty = allow any public host) further constrains
 * egress to a fixed set of destination hostnames (audit SEC-8).
 */
public final class EgressGuard {

    /** Thrown when a target URL must not be called. */
    public static final class EgressException extends RuntimeException {
        public EgressException(String message) { super(message); }
    }

    private final boolean allowPrivate;
    private final boolean allowInsecureHttp;
    private final long maxResponseBytes;
    private final Set<String> allowedHosts;

    public EgressGuard(boolean allowPrivate, long maxResponseBytes) {
        this(allowPrivate, false, maxResponseBytes, Set.of());
    }

    public EgressGuard(boolean allowPrivate, boolean allowInsecureHttp, long maxResponseBytes,
                       Set<String> allowedHosts) {
        this.allowPrivate = allowPrivate;
        // Cleartext http is gated *solely* by allowInsecureHttp (audit SEC-5): the private-IP
        // relaxation is an address-only concern and must never widen the scheme, otherwise turning it
        // on for a loopback stub would silently permit http:// egress to public hosts. A dev pointing
        // at a plain-http loopback stub sets both allow-private-ips and allow-insecure-http.
        this.allowInsecureHttp = allowInsecureHttp;
        this.maxResponseBytes = maxResponseBytes;
        Set<String> hosts = new LinkedHashSet<>();
        if (allowedHosts != null) {
            for (String h : allowedHosts) {
                if (h != null && !h.isBlank()) hosts.add(h.trim().toLowerCase(Locale.ROOT));
            }
        }
        this.allowedHosts = Collections.unmodifiableSet(hosts);
    }

    public long maxResponseBytes() { return maxResponseBytes; }

    /** Validates the target URL and returns the parsed absolute {@link URI}, or throws. */
    public URI check(String url) {
        if (url == null || url.isBlank()) {
            throw new EgressException("effect url is empty");
        }
        URI uri;
        try {
            uri = URI.create(url.trim());
        } catch (IllegalArgumentException e) {
            throw new EgressException("malformed effect url: " + url);
        }

        String scheme = uri.getScheme();
        if (scheme == null) {
            throw new EgressException("effect url must be absolute (scheme required): " + url);
        }
        scheme = scheme.toLowerCase(Locale.ROOT);
        // Scheme enforcement is independent of the private-IP relaxation (SEC-5): https is always
        // allowed; cleartext http only when it is explicitly permitted — never merely because
        // private-IP access is on, which would leak cleartext to public hosts.
        boolean httpOk = allowInsecureHttp;
        if (scheme.equals("https") || (scheme.equals("http") && httpOk)) {
            // ok
        } else if (scheme.equals("http")) {
            throw new EgressException("only https egress is allowed: " + url);
        } else {
            throw new EgressException("unsupported url scheme: " + scheme);
        }

        String host = uri.getHost();
        if (host == null || host.isBlank()) {
            throw new EgressException("effect url has no host: " + url);
        }

        if (!allowedHosts.isEmpty() && !allowedHosts.contains(host.toLowerCase(Locale.ROOT))) {
            throw new EgressException("egress host not in allowlist: " + host);
        }

        validateHost(host, url);
        return uri;
    }

    /**
     * Re-resolves and re-validates the URI's host immediately before the client connects, narrowing
     * the DNS-rebinding window (audit SEC-3). {@code java.net.http} offers no resolver hook to pin the
     * exact validated address, so this shares the OS DNS cache used microseconds later by the send.
     */
    public void revalidate(URI uri) {
        if (allowPrivate) return; // dev mode: private targets are intentionally permitted
        String host = uri.getHost();
        if (host == null || host.isBlank()) {
            throw new EgressException("effect url has no host: " + uri);
        }
        validateHost(host, uri.toString());
    }

    private void validateHost(String host, String url) {
        if (allowPrivate) return;
        InetAddress[] addrs;
        try {
            addrs = InetAddress.getAllByName(host);   // resolve every A/AAAA record
        } catch (UnknownHostException e) {
            throw new EgressException("cannot resolve effect host: " + host);
        }
        if (addrs.length == 0) {
            throw new EgressException("effect host resolved to no addresses: " + host);
        }
        for (InetAddress a : addrs) {
            checkAddress(a, host);
        }
    }

    /** Rejects a resolved address that is not safely public. */
    private static void checkAddress(InetAddress addr, String host) {
        AddressClass c = classify(addr);
        if (c != AddressClass.PUBLIC) {
            throw new EgressException("egress to non-public address blocked: " + host + " -> "
                    + addr.getHostAddress() + " (" + c + ")");
        }
    }

    /**
     * Classification of a resolved IP address for SSRF egress decisions. {@code PUBLIC} is the only
     * safely-routable category; every other value denotes a target egress must refuse (loopback,
     * wildcard, RFC 1918 site-local, link-local — which includes the {@code 169.254.169.254} cloud
     * metadata endpoint — multicast, IPv6 unique-local {@code fc00::/7}, and RFC 6598 shared
     * {@code 100.64/10}). This is the single source of truth for the blocked-range logic; the LLM
     * {@code WebFetchTool} shares it (audit SEC-3/T1.3) so the two egress paths cannot drift.
     */
    public enum AddressClass {
        PUBLIC, LOOPBACK, WILDCARD, SITE_LOCAL, LINK_LOCAL, MULTICAST, UNIQUE_LOCAL, SHARED
    }

    /** Classifies a resolved address, unwrapping an IPv4-mapped IPv6 address first. */
    public static AddressClass classify(InetAddress addr) {
        byte[] b = addr.getAddress();

        // IPv4-mapped IPv6 (::ffff:0:0/96): unwrap to the embedded IPv4 and re-classify, otherwise
        // ::ffff:127.0.0.1 would slip past the IPv4-only predicates below.
        if (b.length == 16 && isIpv4Mapped(b)) {
            try {
                return classify(InetAddress.getByAddress(new byte[]{b[12], b[13], b[14], b[15]}));
            } catch (UnknownHostException e) {
                return AddressClass.WILDCARD; // unparseable mapped address — treat as unsafe
            }
        }

        if (addr.isLoopbackAddress())  return AddressClass.LOOPBACK;
        if (addr.isAnyLocalAddress())  return AddressClass.WILDCARD;
        if (addr.isSiteLocalAddress()) return AddressClass.SITE_LOCAL;
        if (addr.isLinkLocalAddress()) return AddressClass.LINK_LOCAL; // includes 169.254.169.254
        if (addr.isMulticastAddress()) return AddressClass.MULTICAST;
        // IPv6 unique-local addresses fc00::/7 — not covered by isSiteLocalAddress.
        if (b.length == 16 && (b[0] & 0xFE) == 0xFC) return AddressClass.UNIQUE_LOCAL;
        // 100.64.0.0/10 — shared address space (RFC 6598); not caught by isSiteLocalAddress.
        if (b.length == 4) {
            int b0 = b[0] & 0xFF, b1 = b[1] & 0xFF;
            if (b0 == 100 && b1 >= 64 && b1 <= 127) return AddressClass.SHARED;
        }
        return AddressClass.PUBLIC;
    }

    /** True if a 16-byte address is an IPv4-mapped IPv6 address (::ffff:a.b.c.d). */
    private static boolean isIpv4Mapped(byte[] b) {
        for (int i = 0; i < 10; i++) if (b[i] != 0) return false;
        return (b[10] & 0xFF) == 0xFF && (b[11] & 0xFF) == 0xFF;
    }
}
