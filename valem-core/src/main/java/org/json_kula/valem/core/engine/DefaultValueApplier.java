package org.json_kula.valem.core.engine;

import com.fasterxml.jackson.databind.JsonNode;
import org.json_kula.jsonata_jvm.JsonataBindings;
import org.json_kula.jsonata_jvm.JsonataEvaluationException;
import org.json_kula.jsonata_jvm.JsonataExpression;
import org.json_kula.valem.core.graph.CompiledModel;
import org.json_kula.valem.core.model.DefaultValueSpec;
import org.json_kula.valem.core.state.DirtyPropagator;
import org.json_kula.valem.core.state.ModelState;
import org.json_kula.valem.core.state.PathConverter;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Applies {@link DefaultValueSpec} rules to newly-created containers during a mutation.
 *
 * <p>Given the set of container paths (array elements, objects, or the document root {@code $}) that
 * were first created by the writes in this cycle, each matching rule's expression is evaluated and its
 * resulting object is deep-merged into the container, setting only leaves the caller left absent
 * (fill-absent semantics). Defaults are ordinary base writes routed through {@link ModelState#setValue}
 * so they are marked dirty and picked up by the downstream reactive pipeline in the same cycle.
 *
 * <p>The expression is evaluated against the live base document (so a later rule sees an earlier
 * rule's writes), with {@code $parent} bound to the container's JSON-tree parent and {@code $self}
 * to the container node itself. Containers are processed outermost-first so a parent object's
 * defaults are visible to a nested container's {@code $parent}.
 */
public final class DefaultValueApplier {

    private final ExpressionCache cache;

    public DefaultValueApplier(ExpressionCache cache) {
        this.cache = cache;
    }

    /**
     * Applies every matching default rule to each newly-created container and returns the concrete
     * paths that were written (in application order).
     */
    public List<String> apply(CompiledModel model, ModelState state, Set<String> newContainerPaths) {
        List<DefaultValueSpec> rules = model.spec().defaultValues();
        if (rules.isEmpty() || newContainerPaths.isEmpty()) return List.of();

        // Outermost containers first: fewer path segments = shallower.
        List<String> containers = new ArrayList<>(newContainerPaths);
        containers.sort(Comparator.comparingInt(c -> PathConverter.toSegments(c).size()));

        List<String> written = new ArrayList<>();
        for (String container : containers) {
            for (DefaultValueSpec rule : rules) {
                if (!DirtyPropagator.matchesPattern(rule.path(), container)) continue;
                applyRule(rule, container, model, state, written);
            }
        }
        return written;
    }

    private void applyRule(DefaultValueSpec rule, String container, CompiledModel model,
                           ModelState state, List<String> written) {
        JsonNode self   = state.baseDoc().at(PathConverter.toJsonPointer(container));
        JsonNode parent = state.baseDoc().at(PathConverter.toJsonPointer(parentOf(container)));
        JsonataBindings bindings = EvalBindings.forModel(model)
                .bindValue("parent", parent)
                .bindValue("self", self);

        JsonNode result;
        try {
            JsonataExpression compiled = cache.get(rule.expr());
            result = compiled.evaluate(state.baseDoc(), bindings);
        } catch (JsonataEvaluationException e) {
            return; // a rule that fails to evaluate is skipped, not fatal to the mutation
        }
        if (result == null || !result.isObject()) return;

        List<String> containerSegments = PathConverter.toSegments(container);
        for (Map.Entry<List<String>, JsonNode> leaf : flattenLeaves(result).entrySet()) {
            List<String> targetSegments = new ArrayList<>(containerSegments);
            targetSegments.addAll(leaf.getKey());
            String target = PathConverter.toCanonicalAddress(String.join(".", targetSegments));

            // Never overwrite a caller-provided value, and never write a read-only derived field.
            if (state.existsInBase(target)) continue;
            if (model.derivationFor(target) != null) continue;

            state.setValue(target, leaf.getValue());
            written.add(target);
        }
    }

    // ── Helpers ─────────────────────────────────────────────────────────────────

    /**
     * Flattens an object into leaf (relative-segment-path → value) entries. Recurses into nested
     * objects; scalars and arrays are leaves set wholesale. Explicit nulls are skipped (a null
     * default is indistinguishable from "no default" under fill-absent semantics). Insertion order
     * is preserved so writes are deterministic.
     */
    private static Map<List<String>, JsonNode> flattenLeaves(JsonNode obj) {
        Map<List<String>, JsonNode> out = new java.util.LinkedHashMap<>();
        flattenInto(obj, new ArrayList<>(), out);
        return out;
    }

    private static void flattenInto(JsonNode node, List<String> prefix,
                                    Map<List<String>, JsonNode> out) {
        node.fields().forEachRemaining(e -> {
            List<String> path = new ArrayList<>(prefix);
            path.add(e.getKey());
            JsonNode v = e.getValue();
            if (v.isObject() && !v.isEmpty()) {
                flattenInto(v, path, out);
            } else if (!v.isNull()) {
                out.put(path, v);
            }
        });
    }

    /** Drops the last segment of {@code container} to yield its JSON-tree parent ({@code $} → {@code $}). */
    private static String parentOf(String container) {
        List<String> segs = PathConverter.toSegments(container);
        if (segs.isEmpty()) return "$";
        return PathConverter.toCanonicalAddress(String.join(".", segs.subList(0, segs.size() - 1)));
    }
}
