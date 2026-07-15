package org.json_kula.valem.persistence.postgres;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.json_kula.valem.persistence.audit.AuditHashing;
import org.json_kula.valem.persistence.audit.AuditQuery;
import org.json_kula.valem.persistence.audit.AuditRecord;
import org.json_kula.valem.persistence.audit.AuditStore;
import org.json_kula.valem.persistence.audit.AuditVerification;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;

import java.io.IOException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Durable, append-only {@link AuditStore} backed by PostgreSQL table {@code ss_audit}.
 *
 * <p>Each row is {@code (id, model_id, seq, created_at, audit JSONB)} where {@code audit} is the full
 * hash-chained {@link AuditRecord}. The table is never compacted (unlike the state mutation log): it
 * is the tamper-evident trail. The per-model {@code seq} is a monotonic 0-based counter, assigned by
 * reading {@code max(seq)} under the caller-held model lock.
 */
public final class PostgresAuditStore implements AuditStore {

    private static final Logger log = LoggerFactory.getLogger(PostgresAuditStore.class);

    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;

    public PostgresAuditStore(JdbcTemplate jdbc, ObjectMapper mapper) {
        this.jdbc   = jdbc;
        this.mapper = mapper;
    }

    @Override
    public AuditRecord append(AuditRecord record) throws IOException {
        String modelId = record.modelId();
        Long maxSeq = jdbc.queryForObject(
                "SELECT max(seq) FROM ss_audit WHERE model_id = ?", Long.class, modelId);
        long seq = maxSeq == null ? 0 : maxSeq + 1;
        String prevHash = seq == 0 ? AuditHashing.GENESIS : lastHash(modelId);

        AuditRecord chained = AuditHashing.chain(record.withSequence(seq), prevHash);
        String json = mapper.writeValueAsString(chained);
        Instant ts = chained.instant() == null ? Instant.now() : chained.instant();
        jdbc.update("""
                INSERT INTO ss_audit(model_id, seq, created_at, audit)
                VALUES(?, ?, ?, ?::jsonb)
                """, modelId, seq, Timestamp.from(ts), json);
        return chained;
    }

    @Override
    public List<AuditRecord> query(AuditQuery q) throws IOException {
        StringBuilder sql = new StringBuilder("SELECT audit::text FROM ss_audit WHERE model_id = ?");
        List<Object> args = new ArrayList<>();
        args.add(q.modelId());
        if (q.from() != null) { sql.append(" AND created_at >= ?"); args.add(Timestamp.from(q.from())); }
        if (q.to()   != null) { sql.append(" AND created_at <  ?"); args.add(Timestamp.from(q.to())); }
        sql.append(" ORDER BY seq DESC LIMIT ?");
        // When a path filter is present we must fetch more than the limit and filter in Java;
        // otherwise the SQL LIMIT is exact.
        args.add(q.pathPrefix() == null ? q.effectiveLimit() : AuditQuery.MAX_LIMIT);

        List<String> rows = jdbc.queryForList(sql.toString(), String.class, args.toArray());
        List<AuditRecord> out = new ArrayList<>();
        int limit = q.effectiveLimit();
        for (String r : rows) {
            AuditRecord rec = mapper.readValue(r, AuditRecord.class);
            if (rec.touchesPath(q.pathPrefix())) {
                out.add(rec);
                if (out.size() >= limit) break;
            }
        }
        return List.copyOf(out);
    }

    @Override
    public AuditVerification verify(String modelId) throws IOException {
        List<String> rows = jdbc.queryForList(
                "SELECT audit::text FROM ss_audit WHERE model_id = ? ORDER BY seq ASC",
                String.class, modelId);
        List<AuditRecord> recs = new ArrayList<>(rows.size());
        for (String r : rows) recs.add(mapper.readValue(r, AuditRecord.class));
        return AuditHashing.verifyChain(recs);
    }

    @Override
    public long count(String modelId) {
        Long n = jdbc.queryForObject(
                "SELECT count(*) FROM ss_audit WHERE model_id = ?", Long.class, modelId);
        return n == null ? 0 : n;
    }

    @Override
    public void deleteAudit(String modelId) {
        jdbc.update("DELETE FROM ss_audit WHERE model_id = ?", modelId);
        log.debug("Deleted audit trail for model '{}'", modelId);
    }

    @Override
    public boolean isEnabled() { return true; }

    private String lastHash(String modelId) {
        String h = jdbc.query(
                "SELECT audit->>'hash' AS h FROM ss_audit WHERE model_id = ? ORDER BY seq DESC LIMIT 1",
                rs -> rs.next() ? rs.getString("h") : null, modelId);
        return h == null ? AuditHashing.GENESIS : h;
    }
}
