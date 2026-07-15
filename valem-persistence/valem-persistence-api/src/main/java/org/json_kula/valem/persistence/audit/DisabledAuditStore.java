package org.json_kula.valem.persistence.audit;

import java.util.List;

/**
 * No-op {@link AuditStore}: {@link #append} silently discards, {@link #query} returns empty, and
 * {@link #isEnabled()} is {@code false}. This is the default when no durable audit backend is
 * configured — the runtime still exposes its bounded in-memory explainability, but nothing is
 * retained beyond it.
 *
 * <p>Selecting a real backend ({@code valem.storage.audit-type=filesystem|memory|...}) replaces
 * this with a retaining store.
 */
public final class DisabledAuditStore implements AuditStore {

    @Override public AuditRecord append(AuditRecord record) { return record; }
    @Override public List<AuditRecord> query(AuditQuery query) { return List.of(); }
    @Override public AuditVerification verify(String modelId) { return AuditVerification.valid(0); }
    @Override public long count(String modelId) { return 0; }
    @Override public void deleteAudit(String modelId) {}
    @Override public boolean isEnabled() { return false; }
}
