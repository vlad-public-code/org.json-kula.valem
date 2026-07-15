package org.json_kula.valem.api.llm;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.json_kula.valem.core.llm.LlmClient.ToolCall;
import org.json_kula.valem.core.llm.LlmClient.ToolDefinition;
import org.json_kula.valem.core.llm.LlmClient.ToolExecutor;
import org.json_kula.valem.core.llm.WebTool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * WebTool implementation that performs a web search and returns a ranked list of
 * {@code title — url — snippet} entries, so the LLM can discover an authoritative URL
 * instead of guessing one (which wastes the {@code web_fetch} budget on 404s).
 *
 * <p>The actual search call is delegated to a pluggable {@link SearchBackend} (selected via
 * {@code valem.llm.web-search.provider} in {@code LlmConfig}) — this class owns only the tool
 * definition, per-session call budget, and per-query result cache, identical regardless of which
 * backend answers the search.
 */
public class WebSearchTool implements WebTool {

    private static final Logger log = LoggerFactory.getLogger(WebSearchTool.class);

    static final String TOOL_NAME = "web_search";

    private final int           maxCallsPerSession;
    private final int           maxResults;
    private final SearchBackend backend;

    public WebSearchTool(int maxCallsPerSession, int maxResults) {
        this(maxCallsPerSession, maxResults, new DuckDuckGoSearchBackend());
    }

    public WebSearchTool(int maxCallsPerSession, int maxResults, SearchBackend backend) {
        this.maxCallsPerSession = maxCallsPerSession;
        this.maxResults         = maxResults;
        this.backend            = backend;
    }

    @Override
    public List<ToolDefinition> definitions() {
        return List.of(definition());
    }

    ToolDefinition definition() {
        ObjectNode schema = JsonNodeFactory.instance.objectNode();
        schema.put("type", "object");
        ObjectNode props = schema.putObject("properties");
        ObjectNode queryProp = props.putObject("query");
        queryProp.put("type", "string");
        queryProp.put("description", "The search query, e.g. 'Estonia motor vehicle tax rates official'.");
        schema.putArray("required").add("query");
        return new ToolDefinition(
                TOOL_NAME,
                "Searches the public web and returns a ranked list of result titles, URLs, and " +
                "snippets. Use this FIRST to find the authoritative page (government site, official " +
                "documentation) for domain rules, tax rates, or formulas, then pass the best URL to " +
                "web_fetch. Prefer searching over guessing URLs.",
                schema);
    }

    @Override
    public ToolExecutor newExecutor() {
        return new SearchExecutor();
    }

    // ── Executor ──────────────────────────────────────────────────────────────

    /** Per-session executor enforcing a search-call budget, with a per-query result cache. */
    final class SearchExecutor implements ToolExecutor {

        private final AtomicInteger       remaining = new AtomicInteger(maxCallsPerSession);
        private final Map<String, String> cache     = new ConcurrentHashMap<>();

        @Override
        public String execute(ToolCall call) {
            String query = call.arguments().path("query").asText("").strip();
            if (query.isEmpty())
                return "[web_search: missing 'query' argument]";

            String cached = cache.get(query);
            if (cached != null) {
                log.info("WebSearchTool: returning cached results for '{}' (no budget consumed)", query);
                return cached;
            }

            int left = remaining.getAndDecrement();
            if (left <= 0) {
                log.warn("WebSearchTool: per-session search limit ({}) exhausted", maxCallsPerSession);
                return "[Web search limit reached for this generation session]";
            }
            log.info("WebSearchTool: searching '{}' ({} searches remaining)", query, left - 1);
            String result = searchSafe(query);
            cache.put(query, result);
            return result;
        }
    }

    // ── Search ────────────────────────────────────────────────────────────────

    // package-private and overridable so unit tests can supply canned results without a network call.
    String searchSafe(String query) {
        return backend.search(query, maxResults);
    }
}
