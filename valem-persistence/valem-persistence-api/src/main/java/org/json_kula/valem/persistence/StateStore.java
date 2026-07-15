package org.json_kula.valem.persistence;

import com.fasterxml.jackson.databind.node.ArrayNode;
import org.json_kula.valem.core.state.Snapshot;

import java.io.IOException;
import java.time.Instant;
import java.util.Optional;

/**
 * Persistence contract for model runtime state.
 *
 * <p>State is persisted in two layers:
 * <ol>
 *   <li>A <b>baseline snapshot</b> — a full copy of {@code baseDoc}, {@code derivedCache},
 *       and {@code metaCache} written atomically (expensive but infrequent).</li>
 *   <li>An <b>incremental mutation log</b> — one RFC 6902 JSON Patch record per mutation
 *       (cheap, append-only). Applied to the baseline on load to reconstruct current state.</li>
 * </ol>
 *
 * <p>Implementations compact the log into a new baseline when the record count exceeds a
 * configurable threshold (default 100), bounding load latency regardless of history length.
 *
 * <p>Thread-safe with respect to different model IDs. Same-ID operations must be serialised
 * by the caller.
 */
public interface StateStore {

    /**
     * Persists a full baseline snapshot, replacing any previous baseline and clearing
     * the incremental mutation log.
     *
     * <p>Called after spec evolution, explicit {@code POST /snapshot} requests, and compaction.
     * On the normal mutation path, prefer {@link #applyMutationPatch} instead.
     */
    void saveSnapshot(String modelId, Snapshot snapshot) throws IOException;

    /**
     * Appends one RFC 6902 JSON Patch to the incremental log for this model.
     * The patch describes changes to {@code baseDoc} only; derived and meta caches are
     * excluded and will be recomputed by {@code ModelRuntime} after a cold restart.
     *
     * <p>Triggers automatic compaction when the log size exceeds the configured threshold.
     *
     * <p>The default implementation falls back to load + merge + {@link #saveSnapshot} for
     * stores that do not natively support incremental writes. Adapters should override this
     * with a backend-native append (file append, Redis RPUSH, MongoDB insert, SQL INSERT).
     */
    default void applyMutationPatch(String modelId, ArrayNode patch, Instant mutatedAt)
            throws IOException {
        Optional<Snapshot> current = loadSnapshot(modelId);
        if (current.isEmpty()) return;
        // Default: full rewrite. Adapter overrides provide incremental path.
        saveSnapshot(modelId, current.get());
    }

    /**
     * Reconstructs the full current state: baseline {@code baseDoc} with all pending
     * incremental patches applied. Returns empty if no data exists for this model.
     *
     * <p>The returned {@link Snapshot} may have empty {@code derivedCache} and
     * {@code metaCache} if they were omitted from incremental records; the caller
     * ({@code ModelRuntime}) re-evaluates stale derivations on the first mutation.
     */
    Optional<Snapshot> loadSnapshot(String modelId) throws IOException;

    /** Removes all persisted state (baseline + mutation log) for {@code modelId}. */
    void deleteState(String modelId) throws IOException;

    /** Returns {@code true} when this store writes to durable storage. */
    boolean isEnabled();
}
