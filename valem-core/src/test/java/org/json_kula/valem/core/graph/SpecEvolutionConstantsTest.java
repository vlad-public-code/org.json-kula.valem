package org.json_kula.valem.core.graph;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.json_kula.valem.core.model.ModelSpec;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SpecEvolutionConstantsTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private ModelSpec base(String json) throws Exception {
        return MAPPER.readValue(json, ModelSpec.class);
    }

    private SpecEvolution diff(String json) throws Exception {
        return MAPPER.readValue(json, SpecEvolution.class);
    }

    private static final String SPEC = """
            {
              "id": "m", "version": "1.0.0", "schema": {},
              "constants": { "vatRate": 0.2, "fxRate": 1.1, "regions": ["EU"] },
              "derivations": [
                { "path": "$.gross", "expr": "net * (1 + $const.vatRate)" }
              ]
            }
            """;

    @Test
    void upsert_replaces_one_constant_and_leaves_others() throws Exception {
        ModelSpec evolved = diff("""
                { "upsertConstants": { "vatRate": 0.22 } }
                """).applyTo(base(SPEC));

        assertThat(evolved.constants().get("vatRate").asDouble()).isEqualTo(0.22);
        assertThat(evolved.constants().get("fxRate").asDouble()).isEqualTo(1.1);
        assertThat(evolved.constants()).containsKeys("vatRate", "fxRate", "regions");
    }

    @Test
    void upsert_adds_a_new_constant() throws Exception {
        ModelSpec evolved = diff("""
                { "upsertConstants": { "discount": 0.05 } }
                """).applyTo(base(SPEC));
        assertThat(evolved.constants().get("discount").asDouble()).isEqualTo(0.05);
    }

    @Test
    void removing_an_unreferenced_constant_succeeds() throws Exception {
        ModelSpec evolved = diff("""
                { "removeConstants": ["fxRate"] }
                """).applyTo(base(SPEC));
        assertThat(evolved.constants()).doesNotContainKey("fxRate").containsKey("vatRate");
    }

    @Test
    void removing_a_referenced_constant_is_rejected_with_location() throws Exception {
        assertThatThrownBy(() -> diff("""
                { "removeConstants": ["vatRate"] }
                """).applyTo(base(SPEC)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("still referenced")
                .hasMessageContaining("$.gross");
    }

    @Test
    void quoted_const_reference_is_detected() throws Exception {
        ModelSpec src = base("""
                {
                  "id": "m", "version": "1.0.0", "schema": {},
                  "constants": { "odd-name": 5 },
                  "derivations": [ { "path": "$.x", "expr": "$const.\\"odd-name\\" + 1" } ]
                }
                """);
        assertThatThrownBy(() -> diff("""
                { "removeConstants": ["odd-name"] }
                """).applyTo(src))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("still referenced");
    }

    @Test
    void newConstants_combined_with_diff_is_rejected() throws Exception {
        assertThatThrownBy(() -> diff("""
                { "newConstants": {}, "upsertConstants": { "x": 1 } }
                """).applyTo(base(SPEC)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("newConstants cannot be combined");
    }

    @Test
    void substring_name_is_not_a_false_positive() throws Exception {
        // "vat" is a substring of "vatRate" but $const.vat is a distinct reference; removing
        // an unreferenced "vat"-like name must not trip on "$const.vatRate".
        ModelSpec src = base("""
                {
                  "id": "m", "version": "1.0.0", "schema": {},
                  "constants": { "vat": 1, "vatRate": 0.2 },
                  "derivations": [ { "path": "$.g", "expr": "net * $const.vatRate" } ]
                }
                """);
        ModelSpec evolved = diff("""
                { "removeConstants": ["vat"] }
                """).applyTo(src);
        assertThat(evolved.constants()).doesNotContainKey("vat").containsKey("vatRate");
    }
}
