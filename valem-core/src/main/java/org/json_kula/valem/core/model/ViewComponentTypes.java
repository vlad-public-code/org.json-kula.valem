package org.json_kula.valem.core.model;

import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;

/**
 * The closed vocabulary of {@code viewDefinition} component {@code type} values.
 *
 * <p>Lives in {@code core.model} rather than {@code valem-view} because {@code ModelSpecValidator}
 * must reject an unknown type at write time and core carries no {@code valem-view} dependency. Both
 * the server-side {@code ViewEvaluator} and the bundled React renderer switch over exactly this set;
 * {@code ViewComponentTypesCoverageTest} in {@code valem-view} pins them together.
 *
 * <p>Why a closed set: {@code ViewEvaluator}'s {@code default} branch is deliberately permissive
 * (every plain input type funnels into {@code EvaluatedBasicInput}), so a typo like
 * {@code "number-input"} used to evaluate happily server-side and only surface in the browser as a
 * literal "Unknown component type" box. Validating here turns that into an authoring-time error an
 * agent can act on.
 */
public final class ViewComponentTypes {

    /** Input components — bind to a writable path and accept user edits. */
    public static final Set<String> FIELDS = Set.of(
            "textField", "textAreaField", "richTextField", "numericField", "currencyField",
            "percentField", "passwordField", "emailField", "phoneNumberField", "checkboxField",
            "toggleField", "selectField", "radioField", "multiSelectField", "autocompleteField",
            "comboBox", "tagsField", "dateField", "dateTimeField", "timeField", "dateRangeField",
            "sliderField", "ratingField", "numericStepper", "fileUploadField", "countrySelector",
            "countryRegionSelector");

    /** Read-only display components. */
    public static final Set<String> OUTPUT = Set.of(
            "label", "staticText", "badge", "alert", "callout", "separatorLine", "spacer",
            "image", "link", "dataTable", "dataChart", "sparkline", "progressBar", "gauge",
            "keyValueList", "summaryList", "statTile", "metric", "jsonViewer",
            "explainPanel", "auditTimeline", "validationSummary", "effectStatus");

    /** Containers — hold nested {@code components}. */
    public static final Set<String> AGGREGATES = Set.of(
            "group", "fieldSet", "card", "toolbar", "buttonGroup", "tabs", "tabItem",
            "accordion", "collapsible", "sectionList", "sectionItem");

    /** Action components. */
    public static final Set<String> ACTIONS = Set.of("button", "menu", "stepper", "breadcrumb");

    /** Every legal component {@code type}, in a stable sorted order for error messages. */
    public static final Set<String> ALL = unmodifiableSorted();

    private ViewComponentTypes() {}

    public static boolean isKnown(String type) {
        return type != null && ALL.contains(type);
    }

    /** The full vocabulary as a comma-separated list, for embedding in a validation message. */
    public static String allAsList() {
        return String.join(", ", ALL);
    }

    /**
     * Best-guess correction for an unknown type, or {@code null} when nothing is close enough.
     *
     * <p>Two passes, cheapest first: a punctuation/case-insensitive match (catches the kebab-case and
     * snake_case spellings an LLM reaches for — {@code "number_field"} for {@code "numericField"} is
     * not one of these, but {@code "TextField"} and {@code "text-field"} are), then a bounded
     * Levenshtein search over the normalised forms so near-misses like {@code "numberInput"} still
     * land on {@code "numericField"}'s neighbourhood rather than nothing.
     */
    public static String suggest(String unknown) {
        if (unknown == null || unknown.isBlank()) return null;
        String needle = normalise(unknown);
        for (String candidate : ALL) {
            if (normalise(candidate).equals(needle)) return candidate;
        }
        String best = null;
        int bestDistance = Integer.MAX_VALUE;
        // A third of the candidate's length, capped — long names tolerate more drift than short ones,
        // and beyond that a "did you mean" is noise rather than help.
        for (String candidate : ALL) {
            String c = normalise(candidate);
            int limit = Math.min(4, Math.max(2, c.length() / 3));
            int d = distance(needle, c, limit);
            if (d <= limit && d < bestDistance) {
                bestDistance = d;
                best = candidate;
            }
        }
        return best;
    }

    private static String normalise(String s) {
        return s.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
    }

    /** Levenshtein distance, abandoned early once every cell in a row exceeds {@code limit}. */
    private static int distance(String a, String b, int limit) {
        if (Math.abs(a.length() - b.length()) > limit) return limit + 1;
        int[] prev = new int[b.length() + 1];
        int[] cur = new int[b.length() + 1];
        for (int j = 0; j <= b.length(); j++) prev[j] = j;
        for (int i = 1; i <= a.length(); i++) {
            cur[0] = i;
            int rowMin = cur[0];
            for (int j = 1; j <= b.length(); j++) {
                int cost = a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1;
                cur[j] = Math.min(Math.min(cur[j - 1] + 1, prev[j] + 1), prev[j - 1] + cost);
                rowMin = Math.min(rowMin, cur[j]);
            }
            if (rowMin > limit) return limit + 1;
            int[] swap = prev;
            prev = cur;
            cur = swap;
        }
        return prev[b.length()];
    }

    private static Set<String> unmodifiableSorted() {
        Set<String> all = new java.util.TreeSet<>();
        all.addAll(FIELDS);
        all.addAll(OUTPUT);
        all.addAll(AGGREGATES);
        all.addAll(ACTIONS);
        return java.util.Collections.unmodifiableSet(new LinkedHashSet<>(all));
    }
}
