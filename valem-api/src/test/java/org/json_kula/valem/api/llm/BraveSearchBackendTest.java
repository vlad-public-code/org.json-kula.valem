package org.json_kula.valem.api.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for BraveSearchBackend's JSON parsing, using a canned response tree modeled on
 * Brave's documented Web Search API shape so no real network call (or API key) is needed.
 */
class BraveSearchBackendTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static JsonNode braveResponse() throws Exception {
        return MAPPER.readTree("""
                {
                  "web": {
                    "results": [
                      {
                        "title": "Estonian Tax and Customs Board",
                        "url": "https://www.emta.ee/en",
                        "description": "Official information on Estonian motor vehicle tax rates."
                      },
                      {
                        "title": "Motor vehicle tax - Wikipedia",
                        "url": "https://en.wikipedia.org/wiki/Motor_tax",
                        "description": "A general overview of motor vehicle taxation."
                      }
                    ]
                  }
                }
                """);
    }

    @Test
    void parses_brave_results_into_titles_urls_snippets() throws Exception {
        String out = BraveSearchBackend.parseResults(braveResponse(), 5, "estonia motor tax");
        assertThat(out).contains("Estonian Tax and Customs Board");
        assertThat(out).contains("https://www.emta.ee/en");
        assertThat(out).contains("Official information on Estonian motor vehicle tax rates.");
        assertThat(out).contains("Motor vehicle tax - Wikipedia");
        assertThat(out).contains("https://en.wikipedia.org/wiki/Motor_tax");
    }

    @Test
    void respects_max_results() throws Exception {
        String out = BraveSearchBackend.parseResults(braveResponse(), 1, "q");
        assertThat(out).contains("Estonian Tax and Customs Board");
        assertThat(out).doesNotContain("Wikipedia");
    }

    @Test
    void reports_no_results_for_empty_results_array() throws Exception {
        JsonNode empty = MAPPER.readTree("{\"web\":{\"results\":[]}}");
        String out = BraveSearchBackend.parseResults(empty, 5, "obscure query");
        assertThat(out).contains("no results");
        assertThat(out).contains("obscure query");
    }

    @Test
    void tolerates_missing_web_key_entirely() throws Exception {
        // Defensive parsing via JsonNode.path() — a response shape that doesn't match what's
        // expected should degrade to "no results", not throw.
        JsonNode malformed = MAPPER.readTree("{}");
        String out = BraveSearchBackend.parseResults(malformed, 5, "q");
        assertThat(out).contains("no results");
    }

    @Test
    void skips_results_missing_a_url() throws Exception {
        JsonNode oneBad = MAPPER.readTree("""
                {"web":{"results":[
                  {"title":"No URL here","description":"x"},
                  {"title":"Has URL","url":"https://example.com","description":"y"}
                ]}}
                """);
        String out = BraveSearchBackend.parseResults(oneBad, 5, "q");
        assertThat(out).doesNotContain("No URL here");
        assertThat(out).contains("Has URL");
    }

    @Test
    void missing_api_key_returns_status_string_without_network_call() {
        BraveSearchBackend backend = new BraveSearchBackend("", MAPPER);
        assertThat(backend.search("q", 5)).contains("API key not configured");
    }
}
