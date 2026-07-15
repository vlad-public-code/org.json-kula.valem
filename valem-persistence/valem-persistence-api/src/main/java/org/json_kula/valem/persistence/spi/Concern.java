package org.json_kula.valem.persistence.spi;

/**
 * The persistence concerns a {@link PersistenceProvider} may serve.
 *
 * <p>Each concern is selected independently at configuration time
 * ({@code valem.storage.spec-type} / {@code state-type} / {@code blob-type} /
 * {@code audit-type}). A single backend jar typically offers several concerns
 * (e.g. Postgres serves all four), while some offer only a subset
 * (Redis has no blob store; S3 offers only {@link #BLOB}).
 */
public enum Concern {
    /** {@code ModelSpec} documents ({@code SpecStore}). */
    SPEC,
    /** Model runtime state — baseline snapshot + incremental mutation log ({@code StateStore}). */
    STATE,
    /** Content-addressed binary storage ({@code BlobStore}). */
    BLOB,
    /** Durable append-only audit trail ({@code AuditStore}). */
    AUDIT
}
