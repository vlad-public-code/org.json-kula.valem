package org.json_kula.valem.core.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Deterministic wiring check (no LLM call): the generation prompt and the structured-output schema
 * both describe the {@code effects} section and its four executors, so a model can generate effects.
 */
class SpecGenerationEffectsWiringTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void initial_prompt_describes_effects_and_executors() {
        String prompt = SpecGenerationPrompt.initialPrompt("m", "some domain");
        assertThat(prompt).contains("\"effects\"");
        assertThat(prompt).contains("\"executor\"");
        assertThat(prompt).contains("llm").contains("timer").contains("caller").contains("server");
        assertThat(prompt).contains("prompt").contains("afterMs");
    }

    @Test
    void model_spec_schema_includes_effects_with_executor_enum() {
        JsonNode schema = SpecGenerationSchema.modelSpec(MAPPER);
        JsonNode effects = schema.at("/properties/effects");
        assertThat(effects.path("type").asText()).isEqualTo("array");

        JsonNode enumVals = effects.at("/items/properties/executor/enum");
        assertThat(enumVals.isArray()).isTrue();
        assertThat(enumVals).extracting(JsonNode::asText)
                .containsExactlyInAnyOrder("caller", "server", "llm", "timer");
    }

    @Test
    void evolution_schema_includes_effect_upsert_and_remove() {
        JsonNode schema = SpecGenerationSchema.specEvolution(MAPPER);
        assertThat(schema.at("/properties/upsertEffects/type").asText()).isEqualTo("array");
        assertThat(schema.at("/properties/removeEffects/type").asText()).isEqualTo("array");
    }
}
