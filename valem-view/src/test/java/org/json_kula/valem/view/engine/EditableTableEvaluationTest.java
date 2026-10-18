package org.json_kula.valem.view.engine;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.json_kula.valem.core.engine.ExpressionCache;
import org.json_kula.valem.view.model.BasicInputSpec;
import org.json_kula.valem.view.model.EditableTableSpec;
import org.json_kula.valem.view.model.LabelSpec;
import org.json_kula.valem.view.model.ViewDefinition;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** {@code editableTable}: typed parse of both row modes, and server-side evaluation. */
class EditableTableEvaluationTest {

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);

    private static final String VIEW = """
            { "defaultView": "main", "views": [ { "id": "main", "components": [
              { "id": "itemsTable", "type": "editableTable", "label": "Items", "bind": "$.items",
                "canRemove": "$count(items) > 1", "addLabel": "Add item",
                "rowHeader": { "header": "Product", "width": "30%%", "components": [
                  { "id": "itemName", "type": "textField", "bind": "$.items[*].name" } ] },
                "gridColumns": [
                  { "id": "price", "header": "Price", "components": [
                    { "id": "itemPrice", "type": "numericField", "bind": "$.items[*].price" } ] },
                  { "id": "total", "header": "Total", "align": "right", "components": [
                    { "id": "itemTotal", "type": "label", "bind": "$.items[*].lineTotal" } ] } ] },
              { "id": "quarters", "type": "editableTable", "canAdd": true,
                "gridColumns": [ { "id": "amount", "header": "Amount", "components": [
                  { "id": "fallback", "type": "staticText", "text": "'n/a'" } ] } ],
                "gridRows": [
                  { "id": "r1", "header": "Q1", "cells": { "amount": [
                    { "id": "q1Field", "type": "numericField", "bind": "$.q1" } ] } },
                  { "id": "r2", "header": "Q2" } ] }
            ] } ] }
            """;

    @Test
    void both_row_modes_parse_to_typed_records() throws Exception {
        ViewDefinition vd = MAPPER.readValue(VIEW.formatted(), ViewDefinition.class);
        var components = vd.views().getFirst().components();

        EditableTableSpec items = (EditableTableSpec) components.get(0);
        assertThat(items.bind()).isEqualTo("$.items");
        assertThat(items.rowHeader().header()).isEqualTo("Product");
        assertThat(items.rowHeader().components().getFirst()).isInstanceOf(BasicInputSpec.class);
        assertThat(items.gridColumns()).extracting("header").containsExactly("Price", "Total");
        assertThat(items.gridColumns().get(1).align()).isEqualTo("right");
        assertThat(items.gridColumns().get(1).components().getFirst()).isInstanceOf(LabelSpec.class);

        EditableTableSpec quarters = (EditableTableSpec) components.get(1);
        assertThat(quarters.bind()).isNull();
        assertThat(quarters.gridRows()).extracting("header").containsExactly("Q1", "Q2");
        assertThat(quarters.gridRows().getFirst().cells().get("amount").getFirst().id()).isEqualTo("q1Field");
        assertThat(quarters.gridRows().get(1).cells()).isNull();
    }

    @Test
    void array_table_evaluates_columns_as_templates_and_carries_the_rows() throws Exception {
        ObjectNode doc = (ObjectNode) MAPPER.readTree("""
                { "items": [ { "name": "Apple", "price": 1.5, "lineTotal": 6 },
                             { "name": "Bread", "price": 2.75, "lineTotal": 5.5 } ], "q1": 10 }
                """);
        EvaluatedEditableTable t = (EvaluatedEditableTable) evaluate(doc).components().get(0);

        assertThat(t.value().size()).isEqualTo(2);
        assertThat(t.rowHeader().header()).isEqualTo("Product");
        assertThat(t.rowHeader().components().getFirst().bind()).isEqualTo("$.items[*].name");
        assertThat(t.gridColumns()).extracting("id").containsExactly("price", "total");
        assertThat(t.canAdd()).isTrue();
        assertThat(t.canRemove()).as("canRemove expression: two items").isTrue();
        assertThat(t.addLabel()).isEqualTo("Add item");
    }

    @Test
    void can_remove_expression_is_evaluated_against_the_document() throws Exception {
        ObjectNode doc = (ObjectNode) MAPPER.readTree("{ \"items\": [ { \"name\": \"Only\" } ] }");
        EvaluatedEditableTable t = (EvaluatedEditableTable) evaluate(doc).components().get(0);
        assertThat(t.canRemove()).isFalse();
    }

    @Test
    void fixed_row_table_evaluates_each_cell_and_never_offers_add_or_remove() throws Exception {
        ObjectNode doc = (ObjectNode) MAPPER.readTree("{ \"q1\": 10 }");
        EvaluatedEditableTable t = (EvaluatedEditableTable) evaluate(doc).components().get(1);

        assertThat(t.gridRows()).extracting("header").containsExactly("Q1", "Q2");
        EvaluatedComponent q1 = t.gridRows().getFirst().cells().get("amount").getFirst();
        assertThat(q1.value().asInt()).isEqualTo(10);
        // canAdd: true is authored, but a fixed grid has no array to add to.
        assertThat(t.canAdd()).isFalse();
        assertThat(t.canRemove()).isFalse();
    }

    @Test
    void evaluated_table_serializes_its_cells() throws Exception {
        ObjectNode doc = (ObjectNode) MAPPER.readTree("{ \"q1\": 10 }");
        JsonNode json = MAPPER.valueToTree(evaluate(doc).components().get(1));
        assertThat(json.path("type").asText()).isEqualTo("editableTable");
        assertThat(json.at("/gridRows/0/cells/amount/0/id").asText()).isEqualTo("q1Field");
        assertThat(json.at("/gridColumns/0/header").asText()).isEqualTo("Amount");
    }

    private static EvaluatedView evaluate(ObjectNode doc) throws Exception {
        ViewDefinition vd = MAPPER.readValue(VIEW.formatted(), ViewDefinition.class);
        return ViewEvaluator.evaluate("m", vd.views().getFirst(), doc, Map.of(), new ExpressionCache());
    }
}
