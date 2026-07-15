package org.json_kula.valem.core.engine;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.json_kula.valem.core.graph.CompiledModel;
import org.json_kula.valem.core.model.DerivationSpec;
import org.json_kula.valem.core.model.EvaluationMode;
import org.json_kula.valem.core.state.DirtyPropagator;
import org.json_kula.valem.core.state.ModelState;
import org.json_kula.valem.core.state.Snapshot;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Orchestrates the full reactive evaluation lifecycle for a single model instance.
 *
 * <p>Use {@link #mutate} to apply field changes and let the runtime handle:
 * <ol>
 *   <li>Begin a transaction (pre-mutation snapshot).</li>
 *   <li>Apply the field writes to the base document.</li>
 *   <li>Compute the dirty set (BFS through the dependency graph).</li>
 *   <li>Re-evaluate dirty derived fields (topological order).</li>
 *   <li>Re-evaluate dirty meta-derivation nodes.</li>
 *   <li>Evaluate dirty constraint nodes.</li>
 *   <li>Commit on success; rollback on ROLLBACK constraint violation.</li>
 * </ol>
 *
 * <p>Instances are <b>not thread-safe</b>. Wrap in a lock if shared across threads.
 */
public final class ModelRuntime {

    private final CompiledModel model;
    private final ModelState    state;
    private final ExpressionCache          cache;
    private final DerivationEvaluator      derivationEval;
    private final MetaDerivationEvaluator  metaEval;
    private final ConstraintEvaluator      constraintEval;
    private final EffectDispatcher         effectDispatcher;
    private final DefaultValueApplier      defaultApplier;

    // Optional sink to receive emitted effect requests (executed post-commit by the shell)
    private EffectDispatcher.EffectSink effectSink;

    // Trace ring-buffer — last N traces for explain API (oldest discarded when full).
    // ArrayDeque so the overflow trim is O(1) rather than an O(n) array shift (audit CPU-5).
    private static final int MAX_TRACES = 500;
    private final java.util.ArrayDeque<DerivationTrace> traceLog = new java.util.ArrayDeque<>();

    // Temporal history — snapshot after each successful mutation
    private final ModelHistory history = new ModelHistory();

    // The immutable snapshot captured at the last successful commit, reused by the service layer
    // instead of taking a second identical deep copy (audit CPU-4). Null before the first commit.
    private Snapshot lastCommittedSnapshot;

    public ModelRuntime(CompiledModel model, ModelState state) {
        this(model, state, null);
    }

    /**
     * Constructs a runtime whose expression cache is pre-seeded from {@code seedCache} (may be
     * {@code null}). Used on spec evolution to carry compiled expressions forward, so unchanged
     * expressions are not recompiled by the fresh runtime.
     */
    public ModelRuntime(CompiledModel model, ModelState state, ExpressionCache seedCache) {
        this.model           = model;
        this.state           = state;
        this.cache           = new ExpressionCache();
        this.cache.seedFrom(seedCache);
        this.derivationEval  = new DerivationEvaluator(cache);
        this.metaEval        = new MetaDerivationEvaluator(cache);
        this.constraintEval  = new ConstraintEvaluator(cache);
        this.effectDispatcher = new EffectDispatcher(cache);
        this.defaultApplier  = new DefaultValueApplier(cache);
    }

    /** Registers an {@link EffectDispatcher.EffectSink} to receive emitted effect requests. */
    public void setEffectSink(EffectDispatcher.EffectSink sink) { this.effectSink = sink; }

    /**
     * Re-drives any server effect left {@code pending}/{@code in_flight} in the current state (e.g. a
     * request that was mid-flight when the process stopped), submitting each to the effect sink for a
     * fresh attempt. No-op when no sink is registered. Returns the number of effects re-driven.
     *
     * <p>Read-only with respect to the base document; the caller must hold the runtime lock.
     */
    public int reconcileEffects() {
        if (effectSink == null) return 0;
        List<EffectRequest> stuck = effectDispatcher.reconcile(model, state, state.mergedDocument());
        for (EffectRequest er : stuck) {
            effectSink.submit(er);
        }
        return stuck.size();
    }

    /**
     * Re-evaluates an in-flight effect against current state to decide whether its fold-back still
     * applies (the input may have changed, or the trigger may no longer hold). Read-only; the caller
     * must hold the runtime lock.
     */
    public EffectDispatcher.FoldbackDecision resolveFoldback(String effectId, JsonNode firedKey) {
        return effectDispatcher.resolveFoldback(model, state, effectId, firedKey);
    }

    /**
     * Re-fires a superseded effect for its now-current value (trailing debounce), submitting a fresh
     * request to the effect sink. No-op when no sink is registered or the effect can no longer fire.
     * The caller must hold the runtime lock.
     */
    public void redispatchEffect(String effectId) {
        if (effectSink == null) return;
        EffectRequest req = effectDispatcher.buildFor(model, state, effectId);
        if (req != null) effectSink.submit(req);
    }

    // ── Mutation ──────────────────────────────────────────────────────────────

    /**
     * Applies the given field mutations and runs the full reactive pipeline.
     *
     * @param mutations  map of JsonPath expression → new value
     * @return the result of this mutation cycle
     * @throws ConstraintEvaluator.ConstraintViolationException on ROLLBACK violation
     *         (state is automatically restored to pre-mutation values)
     */
    public MutationResult mutate(Map<String, JsonNode> mutations) {
        // Pre-validate all paths against their effective schema before opening a transaction.
        // Collect every violation so the caller gets a complete error picture in one shot.
        List<SchemaViolationException.Violation> schemaViolations = new ArrayList<>();
        for (Map.Entry<String, JsonNode> e : mutations.entrySet()) {
            ObjectNode schema = EffectiveSchemaBuilder.build(model, state, e.getKey());
            schemaViolations.addAll(SchemaValidator.validate(schema, e.getKey(), e.getValue()));
        }
        if (!schemaViolations.isEmpty()) {
            throw new SchemaViolationException(schemaViolations);
        }

        // Which container paths (array elements / objects) are first created by these writes?
        // Captured against the pre-write document so default-value rules fire only for new items.
        Set<String> newContainers = computeNewContainers(mutations.keySet());

        List<DerivationTrace> cycleTraces = new ArrayList<>();

        state.beginTransaction();
        try {
            // 1. Apply writes
            for (Map.Entry<String, JsonNode> e : mutations.entrySet()) {
                state.setValue(e.getKey(), e.getValue());
            }

            // 1.5 Fill default values for newly-created containers (before propagation, so derived
            //     fields see the defaults in this same cycle). Returns the paths it wrote.
            List<String> defaulted = defaultApplier.apply(model, state, newContainers);

            List<String> mutatedPaths = new ArrayList<>(mutations.keySet());
            mutatedPaths.addAll(defaulted);

            return runReactiveCycle(mutatedPaths, cycleTraces);

        } catch (ConstraintEvaluator.ConstraintViolationException cve) {
            state.rollback();
            appendTraces(cycleTraces);
            throw cve;
        } catch (RuntimeException e) {
            // A rejected write (e.g. an over-limit array index) or any other failure inside the
            // transaction must not leave a partially-applied, un-rolled-back document (audit SEC-1).
            // Guard on inTransaction() so a post-commit failure does not mask itself.
            if (state.inTransaction()) state.rollback();
            throw e;
        }
    }

    /** Convenience overload for mutating a single field. */
    public MutationResult mutate(String path, JsonNode value) {
        return mutate(Map.of(path, value));
    }

    /**
     * Applies creation-time default rules and runs the reactive pipeline once, seeding a freshly
     * created model. A {@link org.json_kula.valem.core.model.DefaultValueSpec} rule whose path
     * is the document root {@code $} fires here (the root is a newly-created container at creation),
     * replacing the former {@code initialState} seed map. A no-op when no rule targets {@code $}.
     */
    public MutationResult initialize() {
        Set<String> rootContainer = java.util.Set.of("$");
        List<DerivationTrace> cycleTraces = new ArrayList<>();

        state.beginTransaction();
        try {
            List<String> defaulted = defaultApplier.apply(model, state, rootContainer);
            if (defaulted.isEmpty()) {
                // Nothing seeded — close the transaction without recording history.
                state.commit();
                state.clearDirty();
                return new MutationResult(true, List.of(), List.of(), List.of(),
                        List.of(), List.of(), List.of());
            }
            return runReactiveCycle(defaulted, cycleTraces);
        } catch (ConstraintEvaluator.ConstraintViolationException cve) {
            state.rollback();
            appendTraces(cycleTraces);
            throw cve;
        } catch (RuntimeException e) {
            if (state.inTransaction()) state.rollback();
            throw e;
        }
    }

    /**
     * Runs the reactive tail (steps 2-8) against the writes already applied within the open
     * transaction, committing on success. {@code mutatedPaths} is the set of base paths written
     * (client mutations plus any applied defaults), reported back in the result.
     */
    private MutationResult runReactiveCycle(List<String> mutatedPaths, List<DerivationTrace> cycleTraces) {
        // 2. Compute full dirty set
        Set<String> dirty = DirtyPropagator.propagate(model.graph(), state.dirtyPaths());
        dirty = new java.util.HashSet<>(dirty);
        dirty.addAll(state.dirtyPaths()); // include the base fields themselves

        // 3. Re-evaluate derived fields. The evaluator maintains a single merged document
        //    incrementally across topological levels and hands it back here, so the whole
        //    cycle performs at most one full deep copy (B-T1). It is null only when no
        //    derivations ran, in which case we materialize one for the phases below.
        DerivationEvaluator.EvaluationOutcome outcome =
                derivationEval.evaluateAndMerge(model, state, dirty, cycleTraces);
        List<String> derivedUpdated = outcome.evaluated();
        ObjectNode merged = outcome.merged() != null ? outcome.merged() : state.mergedDocument();

        // 4. Re-evaluate meta-derivation nodes (reusing the post-derivation merged document).
        List<String> metaUpdated = metaEval.evaluate(model, state, dirty, merged);

        // 5. Evaluate constraints (may throw)
        List<ConstraintEvaluator.Violation> flagged =
                constraintEval.evaluate(model, state, dirty, cycleTraces, merged);

        // 6. Evaluate effect triggers (emit request data only — no I/O in the core)
        List<EffectRequest> effects =
                effectDispatcher.evaluate(model, state, dirty, merged);

        // 7. Commit
        state.commit();
        state.clearDirty();
        appendTraces(cycleTraces);
        // Capture the post-commit snapshot exactly once and reuse it for both history and the
        // service-layer MutationOutcome (audit CPU-4) — the two were byte-identical deep copies.
        Snapshot committed = state.snapshot();
        this.lastCommittedSnapshot = committed;
        history.record(Instant.now(), committed);

        // 8. Fire the effect sink (post-commit, so state is settled). Effects are executed
        //    asynchronously by the shell, which folds any response back as a subsequent mutation.
        if (effectSink != null) {
            for (EffectRequest er : effects) {
                effectSink.submit(er);
            }
        }

        return new MutationResult(
                true, List.copyOf(mutatedPaths),
                derivedUpdated, metaUpdated, flagged, effects, List.copyOf(cycleTraces));
    }

    /**
     * Returns the set of container paths (array elements and objects) that are absent in the current
     * (pre-write) base document but will be created by writing {@code mutationKeys}. Only proper
     * container ancestors are considered — the pure-array node itself (e.g. {@code $.items} before an
     * index) is skipped; the container is the element or object. Empty when the model declares no
     * default-value rules (nothing to fire).
     */
    private Set<String> computeNewContainers(Set<String> mutationKeys) {
        if (model.spec().defaultValues().isEmpty()) return Set.of();
        Set<String> newContainers = new java.util.LinkedHashSet<>();
        for (String key : mutationKeys) {
            List<String> segs = org.json_kula.valem.core.state.PathConverter.toSegments(key);
            // Walk every proper prefix (exclude the leaf being written at segs.size()-1).
            for (int i = 0; i < segs.size() - 1; i++) {
                boolean isArrayNode = !isNumeric(segs.get(i)) && isNumeric(segs.get(i + 1));
                if (isArrayNode) continue; // the array holding the element, not a container itself
                String candidate = org.json_kula.valem.core.state.PathConverter
                        .toCanonicalAddress(String.join(".", segs.subList(0, i + 1)));
                if (!state.existsInBase(candidate)) {
                    newContainers.add(candidate);
                }
            }
        }
        return newContainers;
    }

    private static boolean isNumeric(String s) {
        if (s == null || s.isEmpty()) return false;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c < '0' || c > '9') return false;
        }
        return true;
    }

    // ── Read ──────────────────────────────────────────────────────────────────

    /**
     * Returns the effective value of a field, evaluating LAZY derivations on demand.
     *
     * <p>If the field is a LAZY derivation that is stale (inputs changed since last
     * evaluation) or has never been evaluated, the expression is computed now, cached,
     * and the result returned. Subsequent reads return the cached value until the field
     * goes stale again.
     *
     * <p>Note: for LAZY derivations this method writes to the derived cache. It is not
     * safe to call concurrently with {@link #mutate} without external synchronisation.
     */
    public JsonNode getValue(String jsonPath) {
        DerivationSpec spec = model.derivationFor(jsonPath);
        if (spec != null && spec.evaluation() == EvaluationMode.LAZY) {
            boolean stale        = state.isLazyStale(jsonPath);
            boolean neverCached  = state.getDerived(jsonPath) == null;
            if (stale || neverCached) {
                JsonNode result = derivationEval.evalExpression(spec.expr(), state, EvalBindings.forModel(model));
                state.setDerived(jsonPath, result);
                state.clearLazyStale(jsonPath);
            }
        }
        return state.getValue(jsonPath);
    }

    /**
     * Returns a complete merged state document (base + all derived values) after first
     * evaluating every stale or uncached LAZY derivation.
     *
     * <p>Use this instead of {@code stateView().mergedDocument()} when the model may
     * contain LAZY derivations whose values should reflect the current base state.
     */
    public ObjectNode fullState() {
        // Materialize the merged document once, evaluate every stale LAZY derivation against it, and
        // splice each result back in, so a read costs a single deep copy rather than one per LAZY
        // derivation (audit CPU-3). A later-listed LAZY derivation still observes an earlier one's
        // fresh value through the shared merged document, matching the previous per-call semantics.
        ObjectNode merged = state.mergedDocument();
        var bindings = EvalBindings.forModel(model);
        for (DerivationSpec d : model.spec().derivations()) {
            if (d.evaluation() == EvaluationMode.LAZY) {
                boolean stale       = state.isLazyStale(d.path());
                boolean neverCached = state.getDerived(d.path()) == null;
                if (stale || neverCached) {
                    JsonNode result = derivationEval.evalExpressionAgainst(d.expr(), merged, bindings);
                    state.setDerived(d.path(), result);
                    state.clearLazyStale(d.path());
                    ModelState.spliceDerived(merged, d.path(), result);
                }
            }
        }
        return merged;
    }

    /** Returns the effective schema for a field, overlaid with live meta values. */
    public ObjectNode effectiveSchema(String jsonPath) {
        return EffectiveSchemaBuilder.build(model, state, jsonPath);
    }

    /** Returns all trace entries whose target path starts with {@code jsonPath}. */
    public List<DerivationTrace> explain(String jsonPath) {
        String prefix = jsonPath;
        return traceLog.stream()
                .filter(t -> t.targetPath() != null && t.targetPath().startsWith(prefix))
                .toList();
    }

    /** Returns a point-in-time snapshot of the current state. */
    public Snapshot snapshot() { return state.snapshot(); }

    /**
     * Collects every {@link org.json_kula.valem.core.model.BlobRef} id this model still depends
     * on — the current state <b>and</b> every retained history snapshot — into {@code out}. History is
     * included so a blob reachable only through a point-in-time ({@code ?at=}) or restore-from-history
     * read is not classified as an orphan and reclaimed by blob GC (audit MEM-6). Callers hold the
     * runtime lock.
     */
    public void collectReferencedBlobIds(java.util.Set<String> out) {
        state.collectBlobIds(out);
        for (Snapshot s : history.snapshots()) {
            org.json_kula.valem.core.state.ModelState.collectBlobIds(s.baseDoc(), out);
        }
    }

    /**
     * Returns the snapshot captured at the most recent successful commit, or {@code null} if no
     * mutation has committed yet. Reused by the service layer to avoid a redundant deep copy per
     * mutation (audit CPU-4); callers must hold the runtime lock.
     *
     * <p><b>Read-only:</b> this is the same {@link Snapshot} instance held by {@link #history}, so
     * mutating its document would corrupt the corresponding point-in-time history entry. Deep-copy it
     * before modifying.
     */
    public Snapshot lastCommittedSnapshot() { return lastCommittedSnapshot; }

    /** Restores state from a snapshot (clears derived/meta caches, trace log, and history). */
    public void restore(Snapshot snap) {
        state.restore(snap);
        traceLog.clear();
        history.clear();
        lastCommittedSnapshot = null;
    }

    /**
     * Recomputes every EAGER derivation and meta-derivation from the current base document,
     * regardless of dirty state. Used after a cold load whose {@code derivedCache} is empty or
     * stale (e.g. snapshot restored from an incremental mutation log), so that {@code GET /state}
     * returns derived fields before any mutation occurs. Does not evaluate constraints or fire
     * actions, and does not record history — it only repopulates derived/meta caches.
     */
    public void recomputeAllDerivations() {
        // Treat every graph node as dirty so the evaluators process all EAGER derivations and
        // meta-derivations in topological level order.
        Set<String> allNodes = model.graph().evaluationLevels().stream()
                .flatMap(List::stream)
                .collect(java.util.stream.Collectors.toSet());
        if (allNodes.isEmpty()) return;

        state.beginTransaction();
        try {
            DerivationEvaluator.EvaluationOutcome outcome =
                    derivationEval.evaluateAndMerge(model, state, allNodes, null);
            ObjectNode merged = outcome.merged() != null ? outcome.merged() : state.mergedDocument();
            metaEval.evaluate(model, state, allNodes, merged);
            state.commit();
            state.clearDirty();
        } catch (RuntimeException e) {
            try {
                state.rollback();
            } catch (RuntimeException rollbackFailure) {
                e.addSuppressed(rollbackFailure);
            }
            throw e;
        }
    }

    /**
     * Validates the snapshot's base document against the model schema, then restores.
     * Throws {@link SchemaViolationException} (HTTP 422) if any field violates its schema.
     */
    public void validateAndRestore(Snapshot snap) {
        Map<String, JsonNode> flattened = new HashMap<>();
        flattenDoc(snap.baseDoc(), "$", flattened);
        List<SchemaViolationException.Violation> violations = new ArrayList<>();
        for (Map.Entry<String, JsonNode> e : flattened.entrySet()) {
            ObjectNode schema = EffectiveSchemaBuilder.build(model, state, e.getKey());
            violations.addAll(SchemaValidator.validate(schema, e.getKey(), e.getValue()));
        }
        if (!violations.isEmpty()) throw new SchemaViolationException(violations);
        restore(snap);
    }

    private static void flattenDoc(JsonNode node, String prefix, Map<String, JsonNode> out) {
        if (node == null || node.isNull() || node.isMissingNode() || node.isValueNode()) {
            out.put(prefix, node);
        } else if (node.isObject()) {
            if (!node.isEmpty()) {
                node.fields().forEachRemaining(e ->
                        flattenDoc(e.getValue(), prefix + "." + e.getKey(), out));
            }
        } else if (node.isArray()) {
            for (int i = 0; i < node.size(); i++) {
                flattenDoc(node.get(i), prefix + "." + i, out);
            }
        }
    }

    /** Returns the mutation history ring-buffer (read-only view of entries). */
    public ModelHistory history() { return history; }

    /**
     * Returns the settled state at or before the given instant, or empty if no history
     * entry exists for that time.
     */
    public Optional<Snapshot> stateAt(Instant time) { return history.findAt(time); }

    public CompiledModel   model()           { return model; }
    public ModelState      stateView()       { return state; }
    public ExpressionCache expressionCache() { return cache; }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private void appendTraces(List<DerivationTrace> traces) {
        traceLog.addAll(traces);
        // Trim to ring-buffer size
        while (traceLog.size() > MAX_TRACES) traceLog.removeFirst();
    }

    // ── Result type ───────────────────────────────────────────────────────────

    /**
     * Summary of a completed mutation cycle.
     *
     * @param success             {@code true} if the cycle committed cleanly
     * @param mutatedPaths        base fields that were written
     * @param derivedUpdated      derived field paths that were re-evaluated
     * @param metaUpdated         meta node keys that were re-evaluated
     * @param flaggedConstraints  violations from FLAG policy constraints
     * @param dispatchedEffects   effect requests whose triggers fired in this cycle (executed post-commit)
     * @param traces              derivation and constraint traces for this cycle
     */
    public record MutationResult(
            boolean success,
            List<String> mutatedPaths,
            List<String> derivedUpdated,
            List<String> metaUpdated,
            List<ConstraintEvaluator.Violation> flaggedConstraints,
            List<EffectRequest> dispatchedEffects,
            List<DerivationTrace> traces
    ) {
        public boolean hasFlags()   { return !flaggedConstraints.isEmpty(); }
        public boolean hasEffects() { return !dispatchedEffects.isEmpty(); }
    }
}
