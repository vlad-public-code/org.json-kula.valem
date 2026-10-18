package org.json_kula.valem.view.engine;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Collects the JSONata expression strings {@link ViewEvaluator} will compile when it renders a
 * {@code viewDefinition}, so a model's view can be <b>batch-warmed</b> into the expression cache at
 * registration time (one javac invocation) instead of being compiled one expression at a time on the
 * first {@code GET /models/{id}/view}.
 *
 * <p>Why this matters: that first render runs on a request virtual thread, and on a host where Loom
 * gives parallelism 1 a view of ~20 expressions spent several seconds in back-to-back javac runs,
 * starving every other request (health checks included) for the duration — tic-tac-toe's board froze
 * the hosted sandbox's health endpoint for 4–9 s and pushed it toward its memory limit.
 *
 * <p>Walks the raw JSON tree rather than the typed records, so every container shape (including
 * {@code editableTable} cells and {@code sectionList} item views) is covered without a per-type
 * switch. The key sets mirror the evaluation call sites in {@link ViewEvaluator}:
 * <ul>
 *   <li>{@link #TEXT_KEYS} go through {@code resolveText}/{@code resolveNode}, which only evaluate
 *       strings containing {@code $} (bare field references are left to lazy compilation);</li>
 *   <li>{@link #BOOLEAN_KEYS} go through {@code resolveJsonNodeBoolean}, which evaluates any string.</li>
 * </ul>
 * Like {@code SpecExpressions.collect}, the result is a best-effort superset for warming: a missed
 * expression just compiles lazily, and an over-collected one (or an invalid one, which warming skips)
 * costs at most a wasted class — never correctness.
 */
public final class ViewExpressions {

    /** Display fields evaluated as JSONata when they contain {@code $}. */
    static final Set<String> TEXT_KEYS = Set.of("text", "src", "href", "value", "delta", "caption", "trend");

    /** Boolean fields evaluated as JSONata whenever they are a string. */
    static final Set<String> BOOLEAN_KEYS = Set.of(
            "visible", "enabled", "readOnly", "required", "collapsed", "canAdd", "canRemove");

    private ViewExpressions() {}

    /** All expression strings in {@code viewDefinition} (null-safe), deduplicated, in encounter order. */
    public static List<String> collect(JsonNode viewDefinition) {
        List<String> out = new ArrayList<>();
        walk(viewDefinition, out);
        return out.stream().distinct().toList();
    }

    private static void walk(JsonNode node, List<String> out) {
        if (node == null) return;
        if (node.isArray()) {
            node.forEach(child -> walk(child, out));
        } else if (node.isObject()) {
            for (Iterator<Map.Entry<String, JsonNode>> it = node.fields(); it.hasNext(); ) {
                Map.Entry<String, JsonNode> field = it.next();
                JsonNode value = field.getValue();
                if (value.isTextual()) {
                    String s = value.asText();
                    if (s.isBlank()) continue;
                    if (BOOLEAN_KEYS.contains(field.getKey())
                            || (TEXT_KEYS.contains(field.getKey()) && s.contains("$"))) {
                        out.add(s);
                    }
                } else {
                    walk(value, out);
                }
            }
        }
    }
}
