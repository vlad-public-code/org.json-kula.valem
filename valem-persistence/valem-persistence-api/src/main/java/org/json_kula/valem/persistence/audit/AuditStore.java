package org.json_kula.valem.persistence.audit;

import java.io.IOException;
import java.util.List;

/**
 * Durable, append-only audit trail for reactive mutation cycles.
 *
 * <p>This is the persistence answer to the concept audit's headline gap: the runtime's live
 * explainability (a 500-entry {@code DerivationTrace} ring buffer and a 100-entry
 * {@code ModelHistory}) is bounded and lossy, which is fine for live debugging but cannot back the
 * regulatory "explain any value at any point in time" story. An {@code AuditStore} keeps <em>every</em>
 * committed cycle as an {@link AuditRecord}, so the trail can be queried long after the ring buffer
 * has rolled over.
 *
 * <p>The store is <b>append-only</b>: records are never mutated or compacted away (unlike the state
 * mutation log). {@link #append} assigns a per-model monotonic {@link AuditRecord#sequence} and
 * returns the stamped record. {@link #query} filters by path prefix and time window and returns
 * most-recent-first.
 *
 * <p>Thread-safe with respect to different model IDs. Same-model appends are serialised by the caller
 * ({@code ModelService} appends inside the model lock, so the sequence order matches commit order).
 */
public interface AuditStore {

    /**
     * Appends one audit record, assigning it the next per-model {@link AuditRecord#sequence}.
     * Returns the stamped record (same fields, authoritative sequence).
     */
    AuditRecord append(AuditRecord record) throws IOException;

    /** Returns records matching {@code query}, most-recent-first, up to {@link AuditQuery#effectiveLimit()}. */
    List<AuditRecord> query(AuditQuery query) throws IOException;

    /**
     * Verifies the full hash chain for {@code modelId} (see {@link AuditHashing}). Detects any
     * alteration, reordering, or deletion of records. Returns a valid result for an empty/absent
     * trail.
     */
    AuditVerification verify(String modelId) throws IOException;

    /** Number of audit records retained for {@code modelId}. */
    long count(String modelId) throws IOException;

    /** Removes the entire audit trail for {@code modelId} (called when the model is deleted). */
    void deleteAudit(String modelId) throws IOException;

    /** {@code true} when this store durably retains audit records (vs. the disabled no-op). */
    boolean isEnabled();
}
