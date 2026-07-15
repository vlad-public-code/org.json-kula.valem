package org.json_kula.valem.core.engine;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import org.json_kula.valem.core.blob.InMemoryBlobStore;
import org.json_kula.valem.core.graph.CompiledModel;
import org.json_kula.valem.core.graph.ModelSpecCompiler;
import org.json_kula.valem.core.model.ModelSpec;
import org.json_kula.valem.core.state.ModelState;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MetaDerivationEvaluatorTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final JsonNodeFactory NF = JsonNodeFactory.instance;

    // ── elementPathOf ─────────────────────────────────────────────────────────

    @Test
    void elementPathOf_extracts_element_path_from_concrete_and_pattern() {
        assertThat(MetaDerivationEvaluator.elementPathOf("$.items[0].qty", "$.items[*].qty"))
                .isEqualTo("$.items[0]");
        assertThat(MetaDerivationEvaluator.elementPathOf("$.order.lines[2].price", "$.order.lines[*].price"))
                .isEqualTo("$.order.lines[2]");
    }

    @Test
    void elementPathOf_returns_concretePath_when_no_wildcard_in_pattern() {
        assertThat(MetaDerivationEvaluator.elementPathOf("$.order.qty", "$.order.qty"))
                .isEqualTo("$.order.qty");
    }

    // ── Non-wildcard meta derivation ──────────────────────────────────────────

    @Test
    void non_wildcard_meta_derives_from_full_document() throws Exception {
        // Expression "config.limit" references a global field — should use full base doc
        ModelRuntime rt = runtime("""
                {
                  "id": "m",
                  "schema": {},
                  "metaDerivations": [
                    { "path": "$.qty", "property": "maximum", "expr": "config.limit" }
                  ]
                }
                """);

        rt.mutate("$.config.limit", NF.numberNode(50.0));

        assertThat(rt.effectiveSchema("$.qty").path("maximum").asDouble()).isEqualTo(50.0);
    }

    // ── Array-scoped per-element meta derivation ──────────────────────────────

    @Test
    void array_scoped_meta_evaluates_per_element_with_element_context() throws Exception {
        // Expression "cap" is a bare field reference; evaluated per-element it reads
        // each element's own "cap" property — different elements get different maxima.
        ModelRuntime rt = runtime("""
                {
                  "id": "m",
                  "schema": {},
                  "metaDerivations": [
                    { "path": "$.items[*].qty", "property": "maximum", "expr": "cap" }
                  ]
                }
                """);

        // Populate two items with distinct cap values
        rt.mutate(Map.of(
                "$.items[0].qty", NF.numberNode(1),
                "$.items[0].cap", NF.numberNode(10)));
        rt.mutate(Map.of(
                "$.items[1].qty", NF.numberNode(1),
                "$.items[1].cap", NF.numberNode(20)));

        // Mutating "$.cap" makes "$.items[*].qty#maximum" dirty (dependency edge: $.cap → meta node)
        rt.mutate("$.cap", NF.numberNode(0));

        assertThat(rt.effectiveSchema("$.items[0].qty").path("maximum").asDouble()).isEqualTo(10.0);
        assertThat(rt.effectiveSchema("$.items[1].qty").path("maximum").asDouble()).isEqualTo(20.0);
    }

    @Test
    void array_scoped_per_element_maximum_is_enforced_in_schema_validation() throws Exception {
        ModelRuntime rt = runtime("""
                {
                  "id": "m",
                  "schema": {},
                  "metaDerivations": [
                    { "path": "$.items[*].qty", "property": "maximum", "expr": "cap" }
                  ]
                }
                """);

        rt.mutate(Map.of(
                "$.items[0].qty", NF.numberNode(1),
                "$.items[0].cap", NF.numberNode(10)));
        rt.mutate(Map.of(
                "$.items[1].qty", NF.numberNode(1),
                "$.items[1].cap", NF.numberNode(20)));
        rt.mutate("$.cap", NF.numberNode(0)); // trigger meta evaluation

        // Element 0 max is 10 — value of 15 must be rejected
        assertThatThrownBy(() -> rt.mutate("$.items[0].qty", NF.numberNode(15)))
                .isInstanceOf(SchemaViolationException.class)
                .satisfies(ex -> assertThat(((SchemaViolationException) ex)
                        .violations().getFirst().keyword()).isEqualTo("maximum"));

        // Element 1 max is 20 — value of 15 is within bounds
        rt.mutate("$.items[1].qty", NF.numberNode(15));
        assertThat(rt.getValue("$.items[1].qty").asDouble()).isEqualTo(15.0);
    }

    @Test
    void array_scoped_single_element_gets_its_own_maximum() throws Exception {
        ModelRuntime rt = runtime("""
                {
                  "id": "m",
                  "schema": {},
                  "metaDerivations": [
                    { "path": "$.rows[*].val", "property": "minimum", "expr": "floor" }
                  ]
                }
                """);

        rt.mutate(Map.of("$.rows[0].val", NF.numberNode(5), "$.rows[0].floor", NF.numberNode(3)));
        rt.mutate("$.floor", NF.numberNode(0)); // trigger

        assertThat(rt.effectiveSchema("$.rows[0].val").path("minimum").asDouble()).isEqualTo(3.0);
    }

    // ── Helper ────────────────────────────────────────────────────────────────

    private ModelRuntime runtime(String specJson) throws Exception {
        ModelSpec spec = MAPPER.readValue(specJson, ModelSpec.class);
        CompiledModel model = ModelSpecCompiler.compile(spec);
        return new ModelRuntime(model, new ModelState(model, new InMemoryBlobStore()));
    }
}
