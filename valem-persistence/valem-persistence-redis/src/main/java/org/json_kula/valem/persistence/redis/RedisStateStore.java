package org.json_kula.valem.persistence.redis;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.api.sync.RedisCommands;
import org.json_kula.valem.core.state.Snapshot;
import org.json_kula.valem.persistence.MutationLogReplay;
import org.json_kula.valem.persistence.StateStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * {@link StateStore} backed by Redis strings (baseline) and a Redis List (mutation log).
 *
 * <p>Key scheme:
 * <ul>
 *   <li>{@code valem:state:{modelId}} — JSON-serialised baseline snapshot</li>
 *   <li>{@code valem:mutations:{modelId}} — Redis List of JSONL patch records (RPUSH/LRANGE)</li>
 * </ul>
 *
 * <p>On each {@link #applyMutationPatch} call the record is appended via {@code RPUSH}.
 * When the list length exceeds {@code compactionThreshold}, the mutation log is merged
 * into the baseline atomically (load → merge in Java → SET baseline → DEL list).
 * Since callers hold the per-model runtime lock, no Lua script is required for safety.
 */
public final class RedisStateStore implements StateStore {

    private static final Logger log = LoggerFactory.getLogger(RedisStateStore.class);

    static final String KEY_PREFIX_STATE     = "valem:state:";
    static final String KEY_PREFIX_MUTATIONS = "valem:mutations:";

    private final RedisCommands<String, String> commands;
    private final ObjectMapper                  mapper;
    private final int                           compactionThreshold;

    public RedisStateStore(StatefulRedisConnection<String, String> conn,
                           ObjectMapper mapper,
                           int compactionThreshold) {
        this.commands            = conn.sync();
        this.mapper              = mapper;
        this.compactionThreshold = compactionThreshold;
    }

    @Override
    public void saveSnapshot(String modelId, Snapshot snap) throws IOException {
        commands.set(KEY_PREFIX_STATE + modelId, snapshotToJson(snap));
        commands.del(KEY_PREFIX_MUTATIONS + modelId);
        log.debug("Saved snapshot for model '{}'", modelId);
    }

    @Override
    public void applyMutationPatch(String modelId, ArrayNode patch, Instant mutatedAt)
            throws IOException {
        ObjectNode record = mapper.createObjectNode();
        record.put("ts", mutatedAt.toString());
        record.set("patch", patch);
        long len = commands.rpush(KEY_PREFIX_MUTATIONS + modelId,
                mapper.writeValueAsString(record));
        if (len > compactionThreshold) {
            compact(modelId);
        }
    }

    @Override
    public Optional<Snapshot> loadSnapshot(String modelId) throws IOException {
        return loadSnapshot(modelId, -1);
    }

    /**
     * Reconstructs state from the baseline plus log entries, optionally bounded to the first
     * {@code count} list elements ({@code count < 0} replays all). The bound lets compaction merge
     * exactly the prefix it then trims, never an entry appended after the boundary (F-T7).
     */
    private Optional<Snapshot> loadSnapshot(String modelId, long count) throws IOException {
        String baseJson = commands.get(KEY_PREFIX_STATE + modelId);
        if (baseJson == null) return Optional.empty();

        Snapshot baseline = snapshotFromJson(modelId, baseJson);
        List<String> records = (count < 0)
                ? commands.lrange(KEY_PREFIX_MUTATIONS + modelId, 0, -1)
                : commands.lrange(KEY_PREFIX_MUTATIONS + modelId, 0, count - 1);
        if (records.isEmpty()) return Optional.of(baseline);

        ObjectNode baseDoc = baseline.baseDoc().deepCopy();
        for (String recordJson : records) {
            JsonNode node      = mapper.readTree(recordJson);
            JsonNode patchNode = node.get("patch");
            if (patchNode == null || !patchNode.isArray()) continue;
            // Create-as-you-go replay (F-T5): nested paths are reconstructed like the live write,
            // so persisted ops are never silently dropped for a missing parent container.
            baseDoc = MutationLogReplay.apply(baseDoc, patchNode);
        }
        return Optional.of(new Snapshot(
                baseline.modelId(), baseline.modelVersion(),
                baseDoc, baseline.derivedCache(), baseline.metaCache()));
    }

    @Override
    public void deleteState(String modelId) throws IOException {
        commands.del(KEY_PREFIX_STATE + modelId, KEY_PREFIX_MUTATIONS + modelId);
        log.debug("Deleted state for model '{}'", modelId);
    }

    @Override
    public boolean isEnabled() { return true; }

    /**
     * Merges the log into the baseline and trims it, pinned to the current list length (F-T7):
     * the new baseline merges the first {@code n} entries and {@code LTRIM key n -1} removes exactly
     * those, so an entry appended after the boundary lands at index {@code >= n} and survives instead
     * of being merged-then-deleted (as the previous {@code DEL} of the whole list would have done).
     */
    private void compact(String modelId) {
        try {
            String mutKey = KEY_PREFIX_MUTATIONS + modelId;
            long n = commands.llen(mutKey);
            if (n <= 0) return; // nothing to compact

            Optional<Snapshot> merged = loadSnapshot(modelId, n);
            if (merged.isEmpty()) return;
            commands.set(KEY_PREFIX_STATE + modelId, snapshotToJson(merged.get()));
            commands.ltrim(mutKey, n, -1);
            log.debug("Compacted mutation log for model '{}' up to offset {}", modelId, n);
        } catch (IOException e) {
            log.warn("Compaction deferred for model '{}': {}", modelId, e.getMessage());
        }
    }

    private String snapshotToJson(Snapshot snap) throws IOException {
        ObjectNode node = mapper.createObjectNode();
        node.put("modelId",      snap.modelId());
        node.put("modelVersion", snap.modelVersion());
        node.set("baseDoc",      snap.baseDoc());
        node.set("derivedCache", mapper.valueToTree(snap.derivedCache()));
        node.set("metaCache",    mapper.valueToTree(snap.metaCache()));
        return mapper.writeValueAsString(node);
    }

    private Snapshot snapshotFromJson(String modelId, String json) throws IOException {
        JsonNode root        = mapper.readTree(json);
        String storedId      = root.path("modelId").asText(modelId);
        String modelVersion  = root.path("modelVersion").asText("1.0.0");
        ObjectNode baseDoc   = root.has("baseDoc") && root.get("baseDoc").isObject()
                ? (ObjectNode) root.get("baseDoc")
                : mapper.createObjectNode();
        TypeReference<Map<String, JsonNode>> mapType = new TypeReference<>() {};
        Map<String, JsonNode> derived = root.has("derivedCache")
                ? mapper.convertValue(root.get("derivedCache"), mapType) : Map.of();
        Map<String, JsonNode> meta    = root.has("metaCache")
                ? mapper.convertValue(root.get("metaCache"), mapType) : Map.of();
        return new Snapshot(storedId, modelVersion, baseDoc, derived, meta);
    }
}
