package org.json_kula.valem.api.persistence;

import org.json_kula.valem.core.model.ModelSpec;
import org.json_kula.valem.core.state.Snapshot;

import java.util.List;
import java.util.Optional;

/**
 * @deprecated Moved to {@code valem-persistence-memory} module as
 *             {@code org.json_kula.valem.persistence.memory.InMemoryModelStore}.
 *             This copy is retained for backward compatibility only.
 */
@Deprecated
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
