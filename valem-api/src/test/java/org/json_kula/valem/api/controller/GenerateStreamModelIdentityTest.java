package org.json_kula.valem.api.controller;

import org.json_kula.valem.core.llm.LlmClient;
import org.json_kula.valem.core.llm.LlmDescriptor;
import org.json_kula.valem.core.llm.SpecGenerationPrompt;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * That the SSE progress stream tells the visitor which LLM answered.
 *
 * <p>This is the frame the sandbox's generation log renders, so the assertion is on the wire text
 * rather than on the event object: the sandbox never reads {@code /llm/interactions} (that log is a
 * global ring across sessions and is deliberately unreachable there), which makes this stream the
 * only place a sandbox visitor can learn which model built their spec.
 */
// mock=true only satisfies LlmConfig's condition so the SpecGenerator bean exists; the @MockBean
// below replaces the client it would have built, so no credential and no real call are involved.
@SpringBootTest(properties = "valem.llm.mock=true")
@AutoConfigureMockMvc
class GenerateStreamModelIdentityTest {

    @Autowired MockMvc mvc;
    @MockBean  LlmClient llmClient;

    private static final String VALID_SPEC = """
            {"id":"invoice","schema":{"type":"object","properties":{"total":{"type":"number"}}}}""";

    @Test
    void the_llm_requesting_frame_carries_the_provider_and_model() throws Exception {
        when(llmClient.describe()).thenReturn(new LlmDescriptor("groq", "openai/gpt-oss-120b"));
        stubCompletions();

        String body = streamGeneration();

        assertThat(body).contains("\"type\":\"llm_requesting\"");
        assertThat(body).contains("\"provider\":\"groq\"");
        assertThat(body).contains("\"model\":\"openai/gpt-oss-120b\"");
    }

    @Test
    void the_frame_omits_the_fields_when_the_client_cannot_identify_itself() throws Exception {
        // Map.of would have thrown on the nulls; absent keys are what the UI's optional fields expect.
        when(llmClient.describe()).thenReturn(null);
        stubCompletions();

        String body = streamGeneration();

        assertThat(body).contains("\"type\":\"llm_requesting\"");
        assertThat(body).doesNotContain("\"provider\"");
        assertThat(body).doesNotContain("\"model\"");
    }

    /**
     * Both spec-generation entry points, because which one runs depends on whether the grounding
     * tools are configured — and this test is about the progress frame, not about that choice.
     */
    private void stubCompletions() {
        when(llmClient.complete(any(SpecGenerationPrompt.PromptParts.class), any()))
                .thenReturn(VALID_SPEC);
        when(llmClient.completeWithTools(any(SpecGenerationPrompt.PromptParts.class), anyList(),
                any(), any(), any())).thenReturn(VALID_SPEC);
    }

    private String streamGeneration() throws Exception {
        MvcResult started = mvc.perform(post("/models/generate/stream")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"modelId":"invoice","domainDescription":"An invoice with a total"}"""))
                .andExpect(status().isOk())
                .andReturn();

        return mvc.perform(asyncDispatch(started))
                .andReturn()
                .getResponse()
                .getContentAsString();
    }
}
