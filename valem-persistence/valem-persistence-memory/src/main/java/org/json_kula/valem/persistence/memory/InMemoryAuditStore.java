package org.json_kula.valem.persistence.memory;

import org.json_kula.valem.persistence.audit.AuditHashing;
import org.json_kula.valem.persistence.audit.AuditQuery;
import org.json_kula.valem.persistence.audit.AuditRecord;
import org.json_kula.valem.persistence.audit.AuditStore;
import org.json_kula.valem.persistence.audit.AuditVerification;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Retaining in-memory {@link AuditStore}. Unlike {@code InMemoryModelStore} (a no-op), this store
 * genuinely keeps every appended record so the audit query API works in dev and tests without a
 * durable backend. Records live for the life of the JVM; there is no eviction, so it is not meant
 * for unbounded production traffic (use the filesystem or a DB backend for that).
 *
 * <p>{@code isEnabled()} is {@code true} — the trail is real, just not durable across restarts.
 */
public final class InMemoryAuditStore implements AuditStore {

    private final Map<String, List<AuditRecord>> byModel = new ConcurrentHashMap<>();

    @Override
    public AuditRecord append(AuditRecord record) {
        List<AuditRecord> list = byModel.computeIfAbsent(record.modelId(), k -> new ArrayList<>());
        synchronized (list) {
            String prevHash = list.isEmpty() ? AuditHashing.GENESIS : list.get(list.size() - 1).hash();
            AuditRecord stamped = AuditHashing.chain(record.withSequence(list.size()), prevHash);
            list.add(stamped);
            return stamped;
        }
    }

    @Override
    public AuditVerification verify(String modelId) {
        List<AuditRecord> list = byModel.get(modelId);
        if (list == null) return AuditVerification.valid(0);
        synchronized (list) {
            return AuditHashing.verifyChain(new ArrayList<>(list)); // already ascending by sequence
        }
    }

    @Override
    public List<AuditRecord> query(AuditQuery query) {
        List<AuditRecord> list = byModel.get(query.modelId());
        if (list == null) return List.of();
        List<AuditRecord> snapshot;
        synchronized (list) {
            snapshot = new ArrayList<>(list);
        }
        return snapshot.stream()
                .filter(r -> query.inWindow(r.instant()))
                .filter(r -> r.touchesPath(query.pathPrefix()))
                .sorted(Comparator.comparingLong(AuditRecord::sequence).reversed())
                .limit(query.effectiveLimit())
                .toList();
    }

    @Override
    public long count(String modelId) {
        List<AuditRecord> list = byModel.get(modelId);
        if (list == null) return 0;
        synchronized (list) { return list.size(); }
    }

    @Override
    public void deleteAudit(String modelId) {
        byModel.remove(modelId);
    }

    @Override
    public boolean isEnabled() { return true; }
}
