package org.json_kula.valem.core.graph;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.json_kula.valem.core.model.ModelSpec;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The navigation half of the view lint: what an item view edits, whether a view can be reached, and
 * whether an element editor leads back.
 *
 * <p>These are the mistakes a generated {@code sectionList} makes. All are WARNINGs — the model
 * still runs, and the renderer supplies a Back of its own — but the generation loop feeds them back
 * as repair guidance, so a false positive costs a whole retry. The clean cases below are as much of
 * the contract as the flagged ones.
 */
class ModelSpecValidatorNavigationLintTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** A model whose {@code debts} array is edited through a separate item view. */
    private static String twoViewSpec(String itemComponents) {
        return """
                { "id": "debts",
                  "schema": { "properties": { "debts": { "type": "array", "items": {
                      "properties": { "name": {"type":"string"}, "balance": {"type":"number"} } } } } },
                  "viewDefinition": { "defaultView": "main", "views": [
                    { "id": "main", "label": "Debts", "components": [
                      { "id": "debtsList", "type": "sectionList", "label": "Your debts",
                        "bind": "$.debts", "itemView": "debtItem" } ] },
                    { "id": "debtItem", "label": "Debt", "components": [ %s ] } ] } }
                """.formatted(itemComponents);
    }

    private static final String BACK_BUTTON =
            """
            { "id": "done", "type": "button", "label": "Done", "onClick": { "navigate": "main" } }""";

    // ── What the item view edits ────────────────────────────────────────────────

    @Test
    void an_item_view_binding_the_array_pattern_is_clean() throws Exception {
        var w = lint(twoViewSpec("""
                { "id": "n", "type": "textField", "label": "Name", "bind": "$.debts[*].name" },
                """ + BACK_BUTTON));
        assertThat(w).isEmpty();
    }

    @Test
    void an_item_view_binding_a_fixed_element_warns() throws Exception {
        // Every row would open the editor on debts[0] — the mistake the docs' own example once showed.
        var w = lint(twoViewSpec("""
                { "id": "n", "type": "textField", "label": "Name", "bind": "$.debts[0].name" },
                """ + BACK_BUTTON));
        assertThat(w).anyMatch(m -> m.contains("binds a FIXED element of $.debts")
                && m.contains("$.debts[*].<field>"));
    }

    @Test
    void an_item_view_binding_nothing_of_the_array_warns() throws Exception {
        var w = lint(twoViewSpec("""
                { "id": "n", "type": "textField", "label": "Note", "bind": "$.debts" },
                """ + BACK_BUTTON));
        assertThat(w).anyMatch(m -> m.contains("binds no field of $.debts") && m.contains("edits nothing"));
    }

    @Test
    void a_summary_row_inside_the_item_view_counts_as_addressing_the_element() throws Exception {
        var w = lint(twoViewSpec("""
                { "id": "kv", "type": "summaryList", "items": [
                    { "label": "Balance", "bind": "$.debts[*].balance" } ] },
                """ + BACK_BUTTON));
        assertThat(w).isEmpty();
    }

    // ── The way back ────────────────────────────────────────────────────────────

    @Test
    void an_item_view_with_no_way_back_warns_and_names_the_list_view() throws Exception {
        var w = lint(twoViewSpec("""
                { "id": "n", "type": "textField", "label": "Name", "bind": "$.debts[*].name" }"""));
        assertThat(w).anyMatch(m -> m.contains("no control leading back to the list")
                && m.contains("\"navigate\": \"main\""));
    }

    @Test
    void a_menu_on_the_item_view_is_a_way_back() throws Exception {
        var w = lint(twoViewSpec("""
                { "id": "n", "type": "textField", "label": "Name", "bind": "$.debts[*].name" },
                { "id": "nav", "type": "menu", "menuItems": [ { "label": "Debts", "targetView": "main" } ] }"""));
        assertThat(w).isEmpty();
    }

    @Test
    void a_button_that_only_re_targets_the_same_view_is_not_a_way_back() throws Exception {
        var w = lint(twoViewSpec("""
                { "id": "n", "type": "textField", "label": "Name", "bind": "$.debts[*].name" },
                { "id": "self", "type": "button", "label": "Reload",
                  "onClick": { "navigate": "debtItem" } }"""));
        assertThat(w).anyMatch(m -> m.contains("no control leading back to the list"));
    }

    // ── Reachability ────────────────────────────────────────────────────────────

    @Test
    void a_view_nothing_navigates_to_warns() throws Exception {
        var w = lint("""
                { "id": "m", "schema": { "properties": { "a": {"type":"number"} } },
                  "viewDefinition": { "defaultView": "main", "views": [
                    { "id": "main", "components": [ { "id": "a", "type": "numericField", "bind": "$.a" } ] },
                    { "id": "orphan", "components": [ { "id": "t", "type": "statTile", "bind": "$.a" } ] } ] } }
                """);
        assertThat(w).anyMatch(m -> m.contains("view 'orphan' is unreachable"));
    }

    @Test
    void a_view_reached_by_a_stepper_is_not_unreachable() throws Exception {
        var w = lint("""
                { "id": "m", "schema": { "properties": { "a": {"type":"number"} } },
                  "viewDefinition": { "defaultView": "main", "views": [
                    { "id": "main", "components": [
                      { "id": "s", "type": "stepper", "menuItems": [
                        { "label": "Details", "targetView": "second" } ] } ] },
                    { "id": "second", "components": [
                      { "id": "b", "type": "button", "label": "Back",
                        "onClick": { "navigate": "main" } } ] } ] } }
                """);
        assertThat(w).isEmpty();
    }

    @Test
    void the_first_view_is_home_when_no_defaultView_is_declared() throws Exception {
        var w = lint("""
                { "id": "m", "schema": { "properties": { "a": {"type":"number"} } },
                  "viewDefinition": { "views": [
                    { "id": "only", "components": [ { "id": "a", "type": "numericField", "bind": "$.a" } ] } ] } }
                """);
        assertThat(w).isEmpty();
    }

    @Test
    void a_nested_navigation_control_still_makes_its_target_reachable() throws Exception {
        var w = lint("""
                { "id": "m", "schema": { "properties": { "a": {"type":"number"} } },
                  "viewDefinition": { "defaultView": "main", "views": [
                    { "id": "main", "components": [ { "id": "card", "type": "card", "components": [
                      { "id": "go", "type": "button", "label": "Next",
                        "onClick": { "navigate": "second" } } ] } ] },
                    { "id": "second", "components": [ { "id": "back", "type": "button", "label": "Back",
                      "onClick": { "navigate": "main" } } ] } ] } }
                """);
        assertThat(w).isEmpty();
    }

    // ── Inline lists raise none of this ─────────────────────────────────────────

    @Test
    void a_sectionList_editing_its_elements_inline_needs_no_second_view() throws Exception {
        // The shape the prompt now steers to: no itemView, so no scope to carry and nowhere to
        // return from. It must stay silent, or every well-formed list model pays a repair round-trip.
        var w = lint("""
                { "id": "debts",
                  "schema": { "properties": { "debts": { "type": "array", "items": {
                      "properties": { "name": {"type":"string"} } } } } },
                  "viewDefinition": { "defaultView": "main", "views": [
                    { "id": "main", "components": [
                      { "id": "debtsList", "type": "sectionList", "label": "Your debts",
                        "bind": "$.debts", "components": [
                          { "id": "n", "type": "textField", "label": "Name",
                            "bind": "$.debts[*].name" } ] } ] } ] } }
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
