package org.json_kula.valem.api.llm;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for DuckDuckGoSearchBackend's HTML parsing and URL decoding, using canned HTML so no
 * real network call is made.
 */
class DuckDuckGoSearchBackendTest {

    // Minimal DuckDuckGo HTML results page (two results, with uddg-redirect hrefs).
    private static final String DDG_HTML = """
            <html><body>
              <div class="result results_links web-result">
                <div class="links_main">
                  <a class="result__a" href="//duckduckgo.com/l/?uddg=https%3A%2F%2Fwww.emta.ee%2Fen&amp;rut=x">
                     Estonian Tax and Customs Board</a>
                  <a class="result__snippet" href="//duckduckgo.com/l/?uddg=https%3A%2F%2Fwww.emta.ee%2Fen">
                     Official information on Estonian motor vehicle tax rates.</a>
                </div>
              </div>
              <div class="result results_links web-result">
                <div class="links_main">
                  <a class="result__a" href="//duckduckgo.com/l/?uddg=https%3A%2F%2Fen.wikipedia.org%2Fwiki%2FMotor_tax&amp;rut=y">
                     Motor vehicle tax - Wikipedia</a>
                  <a class="result__snippet">A general overview of motor vehicle taxation.</a>
                </div>
              </div>
            </body></html>
            """;

    // ── URL decoding ──────────────────────────────────────────────────────────

    @Test
    void decodes_ddg_redirect_url() {
        String url = DuckDuckGoSearchBackend.decodeDdgUrl("//duckduckgo.com/l/?uddg=https%3A%2F%2Fwww.emta.ee%2Fen&rut=abc");
        assertThat(url).isEqualTo("https://www.emta.ee/en");
    }

    @Test
    void returns_absolute_href_unchanged_when_no_uddg() {
        assertThat(DuckDuckGoSearchBackend.decodeDdgUrl("https://example.com/page"))
                .isEqualTo("https://example.com/page");
    }

    @Test
    void decodes_blank_href_to_empty() {
        assertThat(DuckDuckGoSearchBackend.decodeDdgUrl("")).isEmpty();
        assertThat(DuckDuckGoSearchBackend.decodeDdgUrl(null)).isEmpty();
    }

    // ── Result parsing ────────────────────────────────────────────────────────

    @Test
    void parses_ddg_results_into_titles_urls_snippets() {
        String out = DuckDuckGoSearchBackend.parseResults(DDG_HTML, 5, "estonia motor tax");
        assertThat(out).contains("Estonian Tax and Customs Board");
        assertThat(out).contains("https://www.emta.ee/en");
        assertThat(out).contains("Official information on Estonian motor vehicle tax rates.");
        assertThat(out).contains("Motor vehicle tax - Wikipedia");
        assertThat(out).contains("https://en.wikipedia.org/wiki/Motor_tax");
    }

    @Test
    void respects_max_results() {
        String out = DuckDuckGoSearchBackend.parseResults(DDG_HTML, 1, "q");
        assertThat(out).contains("Estonian Tax and Customs Board");
        assertThat(out).doesNotContain("Wikipedia");
    }

    @Test
    void reports_no_results_for_empty_page() {
        String out = DuckDuckGoSearchBackend.parseResults("<html><body>nothing here</body></html>", 5, "obscure query");
        assertThat(out).contains("no results");
        assertThat(out).contains("obscure query");
    }
}
