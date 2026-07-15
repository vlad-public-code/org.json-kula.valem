package org.json_kula.valem.api.effects;

import org.junit.jupiter.api.Test;

import java.net.InetAddress;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * Unit tests for {@link EgressGuard}. Uses literal IP addresses for {@link EgressGuard#classify} so no
 * DNS resolution (network) is required; the cleartext-scheme cases short-circuit before host
 * resolution or run in {@code allowPrivate} mode (which skips resolution), so they are offline too.
 */
class EgressGuardTest {

    private static final long MAX = 1_048_576;

    // ── SEC-5: allow-private-ips must NOT enable cleartext http to public hosts ────────────────

    @Test
    void private_ips_alone_does_not_permit_cleartext_http() {
        // allowPrivate=true but allowInsecureHttp=false: http to a public host must still be rejected.
        EgressGuard guard = new EgressGuard(true, false, MAX, Set.of());
        assertThatThrownBy(() -> guard.check("http://api.example.com/hook"))
                .isInstanceOf(EgressGuard.EgressException.class)
                .hasMessageContaining("https");
    }

    @Test
    void insecure_http_flag_permits_cleartext_scheme() {
        // allowInsecureHttp=true opens the scheme; allowPrivate=true keeps host validation offline.
        EgressGuard guard = new EgressGuard(true, true, MAX, Set.of());
        assertThatCode(() -> guard.check("http://api.example.com/hook")).doesNotThrowAnyException();
    }

    @Test
    void https_is_always_scheme_allowed_even_without_insecure_flag() {
        EgressGuard guard = new EgressGuard(true, false, MAX, Set.of()); // allowPrivate skips resolve
        assertThatCode(() -> guard.check("https://api.example.com/hook")).doesNotThrowAnyException();
    }

    // ── SEC-8: optional host allowlist ────────────────────────────────────────────────────────

    @Test
    void allowlist_rejects_host_not_in_set() {
        EgressGuard guard = new EgressGuard(true, true, MAX, Set.of("allowed.example.com"));
        assertThatThrownBy(() -> guard.check("http://other.example.com/x"))
                .isInstanceOf(EgressGuard.EgressException.class)
                .hasMessageContaining("allowlist");
        assertThatCode(() -> guard.check("http://allowed.example.com/x")).doesNotThrowAnyException();
    }

    // ── Shared SSRF classifier (also used by WebFetchTool) ────────────────────────────────────

    @Test
    void classify_flags_non_public_ranges() throws Exception {
        assertThat(EgressGuard.classify(InetAddress.getByName("127.0.0.1")))
                .isEqualTo(EgressGuard.AddressClass.LOOPBACK);
        assertThat(EgressGuard.classify(InetAddress.getByName("10.0.0.1")))
                .isEqualTo(EgressGuard.AddressClass.SITE_LOCAL);
        assertThat(EgressGuard.classify(InetAddress.getByName("169.254.169.254")))
                .isEqualTo(EgressGuard.AddressClass.LINK_LOCAL); // cloud metadata endpoint
        assertThat(EgressGuard.classify(InetAddress.getByName("100.64.0.1")))
                .isEqualTo(EgressGuard.AddressClass.SHARED);
        assertThat(EgressGuard.classify(InetAddress.getByName("::1")))
                .isEqualTo(EgressGuard.AddressClass.LOOPBACK);
        assertThat(EgressGuard.classify(InetAddress.getByName("fc00::1")))
                .isEqualTo(EgressGuard.AddressClass.UNIQUE_LOCAL);
        assertThat(EgressGuard.classify(InetAddress.getByName("::ffff:127.0.0.1")))
                .isEqualTo(EgressGuard.AddressClass.LOOPBACK); // IPv4-mapped unwrapped
    }

    @Test
    void classify_passes_public_addresses() throws Exception {
        assertThat(EgressGuard.classify(InetAddress.getByName("8.8.8.8")))
                .isEqualTo(EgressGuard.AddressClass.PUBLIC);
        assertThat(EgressGuard.classify(InetAddress.getByName("2606:4700:4700::1111")))
                .isEqualTo(EgressGuard.AddressClass.PUBLIC);
    }
}
