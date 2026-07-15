package org.json_kula.valem.api.llm;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit tests for WebFetchTool URL validation (SSRF protection).
 * Does not make real network calls.
 */
class WebFetchToolTest {

    private final WebFetchTool tool = new WebFetchTool(5, 8_000);

    // ── Scheme validation ─────────────────────────────────────────────────────

    @Test
    void allows_https_url() {
        assertThat(tool.validateUrl("https://example.com/page")).isNotNull();
    }

    @Test
    void allows_http_url() {
        assertThat(tool.validateUrl("http://example.com/page")).isNotNull();
    }

    // ── URL normalization (#6) ──────────────────────────────────────────────────

    @Test
    void normalize_url_percent_encodes_space_and_non_ascii() {
        // The Estonian-tax run emitted a URL with a literal space and an 'õ'; it must be encoded,
        // not rejected with "Illegal character in path".
        assertThat(WebFetchTool.normalizeUrl("https://x.ee/a b/sõiduk.xlsx"))
                .isEqualTo("https://x.ee/a%20b/s%C3%B5iduk.xlsx");
    }

    @Test
    void normalize_url_leaves_valid_urls_and_existing_escapes_untouched() {
        String valid = "https://example.com/path?q=1&r=2#frag";
        assertThat(WebFetchTool.normalizeUrl(valid)).isEqualTo(valid);
        assertThat(WebFetchTool.normalizeUrl("https://example.com/a%20b")).isEqualTo("https://example.com/a%20b");
    }

    @Test
    void validate_url_accepts_a_url_with_a_space_after_normalization() {
        assertThat(tool.validateUrl("https://example.com/a b/c")).isNotNull();
    }

    @ParameterizedTest
    @ValueSource(strings = {"ftp://example.com", "file:///etc/passwd", "javascript:alert(1)",
                             "data:text/html,<h1>evil</h1>", "//example.com"})
    void rejects_non_http_schemes(String url) {
        assertThatThrownBy(() -> tool.validateUrl(url))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("http");  // covers "Only http/https allowed" and "Malformed"
    }

    // ── Loopback / private addresses ──────────────────────────────────────────

    @ParameterizedTest
    @ValueSource(strings = {"http://localhost/", "http://localhost:8080/api"})
    void rejects_localhost(String url) {
        assertThatThrownBy(() -> tool.validateUrl(url))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Loopback");
    }

    @Test
    void rejects_127_loopback() {
        assertThatThrownBy(() -> tool.validateUrl("http://127.0.0.1/"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Loopback");
    }

    @Test
    void rejects_private_192_168() {
        assertThatThrownBy(() -> tool.validateUrl("http://192.168.1.1/"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Private");
    }

    @Test
    void rejects_private_10_x() {
        assertThatThrownBy(() -> tool.validateUrl("http://10.0.0.1/admin"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Private");
    }

    @Test
    void rejects_private_172_16_range() {
        assertThatThrownBy(() -> tool.validateUrl("http://172.16.0.1/"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Private");
    }

    @Test
    void rejects_link_local_169_254() {
        // 169.254.169.254 is the AWS/GCP/Azure metadata endpoint — critical to block
        assertThatThrownBy(() -> tool.validateUrl("http://169.254.169.254/latest/meta-data/"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("link-local");
    }

    // ── IPv6 SSRF coverage (A-T3) ─────────────────────────────────────────────

    @Test
    void rejects_ipv6_loopback() {
        assertThatThrownBy(() -> tool.validateUrl("http://[::1]/"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Loopback");
    }

    @Test
    void rejects_ipv6_unique_local_fc00() {
        // fc00::/7 unique-local — not caught by isSiteLocalAddress()
        assertThatThrownBy(() -> tool.validateUrl("http://[fc00::1]/"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("unique-local");
    }

    @Test
    void rejects_ipv4_mapped_ipv6_loopback() {
        // ::ffff:127.0.0.1 must be unwrapped and blocked as IPv4 loopback
        assertThatThrownBy(() -> tool.validateUrl("http://[::ffff:127.0.0.1]/"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Loopback");
    }

    @Test
    void rejects_ipv4_mapped_ipv6_private() {
        assertThatThrownBy(() -> tool.validateUrl("http://[::ffff:10.0.0.1]/"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Private");
    }

    @Test
    void checkAddress_rejects_link_local_ipv6() throws Exception {
        java.net.InetAddress fe80 = java.net.InetAddress.getByName("fe80::1");
        assertThatThrownBy(() -> tool.checkAddress(fe80))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("link-local");
    }

    // ── Malformed / edge cases ────────────────────────────────────────────────

    @Test
    void rejects_blank_url() {
        assertThatThrownBy(() -> tool.validateUrl(""))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("blank");
    }

    @Test
    void rejects_oversized_url() {
        String longUrl = "https://example.com/" + "a".repeat(2100);
        assertThatThrownBy(() -> tool.validateUrl(longUrl))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("2048");
    }

    // ── Tool definition ───────────────────────────────────────────────────────

    @Test
    void definition_has_correct_name_and_schema() {
        var def = tool.definition();
        assertThat(def.name()).isEqualTo(WebFetchTool.TOOL_NAME);
        assertThat(def.description()).isNotBlank();
        assertThat(def.inputSchema().path("properties").has("url")).isTrue();
    }

    // ── Call-limit enforcement ────────────────────────────────────────────────

    @Test
    void executor_returns_limit_message_after_max_calls() {
        var limitedTool = new WebFetchTool(2, 8_000);
        var executor = limitedTool.newExecutor();

        // Drain the limit with DISTINCT blocked URLs (a repeated URL is de-duplicated, not re-counted).
        executor.execute(makeCall("http://127.0.0.1/a"));   // blocked → short-circuits
        executor.execute(makeCall("http://127.0.0.1/b"));
        // Third distinct call exceeds the limit
        String result = executor.execute(makeCall("http://127.0.0.1/c"));
        assertThat(result).contains("limit reached");
    }

    @Test
    void each_new_executor_has_its_own_call_counter() {
        var limitedTool = new WebFetchTool(1, 8_000);
        var ex1 = limitedTool.newExecutor();
        var ex2 = limitedTool.newExecutor();

        // Exhaust ex1 with two distinct URLs (budget = 1)
        ex1.execute(makeCall("http://127.0.0.1/a"));
        String ex1Result = ex1.execute(makeCall("http://127.0.0.1/b"));
        assertThat(ex1Result).contains("limit reached");

        // ex2 should still have its own fresh counter
        String ex2Result = ex2.execute(makeCall("http://127.0.0.1/a"));
        assertThat(ex2Result).doesNotContain("limit reached"); // blocked by SSRF, not by limit
    }

    @Test
    void repeated_url_is_de_duplicated_and_does_not_consume_budget() {
        var limitedTool = new WebFetchTool(1, 8_000);  // budget = 1
        var executor = limitedTool.newExecutor();

        String first  = executor.execute(makeCall("http://127.0.0.1/page")); // consumes the 1 budget
        String second = executor.execute(makeCall("http://127.0.0.1/page")); // same URL → cache hit

        assertThat(second).isEqualTo(first);                 // identical cached content
        assertThat(second).doesNotContain("limit reached");  // not blocked by budget — it was cached
    }

    private static org.json_kula.valem.core.llm.LlmClient.ToolCall makeCall(String url) {
        com.fasterxml.jackson.databind.node.ObjectNode args =
                com.fasterxml.jackson.databind.node.JsonNodeFactory.instance.objectNode();
        args.put("url", url);
        return new org.json_kula.valem.core.llm.LlmClient.ToolCall("id1", WebFetchTool.TOOL_NAME, args);
    }
}
