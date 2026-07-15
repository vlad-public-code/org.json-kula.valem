package org.json_kula.valem.console;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.json_kula.valem.core.engine.ModelRuntime;
import org.json_kula.valem.core.graph.SpecEvolution;
import org.json_kula.valem.core.model.BlobRef;
import org.json_kula.valem.core.model.ModelSpec;
import org.json_kula.valem.core.state.Snapshot;
import org.json_kula.valem.service.ModelOperations;
import org.json_kula.valem.service.ModelService;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Routes JSON command objects to {@link ModelService} calls.
 *
 * <p>Command format (all fields are JSON):
 * <pre>
 *   {"cmd": "command-name", ...args}
 * </pre>
 * Returns the operation result, which is serialised by the caller.
 */
class CommandDispatcher {

    private final ModelOperations service;
    private final ObjectMapper mapper;

    CommandDispatcher(ModelOperations service, ObjectMapper mapper) {
        this.service = service;
        this.mapper  = mapper;
    }

    /**
     * Dispatches a parsed command node and returns the result object.
     *
     * @throws Exception on any service or parsing error
     */
    Object dispatch(ObjectNode cmd) throws Exception {
        String name = cmd.path("cmd").asText();
        return switch (name) {

            case "list-models" ->
                    service.listModels();

            case "create-model" -> {
                ModelSpec spec = mapper.treeToValue(required(cmd, "spec"), ModelSpec.class);
                service.createModel(spec);
                yield Map.of("id", spec.id(), "status", "created");
            }

            case "get-spec" ->
                    service.getSpec(requiredText(cmd, "id"));

            case "get-info" ->
                    service.getInfo(requiredText(cmd, "id"));

            case "get-state" -> {
                String  id    = requiredText(cmd, "id");
                Instant at    = cmd.has("at") ? Instant.parse(cmd.get("at").asText()) : null;
                yield service.getState(id, at);
            }

            case "get-field" -> {
                JsonNode value = service.getFieldValue(
                        requiredText(cmd, "id"),
                        requiredText(cmd, "path"));
                yield (value == null || value.isMissingNode()) ? null : value;
            }

            case "mutate" -> {
                ModelService.MutationOutcome outcome = service.mutate(
                        requiredText(cmd, "id"),
                        parseMutations(required(cmd, "mutations")));
                yield mutationMap(outcome.result());
            }

            case "patch-mutate" -> {
                ModelService.MutationOutcome outcome = service.patchMutate(
                        requiredText(cmd, "id"),
                        required(cmd, "patch"));
                yield mutationMap(outcome.result());
            }

            case "get-history" ->
                    service.getHistory(requiredText(cmd, "id"));

            case "get-schema" ->
                    service.getEffectiveSchema(
                            requiredText(cmd, "id"),
                            requiredText(cmd, "path"));

            case "explain" ->
                    service.explain(
                            requiredText(cmd, "id"),
                            requiredText(cmd, "path"));

            case "snapshot" ->
                    service.snapshot(requiredText(cmd, "id"));

            case "restore" -> {
                String   id   = requiredText(cmd, "id");
                Snapshot snap = mapper.treeToValue(required(cmd, "snapshot"), Snapshot.class);
                service.restore(id, snap);
                yield Map.of("restored", true);
            }

            case "evolve-spec" -> {
                String       id        = requiredText(cmd, "id");
                SpecEvolution evolution = mapper.treeToValue(required(cmd, "evolution"), SpecEvolution.class);
                ModelSpec    evolved   = service.evolveSpec(id, evolution);
                yield Map.of("id", id, "version", evolved.version());
            }

            case "delete-model" -> {
                service.deleteModel(requiredText(cmd, "id"));
                yield Map.of("deleted", true);
            }

            case "upload-blob" -> {
                byte[]      bytes     = Base64.getDecoder().decode(requiredText(cmd, "data"));
                String      mediaType = cmd.path("mediaType").asText("application/octet-stream");
                BlobRef ref = service.uploadBlob(new ByteArrayInputStream(bytes), mediaType);
                yield ref;
            }

            case "get-blob" -> {
                String blobId = requiredText(cmd, "blobId");
                try (InputStream in = service.downloadBlob(blobId)) {
                    yield Map.of("blobId", blobId,
                            "data", Base64.getEncoder().encodeToString(in.readAllBytes()));
                }
            }

            case "get-model-blob" -> {
                String id     = requiredText(cmd, "id");
                String blobId = requiredText(cmd, "blobId");
                try (InputStream in = service.getBlobForModel(id, blobId)) {
                    yield Map.of("blobId", blobId,
                            "data", Base64.getEncoder().encodeToString(in.readAllBytes()));
                }
            }

            case "get-view" ->
                    service.getView(
                            requiredText(cmd, "id"),
                            cmd.has("viewId") ? cmd.get("viewId").asText() : null);

            case "help" ->
                    helpText();

            default ->
                    throw new IllegalArgumentException(
                            "Unknown command: '" + name + "'. Send {\"cmd\":\"help\"} to list commands.");
        };
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private static JsonNode required(ObjectNode cmd, String field) {
        JsonNode node = cmd.get(field);
        if (node == null || node.isNull()) {
            throw new IllegalArgumentException("Missing required field: '" + field + "'");
        }
        return node;
    }

    private static String requiredText(ObjectNode cmd, String field) {
        return required(cmd, field).asText();
    }

    private static Map<String, JsonNode> parseMutations(JsonNode node) {
        Map<String, JsonNode> map = new LinkedHashMap<>();
        node.fields().forEachRemaining(e -> map.put(e.getKey(), e.getValue()));
        return map;
    }

    private static Map<String, Object> mutationMap(ModelRuntime.MutationResult r) {
        return Map.of(
                "success",            r.success(),
                "mutatedPaths",       r.mutatedPaths(),
                "derivedUpdated",     r.derivedUpdated(),
                "metaUpdated",        r.metaUpdated(),
                "flaggedConstraints", r.flaggedConstraints(),
                "dispatchedEffects",  r.dispatchedEffects(),
                "traces",             r.traces());
    }

    private static List<String> helpText() {
        return List.of(
                "list-models",
                "create-model                  {spec: <ModelSpec>}",
                "get-spec                       {id}",
                "get-info                       {id}",
                "get-state                      {id} [at: <ISO-8601>]",
                "get-field                      {id, path: \"$.x\"}",
                "mutate                         {id, mutations: {\"a.b\": <value>}}",
                "patch-mutate                   {id, patch: [<RFC-6902-ops>]}",
                "get-history                    {id}",
                "get-schema                     {id, path: \"$.x\"}",
                "explain                        {id, path: \"$.x\"}",
                "snapshot                       {id}",
                "restore                        {id, snapshot: <Snapshot>}",
                "evolve-spec                    {id, evolution: <SpecEvolution>}",
                "delete-model                   {id}",
                "upload-blob                    {data: <base64>, mediaType: \"image/png\"}",
                "get-blob                       {blobId: \"sha256:<hex>\"}",
                "get-model-blob                 {id, blobId: \"sha256:<hex>\"}",
                "get-view                       {id} [viewId: \"main\"]",
                "exit");
    }
}
