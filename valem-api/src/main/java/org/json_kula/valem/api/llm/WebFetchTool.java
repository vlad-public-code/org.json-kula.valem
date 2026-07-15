package org.json_kula.valem.api.llm;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.json_kula.valem.api.effects.EgressGuard;
import org.json_kula.valem.core.llm.LlmClient.ToolCall;
import org.json_kula.valem.core.llm.LlmClient.ToolDefinition;
import org.json_kula.valem.core.llm.LlmClient.ToolExecutor;
import org.json_kula.valem.core.llm.WebTool;
import org.jsoup.Jsoup;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.InetAddress;
import java.net.URI;
import java.net.URL;
import java.net.UnknownHostException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * WebTool implementation that fetches public web pages for LLM use during spec generation.
 *
 * <p>Safety measures:
 * <ul>
 *   <li>Only {@code http://} and {@code https://} schemes allowed.</li>
 *   <li>Hostname is resolved and checked against loopback, private, link-local, and
 *       shared-address-space ranges (SSRF protection).</li>
 *   <li>HTML is stripped to plain text to prevent token explosion.</li>
 *   <li>Per-session call counter (created via {@link #newExecutor()}) caps total fetches.</li>
 * </ul>
 */
public final class WebFetchTool implements WebTool {

    private static final Logger log = LoggerFactory.getLogger(WebFetchTool.class);

    static final String TOOL_NAME = "web_fetch";
    private static final int TIMEOUT_SECONDS = 10;

    private final int        maxCallsPerSession;
    private final int        maxCharsPerPage;
    private final HttpClient httpClient;

    public WebFetchTool(int maxCallsPerSession, int maxCharsPerPage) {
        this.maxCallsPerSession = maxCallsPerSession;
        this.maxCharsPerPage    = maxCharsPerPage;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(TIMEOUT_SECONDS))
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
    }

    @Override
    public List<ToolDefinition> definitions() {
        return List.of(definition());
    }

    ToolDefinition definition() {
        ObjectNode schema = JsonNodeFactory.instance.objectNode();
        schema.put("type", "object");
        ObjectNode props  = schema.putObject("properties");
        ObjectNode urlProp = props.putObject("url");
        urlProp.put("type", "string");
        urlProp.put("description", "The fully qualified public URL to fetch (https:// preferred).");
        schema.putArray("required").add("url");
        return new ToolDefinition(
                TOOL_NAME,
                "Fetches a public HTML web page and returns its plain-text content. " +
                "Only HTML pages are supported; do not use for PDFs, JSON APIs, or binary files. " +
                "Prefer a URL returned by web_search over a guessed one. " +
                "Use to look up domain rules, tax rates, financial formulas, or any " +
                "authoritative reference needed to generate an accurate model spec.",
                schema);
    }

    @Override
    public CollectingExecutor newExecutor() {
        return new CollectingExecutor();
    }

    // ── CollectingExecutor ────────────────────────────────────────────────────

    /** Per-session executor that records a {@link WebFetchFact} for every fetch attempt. */
    final class CollectingExecutor implements ToolExecutor, FactProvider {

        private final List<WebFetchFact>  facts     = new ArrayList<>();
        private final AtomicInteger       remaining = new AtomicInteger(maxCallsPerSession);
        // URL → previously-returned content. Models often re-request the same page across tool turns
        // and retries; a cache hit returns the content without spending the (small) fetch budget.
        private final Map<String, String> cache     = new ConcurrentHashMap<>();

        @Override
        public List<WebFetchFact> facts() { return List.copyOf(facts); }

        @Override
        public String execute(ToolCall call) {
            String url = call.arguments().path("url").asText("");
            String key = url.strip();

            String cached = cache.get(key);
            if (cached != null) {
                log.info("WebFetchTool: returning cached content for '{}' (no budget consumed)",
                        sanitizeUrl(url));
                return cached;
            }

            int left = remaining.getAndDecrement();
            if (left <= 0) {
                log.warn("WebFetchTool: per-session call limit ({}) exhausted", maxCallsPerSession);
                return "[Web fetch limit reached for this generation session]";
            }
            log.info("WebFetchTool: fetching '{}' ({} fetches remaining)", sanitizeUrl(url), left - 1);
            FetchOutcome outcome = fetchSafe(url);
            facts.add(outcome.fact());
            if (!key.isEmpty()) cache.put(key, outcome.content());
            return outcome.content();
        }
    }

    // ── Fetch ─────────────────────────────────────────────────────────────────

    private record FetchOutcome(String content, WebFetchFact fact) {}

    private FetchOutcome fetchSafe(String rawUrl) {
        try {
            URL validated = validateUrl(rawUrl);
            return fetchAndExtract(rawUrl, validated.toURI());
        } catch (IllegalArgumentException e) {
            log.warn("WebFetchTool: URL rejected — {}", e.getMessage());
            return new FetchOutcome("[URL rejected: " + e.getMessage() + "]",
                    new WebFetchFact(rawUrl, 0, "", 0, 0));
        } catch (Exception e) {
            log.warn("WebFetchTool: fetch failed for '{}': {}", rawUrl, e.getMessage());
            return new FetchOutcome("[Fetch failed: " + e.getMessage() + "]",
                    new WebFetchFact(rawUrl, 0, "", 0, 0));
        }
    }

    // package-private for unit-testing
    /**
     * Percent-encodes characters that {@link URI}'s single-argument parser rejects but that appear in
     * otherwise-valid URLs the model emits — spaces and non-ASCII (e.g. a path with Estonian letters or
     * a stray space) — so a recoverable URL is fetched instead of rejected with "Illegal character in
     * path". Already-valid URL delimiters and existing {@code %XX} escapes are left untouched (only
     * space, control chars, and code points &gt; 0x7E are encoded, as UTF-8 bytes).
     */
    static String normalizeUrl(String s) {
        StringBuilder sb = new StringBuilder(s.length() + 16);
        for (int i = 0; i < s.length(); ) {
            int cp = s.codePointAt(i);
            if (cp == ' ' || cp < 0x20 || cp > 0x7E) {
                for (byte b : new String(Character.toChars(cp)).getBytes(StandardCharsets.UTF_8))
                    sb.append('%').append(String.format("%02X", b & 0xFF));
            } else {
                sb.append((char) cp);
            }
            i += Character.charCount(cp);
        }
        return sb.toString();
    }

    URL validateUrl(String raw) {
        if (raw == null || raw.isBlank())
            throw new IllegalArgumentException("URL is blank");
        if (raw.length() > 2048)
            throw new IllegalArgumentException("URL exceeds 2048 chars");

        // Check scheme before full URI parse so the error is consistent regardless of
        // whether Java's URL class can even construct the given scheme.
        String stripped = raw.strip();
        String lower    = stripped.toLowerCase();
        if (!lower.startsWith("https://") && !lower.startsWith("http://"))
            throw new IllegalArgumentException(
                    "Only http/https allowed (got: " + stripped.replaceAll("://.*", "") + ")");

        URI uri;
        URL url;
        try {
            uri = new URI(normalizeUrl(stripped));
            url = uri.toURL();
        } catch (Exception e) {
            throw new IllegalArgumentException("Malformed URL: " + e.getMessage());
        }

        String host = url.getHost();
        if (host == null || host.isBlank())
            throw new IllegalArgumentException("Missing host");

        // Block well-known loopback names before DNS (fast path, no network call)
        String lowerHost = host.toLowerCase();
        if (lowerHost.equals("localhost") || lowerHost.endsWith(".localhost"))
            throw new IllegalArgumentException("Loopback hostname not allowed");

        // Resolve and inspect EVERY address the host maps to (a host with multiple A/AAAA
        // records must have all of them validated, not just the first).
        resolveAndValidate(host);
        return url;
    }

    /**
     * Resolves {@code host} to all of its addresses and validates every one.
     * Returns the validated addresses. Throws {@link IllegalArgumentException} if the host
     * cannot be resolved or any resolved address falls in a blocked range.
     */
    // package-private for unit-testing
    List<InetAddress> resolveAndValidate(String host) {
        InetAddress[] addrs;
        try {
            addrs = InetAddress.getAllByName(host);
        } catch (UnknownHostException e) {
            throw new IllegalArgumentException("Cannot resolve host: " + host);
        }
        if (addrs.length == 0)
            throw new IllegalArgumentException("Host resolved to no addresses: " + host);
        for (InetAddress addr : addrs)
            checkAddress(addr);
        return List.of(addrs);
    }

    // package-private for unit-testing. Delegates the blocked-range logic to the shared
    // EgressGuard.classify (audit SEC-3/T1.3 — one home for the SSRF ranges), mapping the category to
    // this tool's LLM-facing message text.
    void checkAddress(InetAddress addr) {
        switch (EgressGuard.classify(addr)) {
            case PUBLIC -> { /* safely routable */ }
            case LOOPBACK -> throw new IllegalArgumentException("Loopback address blocked");
            case WILDCARD -> throw new IllegalArgumentException("Wildcard address blocked");
            case SITE_LOCAL -> throw new IllegalArgumentException("Private (RFC 1918) address blocked");
            case LINK_LOCAL -> throw new IllegalArgumentException(
                    "link-local address blocked (includes 169.254.x.x)");
            case MULTICAST -> throw new IllegalArgumentException("Multicast address blocked");
            case UNIQUE_LOCAL -> throw new IllegalArgumentException(
                    "IPv6 unique-local (fc00::/7) address blocked");
            case SHARED -> throw new IllegalArgumentException("Shared address space (100.64/10) blocked");
        }
    }

    private FetchOutcome fetchAndExtract(String rawUrl, URI uri) throws IOException, InterruptedException {
        // Re-resolve and re-validate immediately before connecting. java.net.http offers no
        // resolver hook to pin the exact validated InetAddress, so this final check (sharing the
        // OS DNS cache used microseconds later by httpClient.send) narrows the DNS-rebinding
        // window to a practically unexploitable size while keeping TLS hostname verification intact.
        resolveAndValidate(uri.getHost());

        HttpRequest req = HttpRequest.newBuilder(uri)
                .GET()
                .header("User-Agent", "Valem/1.0 LLM-spec-generator")
                .header("Accept", "text/html,text/plain;q=0.9,*/*;q=0.8")
                .timeout(Duration.ofSeconds(TIMEOUT_SECONDS))
                .build();

        HttpResponse<String> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
        int status = resp.statusCode();

        // Redirects are not followed (SSRF via open-redirect prevention): inform the LLM.
        // Handle 3xx before the generic non-2xx branch so the redirect message is reachable.
        if (status >= 300 && status < 400) {
            String location = resp.headers().firstValue("location").orElse("unknown");
            return new FetchOutcome("[Redirect to " + location + " not followed]",
                    new WebFetchFact(rawUrl, status, "", 0, 0));
        }
        if (status < 200 || status >= 300) {
            return new FetchOutcome("[HTTP " + status + " from " + uri.getHost() + "]",
                    new WebFetchFact(rawUrl, status, "", 0, 0));
        }

        String body        = resp.body();
        int    rawLength   = body.length();
        String contentType = resp.headers().firstValue("content-type").orElse("");
        String mediaType   = contentType.isBlank() ? "" : contentType.split(";")[0].strip();

        if (contentType.contains("text/html") || !contentType.contains("text/"))
            body = htmlToText(body);

        if (body.length() > maxCharsPerPage)
            body = body.substring(0, maxCharsPerPage) + "\n[...truncated to " + maxCharsPerPage + " chars]";

        int extractedLength = body.length();
        return new FetchOutcome(body,
                new WebFetchFact(rawUrl, status, mediaType, rawLength, extractedLength));
    }

    // ── HTML → text via Jsoup ─────────────────────────────────────────────────

    private String htmlToText(String html) {
        return Jsoup.parse(html).text();
    }

    // ── Utility ───────────────────────────────────────────────────────────────

    /** Removes embedded credentials from a URL before it is written to logs. */
    static String sanitizeUrl(String url) {
        if (url == null) return "";
        try {
            URI uri = new URI(url);
            if (uri.getUserInfo() != null) {
                return new URI(uri.getScheme(), "***:***", uri.getHost(), uri.getPort(),
                        uri.getPath(), uri.getQuery(), uri.getFragment()).toString();
            }
        } catch (Exception ignored) { /* return as-is */ }
        return url;
    }
}
