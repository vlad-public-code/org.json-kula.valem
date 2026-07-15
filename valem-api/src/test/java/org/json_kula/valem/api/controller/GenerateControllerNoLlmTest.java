package org.json_kula.valem.api.controller;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Verifies the 503 path when no ANTHROPIC_API_KEY is set and LlmConfig is not loaded.
 * No @MockBean here so Optional&lt;LlmClient&gt; in the controller resolves to empty.
 */
// Explicitly blank out the API key so LlmConfig's @ConditionalOnProperty does not activate,
// even if ANTHROPIC_API_KEY happens to be set in the environment.
@SpringBootTest(properties = "valem.llm.api-key=")
@AutoConfigureMockMvc
class GenerateControllerNoLlmTest {

    @Autowired MockMvc mvc;

    @Test
    void preview_still_works_without_llm() throws Exception {
        mvc.perform(post("/models/generate/preview")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"modelId":"m","domainDescription":"A simple model"}
                        """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.prompt").value(containsString("m")));
    }

    @Test
    void generate_returns_503_when_llm_not_configured() throws Exception {
        mvc.perform(post("/models/generate")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"modelId":"m","prompt":"Generate a spec"}
                        """))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.error", containsString("not configured")));
    }
}
