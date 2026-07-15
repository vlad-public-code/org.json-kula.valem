package org.json_kula.valem.service;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.json_kula.tracked_json.json_patch.JsonPatch;
import org.json_kula.tracked_json.json_patch.JsonPatchException;
import org.json_kula.valem.core.blob.BlobStore;
import org.json_kula.valem.core.blob.NoSuchBlobException;
import org.json_kula.valem.core.engine.ConstraintEvaluator;
import org.json_kula.valem.core.engine.DerivationTrace;
import org.json_kula.valem.core.engine.ModelRuntime;
import org.json_kula.valem.core.engine.SchemaStateChecker;
import org.json_kula.valem.core.graph.CompiledModel;
import org.json_kula.valem.core.graph.ModelSpecCompiler;
import org.json_kula.valem.core.graph.ModelSpecValidator;
import org.json_kula.valem.core.graph.SpecEvolution;
import org.json_kula.valem.core.model.BlobRef;
import org.json_kula.valem.core.model.ModelSpec;
import org.json_kula.valem.core.state.ModelState;
import org.json_kula.valem.core.state.Snapshot;
import org.json_kula.valem.view.engine.EvaluatedComponent;
import org.json_kula.valem.view.engine.EvaluatedView;
import org.json_kula.valem.view.engine.ViewEvaluator;
import org.json_kula.valem.view.model.ViewDefinition;
import org.json_kula.valem.view.model.ViewSpec;

import java.io.IOException;
import java.io.InputStream;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

/**
 * Pure-Java service layer for Valem model operations.
 *
 * <p>Extracted from the Spring controllers so the same business logic can be used
 * by the REST API and the console application without duplication.
 *
 * <p>Individual {@link ModelRuntime} instances are not thread-safe; this class
 * synchronises on each runtime for all operations that touch non-thread-safe structures:
 * mutating ops (mutate, patchMutate, restore, evolveSpec) and reads that either write
 * during evaluation (getState, getFieldValue — LAZY derivations) or iterate non-concurrent
 * collections (getHistory, explain — ArrayDeque/ArrayList written under the same lock).
 *
 * <p>Each model has a bounded semaphore with {@code mutationQueueCapacity} permits.
 * A mutation request that cannot acquire a permit (queue full) immediately throws
 * {@link MutationQueueFullException} rather than blocking indefinitely.
 */
public class ModelService implements ModelOperations {

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(ModelService.class);

    // Unsupported component/view properties (e.g. LLM-hallucinated fields) are dropped rather
    // than rejecting the whole viewDefinition.
    private static final ObjectMapper VIEW_MAPPER = new ObjectMapper()
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);

    private final ModelRegistry registry;
    private final BlobStore     blobStore;
    private final int           mutationQueueCapacity;
    private final int           maxModels;

    private static final int  QUEUE_MAX_MODELS = 10_000;
    private static final long QUEUE_IDLE_TTL_H = 1;

    private final Cache<String, Semaphore> mutationQueues = Caffeine.newBuilder()
            .maximumSize(QUEUE_MAX_MODELS)
            .expireAfterAccess(QUEUE_IDLE_TTL_H, TimeUnit.HOURS)
            .build();

    public ModelService(ModelRegistry registry, BlobStore blobStore) {
        this(registry, blobStore, 10, 1000);
    }

    public ModelService(ModelRegistry registry, BlobStore blobStore, int mutationQueueCapacity) {
        this(registry, blobStore, mutationQueueCapacity, 1000);
    }

    public ModelService(ModelRegistry registry, BlobStore blobStore,
                        int mutationQueueCapacity, int maxModels) {
        this.registry              = registry;
        this.blobStore             = blobStore;
        this.mutationQueueCapacity = mutationQueueCapacity;
        this.maxModels             = maxModels;
    }

    /**
     * Returned by mutation methods; bundles the pipeline result, a consistent post-mutation
     * snapshot, and the mutations that were applied (for incremental persistence).
     *
     * <p><b>The {@code snapshot} is read-only.</b> To avoid a redundant deep copy on the mutation hot
     * path (audit CPU-4/T2.3) it aliases the very {@link Snapshot} instance retained by the model's
     * {@code ModelHistory}, so mutating its document would corrupt the point-in-time (<code>?at=</code>)
     * history record. Callers must treat it as an immutable view; deep-copy it first if they need to
     * modify it.
     */
    public record MutationOutcome(
            ModelRuntime.MutationResult result,
            Snapshot snapshot,
            Map<String, JsonNode> appliedMutations) {}

    /**
     * Hook invoked, under the model's lock, immediately after a mutation commits, so the ordered
     * mutation log cannot be written out of order under concurrency. The {@code mutatedAt}
     * timestamp is captured inside the lock too. Implementations must handle their own errors
     * (log + degrade); a thrown exception would propagate to the caller and fail the request.
     */
    @FunctionalInterface
    public interface MutationPersister {
        void persist(String modelId, Map<String, JsonNode> appliedMutations, Instant mutatedAt);
    }

    private volatile MutationPersister mutationPersister;

    /** Registers the persistence hook (or {@code null} to disable). Used by the API layer. */
    public void setMutationPersister(MutationPersister persister) {
        this.mutationPersister = persister;
    }

    /**
     * Hook invoked, under the model's lock, immediately after a mutation commits, carrying the full
     * cycle result (mutations, re-evaluated derivations, traces, flags, effects) so the shell can
     * append a durable, append-only {@code AuditRecord}. Runs in the same lock hold as
     * {@link MutationPersister}, so audit order matches commit order. Implementations must handle
     * their own errors (log + degrade); a thrown exception would fail the request.
     */
    @FunctionalInterface
    public interface AuditSink {
        void record(String modelId, String modelVersion, String source,
                    Map<String, JsonNode> mutations,
                    ModelRuntime.MutationResult result, Instant at);
    }

    private volatile AuditSink auditSink;

    /** Registers the durable-audit hook (or {@code null} to disable). Used by the API layer. */
    public void setAuditSink(AuditSink sink) {
        this.auditSink = sink;
    }

    /**
     * Appends a durable audit record under the caller-held model lock. No-op when no sink is
     * registered or the cycle produced no writes (e.g. an empty patch).
     */
    private void auditUnderLock(ModelRuntime rt, String id, String source,
                                Map<String, JsonNode> mutations,
                                ModelRuntime.MutationResult result, Instant at) {
        AuditSink s = auditSink;
        if (s != null && result != null && mutations != null && !mutations.isEmpty()) {
            s.record(id, rt.model().spec().version(), source, mutations, result, at);
        }
    }

    /**
     * Post-commit change notification for in-process consumers (e.g. a composition <b>watch</b> that
     * folds one model's value into another). Fires under the changed model's lock, so listeners MUST NOT
     * call back into any model synchronously — hand off to another thread (upholds the single-lock
     * invariant). Distinct from the WebSocket broadcast (which the controller does for client mutations
     * only); this also fires for fold-back commits.
     */
    @FunctionalInterface
    public interface ChangeListener {
        void onChange(String modelId, ModelRuntime.MutationResult result);
    }

    private final java.util.List<ChangeListener> changeListeners = new java.util.concurrent.CopyOnWriteArrayList<>();

    /** Registers a change listener; returns a handle whose {@code close()} deregisters it. */
    public AutoCloseable addChangeListener(ChangeListener listener) {
        changeListeners.add(listener);
        return () -> changeListeners.remove(listener);
    }

    private void notifyChange(String id, ModelRuntime.MutationResult result) {
        if (changeListeners.isEmpty() || result == null) return;
        for (ChangeListener l : changeListeners) {
            try { l.onChange(id, result); }
            catch (RuntimeException e) { log.warn("change listener failed for '{}': {}", id, e.toString()); }
        }
    }

    /**
     * Executes a server-effect request emitted by a model's reactive cycle. The core emits the
     * request as data (no I/O); the executor (in the api shell) performs the I/O <b>asynchronously</b>
     * and folds any response back via {@link #mutate}. It must not fold back synchronously — the sink
     * fires while the model lock is held, so an inline {@code mutate} would nest transactions.
     */
    @FunctionalInterface
    public interface EffectExecutor {
        void submit(String modelId, org.json_kula.valem.core.engine.EffectRequest request);
    }

    private volatile EffectExecutor effectExecutor;

    /** Registers the effect executor (or {@code null} to disable). Used by the API layer. */
    public void setEffectExecutor(EffectExecutor executor) {
        this.effectExecutor = executor;
    }

    /** Registers the per-model effect sink so emitted requests reach the executor with their model id. */
    private void wireEffects(String id, ModelRuntime runtime) {
        EffectExecutor ex = effectExecutor;
        if (ex != null) {
            runtime.setEffectSink(req -> ex.submit(id, req));
        }
    }

    /**
     * Re-drives any effect left {@code pending}/{@code in_flight} on the given model (e.g. a timer
     * an executor paused because no client was watching). Silently a no-op if the model does not exist
     * (or no longer does) — callers such as a WebSocket reconnect hook should not fail on a stale id.
     *
     * @return the number of effects re-driven
     */
    public int reconcileEffects(String id) {
        ModelRuntime rt = registry.find(id).orElse(null);
        if (rt == null) return 0;
        synchronized (rt) {
            return rt.reconcileEffects();
        }
    }

    /**
     * Atomically resolves and applies an effect's fold-back under the model lock (keyed compare-and-swap):
     * re-evaluates the effect against current state and, per the verdict, applies {@code appliedWrites}
     * (still current), applies {@code cancelledWrites} (trigger no longer holds), or discards and re-fires
     * for the now-current value (superseded). Doing resolve + apply + re-fire in one lock hold prevents a
     * newer input from being lost between the check and the write. Called by the shell executors.
     *
     * @return the verdict, so the executor can log/observe the outcome
     */
    public org.json_kula.valem.core.engine.EffectDispatcher.FoldbackDecision completeFoldback(
            String id, String effectId, JsonNode firedKey,
            Map<String, JsonNode> appliedWrites, Map<String, JsonNode> cancelledWrites) {
        ModelRuntime rt = requireRuntime(id);
        acquireMutationSlot(id);
        try {
            synchronized (rt) {
                var decision = rt.resolveFoldback(effectId, firedKey);
                Map<String, JsonNode> writes = switch (decision) {
                    case CURRENT    -> appliedWrites;
                    case CANCELLED  -> cancelledWrites;
                    case SUPERSEDED -> Map.of();
                };
                if (writes != null && !writes.isEmpty()) {
                    ModelRuntime.MutationResult result = rt.mutate(writes);
                    Instant now = Instant.now();
                    persistUnderLock(id, writes, now);
                    auditUnderLock(rt, id, "foldback", writes, result, now);
                    notifyChange(id, result);
                }
                if (decision == org.json_kula.valem.core.engine.EffectDispatcher.FoldbackDecision.SUPERSEDED) {
                    rt.redispatchEffect(effectId);   // trailing fire for the current value
                }
                return decision;
            }
        } finally {
            releaseMutationSlot(id);
        }
    }

    /** Persists the applied mutations under the caller-held model lock, with the in-lock timestamp. */
    private void persistUnderLock(String id, Map<String, JsonNode> mutations, Instant mutatedAt) {
        MutationPersister p = mutationPersister;
        if (p != null && mutations != null && !mutations.isEmpty()) {
            p.persist(id, mutations, mutatedAt);
        }
    }

    // ── Create ────────────────────────────────────────────────────────────────

    /**
     * Validates, compiles, and registers a new model.
     *
     * @throws ModelValidationException       if the spec fails structural validation
     * @throws ModelAlreadyExistsException    if a model with the same id is already registered
     * @throws org.json_kula.valem.core.engine.SchemaViolationException   if creation-time defaults violate the JSON schema
     * @throws ConstraintEvaluator.ConstraintViolationException if creation-time defaults trigger a ROLLBACK constraint
     */
    public void createModel(ModelSpec spec) {
        ModelSpecValidator.ValidationResult validation = ModelSpecValidator.validate(spec);
        if (!validation.isValid()) {
            throw new ModelValidationException(validation);
        }
        validateViewParses(spec);
        // Fast-path duplicate check; avoids compiling a spec we know will be rejected.
        if (registry.contains(spec.id())) {
            throw new ModelAlreadyExistsException(spec.id());
        }
        if (registry.size() >= maxModels) {
            throw new ModelLimitExceededException(maxModels);
        }

        CompiledModel model   = ModelSpecCompiler.compile(spec);
        ModelState    state   = new ModelState(model, blobStore);
        ModelRuntime  runtime = new ModelRuntime(model, state);
        wireEffects(spec.id(), runtime);

        // Register BEFORE seeding — creation-time defaults can fire effects (e.g. a $-seeded status
        // that arms a timer), and those effects fold back via mutate(), which must be able to resolve
        // this model. Atomic registration also guards two concurrent creates with the same id.
        if (!registry.registerIfAbsent(spec.id(), runtime)) {
            throw new ModelAlreadyExistsException(spec.id());
        }
        mutationQueues.put(spec.id(), new Semaphore(mutationQueueCapacity, true));
        try {
            // A creation-time default can arm an effect (e.g. a $-seeded status that starts a timer),
            // whose fold-back reaches this same runtime via a concurrently-running mutate() call (the
            // model is already registered, above). initialize()/recomputeAllDerivations() must hold the
            // same runtime lock mutate() does, or the two can interleave mid-transaction (ModelState is
            // not thread-safe) — matching every other runtime-touching method in this class.
            synchronized (runtime) {
                // Apply creation-time defaults (a defaultValues rule with path "$" seeds the root document).
                runtime.initialize();
                // A derivation whose only dependency was never seeded/mutated (e.g. an optional boolean
                // input the caller leaves at its schema default) is never visited by dirty-propagation and
                // would otherwise stay null forever. Same guarantee loadModel() already gives a cold-loaded
                // model: recompute every EAGER derivation once so GET /state is complete from the first read.
                runtime.recomputeAllDerivations();
            }
        } catch (RuntimeException e) {
            // Seeding violated the schema / a ROLLBACK constraint — undo the registration.
            registry.remove(spec.id());
            mutationQueues.invalidate(spec.id());
            throw e;
        }
    }

    /**
     * Loads a pre-compiled model directly into the registry, optionally restoring a snapshot.
     * Bypasses validation and initialState application — intended for startup restoration.
     */
    public void loadModel(ModelSpec spec, Optional<Snapshot> existingSnapshot) {
        CompiledModel model   = ModelSpecCompiler.compile(spec);
        ModelState    state   = new ModelState(model, blobStore);
        existingSnapshot.ifPresent(state::restore);
        ModelRuntime  runtime = new ModelRuntime(model, state);
        wireEffects(spec.id(), runtime);
        // A restored snapshot may carry an empty/stale derivedCache (incremental mutation-log
        // replay persists only the base document). Recompute all EAGER derivations so the first
        // GET /state returns derived fields without requiring a mutation first.
        if (existingSnapshot.isPresent()) {
            runtime.recomputeAllDerivations();
        }
        registry.register(spec.id(), runtime);
        mutationQueues.put(spec.id(), new Semaphore(mutationQueueCapacity, true));
        // Crash recovery: re-drive any effect left pending/in_flight in the restored state. Done after
        // registration so the executor's async fold-back can resolve this model. No-op without a sink.
        synchronized (runtime) {
            runtime.reconcileEffects();
        }
    }

    // ── Mutation ──────────────────────────────────────────────────────────────

    /**
     * Applies field mutations and runs the full reactive pipeline.
     *
     * @throws ModelNotFoundException        if no model with the given id is registered
     * @throws org.json_kula.valem.core.engine.SchemaViolationException   on schema violation
     * @throws ConstraintEvaluator.ConstraintViolationException on ROLLBACK constraint violation
     */
    public MutationOutcome mutate(String id, Map<String, JsonNode> mutations) {
        ModelRuntime rt = requireRuntime(id);
        acquireMutationSlot(id);
        try {
            synchronized (rt) {
                ModelRuntime.MutationResult result = rt.mutate(mutations);
                // Persist inside the lock, with an in-lock timestamp, so concurrent mutations
                // append to the ordered log in the same order they committed in memory (F-T2).
                Instant now = Instant.now();
                persistUnderLock(id, mutations, now);
                auditUnderLock(rt, id, "client", mutations, result, now);
                notifyChange(id, result);
                // Reuse the snapshot captured at commit instead of taking a second deep copy (CPU-4).
                return new MutationOutcome(result, rt.lastCommittedSnapshot(), mutations);
            }
        } finally {
            releaseMutationSlot(id);
        }
    }

    /**
     * Applies a RFC 6902 JSON Patch document as a set of field mutations.
     *
     * @throws ModelNotFoundException        if no model with the given id is registered
     * @throws IllegalArgumentException      if the patch document is structurally invalid
     * @throws org.json_kula.valem.core.engine.SchemaViolationException   on schema violation
     * @throws ConstraintEvaluator.ConstraintViolationException on ROLLBACK constraint violation
     */
    public MutationOutcome patchMutate(String id, JsonNode patchDoc) {
        ModelRuntime rt = requireRuntime(id);
        acquireMutationSlot(id);
        try {
        synchronized (rt) {
            ObjectNode baseDocCopy = rt.stateView().baseDoc().deepCopy();
            JsonNode patched;
            try {
                patched = JsonPatch.compile(patchDoc).apply(baseDocCopy);
            } catch (JsonPatchException e) {
                throw new InvalidPatchException("Invalid patch: " + e.getMessage(), e);
            }
            if (!(patched instanceof ObjectNode patchedObj)) {
                throw new InvalidPatchException("Patch must produce a JSON object at root");
            }
            Map<String, JsonNode> mutations = JsonPatchTranslator.translate(patchDoc, patchedObj);
            if (mutations.isEmpty()) {
                return new MutationOutcome(
                        new ModelRuntime.MutationResult(true, List.of(), List.of(), List.of(), List.of(), List.of(), List.of()),
                        rt.snapshot(), Map.of());
            }
            ModelRuntime.MutationResult result = rt.mutate(mutations);
            Instant now = Instant.now();
            persistUnderLock(id, mutations, now);
            auditUnderLock(rt, id, "patch", mutations, result, now);
            notifyChange(id, result);
            return new MutationOutcome(result, rt.lastCommittedSnapshot(), mutations);
        }
        } finally {
            releaseMutationSlot(id);
        }
    }

    // ── Read ──────────────────────────────────────────────────────────────────

    public List<String> listModels() {
        return registry.allIds();
    }

    /** @throws ModelNotFoundException if the model does not exist */
    public ModelSpec getSpec(String id) {
        return requireRuntime(id).model().spec();
    }

    /** @throws ModelNotFoundException if the model does not exist */
    public ModelInfo getInfo(String id) {
        ModelRuntime rt   = requireRuntime(id);
        ModelSpec    spec = rt.model().spec();
        return new ModelInfo(spec.id(), spec.version(),
                spec.derivations().size(), spec.metaDerivations().size(),
                spec.constraints().size(), spec.effects().size());
    }

    /**
     * Returns the merged state (base + all derived values, including LAZY).
     *
     * @param at optional point-in-time; pass {@code null} for the current state
     * @throws ModelNotFoundException   if the model does not exist
     * @throws HistoryNotFoundException if {@code at} is non-null and no history entry exists at or before it
     */
    public ObjectNode getState(String id, Instant at) {
        ModelRuntime rt = requireRuntime(id);
        // Both branches need the lock:
        // - at==null: fullState() writes to derivedCache/staleLazyPaths for LAZY derivations
        // - at!=null: stateAt() iterates ModelHistory.entries (ArrayDeque, not thread-safe)
        synchronized (rt) {
            if (at != null) {
                return rt.stateAt(at)
                        .map(Snapshot::mergedDocument)
                        .orElseThrow(() -> new HistoryNotFoundException(id, at));
            }
            return rt.fullState();
        }
    }

    /**
     * Returns the effective value of a single field, evaluating LAZY derivations on demand.
     *
     * @throws ModelNotFoundException    if the model does not exist
     */
    public JsonNode getFieldValue(String id, String path) {
        ModelRuntime rt = requireRuntime(id);
        // getValue() writes to derivedCache and staleLazyPaths for LAZY derivations;
        // must not race with concurrent mutations that write the same structures.
        synchronized (rt) {
            return rt.getValue(path);
        }
    }

    /** @throws ModelNotFoundException if the model does not exist */
    public List<String> getHistory(String id) {
        ModelRuntime rt = requireRuntime(id);
        // ModelHistory.entries is an ArrayDeque; record() writes under synchronized(rt),
        // so reads must also hold the lock to avoid ConcurrentModificationException.
        synchronized (rt) {
            return rt.history().timestamps().stream().map(Instant::toString).toList();
        }
    }

    /** @throws ModelNotFoundException if the model does not exist */
    public ObjectNode getEffectiveSchema(String id, String path) {
        return requireRuntime(id).effectiveSchema(path);
    }

    /** @throws ModelNotFoundException if the model does not exist */
    public List<DerivationTrace> explain(String id, String path) {
        ModelRuntime rt = requireRuntime(id);
        // traceLog is an ArrayList; appendTraces() writes under synchronized(rt),
        // so iteration here must hold the same lock to avoid ConcurrentModificationException.
        synchronized (rt) {
            return rt.explain(path);
        }
    }

    // ── Snapshot ──────────────────────────────────────────────────────────────

    /** @throws ModelNotFoundException if the model does not exist */
    public Snapshot snapshot(String id) {
        ModelRuntime rt = requireRuntime(id);
        // snapshot() calls baseDoc.deepCopy() — Jackson ObjectNode is not safe for
        // concurrent read while a mutation modifies it under synchronized(rt).
        synchronized (rt) {
            return rt.snapshot();
        }
    }

    /** @throws ModelNotFoundException if the model does not exist */
    public void restore(String id, Snapshot snapshot) {
        ModelRuntime rt = requireRuntime(id);
        acquireMutationSlot(id);
        try {
            synchronized (rt) {
                rt.validateAndRestore(snapshot);
            }
        } finally {
            releaseMutationSlot(id);
        }
    }

    // ── Spec evolution ────────────────────────────────────────────────────────

    /**
     * Incrementally evolves the spec without replacing state.
     *
     * @throws ModelNotFoundException   if the model does not exist
     * @throws IllegalArgumentException if the evolution produces an invalid spec
     */
    public ModelSpec evolveSpec(String id, SpecEvolution evolution) {
        ModelRuntime rt = requireRuntime(id);
        acquireMutationSlot(id);
        try {
            synchronized (rt) {
                ModelSpec current = rt.model().spec();

                // Optimistic-concurrency precondition: reject a stale read-edit-POST race.
                String expected = evolution.expectedVersion();
                if (expected != null && !expected.equals(current.version())) {
                    throw new SpecVersionConflictException(id, expected, current.version());
                }

                ModelSpec     evolved    = evolution.applyTo(current);
                validateViewParses(evolved);
                CompiledModel newModel   = ModelSpecCompiler.compile(evolved);
                ModelState    newState   = rt.stateView().withModel(newModel);
                // Carry compiled expressions forward so an evolution that leaves most expressions
                // unchanged does not pay the javac round-trip to recompile them.
                ModelRuntime  newRuntime = new ModelRuntime(newModel, newState, rt.expressionCache());
                wireEffects(id, newRuntime);

                // Backfill: seed values for new fields on the existing instance where they are
                // currently absent, so an additive (e.g. required) evolution does not leave nulls.
                // Existing values are never overwritten.
                Map<String, JsonNode> backfill = missingBackfill(newRuntime, evolution.backfill());
                if (!backfill.isEmpty()) {
                    newRuntime.mutate(backfill);     // validates against the new schema + derives
                }

                // A schema change must not strand existing state: reject up-front if the
                // carried-forward (post-backfill) document violates the new schema, rather
                // than committing state that fails its own schema.
                if (evolution.touchesSchema()) {
                    var incompatibilities = SchemaStateChecker.check(
                            evolved.schema(), newRuntime.stateView().baseDoc());
                    if (!incompatibilities.isEmpty()) {
                        throw new SchemaStateIncompatibleException(id, incompatibilities);
                    }
                }

                // Recompute all EAGER derivations so any derivation added by this evolution is
                // populated immediately (withModel only carries forward still-valid cached values).
                newRuntime.recomputeAllDerivations();

                registry.register(id, newRuntime);
                return evolved;
            }
        } finally {
            releaseMutationSlot(id);
        }
    }

    // ── View ──────────────────────────────────────────────────────────────────

    /**
     * Rejects a spec whose {@code viewDefinition} cannot be parsed into the typed view records,
     * so an invalid view fails at write time (422) instead of at first render (500).
     */
    private void validateViewParses(ModelSpec spec) {
        JsonNode rawView = spec.viewDefinition();
        if (rawView == null || rawView.isNull()) return;
        try {
            VIEW_MAPPER.treeToValue(rawView, ViewDefinition.class);
        } catch (Exception e) {
            var err = new ModelSpecValidator.ValidationError(
                    "viewDefinition", "Invalid viewDefinition: " + e.getMessage(),
                    ModelSpecValidator.Severity.ERROR);
            throw new ModelValidationException(
                    new ModelSpecValidator.ValidationResult(java.util.List.of(err)));
        }
    }

    /**
     * Evaluates the view definition embedded in the model spec and returns the
     * fully resolved component tree for the requested view.
     *
     * @param id     model id
     * @param viewId view id within the definition, or {@code null} for the default view
     * @throws ModelNotFoundException if the model or view definition is absent
     */
    public EvaluatedView getEvaluatedView(String id, String viewId) {
        ModelRuntime rt = requireRuntime(id);
        JsonNode rawView = rt.model().spec().viewDefinition();
        if (rawView == null || rawView.isNull()) {
            throw new ModelNotFoundException(id + " has no viewDefinition");
        }
        return getEvaluatedViewInternal(id, viewId, rt, rawView);
    }

    /**
     * The evaluated view as a JSON tree — the {@link ModelOperations} facade shape. Equal to
     * serialising {@link #getEvaluatedView(String, String)}; carried as a tree so the CLI facade is
     * mode-neutral (a remote implementation passes the server's view JSON through unchanged).
     */
    @Override
    public JsonNode getView(String id, String viewId) {
        return VIEW_MAPPER.valueToTree(getEvaluatedView(id, viewId));
    }

    private EvaluatedView getEvaluatedViewInternal(String id, String viewId, ModelRuntime rt, JsonNode rawView) {
        ViewDefinition vd;
        try {
            vd = VIEW_MAPPER.treeToValue(rawView, ViewDefinition.class);
        } catch (Exception e) {
            throw new IllegalStateException("Invalid viewDefinition in model " + id + ": " + e.getMessage(), e);
        }
        ViewSpec view = viewId == null ? findDefaultView(vd, id) : findView(vd, viewId, id);
        return ViewEvaluator.evaluate(
                id, view, rt.fullState(), rt.stateView().metaCache(),
                rt.expressionCache(), rt.model().constantsNode());
    }

    /**
     * Evaluates the given view and returns only the components (flat, across the whole tree)
     * whose {@code bind} path is in the supplied set of changed paths.
     * Returns an empty map when the model has no view definition or the view is not found.
     */
    public Map<String, EvaluatedComponent> getViewDelta(
            String id, String viewId, Set<String> changedPaths) {
        ModelRuntime rt = requireRuntime(id);
        JsonNode rawView = rt.model().spec().viewDefinition();
        if (rawView == null || rawView.isNull()) return Map.of();
        ViewDefinition vd;
        try {
            vd = VIEW_MAPPER.treeToValue(rawView, ViewDefinition.class);
        } catch (Exception e) {
            return Map.of();
        }
        ViewSpec view;
        try {
            view = viewId == null ? findDefaultView(vd, id) : findView(vd, viewId, id);
        } catch (ModelNotFoundException e) {
            return Map.of();
        }
        EvaluatedView evaluated = ViewEvaluator.evaluate(
                id, view, rt.fullState(), rt.stateView().metaCache(),
                rt.expressionCache(), rt.model().constantsNode());
        Map<String, EvaluatedComponent> delta = new HashMap<>();
        flatComponents(evaluated.components())
                .filter(c -> c.bind() != null && changedPaths.contains(c.bind()))
                .forEach(c -> delta.put(c.id(), c));
        return delta.isEmpty() ? Map.of() : Map.copyOf(delta);
    }

    private static Stream<EvaluatedComponent> flatComponents(List<EvaluatedComponent> components) {
        if (components == null) return Stream.empty();
        return components.stream().flatMap(c ->
                Stream.concat(Stream.of(c), flatComponents(c.components())));
    }

    private static ViewSpec findDefaultView(ViewDefinition vd, String modelId) {
        if (vd.views().isEmpty()) throw new ModelNotFoundException(modelId + " viewDefinition has no views");
        String defaultId = vd.defaultView();
        if (defaultId != null) {
            return vd.views().stream()
                    .filter(v -> defaultId.equals(v.id()))
                    .findFirst()
                    .orElse(vd.views().getFirst());
        }
        return vd.views().getFirst();
    }

    private static ViewSpec findView(ViewDefinition vd, String viewId, String modelId) {
        return vd.views().stream()
                .filter(v -> viewId.equals(v.id()))
                .findFirst()
                .orElseThrow(() -> new ModelNotFoundException(modelId + "/view/" + viewId));
    }

    // ── Delete ────────────────────────────────────────────────────────────────

    /** @throws ModelNotFoundException if the model does not exist */
    public void deleteModel(String id) {
        if (!registry.remove(id)) throw new ModelNotFoundException(id);
        mutationQueues.invalidate(id);
    }

    // ── Blob ──────────────────────────────────────────────────────────────────

    public BlobRef uploadBlob(InputStream data, String mediaType) throws IOException {
        return blobStore.store(data, mediaType);
    }

    /**
     * @throws NoSuchBlobException if no blob with that id exists
     */
    public InputStream downloadBlob(String blobId) throws IOException {
        if (!blobStore.exists(blobId)) throw new NoSuchBlobException(blobId);
        return blobStore.load(blobId);
    }

    /**
     * Returns a blob only if it is referenced by the named model's current state.
     *
     * @throws ModelNotFoundException      if the model does not exist
     * @throws BlobNotReferencedException  if the blob is not referenced by the model
     * @throws NoSuchBlobException         if the blob id is not in the store
     */
    public InputStream getBlobForModel(String modelId, String blobId) throws IOException {
        ModelRuntime rt = requireRuntime(modelId);
        if (!rt.stateView().containsBlob(blobId)) {
            throw new BlobNotReferencedException(modelId, blobId);
        }
        if (!blobStore.exists(blobId)) throw new NoSuchBlobException(blobId);
        return blobStore.load(blobId);
    }

    /**
     * Returns the set of blob ids referenced by any registered model — the "mark" set for blob
     * garbage collection (audit MEM-6). Includes each model's current state <b>and</b> its retained
     * history snapshots, so a blob reachable only through a point-in-time ({@code ?at=}) or
     * restore-from-history read is not swept as an orphan. Each runtime is read under its own lock so
     * the walk does not race a concurrent mutation.
     */
    public Set<String> referencedBlobIds() {
        Set<String> ids = new java.util.HashSet<>();
        for (String id : registry.allIds()) {
            ModelRuntime rt = registry.find(id).orElse(null);
            if (rt == null) continue;
            synchronized (rt) {
                rt.collectReferencedBlobIds(ids);
            }
        }
        return ids;
    }

    // ── Internal ──────────────────────────────────────────────────────────────

    public ModelRegistry registry() { return registry; }

    /** Returns the number of available mutation slots for the given model (for monitoring/testing). */
    public int availableMutationSlots(String modelId) {
        Semaphore sem = mutationQueues.getIfPresent(modelId);
        return sem == null ? mutationQueueCapacity : sem.availablePermits();
    }

    private void acquireMutationSlot(String modelId) {
        // Use get() with loader so an expired entry is transparently recreated.
        // requireRuntime() already guards against genuinely absent models.
        Semaphore sem = mutationQueues.get(modelId, k -> new Semaphore(mutationQueueCapacity, true));
        if (!sem.tryAcquire()) throw new MutationQueueFullException(modelId, mutationQueueCapacity);
    }

    private void releaseMutationSlot(String modelId) {
        Semaphore sem = mutationQueues.getIfPresent(modelId);
        if (sem != null) sem.release();
    }

    private ModelRuntime requireRuntime(String id) {
        return registry.find(id)
                .orElseThrow(() -> new ModelNotFoundException(id));
    }

    /** Returns the subset of {@code backfill} whose paths are currently absent/null in {@code rt}. */
    private static Map<String, JsonNode> missingBackfill(ModelRuntime rt, Map<String, JsonNode> backfill) {
        if (backfill == null || backfill.isEmpty()) return Map.of();
        Map<String, JsonNode> missing = new HashMap<>();
        for (Map.Entry<String, JsonNode> e : backfill.entrySet()) {
            JsonNode current = rt.getValue(e.getKey());
            if (current == null || current.isMissingNode() || current.isNull()) {
                missing.put(e.getKey(), e.getValue());
            }
        }
        return missing;
    }
}
