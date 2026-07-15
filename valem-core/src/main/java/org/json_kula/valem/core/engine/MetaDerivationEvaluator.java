package org.json_kula.valem.core.engine;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.NullNode;
import org.json_kula.jsonata_jvm.JsonataBindings;
import org.json_kula.jsonata_jvm.JsonataEvaluationException;
import org.json_kula.jsonata_jvm.JsonataExpression;
import org.json_kula.valem.core.graph.CompiledModel;
import org.json_kula.valem.core.graph.DependencyGraph;
import org.json_kula.valem.core.model.MetaDerivationSpec;
import org.json_kula.valem.core.state.ModelState;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Evaluates meta-derivations after a mutation.
 *
 * <p>Meta-derivations compute dynamic field metadata (minimum, maximum, required,
 * pattern, enum, multipleOf, readOnly, relevant) and store the results in the meta cache
 * keyed by {@code "path#property"} (or {@code "path[N]#property"} for array elements).
 *
 * <p>For paths containing {@code [*]}, the derivation is evaluated once <em>per
 * concrete element</em> with the element object as the JSONata context. This means
 * the expression sees element-local fields: e.g. {@code "limit"} resolves to
 * {@code element.limit}, not the root document's {@code $.limit}. Elements with
 * different field values therefore receive different meta values.
 */
public final class MetaDerivationEvaluator {

    private final ExpressionCache cache;

    public MetaDerivationEvaluator(ExpressionCache cache) {
        this.cache = cache;
    }

    /**
     * Evaluates all meta-derivation nodes that are dirty, following topological order.
     *
     * @param model      the compiled model
     * @param state      current model state
     * @param dirtyNodes dirty graph nodes from propagation
     * @return node keys ({@code "path#property"}) that were updated
     */
    public List<String> evaluate(CompiledModel model, ModelState state, Set<String> dirtyNodes) {
        return evaluate(model, state, dirtyNodes, null);
    }

    /**
     * Same as {@link #evaluate(CompiledModel, ModelState, Set)} but reuses a pre-built merged
     * document (base + all derived values) as the evaluation context for whole-document
     * (non-wildcard) meta-derivations, instead of materializing one per node. Pass the merged
     * document already produced by the derivation pass; {@code null} falls back to building one
     * lazily on first use (B-T1).
     */
    public List<String> evaluate(CompiledModel model, ModelState state, Set<String> dirtyNodes,
                                 JsonNode merged) {
        List<String> updated = new ArrayList<>();
        JsonataBindings bindings = EvalBindings.forModel(model);

        for (String nodeKey : model.graph().evaluationOrder()) {
            DependencyGraph.NodeInfo info = model.graph().nodeInfo(nodeKey);
            if (info == null || info.kind() != DependencyGraph.NodeKind.META) continue;
            if (nodeKey.startsWith("$constraint:")) continue; // handled by ConstraintEvaluator

            MetaDerivationSpec spec = model.metaDerivationFor(nodeKey);
            if (spec == null || !dirtyNodes.contains(nodeKey)) continue;

            if (spec.path().contains("[*]")) {
                // Per-element: evaluate with the element object as context so element-local
                // fields are visible (e.g. "limit" resolves to element.limit, not $.limit).
                List<String> concretePaths = ArrayPathExpander.expand(spec.path(), state);
                for (String concretePath : concretePaths) {
                    String elementPath = elementPathOf(concretePath, spec.path());
                    JsonNode elementContext = state.getValue(elementPath);
                    JsonNode result = evalExpressionWithContext(spec.expr(), elementContext, bindings);
                    String concreteKey = concretePath + "#" + spec.property().name().toLowerCase();
                    state.setMeta(concreteKey, result);
                    updated.add(concreteKey);
                }
            } else {
                if (merged == null) merged = state.mergedDocument();
                JsonNode result = evalExpressionWithContext(spec.expr(), merged, bindings);
                state.setMeta(nodeKey, result);
                updated.add(nodeKey);
            }
        }

        return updated;
    }

    /**
     * Extracts the array element path from a concrete path given the wildcard pattern.
     * E.g. {@code elementPathOf("$.items[0].qty", "$.items[*].qty")} → {@code "$.items[0]"}.
     */
    static String elementPathOf(String concretePath, String patternPath) {
        int wildcardIdx = patternPath.indexOf("[*]");
        if (wildcardIdx < 0) return concretePath;
        int closeIdx = concretePath.indexOf(']', wildcardIdx + 1);
        if (closeIdx < 0) return concretePath;
        return concretePath.substring(0, closeIdx + 1);
    }

    private JsonNode evalExpressionWithContext(String expr, JsonNode context, JsonataBindings bindings) {
        try {
            JsonataExpression compiled = cache.get(expr);
            JsonNode result = compiled.evaluate(context, bindings);
            return result != null ? result : NullNode.instance;
        } catch (JsonataEvaluationException e) {
            return NullNode.instance;
        }
    }
}
