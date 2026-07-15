package org.json_kula.valem.core.graph;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.json_kula.valem.core.model.ModelSpec;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SpecEvolutionSchemaTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private ModelSpec base(String json) throws Exception {
        return MAPPER.readValue(json, ModelSpec.class);
    }

    private SpecEvolution diff(String json) throws Exception {
        return MAPPER.readValue(json, SpecEvolution.class);
    }

    private static final String ORDER_SCHEMA = """
            {
              "id": "m",
              "schema": {
                "type": "object",
                "properties": {
                  "order": {
                    "type": "object",
                    "properties": {
                      "items": {
                        "type": "array",
                        "items": {
                          "type": "object",
                          "properties": {
                            "qty":  { "type": "integer" },
                            "name": { "type": "string" }
                          }
                        }
                      },
                      "total": { "type": "number" }
                    }
                  }
                }
              }
            }
            """;

    // ── Headline: retype one node, siblings untouched ──────────────────────────

    @Test
    void node_upsert_retypes_only_the_target_leaving_siblings_identical() throws Exception {
        ModelSpec src = base(ORDER_SCHEMA);
        JsonNode before = src.schema().deepCopy();

        ModelSpec evolved = diff("""
                { "upsertSchemaNodes": [
                    { "path": "$.order.items[*].qty", "schema": { "type": "string", "pattern": "^[0-9]+$" } }
                ]}
                """).applyTo(src);

        JsonNode s = evolved.schema();
        assertThat(SchemaPaths.resolve(s, "$.order.items[*].qty").path("type").asText()).isEqualTo("string");
        // siblings unchanged
        assertThat(SchemaPaths.resolve(s, "$.order.items[*].name").path("type").asText()).isEqualTo("string");
        assertThat(SchemaPaths.resolve(s, "$.order.total").path("type").asText()).isEqualTo("number");
        // base spec object was not mutated in place
        assertThat(src.schema()).isEqualTo(before);
    }

    @Test
    void node_upsert_creates_intermediate_containers_and_sets_required() throws Exception {
        ModelSpec src = base(ORDER_SCHEMA);
        ModelSpec evolved = diff("""
                { "upsertSchemaNodes": [
                    { "path": "$.order.shipping.method", "schema": { "type": "string" }, "required": true }
                ]}
                """).applyTo(src);

        JsonNode s = evolved.schema();
        assertThat(SchemaPaths.resolve(s, "$.order.shipping.method").path("type").asText()).isEqualTo("string");
        JsonNode shipping = s.path("properties").path("order").path("properties").path("shipping");
        assertThat(shipping.path("required").toString()).contains("method");
    }

    @Test
    void node_remove_drops_property_and_required_entry() throws Exception {
        ModelSpec src = base("""
                {
                  "id": "m",
                  "schema": {
                    "type": "object",
                    "properties": { "a": { "type": "string" }, "b": { "type": "number" } },
                    "required": ["a", "b"]
                  }
                }
                """);
        ModelSpec evolved = diff("""
                { "removeSchemaNodes": ["$.a"] }
                """).applyTo(src);

        JsonNode s = evolved.schema();
        assertThat(s.path("properties").has("a")).isFalse();
        assertThat(s.path("required").toString()).doesNotContain("\"a\"").contains("b");
    }

    @Test
    void removing_absent_node_is_rejected() throws Exception {
        ModelSpec src = base(ORDER_SCHEMA);
        assertThatThrownBy(() -> diff("""
                { "removeSchemaNodes": ["$.order.nonexistent"] }
                """).applyTo(src))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("absent from the schema");
    }

    // ── Definitions tier ───────────────────────────────────────────────────────

    @Test
    void def_upsert_fans_out_to_all_usage_sites() throws Exception {
        ModelSpec src = base("""
                {
                  "id": "m",
                  "schema": {
                    "type": "object",
                    "properties": {
                      "price": { "$ref": "#/$defs/Money" },
                      "fee":   { "$ref": "#/$defs/Money" }
                    },
                    "$defs": { "Money": { "type": "number" } }
                  }
                }
                """);
        ModelSpec evolved = diff("""
                { "upsertSchemaDefs": { "Money": { "type": "string", "pattern": "^\\\\d+$" } } }
                """).applyTo(src);

        JsonNode s = evolved.schema();
        assertThat(SchemaPaths.resolve(s, "$.price").path("type").asText()).isEqualTo("string");
        assertThat(SchemaPaths.resolve(s, "$.fee").path("type").asText()).isEqualTo("string");
    }

    @Test
    void removing_a_still_referenced_def_is_rejected_with_locations() throws Exception {
        ModelSpec src = base("""
                {
                  "id": "m",
                  "schema": {
                    "type": "object",
                    "properties": {
                      "price": { "$ref": "#/$defs/Money" },
                      "fee":   { "$ref": "#/$defs/Money" }
                    },
                    "$defs": { "Money": { "type": "number" } }
                  }
                }
                """);
        assertThatThrownBy(() -> diff("""
                { "removeSchemaDefs": ["Money"] }
                """).applyTo(src))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("still referenced")
                .hasMessageContaining("price")
                .hasMessageContaining("fee");
    }

    @Test
    void removing_a_def_after_its_last_ref_is_removed_succeeds() throws Exception {
        ModelSpec src = base("""
                {
                  "id": "m",
                  "schema": {
                    "type": "object",
                    "properties": { "price": { "$ref": "#/$defs/Money" } },
                    "$defs": { "Money": { "type": "number" } }
                  }
                }
                """);
        // Replace the referencing node with an inline schema in the same evolution, then drop the def.
        ModelSpec evolved = diff("""
                {
                  "upsertSchemaNodes": [ { "path": "$.price", "schema": { "type": "number" } } ],
                  "removeSchemaDefs": ["Money"]
                }
                """).applyTo(src);
        assertThat(evolved.schema().path("$defs").has("Money")).isFalse();
    }

    // ── Ref-boundary rule ──────────────────────────────────────────────────────

    @Test
    void upsert_through_a_ref_is_rejected() throws Exception {
        ModelSpec src = base("""
                {
                  "id": "m",
                  "schema": {
                    "type": "object",
                    "properties": { "billing": { "type": "object", "properties": {
                        "address": { "$ref": "#/$defs/Address" } } } },
                    "$defs": { "Address": { "type": "object", "properties": {
                        "zip": { "type": "string" } } } }
                  }
                }
                """);
        assertThatThrownBy(() -> diff("""
                { "upsertSchemaNodes": [ { "path": "$.billing.address.zip", "schema": { "type": "integer" } } ] }
                """).applyTo(src))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("traverses a $ref")
                .hasMessageContaining("#/$defs/Address");
    }

    @Test
    void upsert_landing_on_a_ref_node_is_allowed() throws Exception {
        ModelSpec src = base("""
                {
                  "id": "m",
                  "schema": {
                    "type": "object",
                    "properties": { "billing": { "type": "object", "properties": {
                        "address": { "$ref": "#/$defs/Address" } } } },
                    "$defs": { "Address": { "type": "object", "properties": {
                        "zip": { "type": "string" } } } }
                  }
                }
                """);
        // Replacing the ref node itself with an inline schema is fine.
        ModelSpec evolved = diff("""
                { "upsertSchemaNodes": [ { "path": "$.billing.address",
                    "schema": { "type": "object", "properties": { "zip": { "type": "integer" } } } } ] }
                """).applyTo(src);
        assertThat(SchemaPaths.resolve(evolved.schema(), "$.billing.address.zip").path("type").asText())
                .isEqualTo("integer");
    }

    // ── Exclusivity & downstream validation ────────────────────────────────────

    @Test
    void newSchema_combined_with_a_diff_field_is_rejected() throws Exception {
        ModelSpec src = base(ORDER_SCHEMA);
        assertThatThrownBy(() -> diff("""
                {
                  "newSchema": { "type": "object" },
                  "upsertSchemaNodes": [ { "path": "$.x", "schema": { "type": "string" } } ]
                }
                """).applyTo(src))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("newSchema cannot be combined");
    }

    @Test
    void node_upsert_introducing_a_dangling_ref_is_caught_by_full_validation() throws Exception {
        ModelSpec src = base(ORDER_SCHEMA);
        assertThatThrownBy(() -> diff("""
                { "upsertSchemaNodes": [ { "path": "$.order.total", "schema": { "$ref": "#/$defs/Nope" } } ] }
                """).applyTo(src))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Evolved spec failed validation");
    }

    @Test
    void non_canonical_node_path_is_rejected() throws Exception {
        ModelSpec src = base(ORDER_SCHEMA);
        assertThatThrownBy(() -> diff("""
                { "upsertSchemaNodes": [ { "path": "$.order.items.0.qty", "schema": { "type": "string" } } ] }
                """).applyTo(src))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("not a canonical address");
    }

    @Test
    void root_node_path_is_rejected() throws Exception {
        ModelSpec src = base(ORDER_SCHEMA);
        assertThatThrownBy(() -> diff("""
                { "upsertSchemaNodes": [ { "path": "$", "schema": { "type": "object" } } ] }
                """).applyTo(src))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("use newSchema");
    }
}
