package org.json_kula.valem.api.llm;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.json_kula.valem.core.llm.LlmClient.ToolCall;
import org.json_kula.valem.core.llm.LlmClient.ToolDefinition;
import org.json_kula.valem.core.llm.LlmClient.ToolExecutor;
import org.json_kula.valem.core.llm.WebTool;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CompositeWebToolTest {

    // ── Stub tools that don't touch the network ───────────────────────────────

    private static ToolDefinition def(String name) {
        return new ToolDefinition(name, name + " description", JsonNodeFactory.instance.objectNode());
    }

    /** A web tool whose executor echoes which tool handled the call. */
    private static WebTool echoTool(String name) {
        return new WebTool() {
            @Override public List<ToolDefinition> definitions() { return List.of(def(name)); }
            @Override public ToolExecutor newExecutor() {
                return call -> name + ":" + call.arguments().path("q").asText("");
            }
        };
    }

    /** A web tool whose executor is a FactProvider, contributing one fact. */
    private static WebTool factTool(String name, WebFetchFact fact) {
        return new WebTool() {
            @Override public List<ToolDefinition> definitions() { return List.of(def(name)); }
            @Override public ToolExecutor newExecutor() {
                class FactEx implements ToolExecutor, FactProvider {
                    public String execute(ToolCall c) { return "ok"; }
                    public List<WebFetchFact> facts() { return List.of(fact); }
                }
                return new FactEx();
            }
        };
    }

    private static ToolCall call(String name, String q) {
        ObjectNode args = JsonNodeFactory.instance.objectNode();
        args.put("q", q);
        return new ToolCall("id", name, args);
    }

    // ── Definitions ───────────────────────────────────────────────────────────

    @Test
    void definitions_are_concatenated_in_order() {
        var composite = new CompositeWebTool(List.of(echoTool("web_search"), echoTool("web_fetch")));
        assertThat(composite.definitions()).extracting(ToolDefinition::name)
                .containsExactly("web_search", "web_fetch");
    }

    @Test
    void rejects_empty_tool_list() {
        assertThatThrownBy(() -> new CompositeWebTool(List.of()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // ── Routing ─────────────────────────────────────────────────────────────-

    @Test
    void routes_call_to_matching_tool_by_name() {
        var composite = new CompositeWebTool(List.of(echoTool("web_search"), echoTool("web_fetch")));
        ToolExecutor ex = composite.newExecutor();
        assertThat(ex.execute(call("web_search", "x"))).isEqualTo("web_search:x");
        assertThat(ex.execute(call("web_fetch", "y"))).isEqualTo("web_fetch:y");
    }

    @Test
    void unknown_tool_name_returns_message_not_exception() {
        var composite = new CompositeWebTool(List.of(echoTool("web_search")));
        ToolExecutor ex = composite.newExecutor();
        assertThat(ex.execute(call("does_not_exist", "z"))).contains("Unknown tool");
    }

    // ── Fact aggregation ──────────────────────────────────────────────────────

    @Test
    void aggregates_facts_from_fact_collecting_sub_executors() {
        WebFetchFact f1 = new WebFetchFact("https://a", 200, "text/html", 10, 5);
        var composite = new CompositeWebTool(List.of(
                echoTool("web_search"),          // not a FactProvider
                factTool("web_fetch", f1)));     // contributes f1
        ToolExecutor ex = composite.newExecutor();
        assertThat(ex).isInstanceOf(FactProvider.class);
        assertThat(((FactProvider) ex).facts()).containsExactly(f1);
    }

    @Test
    void each_executor_session_is_independent() {
        WebFetchFact f1 = new WebFetchFact("https://a", 200, "text/html", 10, 5);
        var composite = new CompositeWebTool(List.of(factTool("web_fetch", f1)));
        ToolExecutor a = composite.newExecutor();
        ToolExecutor b = composite.newExecutor();
        assertThat(a).isNotSameAs(b);
        assertThat(((FactProvider) a).facts()).containsExactly(f1);
        assertThat(((FactProvider) b).facts()).containsExactly(f1);
    }
}
