package org.json_kula.valem.persistence.mongo;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.Sorts;
import org.bson.Document;
import org.bson.conversions.Bson;
import org.json_kula.valem.persistence.audit.AuditHashing;
import org.json_kula.valem.persistence.audit.AuditQuery;
import org.json_kula.valem.persistence.audit.AuditRecord;
import org.json_kula.valem.persistence.audit.AuditStore;
import org.json_kula.valem.persistence.audit.AuditVerification;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;

/**
 * Durable, append-only {@link AuditStore} backed by MongoDB collection {@code ss_audit}.
 *
 * <p>Each document is {@code {modelId, seq, ts: Date, hash, auditJson: "..."}} — the full hash-chained
 * {@link AuditRecord} is stored as a JSON string (avoiding BSON key restrictions for the arbitrary
 * mutation/trace paths), with {@code hash} duplicated as a top-level field for a cheap chain lookup.
 * Never compacted; {@code seq} is a monotonic 0-based per-model counter assigned under the caller-held
 * model lock.
 */
public final class MongoAuditStore implements AuditStore {

    private static final Logger log = LoggerFactory.getLogger(MongoAuditStore.class);
    private static final String AUDIT_COLLECTION = "ss_audit";

    private final MongoCollection<Document> audit;
    private final ObjectMapper              mapper;

    public MongoAuditStore(MongoDatabase db, ObjectMapper mapper) {
        this.audit  = db.getCollection(AUDIT_COLLECTION);
        this.mapper = mapper;
    }

    @Override
    public AuditRecord append(AuditRecord record) throws IOException {
        String modelId = record.modelId();
        Document last = audit.find(Filters.eq("modelId", modelId))
                .sort(Sorts.descending("seq")).limit(1).first();
        long seq = last == null ? 0 : last.getLong("seq") + 1;
        String prevHash = last == null ? AuditHashing.GENESIS
                : (last.getString("hash") == null ? AuditHashing.GENESIS : last.getString("hash"));

        AuditRecord chained = AuditHashing.chain(record.withSequence(seq), prevHash);
        Instant ts = chained.instant() == null ? Instant.now() : chained.instant();
        Document doc = new Document("modelId", modelId)
                .append("seq",       seq)
                .append("ts",        Date.from(ts))
                .append("hash",      chained.hash())
                .append("auditJson", mapper.writeValueAsString(chained));
        audit.insertOne(doc);
        return chained;
    }

    @Override
    public List<AuditRecord> query(AuditQuery q) throws IOException {
        List<Bson> filters = new ArrayList<>();
        filters.add(Filters.eq("modelId", q.modelId()));
        if (q.from() != null) filters.add(Filters.gte("ts", Date.from(q.from())));
        if (q.to()   != null) filters.add(Filters.lt("ts",  Date.from(q.to())));

        int limit = q.effectiveLimit();
        int fetch = q.pathPrefix() == null ? limit : AuditQuery.MAX_LIMIT;
        Iterable<Document> docs = audit.find(Filters.and(filters))
                .sort(Sorts.descending("seq")).limit(fetch);

        List<AuditRecord> out = new ArrayList<>();
        for (Document d : docs) {
            AuditRecord rec = mapper.readValue(d.getString("auditJson"), AuditRecord.class);
            if (rec.touchesPath(q.pathPrefix())) {
                out.add(rec);
                if (out.size() >= limit) break;
            }
        }
        return List.copyOf(out);
    }

    @Override
    public AuditVerification verify(String modelId) throws IOException {
        List<AuditRecord> recs = new ArrayList<>();
        for (Document d : audit.find(Filters.eq("modelId", modelId)).sort(Sorts.ascending("seq"))) {
            recs.add(mapper.readValue(d.getString("auditJson"), AuditRecord.class));
        }
        return AuditHashing.verifyChain(recs);
    }

    @Override
    public long count(String modelId) {
        return audit.countDocuments(Filters.eq("modelId", modelId));
    }

    @Override
    public void deleteAudit(String modelId) {
        audit.deleteMany(Filters.eq("modelId", modelId));
        log.debug("Deleted audit trail for model '{}'", modelId);
    }

    @Override
    public boolean isEnabled() { return true; }
}
