package org.json_kula.valem.persistence.audit;

import org.json_kula.valem.core.util.CanonicalJson;

import java.util.List;

/**
 * SHA-256 hash-chaining for the audit trail. Each {@link AuditRecord} carries the hash of its
 * predecessor ({@link AuditRecord#prevHash}) and its own {@link AuditRecord#hash} over a
 * <em>canonical</em> projection of its content plus that predecessor hash. Chaining makes the trail
 * <b>tamper-evident</b>: altering, reordering, or deleting any record breaks every hash from that
 * point on, which {@link AuditStore#verify} detects.
 *
 * <p>The canonical form sorts object keys (both record properties and JSON map entries) so the hash
 * is stable across store round-trips and independent of serialisation order.
 */
public final class AuditHashing {

    private AuditHashing() {}

    /** prevHash for the first record in a chain (sequence 0). */
    public static final String GENESIS = CanonicalJson.GENESIS;

    /**
     * Computes the SHA-256 hex hash binding {@code record}'s content to {@code prevHash}. Any hash
     * fields already on {@code record} are ignored (nulled) so the projection is content-only.
     * Delegates to the shared {@link CanonicalJson} canonicaliser so audit, spec-digest, and
     * effect-definition hashes stay mutually consistent.
     */
    public static String hash(AuditRecord record, String prevHash) {
        AuditRecord bare = record.withChain(null, null);
        String canonical = CanonicalJson.canonicalize(bare);
        return CanonicalJson.sha256((prevHash == null ? GENESIS : prevHash) + "\n" + canonical);
    }

    /**
     * Stamps {@code record} (which must already carry its sequence) with {@code prevHash} and the
     * computed content hash. Stores call this at append time.
     */
    public static AuditRecord chain(AuditRecord record, String prevHash) {
        String prev = prevHash == null ? GENESIS : prevHash;
        return record.withChain(prev, hash(record, prev));
    }

    /**
     * Verifies a chronologically-ascending list of records: each must be hash-chained, its
     * {@code prevHash} must equal the preceding record's {@code hash} (genesis for the first), and
     * its {@code hash} must match a recomputation of its content. The first failure short-circuits.
     */
    public static AuditVerification verifyChain(List<AuditRecord> ascending) {
        String expectedPrev = GENESIS;
        long checked = 0;
        for (AuditRecord r : ascending) {
            checked++;
            if (r.hash() == null || r.prevHash() == null) {
                return AuditVerification.broken(r.sequence(), checked, "record is not hash-chained");
            }
            if (!expectedPrev.equals(r.prevHash())) {
                return AuditVerification.broken(r.sequence(), checked,
                        "prevHash does not match the preceding record (reorder or deletion)");
            }
            if (!hash(r, r.prevHash()).equals(r.hash())) {
                return AuditVerification.broken(r.sequence(), checked,
                        "content hash mismatch (record was altered)");
            }
            expectedPrev = r.hash();
        }
        return AuditVerification.valid(checked);
    }
}
