package org.json_kula.valem.api.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

/**
 * {@link SearchBackend} backed by the <a href="https://brave.com/search/api/">Brave Search
 * API</a> — an independent index (not reselling Google/Bing results), keyed via
 * {@code X-Subscription-Token}. The recommended backend for a public-facing deployment: unlike
 * {@link DuckDuckGoSearchBackend}'s keyless scraping, an authenticated API call gets a real error
 * status on failure instead of silently parsing zero results out of a bot-challenge page.
 *
 * <p>Response shape (per Brave's documented Web Search API — verify against a live response if
 * this ever needs adjusting, since parsing here is deliberately defensive via {@link JsonNode}
 * path navigation rather than strict deserialization):
 * <pre>{@code
 * { "web": { "results": [ { "title": "...", "url": "...", "description": "..." }, ... ] } }
 * }</pre>
 */
public class BraveSearchBackend implements SearchBackend {

    private static final Logger log = LoggerFactory.getLogger(BraveSearchBackend.class);

    private static final String ENDPOINT = "https://api.search.brave.com/res/v1/web/search";
    private static final int TIMEOUT_SECONDS = 10;
    private static final int SNIPPET_MAX_CHARS = 240;

    private final String apiKey;
    private final ObjectMapper mapper;
    private final HttpClient httpClient;

    public BraveSearchBackend(String apiKey, ObjectMapper mapper) {
        this.apiKey = apiKey;
        this.mapper = mapper;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(TIMEOUT_SECONDS))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
    }

    @Override
    public String search(String query, int maxResults) {
        if (apiKey == null || apiKey.isBlank()) {
            log.warn("BraveSearchBackend: valem.llm.web-search.api-key is not set");
            return "[web_search: Brave Search API key not configured]";
        }
        try {
            String url = ENDPOINT + "?q=" + URLEncoder.encode(query, StandardCharsets.UTF_8)
                    + "&count=" + Math.max(1, Math.min(maxResults, 20));
            HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                    .GET()
                    .header("Accept", "application/json")
                    .header("X-Subscription-Token", apiKey)
                    .timeout(Duration.ofSeconds(TIMEOUT_SECONDS))
                    .build();
            HttpResponse<String> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() / 100 != 2)
                return "[web_search: HTTP " + resp.statusCode() + " from Brave Search API]";
            return parseResults(mapper.readTree(resp.body()), maxResults, query);
        } catch (Exception e) {
            log.warn("BraveSearchBackend: search failed for '{}': {}", query, e.getMessage());
            return "[web_search failed: " + e.getMessage() + "]";
        }
    }

    /**
     * Parses a Brave Search API JSON response into a compact, model-friendly list. Package-private
     * and decoupled from the network so it can be unit-tested with a canned JSON tree.
     */
    static String parseResults(JsonNode root, int maxResults, String query) {
        JsonNode results = root.path("web").path("results");
        StringBuilder sb = new StringBuilder();
        int n = 0;
        for (JsonNode result : results) {
            String title = result.path("title").asText("").strip();
            String url = result.path("url").asText("").strip();
            if (url.isEmpty()) continue;
            String snippet = truncate(result.path("description").asText("").strip());

            sb.append(++n).append(". ").append(title.isEmpty() ? url : title).append('\n')
              .append("   ").append(url).append('\n');
            if (!snippet.isEmpty())
                sb.append("   ").append(snippet).append('\n');

            if (n >= maxResults) break;
        }
        if (n == 0)
            return "[web_search: no results for \"" + query + "\"]";
        return "Search results for \"" + query + "\":\n" + sb;
    }

    private static String truncate(String s) {
        return s.length() <= SNIPPET_MAX_CHARS ? s : s.substring(0, SNIPPET_MAX_CHARS) + "…";
    }
}
