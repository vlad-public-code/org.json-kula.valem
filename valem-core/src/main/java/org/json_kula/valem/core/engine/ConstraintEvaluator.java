package org.json_kula.valem.core.engine;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.NullNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.json_kula.jsonata_jvm.JsonataBindings;
import org.json_kula.jsonata_jvm.JsonataEvaluationException;
import org.json_kula.jsonata_jvm.JsonataExpression;
import org.json_kula.valem.core.graph.CompiledModel;
import org.json_kula.valem.core.model.ConstraintPolicy;
import org.json_kula.valem.core.model.ConstraintSpec;
import org.json_kula.valem.core.state.ModelState;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Evaluates model constraints after reactive derivation has settled.
 *
 * <p>Each constraint is evaluated against the full model state document. If any
 * constraint fails its {@link ConstraintPolicy} determines the response:
 * <ul>
 *   <li>{@link ConstraintPolicy#ROLLBACK} — throws {@link ConstraintViolationException};
 *       the caller (usually {@link ModelRuntime}) is responsible for calling
 *       {@link ModelState#rollback()}.</li>
 *   <li>{@link ConstraintPolicy#FLAG}    — records the violation but continues.</li>
 * </ul>
 *
 * <p>Scoped constraints ({@code path} present in spec) are evaluated per-target:
 * for scalar paths the expression receives the field value as {@code $};
 * for array-scoped paths ({@code [*]}) the expression is evaluated once per element.
 */
public final class ConstraintEvaluator {

    /** Thrown when a ROLLBACK-policy constraint is violated. */
    public static final class ConstraintViolationException extends RuntimeException {
        private final List<Violation> violations;

        ConstraintViolationException(List<Violation> violations) {
            super(buildMessage(violations));
            this.violations = List.copyOf(violations);
        }

        /**
         * Reconstructs the exception from an already-computed violation list. Used by the remote-mode
         * CLI facade ({@code RemoteModelOperations}) to re-raise a server-reported ROLLBACK 409 as the
         * same typed exception the embedded engine throws, so callers cannot tell the modes apart by
         * error shape.
         */
        public static ConstraintViolationException of(List<Violation> violations) {
            return new ConstraintViolationException(violations);
        }

        public List<Violation> violations() { return violations; }

        private static String buildMessage(List<Violation> vs) {
            if (vs.size() == 1) return "Constraint violated: " + vs.getFirst().message();
            return vs.size() + " constraints violated: " +
                    vs.stream().map(Violation::message).toList();
        }
    }

    /** A single constraint violation recorded during evaluation. */
    public record Violation(String constraintId, String message, ConstraintPolicy policy) {}

    // ─────────────────────────────────────────────────────────────────────────

    private final ExpressionCache cache;

    public ConstraintEvaluator(ExpressionCache cache) {
        this.cache = cache;
    }

    /**
     * Evaluates all constraints whose synthetic graph node is dirty.
     *
     * @param model      compiled model
     * @param state      current model state (post-derivation)
     * @param dirtyNodes dirty graph nodes (from dirty propagation)
     * @param traces     list to append {@link DerivationTrace} entries to (may be null)
     * @return list of FLAG/WARN violations that did not trigger a rollback
     * @throws ConstraintViolationException if any ROLLBACK constraint fails
     */
    public List<Violation> evaluate(
            CompiledModel model,
            ModelState state,
            Set<String> dirtyNodes,
            List<DerivationTrace> traces) {
        // Backward-compatible entry point: materialize the merged document once and delegate.
        return evaluate(model, state, dirtyNodes, traces, state.mergedDocument());
    }

    /**
     * Same as {@link #evaluate(CompiledModel, ModelState, Set, List)} but reuses a caller-supplied
     * merged document (base + derived) instead of materializing its own. The caller
     * ({@code ModelRuntime}) computes it once per mutation cycle and shares it with the action phase,
     * so a cycle performs a single full materialization rather than one per global constraint plus
     * one for actions (B-T1).
     */
    public List<Violation> evaluate(
            CompiledModel model,
            ModelState state,
            Set<String> dirtyNodes,
            List<DerivationTrace> traces,
            ObjectNode merged) {

        List<Violation> flagged   = new ArrayList<>();
        List<Violation> rollbacks = new ArrayList<>();
        JsonataBindings bindings  = EvalBindings.forModel(model);

        for (ConstraintSpec constraint : model.constraints()) {
            String nodeKey = "$constraint:" + constraint.id();
            if (!dirtyNodes.contains(nodeKey)) continue;

            evaluateConstraint(constraint, state, merged, flagged, rollbacks, traces, bindings);
        }

        if (!rollbacks.isEmpty()) {
            if (traces != null) {
                for (Violation v : rollbacks) {
                    traces.add(DerivationTrace.ofConstraint(v.constraintId(), "", List.of(), false));
                }
            }
            throw new ConstraintViolationException(rollbacks);
        }

        return List.copyOf(flagged);
    }

    // ── Internals ─────────────────────────────────────────────────────────────

    private void evaluateConstraint(
            ConstraintSpec spec,
            ModelState state,
            ObjectNode merged,
            List<Violation> flagged,
            List<Violation> rollbacks,
            List<DerivationTrace> traces,
            JsonataBindings bindings) {

        if (spec.isGlobal()) {
            // Evaluate against the full merged document (base + derived) supplied by the caller.
            boolean passed = evalBoolean(spec.expr(), merged, bindings);
            recordTrace(traces, spec, passed);
            if (!passed) record(spec, flagged, rollbacks);

        } else if (spec.isArrayScoped()) {
            // Evaluate once per array element
            List<String> concretePaths = expandConstraintPaths(spec, state);
            for (String path : concretePaths) {
                JsonNode element = state.getValue(path);
                boolean passed = evalBoolean(spec.expr(), element, bindings);
                recordTrace(traces, spec, passed);
                if (!passed) record(spec, flagged, rollbacks);
            }

        } else if (spec.isMultiTarget()) {
            // Evaluate against each listed path
            for (String path : spec.path()) {
                JsonNode fieldValue = state.getValue(path);
                boolean passed = evalBoolean(spec.expr(), fieldValue, bindings);
                recordTrace(traces, spec, passed);
                if (!passed) record(spec, flagged, rollbacks);
            }

        } else {
            // Scalar — evaluate against the single target field value
            String path = spec.path().getFirst();
            JsonNode fieldValue = state.getValue(path);
            boolean passed = evalBoolean(spec.expr(), fieldValue, bindings);
            recordTrace(traces, spec, passed);
            if (!passed) record(spec, flagged, rollbacks);
        }
    }

    private boolean evalBoolean(String expr, JsonNode context, JsonataBindings bindings) {
        try {
            JsonataExpression compiled = cache.get(expr);
            JsonNode result = compiled.evaluate(context != null ? context : NullNode.instance, bindings);
            if (result == null || result.isNull() || result.isMissingNode()) return false;
            if (result.isBoolean()) return result.asBoolean();
            // Non-boolean truthy: non-zero number or non-empty string
            if (result.isNumber()) return result.asDouble() != 0;
            if (result.isTextual()) return !result.asText().isEmpty();
            return false;
        } catch (JsonataEvaluationException e) {
            return false; // evaluation error = constraint considered failed
        }
    }

    private List<String> expandConstraintPaths(ConstraintSpec spec, ModelState state) {
        List<String> result = new ArrayList<>();
        for (String p : spec.path()) {
            result.addAll(org.json_kula.valem.core.engine.ArrayPathExpander.expand(p, state));
        }
        return result;
    }

    private static void record(ConstraintSpec spec, List<Violation> flagged, List<Violation> rollbacks) {
        Violation v = new Violation(spec.id(), spec.message(), spec.policy());
        if (spec.policy() == ConstraintPolicy.ROLLBACK) rollbacks.add(v);
        else flagged.add(v);
    }

    private static void recordTrace(List<DerivationTrace> traces, ConstraintSpec spec, boolean passed) {
        if (traces != null) {
            traces.add(DerivationTrace.ofConstraint(spec.id(), spec.expr(), List.of(), passed));
        }
    }
}
