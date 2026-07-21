package org.json_kula.valem.core.graph;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.json_kula.valem.core.model.ModelSpec;
import org.json_kula.valem.core.model.ViewComponentTypes;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Structural {@code viewDefinition} validation — most importantly the component-type vocabulary,
 * which used to pass every server-side check and only fail in the browser (an "Unknown component
 * type" box) because {@code ViewEvaluator}'s {@code default} branch accepts anything.
 */
class ModelSpecValidatorViewTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void known_component_types_pass() throws Exception {
        var result = validate("""
                { "id": "order", "schema": {},
                  "viewDefinition": { "defaultView": "main", "views": [
                    { "id": "main", "components": [
                      { "id": "qty",   "type": "numericField", "bind": "$.qty" },
                      { "id": "title", "type": "staticText", "text": "'Order'" }
                    ] } ] } }
                """);
        assertThat(result.isValid()).isTrue();
    }

    @Test
    void unknown_component_type_is_an_error() throws Exception {
        var result = validate("""
                { "id": "order", "schema": {},
                  "viewDefinition": { "defaultView": "main", "views": [
                    { "id": "main", "components": [
                      { "id": "qty", "type": "number-input", "bind": "$.qty" }
                    ] } ] } }
                """);
        assertThat(result.isValid()).isFalse();
        assertThat(errorMessages(result))
                .anyMatch(m -> m.contains("Unknown component type 'number-input'"));
    }

    @Test
    void unknown_component_type_error_lists_the_allowed_vocabulary() throws Exception {
        var result = validate("""
                { "id": "order", "schema": {},
                  "viewDefinition": { "views": [
                    { "id": "main", "components": [ { "id": "t", "type": "text" } ] } ] } }
                """);
        // The agent fixing this has the whole catalog in the error, no docs round-trip needed.
        assertThat(errorMessages(result))
                .anyMatch(m -> m.contains("staticText") && m.contains("numericField") && m.contains("sectionList"));
    }

    @Test
    void nested_components_are_checked_too() throws Exception {
        var result = validate("""
                { "id": "order", "schema": {},
                  "viewDefinition": { "views": [
                    { "id": "main", "components": [
                      { "id": "g", "type": "group", "components": [
                        { "id": "inner", "type": "checkbox" }
                      ] } ] } ] } }
                """);
        assertThat(result.isValid()).isFalse();
        assertThat(errorMessages(result)).anyMatch(m -> m.contains("Unknown component type 'checkbox'"));
    }

    @Test
    void missing_component_type_is_an_error() throws Exception {
        var result = validate("""
                { "id": "order", "schema": {},
                  "viewDefinition": { "views": [
                    { "id": "main", "components": [ { "id": "orphan", "bind": "$.qty" } ] } ] } }
                """);
        assertThat(result.isValid()).isFalse();
        assertThat(errorMessages(result)).anyMatch(m -> m.contains("missing its 'type'"));
    }

    // ── The "did you mean" hint ───────────────────────────────────────────────

    @Test
    void casing_and_punctuation_variants_suggest_the_canonical_spelling() {
        assertThat(ViewComponentTypes.suggest("text-field")).isEqualTo("textField");
        assertThat(ViewComponentTypes.suggest("TextField")).isEqualTo("textField");
        assertThat(ViewComponentTypes.suggest("section_list")).isEqualTo("sectionList");
    }

    @Test
    void a_near_miss_suggests_its_closest_neighbour() {
        assertThat(ViewComponentTypes.suggest("sliderfeild")).isEqualTo("sliderField");
        assertThat(ViewComponentTypes.suggest("progressbar")).isEqualTo("progressBar");
    }

    @Test
    void something_unrecognisable_suggests_nothing_rather_than_guessing_wildly() {
        assertThat(ViewComponentTypes.suggest("quantumFluxCapacitor")).isNull();
    }

    @Test
    void the_vocabulary_is_the_documented_catalog() {
        // Pins the count against silent drift — the React renderer's switch and
        // docs/reference/view-system.md list exactly these.
        assertThat(ViewComponentTypes.ALL).hasSize(31);
        assertThat(ViewComponentTypes.isKnown("sliderField")).isTrue();
        assertThat(ViewComponentTypes.isKnown("slider")).isFalse();
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private ModelSpecValidator.ValidationResult validate(String json) throws Exception {
        ModelSpec spec = MAPPER.readValue(json, ModelSpec.class);
        return ModelSpecValidator.validate(spec);
    }

    private List<String> errorMessages(ModelSpecValidator.ValidationResult result) {
        return result.errors().stream().map(ModelSpecValidator.ValidationError::message).toList();
    }
}
