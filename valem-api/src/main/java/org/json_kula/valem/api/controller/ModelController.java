package org.json_kula.valem.api.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.json_kula.valem.api.dto.CreateModelResponse;
import org.json_kula.valem.api.dto.MutationResponse;
import org.json_kula.valem.api.websocket.ChangeEvent;
import org.json_kula.valem.persistence.ModelStore;
import org.json_kula.valem.persistence.audit.AuditQuery;
import org.json_kula.valem.persistence.audit.AuditRecord;
import org.json_kula.valem.persistence.audit.AuditStore;
import org.json_kula.valem.api.websocket.ValemWebSocketHandler;
import org.json_kula.valem.core.blob.BlobStore;
import org.json_kula.valem.core.engine.DerivationTrace;
import org.json_kula.valem.core.model.BlobRef;
import org.json_kula.valem.core.model.ModelSpec;
import org.json_kula.valem.core.state.Snapshot;
import org.json_kula.valem.service.ModelService;
import org.json_kula.valem.service.ModelInfo;
import org.json_kula.valem.core.graph.SpecEvolution;
import org.springframework.core.io.InputStreamResource;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import org.json_kula.valem.view.engine.EvaluatedComponent;

import jakarta.annotation.PreDestroy;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.springframework.http.HttpStatus.BAD_REQUEST;

/**
 * REST controller for Valem model lifecycle and mutation operations.
 *
 * <p>All mutation endpoints synchronize on the {@link org.json_kula.valem.core.engine.ModelRuntime}
 * instance via {@link ModelService}, which is not thread-safe internally.
 */
@RestController
@RequestMapping("/models")
public class ModelController {

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(ModelController.class);

    private final ModelService                service;
    private final ValemWebSocketHandler  wsHandler;
    private final ModelStore                  modelStore;
    private final AuditStore                   auditStore;
    private final MeterRegistry                meterRegistry;
    private final ObjectMapper                mapper;
    private final org.json_kula.valem.api.composition.CompositionValidator compositionValidator;
    private final org.json_kula.valem.api.reference.TemplateMaterializer templateMaterializer;
    private final org.json_kula.valem.api.authz.EffectApprovalRegistry effectApprovals;
    private final org.json_kula.valem.api.reference.ModelPromoter modelPromoter;
    private final org.json_kula.valem.api.reference.WatchManager watchManager;

    // WebSocket sends happen off the model lock: the ChangeListener fires while the lock is held
    // (its contract), so blocking network I/O to a slow subscriber must be handed to another thread.
    // Virtual-thread-per-task matches WatchManager's off-lock hand-off idiom.
    private final ExecutorService broadcastPool = Executors.newVirtualThreadPerTaskExecutor();

    public ModelController(ModelService service,
                           ValemWebSocketHandler wsHandler,
                           ModelStore modelStore,
                           AuditStore auditStore,
                           MeterRegistry meterRegistry,
                           ObjectMapper mapper,
                           org.json_kula.valem.api.composition.CompositionValidator compositionValidator,
                           org.json_kula.valem.api.reference.TemplateMaterializer templateMaterializer,
                           org.json_kula.valem.api.authz.EffectApprovalRegistry effectApprovals,
                           org.json_kula.valem.api.reference.ModelPromoter modelPromoter,
                           org.json_kula.valem.api.reference.WatchManager watchManager) {
        this.service       = service;
        this.wsHandler     = wsHandler;
        this.modelStore    = modelStore;
        this.auditStore    = auditStore;
        this.meterRegistry = meterRegistry;
        this.mapper        = mapper;
        this.compositionValidator = compositionValidator;
        this.templateMaterializer = templateMaterializer;
        this.effectApprovals = effectApprovals;
        this.modelPromoter = modelPromoter;
        this.watchManager = watchManager;
        // Persist the mutation patch inside the model lock (ordered log, in-lock timestamp).
        service.setMutationPersister(this::persistMutation);
        // Append the durable audit record inside the same lock hold (append-only trail).
        service.setAuditSink(this::recordAudit);
        // Broadcast every committed change over WebSocket — client mutations AND effect fold-backs
        // (ChangeListener fires for both; the old per-endpoint broadcast() calls below only covered
        // client mutations, so a subscriber never saw a timer/server/llm effect's own fold-back land).
        // The event is built under the lock (cheap) but the send is dispatched off it so a slow
        // consumer cannot stall mutations on this model.
        service.addChangeListener((id, result) -> {
            ChangeEvent event = toChangeEvent(id, result);
            broadcastPool.execute(() -> wsHandler.broadcast(id, event));
        });
        // Notify subscribers a spec (not just state) changed — registered on the service so EVERY
        // evolve entry point (this controller's evolveSpec, AiEvolveController, the streaming AI
        // evolve) broadcasts identically; a browser had no way to learn the spec changed after
        // another client's evolution. Same off-lock dispatch as mutations.
        service.addEvolveListener((id, evolved) -> {
            var specEvolved = new org.json_kula.valem.api.websocket.SpecEvolvedEvent(id, evolved.version());
            broadcastPool.execute(() -> wsHandler.broadcast(id, specEvolved));
        });
    }

    @PreDestroy
    void shutdownBroadcastPool() {
        broadcastPool.shutdownNow();
    }

    // ── Create ────────────────────────────────────────────────────────────────

    @PostMapping
    public ResponseEntity<?> createModel(@RequestBody ModelSpec spec) {
        // A branch (template.ref) is flattened + lineage-pinned into a self-contained inlined spec
        // before anything else; a plain spec passes through unchanged.
        ModelSpec effective = templateMaterializer.materialize(spec);
        compositionValidator.validate(effective, registeredSpecs());
        service.createModel(effective);
        meterRegistry.counter("valem.models.created").increment();
        persistSpec(effective.id(), effective);
        // A defaultValues rule with path "$" seeds the root document at creation; persist the
        // resulting baseline snapshot so a durable backend has state to reload.
        boolean seededAtCreation = effective.defaultValues().stream().anyMatch(r -> "$".equals(r.path()));
        if (seededAtCreation) {
            persistSnapshot(effective.id(), service.snapshot(effective.id()));
        }
        return ResponseEntity
                .created(URI.create("/models/" + effective.id()))
                .body(CreateModelResponse.created(effective.id()));
    }

    // ── Mutation ──────────────────────────────────────────────────────────────

    @PostMapping("/{id}/mutations")
    public ResponseEntity<?> mutate(
            @PathVariable("id") String id,
            @RequestBody Map<String, JsonNode> mutations,
            @RequestHeader(value = "X-View",  required = false) String viewHeader) {

        ModelService.MutationOutcome outcome = timedMutate(() -> service.mutate(id, mutations));
        Map<String, EvaluatedComponent> delta = viewDelta(id, viewHeader, outcome.result());
        return ResponseEntity.ok(MutationResponse.from(outcome.result(), delta));
    }

    @PostMapping(value = "/{id}/mutations/patch", consumes = "application/json-patch+json")
    public ResponseEntity<?> patchMutate(
            @PathVariable("id") String id,
            @RequestBody JsonNode patchDoc,
            @RequestHeader(value = "X-View",  required = false) String viewHeader) {

        ModelService.MutationOutcome outcome = timedMutate(() -> service.patchMutate(id, patchDoc));
        Map<String, EvaluatedComponent> delta = viewDelta(id, viewHeader, outcome.result());
        return ResponseEntity.ok(MutationResponse.from(outcome.result(), delta));
    }

    /**
     * Times a mutation, tagging the {@code valem.mutation.duration} timer by outcome
     * (success / flagged / rollback / schema_violation / error) and counting dispatched effects.
     * Exceptions are re-thrown for the {@code GlobalExceptionHandler} after the timer is recorded.
     */
    private ModelService.MutationOutcome timedMutate(java.util.function.Supplier<ModelService.MutationOutcome> call) {
        Timer.Sample sample = Timer.start(meterRegistry);
        String outcome = "success";
        try {
            ModelService.MutationOutcome o = call.get();
            if (o.result().hasFlags()) outcome = "flagged";
            int effects = o.result().dispatchedEffects().size();
            if (effects > 0) meterRegistry.counter("valem.effects.dispatched").increment(effects);
            return o;
        } catch (org.json_kula.valem.core.engine.ConstraintEvaluator.ConstraintViolationException e) {
            outcome = "rollback"; throw e;
        } catch (org.json_kula.valem.core.engine.SchemaViolationException e) {
            outcome = "schema_violation"; throw e;
        } catch (RuntimeException e) {
            outcome = "error"; throw e;
        } finally {
            sample.stop(meterRegistry.timer("valem.mutation.duration", "outcome", outcome));
        }
    }

    private Map<String, EvaluatedComponent> viewDelta(
            String id, String viewHeader,
            org.json_kula.valem.core.engine.ModelRuntime.MutationResult result) {
        if (viewHeader == null) return null;
        Set<String> changed = Stream.concat(
                result.mutatedPaths().stream(),
                result.derivedUpdated().stream()
        ).collect(Collectors.toUnmodifiableSet());
        return service.getViewDelta(id, viewHeader, changed);
    }

    // ── List / info ───────────────────────────────────────────────────────────

    @GetMapping
    public ResponseEntity<List<String>> listModels() {
        return ResponseEntity.ok(service.listModels());
    }

    @GetMapping("/{id}/spec")
    public ResponseEntity<ModelSpec> getSpec(@PathVariable("id") String id) {
        return ResponseEntity.ok(service.getSpec(id));
    }

    /** The pinned ancestor chain a branch was materialized from (empty for a non-branch model). */
    @GetMapping("/{id}/lineage")
    public ResponseEntity<?> getLineage(@PathVariable("id") String id) {
        return ResponseEntity.ok(service.getSpec(id).lineage());
    }

    /** Promote a local model into a web repository (local→web; one-way, closure-checked §7.1). */
    @PostMapping("/{id}/promote")
    public ResponseEntity<?> promote(@PathVariable("id") String id,
                                     @RequestBody Map<String, String> body) {
        String toRepo = body.get("toRepo");
        if (toRepo == null || toRepo.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "toRepo is required"));
        }
        modelPromoter.promote(id, toRepo);
        return ResponseEntity.ok(Map.of("id", id, "promotedTo", toRepo));
    }

    /** Inherited effects quarantined pending the owner's approval (inherited-effect approval §4.2). */
    @GetMapping("/{id}/effects/pending")
    public ResponseEntity<?> pendingEffects(@PathVariable("id") String id) {
        service.getSpec(id);   // 404 if the model is unknown
        return ResponseEntity.ok(effectApprovals.pending(id));
    }

    /** Approve a quarantined inherited effect so it may run (keyed to its current definitionHash). */
    @PostMapping("/{id}/effects/{effectId}/approve")
    public ResponseEntity<?> approveEffect(@PathVariable("id") String id,
                                           @PathVariable("effectId") String effectId) {
        service.getSpec(id);   // 404 if the model is unknown
        effectApprovals.approve(id, effectId);
        return ResponseEntity.ok(Map.of("id", id, "effectId", effectId, "approved", true));
    }

    @GetMapping("/{id}")
    public ResponseEntity<ModelInfo> getModel(@PathVariable("id") String id) {
        return ResponseEntity.ok(service.getInfo(id));
    }

    // ── Read ──────────────────────────────────────────────────────────────────

    @GetMapping("/{id}/state")
    public ResponseEntity<ObjectNode> getState(
            @PathVariable("id") String id,
            @RequestParam(required = false, name = "at") String at) {

        Instant instant = null;
        if (at != null) {
            try {
                instant = Instant.parse(at);
            } catch (DateTimeParseException e) {
                return ResponseEntity.status(BAD_REQUEST)
                        .body(null);
            }
        }
        return ResponseEntity.ok(service.getState(id, instant));
    }

    @GetMapping("/{id}/history")
    public ResponseEntity<List<String>> getHistory(@PathVariable("id") String id) {
        return ResponseEntity.ok(service.getHistory(id));
    }

    @GetMapping("/{id}/state/{path:.+}")
    public ResponseEntity<JsonNode> getFieldValue(
            @PathVariable("id") String id,
            @PathVariable("path") String path) {

        JsonNode value = service.getFieldValue(id, path);
        if (value == null || value.isMissingNode()) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(value);
    }

    @GetMapping("/{id}/blobs/{blobId:.+}")
    public ResponseEntity<InputStreamResource> getBlob(
            @PathVariable("id") String id,
            @PathVariable("blobId") String blobId) throws IOException {

        InputStream in = service.getBlobForModel(id, blobId);
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .body(new InputStreamResource(in));
    }

    @GetMapping("/{id}/schema/{path:.+}")
    public ResponseEntity<ObjectNode> effectiveSchema(
            @PathVariable("id") String id,
            @PathVariable("path") String path) {
        return ResponseEntity.ok(service.getEffectiveSchema(id, path));
    }

    @GetMapping("/{id}/explain/{path:.+}")
    public ResponseEntity<List<DerivationTrace>> explain(
            @PathVariable("id") String id,
            @PathVariable("path") String path) {
        return ResponseEntity.ok(service.explain(id, path));
    }

    /**
     * Durable, append-only audit trail for a model — the queryable superset of the bounded
     * in-memory {@code explain} ring buffer. Filters: {@code path} (prefix), {@code from}/{@code to}
     * (ISO-8601 time window), {@code limit} (default 100, newest-first). Returns an empty list when
     * no audit backend is configured. Requires the model to exist (404 otherwise).
     */
    @GetMapping("/{id}/audit")
    public ResponseEntity<?> audit(
            @PathVariable("id") String id,
            @RequestParam(value = "path",  required = false) String path,
            @RequestParam(value = "from",  required = false) String from,
            @RequestParam(value = "to",    required = false) String to,
            @RequestParam(value = "limit", required = false) Integer limit) {

        service.getInfo(id); // 404 for unknown model, consistent with the other read endpoints
        Instant fromTs;
        Instant toTs;
        try {
            fromTs = from == null ? null : Instant.parse(from);
            toTs   = to   == null ? null : Instant.parse(to);
        } catch (DateTimeParseException e) {
            return ResponseEntity.status(BAD_REQUEST)
                    .body(Map.of("error", "Invalid ISO-8601 timestamp: " + e.getParsedString()));
        }
        AuditQuery query = new AuditQuery(id, path, fromTs, toTs, limit == null ? 0 : limit);
        try {
            return ResponseEntity.ok(auditStore.query(query));
        } catch (IOException e) {
            log.warn("Audit query failed for model '{}': {}", id, e.getMessage());
            return ResponseEntity.ok(List.of());
        }
    }

    /**
     * Verifies the tamper-evident hash chain of a model's audit trail. Returns
     * {@code {valid, recordsChecked, firstBrokenSequence, detail}}; {@code valid:false} means a
     * record was altered, reordered, or deleted since it was written. Requires the model to exist.
     */
    @GetMapping("/{id}/audit/verify")
    public ResponseEntity<?> verifyAudit(@PathVariable("id") String id) {
        service.getInfo(id); // 404 for unknown model
        try {
            return ResponseEntity.ok(auditStore.verify(id));
        } catch (IOException e) {
            log.warn("Audit verify failed for model '{}': {}", id, e.getMessage());
            return ResponseEntity.internalServerError()
                    .body(Map.of("error", "Audit verification failed: " + e.getMessage()));
        }
    }

    // ── Snapshot ──────────────────────────────────────────────────────────────

    @PostMapping("/{id}/snapshot")
    public ResponseEntity<Snapshot> snapshot(@PathVariable("id") String id) {
        return ResponseEntity.ok(service.snapshot(id));
    }

    @PostMapping("/{id}/restore")
    public ResponseEntity<Void> restore(
            @PathVariable("id") String id,
            @RequestBody Snapshot snapshot) {

        service.restore(id, snapshot);
        persistSnapshot(id, snapshot);
        return ResponseEntity.noContent().build();
    }

    // ── Spec evolution ────────────────────────────────────────────────────────

    @PostMapping("/{id}/spec/evolve")
    public ResponseEntity<?> evolveSpec(
            @PathVariable("id") String id,
            @RequestBody SpecEvolution evolution) {

        // Validate the evolved topology before mutating the model (an evolve may add/remove links).
        ModelSpec evolvedPreview = evolution.applyTo(service.getSpec(id));
        compositionValidator.validate(evolvedPreview, registeredSpecs());

        ModelSpec evolved = service.evolveSpec(id, evolution);
        persistSpec(id, evolved);
        return ResponseEntity.ok(Map.of("id", id, "version", evolved.version()));
    }

    /** All currently-registered specs — the context for cross-model topology validation. */
    private List<ModelSpec> registeredSpecs() {
        List<ModelSpec> specs = new java.util.ArrayList<>();
        for (String modelId : service.listModels()) {
            try { specs.add(service.getSpec(modelId)); }
            catch (RuntimeException ignored) { /* raced deletion — skip */ }
        }
        return specs;
    }

    // ── Delete ────────────────────────────────────────────────────────────────

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deleteModel(@PathVariable("id") String id) {
        service.deleteModel(id);
        watchManager.teardownForModel(id);   // close any watch subscriptions involving this model
        try { modelStore.delete(id); } catch (IOException e) {
            log.warn("Failed to delete persisted data for model '{}': {}", id, e.getMessage());
        }
        try { auditStore.deleteAudit(id); } catch (IOException e) {
            log.warn("Failed to delete audit trail for model '{}': {}", id, e.getMessage());
        }
        return ResponseEntity.noContent().build();
    }

    // ── Persistence helpers ───────────────────────────────────────────────────

    private void persistSpec(String modelId, ModelSpec spec) {
        try { modelStore.saveSpec(modelId, spec); }
        catch (IOException e) {
            log.warn("Failed to persist spec for model '{}': {}", modelId, e.getMessage());
        }
    }

    private void persistSnapshot(String modelId, Snapshot snapshot) {
        try { modelStore.saveSnapshot(modelId, snapshot); }
        catch (IOException e) {
            log.warn("Failed to persist snapshot for model '{}': {}", modelId, e.getMessage());
        }
    }

    /**
     * Incremental persistence hook — registered on {@link ModelService} and invoked inside the
     * model lock immediately after a mutation commits, with the in-lock {@code mutatedAt} timestamp.
     * Errors are logged and swallowed so a persistence hiccup does not fail the mutation request.
     */
    private void persistMutation(String modelId, Map<String, JsonNode> mutations, Instant mutatedAt) {
        if (mutations == null || mutations.isEmpty()) return;
        try {
            modelStore.applyMutationPatch(modelId, toRfc6902Patch(mutations), mutatedAt);
        } catch (IOException e) {
            log.warn("Failed to persist mutation for model '{}': {}", modelId, e.getMessage());
        }
    }

    /**
     * Durable-audit hook — registered on {@link ModelService} and invoked inside the model lock
     * after a mutation commits. Builds an {@link AuditRecord} from the full cycle result and appends
     * it to the append-only {@link AuditStore}. No-op (fast return) when audit is disabled. Errors are
     * logged and swallowed so an audit hiccup never fails the mutation request.
     */
    private void recordAudit(String modelId, String modelVersion, String source,
                             Map<String, JsonNode> mutations,
                             org.json_kula.valem.core.engine.ModelRuntime.MutationResult result,
                             Instant at) {
        if (!auditStore.isEnabled()) return;
        try {
            List<String> flagged = result.flaggedConstraints().stream()
                    .map(org.json_kula.valem.core.engine.ConstraintEvaluator.Violation::constraintId)
                    .toList();
            List<String> effects = result.dispatchedEffects().stream()
                    .map(org.json_kula.valem.core.engine.EffectRequest::effectId)
                    .toList();
            auditStore.append(AuditRecord.of(
                    modelId, at, modelVersion, source, mutations,
                    result.derivedUpdated(), flagged, effects, result.traces()));
            meterRegistry.counter("valem.audit.records", "source", source).increment();
        } catch (IOException e) {
            log.warn("Failed to append audit record for model '{}': {}", modelId, e.getMessage());
        }
    }

    /**
     * Converts a Valem mutations map (dot-notation keys → values) into an RFC 6902
     * JSON Patch array of "add" operations.
     * RFC 6902 "add" on an existing member replaces it, so "add" covers both create and update.
     * Null values are converted to "remove" operations.
     */
    private ArrayNode toRfc6902Patch(Map<String, JsonNode> mutations) {
        ArrayNode patch = mapper.createArrayNode();
        for (Map.Entry<String, JsonNode> entry : mutations.entrySet()) {
            ObjectNode op = mapper.createObjectNode();
            String pointer = dotToPointer(entry.getKey());
            if (entry.getValue() == null || entry.getValue().isNull()) {
                op.put("op",   "remove");
                op.put("path", pointer);
            } else {
                op.put("op",   "add");
                op.put("path", pointer);
                op.set("value", entry.getValue());
            }
            patch.add(op);
        }
        return patch;
    }

    private static String dotToPointer(String dotPath) {
        String p = dotPath.startsWith("$.") ? dotPath.substring(2) : dotPath;
        return "/" + p.replace("~", "~0").replace("/", "~1").replace(".", "/");
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private static ChangeEvent toChangeEvent(String id, org.json_kula.valem.core.engine.ModelRuntime.MutationResult r) {
        return new ChangeEvent(id, r.mutatedPaths(), r.derivedUpdated(), r.flaggedConstraints(),
                org.json_kula.valem.api.dto.DispatchedEffect.callerEffects(r));
    }
}
