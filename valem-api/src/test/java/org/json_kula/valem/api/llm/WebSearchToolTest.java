package org.json_kula.valem.api.llm;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.json_kula.valem.core.llm.LlmClient.ToolCall;
import org.json_kula.valem.core.llm.LlmClient.ToolExecutor;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for WebSearchTool's own responsibilities — tool definition, per-session call budget,
 * and per-query result cache — independent of which {@link SearchBackend} answers the search.
 * Backend-specific parsing is tested separately: see {@link DuckDuckGoSearchBackendTest},
 * {@link BraveSearchBackendTest}, and {@link TavilySearchBackendTest}.
 */
class WebSearchToolTest {

    private final WebSearchTool tool = new WebSearchTool(3, 5);

    // ── Tool definition ───────────────────────────────────────────────────────

    @Test
    void definition_has_correct_name_and_query_schema() {
        var defs = tool.definitions();
        assertThat(defs).hasSize(1);
        var def = defs.get(0);
        assertThat(def.name()).isEqualTo(WebSearchTool.TOOL_NAME);
        assertThat(def.description()).isNotBlank();
        assertThat(def.inputSchema().path("properties").has("query")).isTrue();
    }

    // ── Executor budget / cache (no network) ──────────────────────────────────

    /** Subclass that returns canned results instead of hitting the network, counting invocations. */
    private static final class StubSearchTool extends WebSearchTool {
        int searchCount = 0;
        StubSearchTool(int maxCalls) { super(maxCalls, 5); }
        @Override
        String searchSafe(String query) { searchCount++; return "results for " + query; }
    }

    @Test
    void empty_query_does_not_consume_budget() {
        StubSearchTool t = new StubSearchTool(1);
        ToolExecutor ex = t.newExecutor();
        String r = ex.execute(call(""));
        assertThat(r).contains("missing 'query'");
        // budget intact: a real query still works
        assertThat(ex.execute(call("estonia tax"))).isEqualTo("results for estonia tax");
        assertThat(t.searchCount).isEqualTo(1);
    }

    @Test
    void executor_returns_limit_message_after_max_calls() {
        StubSearchTool t = new StubSearchTool(1);
        ToolExecutor ex = t.newExecutor();
        assertThat(ex.execute(call("first query"))).isEqualTo("results for first query");
        assertThat(ex.execute(call("second query"))).contains("limit reached");
        assertThat(t.searchCount).isEqualTo(1); // the over-limit call never ran the search
    }

    @Test
    void repeated_query_is_cached_and_does_not_consume_budget() {
        StubSearchTool t = new StubSearchTool(1);
        ToolExecutor ex = t.newExecutor();
        String first  = ex.execute(call("estonia tax"));
        String second = ex.execute(call("estonia tax")); // same → cache hit
        assertThat(second).isEqualTo(first);
        assertThat(t.searchCount).isEqualTo(1);
    }

    @Test
    void each_new_executor_has_its_own_budget() {
        StubSearchTool t = new StubSearchTool(1);
        ToolExecutor ex1 = t.newExecutor();
        ToolExecutor ex2 = t.newExecutor();
        ex1.execute(call("a"));
        assertThat(ex1.execute(call("b"))).contains("limit reached");
        assertThat(ex2.execute(call("c"))).isEqualTo("results for c"); // fresh budget
    }

    @Test
    void delegates_to_injected_backend() {
        SearchBackend backend = (query, maxResults) -> "backend saw: " + query + " / " + maxResults;
        WebSearchTool t = new WebSearchTool(3, 7, backend);
        assertThat(t.searchSafe("hello")).isEqualTo("backend saw: hello / 7");
    }

    private static ToolCall call(String query) {
        ObjectNode args = JsonNodeFactory.instance.objectNode();
        args.put("query", query);
        return new ToolCall("id1", WebSearchTool.TOOL_NAME, args);
    }
}
