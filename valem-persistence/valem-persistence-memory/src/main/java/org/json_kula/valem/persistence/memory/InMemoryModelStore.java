package org.json_kula.valem.persistence.memory;

import org.json_kula.valem.core.model.ModelSpec;
import org.json_kula.valem.core.state.Snapshot;
import org.json_kula.valem.persistence.ModelStore;

import java.util.List;
import java.util.Optional;

/**
 * No-op {@link ModelStore} — all operations are silent no-ops.
 * {@code isEnabled()} returns {@code false}. Suitable for tests and ephemeral deployments.
 */
public final class InMemoryModelStore implements ModelStore {

    @Override public void saveSpec(String modelId, ModelSpec spec) {}
    @Override public void saveSnapshot(String modelId, Snapshot snapshot) {}
    @Override public Optional<ModelSpec>  loadSpec(String modelId)     { return Optional.empty(); }
    @Override public Optional<Snapshot>   loadSnapshot(String modelId) { return Optional.empty(); }
    @Override public List<String>         modelIds()                   { return List.of(); }
    @Override public void                 delete(String modelId)       {}
    @Override public void                 deleteState(String modelId)  {}
    @Override public boolean              isEnabled()                  { return false; }
}
