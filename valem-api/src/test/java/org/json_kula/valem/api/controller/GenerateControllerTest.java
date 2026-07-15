package org.json_kula.valem.api.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.json_kula.valem.core.llm.LlmClient;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class GenerateControllerTest {

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @MockBean  LlmClient llmClient;

    // ── preview ───────────────────────────────────────────────────────────────

    @Test
    void preview_returns_prompt_containing_model_id_and_description() throws Exception {
        mvc.perform(post("/models/generate/preview")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"modelId":"invoice","domainDescription":"An invoice with line items and total"}
                        """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.prompt").value(containsString("invoice")))
                .andExpect(jsonPath("$.prompt").value(containsString("An invoice with line items and total")))
                .andExpect(jsonPath("$.prompt").value(containsString("Valem")));
    }

    // ── generate — success ────────────────────────────────────────────────────

    @Test
    void generate_returns_200_and_spec_when_llm_produces_valid_json() throws Exception {
        when(llmClient.complete(anyString())).thenReturn("""
                {"id":"invoice","schema":{},"derivations":[],"constraints":[],"actions":[],"metaDerivations":[]}
                """);

        mvc.perform(post("/models/generate")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"modelId":"invoice","prompt":"Generate a spec for an invoice model"}
                        """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.valid", is(true)))
                .andExpect(jsonPath("$.spec.id", is("invoice")));
    }

    @Test
    void generate_strips_markdown_fences_from_llm_response() throws Exception {
        when(llmClient.complete(anyString())).thenReturn("""
                ```json
                {"id":"fenced","schema":{}}
                ```
                """);

        mvc.perform(post("/models/generate")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"modelId":"fenced","prompt":"Generate a spec"}
                        """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.valid", is(true)))
                .andExpect(jsonPath("$.spec.id", is("fenced")));
    }

    @Test
    void generate_builds_prompt_from_description_when_no_prompt_given() throws Exception {
        when(llmClient.complete(anyString())).thenReturn("""
                {"id":"invoice","schema":{}}
                """);

        mvc.perform(post("/models/generate")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"modelId":"invoice","domainDescription":"An invoice with line items","includeView":true}
                        """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.valid", is(true)))
                .andExpect(jsonPath("$.spec.id", is("invoice")));

        // The prompt handed to the LLM is built server-side from the description (never returned).
        org.mockito.ArgumentCaptor<String> promptCaptor = org.mockito.ArgumentCaptor.forClass(String.class);
        org.mockito.Mockito.verify(llmClient).complete(promptCaptor.capture());
        org.assertj.core.api.Assertions.assertThat(promptCaptor.getValue())
                .contains("An invoice with line items");
    }

    @Test
    void generate_returns_400_when_neither_prompt_nor_description_given() throws Exception {
        mvc.perform(post("/models/generate")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"modelId":"invoice"}
                        """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error", containsString("prompt or a domainDescription")));
    }

    // ── generate — validation failures ───────────────────────────────────────

    @Test
    void generate_returns_422_when_llm_response_is_not_json() throws Exception {
        when(llmClient.complete(anyString())).thenReturn("Sorry, I cannot generate that.");

        mvc.perform(post("/models/generate")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"modelId":"bad","prompt":"Generate a spec"}
                        """))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.valid", is(false)))
                .andExpect(jsonPath("$.errors[0].location", is("root")))
                .andExpect(jsonPath("$.rawResponse", is("Sorry, I cannot generate that.")));
    }

    @Test
    void generate_returns_422_when_spec_fails_structural_validation() throws Exception {
        // blank id violates ModelSpecValidator
        when(llmClient.complete(anyString())).thenReturn("""
                {"id":"","schema":{}}
                """);

        mvc.perform(post("/models/generate")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"modelId":"bad","prompt":"Generate a spec"}
                        """))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.valid", is(false)))
                .andExpect(jsonPath("$.errors").isArray());
    }

    // ── generate — LLM error ──────────────────────────────────────────────────

    @Test
    void generate_returns_502_when_llm_throws() throws Exception {
        when(llmClient.complete(anyString()))
                .thenThrow(new LlmClient.LlmException("API key invalid"));

        mvc.perform(post("/models/generate")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"modelId":"x","prompt":"Generate a spec"}
                        """))
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.error", containsString("API key invalid")));
    }

    // ── no LLM configured (Optional.empty path covered by no-@MockBean context) ──

    // The 503 path (llmClient absent) is covered by GenerateControllerNoLlmTest
    // which loads the context without a LlmClient bean.
}
