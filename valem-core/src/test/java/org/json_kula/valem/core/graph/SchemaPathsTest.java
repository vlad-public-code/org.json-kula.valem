package org.json_kula.valem.core.graph;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class SchemaPathsTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private JsonNode schema(String json) throws Exception {
        return MAPPER.readTree(json);
    }

    @Test
    void resolves_plain_property_and_array_navigation() throws Exception {
        JsonNode s = schema("""
                {
                  "type": "object",
                  "properties": {
                    "order": {
                      "type": "object",
                      "properties": {
                        "items": {
                          "type": "array",
                          "items": {
                            "type": "object",
                            "properties": { "qty": { "type": "integer" } }
                          }
                        }
                      }
                    }
                  }
                }
                """);
        assertThat(SchemaPaths.resolve(s, "$.order.items[0].qty").path("type").asText()).isEqualTo("integer");
        assertThat(SchemaPaths.resolve(s, "$.order.items[*].qty").path("type").asText()).isEqualTo("integer");
    }

    @Test
    void resolves_through_a_local_ref() throws Exception {
        JsonNode s = schema("""
                {
                  "type": "object",
                  "properties": { "price": { "$ref": "#/$defs/Money" } },
                  "$defs": { "Money": {
                    "type": "object",
                    "properties": { "amount": { "type": "string" } } } }
                }
                """);
        // Landing on the ref node resolves to the definition
        assertThat(SchemaPaths.resolve(s, "$.price").path("type").asText()).isEqualTo("object");
        // Navigating through the ref reaches the definition's inner field
        assertThat(SchemaPaths.resolve(s, "$.price.amount").path("type").asText()).isEqualTo("string");
    }

    @Test
    void recursive_ref_terminates() throws Exception {
        JsonNode s = schema("""
                {
                  "type": "object",
                  "properties": { "root": { "$ref": "#/$defs/Category" } },
                  "$defs": { "Category": {
                    "type": "object",
                    "properties": {
                      "name": { "type": "string" },
                      "children": { "type": "array", "items": { "$ref": "#/$defs/Category" } }
                    } } }
                }
                """);
        assertThat(SchemaPaths.resolve(s, "$.root.children[0].name").path("type").asText())
                .isEqualTo("string");
    }

    @Test
    void ref_chain_loop_resolves_to_empty() throws Exception {
        JsonNode s = schema("""
                {
                  "type": "object",
                  "properties": { "x": { "$ref": "#/$defs/A" } },
                  "$defs": { "A": { "$ref": "#/$defs/B" }, "B": { "$ref": "#/$defs/A" } }
                }
                """);
        assertThat(SchemaPaths.resolve(s, "$.x").isEmpty()).isTrue();
    }

    @Test
    void absent_path_resolves_to_empty_object() throws Exception {
        JsonNode s = schema("""
                { "type": "object", "properties": { "a": { "type": "string" } } }
                """);
        assertThat(SchemaPaths.resolve(s, "$.nope.deeper").isEmpty()).isTrue();
    }

    @Test
    void localDefName_extracts_and_rejects() throws Exception {
        assertThat(SchemaPaths.localDefName(MAPPER.readTree("\"#/$defs/Money\""))).isEqualTo("Money");
        assertThat(SchemaPaths.localDefName(MAPPER.readTree("\"#/definitions/Money\""))).isNull();
        assertThat(SchemaPaths.localDefName(MAPPER.readTree("\"https://x/y\""))).isNull();
        assertThat(SchemaPaths.localDefName(MAPPER.readTree("\"#/$defs/a/b\""))).isNull();
    }
}
