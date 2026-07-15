-- Valem PostgreSQL schema
-- Run with Flyway or apply manually before starting the application.

CREATE TABLE IF NOT EXISTS ss_specs (
    model_id TEXT        PRIMARY KEY,
    spec     JSONB       NOT NULL,
    saved_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE IF NOT EXISTS ss_states (
    model_id    TEXT        PRIMARY KEY,
    snapshot    JSONB       NOT NULL,
    snapshot_ts TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE IF NOT EXISTS ss_mutations (
    id         BIGSERIAL   PRIMARY KEY,
    model_id   TEXT        NOT NULL,
    applied_at TIMESTAMPTZ NOT NULL,
    patch      JSONB       NOT NULL
);
CREATE INDEX IF NOT EXISTS ss_mutations_model ON ss_mutations(model_id, applied_at);

CREATE TABLE IF NOT EXISTS ss_blobs (
    blob_id    TEXT   PRIMARY KEY,
    media_type TEXT   NOT NULL,
    byte_count BIGINT NOT NULL,
    data       BYTEA
);

-- Durable, append-only, hash-chained audit trail (never compacted).
CREATE TABLE IF NOT EXISTS ss_audit (
    id         BIGSERIAL   PRIMARY KEY,
    model_id   TEXT        NOT NULL,
    seq        BIGINT      NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    audit      JSONB       NOT NULL,
    UNIQUE (model_id, seq)
);
CREATE INDEX IF NOT EXISTS ss_audit_model ON ss_audit(model_id, seq);
