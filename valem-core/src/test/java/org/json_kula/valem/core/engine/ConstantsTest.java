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
        // Each derivation reads an input (subtotal) AND a constant. A value derived purely from
        // constants has no dependency at all; see constant_only_derivation_* below for that case.
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

    @Test
    void constant_only_derivation_is_computed_at_initialize() throws Exception {
        // No field feeds $.annualFee, so dirty propagation can never reach it. initialize() is the
        // one moment it can be evaluated — before this it stayed absent forever, and every consumer
        // downstream of it silently collapsed to 0.
        ModelRuntime rt = runtime("""
            { "id": "m", "schema": {},
              "constants": { "monthlyFee": 291 },
              "defaultValues": [ { "path": "$", "expr": "{ \\"revenue\\": 30000 }" } ],
              "derivations": [
                { "path": "$.annualFee", "expr": "$const.monthlyFee * 12" },
                { "path": "$.net",       "expr": "revenue - annualFee" }
              ] }
            """);

        rt.initialize();

        assertThat(rt.getValue("$.annualFee").asInt()).isEqualTo(3492);
        assertThat(rt.getValue("$.net").asInt()).isEqualTo(26508);

        // And it survives a later mutation of an unrelated base field.
        rt.mutate(Map.of("$.revenue", F.numberNode(40000)));
        assertThat(rt.getValue("$.annualFee").asInt()).isEqualTo(3492);
        assertThat(rt.getValue("$.net").asInt()).isEqualTo(36508);
    }

    @Test
    void constant_only_derivation_is_computed_without_any_default_values() throws Exception {
        // initialize() used to short-circuit when nothing was defaulted; a model whose only
        // creation-time work is a constant-only derivation has to run the cycle anyway.
        ModelRuntime rt = runtime("""
            { "id": "m", "schema": {},
              "constants": { "monthlyFee": 88.64 },
              "derivations": [ { "path": "$.annualFee", "expr": "$round($const.monthlyFee * 12, 2)" } ] }
            """);

        rt.initialize();

        assertThat(rt.getValue("$.annualFee").asDouble()).isEqualTo(1063.68);
    }

    private ModelRuntime runtime(String specJson) throws Exception {
        ModelSpec spec = MAPPER.readValue(specJson, ModelSpec.class);
        CompiledModel model = ModelSpecCompiler.compile(spec);
        ModelState state = new ModelState(model, new InMemoryBlobStore());
        return new ModelRuntime(model, state);
    }
}
