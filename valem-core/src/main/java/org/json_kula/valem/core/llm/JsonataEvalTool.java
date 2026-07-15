package org.json_kula.valem.core.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.json_kula.jsonata_jvm.JsonataExpression;
import org.json_kula.valem.core.engine.ExpressionCache;
import org.json_kula.valem.core.llm.LlmClient.ToolCall;
import org.json_kula.valem.core.llm.LlmClient.ToolDefinition;
import org.json_kula.valem.core.llm.LlmClient.ToolExecutor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * A {@link WebTool} that lets the LLM evaluate a candidate JSONata expression against a sample input
 * <em>during</em> spec generation, turning the otherwise blind "write expr → validate → re-prompt"
 * loop into in-context verification. The model can confirm an {@code expr} compiles and produces the
 * value it intends before committing it to a derivation/constraint — catching syntax mistakes
 * (parens, lambda bodies, operators) and wrong values in place rather than across LLM round-trips.
 *
 * <p>Backed by the same {@link ExpressionCache} the runtime uses to compile expressions, so a tool
 * call exercises the exact compiler the spec will be validated against. Despite implementing
 * {@code WebTool} it makes no network call; it is composed alongside the web tools so the generation
 * tool-calling loop offers all of them at once.
 */
public class JsonataEvalTool implements WebTool {

    private static final Logger log = LoggerFactory.getLogger(JsonataEvalTool.class);

    static final String TOOL_NAME = "eval_jsonata";
    private static final int RESULT_MAX_CHARS = 2000;

    private final int maxCallsPerSession;

    public JsonataEvalTool(int maxCallsPerSession) {
        this.maxCallsPerSession = maxCallsPerSession;
    }

    @Override
    public List<ToolDefinition> definitions() {
        return List.of(definition());
    }

    ToolDefinition definition() {
        ObjectNode schema = JsonNodeFactory.instance.objectNode();
        schema.put("type", "object");
        ObjectNode props = schema.putObject("properties");
        ObjectNode exprProp = props.putObject("expr");
        exprProp.put("type", "string");
        exprProp.put("description",
                "A single JSONata expression to test, written exactly as it would appear in a "
                + "derivation/constraint 'expr': bare dot-paths, no leading $. "
                + "(e.g. \"loan.amount * loan.annualRate / 1200\").");
        ObjectNode inputProp = props.putObject("input");
        inputProp.put("type", "object");
        inputProp.put("description",
                "Sample document the expression runs against. Use the FULL nested shape your schema "
                + "defines (e.g. {\"loan\": {\"amount\": 20000, \"annualRate\": 5}}). Field names "
                + "resolve from this object's root, exactly like a non-wildcard derivation evaluating "
                + "against the merged document. Optional; defaults to an empty object.");
        schema.putArray("required").add("expr");
        return new ToolDefinition(
                TOOL_NAME,
                "Evaluate a candidate JSONata expression against a sample input and return the "
                + "computed value, or the exact compiler/runtime error. Use this to VERIFY every "
                + "non-trivial derivation or constraint expression BEFORE writing it into the spec: "
                + "confirm it compiles and produces the value you expect, then fix syntax "
                + "(parentheses, lambda { } bodies, operators) and logic in place instead of guessing.",
                schema);
    }

    @Override
    public ToolExecutor newExecutor() {
        return new EvalExecutor();
    }

    /** Per-session executor enforcing an eval-call budget, with a per-session compile cache. */
    final class EvalExecutor implements ToolExecutor {

        private final ExpressionCache cache     = new ExpressionCache();
        private final AtomicInteger   remaining = new AtomicInteger(maxCallsPerSession);

        @Override
        public String execute(ToolCall call) {
            JsonNode args = call.arguments();
            String expr = args.path("expr").asText("").strip();
            if (expr.isEmpty())
                return "[eval_jsonata: missing 'expr' argument]";

            JsonNode input = args.get("input");
            if (input == null || input.isNull() || input.isMissingNode())
                input = JsonNodeFactory.instance.objectNode();

            int left = remaining.getAndDecrement();
            if (left <= 0) {
                log.warn("JsonataEvalTool: per-session eval limit ({}) exhausted", maxCallsPerSession);
                return "[eval_jsonata limit reached for this generation session]";
            }
            log.info("JsonataEvalTool: evaluating expr ({} evals remaining)", left - 1);
            return evaluate(cache, expr, input);
        }
    }

    /**
     * Compiles and evaluates {@code expr} against {@code input}, returning a compact, model-friendly
     * line: {@code result: <json>}, {@code COMPILE ERROR: …}, or {@code EVALUATION ERROR: …}.
     * Package-private so it can be unit-tested without going through a {@link ToolCall}.
     */
    static String evaluate(ExpressionCache cache, String expr, JsonNode input) {
        JsonataExpression compiled;
        try {
            compiled = cache.get(expr);
        } catch (ExpressionCache.CompilationException ce) {
            return "COMPILE ERROR: " + ce.getMessage();
        }
        try {
            JsonNode result = compiled.evaluate(input);
            if (result == null || result.isMissingNode())
                return "result: undefined (the expression produced no value — check the field names "
                        + "exist in the input and match the schema)";
            return "result: " + truncate(result.toString());
        } catch (Exception ee) {
            return "EVALUATION ERROR: " + ee.getMessage();
        }
    }

    private static String truncate(String s) {
        return s.length() <= RESULT_MAX_CHARS ? s : s.substring(0, RESULT_MAX_CHARS) + "…(truncated)";
    }
}
