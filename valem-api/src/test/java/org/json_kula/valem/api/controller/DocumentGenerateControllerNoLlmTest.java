package org.json_kula.valem.api.controller;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Verifies the 503 path when no LLM is configured and {@code LlmConfig} does not load — mirrors
 * {@link GenerateControllerNoLlmTest}. No {@code @MockBean} here, so {@code Optional<DocumentScanner>}
 * and {@code Optional<SpecGenerator>} in the controller resolve to empty.
 */
@SpringBootTest(properties = "valem.llm.api-key=")
@AutoConfigureMockMvc
class DocumentGenerateControllerNoLlmTest {

    @Autowired MockMvc mvc;

    @Test
    void scan_returns_503_when_llm_not_configured() throws Exception {
        mvc.perform(multipart("/models/generate/document/scan")
                        .file(new MockMultipartFile("file", "policy.pdf", "application/pdf", "x".getBytes()))
                        .param("prompt", "what is my coinsurance?"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.error", containsString("not configured")));
    }

    @Test
    void compile_returns_503_when_llm_not_configured() throws Exception {
        mvc.perform(post("/models/generate/document/compile")
                        .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                        .content("""
                                {"modelId":"lease","candidateQuote":"Early termination fee is $200.",
                                 "candidatePage":2,"includeView":false}
                                """))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.error", containsString("not configured")));
    }
}
