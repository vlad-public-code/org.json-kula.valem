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
import org.json_kula.valem.view.model.DependentSelectorSpec;
import org.json_kula.valem.view.model.FileUploadSpec;
import org.json_kula.valem.view.model.LabelSpec;
import org.json_kula.valem.view.model.MenuSpec;
import org.json_kula.valem.view.model.ProgressBarSpec;
import org.json_kula.valem.view.model.SectionListSpec;
import org.json_kula.valem.view.model.SeparatorLineSpec;
import org.json_kula.valem.view.model.SliderSpec;
import org.json_kula.valem.view.model.StaticTextSpec;
import org.json_kula.valem.view.model.TextAreaSpec;
import org.json_kula.valem.view.model.UnknownComponentSpec;
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
                    new EvaluatedSeparatorLine(s.id(), s.type(), visible);

            case StaticTextSpec s ->
                    new EvaluatedStaticText(s.id(), s.type(), visible,
                            resolveText(s.text(), mergedDocument, exprCache, bindings));

            case BadgeSpec b ->
                    new EvaluatedBadge(b.id(), b.type(), visible, b.variant(),
                            resolveText(b.text(), mergedDocument, exprCache, bindings), b.label());

            case LabelSpec l ->
                    new EvaluatedLabel(l.id(), l.type(), l.label(), l.bind(), value, visible);

            case ProgressBarSpec p ->
                    new EvaluatedProgressBar(p.id(), p.type(), p.label(), p.bind(), value,
                            visible, p.min(), p.max(), p.showValue(), p.format(), p.tooltip());

            case DataTableSpec t ->
                    new EvaluatedDataTable(t.id(), t.type(), t.label(), t.bind(), value,
                            visible, t.tableColumns(), t.pageSize(), t.tooltip());

            case DataChartSpec ch ->
                    new EvaluatedDataChart(ch.id(), ch.type(), ch.label(), ch.bind(), value,
                            visible, ch.chartType(), ch.chartX(), ch.chartSeries());

            case ContainerSpec g ->
                    new EvaluatedContainer(g.id(), g.type(), g.bind(), visible,
                            g.layout(), g.columns(),
                            evaluateChildren(g.components(), mergedDocument, metaCache, exprCache, bindings),
                            g.legend());

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

            case TextAreaSpec t ->
                    new EvaluatedTextArea(t.id(), t.type(), t.label(), t.bind(), value,
                            visible, enabled, readOnly, required,
                            t.placeholder(), t.helperText(), t.tooltip(), t.rows(), t.onChange());

            case ChoiceInputSpec s ->
                    new EvaluatedSelectField(s.id(), s.type(), s.label(), s.bind(), value,
                            visible, enabled, readOnly, required,
                            s.placeholder(), s.helperText(), s.tooltip(),
                            s.options(), s.onChange(), s.onOpen(), s.onClose());

            case DependentSelectorSpec d ->
                    new EvaluatedDependentSelector(d.id(), d.type(), d.label(), d.bind(), value,
                            visible, enabled, readOnly, required,
                            d.placeholder(), d.helperText(), d.tooltip(),
                            d.options(), d.dependsOn(), d.onChange());

            case BasicInputSpec b ->
                    new EvaluatedBasicInput(b.id(), b.type(), b.label(), b.bind(), value,
                            visible, enabled, readOnly, required,
                            b.placeholder(), b.helperText(), b.tooltip(), b.onChange());

            // A type Valem does not know: rendered as a basic input, as before.
            case UnknownComponentSpec u ->
                    new EvaluatedBasicInput(u.id(), u.type(), u.label(), u.bind(), value,
                            visible, enabled, readOnly, required,
                            u.placeholder(), u.helperText(), u.tooltip(), u.onChange());
        };
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

    /** Evaluates {@code expr} against {@code doc}, binding {@code $const} when {@code bindings} is set. */
    private static JsonNode eval(ExpressionCache exprCache, String expr, ObjectNode doc,
                                 JsonataBindings bindings)
            throws org.json_kula.jsonata_jvm.JsonataEvaluationException {
        return bindings != null
                ? exprCache.get(expr).evaluate(doc, bindings)
                : exprCache.get(expr).evaluate(doc);
    }
}
