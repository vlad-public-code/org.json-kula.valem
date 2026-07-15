package org.json_kula.valem.view.engine;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.json_kula.jsonata_jvm.JsonataBindings;
import org.json_kula.valem.core.engine.ExpressionCache;
import org.json_kula.valem.core.state.PathConverter;
import org.json_kula.valem.view.model.ComponentSpec;
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
        String   text  = resolveText(c.text(), mergedDocument, exprCache, bindings);

        List<EvaluatedComponent> subComponents = null;
        if (c.components() != null && !c.components().isEmpty()) {
            subComponents = c.components().stream()
                    .map(sub -> evaluateComponent(sub, mergedDocument, metaCache, exprCache, bindings))
                    .toList();
        }

        return switch (c.type()) {
            case "separatorLine" ->
                    new EvaluatedSeparatorLine(c.id(), c.type(), visible);

            case "staticText" ->
                    new EvaluatedStaticText(c.id(), c.type(), visible, text);

            case "badge" ->
                    new EvaluatedBadge(c.id(), c.type(), visible, c.variant(), text, c.label());

            case "label" ->
                    new EvaluatedLabel(c.id(), c.type(), c.label(), c.bind(), value, visible);

            case "progressBar" ->
                    new EvaluatedProgressBar(c.id(), c.type(), c.label(), c.bind(), value,
                            visible, c.min(), c.max(), c.showValue(), c.format(), c.tooltip());

            case "dataTable" ->
                    new EvaluatedDataTable(c.id(), c.type(), c.label(), c.bind(), value,
                            visible, c.tableColumns(), c.pageSize(), c.tooltip());

            case "dataChart" ->
                    new EvaluatedDataChart(c.id(), c.type(), c.label(), c.bind(), value,
                            visible, c.chartType(), c.chartX(), c.chartSeries());

            case "group", "fieldSet" ->
                    new EvaluatedContainer(c.id(), c.type(), visible,
                            c.layout(), c.columns(), subComponents, c.legend());

            case "sectionList" -> {
                boolean canAdd    = resolveJsonNodeBoolean(c.canAdd(),    true,  mergedDocument, exprCache, bindings);
                boolean canRemove = resolveJsonNodeBoolean(c.canRemove(), true,  mergedDocument, exprCache, bindings);
                yield new EvaluatedSectionList(c.id(), c.type(), c.label(), c.bind(), visible,
                        subComponents, c.layout(), c.columns(), canAdd, canRemove,
                        c.addLabel(), c.removeLabel(), c.onChange());
            }

            case "button" ->
                    new EvaluatedButton(c.id(), c.type(), c.label(), visible, enabled,
                            c.variant(), c.icon(), c.onClick());

            case "menu" ->
                    new EvaluatedMenu(c.id(), c.type(), visible, c.orientation(), c.menuItems());

            case "sliderField" ->
                    new EvaluatedSlider(c.id(), c.type(), c.label(), c.bind(), value,
                            visible, enabled, readOnly, required,
                            c.min(), c.max(), c.step(), c.helperText(), c.tooltip(), c.onChange());

            case "fileUploadField" ->
                    new EvaluatedFileUpload(c.id(), c.type(), c.label(), c.bind(), value,
                            visible, enabled, readOnly,
                            c.accept(), c.multiple(),
                            resolveIntMeta(c.bind(), "minItems",  c.minFiles(), metaCache),
                            resolveIntMeta(c.bind(), "maxItems",  c.maxFiles(), metaCache),
                            resolveLongMeta(c.bind(),   "minSize",  c.minSize(),  metaCache),
                            resolveLongMeta(c.bind(),   "maxSize",  c.maxSize(),  metaCache),
                            resolveStringMeta(c.bind(), "allowedMediaTypes", c.allowedMediaTypes(), metaCache),
                            c.helperText(), c.tooltip(), c.onChange());

            case "textAreaField" ->
                    new EvaluatedTextArea(c.id(), c.type(), c.label(), c.bind(), value,
                            visible, enabled, readOnly, required,
                            c.placeholder(), c.helperText(), c.tooltip(), c.rows(), c.onChange());

            case "selectField", "radioField", "multiSelectField" ->
                    new EvaluatedSelectField(c.id(), c.type(), c.label(), c.bind(), value,
                            visible, enabled, readOnly, required,
                            c.placeholder(), c.helperText(), c.tooltip(),
                            c.options(), c.onChange(), c.onOpen(), c.onClose());

            case "countryRegionSelector" ->
                    new EvaluatedDependentSelector(c.id(), c.type(), c.label(), c.bind(), value,
                            visible, enabled, readOnly, required,
                            c.placeholder(), c.helperText(), c.tooltip(),
                            c.options(), c.dependsOn(), c.onChange());

            // All remaining basic input types
            default ->
                    new EvaluatedBasicInput(c.id(), c.type(), c.label(), c.bind(), value,
                            visible, enabled, readOnly, required,
                            c.placeholder(), c.helperText(), c.tooltip(), c.onChange());
        };
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
