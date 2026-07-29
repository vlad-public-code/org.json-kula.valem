package org.json_kula.valem.core.graph;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.json_kula.valem.core.model.ModelSpec;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The view-lint layer ({@link ModelSpecValidator#lintView}): dangling {@code bind} paths and
 * literal-where-expression-meant display fields. All findings are WARNINGs — a view still renders —
 * but the generation loop feeds them back as repair guidance, so precision matters (a false positive
 * would send the LLM chasing a non-problem).
 */
class ModelSpecValidatorViewLintTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    // ── dangling binds ──────────────────────────────────────────────────────────

    @Test
    void bind_to_a_declared_field_or_derivation_is_clean() throws Exception {
        var w = lint("""
                { "id": "bmi",
                  "schema": { "properties": { "weight": {"type":"number"}, "height": {"type":"number"} } },
                  "derivations": [ { "path": "$.bmi", "expr": "weight/(height*height)" } ],
                  "viewDefinition": { "defaultView": "v", "views": [ { "id": "v", "components": [
                    { "id": "w", "type": "numericField", "bind": "$.weight" },
                    { "id": "t", "type": "statTile", "label": "BMI", "bind": "$.bmi" }
                  ] } ] } }
                """);
        assertThat(w).isEmpty();
    }

    @Test
    void bind_to_a_nonexistent_path_warns_and_does_not_match_a_similar_prefix() throws Exception {
        // "$.bmi" must NOT be accepted just because "$.bmiValue" exists (segment-aware, not string prefix).
        var w = lint("""
                { "id": "bmi",
                  "schema": { "properties": { "weight": {"type":"number"} } },
                  "derivations": [ { "path": "$.bmiValue", "expr": "weight" } ],
                  "viewDefinition": { "defaultView": "v", "views": [ { "id": "v", "components": [
                    { "id": "t", "type": "statTile", "label": "BMI", "bind": "$.bmi" }
                  ] } ] } }
                """);
        assertThat(w).anyMatch(m -> m.contains("bind '$.bmi'") && m.contains("render empty"));
    }

    @Test
    void bind_to_an_array_element_field_is_clean() throws Exception {
        var w = lint("""
                { "id": "order",
                  "schema": { "properties": { "items": { "type":"array",
                    "items": { "properties": { "amount": {"type":"number"} } } } } },
                  "viewDefinition": { "defaultView": "v", "views": [ { "id": "v", "components": [
                    { "id": "kv", "type": "keyValueList", "items": [
                      { "label": "First", "bind": "$.items[0].amount" } ] } ] } ] } }
                """);
        assertThat(w).isEmpty();
    }

    @Test
    void bind_to_a_container_above_declared_leaves_is_clean() throws Exception {
        var w = lint("""
                { "id": "c",
                  "schema": { "properties": { "customer": { "type":"object",
                    "properties": { "name": {"type":"string"} } } } },
                  "viewDefinition": { "defaultView": "v", "views": [ { "id": "v", "components": [
                    { "id": "j", "type": "jsonViewer", "bind": "$.customer" },
                    { "id": "all", "type": "jsonViewer", "bind": "$" } ] } ] } }
                """);
        assertThat(w).isEmpty();
    }

    // ── literal-where-expression-meant ────────────────────────────────────────────

    @Test
    void over_quoted_plain_text_field_warns() throws Exception {
        var w = lint("""
                { "id": "bmi", "schema": { "properties": { "weight": {"type":"number"} } },
                  "viewDefinition": { "defaultView": "v", "views": [ { "id": "v", "components": [
                    { "id": "w", "type": "numericField", "bind": "$.weight", "label": "\\"Weight (kg)\\"" }
                  ] } ] } }
                """);
        assertThat(w).anyMatch(m -> m.contains("label is wrapped in quotes")
                && m.contains("verbatim"));
    }

    @Test
    void bare_field_reference_in_text_warns_with_a_fix() throws Exception {
        var w = lint("""
                { "id": "bmi",
                  "schema": { "properties": { "weight": {"type":"number"} } },
                  "derivations": [ { "path": "$.bmiCategory", "expr": "'x'" } ],
                  "viewDefinition": { "defaultView": "v", "views": [ { "id": "v", "components": [
                    { "id": "b", "type": "badge", "text": "bmiCategory" }
                  ] } ] } }
                """);
        assertThat(w).anyMatch(m -> m.contains("bmiCategory")
                && (m.contains("$string(bmiCategory)") || m.contains("$.bmiCategory")));
    }

    @Test
    void quoted_literal_expression_with_no_dollar_warns() throws Exception {
        var w = lint("""
                { "id": "bmi", "schema": { "properties": { "weight": {"type":"number"} } },
                  "derivations": [ { "path": "$.bmi", "expr": "weight" } ],
                  "viewDefinition": { "defaultView": "v", "views": [ { "id": "v", "components": [
                    { "id": "t", "type": "statTile", "bind": "$.bmi", "caption": "\\"kg/m2\\"" }
                  ] } ] } }
                """);
        assertThat(w).anyMatch(m -> m.contains("caption") && m.contains("quote characters"));
    }

    @Test
    void a_fixed_literal_caption_and_a_real_expression_are_clean() throws Exception {
        // "kg/m2" (no $, has an operator-ish char, not a field) and "$string(bmi)" are both fine.
        var w = lint("""
                { "id": "bmi", "schema": { "properties": { "weight": {"type":"number"} } },
                  "derivations": [ { "path": "$.bmi", "expr": "weight" } ],
                  "viewDefinition": { "defaultView": "v", "views": [ { "id": "v", "components": [
                    { "id": "t", "type": "statTile", "bind": "$.bmi", "caption": "kg/m2" },
                    { "id": "b", "type": "badge", "text": "$string(bmi)" } ] } ] } }
                """);
        assertThat(w).isEmpty();
    }

    @Test
    void a_literal_word_that_names_no_field_is_not_flagged() throws Exception {
        // "Underweight" is a bare identifier but matches no data path → intended literal, no warning.
        var w = lint("""
                { "id": "bmi", "schema": { "properties": { "weight": {"type":"number"} } },
                  "viewDefinition": { "defaultView": "v", "views": [ { "id": "v", "components": [
                    { "id": "b", "type": "badge", "text": "Underweight" } ] } ] } }
                """);
        assertThat(w).isEmpty();
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private List<String> lint(String json) throws Exception {
        ModelSpec spec = MAPPER.readValue(json, ModelSpec.class);
        return ModelSpecValidator.lintView(spec).stream()
                .map(ModelSpecValidator.ValidationError::message).toList();
    }
}
