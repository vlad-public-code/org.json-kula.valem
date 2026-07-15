package org.json_kula.valem.persistence.audit;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.JsonNode;
import org.json_kula.valem.core.engine.DerivationTrace;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * One durable, append-only audit entry for a single committed mutation cycle.
 *
 * <p>Unlike the runtime's in-memory {@code DerivationTrace} ring buffer (500 entries) and
 * {@code ModelHistory} (100 timestamps) — both of which silently drop their oldest records — an
 * {@link AuditStore} keeps every {@code AuditRecord} for the life of the model. A record answers
 * <em>what changed, when, why, and what it triggered</em> for a single reactive cycle:
 * <ul>
 *   <li>{@link #mutations} — the base-field writes that opened the cycle (client input, a folded-back
 *       effect result, a creation-time seed, or an evolution backfill), keyed by JSON Path.</li>
 *   <li>{@link #derivedUpdated} — derived field paths that were re-evaluated as a result.</li>
 *   <li>{@link #traces} — the derivation and constraint evaluation traces (the "why"), a durable copy
 *       of what the runtime also exposes transiently via {@code GET /models/{id}/explain}.</li>
 *   <li>{@link #flaggedConstraints} / {@link #dispatchedEffects} — soft-constraint violations recorded
 *       and effect ids whose triggers fired in this cycle.</li>
 * </ul>
 *
 * <p>{@link #sequence} is a per-model monotonic counter assigned by the store at append time (the
 * caller passes any value; {@link #withSequence} stamps the authoritative one). {@link #source}
 * distinguishes how the cycle was initiated: {@code client}, {@code patch}, {@code foldback},
 * {@code init}, {@code restore}, or {@code evolve}.
 *
 * <p>The record is a plain Jackson-serialisable Java record — every backend stores it as JSON.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record AuditRecord(
        String modelId,
        long sequence,
        String timestamp,
        String modelVersion,
        String source,
        Map<String, JsonNode> mutations,
        List<String> derivedUpdated,
        List<String> flaggedConstraints,
        List<String> dispatchedEffects,
        List<DerivationTrace> traces,
        String prevHash,
        String hash
) {
    public AuditRecord {
        mutations          = mutations          == null ? Map.of()  : Map.copyOf(mutations);
        derivedUpdated     = derivedUpdated     == null ? List.of() : List.copyOf(derivedUpdated);
        flaggedConstraints = flaggedConstraints == null ? List.of() : List.copyOf(flaggedConstraints);
        dispatchedEffects  = dispatchedEffects  == null ? List.of() : List.copyOf(dispatchedEffects);
        traces             = traces             == null ? List.of() : List.copyOf(traces);
    }

    /**
     * Builds a record with an {@link Instant} timestamp (stored as ISO-8601 text so the record
     * serialises with plain jackson-databind, no JavaTime module required). Sequence is left 0 for
     * the store to stamp via {@link #withSequence}.
     */
    public static AuditRecord of(
            String modelId, Instant timestamp, String modelVersion, String source,
            Map<String, JsonNode> mutations, List<String> derivedUpdated,
            List<String> flaggedConstraints, List<String> dispatchedEffects,
            List<DerivationTrace> traces) {
        return new AuditRecord(modelId, 0L,
                timestamp == null ? null : timestamp.toString(),
                modelVersion, source, mutations, derivedUpdated,
                flaggedConstraints, dispatchedEffects, traces, null, null);
    }

    /** Parses {@link #timestamp} back to an {@link Instant}, or {@code null} if unset/unparseable. */
    public Instant instant() {
        if (timestamp == null || timestamp.isBlank()) return null;
        try {
            return Instant.parse(timestamp);
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** Returns a copy of this record with the store-assigned monotonic sequence number. */
    public AuditRecord withSequence(long assigned) {
        return new AuditRecord(modelId, assigned, timestamp, modelVersion, source,
                mutations, derivedUpdated, flaggedConstraints, dispatchedEffects, traces, prevHash, hash);
    }

    /** Returns a copy of this record with the hash-chain fields set (see {@link AuditHashing}). */
    public AuditRecord withChain(String newPrevHash, String newHash) {
        return new AuditRecord(modelId, sequence, timestamp, modelVersion, source,
                mutations, derivedUpdated, flaggedConstraints, dispatchedEffects, traces, newPrevHash, newHash);
    }

    /**
     * Returns {@code true} if this record touched {@code pathPrefix} — either a written mutation key,
     * a re-evaluated derived path, or a trace target path starts with it. Used by
     * {@link AuditQuery} path filtering. A {@code null}/blank prefix matches everything.
     */
    public boolean touchesPath(String pathPrefix) {
        if (pathPrefix == null || pathPrefix.isBlank()) return true;
        for (String k : mutations.keySet())  if (k != null && k.startsWith(pathPrefix)) return true;
        for (String d : derivedUpdated)       if (d != null && d.startsWith(pathPrefix)) return true;
        for (DerivationTrace t : traces) {
            if (t.targetPath() != null && t.targetPath().startsWith(pathPrefix)) return true;
        }
        return false;
    }
}
