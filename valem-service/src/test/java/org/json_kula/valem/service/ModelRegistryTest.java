package org.json_kula.valem.service;

import org.json_kula.valem.core.blob.InMemoryBlobStore;
import org.json_kula.valem.core.engine.ModelRuntime;
import org.json_kula.valem.core.graph.CompiledModel;
import org.json_kula.valem.core.graph.ModelSpecCompiler;
import org.json_kula.valem.core.model.ModelSpec;
import org.json_kula.valem.core.state.ModelState;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ModelRegistryTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private ModelRegistry registry;

    @BeforeEach
    void setUp() {
        registry = new ModelRegistry();
    }

    @Test
    void register_and_find_returns_runtime() throws Exception {
        ModelRuntime rt = makeRuntime("reg-find");
        registry.register("reg-find", rt);
        assertThat(registry.find("reg-find")).contains(rt);
    }

    @Test
    void find_missing_returns_empty() {
        assertThat(registry.find("no-such-id")).isEmpty();
    }

    @Test
    void remove_registered_returns_true() throws Exception {
        registry.register("to-remove", makeRuntime("to-remove"));
        assertThat(registry.remove("to-remove")).isTrue();
        assertThat(registry.find("to-remove")).isEmpty();
    }

    @Test
    void remove_missing_returns_false() {
        assertThat(registry.remove("ghost")).isFalse();
    }

    @Test
    void contains_reflects_registration_state() throws Exception {
        assertThat(registry.contains("x")).isFalse();
        registry.register("x", makeRuntime("x"));
        assertThat(registry.contains("x")).isTrue();
        registry.remove("x");
        assertThat(registry.contains("x")).isFalse();
    }

    @Test
    void allIds_returns_sorted_list() throws Exception {
        registry.register("bravo",   makeRuntime("bravo"));
        registry.register("alpha",   makeRuntime("alpha"));
        registry.register("charlie", makeRuntime("charlie"));
        assertThat(registry.allIds()).containsExactly("alpha", "bravo", "charlie");
    }

    private static ModelRuntime makeRuntime(String id) throws Exception {
        ModelSpec spec = MAPPER.readValue(
                "{\"id\":\"" + id + "\",\"schema\":{}}",
                ModelSpec.class);
        CompiledModel model = ModelSpecCompiler.compile(spec);
        ModelState    state = new ModelState(model, new InMemoryBlobStore());
        return new ModelRuntime(model, state);
    }
}
