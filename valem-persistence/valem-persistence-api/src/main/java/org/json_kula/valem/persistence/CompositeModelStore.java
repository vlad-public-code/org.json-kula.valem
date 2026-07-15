package org.json_kula.valem.persistence;

import com.fasterxml.jackson.databind.node.ArrayNode;
import org.json_kula.valem.core.model.ModelSpec;
import org.json_kula.valem.core.state.Snapshot;

import java.io.IOException;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * A {@link ModelStore} assembled from an independent {@link SpecStore} and {@link StateStore},
 * so spec documents and runtime state can live on <em>different</em> backends (F-T4) — the original
 * motivation for splitting the two interfaces. Spec operations delegate to the spec store; state
 * operations to the state store.
 *
 * <p>{@link #delete} removes from both: it calls {@link SpecStore#delete} (which, for a store whose
 * spec and state share one backend, may already drop state) <em>and</em>
 * {@link StateStore#deleteState} (a no-op if the state is already gone), so a separate-backend pair
 * is fully cleaned up either way.
 *
 * <p>When both halves point at the same backend, the backend's own combined {@code *ModelStore}
 * is interchangeable with this composite; this class exists so any spec/state backend pairing works
 * without a bespoke composite per combination.
 */
public final class CompositeModelStore implements ModelStore {

    private final SpecStore  specStore;
    private final StateStore stateStore;

    public CompositeModelStore(SpecStore specStore, StateStore stateStore) {
        if (specStore == null || stateStore == null) {
            throw new IllegalArgumentException("specStore and stateStore must both be non-null");
        }
        this.specStore  = specStore;
        this.stateStore = stateStore;
    }

    public SpecStore  specStore()  { return specStore; }
    public StateStore stateStore() { return stateStore; }

    // ── SpecStore ───────────────────────────────────────────────────────────────

    @Override
    public void saveSpec(String modelId, ModelSpec spec) throws IOException {
        specStore.saveSpec(modelId, spec);
    }

    @Override
    public Optional<ModelSpec> loadSpec(String modelId) throws IOException {
        return specStore.loadSpec(modelId);
    }

    @Override
    public List<String> modelIds() throws IOException {
        return specStore.modelIds();
    }

    @Override
    public void delete(String modelId) throws IOException {
        specStore.delete(modelId);
        stateStore.deleteState(modelId);
    }

    // ── StateStore ──────────────────────────────────────────────────────────────

    @Override
    public void saveSnapshot(String modelId, Snapshot snapshot) throws IOException {
        stateStore.saveSnapshot(modelId, snapshot);
    }

    @Override
    public void applyMutationPatch(String modelId, ArrayNode patch, Instant mutatedAt)
            throws IOException {
        stateStore.applyMutationPatch(modelId, patch, mutatedAt);
    }

    @Override
    public Optional<Snapshot> loadSnapshot(String modelId) throws IOException {
        return stateStore.loadSnapshot(modelId);
    }

    @Override
    public void deleteState(String modelId) throws IOException {
        stateStore.deleteState(modelId);
    }

    @Override
    public boolean isEnabled() {
        return specStore.isEnabled() || stateStore.isEnabled();
    }
}
