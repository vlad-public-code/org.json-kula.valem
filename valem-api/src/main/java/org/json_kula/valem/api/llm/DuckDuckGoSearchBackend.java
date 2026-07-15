package org.json_kula.valem.api.llm;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

/**
 * {@link SearchBackend} backed by the keyless DuckDuckGo HTML endpoint. No API key required, but
 * fragile: DuckDuckGo's anti-bot detection can silently return a challenge/block page (still HTTP
 * 200) for traffic from datacenter/hosting-provider IP ranges, which parses as zero results rather
 * than a clear error — the sandbox's hosted deployment hit exactly this. Kept as the free, no-key
 * default for self-hosted/dev use; {@link BraveSearchBackend} is the recommended option for a
 * public-facing deployment.
 *
 * <p>The search endpoint itself is fixed (no SSRF surface); the URLs it returns are plain text for
 * the model to pass to {@code web_fetch}, which independently validates them against
 * private/loopback ranges.
 */
public class DuckDuckGoSearchBackend implements SearchBackend {

    private static final Logger log = LoggerFactory.getLogger(DuckDuckGoSearchBackend.class);

    private static final String DDG_ENDPOINT = "https://html.duckduckgo.com/html/";
    private static final int TIMEOUT_SECONDS = 10;
    private static final int SNIPPET_MAX_CHARS = 240;

    private final HttpClient httpClient;

    public DuckDuckGoSearchBackend() {
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(TIMEOUT_SECONDS))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
    }

    @Override
    public String search(String query, int maxResults) {
        try {
            String form = "q=" + URLEncoder.encode(query, StandardCharsets.UTF_8);
            HttpRequest req = HttpRequest.newBuilder(URI.create(DDG_ENDPOINT))
                    .POST(HttpRequest.BodyPublishers.ofString(form))
                    .header("Content-Type", "application/x-www-form-urlencoded")
                    .header("User-Agent", "Valem/1.0 LLM-spec-generator")
                    .header("Accept", "text/html")
                    .timeout(Duration.ofSeconds(TIMEOUT_SECONDS))
                    .build();
            HttpResponse<String> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() / 100 != 2)
                return "[web_search: HTTP " + resp.statusCode() + " from search backend]";
            return parseResults(resp.body(), maxResults, query);
        } catch (Exception e) {
            log.warn("DuckDuckGoSearchBackend: search failed for '{}': {}", query, e.getMessage());
            return "[web_search failed: " + e.getMessage() + "]";
        }
    }

    /**
     * Parses a DuckDuckGo HTML results page into a compact, model-friendly list.
     * Package-private and decoupled from the network so it can be unit-tested with canned HTML.
     */
    static String parseResults(String html, int maxResults, String query) {
        Document doc = Jsoup.parse(html);
        StringBuilder sb = new StringBuilder();
        int n = 0;
        for (Element result : doc.select("div.result, div.web-result")) {
            Element anchor = result.selectFirst("a.result__a");
            if (anchor == null) continue;
            String url = decodeDdgUrl(anchor.attr("href"));
            if (url.isEmpty()) continue;
            String title = anchor.text().strip();

            Element snippetEl = result.selectFirst(".result__snippet");
            String snippet = snippetEl != null ? truncate(snippetEl.text().strip()) : "";

            sb.append(++n).append(". ").append(title).append('\n')
              .append("   ").append(url).append('\n');
            if (!snippet.isEmpty())
                sb.append("   ").append(snippet).append('\n');

            if (n >= maxResults) break;
        }
        if (n == 0)
            return "[web_search: no results for \"" + query + "\"]";
        return "Search results for \"" + query + "\":\n" + sb;
    }

    /**
     * DuckDuckGo HTML result links are redirect URLs of the form
     * {@code //duckduckgo.com/l/?uddg=<percent-encoded-target>&rut=...}. Extract and decode the
     * real target. Absolute hrefs (some result types) are returned as-is.
     */
    static String decodeDdgUrl(String href) {
        if (href == null || href.isBlank()) return "";
        String normalized = href.startsWith("//") ? "https:" + href : href;
        try {
            URI uri = URI.create(normalized);
            String rawQuery = uri.getRawQuery();
            if (rawQuery != null) {
                for (String part : rawQuery.split("&")) {
                    if (part.startsWith("uddg="))
                        return URLDecoder.decode(part.substring("uddg=".length()), StandardCharsets.UTF_8);
                }
            }
            return normalized;
        } catch (Exception e) {
            return normalized;
        }
    }

    private static String truncate(String s) {
        return s.length() <= SNIPPET_MAX_CHARS ? s : s.substring(0, SNIPPET_MAX_CHARS) + "…";
    }
}
