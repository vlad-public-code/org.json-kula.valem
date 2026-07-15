package org.json_kula.valem.persistence.postgres;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.json_kula.valem.core.state.Snapshot;
import org.json_kula.valem.persistence.MutationLogReplay;
import org.json_kula.valem.persistence.StateStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;

import java.io.IOException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * {@link StateStore} backed by PostgreSQL tables {@code ss_states} (baseline) and
 * {@code ss_mutations} (incremental log).
 *
 * <p>The full snapshot is stored as a single JSONB column containing
 * {@code {modelId, modelVersion, baseDoc, derivedCache, metaCache}}.
 * Each mutation patch is stored as a JSONB array in {@code ss_mutations}.
 *
 * <p>Compaction is performed when the mutation count exceeds the threshold.
 * It runs in a JDBC transaction (both UPDATE ss_states and DELETE ss_mutations).
 */
public final class PostgresStateStore implements StateStore {

    private static final Logger log = LoggerFactory.getLogger(PostgresStateStore.class);

    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private final int          compactionThreshold;

    public PostgresStateStore(JdbcTemplate jdbc, ObjectMapper mapper, int compactionThreshold) {
        this.jdbc                = jdbc;
        this.mapper              = mapper;
        this.compactionThreshold = compactionThreshold;
    }

    @Override
    public void saveSnapshot(String modelId, Snapshot snap) throws IOException {
        String json = snapshotToJson(snap);
        jdbc.update("""
                INSERT INTO ss_states(model_id, snapshot, snapshot_ts)
                VALUES(?, ?::jsonb, now())
                ON CONFLICT (model_id) DO UPDATE
                SET snapshot = excluded.snapshot, snapshot_ts = now()
                """, modelId, json);
        jdbc.update("DELETE FROM ss_mutations WHERE model_id = ?", modelId);
        log.debug("Saved snapshot for model '{}'", modelId);
    }

    @Override
    public void applyMutationPatch(String modelId, ArrayNode patch, Instant mutatedAt)
            throws IOException {
        String patchJson = mapper.writeValueAsString(patch);
        jdbc.update("""
                INSERT INTO ss_mutations(model_id, applied_at, patch)
                VALUES(?, ?, ?::jsonb)
                """, modelId, Timestamp.from(mutatedAt), patchJson);

        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM ss_mutations WHERE model_id = ?",
                Integer.class, modelId);
        if (count != null && count > compactionThreshold) {
            compact(modelId);
        }
    }

    @Override
    public Optional<Snapshot> loadSnapshot(String modelId) throws IOException {
        return loadSnapshot(modelId, null);
    }

    /**
     * Reconstructs state from the baseline plus mutations, optionally bounded to those with
     * {@code id <= maxId} (inclusive). A {@code null} bound replays the entire log. The bound lets
     * compaction merge exactly the patches it is about to delete, never a concurrently-appended one.
     */
    private Optional<Snapshot> loadSnapshot(String modelId, Long maxId) throws IOException {
        String baseJson;
        try {
            baseJson = jdbc.queryForObject(
                    "SELECT snapshot::text FROM ss_states WHERE model_id = ?",
                    String.class, modelId);
        } catch (EmptyResultDataAccessException e) {
            return Optional.empty();
        }

        Snapshot baseline = snapshotFromJson(modelId, baseJson);
        List<String> patches = (maxId == null)
                ? jdbc.queryForList(
                        "SELECT patch::text FROM ss_mutations WHERE model_id = ? ORDER BY id",
                        String.class, modelId)
                : jdbc.queryForList(
                        "SELECT patch::text FROM ss_mutations WHERE model_id = ? AND id <= ? ORDER BY id",
                        String.class, modelId, maxId);
        if (patches.isEmpty()) return Optional.of(baseline);

        ObjectNode baseDoc = baseline.baseDoc().deepCopy();
        for (String patchJson : patches) {
            JsonNode patchNode = mapper.readTree(patchJson);
            if (!patchNode.isArray()) continue;
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
        jdbc.update("DELETE FROM ss_states    WHERE model_id = ?", modelId);
        jdbc.update("DELETE FROM ss_mutations WHERE model_id = ?", modelId);
        log.debug("Deleted state for model '{}'", modelId);
    }

    @Override
    public boolean isEnabled() { return true; }

    /**
     * Merges the log into the baseline and truncates it. The compaction boundary is pinned to a
     * concrete last-included mutation {@code id} (F-T7): the new baseline merges patches
     * {@code id <= maxId}, and only those are deleted — so a patch appended after {@code maxId}
     * (a later mutation in the same JDBC connection's view, or a future concurrent writer) is never
     * merged-then-deleted and survives to be replayed. Pinning by {@code id} also avoids the
     * {@code applied_at <= now()} hazard where two patches share a timestamp.
     */
    private void compact(String modelId) {
        try {
            Long maxId = jdbc.queryForObject(
                    "SELECT max(id) FROM ss_mutations WHERE model_id = ?", Long.class, modelId);
            if (maxId == null) return; // nothing to compact

            Optional<Snapshot> merged = loadSnapshot(modelId, maxId);
            if (merged.isEmpty()) return;
            String json = snapshotToJson(merged.get());
            jdbc.update("""
                    UPDATE ss_states SET snapshot = ?::jsonb, snapshot_ts = now()
                    WHERE model_id = ?
                    """, json, modelId);
            jdbc.update("DELETE FROM ss_mutations WHERE model_id = ? AND id <= ?", modelId, maxId);
            log.debug("Compacted mutation log for model '{}' up to offset {}", modelId, maxId);
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
