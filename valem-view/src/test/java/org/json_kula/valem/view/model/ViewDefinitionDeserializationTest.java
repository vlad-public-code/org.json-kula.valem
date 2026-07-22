package org.json_kula.valem.view.model;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.BooleanNode;
import org.junit.jupiter.api.Test;

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

    // ── type dispatch ─────────────────────────────────────────────────────────

    @Test
    void type_selects_the_record_that_carries_that_type_s_fields() throws Exception {
        assertThat(parse("{ \"id\": \"a\", \"type\": \"textField\" }")).isInstanceOf(BasicInputSpec.class);
        assertThat(parse("{ \"id\": \"a\", \"type\": \"textAreaField\" }")).isInstanceOf(TextAreaSpec.class);
        assertThat(parse("{ \"id\": \"a\", \"type\": \"selectField\" }")).isInstanceOf(ChoiceInputSpec.class);
        assertThat(parse("{ \"id\": \"a\", \"type\": \"radioField\" }")).isInstanceOf(ChoiceInputSpec.class);
        assertThat(parse("{ \"id\": \"a\", \"type\": \"countryRegionSelector\" }")).isInstanceOf(DependentSelectorSpec.class);
        assertThat(parse("{ \"id\": \"a\", \"type\": \"sliderField\" }")).isInstanceOf(SliderSpec.class);
        assertThat(parse("{ \"id\": \"a\", \"type\": \"fileUploadField\" }")).isInstanceOf(FileUploadSpec.class);
        assertThat(parse("{ \"id\": \"a\", \"type\": \"label\" }")).isInstanceOf(LabelSpec.class);
        assertThat(parse("{ \"id\": \"a\", \"type\": \"staticText\" }")).isInstanceOf(StaticTextSpec.class);
        assertThat(parse("{ \"id\": \"a\", \"type\": \"badge\" }")).isInstanceOf(BadgeSpec.class);
        assertThat(parse("{ \"id\": \"a\", \"type\": \"separatorLine\" }")).isInstanceOf(SeparatorLineSpec.class);
        assertThat(parse("{ \"id\": \"a\", \"type\": \"progressBar\" }")).isInstanceOf(ProgressBarSpec.class);
        assertThat(parse("{ \"id\": \"a\", \"type\": \"dataTable\" }")).isInstanceOf(DataTableSpec.class);
        assertThat(parse("{ \"id\": \"a\", \"type\": \"dataChart\" }")).isInstanceOf(DataChartSpec.class);
        assertThat(parse("{ \"id\": \"a\", \"type\": \"group\" }")).isInstanceOf(ContainerSpec.class);
        assertThat(parse("{ \"id\": \"a\", \"type\": \"fieldSet\" }")).isInstanceOf(ContainerSpec.class);
        assertThat(parse("{ \"id\": \"a\", \"type\": \"sectionItem\" }")).isInstanceOf(ContainerSpec.class);
        assertThat(parse("{ \"id\": \"a\", \"type\": \"sectionList\" }")).isInstanceOf(SectionListSpec.class);
        assertThat(parse("{ \"id\": \"a\", \"type\": \"button\" }")).isInstanceOf(ButtonSpec.class);
        assertThat(parse("{ \"id\": \"a\", \"type\": \"menu\" }")).isInstanceOf(MenuSpec.class);
    }

    @Test
    void added_types_land_on_the_record_that_already_had_their_field_shape() throws Exception {
        // Grouping by field shape rather than one record per type is what keeps these free: a
        // rating is a slider's min/max/step, an alert is a badge's label/text/variant.
        assertThat(parse("{ \"id\": \"a\", \"type\": \"currencyField\" }")).isInstanceOf(BasicInputSpec.class);
        assertThat(parse("{ \"id\": \"a\", \"type\": \"percentField\" }")).isInstanceOf(BasicInputSpec.class);
        assertThat(parse("{ \"id\": \"a\", \"type\": \"richTextField\" }")).isInstanceOf(TextAreaSpec.class);
        assertThat(parse("{ \"id\": \"a\", \"type\": \"autocompleteField\" }")).isInstanceOf(ChoiceInputSpec.class);
        assertThat(parse("{ \"id\": \"a\", \"type\": \"comboBox\" }")).isInstanceOf(ChoiceInputSpec.class);
        assertThat(parse("{ \"id\": \"a\", \"type\": \"tagsField\" }")).isInstanceOf(ChoiceInputSpec.class);
        assertThat(parse("{ \"id\": \"a\", \"type\": \"ratingField\" }")).isInstanceOf(SliderSpec.class);
        assertThat(parse("{ \"id\": \"a\", \"type\": \"numericStepper\" }")).isInstanceOf(SliderSpec.class);
        assertThat(parse("{ \"id\": \"a\", \"type\": \"alert\" }")).isInstanceOf(BadgeSpec.class);
        assertThat(parse("{ \"id\": \"a\", \"type\": \"callout\" }")).isInstanceOf(BadgeSpec.class);
        assertThat(parse("{ \"id\": \"a\", \"type\": \"spacer\" }")).isInstanceOf(SeparatorLineSpec.class);
        assertThat(parse("{ \"id\": \"a\", \"type\": \"gauge\" }")).isInstanceOf(ProgressBarSpec.class);
        assertThat(parse("{ \"id\": \"a\", \"type\": \"sparkline\" }")).isInstanceOf(DataChartSpec.class);
        assertThat(parse("{ \"id\": \"a\", \"type\": \"card\" }")).isInstanceOf(ContainerSpec.class);
        assertThat(parse("{ \"id\": \"a\", \"type\": \"toolbar\" }")).isInstanceOf(ContainerSpec.class);
        assertThat(parse("{ \"id\": \"a\", \"type\": \"buttonGroup\" }")).isInstanceOf(ContainerSpec.class);
        assertThat(parse("{ \"id\": \"a\", \"type\": \"tabs\" }")).isInstanceOf(ContainerSpec.class);
        assertThat(parse("{ \"id\": \"a\", \"type\": \"tabItem\" }")).isInstanceOf(ContainerSpec.class);
        assertThat(parse("{ \"id\": \"a\", \"type\": \"accordion\" }")).isInstanceOf(ContainerSpec.class);
        assertThat(parse("{ \"id\": \"a\", \"type\": \"collapsible\" }")).isInstanceOf(ContainerSpec.class);
        assertThat(parse("{ \"id\": \"a\", \"type\": \"stepper\" }")).isInstanceOf(MenuSpec.class);
        assertThat(parse("{ \"id\": \"a\", \"type\": \"breadcrumb\" }")).isInstanceOf(MenuSpec.class);
    }

    @Test
    void added_types_with_their_own_shape_get_their_own_record() throws Exception {
        assertThat(parse("{ \"id\": \"a\", \"type\": \"dateRangeField\" }")).isInstanceOf(DateRangeSpec.class);
        assertThat(parse("{ \"id\": \"a\", \"type\": \"image\" }")).isInstanceOf(ImageSpec.class);
        assertThat(parse("{ \"id\": \"a\", \"type\": \"link\" }")).isInstanceOf(LinkSpec.class);
        assertThat(parse("{ \"id\": \"a\", \"type\": \"keyValueList\" }")).isInstanceOf(KeyValueListSpec.class);
        assertThat(parse("{ \"id\": \"a\", \"type\": \"summaryList\" }")).isInstanceOf(KeyValueListSpec.class);
        assertThat(parse("{ \"id\": \"a\", \"type\": \"statTile\" }")).isInstanceOf(StatTileSpec.class);
        assertThat(parse("{ \"id\": \"a\", \"type\": \"metric\" }")).isInstanceOf(StatTileSpec.class);
        assertThat(parse("{ \"id\": \"a\", \"type\": \"jsonViewer\" }")).isInstanceOf(JsonViewerSpec.class);
        assertThat(parse("{ \"id\": \"a\", \"type\": \"explainPanel\" }")).isInstanceOf(TracePanelSpec.class);
        assertThat(parse("{ \"id\": \"a\", \"type\": \"auditTimeline\" }")).isInstanceOf(TracePanelSpec.class);
        assertThat(parse("{ \"id\": \"a\", \"type\": \"validationSummary\" }")).isInstanceOf(ValidationSummarySpec.class);
        assertThat(parse("{ \"id\": \"a\", \"type\": \"effectStatus\" }")).isInstanceOf(EffectStatusSpec.class);
    }

    @Test
    void the_new_fields_bind_by_name() throws Exception {
        BasicInputSpec money = (BasicInputSpec) parse("""
                { "id": "p", "type": "currencyField", "bind": "$.premium",
                  "format": "currency", "currency": "GBP" }
                """);
        assertThat(money.currency()).isEqualTo("GBP");

        ChoiceInputSpec tags = (ChoiceInputSpec) parse("""
                { "id": "t", "type": "tagsField", "bind": "$.tags", "allowCustom": false }
                """);
        assertThat(tags.allowCustom()).isFalse();

        ContainerSpec panel = (ContainerSpec) parse("""
                { "id": "s", "type": "collapsible", "label": "Details", "collapsed": true }
                """);
        assertThat(panel.collapsed().asBoolean()).isTrue();

        DateRangeSpec range = (DateRangeSpec) parse("""
                { "id": "d", "type": "dateRangeField",
                  "bindFrom": "$.start", "bindTo": "$.end", "minDate": "2026-01-01" }
                """);
        assertThat(range.bindFrom()).isEqualTo("$.start");
        assertThat(range.bindTo()).isEqualTo("$.end");
        assertThat(range.minDate()).isEqualTo("2026-01-01");
    }

    @Test
    void key_value_rows_deserialize_into_their_supporting_record() throws Exception {
        KeyValueListSpec kv = (KeyValueListSpec) parse("""
                {
                  "id": "summary", "type": "summaryList", "label": "Quote",
                  "items": [
                    { "label": "Premium", "bind": "$.premium", "format": "currency" },
                    { "label": "Reference", "text": "$.ref" }
                  ]
                }
                """);
        assertThat(kv.items()).hasSize(2);
        assertThat(kv.items().getFirst().label()).isEqualTo("Premium");
        assertThat(kv.items().getFirst().format()).isEqualTo("currency");
        assertThat(kv.items().get(1).text().asText()).isEqualTo("$.ref");
    }

    @Test
    void key_value_items_list_is_immutable() throws Exception {
        KeyValueListSpec kv = (KeyValueListSpec) parse("""
                { "id": "s", "type": "keyValueList", "items": [ { "label": "A", "bind": "$.a" } ] }
                """);
        assertThatThrownBy(() -> kv.items().add(null))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void type_is_retained_on_the_record_so_grouped_types_stay_distinguishable() throws Exception {
        assertThat(parse("{ \"id\": \"a\", \"type\": \"radioField\" }").type()).isEqualTo("radioField");
        assertThat(parse("{ \"id\": \"a\", \"type\": \"fieldSet\" }").type()).isEqualTo("fieldSet");
    }

    @Test
    void id_and_type_are_required() {
        assertThatThrownBy(() -> parse("{ \"type\": \"textField\" }"))
                .hasRootCauseInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("'id' is required");
        assertThatThrownBy(() -> parse("{ \"id\": \"a\" }"))
                .hasRootCauseInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("'type' is required");
    }

    // ── common fields ─────────────────────────────────────────────────────────

    @Test
    void component_spec_minimal_fields_deserialize() throws Exception {
        BasicInputSpec c = (BasicInputSpec) parse("{ \"id\": \"f1\", \"type\": \"textField\" }");
        assertThat(c.id()).isEqualTo("f1");
        assertThat(c.type()).isEqualTo("textField");
        assertThat(c.visible()).isNull();
        assertThat(c.bind()).isNull();
    }

    @Test
    void component_spec_with_all_dynamic_fields() throws Exception {
        BasicInputSpec c = (BasicInputSpec) parse("""
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
                """);
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
        ChoiceInputSpec c = (ChoiceInputSpec) parse("""
                {
                  "id": "s1", "type": "selectField",
                  "options": [
                    {"value": "a", "label": "A"},
                    {"value": "b", "label": "B"}
                  ]
                }
                """);
        assertThat(c.options()).hasSize(2);
        assertThatThrownBy(() -> c.options().add(null))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void nested_components_deserialize_recursively() throws Exception {
        ContainerSpec group = (ContainerSpec) parse("""
                {
                  "id": "grp", "type": "group",
                  "layout": "horizontal",
                  "components": [
                    { "id": "inner1", "type": "textField", "bind": "$.firstName" },
                    { "id": "inner2", "type": "textField", "bind": "$.lastName" }
                  ]
                }
                """);
        assertThat(group.components()).hasSize(2);
        assertThat(group.components().get(0).bind()).isEqualTo("$.firstName");
        assertThat(group.components().get(1).id()).isEqualTo("inner2");
    }

    @Test
    void sectionItem_carries_bind_and_children() throws Exception {
        ContainerSpec item = (ContainerSpec) parse("""
                {
                  "id": "row", "type": "sectionItem",
                  "bind": "$.items[0]",
                  "components": [ { "id": "qty", "type": "numericField", "bind": "$.items[0].qty" } ]
                }
                """);
        assertThat(item.bind()).isEqualTo("$.items[0]");
        assertThat(item.components()).hasSize(1);
    }

    @Test
    void table_columns_deserialize() throws Exception {
        DataTableSpec c = (DataTableSpec) parse("""
                {
                  "id": "t1", "type": "dataTable",
                  "bind": "$.items",
                  "tableColumns": [
                    { "field": "name",  "header": "Name",  "width": "50%" },
                    { "field": "price", "header": "Price", "format": "currency" }
                  ],
                  "pageSize": 25
                }
                """);
        assertThat(c.tableColumns()).hasSize(2);
        assertThat(c.tableColumns().get(1).format()).isEqualTo("currency");
        assertThat(c.pageSize()).isEqualTo(25);
    }

    @Test
    void event_handler_deserializes() throws Exception {
        ButtonSpec c = (ButtonSpec) parse("""
                {
                  "id": "btn", "type": "button",
                  "label": "Submit",
                  "onClick": {
                    "mutations": "$string(status)",
                    "navigate": "confirm"
                  }
                }
                """);
        assertThat(c.onClick()).isNotNull();
        assertThat(c.onClick().navigate()).isEqualTo("confirm");
        assertThat(c.onClick().mutations()).isEqualTo("$string(status)");
    }

    // ── per-type fields ───────────────────────────────────────────────────────

    @Test
    void sliderField_deserializes_min_max_step() throws Exception {
        SliderSpec c = (SliderSpec) parse("""
                {
                  "id": "vol", "type": "sliderField",
                  "bind": "$.volume",
                  "label": "Volume",
                  "min": 0,
                  "max": 100,
                  "step": 5
                }
                """);
        assertThat(c.bind()).isEqualTo("$.volume");
        assertThat(c.min()).isEqualTo(0.0);
        assertThat(c.max()).isEqualTo(100.0);
        assertThat(c.step()).isEqualTo(5.0);
    }

    @Test
    void sliderField_min_max_step_default_to_null_when_absent() throws Exception {
        SliderSpec c = (SliderSpec) parse("{ \"id\": \"s1\", \"type\": \"sliderField\", \"bind\": \"$.x\" }");
        assertThat(c.min()).isNull();
        assertThat(c.max()).isNull();
        assertThat(c.step()).isNull();
    }

    @Test
    void timeField_deserializes_common_fields() throws Exception {
        BasicInputSpec c = (BasicInputSpec) parse("""
                {
                  "id": "start", "type": "timeField",
                  "bind": "$.startTime",
                  "label": "Start Time",
                  "helperText": "HH:mm format"
                }
                """);
        assertThat(c.type()).isEqualTo("timeField");
        assertThat(c.bind()).isEqualTo("$.startTime");
        assertThat(c.helperText()).isEqualTo("HH:mm format");
    }

    @Test
    void fields_belonging_to_another_type_are_not_bound() throws Exception {
        // A slider's min/max on a timeField are simply not part of that component's shape.
        ComponentSpec c = parse("""
                { "id": "start", "type": "timeField", "min": 0, "max": 100, "accept": "image/*" }
                """);
        assertThat(c).isInstanceOf(BasicInputSpec.class);
    }

    @Test
    void fileUploadField_deserializes_accept() throws Exception {
        FileUploadSpec c = (FileUploadSpec) parse("""
                {
                  "id": "avatar", "type": "fileUploadField",
                  "bind": "$.avatarBlob",
                  "label": "Profile Photo",
                  "accept": "image/*"
                }
                """);
        assertThat(c.accept()).isEqualTo("image/*");
        assertThat(c.bind()).isEqualTo("$.avatarBlob");
    }

    @Test
    void fileUploadField_accept_defaults_to_null_when_absent() throws Exception {
        FileUploadSpec c = (FileUploadSpec) parse(
                "{ \"id\": \"f1\", \"type\": \"fileUploadField\", \"bind\": \"$.file\" }");
        assertThat(c.accept()).isNull();
    }

    @Test
    void progressBar_deserializes_all_display_fields() throws Exception {
        ProgressBarSpec c = (ProgressBarSpec) parse("""
                {
                  "id": "prog", "type": "progressBar",
                  "bind": "$.completionPct",
                  "label": "Completion",
                  "min": 0,
                  "max": 100,
                  "showValue": true,
                  "format": "percent"
                }
                """);
        assertThat(c.bind()).isEqualTo("$.completionPct");
        assertThat(c.min()).isEqualTo(0.0);
        assertThat(c.max()).isEqualTo(100.0);
        assertThat(c.showValue()).isTrue();
        assertThat(c.format()).isEqualTo("percent");
    }

    @Test
    void progressBar_value_format_deserializes() throws Exception {
        ProgressBarSpec c = (ProgressBarSpec) parse("""
                { "id": "pb", "type": "progressBar", "bind": "$.x",
                  "showValue": false, "format": "value" }
                """);
        assertThat(c.showValue()).isFalse();
        assertThat(c.format()).isEqualTo("value");
    }

    @Test
    void progressBar_display_fields_default_to_null_when_absent() throws Exception {
        ProgressBarSpec c = (ProgressBarSpec) parse(
                "{ \"id\": \"pb2\", \"type\": \"progressBar\", \"bind\": \"$.x\" }");
        assertThat(c.showValue()).isNull();
        assertThat(c.format()).isNull();
        assertThat(c.min()).isNull();
        assertThat(c.max()).isNull();
    }

    @Test
    void fileUploadField_multi_file_constraints_deserialize() throws Exception {
        FileUploadSpec c = (FileUploadSpec) parse("""
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
                """);
        assertThat(c.multiple()).isTrue();
        assertThat(c.minFiles()).isEqualTo(1);
        assertThat(c.maxFiles()).isEqualTo(5);
        assertThat(c.minSize()).isEqualTo(1024L);
        assertThat(c.maxSize()).isEqualTo(5242880L);
        assertThat(c.allowedMediaTypes()).isEqualTo("image/jpeg,image/png,application/pdf");
    }

    @Test
    void fileUploadField_multi_file_constraints_default_to_null() throws Exception {
        FileUploadSpec c = (FileUploadSpec) parse(
                "{ \"id\": \"f1\", \"type\": \"fileUploadField\", \"bind\": \"$.file\" }");
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
        ComponentSpec c = parse("{ \"id\": \"x\", \"type\": \"textField\", \"unknownFutureField\": 42 }");
        assertThat(c.id()).isEqualTo("x");
    }

    // ── unknown component types ───────────────────────────────────────────────

    @Test
    void unknown_type_keeps_every_property_it_was_given() throws Exception {
        UnknownComponentSpec c = (UnknownComponentSpec) parse("""
                {
                  "id": "sig", "type": "signaturePad",
                  "label": "Sign here",
                  "bind": "$.signature",
                  "visible": "active = true",
                  "penColour": "#004488",
                  "strokeWidths": [1, 2, 3],
                  "advanced": { "smoothing": true }
                }
                """);

        assertThat(c.id()).isEqualTo("sig");
        assertThat(c.type()).isEqualTo("signaturePad");
        assertThat(c.bind()).isEqualTo("$.signature");
        assertThat(c.label()).isEqualTo("Sign here");
        assertThat(c.visible().asText()).isEqualTo("active = true");

        // properties no built-in component type declares survive verbatim
        assertThat(c.property("penColour").asText()).isEqualTo("#004488");
        assertThat(c.property("strokeWidths").size()).isEqualTo(3);
        assertThat(c.property("advanced").get("smoothing").asBoolean()).isTrue();
        assertThat(c.property("nothingHere")).isNull();
    }

    @Test
    void unknown_type_round_trips_back_to_the_json_it_came_from() throws Exception {
        String json = "{\"id\":\"sig\",\"type\":\"signaturePad\",\"penColour\":\"#004488\"}";
        ComponentSpec c = parse(json);
        assertThat(MAPPER.writeValueAsString(c)).isEqualTo(json);
    }

    @Test
    void unknown_type_nested_in_a_container_deserializes() throws Exception {
        ContainerSpec group = (ContainerSpec) parse("""
                {
                  "id": "grp", "type": "group",
                  "components": [ { "id": "custom", "type": "orgChart", "depth": 4 } ]
                }
                """);
        assertThat(group.components()).hasSize(1);
        assertThat(group.components().getFirst()).isInstanceOf(UnknownComponentSpec.class);
        assertThat(group.components().getFirst().type()).isEqualTo("orgChart");
    }

    private static ComponentSpec parse(String json) throws Exception {
        return MAPPER.readValue(json, ComponentSpec.class);
    }
}
