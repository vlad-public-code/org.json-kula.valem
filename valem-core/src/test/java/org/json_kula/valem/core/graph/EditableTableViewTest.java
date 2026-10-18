package org.json_kula.valem.core.graph;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.json_kula.valem.core.model.ModelSpec;
import org.json_kula.valem.core.model.ViewComponentTypes;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@code editableTable} keeps its child components per cell — under {@code rowHeader},
 * {@code gridColumns[*]} and {@code gridRows[*].cells} — not in {@code components}. Every raw tree
 * walk must still reach them, or a typo, a duplicate id or a dangling bind inside a cell would pass
 * every server-side check and only fail in the browser.
 */
class EditableTableViewTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** An array-bound table and a fixed-row table; {@code CELLS} is spliced into the first column. */
    private static String spec(String priceCell) {
        return """
                { "id": "order", "version": "1.0.0",
                  "schema": { "type": "object", "properties": {
                    "items": { "type": "array", "items": { "type": "object", "properties": {
                      "name": { "type": "string" }, "price": { "type": "number" } } } },
                    "q1": { "type": "number" }, "q2": { "type": "number" } } },
                  "viewDefinition": { "defaultView": "main", "views": [
                    { "id": "main", "components": [
                      { "id": "itemsTable", "type": "editableTable", "bind": "$.items",
                        "rowHeader": { "header": "Product", "components": [
                          { "id": "itemName", "type": "textField", "bind": "$.items[*].name" } ] },
                        "gridColumns": [
                          { "id": "price", "header": "Price", "components": [ %s ] } ] },
                      { "id": "quarters", "type": "editableTable",
                        "gridColumns": [ { "id": "amount", "header": "Amount" } ],
                        "gridRows": [
                          { "id": "r1", "header": "Q1", "cells": { "amount": [
                            { "id": "q1Field", "type": "numericField", "bind": "$.q1" } ] } },
                          { "id": "r2", "header": "Q2", "cells": { "amount": [
                            { "id": "q2Field", "type": "numericField", "bind": "$.q2" } ] } } ] }
                    ] } ] } }
                """.formatted(priceCell);
    }

    private static final String GOOD_CELL =
            "{ \"id\": \"itemPrice\", \"type\": \"numericField\", \"bind\": \"$.items[*].price\" }";

    @Test
    void editable_table_is_a_known_component_type() {
        assertThat(ViewComponentTypes.isKnown("editableTable")).isTrue();
        assertThat(ViewComponentTypes.AGGREGATES).contains("editableTable");
    }

    @Test
    void child_component_lists_cover_row_header_columns_and_fixed_row_cells() throws Exception {
        JsonNode components = model(spec(GOOD_CELL)).viewDefinition().path("views").path(0).path("components");

        List<String> arrayTable = ids(ViewComponentTypes.childComponentLists(components.get(0)));
        assertThat(arrayTable).containsExactly("itemName", "itemPrice");

        List<String> fixedTable = ids(ViewComponentTypes.childComponentLists(components.get(1)));
        assertThat(fixedTable).containsExactly("q1Field", "q2Field");
    }

    @Test
    void a_well_formed_table_validates_with_no_view_warnings() throws Exception {
        ModelSpec spec = model(spec(GOOD_CELL));
        assertThat(ModelSpecValidator.validate(spec).isValid()).isTrue();
        assertThat(ModelSpecValidator.lintView(spec)).isEmpty();
    }

    @Test
    void an_unknown_type_inside_a_cell_is_an_error() throws Exception {
        var result = ModelSpecValidator.validate(model(spec(
                "{ \"id\": \"itemPrice\", \"type\": \"number-input\", \"bind\": \"$.items[*].price\" }")));
        assertThat(result.isValid()).isFalse();
        assertThat(messages(result.errors())).anyMatch(m -> m.contains("Unknown component type 'number-input'"));
    }

    @Test
    void a_duplicate_id_inside_a_cell_is_an_error() throws Exception {
        var result = ModelSpecValidator.validate(model(spec(
                "{ \"id\": \"itemName\", \"type\": \"numericField\", \"bind\": \"$.items[*].price\" }")));
        assertThat(messages(result.errors())).anyMatch(m -> m.contains("Duplicate component id") && m.contains("itemName"));
    }

    @Test
    void a_dangling_bind_inside_a_cell_is_linted() throws Exception {
        var warnings = ModelSpecValidator.lintView(model(spec(
                "{ \"id\": \"itemPrice\", \"type\": \"numericField\", \"bind\": \"$.unitCost\" }")));
        assertThat(messages(warnings)).anyMatch(m -> m.contains("$.unitCost"));
    }

    @Test
    void a_cell_component_can_be_replaced_and_removed_by_id() throws Exception {
        ModelSpec base = model(spec(GOOD_CELL));

        ModelSpec replaced = MAPPER.readValue("""
                { "upsertComponents": [ { "viewId": "main", "component":
                    { "id": "q2Field", "type": "currencyField", "bind": "$.q2" } } ] }
                """, SpecEvolution.class).applyTo(base);
        JsonNode q2Cell = replaced.viewDefinition().at("/views/0/components/1/gridRows/1/cells/amount/0");
        assertThat(q2Cell.path("type").asText()).isEqualTo("currencyField");

        ModelSpec removed = MAPPER.readValue("""
                { "removeComponents": [ { "viewId": "main", "componentId": "itemName" } ] }
                """, SpecEvolution.class).applyTo(base);
        assertThat(removed.viewDefinition().at("/views/0/components/0/rowHeader/components").size()).isZero();
    }

    @Test
    void inserting_under_a_table_by_parent_id_is_refused_with_guidance() throws Exception {
        SpecEvolution evo = MAPPER.readValue("""
                { "upsertComponents": [ { "viewId": "main", "parentId": "itemsTable", "component":
                    { "id": "note", "type": "staticText", "text": "hi" } } ] }
                """, SpecEvolution.class);
        assertThatThrownBy(() -> evo.applyTo(model(spec(GOOD_CELL))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("editableTable");
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private static ModelSpec model(String json) throws Exception {
        return MAPPER.readValue(json, ModelSpec.class);
    }

    private static List<String> ids(List<? extends JsonNode> lists) {
        return lists.stream().flatMap(a -> java.util.stream.StreamSupport.stream(a.spliterator(), false))
                .map(c -> c.path("id").asText()).toList();
    }

    private static List<String> messages(List<ModelSpecValidator.ValidationError> errors) {
        return errors.stream().map(ModelSpecValidator.ValidationError::message).toList();
    }
}
