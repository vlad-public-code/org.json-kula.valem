package org.json_kula.valem.core.llm;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.json_kula.valem.core.llm.LlmClient.ToolCall;
import org.json_kula.valem.core.llm.LlmClient.ToolExecutor;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link JsonataEvalTool}. Exercises the real compile+evaluate path (no network,
 * no Spring) plus the per-session budget so the in-loop verification behaviour is covered.
 */
class JsonataEvalToolTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private final JsonataEvalTool tool = new JsonataEvalTool(25);

    // ── Tool definition ─────────────────────────────────────────────────────────

    @Test
    void definition_has_name_and_expr_schema() {
        var defs = tool.definitions();
        assertThat(defs).hasSize(1);
        var def = defs.get(0);
        assertThat(def.name()).isEqualTo(JsonataEvalTool.TOOL_NAME);
        assertThat(def.description()).isNotBlank();
        assertThat(def.inputSchema().path("properties").has("expr")).isTrue();
        assertThat(def.inputSchema().path("properties").has("input")).isTrue();
        assertThat(def.inputSchema().path("required").get(0).asText()).isEqualTo("expr");
    }

    // ── Evaluation ──────────────────────────────────────────────────────────────

    @Test
    void evaluates_expression_against_nested_input() throws Exception {
        var input = MAPPER.readTree("{\"loan\": {\"amount\": 100, \"annualRate\": 6}}");
        String out = tool.newExecutor().execute(call("loan.amount * loan.annualRate / 100", input));
        assertThat(out).isEqualTo("result: 6");
    }

    @Test
    void reports_compile_error_for_unbalanced_parens() {
        String out = tool.newExecutor().execute(call("(1 + 2", null));
        assertThat(out).startsWith("COMPILE ERROR:");
    }

    @Test
    void reports_undefined_when_field_is_missing() {
        String out = tool.newExecutor().execute(call("missing.field", null));
        assertThat(out).contains("undefined");
    }

    // ── Budget / arguments ──────────────────────────────────────────────────────

    @Test
    void missing_expr_does_not_consume_budget() {
        ToolExecutor ex = new JsonataEvalTool(1).newExecutor();
        assertThat(ex.execute(call("", null))).contains("missing 'expr'");
        // budget intact: a real eval still works
        assertThat(ex.execute(call("1 + 1", null))).isEqualTo("result: 2");
    }

    @Test
    void executor_returns_limit_message_after_max_calls() {
        ToolExecutor ex = new JsonataEvalTool(1).newExecutor();
        assertThat(ex.execute(call("1 + 1", null))).isEqualTo("result: 2");
        assertThat(ex.execute(call("2 + 2", null))).contains("limit reached");
    }

    @Test
    void each_new_executor_has_its_own_budget() {
        JsonataEvalTool t = new JsonataEvalTool(1);
        ToolExecutor ex1 = t.newExecutor();
        ToolExecutor ex2 = t.newExecutor();
        ex1.execute(call("1 + 1", null));
        assertThat(ex1.execute(call("2 + 2", null))).contains("limit reached");
        assertThat(ex2.execute(call("3 + 3", null))).isEqualTo("result: 6"); // fresh budget
    }

    private static ToolCall call(String expr, com.fasterxml.jackson.databind.JsonNode input) {
        ObjectNode args = JsonNodeFactory.instance.objectNode();
        args.put("expr", expr);
        if (input != null) args.set("input", input);
        return new ToolCall("id1", JsonataEvalTool.TOOL_NAME, args);
    }
}
