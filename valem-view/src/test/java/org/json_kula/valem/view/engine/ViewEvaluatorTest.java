package org.json_kula.valem.view.engine;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.BooleanNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;
import org.json_kula.valem.core.engine.ExpressionCache;
import org.json_kula.valem.view.model.ComponentSpec;
import org.json_kula.valem.view.model.ViewSpec;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ViewEvaluatorTest {

    private static final JsonNodeFactory NF = JsonNodeFactory.instance;

    private ExpressionCache exprCache;
    private ObjectNode mergedDoc;

    @BeforeEach
    void setUp() {
        exprCache = new ExpressionCache();
        mergedDoc = NF.objectNode();
        mergedDoc.put("active", true);
        mergedDoc.put("score", 42);
        mergedDoc.put("name", "Alice");
    }

    // ── visible ───────────────────────────────────────────────────────────────

    @Test
    void visible_null_defaults_to_true() {
        EvaluatedView view = evaluate(component("c1", "label", null, null, null, null, null, null));
        assertThat(first(view).visible()).isTrue();
    }

    @Test
    void visible_false_boolean_hides_component() {
        EvaluatedView view = evaluate(component("c1", "label", BooleanNode.FALSE, null, null, null, null, null));
        assertThat(first(view).visible()).isFalse();
    }

    @Test
    void visible_jsonata_expression_is_evaluated() {
        // "active = true" → true
        EvaluatedView view = evaluate(component("c1", "label", TextNode.valueOf("active = true"), null, null, null, null, null));
        assertThat(first(view).visible()).isTrue();
    }

    @Test
    void visible_jsonata_expression_false_hides_component() {
        EvaluatedView view = evaluate(component("c1", "label", TextNode.valueOf("active = false"), null, null, null, null, null));
        assertThat(first(view).visible()).isFalse();
    }

    // ── readOnly ──────────────────────────────────────────────────────────────

    @Test
    void readOnly_null_defaults_to_false() {
        EvaluatedView view = evaluate(component("c1", "label", null, null, null, null, null, null));
        assertThat(first(view).readOnly()).isFalse();
    }

    @Test
    void readOnly_true_from_meta_cache() {
        Map<String, JsonNode> meta = Map.of("$.name#read_only", BooleanNode.TRUE);
        ComponentSpec c = component("c1", "textField", null, null, null, null, "$.name", null);
        EvaluatedView view = evaluate(c, meta);
        assertThat(first(view).readOnly()).isTrue();
    }

    @Test
    void readOnly_false_from_meta_cache() {
        Map<String, JsonNode> meta = Map.of("$.name#read_only", BooleanNode.FALSE);
        ComponentSpec c = component("c1", "textField", null, null, null, null, "$.name", null);
        EvaluatedView view = evaluate(c, meta);
        assertThat(first(view).readOnly()).isFalse();
    }

    @Test
    void readOnly_boolean_override() {
        EvaluatedView view = evaluate(component("c1", "textField", null, null, BooleanNode.TRUE, null, null, null));
        assertThat(first(view).readOnly()).isTrue();
    }

    // ── enabled derived from readOnly ─────────────────────────────────────────

    @Test
    void enabled_is_false_when_readOnly_is_true() {
        EvaluatedView view = evaluate(component("c1", "textField", null, null, BooleanNode.TRUE, null, null, null));
        assertThat(first(view).readOnly()).isTrue();
        assertThat(first(view).enabled()).isFalse();
    }

    @Test
    void enabled_is_true_when_readOnly_is_false() {
        EvaluatedView view = evaluate(component("c1", "textField", null, null, BooleanNode.FALSE, null, null, null));
        assertThat(first(view).enabled()).isTrue();
    }

    // ── visible from relevant meta ─────────────────────────────────────────────

    @Test
    void visible_false_from_relevant_meta() {
        Map<String, JsonNode> meta = Map.of("$.name#relevant", BooleanNode.FALSE);
        ComponentSpec c = component("c1", "label", null, null, null, null, "$.name", null);
        EvaluatedView view = evaluate(c, meta);
        assertThat(first(view).visible()).isFalse();
    }

    @Test
    void visible_true_from_relevant_meta() {
        Map<String, JsonNode> meta = Map.of("$.name#relevant", BooleanNode.TRUE);
        ComponentSpec c = component("c1", "label", null, null, null, null, "$.name", null);
        EvaluatedView view = evaluate(c, meta);
        assertThat(first(view).visible()).isTrue();
    }

    // ── bound value ───────────────────────────────────────────────────────────

    @Test
    void bind_resolves_value_from_merged_document() {
        ComponentSpec c = component("c1", "label", null, null, null, null, "$.score", null);
        EvaluatedView view = evaluate(c);
        assertThat(first(view).value()).isNotNull();
        assertThat(first(view).value().asInt()).isEqualTo(42);
    }

    // ── text resolution ───────────────────────────────────────────────────────

    @Test
    void text_literal_is_passed_through() {
        ComponentSpec c = component("c1", "staticText", null, null, null, TextNode.valueOf("Hello"), null, null);
        EvaluatedView view = evaluate(c);
        assertThat(first(view).text()).isEqualTo("Hello");
    }

    @Test
    void text_jsonata_expression_is_evaluated() {
        ComponentSpec c = component("c1", "staticText", null, null, null, TextNode.valueOf("$string(score)"), null, null);
        EvaluatedView view = evaluate(c);
        assertThat(first(view).text()).isEqualTo("42");
    }

    // ── aggregate recursion ───────────────────────────────────────────────────

    @Test
    void aggregate_components_are_recursively_evaluated() {
        ComponentSpec inner = component("inner", "label", BooleanNode.FALSE, null, null, null, null, null);
        ComponentSpec outer = component("outer", "group", null, null, null, null, null, List.of(inner));
        EvaluatedView view = evaluate(outer);
        EvaluatedComponent outerEval = first(view);
        assertThat(outerEval.components()).hasSize(1);
        assertThat(outerEval.components().getFirst().id()).isEqualTo("inner");
        assertThat(outerEval.components().getFirst().visible()).isFalse();
    }

    // ── required ──────────────────────────────────────────────────────────────

    @Test
    void required_false_by_default_when_meta_absent() {
        EvaluatedView view = evaluate(component("c1", "textField", null, null, null, null, "$.name", null));
        assertThat(first(view).required()).isFalse();
    }

    @Test
    void required_true_from_meta_cache() {
        Map<String, JsonNode> meta = Map.of("$.name#required", BooleanNode.TRUE);
        ComponentSpec c = component("c1", "textField", null, null, null, null, "$.name", null);
        EvaluatedView view = evaluate(c, meta);
        assertThat(first(view).required()).isTrue();
    }

    @Test
    void required_boolean_override_via_spec() {
        ComponentSpec c = componentWithRequired("c1", "textField", BooleanNode.TRUE);
        EvaluatedView view = evaluate(c);
        assertThat(first(view).required()).isTrue();
    }

    // ── options passthrough ───────────────────────────────────────────────────

    @Test
    void options_list_passes_through_to_evaluated_component() {
        var opts = List.of(
                org.json_kula.valem.view.model.OptionSpec.of("a", "Alpha"),
                org.json_kula.valem.view.model.OptionSpec.of("b", "Beta")
        );
        ComponentSpec c = componentWithOptions("c1", "selectField", opts);
        EvaluatedView view = evaluate(c);
        assertThat(first(view).options()).hasSize(2);
        assertThat(first(view).options().get(0).value()).isEqualTo("a");
    }

    // ── sliderField field passthrough ────────────────────────────────────────

    @Test
    void sliderField_min_max_step_pass_through_to_evaluated_component() {
        ComponentSpec c = componentWithNewFields("s1", "sliderField", "$.volume",
                0.0, 100.0, 5.0, null, null, null);
        EvaluatedView view = evaluate(c);
        EvaluatedComponent eval = first(view);
        assertThat(eval.type()).isEqualTo("sliderField");
        assertThat(eval.min()).isEqualTo(0.0);
        assertThat(eval.max()).isEqualTo(100.0);
        assertThat(eval.step()).isEqualTo(5.0);
        assertThat(eval.accept()).isNull();
    }

    @Test
    void sliderField_null_min_max_step_are_preserved() {
        ComponentSpec c = componentWithNewFields("s2", "sliderField", "$.x",
                null, null, null, null, null, null);
        EvaluatedView view = evaluate(c);
        EvaluatedComponent eval = first(view);
        assertThat(eval.min()).isNull();
        assertThat(eval.max()).isNull();
        assertThat(eval.step()).isNull();
    }

    // ── timeField ─────────────────────────────────────────────────────────────

    @Test
    void timeField_binds_value_from_merged_document() {
        mergedDoc.put("startTime", "09:30");
        ComponentSpec c = componentWithNewFields("t1", "timeField", "$.startTime",
                null, null, null, null, null, null);
        EvaluatedView view = evaluate(c);
        EvaluatedComponent eval = first(view);
        assertThat(eval.type()).isEqualTo("timeField");
        assertThat(eval.value()).isNotNull();
        assertThat(eval.value().asText()).isEqualTo("09:30");
    }

    // ── fileUploadField ───────────────────────────────────────────────────────

    @Test
    void fileUploadField_accept_passes_through_to_evaluated_component() {
        ComponentSpec c = componentWithNewFields("f1", "fileUploadField", "$.avatar",
                null, null, null, "image/*", null, null);
        EvaluatedView view = evaluate(c);
        EvaluatedComponent eval = first(view);
        assertThat(eval.type()).isEqualTo("fileUploadField");
        assertThat(eval.accept()).isEqualTo("image/*");
    }

    @Test
    void fileUploadField_null_accept_is_preserved() {
        ComponentSpec c = componentWithNewFields("f2", "fileUploadField", "$.file",
                null, null, null, null, null, null);
        EvaluatedView view = evaluate(c);
        assertThat(first(view).accept()).isNull();
    }

    // ── fileUploadField — multi-file constraints ──────────────────────────────

    @Test
    void fileUploadField_multi_constraints_pass_through_from_spec() {
        ComponentSpec c = componentWithMultiFile("mf1", "$.docs",
                true, 1, 5, 1024L, 5242880L, "image/jpeg,application/pdf");
        EvaluatedView view = evaluate(c);
        EvaluatedComponent eval = first(view);
        assertThat(eval.multiple()).isTrue();
        assertThat(eval.minFiles()).isEqualTo(1);
        assertThat(eval.maxFiles()).isEqualTo(5);
        assertThat(eval.minSize()).isEqualTo(1024L);
        assertThat(eval.maxSize()).isEqualTo(5242880L);
        assertThat(eval.allowedMediaTypes()).isEqualTo("image/jpeg,application/pdf");
    }

    @Test
    void fileUploadField_minItems_from_meta_overrides_spec() {
        ComponentSpec c = componentWithMultiFile("mf2", "$.docs", true, 1, 5, null, null, null);
        Map<String, JsonNode> meta = Map.of(
                "$.docs#minItems", NF.numberNode(2),
                "$.docs#maxItems", NF.numberNode(10)
        );
        EvaluatedView view = evaluate(c, meta);
        EvaluatedComponent eval = first(view);
        assertThat(eval.minFiles()).isEqualTo(2);
        assertThat(eval.maxFiles()).isEqualTo(10);
    }

    @Test
    void fileUploadField_size_limits_from_meta_override_spec() {
        ComponentSpec c = componentWithMultiFile("mf3", "$.docs", true, null, null, 512L, 1048576L, null);
        Map<String, JsonNode> meta = Map.of(
                "$.docs#minSize", NF.numberNode(4096),
                "$.docs#maxSize", NF.numberNode(10485760)
        );
        EvaluatedView view = evaluate(c, meta);
        EvaluatedComponent eval = first(view);
        assertThat(eval.minSize()).isEqualTo(4096L);
        assertThat(eval.maxSize()).isEqualTo(10485760L);
    }

    @Test
    void fileUploadField_allowedMediaTypes_from_meta_overrides_spec() {
        ComponentSpec c = componentWithMultiFile("mf4", "$.docs", true, null, null, null, null, "image/*");
        Map<String, JsonNode> meta = Map.of(
                "$.docs#allowedMediaTypes", NF.textNode("image/jpeg,image/png")
        );
        EvaluatedView view = evaluate(c, meta);
        assertThat(first(view).allowedMediaTypes()).isEqualTo("image/jpeg,image/png");
    }

    // ── progressBar ───────────────────────────────────────────────────────────

    @Test
    void progressBar_all_display_fields_pass_through_to_evaluated_component() {
        mergedDoc.put("progress", 65);
        ComponentSpec c = componentWithNewFields("pb1", "progressBar", "$.progress",
                0.0, 100.0, null, null, true, "percent");
        EvaluatedView view = evaluate(c);
        EvaluatedComponent eval = first(view);
        assertThat(eval.type()).isEqualTo("progressBar");
        assertThat(eval.min()).isEqualTo(0.0);
        assertThat(eval.max()).isEqualTo(100.0);
        assertThat(eval.showValue()).isTrue();
        assertThat(eval.format()).isEqualTo("percent");
        assertThat(eval.value().asInt()).isEqualTo(65);
    }

    @Test
    void progressBar_value_format_passes_through() {
        ComponentSpec c = componentWithNewFields("pb2", "progressBar", null,
                null, null, null, null, false, "value");
        EvaluatedView view = evaluate(c);
        assertThat(first(view).format()).isEqualTo("value");
        assertThat(first(view).showValue()).isFalse();
    }

    // ── EvaluatedView metadata ────────────────────────────────────────────────

    @Test
    void evaluated_view_carries_correct_metadata() {
        ComponentSpec c = component("c1", "label", null, null, null, null, null, null);
        ViewSpec view = ViewSpec.of("dashboard", "Dashboard", "grid", 3, List.of(c), null, null);
        EvaluatedView result = ViewEvaluator.evaluate("my-model", view, mergedDoc, Map.of(), exprCache);
        assertThat(result.modelId()).isEqualTo("my-model");
        assertThat(result.viewId()).isEqualTo("dashboard");
        assertThat(result.title()).isEqualTo("Dashboard");
        assertThat(result.layout()).isEqualTo("grid");
    }

    // ── constants ($const) ──────────────────────────────────────────────────────

    @Test
    void view_text_expression_can_reference_const() {
        ObjectNode constants = NF.objectNode();
        constants.put("greeting", "Hello from const");
        ComponentSpec c = component("t1", "staticText", null, null, null,
                new TextNode("$const.greeting"), null, null);
        ViewSpec view = ViewSpec.of("v1", "V", "vertical", null, List.of(c), null, null);

        EvaluatedView result = ViewEvaluator.evaluate(
                "m", view, mergedDoc, Map.of(), exprCache, constants);

        EvaluatedStaticText st = (EvaluatedStaticText) result.components().getFirst();
        assertThat(st.text()).isEqualTo("Hello from const");
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private EvaluatedView evaluate(ComponentSpec c) {
        return evaluate(c, Map.of());
    }

    private EvaluatedView evaluate(ComponentSpec c, Map<String, JsonNode> meta) {
        ViewSpec view = ViewSpec.of("v1", "Test View", "vertical", null, List.of(c), null, null);
        return ViewEvaluator.evaluate("test-model", view, mergedDoc, meta, exprCache);
    }

    private EvaluatedComponent first(EvaluatedView view) {
        return view.components().getFirst();
    }

    private static ComponentSpec component(
            String id, String type,
            JsonNode visible, JsonNode enabled, JsonNode readOnly,
            JsonNode text, String bind,
            List<ComponentSpec> components
    ) {
        return ComponentSpec.of(
                id, type, null,
                visible, enabled, readOnly, null,
                bind, null, null, null, null,
                null, null, null, null, null,
                null, components, null, null, null, null,
                null, null, null, null,
                null, null,
                null, null, null,
                text, null, null, null, null,
                null, null, null, null, null, null, null, null, null, null, null, null,
                null, null, null, null
        );
    }

    private static ComponentSpec componentWithRequired(String id, String type, JsonNode required) {
        return ComponentSpec.of(
                id, type, null,
                null, null, null, required,
                null, null, null, null, null,
                null, null, null, null, null,
                null, null, null, null, null, null,
                null, null, null, null,
                null, null,
                null, null, null,
                null, null, null, null, null,
                null, null, null, null, null, null, null, null, null, null, null, null,
                null, null, null, null
        );
    }

    private static ComponentSpec componentWithNewFields(
            String id, String type, String bind,
            Double min, Double max, Double step,
            String accept, Boolean showValue, String format
    ) {
        return ComponentSpec.of(
                id, type, null,
                null, null, null, null,
                bind, null, null, null, null,
                null, null, null, null, null,
                null, null, null, null, null, null,
                null, null, null, null,
                null, null,
                null, null, null,
                null, null, null, null, null,
                min, max, step, accept, null, null, null, null, null, null, showValue, format,
                null, null, null, null
        );
    }

    private static ComponentSpec componentWithMultiFile(
            String id, String bind,
            Boolean multiple, Integer minFiles, Integer maxFiles,
            Long minSize, Long maxSize, String allowedMediaTypes
    ) {
        return ComponentSpec.of(
                id, "fileUploadField", null,
                null, null, null, null,
                bind, null, null, null, null,
                null, null, null, null, null,
                null, null, null, null, null, null,
                null, null, null, null,
                null, null,
                null, null, null,
                null, null, null, null, null,
                null, null, null, null, multiple, minFiles, maxFiles, minSize, maxSize, allowedMediaTypes, null, null,
                null, null, null, null
        );
    }

    private static ComponentSpec componentWithOptions(
            String id, String type,
            List<org.json_kula.valem.view.model.OptionSpec> options
    ) {
        return ComponentSpec.of(
                id, type, null,
                null, null, null, null,
                null, null, null, null, null,
                options, null, null, null, null,
                null, null, null, null, null, null,
                null, null, null, null,
                null, null,
                null, null, null,
                null, null, null, null, null,
                null, null, null, null, null, null, null, null, null, null, null, null,
                null, null, null, null
        );
    }
}
