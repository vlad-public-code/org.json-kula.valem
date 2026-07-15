package org.json_kula.valem.api.registry;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import org.json_kula.valem.core.blob.InMemoryBlobStore;
import org.json_kula.valem.core.model.ModelSpec;
import org.json_kula.valem.core.state.Snapshot;
import org.json_kula.valem.persistence.ModelStore;
import org.json_kula.valem.service.ModelRegistry;
import org.json_kula.valem.service.ModelService;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class ModelLoaderTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static ModelSpec spec(String id) throws Exception {
        return MAPPER.readValue("{\"id\":\"" + id + "\",\"schema\":{}}", ModelSpec.class);
    }

    private ModelService newService() {
        return new ModelService(new ModelRegistry(), new InMemoryBlobStore());
    }

    @Test
    void state_load_failure_degrades_to_spec_only_instead_of_dropping_the_model() {
        ModelService service = newService();

        // "good" loads cleanly; "badstate" has a corrupt mutation log so loadSnapshot throws.
        ModelStore store = new StubStore() {
            @Override public List<String> modelIds() { return List.of("badstate", "good"); }
            @Override public Optional<ModelSpec> loadSpec(String id) throws IOException {
                try { return Optional.of(spec(id)); }
                catch (Exception e) { throw new IOException(e); }
            }
            @Override public Optional<Snapshot> loadSnapshot(String id) throws IOException {
                if (id.equals("badstate")) throw new IOException("corrupt mutation log");
                return Optional.empty();
            }
        };

        new ModelLoader(store, service).run(null);

        // Both models are registered — the corrupt-state model survived as spec-only (degraded),
        // it was NOT silently dropped.
        assertThat(service.listModels()).containsExactlyInAnyOrder("badstate", "good");
    }

    @Test
    void missing_spec_skips_that_model_only() {
        ModelService service = newService();

        ModelStore store = new StubStore() {
            @Override public List<String> modelIds() { return List.of("nospec", "good"); }
            @Override public Optional<ModelSpec> loadSpec(String id) throws IOException {
                if (id.equals("nospec")) return Optional.empty();
                try { return Optional.of(spec(id)); }
                catch (Exception e) { throw new IOException(e); }
            }
            @Override public Optional<Snapshot> loadSnapshot(String id) { return Optional.empty(); }
        };

        new ModelLoader(store, service).run(null);

        assertThat(service.listModels()).containsExactly("good");
    }

    @Test
    void disabled_store_loads_nothing() {
        ModelService service = newService();
        ModelStore store = new StubStore() {
            @Override public boolean isEnabled() { return false; }
            @Override public List<String> modelIds() { throw new AssertionError("must not be called"); }
        };
        new ModelLoader(store, service).run(null);
        assertThat(service.listModels()).isEmpty();
    }

    /** Minimal {@link ModelStore} with no-op writes; tests override only the reads they exercise. */
    private static class StubStore implements ModelStore {
        @Override public void saveSpec(String id, ModelSpec spec) {}
        @Override public Optional<ModelSpec> loadSpec(String id) throws IOException { return Optional.empty(); }
        @Override public List<String> modelIds() throws IOException { return List.of(); }
        @Override public void delete(String id) {}
        @Override public void saveSnapshot(String id, Snapshot snapshot) {}
        @Override public void applyMutationPatch(String id, ArrayNode patch, Instant mutatedAt) {}
        @Override public Optional<Snapshot> loadSnapshot(String id) throws IOException { return Optional.empty(); }
        @Override public void deleteState(String id) {}
        @Override public boolean isEnabled() { return true; }
    }
}
