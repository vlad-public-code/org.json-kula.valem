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

class SchemaValidationTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final JsonNodeFactory NF = JsonNodeFactory.instance;

    // ── readOnly ──────────────────────────────────────────────────────────────

    @Test
    void rejects_write_to_readOnly_field() throws Exception {
        ModelRuntime rt = runtime("""
                {
                  "id": "m",
                  "schema": {
                    "properties": {
                      "x": { "properties": { "locked": { "type": "number", "readOnly": true } } }
                    }
                  }
                }
                """);

        assertThatThrownBy(() -> rt.mutate("$.x.locked", NF.numberNode(1.0)))
                .isInstanceOf(SchemaViolationException.class)
                .satisfies(ex -> {
                    var sve = (SchemaViolationException) ex;
                    assertThat(sve.violations()).hasSize(1);
                    assertThat(sve.violations().getFirst().keyword()).isEqualTo("readOnly");
                    assertThat(sve.violations().getFirst().path()).isEqualTo("$.x.locked");
                });
    }

    @Test
    void allows_write_to_field_not_in_schema() throws Exception {
        ModelRuntime rt = runtime("""
                { "id": "m", "schema": { "properties": {} } }
                """);

        // No schema for this path → no constraints → allowed
        rt.mutate("$.anything.goes", NF.numberNode(42.0));
        assertThat(rt.getValue("$.anything.goes").asDouble()).isEqualTo(42.0);
    }

    // ── minimum / maximum ─────────────────────────────────────────────────────

    @Test
    void rejects_value_below_minimum() throws Exception {
        ModelRuntime rt = runtime("""
                {
                  "id": "m",
                  "schema": {
                    "properties": { "qty": { "type": "number", "minimum": 1 } }
                  }
                }
                """);

        assertThatThrownBy(() -> rt.mutate("$.qty", NF.numberNode(0)))
                .isInstanceOf(SchemaViolationException.class)
                .satisfies(ex -> {
                    var sve = (SchemaViolationException) ex;
                    assertThat(sve.violations().getFirst().keyword()).isEqualTo("minimum");
                });
    }

    @Test
    void allows_value_equal_to_minimum() throws Exception {
        ModelRuntime rt = runtime("""
                {
                  "id": "m",
                  "schema": { "properties": { "qty": { "type": "number", "minimum": 1 } } }
                }
                """);

        rt.mutate("$.qty", NF.numberNode(1.0));
        assertThat(rt.getValue("$.qty").asDouble()).isEqualTo(1.0);
    }

    @Test
    void rejects_value_above_maximum() throws Exception {
        ModelRuntime rt = runtime("""
                {
                  "id": "m",
                  "schema": {
                    "properties": { "score": { "type": "number", "maximum": 100 } }
                  }
                }
                """);

        assertThatThrownBy(() -> rt.mutate("$.score", NF.numberNode(101)))
                .isInstanceOf(SchemaViolationException.class)
                .satisfies(ex -> {
                    var sve = (SchemaViolationException) ex;
                    assertThat(sve.violations().getFirst().keyword()).isEqualTo("maximum");
                });
    }

    // ── minLength / maxLength ─────────────────────────────────────────────────

    @Test
    void rejects_string_shorter_than_minLength() throws Exception {
        ModelRuntime rt = runtime("""
                {
                  "id": "m",
                  "schema": {
                    "properties": { "code": { "type": "string", "minLength": 3 } }
                  }
                }
                """);

        assertThatThrownBy(() -> rt.mutate("$.code", NF.textNode("ab")))
                .isInstanceOf(SchemaViolationException.class)
                .satisfies(ex -> assertThat(((SchemaViolationException) ex)
                        .violations().getFirst().keyword()).isEqualTo("minLength"));
    }

    @Test
    void rejects_string_longer_than_maxLength() throws Exception {
        ModelRuntime rt = runtime("""
                {
                  "id": "m",
                  "schema": {
                    "properties": { "tag": { "type": "string", "maxLength": 5 } }
                  }
                }
                """);

        assertThatThrownBy(() -> rt.mutate("$.tag", NF.textNode("toolong")))
                .isInstanceOf(SchemaViolationException.class)
                .satisfies(ex -> assertThat(((SchemaViolationException) ex)
                        .violations().getFirst().keyword()).isEqualTo("maxLength"));
    }

    // ── pattern ───────────────────────────────────────────────────────────────

    @Test
    void rejects_string_not_matching_pattern() throws Exception {
        ModelRuntime rt = runtime("""
                {
                  "id": "m",
                  "schema": {
                    "properties": { "code": { "type": "string", "pattern": "[A-Z]{3}" } }
                  }
                }
                """);

        assertThatThrownBy(() -> rt.mutate("$.code", NF.textNode("abc")))
                .isInstanceOf(SchemaViolationException.class)
                .satisfies(ex -> assertThat(((SchemaViolationException) ex)
                        .violations().getFirst().keyword()).isEqualTo("pattern"));
    }

    @Test
    void allows_string_matching_pattern() throws Exception {
        ModelRuntime rt = runtime("""
                {
                  "id": "m",
                  "schema": {
                    "properties": { "code": { "type": "string", "pattern": "[A-Z]{3}" } }
                  }
                }
                """);

        rt.mutate("$.code", NF.textNode("ABC"));
        assertThat(rt.getValue("$.code").asText()).isEqualTo("ABC");
    }

    // ── enum ──────────────────────────────────────────────────────────────────

    @Test
    void rejects_value_not_in_enum() throws Exception {
        ModelRuntime rt = runtime("""
                {
                  "id": "m",
                  "schema": {
                    "properties": { "status": { "enum": ["pending", "approved", "rejected"] } }
                  }
                }
                """);

        assertThatThrownBy(() -> rt.mutate("$.status", NF.textNode("unknown")))
                .isInstanceOf(SchemaViolationException.class)
                .satisfies(ex -> assertThat(((SchemaViolationException) ex)
                        .violations().getFirst().keyword()).isEqualTo("enum"));
    }

    @Test
    void allows_value_in_enum() throws Exception {
        ModelRuntime rt = runtime("""
                {
                  "id": "m",
                  "schema": {
                    "properties": { "status": { "enum": ["pending", "approved", "rejected"] } }
                  }
                }
                """);

        rt.mutate("$.status", NF.textNode("approved"));
        assertThat(rt.getValue("$.status").asText()).isEqualTo("approved");
    }

    // ── Multiple violations collected ─────────────────────────────────────────

    @Test
    void collects_all_violations_across_mutation_map() throws Exception {
        ModelRuntime rt = runtime("""
                {
                  "id": "m",
                  "schema": {
                    "properties": {
                      "qty":   { "type": "number", "minimum": 1 },
                      "score": { "type": "number", "maximum": 100 }
                    }
                  }
                }
                """);

        assertThatThrownBy(() -> rt.mutate(Map.of(
                "$.qty",   NF.numberNode(0),
                "$.score", NF.numberNode(200))))
                .isInstanceOf(SchemaViolationException.class)
                .satisfies(ex -> assertThat(((SchemaViolationException) ex).violations()).hasSize(2));
    }

    // ── No state change on violation ──────────────────────────────────────────

    @Test
    void state_unchanged_when_schema_violation_thrown() throws Exception {
        ModelRuntime rt = runtime("""
                {
                  "id": "m",
                  "schema": { "properties": { "qty": { "type": "number", "minimum": 1 } } }
                }
                """);
        rt.mutate("$.qty", NF.numberNode(5.0));

        assertThatThrownBy(() -> rt.mutate("$.qty", NF.numberNode(0)))
                .isInstanceOf(SchemaViolationException.class);

        // Value must remain 5, not 0
        assertThat(rt.getValue("$.qty").asDouble()).isEqualTo(5.0);
    }

    // ── Meta-derivation overrides static schema ───────────────────────────────

    @Test
    void rejects_value_below_meta_derived_minimum() throws Exception {
        // $.qty#minimum is derived from $.config.minQty.
        // When config.minQty changes, the meta node becomes dirty and is evaluated.
        ModelRuntime rt = runtime("""
                {
                  "id": "m",
                  "schema": { "properties": { "qty": { "type": "number" } } },
                  "metaDerivations": [
                    { "path": "$.qty", "property": "minimum", "expr": "config.minQty" }
                  ]
                }
                """);

        // Setting minQty=10 makes $.qty#minimum dirty → evaluated to 10 in meta cache
        rt.mutate("$.config.minQty", NF.numberNode(10.0));

        // Next mutation of $.qty should be checked against the live minimum of 10
        assertThatThrownBy(() -> rt.mutate("$.qty", NF.numberNode(5.0)))
                .isInstanceOf(SchemaViolationException.class)
                .satisfies(ex -> assertThat(((SchemaViolationException) ex)
                        .violations().getFirst().keyword()).isEqualTo("minimum"));
    }

    // ── Helper ────────────────────────────────────────────────────────────────

    // ── $ref-typed fields are validated (M1 trap fix) ──────────────────────────

    @Test
    void validates_type_through_a_local_ref() throws Exception {
        // Before $ref support, a field typed via $ref resolved to an empty schema and every
        // write silently passed. Now the ref is resolved and the type is enforced.
        ModelRuntime rt = runtime("""
                {
                  "id": "m",
                  "schema": {
                    "type": "object",
                    "properties": { "qty": { "$ref": "#/$defs/Count" } },
                    "$defs": { "Count": { "type": "integer" } }
                  }
                }
                """);

        rt.mutate("$.qty", NF.numberNode(3));   // conforming write is fine

        assertThatThrownBy(() -> rt.mutate("$.qty", NF.textNode("three")))
                .isInstanceOf(SchemaViolationException.class)
                .satisfies(ex -> assertThat(((SchemaViolationException) ex)
                        .violations().getFirst().keyword()).isEqualTo("type"));
    }

    private ModelRuntime runtime(String specJson) throws Exception {
        ModelSpec spec = MAPPER.readValue(specJson, ModelSpec.class);
        CompiledModel model = ModelSpecCompiler.compile(spec);
        return new ModelRuntime(model, new ModelState(model, new InMemoryBlobStore()));
    }
}
