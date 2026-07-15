package org.json_kula.valem.persistence;

import org.json_kula.valem.core.model.ModelSpec;

import java.io.IOException;
import java.util.List;
import java.util.Optional;

/**
 * Persistence contract for {@link ModelSpec} documents.
 *
 * <p>Thread-safe with respect to operations on different model IDs.
 * Concurrent operations on the <em>same</em> model ID must be serialised by the caller
 * ({@code ModelService} holds the runtime lock during all mutating operations).
 */
public interface SpecStore {

    /** Persists (or replaces) the spec for {@code modelId}. */
    void saveSpec(String modelId, ModelSpec spec) throws IOException;

    /** Loads the spec for {@code modelId}, or empty if not found. */
    Optional<ModelSpec> loadSpec(String modelId) throws IOException;

    /**
     * Returns the IDs of all models that have a persisted spec, sorted alphabetically.
     * Used by {@code ModelLoader} on startup to enumerate models to reload.
     */
    List<String> modelIds() throws IOException;

    /**
     * Removes all persisted data (spec + state) for {@code modelId}.
     * Delegates to {@link StateStore#deleteState} for state removal when the two
     * stores are separate instances.
     */
    void delete(String modelId) throws IOException;

    /** Returns {@code true} when this store writes to durable storage. */
    boolean isEnabled();
}
