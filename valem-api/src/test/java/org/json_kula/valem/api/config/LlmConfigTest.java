package org.json_kula.valem.api.config;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class LlmConfigTest {

    @Test
    void default_model_is_provider_appropriate() {
        assertThat(LlmConfig.defaultModelFor("anthropic")).isEqualTo("claude-sonnet-4-6");
        assertThat(LlmConfig.defaultModelFor("mistral")).isEqualTo("mistral-large-latest");
        assertThat(LlmConfig.defaultModelFor("openai")).isEqualTo("gpt-4o");
        assertThat(LlmConfig.defaultModelFor("groq")).isEqualTo("llama-3.3-70b-versatile");
        assertThat(LlmConfig.defaultModelFor("gemini")).isEqualTo("gemini-2.0-flash");
    }

    @Test
    void default_model_is_case_insensitive() {
        assertThat(LlmConfig.defaultModelFor("MISTRAL")).isEqualTo("mistral-large-latest");
    }

    @Test
    void unknown_or_null_provider_falls_back_to_anthropic_default() {
        assertThat(LlmConfig.defaultModelFor("nope")).isEqualTo("claude-sonnet-4-6");
        assertThat(LlmConfig.defaultModelFor(null)).isEqualTo("claude-sonnet-4-6");
    }
}
