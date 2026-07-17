package org.json_kula.valem.cli;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.json_kula.valem.client.ValemClient;
import org.json_kula.valem.client.ValemException;
import org.json_kula.valem.client.ValemTypes;
import org.json_kula.valem.core.engine.ConstraintEvaluator;
import org.json_kula.valem.core.engine.DerivationTrace;
import org.json_kula.valem.core.engine.EffectRequest;
import org.json_kula.valem.core.engine.ModelRuntime;
import org.json_kula.valem.core.graph.SpecEvolution;
import org.json_kula.valem.core.model.BlobRef;
import org.json_kula.valem.core.model.ConstraintPolicy;
import org.json_kula.valem.core.model.ModelSpec;
import org.json_kula.valem.core.state.Snapshot;
import org.json_kula.valem.service.ModelInfo;
import org.json_kula.valem.service.ModelOperations;
import org.json_kula.valem.service.ModelService;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * A {@link ModelOperations} implementation that drives a remote {@code valem-web} server over
 * the typed Java SDK ({@link ValemClient}). Selected when a CLI is launched with {@code --url}.
 *
 * <p>Model operations become REST/WS calls; the server owns the durable, shared state. Only the
 * operations that map onto a running server live here — the pure authoring/verify tools
 * ({@code validate_spec}, {@code eval_expression}, {@code test_spec}, {@code dry_run}) stay local in
 * both modes and never reach this class.
 *
 * <p><b>Error parity.</b> A non-2xx response ({@link ValemException}) is translated back into the
 * shape the embedded engine would have thrown: a ROLLBACK 409 becomes a typed
 * {@link ConstraintEvaluator.ConstraintViolationException} (so MCP surfaces its structured violation
 * list identically), and every other failure becomes a {@link RemoteOperationException} carrying the
 * server's message verbatim — so a caller cannot tell the modes apart by error format.
 *
 * <p><b>Documented differences</b> (per deliverables-and-packaging §7.4): {@code listModels} is
 * server-wide, models are durable, {@code createModel} can collide (real 409), and calls carry the
 * server API key. {@code metaUpdated} is not carried on the mutation wire response, so a remote
 * mutation outcome reports an empty {@code metaUpdated} list (the meta values are still applied
 * server-side).
 */
public final class RemoteModelOperations implements ModelOperations,
        org.json_kula.valem.service.ChangeSubscribable {

    private final ValemClient client;
    private final ObjectMapper     mapper;

    public RemoteModelOperations(ValemClient client, ObjectMapper mapper) {
        this.client = client;
        this.mapper = mapper;
    }

    // ── Create ──────────────────────────────────────────────────────────────────

    @Override
    public void createModel(ModelSpec spec) {
        JsonNode specNode = mapper.valueToTree(spec);
        call(() -> client.createModel(specNode));
    }

    // ── Mutation ────────────────────────────────────────────────────────────────

    @Override
    public ModelService.MutationOutcome mutate(String id, Map<String, JsonNode> mutations) {
        return outcome(call(() -> client.mutate(id, mutations)));
    }

    @Override
    public ModelService.MutationOutcome patchMutate(String id, JsonNode patchDoc) {
        return outcome(call(() -> client.patch(id, patchDoc)));
    }

    // ── Read ────────────────────────────────────────────────────────────────────

    @Override
    public List<String> listModels() {
        return call(client::listModels);
    }

    @Override
    public ModelSpec getSpec(String id) {
        return treeTo(call(() -> client.getSpec(id)), ModelSpec.class);
    }

    @Override
    public ModelInfo getInfo(String id) {
        ValemTypes.ModelInfo m = call(() -> client.getModel(id));
        return new ModelInfo(m.id(), m.version(), m.derivationCount(),
                m.metaDerivationCount(), m.constraintCount(), m.effectCount());
    }

    @Override
    public ObjectNode getState(String id, Instant at) {
        return asObject(call(() -> client.getState(id, at)));
    }

    @Override
    public JsonNode getFieldValue(String id, String path) {
        return call(() -> client.getField(id, path));
    }

    @Override
    public List<String> getHistory(String id) {
        return call(() -> client.history(id));
    }

    @Override
    public ObjectNode getEffectiveSchema(String id, String path) {
        return asObject(call(() -> client.effectiveSchema(id, path)));
    }

    @Override
    public List<DerivationTrace> explain(String id, String path) {
        return call(() -> client.explain(id, path)).stream()
                .map(RemoteModelOperations::toCoreTrace)
                .toList();
    }

    @Override
    public JsonNode getAudit(String id, String pathPrefix, Instant from, Instant to, int limit) {
        ValemTypes.AuditQuery query = new ValemTypes.AuditQuery(
                blankToNull(pathPrefix), from, to, limit > 0 ? limit : null);
        return mapper.valueToTree(call(() -> client.audit(id, query)));
    }

    @Override
    public JsonNode verifyAudit(String id) {
        return mapper.valueToTree(call(() -> client.verifyAudit(id)));
    }

    // ── Change streaming (ChangeSubscribable) ────────────────────────────────────

    @Override
    public AutoCloseable subscribeChanges(String modelId, java.util.function.Consumer<String> onChange) {
        // Reconnecting WebSocket subscription; each ChangeEvent notifies with the changed model id.
        org.json_kula.valem.client.Subscription sub =
                client.subscribe(modelId, event -> onChange.accept(event.modelId()));
        return sub::close;
    }

    private static String blankToNull(String s) {
        return (s == null || s.isBlank()) ? null : s;
    }

    // ── Snapshot ──────────────────────────────────────────────────────────────

    @Override
    public Snapshot snapshot(String id) {
        return treeTo(call(() -> client.snapshot(id)), Snapshot.class);
    }

    @Override
    public void restore(String id, Snapshot snapshot) {
        JsonNode snapNode = mapper.valueToTree(snapshot);
        call(() -> { client.restore(id, snapNode); return null; });
    }

    // ── Spec evolution ────────────────────────────────────────────────────────

    @Override
    public ModelSpec evolveSpec(String id, SpecEvolution evolution) {
        JsonNode evolutionNode = mapper.valueToTree(evolution);
        return treeTo(call(() -> client.evolveSpec(id, evolutionNode)), ModelSpec.class);
    }

    // ── View ──────────────────────────────────────────────────────────────────

    @Override
    public JsonNode getView(String id, String viewId) {
        return call(() -> client.getView(id, viewId));
    }

    // ── Delete ────────────────────────────────────────────────────────────────

    @Override
    public void deleteModel(String id) {
        call(() -> { client.deleteModel(id); return null; });
    }

    // ── Blob ────────────────────────────────────────────────────────────────────

    @Override
    public BlobRef uploadBlob(InputStream data, String mediaType) throws IOException {
        byte[] bytes = data.readAllBytes();
        return BlobRef.fromJsonNode(call(() -> client.uploadBlob(bytes, mediaType)));
    }

    @Override
    public InputStream downloadBlob(String blobId) throws IOException {
        return new ByteArrayInputStream(call(() -> client.downloadBlob(blobId)));
    }

    @Override
    public InputStream getBlobForModel(String modelId, String blobId) throws IOException {
        return new ByteArrayInputStream(call(() -> client.getModelBlob(modelId, blobId)));
    }

    // ── Translation helpers ──────────────────────────────────────────────────────

    /** Runs a SDK call, translating any {@link ValemException} into the embedded-mode error shape. */
    private <T> T call(Supplier<T> op) {
        try {
            return op.get();
        } catch (ValemException e) {
            throw mapError(e);
        }
    }

    private RuntimeException mapError(ValemException e) {
        JsonNode body = tryParse(e.body());
        // A ROLLBACK constraint 409 is returned as {"error":"Constraint violation","violations":[...]}.
        // Rebuild the typed exception so the MCP tool surface structures it exactly as embedded mode does.
        if (body != null && body.has("violations")
                && "Constraint violation".equals(body.path("error").asText(null))) {
            try {
                ConstraintEvaluator.Violation[] violations =
                        mapper.treeToValue(body.get("violations"), ConstraintEvaluator.Violation[].class);
                return ConstraintEvaluator.ConstraintViolationException.of(List.of(violations));
            } catch (Exception ignore) {
                // Fall through to a plain message if the violations payload is unexpected.
            }
        }
        return new RemoteOperationException(e.status(), extractMessage(body, e.body()));
    }

    /** Extracts a human-readable message from a Problem Detail ({@code detail}) or plain error body. */
    private static String extractMessage(JsonNode body, String rawBody) {
        if (body != null) {
            if (body.hasNonNull("detail"))  return body.get("detail").asText();
            if (body.hasNonNull("error"))   return body.get("error").asText();
            if (body.hasNonNull("message")) return body.get("message").asText();
        }
        return (rawBody == null || rawBody.isBlank()) ? "Remote request failed" : rawBody;
    }

    private JsonNode tryParse(String body) {
        if (body == null || body.isBlank()) return null;
        try {
            return mapper.readTree(body);
        } catch (Exception e) {
            return null;
        }
    }

    /** Reconstructs the caller-visible {@link ModelService.MutationOutcome} from the wire response. */
    private ModelService.MutationOutcome outcome(ValemTypes.MutationResponse r) {
        ModelRuntime.MutationResult result = new ModelRuntime.MutationResult(
                r.success(),
                orEmpty(r.mutatedPaths()),
                orEmpty(r.derivedUpdated()),
                List.of(),                                  // metaUpdated: not carried on the wire
                toViolations(r.flaggedConstraints()),
                toEffects(r.dispatchedEffects()),
                toTraces(r.traces()));
        // The snapshot/appliedMutations are server-side concerns; the dispatchers only read result().
        return new ModelService.MutationOutcome(result, null, Map.of());
    }

    private static List<ConstraintEvaluator.Violation> toViolations(List<ValemTypes.ConstraintViolation> vs) {
        if (vs == null) return List.of();
        return vs.stream()
                .map(v -> new ConstraintEvaluator.Violation(v.constraintId(), v.message(), toPolicy(v.policy())))
                .toList();
    }

    private static List<EffectRequest> toEffects(List<ValemTypes.DispatchedEffect> es) {
        if (es == null) return List.of();
        // The mutation response carries only caller-executed effects (server effects fold back async),
        // so each round-trips to an EffectRequest.Caller — exactly what embedded mode emits here.
        return es.stream()
                .map(e -> (EffectRequest) new EffectRequest.Caller(e.effectId(), e.emit(), e.payload(), null, null))
                .toList();
    }

    private static List<DerivationTrace> toTraces(List<ValemTypes.DerivationTrace> ts) {
        if (ts == null) return List.of();
        return ts.stream().map(RemoteModelOperations::toCoreTrace).toList();
    }

    private static DerivationTrace toCoreTrace(ValemTypes.DerivationTrace t) {
        return new DerivationTrace(t.targetPath(), t.expression(), orEmpty(t.inputPaths()),
                t.result(), t.constraintPassed(), t.errorMessage());
    }

    private static ConstraintPolicy toPolicy(String s) {
        if (s == null) return null;
        return "rollback".equalsIgnoreCase(s) ? ConstraintPolicy.ROLLBACK
                : "flag".equalsIgnoreCase(s) ? ConstraintPolicy.FLAG : null;
    }

    private <T> T treeTo(JsonNode node, Class<T> type) {
        try {
            return mapper.treeToValue(node, type);
        } catch (Exception e) {
            throw new RemoteOperationException(0, "Failed to parse server response: " + e.getMessage());
        }
    }

    private static ObjectNode asObject(JsonNode node) {
        if (node instanceof ObjectNode obj) return obj;
        throw new RemoteOperationException(0, "Expected a JSON object from the server");
    }

    private static <T> List<T> orEmpty(List<T> list) {
        return list == null ? List.of() : list;
    }
}
