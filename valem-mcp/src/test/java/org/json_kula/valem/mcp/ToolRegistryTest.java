package org.json_kula.valem.mcp;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.json_kula.valem.core.blob.InMemoryBlobStore;
import org.json_kula.valem.service.ModelRegistry;
import org.json_kula.valem.service.ModelService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ToolRegistryTest {

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
            .configure(DeserializationFeature.READ_ENUMS_USING_TO_STRING, false)
            .configure(SerializationFeature.FAIL_ON_EMPTY_BEANS, false);

    private static final String SIMPLE_SPEC = """
            {
              "id": "mcp-test",
              "version": "1.0.0",
              "schema": {},
              "derivations": [ {"path": "$.total", "expr": "price * qty"} ],
              "constraints": [], "metaDerivations": [], "tests": []
            }
            """;

    private static final String CONSTRAINED_SPEC = """
            {
              "id": "mcp-capped",
              "version": "1.0.0",
              "schema": {},
              "derivations": [ {"path": "$.total", "expr": "price * qty"} ],
              "constraints": [ {"id": "cap", "expr": "total <= 100", "message": "over cap", "policy": "rollback"} ],
              "metaDerivations": [], "tests": []
            }
            """;

    private ToolRegistry registry;

    @BeforeEach
    void setUp() {
        ModelService service = new ModelService(new ModelRegistry(), new InMemoryBlobStore());
        registry = new ToolRegistry(service, MAPPER);
    }

    // ── Tool catalogue ───────────────────────────────────────────────────────────

    @Test
    void exposes_the_expected_tool_surface() {
        assertThat(registry.toolNames()).containsExactlyInAnyOrder(
                "list_models", "create_model", "get_model_info", "get_spec",
                "get_state", "get_field", "mutate", "explain", "get_history",
                "evolve_spec", "delete_model", "get_view",
                "validate_spec", "eval_expression", "test_spec", "dry_run");
    }

    @Test
    void listNode_entries_have_name_description_and_object_input_schema() {
        JsonNode list = registry.listNode();
        assertThat(list.isArray()).isTrue();
        assertThat(list.size()).isEqualTo(registry.toolNames().size());
        for (JsonNode tool : list) {
            assertThat(tool.path("name").asText()).isNotBlank();
            assertThat(tool.path("description").asText()).isNotBlank();
            assertThat(tool.path("inputSchema").path("type").asText()).isEqualTo("object");
        }
    }

    @Test
    void mutate_input_schema_declares_id_and_mutations_required() {
        JsonNode mutate = toolByName("mutate");
        JsonNode required = mutate.path("inputSchema").path("required");
        assertThat(elems(required)).containsExactlyInAnyOrder("id", "mutations");
    }

    // ── Successful calls ─────────────────────────────────────────────────────────

    @Test
    void create_model_returns_model_info() {
        ObjectNode result = createModel();
        assertThat(result.path("isError").asBoolean()).isFalse();
        JsonNode payload = payload(result);
        assertThat(payload.path("id").asText()).isEqualTo("mcp-test");
        assertThat(payload.path("derivationCount").asInt()).isEqualTo(1);
        // object result → structuredContent present
        assertThat(result.has("structuredContent")).isTrue();
    }

    @Test
    void list_models_reflects_created_model() {
        createModel();
        ObjectNode result = registry.call("list_models", MAPPER.createObjectNode());
        assertThat(payload(result).elements().next().asText()).isEqualTo("mcp-test");
    }

    @Test
    void mutate_runs_reactive_pipeline_and_get_state_reflects_derived_value() {
        createModel();

        ObjectNode args = MAPPER.createObjectNode();
        args.put("id", "mcp-test");
        args.putObject("mutations").put("$.price", 5.0).put("$.qty", 4);
        ObjectNode mutateResult = registry.call("mutate", args);
        assertThat(mutateResult.path("isError").asBoolean()).isFalse();
        assertThat(payload(mutateResult).path("success").asBoolean()).isTrue();

        ObjectNode stateArgs = MAPPER.createObjectNode();
        stateArgs.put("id", "mcp-test");
        JsonNode state = payload(registry.call("get_state", stateArgs));
        assertThat(state.path("price").asDouble()).isEqualTo(5.0);
        assertThat(state.path("total").asDouble()).isEqualTo(20.0);
    }

    @Test
    void get_field_evaluates_derived_field() {
        createModel();
        ObjectNode args = MAPPER.createObjectNode();
        args.put("id", "mcp-test");
        args.putObject("mutations").put("$.price", 7.0).put("$.qty", 2);
        registry.call("mutate", args);

        ObjectNode fieldArgs = MAPPER.createObjectNode();
        fieldArgs.put("id", "mcp-test");
        fieldArgs.put("path", "$.total");
        assertThat(payload(registry.call("get_field", fieldArgs)).asDouble()).isEqualTo(14.0);
    }

    @Test
    void get_model_info_reports_counts() {
        createModel();
        ObjectNode args = MAPPER.createObjectNode();
        args.put("id", "mcp-test");
        JsonNode info = payload(registry.call("get_model_info", args));
        assertThat(info.path("id").asText()).isEqualTo("mcp-test");
        assertThat(info.path("derivationCount").asInt()).isEqualTo(1);
    }

    @Test
    void get_history_lists_a_timestamp_after_mutation() {
        createModel();
        ObjectNode args = MAPPER.createObjectNode();
        args.put("id", "mcp-test");
        args.putObject("mutations").put("$.price", 1.0);
        registry.call("mutate", args);

        ObjectNode histArgs = MAPPER.createObjectNode();
        histArgs.put("id", "mcp-test");
        assertThat(payload(registry.call("get_history", histArgs)).size()).isEqualTo(1);
    }

    @Test
    void evolve_spec_adds_a_derivation() {
        createModel();
        ObjectNode args = MAPPER.createObjectNode();
        args.put("id", "mcp-test");
        args.putObject("evolution").putArray("upsertDerivations").addObject()
                .put("path", "$.doubleTotal").put("expr", "total * 2");
        ObjectNode result = registry.call("evolve_spec", args);
        assertThat(result.path("isError").asBoolean()).isFalse();

        ObjectNode infoArgs = MAPPER.createObjectNode();
        infoArgs.put("id", "mcp-test");
        assertThat(payload(registry.call("get_model_info", infoArgs)).path("derivationCount").asInt())
                .isEqualTo(2);
    }

    @Test
    void delete_model_removes_it() {
        createModel();
        ObjectNode args = MAPPER.createObjectNode();
        args.put("id", "mcp-test");
        assertThat(payload(registry.call("delete_model", args)).path("deleted").asBoolean()).isTrue();
        assertThat(payload(registry.call("list_models", MAPPER.createObjectNode())).isEmpty()).isTrue();
    }

    // ── structuredContent (improvement 1) ────────────────────────────────────────

    @Test
    void object_results_include_structuredContent_matching_the_text() {
        createModel();
        ObjectNode args = MAPPER.createObjectNode();
        args.put("id", "mcp-test");
        args.putObject("mutations").put("$.price", 5.0).put("$.qty", 4);
        registry.call("mutate", args);

        ObjectNode stateArgs = MAPPER.createObjectNode();
        stateArgs.put("id", "mcp-test");
        ObjectNode result = registry.call("get_state", stateArgs);

        assertThat(result.has("structuredContent")).isTrue();
        JsonNode structured = result.path("structuredContent");
        assertThat(structured.path("total").asDouble()).isEqualTo(20.0);
        // structuredContent conveys the same data as the text content block (compared by value —
        // JSONata numerics can differ in node type, e.g. DecimalNode vs a reparsed DoubleNode)
        JsonNode text = payload(result);
        assertThat(structured.path("price").asDouble()).isEqualTo(text.path("price").asDouble());
        assertThat(structured.path("qty").asInt()).isEqualTo(text.path("qty").asInt());
        assertThat(structured.path("total").asDouble()).isEqualTo(text.path("total").asDouble());
    }

    @Test
    void array_results_omit_structuredContent_but_keep_text() {
        createModel();
        ObjectNode result = registry.call("list_models", MAPPER.createObjectNode());
        assertThat(result.has("structuredContent")).isFalse();
        assertThat(payload(result).elements().next().asText()).isEqualTo("mcp-test");
    }

    // ── structured constraint violations (improvement 2) ──────────────────────────

    @Test
    void rollback_constraint_violation_is_surfaced_structurally() {
        createConstrained();

        ObjectNode ok = MAPPER.createObjectNode();
        ok.put("id", "mcp-capped");
        ok.putObject("mutations").put("$.price", 10).put("$.qty", 3);   // total 30, ok
        assertThat(registry.call("mutate", ok).path("isError").asBoolean()).isFalse();

        ObjectNode bad = MAPPER.createObjectNode();
        bad.put("id", "mcp-capped");
        bad.putObject("mutations").put("$.qty", 50);                    // total 500 > 100
        ObjectNode result = registry.call("mutate", bad);

        assertThat(result.path("isError").asBoolean()).isTrue();
        JsonNode structured = result.path("structuredContent");
        assertThat(structured.path("error").asText()).isEqualTo("Constraint violation");
        JsonNode v0 = structured.path("violations").get(0);
        assertThat(v0.path("constraintId").asText()).isEqualTo("cap");
        assertThat(v0.path("message").asText()).isEqualTo("over cap");
        assertThat(v0.path("policy").asText()).isEqualTo("rollback");
    }

    // ── embedded ModelSpec schema (improvement 3) ─────────────────────────────────

    @Test
    void create_model_input_schema_embeds_the_real_modelspec_schema() {
        JsonNode specSchema = toolByName("create_model").path("inputSchema").path("properties").path("spec");
        assertThat(specSchema.path("type").asText()).isEqualTo("object");
        assertThat(specSchema.path("properties").has("derivations")).isTrue();
        assertThat(specSchema.path("properties").has("constraints")).isTrue();
        assertThat(specSchema.path("description").asText()).isNotBlank();
    }

    @Test
    void evolve_spec_input_schema_embeds_the_real_evolution_schema() {
        JsonNode evoSchema = toolByName("evolve_spec").path("inputSchema").path("properties").path("evolution");
        assertThat(evoSchema.path("type").asText()).isEqualTo("object");
        assertThat(evoSchema.path("properties").has("upsertDerivations")).isTrue();
    }

    // ── tool annotations + titles (improvement 4) ─────────────────────────────────

    @Test
    void tools_carry_titles_and_safety_annotations() {
        JsonNode getState = toolByName("get_state");
        assertThat(getState.path("title").asText()).isEqualTo("Get state");
        assertThat(getState.path("annotations").path("readOnlyHint").asBoolean()).isTrue();

        JsonNode del = toolByName("delete_model");
        assertThat(del.path("annotations").path("destructiveHint").asBoolean()).isTrue();
        assertThat(del.path("annotations").path("readOnlyHint").asBoolean()).isFalse();

        JsonNode mutate = toolByName("mutate");
        assertThat(mutate.path("annotations").path("readOnlyHint").asBoolean()).isFalse();
        assertThat(mutate.path("annotations").path("openWorldHint").asBoolean()).isFalse();

        JsonNode evolve = toolByName("evolve_spec");
        assertThat(evolve.path("annotations").path("destructiveHint").asBoolean()).isTrue();
    }

    // ── validate_spec / eval_expression / test_spec / dry_run (authoring tools) ───

    @Test
    void validate_spec_reports_valid_for_a_good_spec() throws Exception {
        ObjectNode args = MAPPER.createObjectNode();
        args.set("spec", MAPPER.readTree(SIMPLE_SPEC));
        ObjectNode result = registry.call("validate_spec", args);
        assertThat(result.path("isError").asBoolean()).isFalse();
        JsonNode payload = payload(result);
        assertThat(payload.path("valid").asBoolean()).isTrue();
        assertThat(payload.path("errors").isArray()).isTrue();
        assertThat(payload.path("errors")).isEmpty();
        // does NOT register the model
        assertThat(payload(registry.call("list_models", MAPPER.createObjectNode()))).isEmpty();
    }

    @Test
    void validate_spec_reports_errors_with_locations_for_a_bad_spec() throws Exception {
        // a derivation with an unparseable JSONata expr
        String bad = """
                { "id": "bad", "version": "1.0.0", "schema": {},
                  "derivations": [ {"path": "$.x", "expr": "price *"} ],
                  "constraints": [], "metaDerivations": [], "tests": [] }
                """;
        ObjectNode args = MAPPER.createObjectNode();
        args.set("spec", MAPPER.readTree(bad));
        JsonNode payload = payload(registry.call("validate_spec", args));
        assertThat(payload.path("valid").asBoolean()).isFalse();
        assertThat(payload.path("errors").size()).isGreaterThan(0);
        assertThat(payload.path("errors").get(0).path("location").asText()).isNotBlank();
    }

    @Test
    void eval_expression_returns_the_computed_value() {
        ObjectNode args = MAPPER.createObjectNode();
        args.put("expr", "price * qty");
        args.putObject("input").put("price", 4).put("qty", 5);
        JsonNode payload = payload(registry.call("eval_expression", args));
        assertThat(payload.path("ok").asBoolean()).isTrue();
        assertThat(payload.path("result").asInt()).isEqualTo(20);
    }

    @Test
    void eval_expression_reports_a_compile_error() {
        ObjectNode args = MAPPER.createObjectNode();
        args.put("expr", "price *");   // syntactically invalid
        args.putObject("input").put("price", 4);
        ObjectNode result = registry.call("eval_expression", args);
        // a compile error is a normal (non-isError) structured result the agent can act on
        assertThat(result.path("isError").asBoolean()).isFalse();
        JsonNode payload = payload(result);
        assertThat(payload.path("ok").asBoolean()).isFalse();
        assertThat(payload.path("error").asText()).isEqualTo("compile");
    }

    @Test
    void test_spec_runs_embedded_tests() throws Exception {
        String withTests = """
                { "id": "tested", "version": "1.0.0", "schema": {},
                  "derivations": [ {"path": "$.total", "expr": "price * qty"} ],
                  "constraints": [], "metaDerivations": [],
                  "tests": [
                    { "description": "2x3=6", "given": {"$.price": 2, "$.qty": 3}, "expect": {"$.total": 6} },
                    { "description": "wrong", "given": {"$.price": 2, "$.qty": 3}, "expect": {"$.total": 99} }
                  ] }
                """;
        ObjectNode args = MAPPER.createObjectNode();
        args.set("spec", MAPPER.readTree(withTests));
        JsonNode payload = payload(registry.call("test_spec", args));
        assertThat(payload.path("total").asInt()).isEqualTo(2);
        assertThat(payload.path("passed").asInt()).isEqualTo(1);
        assertThat(payload.path("failed").asInt()).isEqualTo(1);
        // the failing case reports the field failure (find it regardless of result ordering)
        JsonNode failing = null;
        for (JsonNode r : payload.path("results")) {
            if (!r.path("passed").asBoolean()) { failing = r; break; }
        }
        assertThat(failing).isNotNull();
        assertThat(failing.path("failures").get(0).path("path").asText()).contains("$.total");
    }

    @Test
    void dry_run_returns_merged_state_without_registering() throws Exception {
        ObjectNode args = MAPPER.createObjectNode();
        args.set("spec", MAPPER.readTree(SIMPLE_SPEC));
        args.putObject("mutations").put("$.price", 6).put("$.qty", 7);
        JsonNode state = payload(registry.call("dry_run", args));
        assertThat(state.path("total").asInt()).isEqualTo(42);
        // dry_run must NOT leak into the live registry
        assertThat(payload(registry.call("list_models", MAPPER.createObjectNode()))).isEmpty();
    }

    // ── Error handling (isError results, not thrown) ──────────────────────────────

    @Test
    void unknown_tool_returns_isError_result() {
        ObjectNode result = registry.call("no_such_tool", MAPPER.createObjectNode());
        assertThat(result.path("isError").asBoolean()).isTrue();
        assertThat(textOf(result)).contains("Unknown tool");
    }

    @Test
    void call_on_missing_model_returns_isError_result() {
        ObjectNode args = MAPPER.createObjectNode();
        args.put("id", "does-not-exist");
        ObjectNode result = registry.call("get_state", args);
        assertThat(result.path("isError").asBoolean()).isTrue();
        assertThat(textOf(result)).isNotBlank();
    }

    @Test
    void missing_required_argument_returns_isError_result() {
        ObjectNode result = registry.call("get_state", MAPPER.createObjectNode());
        assertThat(result.path("isError").asBoolean()).isTrue();
        assertThat(textOf(result)).contains("id");
    }

    @Test
    void invalid_spec_returns_isError_result() {
        ObjectNode args = MAPPER.createObjectNode();
        args.putObject("spec").put("version", "1");  // no id → invalid
        ObjectNode result = registry.call("create_model", args);
        assertThat(result.path("isError").asBoolean()).isTrue();
    }

    // ── helpers ────────────────────────────────────────────────────────────────

    private ObjectNode createModel() {
        return create(SIMPLE_SPEC);
    }

    private ObjectNode createConstrained() {
        return create(CONSTRAINED_SPEC);
    }

    private ObjectNode create(String specJson) {
        try {
            ObjectNode args = MAPPER.createObjectNode();
            args.set("spec", MAPPER.readTree(specJson));
            return registry.call("create_model", args);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    /** The single text content block's raw string. */
    private static String textOf(ObjectNode result) {
        return result.path("content").get(0).path("text").asText();
    }

    /** The text content block parsed back as JSON (tools serialise their payload as JSON text). */
    private JsonNode payload(ObjectNode result) {
        try {
            return MAPPER.readTree(textOf(result));
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private JsonNode toolByName(String name) {
        for (JsonNode t : registry.listNode()) {
            if (name.equals(t.path("name").asText())) return t;
        }
        throw new AssertionError("tool not found: " + name);
    }

    private static java.util.List<String> elems(JsonNode arr) {
        java.util.List<String> out = new java.util.ArrayList<>();
        arr.forEach(n -> out.add(n.asText()));
        return out;
    }
}
