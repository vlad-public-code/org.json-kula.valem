package org.json_kula.valem.view.engine;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.json_kula.jsonata_jvm.JsonataBindings;
import org.json_kula.valem.core.engine.ExpressionCache;
import org.json_kula.valem.core.state.PathConverter;
import org.json_kula.valem.view.model.BadgeSpec;
import org.json_kula.valem.view.model.BasicInputSpec;
import org.json_kula.valem.view.model.ButtonSpec;
import org.json_kula.valem.view.model.ChoiceInputSpec;
import org.json_kula.valem.view.model.ComponentSpec;
import org.json_kula.valem.view.model.ContainerSpec;
import org.json_kula.valem.view.model.DataChartSpec;
import org.json_kula.valem.view.model.DataTableSpec;
import org.json_kula.valem.view.model.DateRangeSpec;
import org.json_kula.valem.view.model.DependentSelectorSpec;
import org.json_kula.valem.view.model.EffectStatusSpec;
import org.json_kula.valem.view.model.FileUploadSpec;
import org.json_kula.valem.view.model.ImageSpec;
import org.json_kula.valem.view.model.JsonViewerSpec;
import org.json_kula.valem.view.model.KeyValueItemSpec;
import org.json_kula.valem.view.model.KeyValueListSpec;
import org.json_kula.valem.view.model.LabelSpec;
import org.json_kula.valem.view.model.LinkSpec;
import org.json_kula.valem.view.model.MenuSpec;
import org.json_kula.valem.view.model.ProgressBarSpec;
import org.json_kula.valem.view.model.SectionListSpec;
import org.json_kula.valem.view.model.SeparatorLineSpec;
import org.json_kula.valem.view.model.SliderSpec;
import org.json_kula.valem.view.model.StatTileSpec;
import org.json_kula.valem.view.model.StaticTextSpec;
import org.json_kula.valem.view.model.TextAreaSpec;
import org.json_kula.valem.view.model.TracePanelSpec;
import org.json_kula.valem.view.model.UnknownComponentSpec;
import org.json_kula.valem.view.model.ValidationSummarySpec;
import org.json_kula.valem.view.model.ViewSpec;

import java.util.List;
import java.util.Map;

/**
 * Stateless evaluator that resolves a ViewSpec into an EvaluatedView by computing all
 * dynamic expressions (visible, enabled, readOnly, required, text) against the current
 * merged model state and meta cache.
 */
public final class ViewEvaluator {

    private ViewEvaluator() {}

    public static EvaluatedView evaluate(
            String modelId,
            ViewSpec view,
            ObjectNode mergedDocument,
            Map<String, JsonNode> metaCache,
            ExpressionCache exprCache
    ) {
        return evaluate(modelId, view, mergedDocument, metaCache, exprCache, null);
    }

    /**
     * Same as {@link #evaluate(String, ViewSpec, ObjectNode, Map, ExpressionCache)} but binds the
     * model's named constants as {@code $const} in every view expression. {@code constants} may be
     * {@code null} (no constants bound).
     */
    public static EvaluatedView evaluate(
            String modelId,
            ViewSpec view,
            ObjectNode mergedDocument,
            Map<String, JsonNode> metaCache,
            ExpressionCache exprCache,
            ObjectNode constants
    ) {
        JsonataBindings bindings = constants != null
                ? new JsonataBindings().bindValue("const", constants) : null;
        List<EvaluatedComponent> components = view.components().stream()
                .map(c -> evaluateComponent(c, mergedDocument, metaCache, exprCache, bindings))
                .toList();
        return new EvaluatedView(modelId, view.id(), view.label(), view.layout(), components);
    }

    static EvaluatedComponent evaluateComponent(
            ComponentSpec c,
            ObjectNode mergedDocument,
            Map<String, JsonNode> metaCache,
            ExpressionCache exprCache,
            JsonataBindings bindings
    ) {
        boolean visible  = resolveVisible(c, metaCache, mergedDocument, exprCache, bindings);
        boolean readOnly = resolveReadOnly(c, metaCache, mergedDocument, exprCache, bindings);
        boolean required = resolveRequired(c, metaCache, mergedDocument, exprCache, bindings);
        boolean enabled  = resolveEnabled(c, readOnly, mergedDocument, exprCache, bindings);

        JsonNode value = resolveValue(c.bind(), mergedDocument);

        // Exhaustive over the sealed hierarchy: a new component record does not compile
        // until it is handled here.
        return switch (c) {
            case SeparatorLineSpec s ->
                    new EvaluatedSeparatorLine(s.id(), s.type(), visible, s.size());

            case StaticTextSpec s ->
                    new EvaluatedStaticText(s.id(), s.type(), visible,
                            resolveText(s.text(), mergedDocument, exprCache, bindings));

            case BadgeSpec b ->
                    new EvaluatedBadge(b.id(), b.type(), visible, b.variant(),
                            resolveText(b.text(), mergedDocument, exprCache, bindings), b.label());

            case LabelSpec l ->
                    new EvaluatedLabel(l.id(), l.type(), l.label(), l.bind(), value, visible);

            case ImageSpec i ->
                    new EvaluatedImage(i.id(), i.type(), i.label(), i.bind(), value, visible,
                            firstNonNull(resolveText(i.src(), mergedDocument, exprCache, bindings),
                                         textValue(value)),
                            i.alt(), i.width(), i.height(), i.fit());

            case LinkSpec l ->
                    new EvaluatedLink(l.id(), l.type(), l.label(), l.bind(), visible,
                            firstNonNull(resolveText(l.href(), mergedDocument, exprCache, bindings),
                                         textValue(value)),
                            resolveText(l.text(), mergedDocument, exprCache, bindings),
                            l.target(), l.icon());

            case ProgressBarSpec p ->
                    new EvaluatedProgressBar(p.id(), p.type(), p.label(), p.bind(), value,
                            visible, p.min(), p.max(), p.showValue(), p.format(), p.tooltip());

            case DataTableSpec t ->
                    new EvaluatedDataTable(t.id(), t.type(), t.label(), t.bind(), value,
                            visible, t.tableColumns(), t.pageSize(), t.tooltip());

            case DataChartSpec ch ->
                    new EvaluatedDataChart(ch.id(), ch.type(), ch.label(), ch.bind(), value,
                            visible, ch.chartType(), ch.chartX(), ch.chartSeries());

            case KeyValueListSpec kv ->
                    new EvaluatedKeyValueList(kv.id(), kv.type(), kv.label(), kv.bind(), visible,
                            evaluateItems(kv.items(), mergedDocument, exprCache, bindings),
                            kv.columns(), kv.tooltip());

            case StatTileSpec st ->
                    new EvaluatedStatTile(st.id(), st.type(), st.label(), st.bind(),
                            st.bind() != null ? value
                                    : resolveNode(st.value(), mergedDocument, exprCache, bindings),
                            visible,
                            resolveText(st.delta(),   mergedDocument, exprCache, bindings),
                            resolveText(st.caption(), mergedDocument, exprCache, bindings),
                            resolveText(st.trend(),   mergedDocument, exprCache, bindings),
                            defaultFormat(st.type(), st.format()), st.currency(),
                            st.variant(), st.icon(), st.tooltip());

            case JsonViewerSpec j ->
                    new EvaluatedJsonViewer(j.id(), j.type(), j.label(), j.bind(),
                            "$".equals(j.bind()) ? mergedDocument : value, visible,
                            resolveJsonNodeBoolean(j.collapsed(), false, mergedDocument, exprCache, bindings),
                            j.maxDepth(), j.tooltip());

            case TracePanelSpec tp ->
                    new EvaluatedTracePanel(tp.id(), tp.type(), tp.label(), tp.bind(), visible,
                            tp.limit(), tp.showConstraints(),
                            resolveJsonNodeBoolean(tp.collapsed(), false, mergedDocument, exprCache, bindings),
                            tp.tooltip());

            case ValidationSummarySpec vs ->
                    new EvaluatedValidationSummary(vs.id(), vs.type(), vs.label(), vs.bind(),
                            visible, vs.pathPrefix(), vs.variant(), vs.maxItems(), vs.emptyText());

            case EffectStatusSpec es ->
                    new EvaluatedEffectStatus(es.id(), es.type(), es.label(), es.bind(), value,
                            visible, es.effectId(), es.errorPath(),
                            textValue(resolveValue(es.errorPath(), mergedDocument)),
                            es.showRetry(), es.retryLabel(), es.tooltip(), es.onRetry());

            case ContainerSpec g ->
                    new EvaluatedContainer(g.id(), g.type(), g.label(), g.bind(), visible,
                            g.layout(), g.columns(),
                            evaluateChildren(g.components(), mergedDocument, metaCache, exprCache, bindings),
                            g.legend(),
                            resolveJsonNodeBoolean(g.collapsed(), false, mergedDocument, exprCache, bindings));

            case SectionListSpec sl -> {
                boolean canAdd    = resolveJsonNodeBoolean(sl.canAdd(),    true, mergedDocument, exprCache, bindings);
                boolean canRemove = resolveJsonNodeBoolean(sl.canRemove(), true, mergedDocument, exprCache, bindings);
                yield new EvaluatedSectionList(sl.id(), sl.type(), sl.label(), sl.bind(), visible,
                        evaluateChildren(sl.components(), mergedDocument, metaCache, exprCache, bindings),
                        sl.layout(), sl.columns(), canAdd, canRemove,
                        sl.addLabel(), sl.removeLabel(), sl.onChange());
            }

            case ButtonSpec b ->
                    new EvaluatedButton(b.id(), b.type(), b.label(), visible, enabled,
                            b.variant(), b.icon(), b.onClick());

            case MenuSpec m ->
                    new EvaluatedMenu(m.id(), m.type(), visible, m.orientation(), m.menuItems());

            case SliderSpec s ->
                    new EvaluatedSlider(s.id(), s.type(), s.label(), s.bind(), value,
                            visible, enabled, readOnly, required,
                            s.min(), s.max(), s.step(), s.helperText(), s.tooltip(), s.onChange());

            case FileUploadSpec f ->
                    new EvaluatedFileUpload(f.id(), f.type(), f.label(), f.bind(), value,
                            visible, enabled, readOnly,
                            f.accept(), f.multiple(),
                            resolveIntMeta(f.bind(),    "minItems",          f.minFiles(),          metaCache),
                            resolveIntMeta(f.bind(),    "maxItems",          f.maxFiles(),          metaCache),
                            resolveLongMeta(f.bind(),   "minSize",           f.minSize(),           metaCache),
                            resolveLongMeta(f.bind(),   "maxSize",           f.maxSize(),           metaCache),
                            resolveStringMeta(f.bind(), "allowedMediaTypes", f.allowedMediaTypes(), metaCache),
                            f.helperText(), f.tooltip(), f.onChange());

            case DateRangeSpec dr ->
                    new EvaluatedDateRange(dr.id(), dr.type(), dr.label(), dr.bind(),
                            dr.bindFrom(), dr.bindTo(),
                            resolveValue(dr.bindFrom(), mergedDocument),
                            resolveValue(dr.bindTo(), mergedDocument),
                            visible, enabled, readOnly, required,
                            dr.fromLabel(), dr.toLabel(), dr.helperText(), dr.tooltip(),
                            dr.minDate(), dr.maxDate(), dr.onChange());

            case TextAreaSpec t ->
                    new EvaluatedTextArea(t.id(), t.type(), t.label(), t.bind(), value,
                            visible, enabled, readOnly, required,
                            t.placeholder(), t.helperText(), t.tooltip(), t.rows(),
                            t.toolbar(), t.onChange());

            case ChoiceInputSpec s ->
                    new EvaluatedSelectField(s.id(), s.type(), s.label(), s.bind(), value,
                            visible, enabled, readOnly, required,
                            s.placeholder(), s.helperText(), s.tooltip(),
                            s.options(), defaultAllowCustom(s.type(), s.allowCustom()),
                            s.onChange(), s.onOpen(), s.onClose());

            case DependentSelectorSpec d ->
                    new EvaluatedDependentSelector(d.id(), d.type(), d.label(), d.bind(), value,
                            visible, enabled, readOnly, required,
                            d.placeholder(), d.helperText(), d.tooltip(),
                            d.options(), d.dependsOn(), d.onChange());

            case BasicInputSpec b ->
                    new EvaluatedBasicInput(b.id(), b.type(), b.label(), b.bind(), value,
                            visible, enabled, readOnly, required,
                            b.placeholder(), b.helperText(), b.tooltip(),
                            defaultFormat(b.type(), b.format()), b.currency(), b.onChange());

            // A type Valem does not know: rendered as a basic input, as before.
            case UnknownComponentSpec u ->
                    new EvaluatedBasicInput(u.id(), u.type(), u.label(), u.bind(), value,
                            visible, enabled, readOnly, required,
                            u.placeholder(), u.helperText(), u.tooltip(), null, null, u.onChange());
        };
    }

    private static List<EvaluatedKeyValueItem> evaluateItems(
            List<KeyValueItemSpec> items,
            ObjectNode mergedDocument,
            ExpressionCache exprCache,
            JsonataBindings bindings
    ) {
        if (items == null || items.isEmpty()) return null;
        return items.stream()
                .map(i -> new EvaluatedKeyValueItem(
                        i.label(),
                        i.bind(),
                        i.bind() != null ? resolveValue(i.bind(), mergedDocument) : null,
                        // bind wins: a row shows one thing, not two depending on which the reader picks.
                        i.bind() != null ? null
                                : resolveText(i.text(), mergedDocument, exprCache, bindings),
                        i.format(), i.currency()))
                .toList();
    }

    private static List<EvaluatedComponent> evaluateChildren(
            List<ComponentSpec> children,
            ObjectNode mergedDocument,
            Map<String, JsonNode> metaCache,
            ExpressionCache exprCache,
            JsonataBindings bindings
    ) {
        if (children == null || children.isEmpty()) return null;
        return children.stream()
                .map(sub -> evaluateComponent(sub, mergedDocument, metaCache, exprCache, bindings))
                .toList();
    }

    // ── Value resolver ────────────────────────────────────────────────────────

    private static JsonNode resolveValue(String bind, ObjectNode mergedDocument) {
        if (bind == null) return null;
        JsonNode v = mergedDocument.at(PathConverter.toJsonPointer(bind));
        return v.isMissingNode() ? null : v;
    }

    private static String textValue(JsonNode node) {
        return node == null || node.isNull() || node.isMissingNode() || !node.isValueNode()
                ? null : node.asText();
    }

    private static String firstNonNull(String a, String b) {
        return a != null ? a : b;
    }

    // ── Type-driven defaults ──────────────────────────────────────────────────

    /**
     * {@code currencyField} and {@code percentField} carry their formatting in the type name, so
     * an unset {@code format} is filled in here rather than left for each renderer to re-derive —
     * a console or MCP consumer reading the evaluated view should not have to know that
     * {@code percentField} implies a percent.
     *
     * <p>{@code statTile} passes through unchanged: its type says nothing about how the number
     * should read, so an unformatted tile is the author's choice rather than a gap to fill.
     */
    private static String defaultFormat(String type, String specValue) {
        if (specValue != null) return specValue;
        return switch (type) {
            case "currencyField" -> "currency";
            case "percentField"  -> "percent";
            default -> null;
        };
    }

    /**
     * A free-typed value is the point of {@code tagsField} and {@code comboBox} and never allowed
     * for a {@code selectField}, so the default follows the type unless the spec overrides it.
     */
    private static Boolean defaultAllowCustom(String type, Boolean specValue) {
        if (specValue != null) return specValue;
        return switch (type) {
            case "tagsField", "comboBox" -> Boolean.TRUE;
            default -> null;
        };
    }

    // ── Meta resolvers ────────────────────────────────────────────────────────

    private static Integer resolveIntMeta(String bind, String key, Integer specValue, Map<String, JsonNode> metaCache) {
        if (bind != null) {
            JsonNode meta = metaCache.get(bind + "#" + key);
            if (meta != null && !meta.isNull() && meta.isNumber()) return meta.intValue();
        }
        return specValue;
    }

    private static Long resolveLongMeta(String bind, String key, Long specValue, Map<String, JsonNode> metaCache) {
        if (bind != null) {
            JsonNode meta = metaCache.get(bind + "#" + key);
            if (meta != null && !meta.isNull() && meta.isNumber()) return meta.longValue();
        }
        return specValue;
    }

    private static String resolveStringMeta(String bind, String key, String specValue, Map<String, JsonNode> metaCache) {
        if (bind != null) {
            JsonNode meta = metaCache.get(bind + "#" + key);
            if (meta != null && !meta.isNull() && meta.isTextual()) return meta.asText();
        }
        return specValue;
    }

    // ── Boolean resolvers ─────────────────────────────────────────────────────

    private static boolean resolveVisible(
            ComponentSpec c, Map<String, JsonNode> metaCache,
            ObjectNode mergedDocument, ExpressionCache exprCache, JsonataBindings bindings
    ) {
        if (c.visible() == null) {
            if (c.bind() != null) {
                JsonNode meta = metaCache.get(c.bind() + "#relevant");
                if (meta != null && !meta.isNull() && !meta.isMissingNode()) {
                    return meta.asBoolean(true);
                }
            }
            return true;
        }
        return resolveJsonNodeBoolean(c.visible(), true, mergedDocument, exprCache, bindings);
    }

    private static boolean resolveReadOnly(
            ComponentSpec c, Map<String, JsonNode> metaCache,
            ObjectNode mergedDocument, ExpressionCache exprCache, JsonataBindings bindings
    ) {
        if (c.readOnly() == null) {
            if (c.bind() != null) {
                JsonNode meta = metaCache.get(c.bind() + "#read_only");
                if (meta != null && !meta.isNull() && !meta.isMissingNode()) {
                    return meta.asBoolean(false);
                }
            }
            return false;
        }
        return resolveJsonNodeBoolean(c.readOnly(), false, mergedDocument, exprCache, bindings);
    }

    private static boolean resolveRequired(
            ComponentSpec c, Map<String, JsonNode> metaCache,
            ObjectNode mergedDocument, ExpressionCache exprCache, JsonataBindings bindings
    ) {
        if (c.required() == null) {
            if (c.bind() != null) {
                JsonNode meta = metaCache.get(c.bind() + "#required");
                if (meta != null && !meta.isNull() && !meta.isMissingNode()) {
                    return meta.asBoolean(false);
                }
            }
            return false;
        }
        return resolveJsonNodeBoolean(c.required(), false, mergedDocument, exprCache, bindings);
    }

    private static boolean resolveEnabled(
            ComponentSpec c, boolean effectiveReadOnly,
            ObjectNode mergedDocument, ExpressionCache exprCache, JsonataBindings bindings
    ) {
        if (c.enabled() == null) return !effectiveReadOnly;
        return resolveJsonNodeBoolean(c.enabled(), !effectiveReadOnly, mergedDocument, exprCache, bindings);
    }

    static boolean resolveJsonNodeBoolean(JsonNode spec, boolean fallback,
                                          ObjectNode mergedDocument, ExpressionCache exprCache,
                                          JsonataBindings bindings) {
        if (spec == null || spec.isNull()) return fallback;
        if (spec.isBoolean()) return spec.asBoolean();
        if (spec.isTextual()) {
            try {
                JsonNode result = eval(exprCache, spec.asText(), mergedDocument, bindings);
                return result != null && result.asBoolean(fallback);
            } catch (Exception ignored) {
                return fallback;
            }
        }
        return fallback;
    }

    // ── Text resolver ─────────────────────────────────────────────────────────

    private static String resolveText(JsonNode textSpec, ObjectNode mergedDocument,
                                      ExpressionCache exprCache, JsonataBindings bindings) {
        if (textSpec == null || textSpec.isNull()) return null;
        String raw = textSpec.asText();
        if (textSpec.isTextual() && raw.contains("$")) {
            try {
                JsonNode result = eval(exprCache, raw, mergedDocument, bindings);
                return result != null ? result.asText(raw) : raw;
            } catch (Exception ignored) {
                return raw;
            }
        }
        return raw;
    }

    /**
     * Like {@link #resolveText} but keeps the result a {@link JsonNode}, for the places a
     * component wants the evaluated value itself rather than its string form — a
     * {@code statTile} with no {@code bind} still holds a number, and stringifying it here would
     * make every renderer parse it back.
     */
    private static JsonNode resolveNode(JsonNode spec, ObjectNode mergedDocument,
                                        ExpressionCache exprCache, JsonataBindings bindings) {
        if (spec == null || spec.isNull()) return null;
        if (!spec.isTextual() || !spec.asText().contains("$")) return spec;
        try {
            JsonNode result = eval(exprCache, spec.asText(), mergedDocument, bindings);
            return result != null && !result.isMissingNode() ? result : spec;
        } catch (Exception ignored) {
            return spec;
        }
    }

    /** Evaluates {@code expr} against {@code doc}, binding {@code $const} when {@code bindings} is set. */
    private static JsonNode eval(ExpressionCache exprCache, String expr, ObjectNode doc,
                                 JsonataBindings bindings)
            throws org.json_kula.jsonata_jvm.JsonataEvaluationException {
        return bindings != null
                ? exprCache.get(expr).evaluate(doc, bindings)
                : exprCache.get(expr).evaluate(doc);
    }
}
