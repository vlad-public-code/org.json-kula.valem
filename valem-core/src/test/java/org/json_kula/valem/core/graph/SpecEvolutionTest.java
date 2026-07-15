package org.json_kula.valem.core.graph;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.json_kula.valem.core.model.ModelSpec;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SpecEvolutionTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private ModelSpec base(String json) throws Exception {
        return MAPPER.readValue(json, ModelSpec.class);
    }

    // ── Add derivation ─────────────────────────────────────────────────────────

    @Test
    void adding_derivation_appears_in_evolved_spec() throws Exception {
        ModelSpec src = base("""
                { "id": "m", "schema": {}, "derivations": [
                    { "path": "$.order.total", "expr": "order.sub + order.tax" }
                ]}
                """);
        SpecEvolution diff = MAPPER.readValue("""
                { "upsertDerivations": [
                    { "path": "$.order.vat", "expr": "order.total * 0.2" }
                ]}
                """, SpecEvolution.class);

        ModelSpec evolved = diff.applyTo(src);

        assertThat(evolved.derivations()).hasSize(2);
        assertThat(evolved.derivations().stream()
                .anyMatch(d -> d.path().equals("$.order.vat"))).isTrue();
    }

    // ── defaultValues upsert / remove ──────────────────────────────────────────

    @Test
    void upserting_default_value_adds_and_replaces_by_path() throws Exception {
        ModelSpec src = base("""
                { "id": "m", "schema": {}, "defaultValues": [
                    { "path": "$.items[*]", "expr": "{ \\"qty\\": 1 }" }
                ]}
                """);
        SpecEvolution diff = MAPPER.readValue("""
                { "upsertDefaultValues": [
                    { "path": "$.items[*]", "expr": "{ \\"qty\\": 2 }" },
                    { "path": "$.customer", "expr": "{ \\"country\\": \\"US\\" }" }
                ]}
                """, SpecEvolution.class);

        ModelSpec evolved = diff.applyTo(src);

        assertThat(evolved.defaultValues()).hasSize(2);
        assertThat(evolved.defaultValues().stream()
                .filter(d -> d.path().equals("$.items[*]"))
                .findFirst().orElseThrow().expr()).contains("\"qty\": 2");
    }

    @Test
    void removing_default_value_drops_it_by_path() throws Exception {
        ModelSpec src = base("""
                { "id": "m", "schema": {}, "defaultValues": [
                    { "path": "$.items[*]", "expr": "{ \\"qty\\": 1 }" },
                    { "path": "$.customer", "expr": "{ \\"country\\": \\"US\\" }" }
                ]}
                """);
        SpecEvolution diff = MAPPER.readValue("""
                { "removeDefaultValues": ["$.items[*]"] }
                """, SpecEvolution.class);

        ModelSpec evolved = diff.applyTo(src);

        assertThat(evolved.defaultValues()).hasSize(1);
        assertThat(evolved.defaultValues().getFirst().path()).isEqualTo("$.customer");
    }

    @Test
    void unrelated_evolution_preserves_default_values() throws Exception {
        ModelSpec src = base("""
                { "id": "m", "schema": {},
                  "defaultValues": [ { "path": "$.items[*]", "expr": "{ \\"qty\\": 1 }" } ],
                  "derivations": [ { "path": "$.x.val", "expr": "1" } ]
                }
                """);
        SpecEvolution diff = MAPPER.readValue("""
                { "upsertDerivations": [ { "path": "$.y.val", "expr": "2" } ] }
                """, SpecEvolution.class);

        ModelSpec evolved = diff.applyTo(src);

        assertThat(evolved.defaultValues()).hasSize(1);
        assertThat(evolved.defaultValues().getFirst().path()).isEqualTo("$.items[*]");
    }

    // ── constants (newConstants full-replace / carry-forward) ───────────────────

    @Test
    void new_constants_replace_the_map() throws Exception {
        ModelSpec src = base("""
                { "id": "m", "schema": {}, "constants": { "vatRate": 0.2 } }
                """);
        SpecEvolution diff = MAPPER.readValue("""
                { "newConstants": { "vatRate": 0.25, "surcharge": 5 } }
                """, SpecEvolution.class);

        ModelSpec evolved = diff.applyTo(src);

        assertThat(evolved.constants()).containsKeys("vatRate", "surcharge");
        assertThat(evolved.constants().get("vatRate").asDouble()).isEqualTo(0.25);
    }

    @Test
    void unrelated_evolution_preserves_constants() throws Exception {
        ModelSpec src = base("""
                { "id": "m", "schema": {},
                  "constants": { "vatRate": 0.2 },
                  "derivations": [ { "path": "$.x.val", "expr": "1" } ] }
                """);
        SpecEvolution diff = MAPPER.readValue("""
                { "upsertDerivations": [ { "path": "$.y.val", "expr": "2" } ] }
                """, SpecEvolution.class);

        ModelSpec evolved = diff.applyTo(src);

        assertThat(evolved.constants()).containsEntry("vatRate", src.constants().get("vatRate"));
    }

    // ── Remove derivation ──────────────────────────────────────────────────────

    @Test
    void removing_derivation_is_absent_in_evolved_spec() throws Exception {
        ModelSpec src = base("""
                { "id": "m", "schema": {}, "derivations": [
                    { "path": "$.a.val", "expr": "1" },
                    { "path": "$.b.val", "expr": "2" }
                ]}
                """);
        SpecEvolution diff = MAPPER.readValue("""
                { "removeDerivations": ["$.a.val"] }
                """, SpecEvolution.class);

        ModelSpec evolved = diff.applyTo(src);

        assertThat(evolved.derivations()).hasSize(1);
        assertThat(evolved.derivations().getFirst().path()).isEqualTo("$.b.val");
    }

    // ── Update (upsert) derivation ─────────────────────────────────────────────

    @Test
    void updating_derivation_replaces_expression() throws Exception {
        ModelSpec src = base("""
                { "id": "m", "schema": {}, "derivations": [
                    { "path": "$.x.val", "expr": "1 + 1" }
                ]}
                """);
        SpecEvolution diff = MAPPER.readValue("""
                { "upsertDerivations": [
                    { "path": "$.x.val", "expr": "2 + 2" }
                ]}
                """, SpecEvolution.class);

        ModelSpec evolved = diff.applyTo(src);

        assertThat(evolved.derivations()).hasSize(1);
        assertThat(evolved.derivations().getFirst().expr()).isEqualTo("2 + 2");
    }

    // ── Add / remove constraint ────────────────────────────────────────────────

    @Test
    void adding_constraint_appears_in_evolved_spec() throws Exception {
        ModelSpec src = base("""
                { "id": "m", "schema": {} }
                """);
        SpecEvolution diff = MAPPER.readValue("""
                { "upsertConstraints": [
                    { "id": "c1", "expr": "x > 0", "message": "pos", "policy": "flag" }
                ]}
                """, SpecEvolution.class);

        ModelSpec evolved = diff.applyTo(src);

        assertThat(evolved.constraints()).hasSize(1);
        assertThat(evolved.constraints().getFirst().id()).isEqualTo("c1");
    }

    @Test
    void removing_constraint_is_absent_in_evolved_spec() throws Exception {
        ModelSpec src = base("""
                { "id": "m", "schema": {}, "constraints": [
                    { "id": "c1", "expr": "x > 0", "message": "pos", "policy": "flag" }
                ]}
                """);
        SpecEvolution diff = MAPPER.readValue("""
                { "removeConstraints": ["c1"] }
                """, SpecEvolution.class);

        ModelSpec evolved = diff.applyTo(src);

        assertThat(evolved.constraints()).isEmpty();
    }

    // ── Cyclic evolution rejected ──────────────────────────────────────────────

    @Test
    void cyclic_evolution_throws_illegal_argument() throws Exception {
        ModelSpec src = base("""
                { "id": "m", "schema": {} }
                """);
        SpecEvolution diff = MAPPER.readValue("""
                { "upsertDerivations": [
                    { "path": "$.a.val", "expr": "b.val + 1" },
                    { "path": "$.b.val", "expr": "a.val + 1" }
                ]}
                """, SpecEvolution.class);

        assertThatThrownBy(() -> diff.applyTo(src))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("validation");
    }

    // ── Version update ─────────────────────────────────────────────────────────

    @Test
    void new_version_is_applied_to_evolved_spec() throws Exception {
        ModelSpec src = base("""
                { "id": "m", "schema": {} }
                """);
        SpecEvolution diff = MAPPER.readValue("""
                { "newVersion": "2.0.0" }
                """, SpecEvolution.class);

        ModelSpec evolved = diff.applyTo(src);
        assertThat(evolved.version()).isEqualTo("2.0.0");
    }

    // ── viewDefinition evolution ───────────────────────────────────────────────

    @Test
    void viewDefinition_is_replaced_on_evolve() throws Exception {
        ModelSpec src = base("""
                {
                  "id": "m", "schema": {},
                  "viewDefinition": {
                    "views": [ { "id": "old", "label": "Old", "components": [] } ],
                    "defaultView": "old"
                  }
                }
                """);

        assertThat(src.viewDefinition()).isNotNull();
        assertThat(src.viewDefinition().path("defaultView").asText()).isEqualTo("old");

        SpecEvolution diff = MAPPER.readValue("""
                {
                  "newViewDefinition": {
                    "views": [ { "id": "new", "label": "New", "components": [] } ],
                    "defaultView": "new"
                  }
                }
                """, SpecEvolution.class);

        ModelSpec evolved = diff.applyTo(src);

        assertThat(evolved.viewDefinition()).isNotNull();
        assertThat(evolved.viewDefinition().path("defaultView").asText()).isEqualTo("new");
        assertThat(evolved.viewDefinition().path("views").get(0).path("id").asText()).isEqualTo("new");
    }

    @Test
    void viewDefinition_is_preserved_when_evolution_does_not_include_it() throws Exception {
        ModelSpec src = base("""
                {
                  "id": "m", "schema": {},
                  "viewDefinition": {
                    "views": [ { "id": "original", "label": "Original", "components": [] } ],
                    "defaultView": "original"
                  }
                }
                """);

        SpecEvolution diff = MAPPER.readValue("""
                { "newVersion": "2.0.0" }
                """, SpecEvolution.class);

        ModelSpec evolved = diff.applyTo(src);

        assertThat(evolved.viewDefinition()).isNotNull();
        assertThat(evolved.viewDefinition().path("defaultView").asText()).isEqualTo("original");
    }

    @Test
    void viewDefinition_starts_null_and_can_be_added_via_evolve() throws Exception {
        ModelSpec src = base("""
                { "id": "m", "schema": {} }
                """);

        assertThat(src.viewDefinition()).isNull();

        SpecEvolution diff = MAPPER.readValue("""
                {
                  "newViewDefinition": {
                    "views": [ { "id": "v1", "label": "V1", "components": [] } ],
                    "defaultView": "v1"
                  }
                }
                """, SpecEvolution.class);

        ModelSpec evolved = diff.applyTo(src);
        assertThat(evolved.viewDefinition()).isNotNull();
        assertThat(evolved.viewDefinition().path("defaultView").asText()).isEqualTo("v1");
    }
}
