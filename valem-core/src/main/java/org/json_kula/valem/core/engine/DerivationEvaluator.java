package org.json_kula.valem.core.engine;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.NullNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.json_kula.jsonata_jvm.JsonataBindings;
import org.json_kula.jsonata_jvm.JsonataEvaluationException;
import org.json_kula.jsonata_jvm.JsonataExpression;
import org.json_kula.valem.core.graph.CompiledModel;
import org.json_kula.valem.core.graph.DependencyGraph;
import org.json_kula.valem.core.model.DerivationSpec;
import org.json_kula.valem.core.model.EvaluationMode;
import org.json_kula.valem.core.state.ModelState;
import org.json_kula.valem.core.state.PathConverter;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Evaluates value derivations after a mutation.
 *
 * <p>Given the set of dirty nodes (paths that changed directly or transitively),
 * re-evaluates only the derived fields whose evaluation order position requires it,
 * skipping fields that are not dirty and not in EAGER mode.
 *
 * <p>Derivations are evaluated <b>sequentially</b> in topological level order. Intra-level
 * parallelism was removed (the per-task executor and futures added overhead that dominated the
 * actual per-expression cost); evaluation order within a level is irrelevant because same-level
 * nodes are mutually independent by construction.
 *
 * <p>Each topological level evaluates against {@code state.mergedDocument()} — the base
 * document with all previously computed derived values spliced in. This means a derivation
 * at level k+1 can reference the result of a derivation at level k using the same dot-path
 * it would use for a base field. All tasks within a level share the same pre-level
 * snapshot so they see a consistent view.
 *
 * <p>Wildcard derivations (paths containing {@code [*]}, e.g. {@code $.items[*].lineTotal})
 * are evaluated once per array element. The expression receives the full merged document as
 * context plus a {@code $parent} variable bound to the element node, allowing expressions
 * like {@code $parent.price * $parent.qty}.
 */
public final class DerivationEvaluator {

    private final ExpressionCache cache;

    public DerivationEvaluator(ExpressionCache cache) {
        this.cache = cache;
    }

    private record EvalResult(String path, JsonNode value, DerivationTrace trace) {}

    /**
     * Result of a derivation pass: the paths that were (re-)evaluated, plus the merged document
     * as it stood after the last level (base + all derived values). The merged document is
     * {@code null} when no derivation work was performed (no dirty EAGER derivations), in which
     * case the caller already holds an up-to-date view and need not materialize one.
     *
     * @param evaluated paths of derived fields that were (re-)evaluated in this pass
     * @param merged    post-derivation merged document, or {@code null} if nothing was evaluated
     */
    public record EvaluationOutcome(List<String> evaluated, ObjectNode merged) {}

    /**
     * Evaluates all derived fields that are marked dirty, following the topological
     * level order.
     *
     * @param model      the compiled model (provides derivation specs + evaluation levels)
     * @param state      the current model state (read input, write results)
     * @param dirtyNodes the set of dirty graph nodes produced by dirty propagation
     * @return paths of derived fields that were (re-)evaluated in this call
     */
    public List<String> evaluate(CompiledModel model, ModelState state, Set<String> dirtyNodes) {
        return evaluate(model, state, dirtyNodes, null);
    }

    /**
     * Same as {@link #evaluate(CompiledModel, ModelState, Set)} but appends a
     * {@link DerivationTrace} for every field evaluation (success or error) to {@code traces}.
     *
     * @param traces list to append traces to; may be {@code null} to skip recording
     */
    public List<String> evaluate(CompiledModel model, ModelState state, Set<String> dirtyNodes,
                                 List<DerivationTrace> traces) {
        return evaluateAndMerge(model, state, dirtyNodes, traces).evaluated();
    }

    /**
     * Evaluates dirty derivations in topological level order, maintaining a <b>single</b> merged
     * document that is spliced forward between levels instead of being deep-copied per level (B-T1).
     *
     * <p>Within a level, every node is evaluated against the pre-level view of the merged document,
     * so same-level derivations cannot observe each other. After the level completes, its results
     * are written to state and spliced into the shared merged document so the next level sees them.
     *
     * <p>The returned {@link EvaluationOutcome#merged()} is byte-for-byte identical to a fresh
     * {@link ModelState#mergedDocument()} call made after this pass, so callers (constraints,
     * actions, meta-derivations) can reuse it directly rather than materializing their own copy.
     */
    public EvaluationOutcome evaluateAndMerge(CompiledModel model, ModelState state,
                                              Set<String> dirtyNodes, List<DerivationTrace> traces) {
        List<String> evaluated = new ArrayList<>();
        // Built lazily on the first level with work; one deep copy per cycle, reused across levels.
        ObjectNode merged = null;

        for (List<String> level : model.graph().evaluationLevels()) {
            List<String> eagerDirty = collectEagerDirty(level, model, state, dirtyNodes);
            if (eagerDirty.isEmpty()) continue;

            if (merged == null) merged = state.mergedDocument();

            // Wildcard paths ([*]) are evaluated at root and their array result is
            // distributed across concrete element paths.
            List<String> wildcardNodes = new ArrayList<>();
            List<String> concreteNodes = new ArrayList<>();
            for (String key : eagerDirty) {
                if (key.contains("[*]")) wildcardNodes.add(key);
                else concreteNodes.add(key);
            }

            // Evaluate every node in this level against the pre-level merged view (so same-level
            // nodes cannot see each other), collecting results without yet splicing them in.
            List<EvalResult> levelResults = new ArrayList<>();
            for (String nodeKey : concreteNodes) {
                levelResults.add(evalOne(nodeKey, model, merged));
            }
            for (String nodeKey : wildcardNodes) {
                evalWildcardDerivation(nodeKey, model, state, merged, levelResults);
            }

            // Commit this level's results to state and splice them into the shared merged
            // document so the NEXT level sees them (the cross-level visibility contract).
            for (EvalResult r : levelResults) {
                applyResult(r, state, evaluated, traces);
                ModelState.spliceDerived(merged, r.path(), r.value());
            }
        }

        return new EvaluationOutcome(evaluated, merged);
    }

    /**
     * Evaluates a derivation whose path contains {@code [*]} by iterating over all
     * concrete element paths and evaluating the expression once per element, with
     * {@code $parent} bound to the element node.
     *
     * <p>For example, a derivation at {@code $.items[*].lineTotal} with expression
     * {@code $parent.price * $parent.qty} is evaluated once for {@code $.items[0]}
     * (with {@code $parent = items[0]}) and once for {@code $.items[1]}, etc.
     */
    private void evalWildcardDerivation(String nodeKey, CompiledModel model, ModelState state,
                                        JsonNode evalContext, List<EvalResult> out) {
        DerivationSpec spec = model.derivationFor(nodeKey);
        List<String> inputPaths = new ArrayList<>(model.graph().dependenciesOf(nodeKey));

        // Expand the array-element prefix (up to and including [*]) to get concrete element paths.
        // The derived field itself does not exist in the base doc yet, so expand the parent array.
        int wildcardPos = spec.path().indexOf("[*]");
        String arrayPattern = spec.path().substring(0, wildcardPos + 3); // e.g. "$.items[*]"
        String fieldSuffix  = spec.path().substring(wildcardPos + 3);    // e.g. ".lineTotal"

        List<String> elementPaths = ArrayPathExpander.expand(arrayPattern, state);
        // No manual "clear stale wildcard entries" pass is needed: mergedDocument() / Snapshot
        // splice derived values length-aware (ModelState.setDerivedInDoc), so a stale entry like
        // $.items[2].lineTotal left over after the array shrank is never spliced as a phantom
        // element. Re-evaluation below overwrites the entries for the indices that still exist.
        if (elementPaths.isEmpty()) return;

        JsonataExpression compiled = cache.get(spec.expr()); // CompilationException propagates

        for (String elementPath : elementPaths) {
            String concretePath = elementPath + fieldSuffix;

            // Bind $parent to the element node so the expression can reference its fields directly.
            JsonNode parentNode = evalContext.at(PathConverter.toJsonPointer(elementPath));
            JsonataBindings bindings = EvalBindings.forModel(model).bindValue("parent", parentNode);

            JsonNode result;
            try {
                result = compiled.evaluate(evalContext, bindings);
                if (result == null) result = NullNode.instance;
            } catch (JsonataEvaluationException e) {
                out.add(new EvalResult(concretePath, NullNode.instance,
                        DerivationTrace.ofError(concretePath, spec.expr(), e.getMessage())));
                continue;
            }

            out.add(new EvalResult(concretePath, result,
                    DerivationTrace.ofDerivation(concretePath, spec.expr(), inputPaths, result)));
        }
    }

    /** Filters a level's nodes down to dirty EAGER derivations, marking LAZY ones stale. */
    private List<String> collectEagerDirty(List<String> level, CompiledModel model,
                                           ModelState state, Set<String> dirtyNodes) {
        List<String> result = new ArrayList<>();
        for (String nodeKey : level) {
            DependencyGraph.NodeInfo info = model.graph().nodeInfo(nodeKey);
            if (info == null || info.kind() != DependencyGraph.NodeKind.DERIVED) continue;

            DerivationSpec spec = model.derivationFor(nodeKey);
            if (spec == null || !dirtyNodes.contains(nodeKey)) continue;

            if (spec.evaluation() == EvaluationMode.LAZY) {
                state.markLazyStale(spec.path());
            } else {
                result.add(nodeKey);
            }
        }
        return result;
    }

    /** Evaluates one derivation against the base document snapshot. Always returns a result. */
    private EvalResult evalOne(String nodeKey, CompiledModel model, JsonNode baseDoc) {
        DerivationSpec spec = model.derivationFor(nodeKey);
        List<String> inputPaths = new ArrayList<>(model.graph().dependenciesOf(nodeKey));
        try {
            JsonataExpression compiled = cache.get(spec.expr());
            JsonNode result = compiled.evaluate(baseDoc, EvalBindings.forModel(model));
            if (result == null) result = NullNode.instance;
            return new EvalResult(spec.path(), result,
                    DerivationTrace.ofDerivation(spec.path(), spec.expr(), inputPaths, result));
        } catch (JsonataEvaluationException e) {
            return new EvalResult(spec.path(), NullNode.instance,
                    DerivationTrace.ofError(spec.path(), spec.expr(), e.getMessage()));
        }
    }

    /** Writes one eval result to state and appends its trace if recording is active. */
    private static void applyResult(EvalResult r, ModelState state,
                                    List<String> evaluated, List<DerivationTrace> traces) {
        state.setDerived(r.path(), r.value());
        evaluated.add(r.path());
        if (traces != null) traces.add(r.trace());
    }

    /**
     * Evaluates a single expression against the merged state (base + all cached derived values).
     * Used for on-demand evaluation of LAZY derivations.
     * Returns {@link NullNode} on evaluation error (expression mis-typed, missing data, etc.).
     */
    public JsonNode evalExpression(String expr, ModelState state) {
        return evalExpression(expr, state, null);
    }

    /**
     * Same as {@link #evalExpression(String, ModelState)} but binds the given extra bindings
     * (e.g. {@code $const} from {@link EvalBindings#forModel}). {@code bindings} may be {@code null}.
     */
    public JsonNode evalExpression(String expr, ModelState state, JsonataBindings bindings) {
        try {
            JsonataExpression compiled = cache.get(expr);
            JsonNode result = bindings != null
                    ? compiled.evaluate(state.mergedDocument(), bindings)
                    : compiled.evaluate(state.mergedDocument());
            return result != null ? result : NullNode.instance;
        } catch (JsonataEvaluationException e) {
            return NullNode.instance;
        }
    }

    /**
     * Evaluates a single expression against a caller-supplied {@code context} document, without
     * materializing a fresh merged document per call. Lets the read path (e.g.
     * {@link ModelRuntime#fullState()}) build one merged document, evaluate every stale LAZY
     * derivation against it, and splice each result back in — a single deep copy per read instead
     * of one per LAZY derivation (audit CPU-3). Returns {@link NullNode} on evaluation error.
     */
    public JsonNode evalExpressionAgainst(String expr, JsonNode context, JsonataBindings bindings) {
        try {
            JsonataExpression compiled = cache.get(expr);
            JsonNode result = bindings != null
                    ? compiled.evaluate(context, bindings)
                    : compiled.evaluate(context);
            return result != null ? result : NullNode.instance;
        } catch (JsonataEvaluationException e) {
            return NullNode.instance;
        }
    }
}
