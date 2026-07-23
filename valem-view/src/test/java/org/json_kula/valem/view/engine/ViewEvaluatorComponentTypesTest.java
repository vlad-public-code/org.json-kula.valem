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
import org.json_kula.valem.view.model.DateRangeSpec;
import org.json_kula.valem.view.model.EffectStatusSpec;
import org.json_kula.valem.view.model.EventHandler;
import org.json_kula.valem.view.model.ImageSpec;
import org.json_kula.valem.view.model.JsonViewerSpec;
import org.json_kula.valem.view.model.KeyValueItemSpec;
import org.json_kula.valem.view.model.KeyValueListSpec;
import org.json_kula.valem.view.model.LinkSpec;
import org.json_kula.valem.view.model.MenuItemSpec;
import org.json_kula.valem.view.model.MenuSpec;
import org.json_kula.valem.view.model.SeparatorLineSpec;
import org.json_kula.valem.view.model.SliderSpec;
import org.json_kula.valem.view.model.StatTileSpec;
import org.json_kula.valem.view.model.TextAreaSpec;
import org.json_kula.valem.view.model.TracePanelSpec;
import org.json_kula.valem.view.model.ValidationSummarySpec;
import org.json_kula.valem.view.model.ViewSpec;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Evaluation behaviour of the component types added beyond the original catalog.
 *
 * <p>Kept apart from {@link ViewEvaluatorTest}, which covers the cross-cutting resolution rules
 * (visible / readOnly / required / enabled / meta inheritance) that apply to every type. What is
 * tested here is per-type: which record a type lands on, which fields the evaluator fills in for
 * it, and — for the diagnostic components — what it deliberately does <em>not</em> fill in.
 */
class ViewEvaluatorComponentTypesTest {

    private static final JsonNodeFactory NF = JsonNodeFactory.instance;
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private ExpressionCache exprCache;
    private ObjectNode mergedDoc;

    @BeforeEach
    void setUp() {
        exprCache = new ExpressionCache();
        mergedDoc = NF.objectNode();
        mergedDoc.put("active", true);
        mergedDoc.put("score", 42);
        mergedDoc.put("name", "Alice");
        mergedDoc.put("total", 1250.5);
        mergedDoc.put("startDate", "2026-01-01");
        mergedDoc.put("endDate", "2026-03-31");
        mergedDoc.put("avatarUrl", "https://example.test/a.png");
        mergedDoc.put("quoteStatus", "in_flight");
        mergedDoc.put("quoteError", "upstream timeout");
        ObjectNode nested = mergedDoc.putObject("order");
        nested.put("ref", "ORD-9");
    }

    // ── numeric spellings: format follows the type ────────────────────────────

    @Test
    void currency_and_percent_fields_get_their_format_filled_in() {
        assertThat(((EvaluatedBasicInput) eval(input("c", "currencyField", null))).format())
                .isEqualTo("currency");
        assertThat(((EvaluatedBasicInput) eval(input("c", "percentField", null))).format())
                .isEqualTo("percent");
    }

    @Test
    void an_explicit_format_beats_the_type_default() {
        assertThat(((EvaluatedBasicInput) eval(input("c", "currencyField", "value"))).format())
                .isEqualTo("value");
    }

    @Test
    void a_plain_text_field_gets_no_format() {
        assertThat(((EvaluatedBasicInput) eval(input("c", "textField", null))).format()).isNull();
    }

    @Test
    void currency_code_is_carried_through() {
        BasicInputSpec spec = new BasicInputSpec("c", "currencyField", "Premium", null, null, null,
                null, "$.total", null, null, null, null, "EUR", null);
        assertThat(((EvaluatedBasicInput) eval(spec)).currency()).isEqualTo("EUR");
    }

    // ── choice spellings: allowCustom follows the type ────────────────────────

    @Test
    void free_entry_types_allow_custom_values_by_default() {
        assertThat(((EvaluatedSelectField) eval(choice("c", "tagsField", null))).allowCustom()).isTrue();
        assertThat(((EvaluatedSelectField) eval(choice("c", "comboBox", null))).allowCustom()).isTrue();
    }

    @Test
    void a_select_field_never_allows_custom_values_by_default() {
        assertThat(((EvaluatedSelectField) eval(choice("c", "selectField", null))).allowCustom()).isNull();
        assertThat(((EvaluatedSelectField) eval(choice("c", "autocompleteField", null))).allowCustom()).isNull();
    }

    @Test
    void an_explicit_allowCustom_beats_the_type_default() {
        assertThat(((EvaluatedSelectField) eval(choice("c", "tagsField", Boolean.FALSE))).allowCustom())
                .isFalse();
    }

    // ── types that reuse an existing record unchanged ─────────────────────────

    @Test
    void rating_and_stepper_evaluate_as_sliders_keeping_their_own_type() {
        EvaluatedComponent rating = eval(new SliderSpec("r", "ratingField", "Rating", null, null,
                null, null, "$.score", null, null, 1.0, 5.0, 1.0, null));
        assertThat(rating).isInstanceOf(EvaluatedSlider.class);
        assertThat(rating.type()).isEqualTo("ratingField");
        assertThat(rating.min()).isEqualTo(1.0);
        assertThat(rating.max()).isEqualTo(5.0);
        assertThat(rating.value().intValue()).isEqualTo(42);
    }

    @Test
    void stepper_and_breadcrumb_evaluate_as_menus() {
        MenuSpec spec = new MenuSpec("nav", "stepper", null, null, "horizontal",
                List.of(new MenuItemSpec("Details", "details", null)));
        EvaluatedComponent result = eval(spec);
        assertThat(result).isInstanceOf(EvaluatedMenu.class);
        assertThat(result.type()).isEqualTo("stepper");
        assertThat(result.menuItems()).hasSize(1);
    }

    @Test
    void spacer_carries_its_size_and_separator_line_does_not_need_one() {
        EvaluatedSeparatorLine spacer =
                (EvaluatedSeparatorLine) eval(new SeparatorLineSpec("s", "spacer", null, null, 32));
        assertThat(spacer.type()).isEqualTo("spacer");
        assertThat(spacer.size()).isEqualTo(32);

        EvaluatedSeparatorLine rule =
                (EvaluatedSeparatorLine) eval(new SeparatorLineSpec("r", "separatorLine", null, null, null));
        assertThat(rule.size()).isNull();
    }

    @Test
    void rich_text_field_evaluates_as_a_text_area_carrying_its_toolbar() {
        TextAreaSpec spec = new TextAreaSpec("n", "richTextField", "Notes", null, null, null, null,
                "$.name", null, null, null, 6, "full", null);
        EvaluatedTextArea result = (EvaluatedTextArea) eval(spec);
        assertThat(result.type()).isEqualTo("richTextField");
        assertThat(result.toolbar()).isEqualTo("full");
        assertThat(result.rows()).isEqualTo(6);
    }

    // ── containers ────────────────────────────────────────────────────────────

    @Test
    void every_container_spelling_evaluates_to_a_container_with_its_children() {
        for (String type : List.of("group", "fieldSet", "card", "toolbar", "buttonGroup",
                "tabs", "tabItem", "accordion", "collapsible")) {
            EvaluatedComponent result = eval(container("c", type, null,
                    List.of(input("inner", "textField", null))));
            assertThat(result).as(type).isInstanceOf(EvaluatedContainer.class);
            assertThat(result.type()).isEqualTo(type);
            assertThat(result.components()).as(type).hasSize(1);
        }
    }

    @Test
    void a_container_label_survives_evaluation() {
        // The tab title / card heading is the label, so dropping it (as the pre-card
        // EvaluatedContainer did) would leave a tab strip with nothing to print on it.
        ContainerSpec spec = new ContainerSpec("t", "tabItem", "Coverage", null, null,
                null, null, null, null, List.of());
        assertThat(eval(spec).label()).isEqualTo("Coverage");
    }

    @Test
    void collapsed_defaults_to_open_and_accepts_a_boolean_or_an_expression() {
        assertThat(eval(container("a", "collapsible", null, List.of())).collapsed()).isFalse();
        assertThat(eval(container("b", "collapsible", BooleanNode.TRUE, List.of())).collapsed()).isTrue();
        assertThat(eval(container("c", "collapsible", TextNode.valueOf("active = false"), List.of()))
                .collapsed()).isFalse();
        assertThat(eval(container("d", "collapsible", TextNode.valueOf("score > 40"), List.of()))
                .collapsed()).isTrue();
    }

    // ── keyValueList ──────────────────────────────────────────────────────────

    @Test
    void key_value_rows_resolve_their_bound_paths() {
        KeyValueListSpec spec = keyValueList(
                KeyValueItemSpec.of("Name", "$.name", null, null, null),
                KeyValueItemSpec.of("Total", "$.total", null, "currency", "EUR"));

        EvaluatedKeyValueList result = (EvaluatedKeyValueList) eval(spec);
        assertThat(result.items()).hasSize(2);
        assertThat(result.items().get(0).label()).isEqualTo("Name");
        assertThat(result.items().get(0).value().asText()).isEqualTo("Alice");
        assertThat(result.items().get(1).value().doubleValue()).isEqualTo(1250.5);
        assertThat(result.items().get(1).format()).isEqualTo("currency");
    }

    @Test
    void a_money_row_carries_its_own_currency_code() {
        // Per row, not per list: a summary legitimately mixes currencies, and a `currency` format
        // with no code renders in whatever the renderer defaults to — which is how a euro total
        // ends up displayed as dollars.
        KeyValueListSpec spec = keyValueList(
                KeyValueItemSpec.of("Quoted", "$.total", null, "currency", "EUR"),
                KeyValueItemSpec.of("Converted", "$.score", null, "currency", "JPY"));

        EvaluatedKeyValueList result = (EvaluatedKeyValueList) eval(spec);
        assertThat(result.items().get(0).currency()).isEqualTo("EUR");
        assertThat(result.items().get(1).currency()).isEqualTo("JPY");
    }

    @Test
    void a_row_without_a_bind_falls_back_to_its_evaluated_text() {
        KeyValueListSpec spec = keyValueList(
                KeyValueItemSpec.of("Greeting", null, TextNode.valueOf("$uppercase(name)"), null, null));
        EvaluatedKeyValueItem row = ((EvaluatedKeyValueList) eval(spec)).items().getFirst();
        assertThat(row.text()).isEqualTo("ALICE");
        assertThat(row.value()).isNull();
    }

    @Test
    void bind_wins_over_text_so_a_row_never_shows_two_things() {
        KeyValueListSpec spec = keyValueList(
                KeyValueItemSpec.of("Name", "$.name", TextNode.valueOf("$uppercase(name)"), null, null));
        EvaluatedKeyValueItem row = ((EvaluatedKeyValueList) eval(spec)).items().getFirst();
        assertThat(row.value().asText()).isEqualTo("Alice");
        assertThat(row.text()).isNull();
    }

    @Test
    void a_missing_bound_path_yields_a_null_value_rather_than_failing() {
        KeyValueListSpec spec = keyValueList(KeyValueItemSpec.of("Nope", "$.absent", null, null, null));
        assertThat(((EvaluatedKeyValueList) eval(spec)).items().getFirst().value()).isNull();
    }

    // ── statTile ──────────────────────────────────────────────────────────────

    @Test
    void a_stat_tile_reads_its_bound_number_and_resolves_its_supporting_text() {
        StatTileSpec spec = new StatTileSpec("t", "statTile", "Total", null, "$.total",
                null, TextNode.valueOf("$string(score)"), TextNode.valueOf("$uppercase(name)"),
                TextNode.valueOf("up"), null, "USD", "success", "trending-up", null);

        EvaluatedStatTile result = (EvaluatedStatTile) eval(spec);
        assertThat(result.value().doubleValue()).isEqualTo(1250.5);
        assertThat(result.delta()).isEqualTo("42");
        assertThat(result.caption()).isEqualTo("ALICE");
        assertThat(result.trend()).isEqualTo("up");
        assertThat(result.currency()).isEqualTo("USD");
        assertThat(result.variant()).isEqualTo("success");
    }

    @Test
    void a_stat_tile_without_a_bind_evaluates_its_value_expression_as_a_number() {
        StatTileSpec spec = new StatTileSpec("t", "metric", "Derived", null, null,
                TextNode.valueOf("$sum([score, 8])"), null, null, null, null, null, null, null, null);

        JsonNode value = ((EvaluatedStatTile) eval(spec)).value();
        assertThat(value.isNumber()).as("value should stay a number, not become a string").isTrue();
        assertThat(value.intValue()).isEqualTo(50);
    }

    // ── dateRangeField ────────────────────────────────────────────────────────

    @Test
    void a_date_range_resolves_both_ends_from_their_own_paths() {
        DateRangeSpec spec = new DateRangeSpec("d", "dateRangeField", "Period", null, null, null,
                null, null, "$.startDate", "$.endDate", "From", "To", null, null, null, null, null);

        EvaluatedDateRange result = (EvaluatedDateRange) eval(spec);
        assertThat(result.valueFrom().asText()).isEqualTo("2026-01-01");
        assertThat(result.valueTo().asText()).isEqualTo("2026-03-31");
        assertThat(result.bindFrom()).isEqualTo("$.startDate");
        assertThat(result.fromLabel()).isEqualTo("From");
    }

    // ── image / link ──────────────────────────────────────────────────────────

    @Test
    void an_image_prefers_an_explicit_src_and_otherwise_uses_the_bound_value() {
        EvaluatedImage explicit = (EvaluatedImage) eval(new ImageSpec("i", "image", null, null,
                "$.avatarUrl", TextNode.valueOf("https://cdn.test/logo.svg"), "Logo", null, null, null));
        assertThat(explicit.src()).isEqualTo("https://cdn.test/logo.svg");

        EvaluatedImage bound = (EvaluatedImage) eval(new ImageSpec("i", "image", null, null,
                "$.avatarUrl", null, "Avatar", null, null, "cover"));
        assertThat(bound.src()).isEqualTo("https://example.test/a.png");
        assertThat(bound.alt()).isEqualTo("Avatar");
        assertThat(bound.fit()).isEqualTo("cover");
    }

    @Test
    void a_link_evaluates_its_href_and_text_expressions() {
        LinkSpec spec = new LinkSpec("l", "link", null, null, null,
                TextNode.valueOf("'https://track.test/' & $string(order.ref)"),
                TextNode.valueOf("$uppercase(order.ref)"), "_blank", null);

        EvaluatedLink result = (EvaluatedLink) eval(spec);
        assertThat(result.href()).isEqualTo("https://track.test/ORD-9");
        assertThat(result.text()).isEqualTo("ORD-9");
        assertThat(result.target()).isEqualTo("_blank");
    }

    // ── jsonViewer ────────────────────────────────────────────────────────────

    @Test
    void a_json_viewer_bound_to_root_shows_the_whole_merged_document() {
        EvaluatedJsonViewer result = (EvaluatedJsonViewer) eval(
                new JsonViewerSpec("j", "jsonViewer", "State", null, "$", null, 4, null));
        assertThat(result.value().get("name").asText()).isEqualTo("Alice");
        assertThat(result.maxDepth()).isEqualTo(4);
    }

    @Test
    void a_json_viewer_bound_to_a_subtree_shows_only_that_subtree() {
        EvaluatedJsonViewer result = (EvaluatedJsonViewer) eval(
                new JsonViewerSpec("j", "jsonViewer", null, null, "$.order", BooleanNode.TRUE, null, null));
        assertThat(result.value().get("ref").asText()).isEqualTo("ORD-9");
        assertThat(result.value().has("name")).isFalse();
        assertThat(result.collapsed()).isTrue();
    }

    // ── diagnostic panels: declarations, not results ──────────────────────────

    @Test
    void a_trace_panel_carries_its_declaration_and_no_trace_rows() {
        // The evaluator has no access to the trace ring buffer or the audit store by design —
        // fetching either would put an unbounded read inside every view evaluation. What travels
        // is which path to explain; the renderer calls /explain or /audit itself.
        TracePanelSpec spec = new TracePanelSpec("x", "explainPanel", "Why?", null, "$.total",
                20, Boolean.TRUE, TextNode.valueOf("active = false"), null);

        EvaluatedTracePanel result = (EvaluatedTracePanel) eval(spec);
        assertThat(result.bind()).isEqualTo("$.total");
        assertThat(result.limit()).isEqualTo(20);
        assertThat(result.showConstraints()).isTrue();
        assertThat(result.collapsed()).isFalse();
        assertThat(result.value()).as("no trace data is resolved server-side").isNull();
    }

    @Test
    void an_audit_timeline_shares_the_trace_panel_shape() {
        EvaluatedComponent result = eval(
                new TracePanelSpec("a", "auditTimeline", null, null, "$.order", 50, null, null, null));
        assertThat(result).isInstanceOf(EvaluatedTracePanel.class);
        assertThat(result.type()).isEqualTo("auditTimeline");
        assertThat(result.limit()).isEqualTo(50);
    }

    @Test
    void a_validation_summary_carries_its_scope_and_no_violations() {
        ValidationSummarySpec spec = new ValidationSummarySpec("v", "validationSummary",
                "Problems", null, null, "$.order", "danger", 10, "All good");

        EvaluatedValidationSummary result = (EvaluatedValidationSummary) eval(spec);
        assertThat(result.pathPrefix()).isEqualTo("$.order");
        assertThat(result.variant()).isEqualTo("danger");
        assertThat(result.maxItems()).isEqualTo(10);
        assertThat(result.emptyText()).isEqualTo("All good");
    }

    // ── effectStatus: fully resolved, unlike the other diagnostics ────────────

    @Test
    void an_effect_status_reads_the_status_and_error_straight_out_of_the_document() {
        EffectStatusSpec spec = new EffectStatusSpec("e", "effectStatus", "Quote", null,
                "$.quoteStatus", "fetchQuote", "$.quoteError", Boolean.TRUE, "Retry", null,
                new EventHandler("{ \"$.quoteStatus\": \"pending\" }", null));

        EvaluatedEffectStatus result = (EvaluatedEffectStatus) eval(spec);
        assertThat(result.value().asText()).isEqualTo("in_flight");
        assertThat(result.error()).isEqualTo("upstream timeout");
        assertThat(result.effectId()).isEqualTo("fetchQuote");
        assertThat(result.showRetry()).isTrue();
        assertThat(result.onRetry().mutations()).contains("pending");
    }

    @Test
    void an_effect_status_with_no_error_path_reports_no_error() {
        EffectStatusSpec spec = new EffectStatusSpec("e", "effectStatus", null, null,
                "$.quoteStatus", null, null, null, null, null, null);
        assertThat(((EvaluatedEffectStatus) eval(spec)).error()).isNull();
    }

    // ── serialization ─────────────────────────────────────────────────────────

    @Test
    void new_records_omit_defaults_the_way_the_existing_ones_do() throws Exception {
        String json = MAPPER.writeValueAsString(eval(container("c", "card", null, List.of())));
        assertThat(json).doesNotContain("\"visible\"");   // true is the default
        assertThat(json).doesNotContain("\"collapsed\""); // false is the default
        assertThat(json).contains("\"type\":\"card\"");
    }

    @Test
    void a_hidden_component_still_reports_its_visibility() throws Exception {
        ContainerSpec hidden = new ContainerSpec("c", "card", null, BooleanNode.FALSE, null,
                null, null, null, null, List.of());
        assertThat(MAPPER.writeValueAsString(eval(hidden))).contains("\"visible\":false");
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private EvaluatedComponent eval(ComponentSpec c) {
        ViewSpec view = ViewSpec.of("v1", "Test View", "vertical", null, List.of(c), null, null);
        return ViewEvaluator.evaluate("test-model", view, mergedDoc, Map.of(), exprCache)
                .components().getFirst();
    }

    private static BasicInputSpec input(String id, String type, String format) {
        return new BasicInputSpec(id, type, null, null, null, null, null,
                "$.total", null, null, null, format, null, null);
    }

    private static ChoiceInputSpec choice(String id, String type, Boolean allowCustom) {
        return new ChoiceInputSpec(id, type, null, null, null, null, null, "$.name", null, null,
                null, null, null, null, null, allowCustom, null, null, null);
    }

    private static ContainerSpec container(String id, String type, JsonNode collapsed,
                                           List<ComponentSpec> children) {
        return new ContainerSpec(id, type, null, null, null, null, null, null, collapsed, children);
    }

    private static KeyValueListSpec keyValueList(KeyValueItemSpec... items) {
        return new KeyValueListSpec("kv", "keyValueList", "Summary", null, null,
                List.of(items), null, null);
    }
}
