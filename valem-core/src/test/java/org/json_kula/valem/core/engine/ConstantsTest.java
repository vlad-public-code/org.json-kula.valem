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

/** Named constants ($const) referenced from expressions in every evaluation context. */
class ConstantsTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final JsonNodeFactory F = JsonNodeFactory.instance;

    @Test
    void derivation_references_primitive_array_and_object_constants() throws Exception {
        // Each derivation reads an input (subtotal) AND a constant — a value derived purely from
        // constants has no dependency and would never be re-evaluated (same as a literal derivation).
        ModelRuntime rt = runtime("""
            { "id": "m", "schema": {},
              "constants": {
                "vatRate": 0.2,
                "tiers":   [10, 20, 30],
                "config":  { "threshold": 100 }
              },
              "derivations": [
                { "path": "$.tax",             "expr": "subtotal * $const.vatRate" },
                { "path": "$.totalWithTiers",  "expr": "subtotal + $sum($const.tiers)" },
                { "path": "$.meetsThreshold",  "expr": "subtotal >= $const.config.threshold" }
              ] }
            """);

        rt.mutate(Map.of("$.subtotal", F.numberNode(100)));

        assertThat(rt.getValue("$.tax").asDouble()).isEqualTo(20.0);
        assertThat(rt.getValue("$.totalWithTiers").asInt()).isEqualTo(160);
        assertThat(rt.getValue("$.meetsThreshold").asBoolean()).isTrue();
    }

    @Test
    void constant_backed_derivation_recomputes_when_base_field_changes() throws Exception {
        ModelRuntime rt = runtime("""
            { "id": "m", "schema": {},
              "constants": { "vatRate": 0.1 },
              "derivations": [ { "path": "$.tax", "expr": "subtotal * $const.vatRate" } ] }
            """);

        rt.mutate(Map.of("$.subtotal", F.numberNode(100)));
        assertThat(rt.getValue("$.tax").asDouble()).isEqualTo(10.0);
        rt.mutate(Map.of("$.subtotal", F.numberNode(200)));
        assertThat(rt.getValue("$.tax").asDouble()).isEqualTo(20.0);
    }

    @Test
    void constraint_references_constant() throws Exception {
        ModelRuntime rt = runtime("""
            { "id": "m", "schema": {},
              "constants": { "maxQty": 5 },
              "constraints": [
                { "id": "qty-max", "expr": "qty <= $const.maxQty",
                  "message": "too many", "policy": "rollback" }
              ] }
            """);

        rt.mutate(Map.of("$.qty", F.numberNode(3)));   // ok
        assertThatThrownBy(() -> rt.mutate(Map.of("$.qty", F.numberNode(9))))
                .isInstanceOf(ConstraintEvaluator.ConstraintViolationException.class);
    }

    @Test
    void default_value_rule_references_constant() throws Exception {
        ModelRuntime rt = runtime("""
            { "id": "m", "schema": {},
              "constants": { "startingBalance": 500 },
              "defaultValues": [
                { "path": "$", "expr": "{ \\"balance\\": $const.startingBalance }" }
              ] }
            """);

        rt.initialize();

        assertThat(rt.getValue("$.balance").asInt()).isEqualTo(500);
    }

    private ModelRuntime runtime(String specJson) throws Exception {
        ModelSpec spec = MAPPER.readValue(specJson, ModelSpec.class);
        CompiledModel model = ModelSpecCompiler.compile(spec);
        ModelState state = new ModelState(model, new InMemoryBlobStore());
        return new ModelRuntime(model, state);
    }
}
