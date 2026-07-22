package org.json_kula.valem.view.engine;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.BooleanNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;
import org.json_kula.valem.core.engine.ExpressionCache;
import org.json_kula.valem.view.model.BasicInputSpec;
import org.json_kula.valem.view.model.ChoiceInputSpec;
import org.json_kula.valem.view.model.ComponentSpec;
import org.json_kula.valem.view.model.ContainerSpec;
import org.json_kula.valem.view.model.FileUploadSpec;
import org.json_kula.valem.view.model.LabelSpec;
import org.json_kula.valem.view.model.OptionSpec;
import org.json_kula.valem.view.model.ProgressBarSpec;
import org.json_kula.valem.view.model.SliderSpec;
import org.json_kula.valem.view.model.StaticTextSpec;
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
        EvaluatedView view = evaluate(label("c1", null, null));
        assertThat(first(view).visible()).isTrue();
    }

    @Test
    void visible_false_boolean_hides_component() {
        EvaluatedView view = evaluate(label("c1", BooleanNode.FALSE, null));
        assertThat(first(view).visible()).isFalse();
    }

    @Test
    void visible_jsonata_expression_is_evaluated() {
        // "active = true" → true
        EvaluatedView view = evaluate(label("c1", TextNode.valueOf("active = true"), null));
        assertThat(first(view).visible()).isTrue();
    }

    @Test
    void visible_jsonata_expression_false_hides_component() {
        EvaluatedView view = evaluate(label("c1", TextNode.valueOf("active = false"), null));
        assertThat(first(view).visible()).isFalse();
    }

    // ── readOnly ──────────────────────────────────────────────────────────────

    @Test
    void readOnly_null_defaults_to_false() {
        EvaluatedView view = evaluate(label("c1", null, null));
        assertThat(first(view).readOnly()).isFalse();
    }

    @Test
    void readOnly_true_from_meta_cache() {
        Map<String, JsonNode> meta = Map.of("$.name#read_only", BooleanNode.TRUE);
        EvaluatedView view = evaluate(input("c1", "textField", null, null, null, null, "$.name"), meta);
        assertThat(first(view).readOnly()).isTrue();
    }

    @Test
    void readOnly_false_from_meta_cache() {
        Map<String, JsonNode> meta = Map.of("$.name#read_only", BooleanNode.FALSE);
        EvaluatedView view = evaluate(input("c1", "textField", null, null, null, null, "$.name"), meta);
        assertThat(first(view).readOnly()).isFalse();
    }

    @Test
    void readOnly_boolean_override() {
        EvaluatedView view = evaluate(input("c1", "textField", null, null, BooleanNode.TRUE, null, null));
        assertThat(first(view).readOnly()).isTrue();
    }

    // ── enabled derived from readOnly ─────────────────────────────────────────

    @Test
    void enabled_is_false_when_readOnly_is_true() {
        EvaluatedView view = evaluate(input("c1", "textField", null, null, BooleanNode.TRUE, null, null));
        assertThat(first(view).readOnly()).isTrue();
        assertThat(first(view).enabled()).isFalse();
    }

    @Test
    void enabled_is_true_when_readOnly_is_false() {
        EvaluatedView view = evaluate(input("c1", "textField", null, null, BooleanNode.FALSE, null, null));
        assertThat(first(view).enabled()).isTrue();
    }

    // ── visible from relevant meta ─────────────────────────────────────────────

    @Test
    void visible_false_from_relevant_meta() {
        Map<String, JsonNode> meta = Map.of("$.name#relevant", BooleanNode.FALSE);
        EvaluatedView view = evaluate(label("c1", null, "$.name"), meta);
        assertThat(first(view).visible()).isFalse();
    }

    @Test
    void visible_true_from_relevant_meta() {
        Map<String, JsonNode> meta = Map.of("$.name#relevant", BooleanNode.TRUE);
        EvaluatedView view = evaluate(label("c1", null, "$.name"), meta);
        assertThat(first(view).visible()).isTrue();
    }

    // ── bound value ───────────────────────────────────────────────────────────

    @Test
    void bind_resolves_value_from_merged_document() {
        EvaluatedView view = evaluate(label("c1", null, "$.score"));
        assertThat(first(view).value()).isNotNull();
        assertThat(first(view).value().asInt()).isEqualTo(42);
    }

    // ── text resolution ───────────────────────────────────────────────────────

    @Test
    void text_literal_is_passed_through() {
        EvaluatedView view = evaluate(staticText("c1", TextNode.valueOf("Hello")));
        assertThat(first(view).text()).isEqualTo("Hello");
    }

    @Test
    void text_jsonata_expression_is_evaluated() {
        EvaluatedView view = evaluate(staticText("c1", TextNode.valueOf("$string(score)")));
        assertThat(first(view).text()).isEqualTo("42");
    }

    // ── aggregate recursion ───────────────────────────────────────────────────

    @Test
    void aggregate_components_are_recursively_evaluated() {
        ComponentSpec inner = label("inner", BooleanNode.FALSE, null);
        EvaluatedView view = evaluate(container("outer", "group", null, List.of(inner)));
        EvaluatedComponent outerEval = first(view);
        assertThat(outerEval.components()).hasSize(1);
        assertThat(outerEval.components().getFirst().id()).isEqualTo("inner");
        assertThat(outerEval.components().getFirst().visible()).isFalse();
    }

    @Test
    void sectionItem_is_a_container_and_keeps_its_children() {
        ComponentSpec inner = input("qty", "numericField", null, null, null, null, "$.items[0].qty");
        EvaluatedView view = evaluate(container("item0", "sectionItem", "$.items[0]", List.of(inner)));

        EvaluatedComponent evaluated = first(view);
        assertThat(evaluated).isInstanceOf(EvaluatedContainer.class);
        assertThat(evaluated.bind()).isEqualTo("$.items[0]");
        assertThat(evaluated.components()).hasSize(1);
        assertThat(evaluated.components().getFirst().id()).isEqualTo("qty");
    }

    // ── required ──────────────────────────────────────────────────────────────

    @Test
    void required_false_by_default_when_meta_absent() {
        EvaluatedView view = evaluate(input("c1", "textField", null, null, null, null, "$.name"));
        assertThat(first(view).required()).isFalse();
    }

    @Test
    void required_true_from_meta_cache() {
        Map<String, JsonNode> meta = Map.of("$.name#required", BooleanNode.TRUE);
        EvaluatedView view = evaluate(input("c1", "textField", null, null, null, null, "$.name"), meta);
        assertThat(first(view).required()).isTrue();
    }

    @Test
    void required_boolean_override_via_spec() {
        EvaluatedView view = evaluate(input("c1", "textField", null, null, null, BooleanNode.TRUE, null));
        assertThat(first(view).required()).isTrue();
    }

    // ── options passthrough ───────────────────────────────────────────────────

    @Test
    void options_list_passes_through_to_evaluated_component() {
        var opts = List.of(OptionSpec.of("a", "Alpha"), OptionSpec.of("b", "Beta"));
        EvaluatedView view = evaluate(choice("c1", "selectField", opts));
        assertThat(first(view).options()).hasSize(2);
        assertThat(first(view).options().get(0).value()).isEqualTo("a");
    }

    // ── sliderField field passthrough ────────────────────────────────────────

    @Test
    void sliderField_min_max_step_pass_through_to_evaluated_component() {
        EvaluatedView view = evaluate(slider("s1", "$.volume", 0.0, 100.0, 5.0));
        EvaluatedComponent eval = first(view);
        assertThat(eval.type()).isEqualTo("sliderField");
        assertThat(eval.min()).isEqualTo(0.0);
        assertThat(eval.max()).isEqualTo(100.0);
        assertThat(eval.step()).isEqualTo(5.0);
        assertThat(eval.accept()).isNull();
    }

    @Test
    void sliderField_null_min_max_step_are_preserved() {
        EvaluatedView view = evaluate(slider("s2", "$.x", null, null, null));
        EvaluatedComponent eval = first(view);
        assertThat(eval.min()).isNull();
        assertThat(eval.max()).isNull();
        assertThat(eval.step()).isNull();
    }

    // ── timeField ─────────────────────────────────────────────────────────────

    @Test
    void timeField_binds_value_from_merged_document() {
        mergedDoc.put("startTime", "09:30");
        EvaluatedView view = evaluate(input("t1", "timeField", null, null, null, null, "$.startTime"));
        EvaluatedComponent eval = first(view);
        assertThat(eval.type()).isEqualTo("timeField");
        assertThat(eval.value()).isNotNull();
        assertThat(eval.value().asText()).isEqualTo("09:30");
    }

    // ── fileUploadField ───────────────────────────────────────────────────────

    @Test
    void fileUploadField_accept_passes_through_to_evaluated_component() {
        EvaluatedView view = evaluate(fileUpload("f1", "$.avatar", "image/*",
                null, null, null, null, null, null));
        EvaluatedComponent eval = first(view);
        assertThat(eval.type()).isEqualTo("fileUploadField");
        assertThat(eval.accept()).isEqualTo("image/*");
    }

    @Test
    void fileUploadField_null_accept_is_preserved() {
        EvaluatedView view = evaluate(fileUpload("f2", "$.file", null,
                null, null, null, null, null, null));
        assertThat(first(view).accept()).isNull();
    }

    // ── fileUploadField — multi-file constraints ──────────────────────────────

    @Test
    void fileUploadField_multi_constraints_pass_through_from_spec() {
        EvaluatedView view = evaluate(fileUpload("mf1", "$.docs", null,
                true, 1, 5, 1024L, 5242880L, "image/jpeg,application/pdf"));
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
        ComponentSpec c = fileUpload("mf2", "$.docs", null, true, 1, 5, null, null, null);
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
        ComponentSpec c = fileUpload("mf3", "$.docs", null, true, null, null, 512L, 1048576L, null);
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
        ComponentSpec c = fileUpload("mf4", "$.docs", null, true, null, null, null, null, "image/*");
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
        EvaluatedView view = evaluate(progressBar("pb1", "$.progress", 0.0, 100.0, true, "percent"));
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
        EvaluatedView view = evaluate(progressBar("pb2", null, null, null, false, "value"));
        assertThat(first(view).format()).isEqualTo("value");
        assertThat(first(view).showValue()).isFalse();
    }

    // ── unknown component types ───────────────────────────────────────────────

    @Test
    void unknown_type_renders_as_basic_input_and_keeps_every_property() throws Exception {
        ComponentSpec c = new ObjectMapper().readValue("""
                {
                  "id": "x1", "type": "signaturePad",
                  "label": "Sign here",
                  "bind": "$.name",
                  "penColour": "#004",
                  "strokes": [1, 2, 3]
                }
                """, ComponentSpec.class);

        EvaluatedView view = evaluate(c);
        EvaluatedComponent eval = first(view);
        assertThat(eval).isInstanceOf(EvaluatedBasicInput.class);
        assertThat(eval.type()).isEqualTo("signaturePad");
        assertThat(eval.label()).isEqualTo("Sign here");
        assertThat(eval.value().asText()).isEqualTo("Alice");
    }

    // ── EvaluatedView metadata ────────────────────────────────────────────────

    @Test
    void evaluated_view_carries_correct_metadata() {
        ViewSpec view = ViewSpec.of("dashboard", "Dashboard", "grid", 3,
                List.of(label("c1", null, null)), null, null);
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
        ViewSpec view = ViewSpec.of("v1", "V", "vertical", null,
                List.of(staticText("t1", new TextNode("$const.greeting"))), null, null);

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

    private static LabelSpec label(String id, JsonNode visible, String bind) {
        return new LabelSpec(id, "label", null, visible, bind, null);
    }

    private static BasicInputSpec input(String id, String type, JsonNode visible, JsonNode enabled,
                                        JsonNode readOnly, JsonNode required, String bind) {
        return new BasicInputSpec(id, type, null, visible, enabled, readOnly, required,
                bind, null, null, null, null);
    }

    private static StaticTextSpec staticText(String id, JsonNode text) {
        return new StaticTextSpec(id, "staticText", null, null, text);
    }

    private static ContainerSpec container(String id, String type, String bind,
                                           List<ComponentSpec> children) {
        return new ContainerSpec(id, type, null, null, bind, null, null, null, children);
    }

    private static SliderSpec slider(String id, String bind, Double min, Double max, Double step) {
        return new SliderSpec(id, "sliderField", null, null, null, null, null,
                bind, null, null, min, max, step, null);
    }

    private static ProgressBarSpec progressBar(String id, String bind, Double min, Double max,
                                               Boolean showValue, String format) {
        return new ProgressBarSpec(id, "progressBar", null, null, bind, min, max, showValue, format, null);
    }

    private static FileUploadSpec fileUpload(String id, String bind, String accept, Boolean multiple,
                                             Integer minFiles, Integer maxFiles,
                                             Long minSize, Long maxSize, String allowedMediaTypes) {
        return new FileUploadSpec(id, "fileUploadField", null, null, null, null, null,
                bind, null, null, accept, multiple, minFiles, maxFiles,
                minSize, maxSize, allowedMediaTypes, null);
    }

    private static ChoiceInputSpec choice(String id, String type, List<OptionSpec> options) {
        return new ChoiceInputSpec(id, type, null, null, null, null, null, null, null, null, null,
                options, null, null, null, null, null, null);
    }
}
