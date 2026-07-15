package org.json_kula.valem.core.graph;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.json_kula.valem.core.model.ModelSpec;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ModelSpecValidatorTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    // ── Valid spec ─────────────────────────────────────────────────────────────

    @Test
    void valid_minimal_spec_passes() throws Exception {
        var result = validate("""
                { "id": "order", "schema": {} }
                """);
        assertThat(result.isValid()).isTrue();
        assertThat(result.findings()).isEmpty();
    }

    @Test
    void valid_full_spec_passes() throws Exception {
        var result = validate("""
                {
                  "id": "order", "schema": {},
                  "derivations": [
                    { "path": "$.order.total", "expr": "order.subtotal + order.tax" }
                  ],
                  "constraints": [
                    { "id": "c1", "expr": "order.total <= 1000",
                      "message": "Too much", "policy": "rollback" }
                  ]
                }
                """);
        assertThat(result.isValid()).isTrue();
    }

    // ── Spec-level errors ──────────────────────────────────────────────────────

    @Test
    void missing_id_produces_error() throws Exception {
        var result = validate("""
                { "id": null, "schema": {} }
                """);
        assertThat(result.isValid()).isFalse();
        assertThat(errorLocations(result)).contains("id");
    }

    @Test
    void missing_schema_produces_error() throws Exception {
        // Jackson requires schema at deserialisation time, so we test blank-schema variant
        // by setting schema to null after parsing via a partial spec
        var result = validate("""
                { "id": "m", "schema": null }
                """);
        assertThat(result.isValid()).isFalse();
        assertThat(errorLocations(result)).contains("schema");
    }

    // ── Derivation errors ──────────────────────────────────────────────────────

    @Test
    void blank_derivation_path_is_an_error() throws Exception {
        var result = validate("""
                {
                  "id": "m", "schema": {},
                  "derivations": [{ "path": "", "expr": "1+1" }]
                }
                """);
        assertThat(result.isValid()).isFalse();
        assertThat(result.errors().getFirst().location()).startsWith("derivations[0]");
    }

    @Test
    void duplicate_derivation_path_is_an_error() throws Exception {
        var result = validate("""
                {
                  "id": "m", "schema": {},
                  "derivations": [
                    { "path": "$.order.total", "expr": "1" },
                    { "path": "$.order.total", "expr": "2" }
                  ]
                }
                """);
        assertThat(result.isValid()).isFalse();
        assertThat(result.errors().stream()
                .anyMatch(e -> e.message().contains("Duplicate"))).isTrue();
    }

    @Test
    void invalid_derivation_expression_is_an_error() throws Exception {
        var result = validate("""
                {
                  "id": "m", "schema": {},
                  "derivations": [{ "path": "$.x.val", "expr": "(((" }]
                }
                """);
        assertThat(result.isValid()).isFalse();
        assertThat(result.errors().stream()
                .anyMatch(e -> e.message().contains("Invalid JSONata"))).isTrue();
    }

    // ── Constraint errors ──────────────────────────────────────────────────────

    @Test
    void duplicate_constraint_id_is_an_error() throws Exception {
        var result = validate("""
                {
                  "id": "m", "schema": {},
                  "constraints": [
                    { "id": "c1", "expr": "x > 0", "message": "m", "policy": "flag" },
                    { "id": "c1", "expr": "x < 100", "message": "m", "policy": "flag" }
                  ]
                }
                """);
        assertThat(result.isValid()).isFalse();
        assertThat(result.errors().stream()
                .anyMatch(e -> e.message().contains("Duplicate"))).isTrue();
    }

    @Test
    void missing_constraint_message_produces_warning_not_error() throws Exception {
        var result = validate("""
                {
                  "id": "m", "schema": {},
                  "constraints": [
                    { "id": "c1", "expr": "x > 0", "message": "", "policy": "flag" }
                  ]
                }
                """);
        assertThat(result.isValid()).isTrue();   // warning only
        assertThat(result.warnings()).isNotEmpty();
        assertThat(result.warnings().getFirst().message()).contains("message");
    }

    // ── Test case execution ───────────────────────────────────────────────────

    @Test
    void failing_test_case_produces_warning_not_error() throws Exception {
        var result = validate("""
                {
                  "id": "m", "schema": {},
                  "derivations": [
                    { "path": "$.x.total", "expr": "x.a - x.b" }
                  ],
                  "tests": [
                    {
                      "description": "wrong expr",
                      "given":  { "$.x.a": 10, "$.x.b": 5 },
                      "expect": { "$.x.total": 15 }
                    }
                  ]
                }
                """);

        assertThat(result.isValid()).isTrue();   // warnings don't block validity
        assertThat(result.warnings()).isNotEmpty();
        assertThat(result.warnings().stream().anyMatch(w ->
                w.location().equals("tests") && w.message().contains("wrong expr"))).isTrue();
    }

    @Test
    void passing_test_case_produces_no_warnings() throws Exception {
        var result = validate("""
                {
                  "id": "m", "schema": {},
                  "derivations": [
                    { "path": "$.x.total", "expr": "x.a + x.b" }
                  ],
                  "tests": [
                    {
                      "description": "sum",
                      "given":  { "$.x.a": 10, "$.x.b": 5 },
                      "expect": { "$.x.total": 15 }
                    }
                  ]
                }
                """);

        assertThat(result.isValid()).isTrue();
        assertThat(result.warnings().stream()
                .noneMatch(w -> w.location().equals("tests"))).isTrue();
    }

    // ── Cycle detection ───────────────────────────────────────────────────────

    @Test
    void cyclic_derivations_produce_error() throws Exception {
        var result = validate("""
                {
                  "id": "m", "schema": {},
                  "derivations": [
                    { "path": "$.a.val", "expr": "b.val + 1" },
                    { "path": "$.b.val", "expr": "a.val + 1" }
                  ]
                }
                """);
        assertThat(result.isValid()).isFalse();
        assertThat(result.errors().stream()
                .anyMatch(e -> e.location().equals("graph") &&
                               e.message().contains("Cyclic"))).isTrue();
    }

    // ── Static-extractability soundness warnings (D-T3) ────────────────────────

    @Test
    void derivation_using_dynamic_lookup_produces_a_warning() throws Exception {
        var result = validate("""
                {
                  "id": "order", "schema": {},
                  "derivations": [
                    { "path": "$.picked", "expr": "$lookup(catalog, selectedKey)" }
                  ]
                }
                """);
        // Not an error (still valid), but a warning is surfaced.
        assertThat(result.findings().stream()
                .anyMatch(f -> f.severity() == ModelSpecValidator.Severity.WARNING
                        && f.message().contains("$lookup"))).isTrue();
    }

    @Test
    void plain_static_derivation_has_no_dynamic_warning() throws Exception {
        var result = validate("""
                {
                  "id": "order", "schema": {},
                  "derivations": [ { "path": "$.total", "expr": "subtotal + tax" } ]
                }
                """);
        assertThat(result.findings().stream()
                .noneMatch(f -> f.message().contains("$lookup") || f.message().contains("$eval"))).isTrue();
    }

    // ── Address dialect (DEC-6 / E-T1) ─────────────────────────────────────────

    @Test
    void legacy_dot_index_address_is_rejected_as_error() throws Exception {
        var result = validate("""
                {
                  "id": "order", "schema": {},
                  "derivations": [ { "path": "$.order.items.0.total", "expr": "1 + 1" } ]
                }
                """);
        assertThat(result.isValid()).isFalse(); // hard error now
        assertThat(result.errors().stream().anyMatch(e ->
                e.message().contains("Non-canonical address")
                && e.message().contains("$.order.items[0].total"))).isTrue();
    }

    @Test
    void unrooted_default_value_path_is_rejected_as_error() throws Exception {
        var result = validate("""
                {
                  "id": "order", "schema": {},
                  "defaultValues": [ { "path": "order.status", "expr": "{ \\"x\\": 1 }" } ]
                }
                """);
        assertThat(result.isValid()).isFalse();
        assertThat(result.errors().stream().anyMatch(e ->
                e.message().contains("Non-canonical address")
                && e.message().contains("$.order.status"))).isTrue();
    }

    @Test
    void valid_constants_of_any_json_type_produce_no_finding() throws Exception {
        var result = validate("""
                {
                  "id": "m", "schema": {},
                  "constants": { "vatRate": 0.2, "tiers": [10, 20], "cfg": { "threshold": 100 } }
                }
                """);
        assertThat(result.isValid()).isTrue();
        assertThat(result.findings()).isEmpty();
    }

    @Test
    void constant_with_non_identifier_name_warns_but_is_valid() throws Exception {
        var result = validate("""
                { "id": "m", "schema": {}, "constants": { "odd-name": 1 } }
                """);
        assertThat(result.isValid()).isTrue(); // warning, not error
        assertThat(result.warnings().stream()
                .anyMatch(w -> w.message().contains("odd-name"))).isTrue();
    }

    @Test
    void initial_state_field_is_rejected_at_deserialization() {
        // initialState was removed in favour of a defaultValues rule with path "$".
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> MAPPER.readValue("""
                {
                  "id": "order", "schema": {},
                  "initialState": { "$.order.status": "new" }
                }
                """, ModelSpec.class))
                .hasMessageContaining("initialState has been removed");
    }

    @Test
    void canonical_addresses_produce_no_address_finding() throws Exception {
        var result = validate("""
                {
                  "id": "order", "schema": {},
                  "defaultValues": [ { "path": "$", "expr": "{ \\"order\\": { \\"status\\": \\"new\\" } }" } ],
                  "derivations": [ { "path": "$.order.items[0].total", "expr": "1 + 1" } ],
                  "metaDerivations": [
                    { "path": "$.order.total", "property": "minimum", "expr": "0" }
                  ]
                }
                """);
        assertThat(result.isValid()).isTrue();
        assertThat(result.findings().stream()
                .noneMatch(f -> f.message().contains("Non-canonical address"))).isTrue();
    }

    @Test
    void expr_navigation_is_never_flagged_as_an_address() throws Exception {
        // The path is canonical; the expr uses dotted/legacy-looking navigation, which must NOT be
        // treated as an address — expressions may use any JSONata-valid navigation (DEC-6).
        var result = validate("""
                {
                  "id": "order", "schema": {},
                  "derivations": [
                    { "path": "$.order.firstItemQty", "expr": "order.items.0.qty" }
                  ]
                }
                """);
        assertThat(result.findings().stream()
                .noneMatch(f -> f.message().contains("Non-canonical address"))).isTrue();
    }

    // ── Schema $defs / $ref (M1) ───────────────────────────────────────────────

    @Test
    void local_ref_to_existing_def_is_valid() throws Exception {
        var result = validate("""
                {
                  "id": "m",
                  "schema": {
                    "type": "object",
                    "properties": { "price": { "$ref": "#/$defs/Money" } },
                    "$defs": { "Money": { "type": "number" } }
                  }
                }
                """);
        assertThat(result.isValid()).isTrue();
        assertThat(result.findings()).isEmpty();
    }

    @Test
    void dangling_ref_is_an_error() throws Exception {
        var result = validate("""
                {
                  "id": "m",
                  "schema": {
                    "type": "object",
                    "properties": { "price": { "$ref": "#/$defs/Money" } },
                    "$defs": {}
                  }
                }
                """);
        assertThat(result.isValid()).isFalse();
        assertThat(result.errors().stream().anyMatch(e ->
                e.message().contains("no such definition") && e.message().contains("Money"))).isTrue();
    }

    @Test
    void non_local_ref_is_rejected() throws Exception {
        var result = validate("""
                {
                  "id": "m",
                  "schema": {
                    "type": "object",
                    "properties": { "x": { "$ref": "https://example.com/schema.json" } }
                  }
                }
                """);
        assertThat(result.isValid()).isFalse();
        assertThat(result.errors().stream().anyMatch(e ->
                e.message().contains("only local definition refs"))).isTrue();
    }

    @Test
    void draft07_definitions_ref_is_rejected_as_non_local_form() throws Exception {
        var result = validate("""
                {
                  "id": "m",
                  "schema": {
                    "type": "object",
                    "properties": { "x": { "$ref": "#/definitions/Money" } },
                    "definitions": { "Money": { "type": "number" } }
                  }
                }
                """);
        assertThat(result.isValid()).isFalse();
        assertThat(result.errors().stream().anyMatch(e ->
                e.message().contains("only local definition refs"))).isTrue();
    }

    @Test
    void unused_def_produces_a_warning_not_error() throws Exception {
        var result = validate("""
                {
                  "id": "m",
                  "schema": { "type": "object", "$defs": { "Unused": { "type": "string" } } }
                }
                """);
        assertThat(result.isValid()).isTrue();
        assertThat(result.warnings().stream().anyMatch(w ->
                w.message().contains("never referenced") && w.message().contains("Unused"))).isTrue();
    }

    @Test
    void keywords_alongside_ref_produce_a_warning() throws Exception {
        var result = validate("""
                {
                  "id": "m",
                  "schema": {
                    "type": "object",
                    "properties": { "price": { "$ref": "#/$defs/Money", "minimum": 0 } },
                    "$defs": { "Money": { "type": "number" } }
                  }
                }
                """);
        assertThat(result.isValid()).isTrue();
        assertThat(result.warnings().stream().anyMatch(w ->
                w.message().contains("alongside $ref"))).isTrue();
    }

    @Test
    void recursive_def_referencing_itself_is_valid_and_not_unused() throws Exception {
        var result = validate("""
                {
                  "id": "m",
                  "schema": {
                    "type": "object",
                    "properties": { "root": { "$ref": "#/$defs/Category" } },
                    "$defs": {
                      "Category": {
                        "type": "object",
                        "properties": {
                          "name": { "type": "string" },
                          "children": { "type": "array", "items": { "$ref": "#/$defs/Category" } }
                        }
                      }
                    }
                  }
                }
                """);
        assertThat(result.isValid()).isTrue();
        assertThat(result.warnings().stream().noneMatch(w -> w.message().contains("never referenced"))).isTrue();
    }

    // ── Schema pattern syntax ───────────────────────────────────────────────────

    @Test
    void malformed_schema_pattern_is_rejected() throws Exception {
        // An uncompilable regex must fail at create/evolve (422) rather than silently leaving the field
        // unvalidated at runtime (audit SEC-2 fail-open).
        var result = validate("""
                { "id": "order", "schema": {
                    "type": "object",
                    "properties": { "code": { "type": "string", "pattern": "[A-Z" } }
                } }
                """);
        assertThat(result.isValid()).isFalse();
        assertThat(errorLocations(result)).anyMatch(l -> l.endsWith(".pattern"));
    }

    @Test
    void valid_schema_pattern_passes() throws Exception {
        var result = validate("""
                { "id": "order", "schema": {
                    "type": "object",
                    "properties": { "code": { "type": "string", "pattern": "^[A-Z]{3}$" } }
                } }
                """);
        assertThat(result.isValid()).isTrue();
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private ModelSpecValidator.ValidationResult validate(String json) throws Exception {
        ModelSpec spec = MAPPER.readValue(json, ModelSpec.class);
        return ModelSpecValidator.validate(spec);
    }

    private List<String> errorLocations(ModelSpecValidator.ValidationResult result) {
        return result.errors().stream().map(ModelSpecValidator.ValidationError::location).toList();
    }
}
