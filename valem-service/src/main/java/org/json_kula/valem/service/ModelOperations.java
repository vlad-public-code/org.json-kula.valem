package org.json_kula.valem.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.json_kula.valem.core.engine.DerivationTrace;
import org.json_kula.valem.core.graph.SpecEvolution;
import org.json_kula.valem.core.model.BlobRef;
import org.json_kula.valem.core.model.ModelSpec;
import org.json_kula.valem.core.state.Snapshot;

import java.io.IOException;
import java.io.InputStream;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * The set of model operations both CLI dispatchers ({@code ToolRegistry} for MCP,
 * {@code CommandDispatcher} for the console) invoke — the seam that lets each CLI run
 * <b>embedded</b> (the default {@link ModelService}, in-process, in-memory) or against a
 * <b>remote</b> {@code valem-web} server (a {@code RemoteModelOperations} backed by the
 * Java SDK) without either dispatcher knowing which.
 *
 * <p>This interface deliberately covers <i>only</i> the operations that map onto a running
 * model/server. Pure authoring/verification tools ({@code validate_spec}, {@code eval_expression},
 * {@code test_spec}, {@code dry_run} and the console equivalents) are pure functions of their
 * inputs and stay local in both modes, so they are not part of this facade.
 *
 * <p>Behaviour differs between modes only in lifetime, {@code listModels}
 * scope, {@code createModel} collisions, auth, and latency. Every method's contract — including the
 * domain exceptions thrown by {@link ModelService} — is otherwise identical across modes; a remote
 * implementation maps the equivalent HTTP failures (404/409/422/429) onto the same domain
 * exceptions so a caller cannot tell the modes apart by error shape.
 */
public interface ModelOperations {

    // ── Create ────────────────────────────────────────────────────────────────

    /** @see ModelService#createModel(ModelSpec) */
    void createModel(ModelSpec spec);

    // ── Mutation ────────────────────────────────────────────────────────────────

    /** @see ModelService#mutate(String, Map) */
    ModelService.MutationOutcome mutate(String id, Map<String, JsonNode> mutations);

    /** @see ModelService#patchMutate(String, JsonNode) */
    ModelService.MutationOutcome patchMutate(String id, JsonNode patchDoc);

    // ── Read ────────────────────────────────────────────────────────────────────

    /** @see ModelService#listModels() */
    List<String> listModels();

    /** @see ModelService#getSpec(String) */
    ModelSpec getSpec(String id);

    /** @see ModelService#getInfo(String) */
    ModelInfo getInfo(String id);

    /** @see ModelService#getState(String, Instant) */
    ObjectNode getState(String id, Instant at);

    /** @see ModelService#getFieldValue(String, String) */
    JsonNode getFieldValue(String id, String path);

    /** @see ModelService#getHistory(String) */
    List<String> getHistory(String id);

    /** @see ModelService#getEffectiveSchema(String, String) */
    ObjectNode getEffectiveSchema(String id, String path);

    /** @see ModelService#explain(String, String) */
    List<DerivationTrace> explain(String id, String path);

    // ── Snapshot ──────────────────────────────────────────────────────────────

    /** @see ModelService#snapshot(String) */
    Snapshot snapshot(String id);

    /** @see ModelService#restore(String, Snapshot) */
    void restore(String id, Snapshot snapshot);

    // ── Spec evolution ────────────────────────────────────────────────────────

    /** @see ModelService#evolveSpec(String, SpecEvolution) */
    ModelSpec evolveSpec(String id, SpecEvolution evolution);

    // ── View ──────────────────────────────────────────────────────────────────

    /**
     * The evaluated view as a renderer-agnostic JSON tree (the same shape {@code GET /models/{id}/view}
     * serves). Returned as {@link JsonNode} rather than the typed {@code EvaluatedView} because the
     * evaluated component tree is a sealed hierarchy with no deserialization type info — the facade
     * carries the already-resolved tree so a remote implementation can pass the server's response
     * through unchanged. Embedded mode serialises its {@code EvaluatedView} to the identical tree.
     *
     * @see ModelService#getView(String, String)
     */
    JsonNode getView(String id, String viewId);

    // ── Delete ────────────────────────────────────────────────────────────────

    /** @see ModelService#deleteModel(String) */
    void deleteModel(String id);

    // ── Blob ──────────────────────────────────────────────────────────────────

    /** @see ModelService#uploadBlob(InputStream, String) */
    BlobRef uploadBlob(InputStream data, String mediaType) throws IOException;

    /** @see ModelService#downloadBlob(String) */
    InputStream downloadBlob(String blobId) throws IOException;

    /** @see ModelService#getBlobForModel(String, String) */
    InputStream getBlobForModel(String modelId, String blobId) throws IOException;
}
