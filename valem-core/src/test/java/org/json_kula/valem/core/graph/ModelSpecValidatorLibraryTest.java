package org.json_kula.valem.core.graph;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.json_kula.valem.core.model.ModelSpec;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** Validation of the {@code library} section, and name resolution against what it exports. */
class ModelSpecValidatorLibraryTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    // ── Library-local rules ───────────────────────────────────────────────────

    @Test
    void accepts_a_well_formed_library() {
        assertThat(errors("""
            { "id": "m", "schema": {},
              "library": "( $double := function($n){ $n * 2 }; [\\"double\\"] )",
              "derivations": [ { "path": "$.d", "expr": "$double(base)" } ] }
            """)).isEmpty();
    }

    @Test
    void rejects_a_definition_that_reads_the_model_document() {
        assertThat(messages("""
            { "id": "m", "schema": {},
              "library": "( $net := function(){ order.subtotal - order.discount }; [\\"net\\"] )",
              "derivations": [ { "path": "$.d", "expr": "$net()" } ] }
            """))
                .anySatisfy(m -> assertThat(m)
                        .contains("always evaluates to nothing")
                        .contains("order.subtotal")
                        .contains("$myFn(order.total)"));   // the corrected pair, not just the rule
    }

    @Test
    void rejects_a_definition_that_does_not_compile() {
        assertThat(messages("""
            { "id": "m", "schema": {},
              "library": "( $f := function($x { $x }; [\\"f\\"] )" }
            """)).anySatisfy(m -> assertThat(m).contains("Invalid library definition"));
    }

    @Test
    void hints_at_the_parenthesis_rule_for_a_multi_statement_lambda_body() {
        assertThat(messages("""
            { "id": "m", "schema": {},
              "library": "( $f := function($n){ $a := $n * 2; $a }; [\\"f\\"] )" }
            """)).anySatisfy(m -> assertThat(m).contains("wrap a multi-statement body in"));
    }

    @Test
    void rejects_an_export_that_shadows_a_jsonata_builtin() {
        assertThat(messages("""
            { "id": "m", "schema": {},
              "library": "( $sum := function($x){ 999 }; [\\"sum\\"] )" }
            """)).anySatisfy(m -> assertThat(m).contains("shadows a JSONata built-in"));
    }

    @Test
    void rejects_an_export_that_collides_with_an_engine_binding() {
        assertThat(messages("""
            { "id": "m", "schema": {},
              "library": "( $parent := function($x){ $x }; [\\"parent\\"] )" }
            """)).anySatisfy(m -> assertThat(m).contains("collides with a name Valem binds"));
    }

    @Test
    void rejects_a_non_deterministic_builtin_in_a_definition() {
        assertThat(messages("""
            { "id": "m", "schema": {},
              "library": "( $stamp := function($x){ $x & $now() }; [\\"stamp\\"] )" }
            """)).anySatisfy(m -> assertThat(m).contains("evaluated once, when the library is compiled"));
    }

    @Test
    void rejects_a_library_that_exports_nothing_usable() {
        // A definition ending on its binding returns a function value, not a list of names.
        assertThat(errors("""
            { "id": "m", "schema": {},
              "library": "( $f := function($n){ $n }; $f )" }
            """)).isNotEmpty();
    }

    @Test
    void rejects_an_invalid_extends_coordinate() {
        assertThat(messages("""
            { "id": "m", "schema": {},
              "library": { "extends": ["not a coordinate!"],
                           "define": "( $f := function($n){ $n }; [\\"f\\"] )" } }
            """)).anySatisfy(m -> assertThat(m).contains("invalid library coordinate"));
    }

    // ── Name resolution across the spec ───────────────────────────────────────

    @Test
    void rejects_a_call_to_a_function_nothing_defines_and_suggests_the_near_miss() {
        assertThat(messages("""
            { "id": "m", "schema": {},
              "library": "( $double := function($n){ $n * 2 }; [\\"double\\"] )",
              "derivations": [ { "path": "$.d", "expr": "$doubel(base)" } ] }
            """))
                .anySatisfy(m -> assertThat(m)
                        .contains("$doubel is not defined")
                        .contains("did you mean $double"));
    }

    @Test
    void an_unknown_call_is_an_error_even_without_a_library() {
        assertThat(messages("""
            { "id": "m", "schema": {},
              "derivations": [ { "path": "$.d", "expr": "$nope(base)" } ] }
            """)).anySatisfy(m -> assertThat(m).contains("this model declares no library"));
    }

    @Test
    void an_unknown_value_reference_is_only_a_warning() {
        ModelSpecValidator.ValidationResult result = validate("""
            { "id": "m", "schema": {},
              "derivations": [ { "path": "$.d", "expr": "base + $missing" } ] }
            """);
        assertThat(result.errors()).isEmpty();
        assertThat(result.warnings()).anySatisfy(
                w -> assertThat(w.message()).contains("$missing is not bound"));
    }

    @Test
    void a_block_local_lambda_is_not_reported_as_unknown() {
        assertThat(errors("""
            { "id": "m", "schema": {},
              "derivations": [ { "path": "$.d", "expr": "( $f := function($x){ $x * 2 }; $f(base) )" } ] }
            """)).isEmpty();
    }

    @Test
    void engine_bindings_are_not_reported_as_unknown() {
        assertThat(errors("""
            { "id": "m", "schema": {},
              "constants": { "rate": 2 },
              "defaultValues": [ { "path": "$.items[*]", "expr": "{ \\"n\\": $count($parent) }" } ],
              "derivations": [ { "path": "$.items[*].t", "expr": "$parent.qty * $const.rate" } ] }
            """)).isEmpty();
    }

    @Test
    void library_exports_resolve_in_every_section() {
        assertThat(errors("""
            { "id": "m", "schema": {},
              "library": "( $ok := function($n){ $n > 0 }; $two := function($n){ $n * 2 }; [\\"ok\\", \\"two\\"] )",
              "defaultValues": [ { "path": "$", "expr": "{ \\"base\\": $two(5) }" } ],
              "derivations":   [ { "path": "$.d", "expr": "$two(base)" } ],
              "metaDerivations": [ { "path": "$.base", "property": "maximum", "expr": "$two(base)" } ],
              "constraints":   [ { "id": "c", "expr": "$ok(base)", "policy": "flag", "message": "x" } ],
              "effects":       [ { "id": "e", "executor": "caller", "trigger": "$ok(base)", "emit": "x" } ] }
            """)).isEmpty();
    }

    private ModelSpecValidator.ValidationResult validate(String specJson) {
        try {
            return ModelSpecValidator.validate(MAPPER.readValue(specJson, ModelSpec.class));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private List<ModelSpecValidator.ValidationError> errors(String specJson) {
        return validate(specJson).errors();
    }

    private List<String> messages(String specJson) {
        return errors(specJson).stream().map(ModelSpecValidator.ValidationError::message).toList();
    }
}
