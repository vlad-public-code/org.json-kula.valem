package org.json_kula.valem.view.model;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.BooleanNode;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ViewDefinitionDeserializationTest {

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);

    // ── ViewDefinition ────────────────────────────────────────────────────────

    @Test
    void minimal_view_definition_deserializes() throws Exception {
        ViewDefinition vd = MAPPER.readValue("""
                {
                  "views": [
                    { "id": "main", "label": "Main", "components": [] }
                  ],
                  "defaultView": "main"
                }
                """, ViewDefinition.class);

        assertThat(vd.defaultView()).isEqualTo("main");
        assertThat(vd.views()).hasSize(1);
        assertThat(vd.views().getFirst().id()).isEqualTo("main");
    }

    @Test
    void renderer_defaults_to_builtin_when_absent() throws Exception {
        ViewDefinition vd = MAPPER.readValue("""
                { "views": [], "defaultView": null }
                """, ViewDefinition.class);
        assertThat(vd.renderer()).isEqualTo("builtin");
    }

    @Test
    void explicit_renderer_is_preserved() throws Exception {
        ViewDefinition vd = MAPPER.readValue("""
                { "renderer": "custom", "views": [], "defaultView": null }
                """, ViewDefinition.class);
        assertThat(vd.renderer()).isEqualTo("custom");
    }

    @Test
    void views_list_is_immutable() throws Exception {
        ViewDefinition vd = MAPPER.readValue("""
                { "views": [ { "id": "v1", "label": "V1", "components": [] } ] }
                """, ViewDefinition.class);
        assertThatThrownBy(() -> vd.views().add(null))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    // ── ViewSpec ──────────────────────────────────────────────────────────────

    @Test
    void view_spec_layout_defaults_to_vertical() throws Exception {
        ViewDefinition vd = MAPPER.readValue("""
                { "views": [ { "id": "v1", "label": "V1", "components": [] } ] }
                """, ViewDefinition.class);
        assertThat(vd.views().getFirst().layout()).isEqualTo("vertical");
    }

    @Test
    void view_spec_preserves_grid_layout_and_columns() throws Exception {
        ViewSpec view = MAPPER.readValue("""
                { "id": "v1", "label": "V1", "layout": "grid", "columns": 3, "components": [] }
                """, ViewSpec.class);
        assertThat(view.layout()).isEqualTo("grid");
        assertThat(view.columns()).isEqualTo(3);
    }

    // ── ComponentSpec ─────────────────────────────────────────────────────────

    @Test
    void component_spec_minimal_fields_deserialize() throws Exception {
        ComponentSpec c = MAPPER.readValue("""
                { "id": "f1", "type": "textField" }
                """, ComponentSpec.class);
        assertThat(c.id()).isEqualTo("f1");
        assertThat(c.type()).isEqualTo("textField");
        assertThat(c.visible()).isNull();
        assertThat(c.bind()).isNull();
        assertThat(c.options()).isNull();
    }

    @Test
    void component_spec_with_all_dynamic_fields() throws Exception {
        ComponentSpec c = MAPPER.readValue("""
                {
                  "id": "f1", "type": "textField",
                  "label": "Name",
                  "visible": true,
                  "readOnly": false,
                  "required": true,
                  "enabled": "active = true",
                  "bind": "$.name",
                  "placeholder": "Enter name",
                  "helperText": "Full legal name"
                }
                """, ComponentSpec.class);
        assertThat(c.label()).isEqualTo("Name");
        assertThat(c.visible()).isEqualTo(BooleanNode.TRUE);
        assertThat(c.readOnly()).isEqualTo(BooleanNode.FALSE);
        assertThat(c.required()).isEqualTo(BooleanNode.TRUE);
        assertThat(c.enabled().asText()).isEqualTo("active = true");
        assertThat(c.bind()).isEqualTo("$.name");
        assertThat(c.placeholder()).isEqualTo("Enter name");
        assertThat(c.helperText()).isEqualTo("Full legal name");
    }

    @Test
    void component_spec_options_are_immutable() throws Exception {
        ComponentSpec c = MAPPER.readValue("""
                {
                  "id": "s1", "type": "selectField",
                  "options": [
                    {"value": "a", "label": "A"},
                    {"value": "b", "label": "B"}
                  ]
                }
                """, ComponentSpec.class);
        assertThat(c.options()).hasSize(2);
        assertThatThrownBy(() -> c.options().add(null))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void nested_components_deserialize_recursively() throws Exception {
        ComponentSpec group = MAPPER.readValue("""
                {
                  "id": "grp", "type": "group",
                  "layout": "horizontal",
                  "components": [
                    { "id": "inner1", "type": "textField", "bind": "$.firstName" },
                    { "id": "inner2", "type": "textField", "bind": "$.lastName" }
                  ]
                }
                """, ComponentSpec.class);
        assertThat(group.components()).hasSize(2);
        assertThat(group.components().get(0).bind()).isEqualTo("$.firstName");
        assertThat(group.components().get(1).id()).isEqualTo("inner2");
    }

    @Test
    void table_columns_deserialize() throws Exception {
        ComponentSpec c = MAPPER.readValue("""
                {
                  "id": "t1", "type": "dataTable",
                  "bind": "$.items",
                  "tableColumns": [
                    { "field": "name",  "header": "Name",  "width": "50%" },
                    { "field": "price", "header": "Price", "format": "currency" }
                  ],
                  "pageSize": 25
                }
                """, ComponentSpec.class);
        assertThat(c.tableColumns()).hasSize(2);
        assertThat(c.tableColumns().get(1).format()).isEqualTo("currency");
        assertThat(c.pageSize()).isEqualTo(25);
    }

    @Test
    void event_handler_deserializes() throws Exception {
        ComponentSpec c = MAPPER.readValue("""
                {
                  "id": "btn", "type": "button",
                  "label": "Submit",
                  "onClick": {
                    "mutations": "$string(status)",
                    "navigate": "confirm"
                  }
                }
                """, ComponentSpec.class);
        assertThat(c.onClick()).isNotNull();
        assertThat(c.onClick().navigate()).isEqualTo("confirm");
        assertThat(c.onClick().mutations()).isEqualTo("$string(status)");
    }

    // ── new component types ───────────────────────────────────────────────────

    @Test
    void sliderField_deserializes_min_max_step() throws Exception {
        ComponentSpec c = MAPPER.readValue("""
                {
                  "id": "vol", "type": "sliderField",
                  "bind": "$.volume",
                  "label": "Volume",
                  "min": 0,
                  "max": 100,
                  "step": 5
                }
                """, ComponentSpec.class);
        assertThat(c.type()).isEqualTo("sliderField");
        assertThat(c.bind()).isEqualTo("$.volume");
        assertThat(c.min()).isEqualTo(0.0);
        assertThat(c.max()).isEqualTo(100.0);
        assertThat(c.step()).isEqualTo(5.0);
    }

    @Test
    void sliderField_min_max_step_default_to_null_when_absent() throws Exception {
        ComponentSpec c = MAPPER.readValue("""
                { "id": "s1", "type": "sliderField", "bind": "$.x" }
                """, ComponentSpec.class);
        assertThat(c.min()).isNull();
        assertThat(c.max()).isNull();
        assertThat(c.step()).isNull();
    }

    @Test
    void timeField_deserializes_common_fields() throws Exception {
        ComponentSpec c = MAPPER.readValue("""
                {
                  "id": "start", "type": "timeField",
                  "bind": "$.startTime",
                  "label": "Start Time",
                  "helperText": "HH:mm format"
                }
                """, ComponentSpec.class);
        assertThat(c.type()).isEqualTo("timeField");
        assertThat(c.bind()).isEqualTo("$.startTime");
        assertThat(c.helperText()).isEqualTo("HH:mm format");
        assertThat(c.min()).isNull();
        assertThat(c.accept()).isNull();
    }

    @Test
    void fileUploadField_deserializes_accept() throws Exception {
        ComponentSpec c = MAPPER.readValue("""
                {
                  "id": "avatar", "type": "fileUploadField",
                  "bind": "$.avatarBlob",
                  "label": "Profile Photo",
                  "accept": "image/*"
                }
                """, ComponentSpec.class);
        assertThat(c.type()).isEqualTo("fileUploadField");
        assertThat(c.accept()).isEqualTo("image/*");
        assertThat(c.bind()).isEqualTo("$.avatarBlob");
    }

    @Test
    void fileUploadField_accept_defaults_to_null_when_absent() throws Exception {
        ComponentSpec c = MAPPER.readValue("""
                { "id": "f1", "type": "fileUploadField", "bind": "$.file" }
                """, ComponentSpec.class);
        assertThat(c.accept()).isNull();
    }

    @Test
    void progressBar_deserializes_all_display_fields() throws Exception {
        ComponentSpec c = MAPPER.readValue("""
                {
                  "id": "prog", "type": "progressBar",
                  "bind": "$.completionPct",
                  "label": "Completion",
                  "min": 0,
                  "max": 100,
                  "showValue": true,
                  "format": "percent"
                }
                """, ComponentSpec.class);
        assertThat(c.type()).isEqualTo("progressBar");
        assertThat(c.bind()).isEqualTo("$.completionPct");
        assertThat(c.min()).isEqualTo(0.0);
        assertThat(c.max()).isEqualTo(100.0);
        assertThat(c.showValue()).isTrue();
        assertThat(c.format()).isEqualTo("percent");
    }

    @Test
    void progressBar_value_format_deserializes() throws Exception {
        ComponentSpec c = MAPPER.readValue("""
                { "id": "pb", "type": "progressBar", "bind": "$.x",
                  "showValue": false, "format": "value" }
                """, ComponentSpec.class);
        assertThat(c.showValue()).isFalse();
        assertThat(c.format()).isEqualTo("value");
    }

    @Test
    void progressBar_display_fields_default_to_null_when_absent() throws Exception {
        ComponentSpec c = MAPPER.readValue("""
                { "id": "pb2", "type": "progressBar", "bind": "$.x" }
                """, ComponentSpec.class);
        assertThat(c.showValue()).isNull();
        assertThat(c.format()).isNull();
        assertThat(c.min()).isNull();
        assertThat(c.max()).isNull();
    }

    @Test
    void fileUploadField_multi_file_constraints_deserialize() throws Exception {
        ComponentSpec c = MAPPER.readValue("""
                {
                  "id": "docs", "type": "fileUploadField",
                  "bind": "$.attachments",
                  "label": "Attachments",
                  "accept": "image/*,application/pdf",
                  "multiple": true,
                  "minFiles": 1,
                  "maxFiles": 5,
                  "minSize": 1024,
                  "maxSize": 5242880,
                  "allowedMediaTypes": "image/jpeg,image/png,application/pdf"
                }
                """, ComponentSpec.class);
        assertThat(c.multiple()).isTrue();
        assertThat(c.minFiles()).isEqualTo(1);
        assertThat(c.maxFiles()).isEqualTo(5);
        assertThat(c.minSize()).isEqualTo(1024L);
        assertThat(c.maxSize()).isEqualTo(5242880L);
        assertThat(c.allowedMediaTypes()).isEqualTo("image/jpeg,image/png,application/pdf");
    }

    @Test
    void fileUploadField_multi_file_constraints_default_to_null() throws Exception {
        ComponentSpec c = MAPPER.readValue("""
                { "id": "f1", "type": "fileUploadField", "bind": "$.file" }
                """, ComponentSpec.class);
        assertThat(c.multiple()).isNull();
        assertThat(c.minFiles()).isNull();
        assertThat(c.maxFiles()).isNull();
        assertThat(c.minSize()).isNull();
        assertThat(c.maxSize()).isNull();
        assertThat(c.allowedMediaTypes()).isNull();
    }

    @Test
    void unknown_fields_are_ignored() throws Exception {
        // The ObjectMapper is configured with FAIL_ON_UNKNOWN_PROPERTIES = false,
        // matching how the console app and API deserialize view specs from LLM output.
        ComponentSpec c = MAPPER.readValue("""
                { "id": "x", "type": "textField", "unknownFutureField": 42 }
                """, ComponentSpec.class);
        assertThat(c.id()).isEqualTo("x");
    }
}
