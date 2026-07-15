package org.json_kula.valem.persistence.mongo;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.ReplaceOptions;
import com.mongodb.client.model.Sorts;
import org.bson.Document;
import org.bson.conversions.Bson;
import org.bson.types.ObjectId;
import org.json_kula.valem.core.state.Snapshot;
import org.json_kula.valem.persistence.MutationLogReplay;
import org.json_kula.valem.persistence.StateStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.time.Instant;
import java.util.Date;
import java.util.Map;
import java.util.Optional;

/**
 * {@link StateStore} backed by MongoDB collections {@code ss_states} (baseline) and
 * {@code ss_mutations} (incremental log).
 *
 * <p>State document shapes (stored as JSON strings to avoid BSON key restrictions):
 * <ul>
 *   <li>{@code ss_states}: {@code {_id: modelId, snapshotJson: "...", snapshotTs: Date}}</li>
 *   <li>{@code ss_mutations}: {@code {modelId, appliedAt: Date, patchJson: "[...]"}}</li>
 * </ul>
 *
 * <p>Compaction (merge log into new baseline) runs when the mutation count exceeds
 * {@code compactionThreshold}. It is safe without a transaction because the caller
 * (ModelService) holds the per-model runtime lock throughout.
 */
public final class MongoStateStore implements StateStore {

    private static final Logger log = LoggerFactory.getLogger(MongoStateStore.class);
    private static final String STATES_COLLECTION    = "ss_states";
    private static final String MUTATIONS_COLLECTION = "ss_mutations";

    private final MongoCollection<Document> states;
    private final MongoCollection<Document> mutations;
    private final ObjectMapper              mapper;
    private final int                       compactionThreshold;

    public MongoStateStore(MongoDatabase db, ObjectMapper mapper, int compactionThreshold) {
        this.states              = db.getCollection(STATES_COLLECTION);
        this.mutations           = db.getCollection(MUTATIONS_COLLECTION);
        this.mapper              = mapper;
        this.compactionThreshold = compactionThreshold;
    }

    @Override
    public void saveSnapshot(String modelId, Snapshot snap) throws IOException {
        Document doc = new Document("_id", modelId)
                .append("snapshotJson", snapshotToJson(snap))
                .append("snapshotTs",   new Date());
        states.replaceOne(Filters.eq("_id", modelId), doc, new ReplaceOptions().upsert(true));
        mutations.deleteMany(Filters.eq("modelId", modelId));
        log.debug("Saved snapshot for model '{}'", modelId);
    }

    @Override
    public void applyMutationPatch(String modelId, ArrayNode patch, Instant mutatedAt)
            throws IOException {
        Document record = new Document("modelId",  modelId)
                .append("appliedAt", Date.from(mutatedAt))
                .append("patchJson", mapper.writeValueAsString(patch));
        mutations.insertOne(record);

        long count = mutations.countDocuments(Filters.eq("modelId", modelId));
        if (count > compactionThreshold) {
            compact(modelId);
        }
    }

    @Override
    public Optional<Snapshot> loadSnapshot(String modelId) throws IOException {
        return loadSnapshot(modelId, null);
    }

    /**
     * Reconstructs state from the baseline plus mutations, ordered by the monotonic insertion
     * {@code _id} (not {@code appliedAt}, which can tie for same-millisecond writes), optionally
     * bounded to {@code _id <= maxId}. The bound lets compaction merge exactly the records it then
     * deletes (F-T7).
     */
    private Optional<Snapshot> loadSnapshot(String modelId, ObjectId maxId) throws IOException {
        Document stateDoc = states.find(Filters.eq("_id", modelId)).first();
        if (stateDoc == null) return Optional.empty();

        Snapshot baseline = snapshotFromJson(modelId, stateDoc.getString("snapshotJson"));

        ObjectNode baseDoc = baseline.baseDoc().deepCopy();
        Bson filter = (maxId == null)
                ? Filters.eq("modelId", modelId)
                : Filters.and(Filters.eq("modelId", modelId), Filters.lte("_id", maxId));
        Iterable<Document> records = mutations.find(filter).sort(Sorts.ascending("_id"));
        for (Document record : records) {
            String patchJson = record.getString("patchJson");
            if (patchJson == null) continue;
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
        states.deleteOne(Filters.eq("_id", modelId));
        mutations.deleteMany(Filters.eq("modelId", modelId));
        log.debug("Deleted state for model '{}'", modelId);
    }

    @Override
    public boolean isEnabled() { return true; }

    /**
     * Merges the log into the baseline and truncates it, pinned to a concrete last-included
     * insertion {@code _id} (F-T7): the new baseline merges records {@code _id <= maxId} and only
     * those are deleted, so a record inserted after {@code maxId} survives instead of being
     * merged-then-deleted.
     */
    private void compact(String modelId) {
        try {
            Document last = mutations.find(Filters.eq("modelId", modelId))
                    .sort(Sorts.descending("_id")).limit(1).first();
            if (last == null) return; // nothing to compact
            ObjectId maxId = last.getObjectId("_id");

            Optional<Snapshot> merged = loadSnapshot(modelId, maxId);
            if (merged.isEmpty()) return;
            Document doc = new Document("_id", modelId)
                    .append("snapshotJson", snapshotToJson(merged.get()))
                    .append("snapshotTs",   new Date());
            states.replaceOne(Filters.eq("_id", modelId), doc, new ReplaceOptions().upsert(true));
            mutations.deleteMany(Filters.and(Filters.eq("modelId", modelId), Filters.lte("_id", maxId)));
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
