package org.json_kula.valem.persistence.postgres;

import com.fasterxml.jackson.databind.node.ArrayNode;
import org.json_kula.valem.core.model.ModelSpec;
import org.json_kula.valem.core.state.Snapshot;
import org.json_kula.valem.persistence.ModelStore;

import java.io.IOException;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Composite {@link ModelStore} that delegates spec operations to {@link PostgresSpecStore}
 * and state operations to {@link PostgresStateStore}.
 */
public final class PostgresModelStore implements ModelStore {

    private final PostgresSpecStore  specStore;
    private final PostgresStateStore stateStore;

    public PostgresModelStore(PostgresSpecStore specStore, PostgresStateStore stateStore) {
        this.specStore  = specStore;
        this.stateStore = stateStore;
    }

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
        stateStore.deleteState(modelId);
        specStore.delete(modelId);
    }

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
    public boolean isEnabled() { return true; }
}
