package org.json_kula.valem.core.llm;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.json_kula.valem.core.blob.InMemoryBlobStore;
import org.json_kula.valem.core.engine.ConstraintEvaluator;
import org.json_kula.valem.core.engine.ModelRuntime;
import org.json_kula.valem.core.graph.CompiledModel;
import org.json_kula.valem.core.graph.ModelSpecCompiler;
import org.json_kula.valem.core.model.ModelSpec;
import org.json_kula.valem.core.state.ModelState;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Regression guard for the initial-state gate, using a REAL LLM-generated spec that zero-seeds a
 * field its own rollback constraint requires positive. {@code ModelRuntime.initialize()} — the exact
 * check {@code ModelService.create()} and {@code SpecGenerator}'s gate both run — must reject it, so
 * the generator re-prompts instead of returning a spec that would 409 at create.
 */
class InitialStateGateReproTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private ModelSpec load(String resource) throws Exception {
        try (var in = getClass().getClassLoader().getResourceAsStream(resource)) {
            assertThat(in).as("test resource: " + resource).isNotNull();
            return MAPPER.readValue(in, ModelSpec.class);
        }
    }

    @Test
    void initialize_rejects_a_zero_seed_that_violates_a_positive_rollback_constraint() throws Exception {
        ModelSpec spec = load("gate/house-heating-zero-seed.json");
        CompiledModel model = ModelSpecCompiler.compile(spec);
        ModelRuntime rt = new ModelRuntime(model, new ModelState(model, new InMemoryBlobStore()));

        assertThatThrownBy(rt::initialize)
                .isInstanceOf(ConstraintEvaluator.ConstraintViolationException.class)
                .hasMessageContaining("Floor area must be positive");
    }
}
