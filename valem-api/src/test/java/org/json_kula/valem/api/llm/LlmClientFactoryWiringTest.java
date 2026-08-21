package org.json_kula.valem.api.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.json_kula.valem.core.llm.LlmClient;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * That the provider quirks in {@link LlmClientFactory}'s table actually reach the client it builds.
 *
 * <p>{@link StructuredOutputModeTest} covers what each setting puts on the wire by constructing the
 * client directly. That leaves the wiring itself untested, which is exactly where a provider quirk
 * goes missing: the table can say the right thing while {@code create} forgets to pass it, and the
 * only symptom is a 400 from a live key.
 */
class LlmClientFactoryWiringTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final String COMPLETION_RESPONSE = """
            {"choices":[{"message":{"content":"{\\"ok\\":true}"}}]}""";

    @Test
    void a_groq_client_built_by_the_factory_omits_response_format_when_tools_are_present() throws Exception {
        JsonNode body = captureToolRequest("groq");

        assertThat(body.path("model").asText())
                .as("provider default model").isEqualTo("openai/gpt-oss-120b");
        assertThat(body.has("tools")).isTrue();
        assertThat(body.has("response_format"))
                .as("Groq 400s on response_format + tools; the factory must pass the quirk through")
                .isFalse();
    }

    @Test
    void a_groq_client_built_by_the_factory_still_sends_response_format_without_tools() throws Exception {
        JsonNode body = capturePlainRequest("groq");

        assertThat(body.path("response_format").path("type").asText()).isEqualTo("json_schema");
    }

    @Test
    void an_ordinary_provider_built_by_the_factory_keeps_both() throws Exception {
        JsonNode body = captureToolRequest("openai");

        assertThat(body.has("tools")).isTrue();
        assertThat(body.path("response_format").path("type").asText()).isEqualTo("json_schema");
    }

    // ── Fixture ───────────────────────────────────────────────────────────────

    private JsonNode captureToolRequest(String provider) throws Exception {
        Captured captured = build(provider);
        captured.client.completeWithTools("give me a spec",
                java.util.List.of(new LlmClient.ToolDefinition(
                        "eval_jsonata", "evaluate an expression", MAPPER.createObjectNode())),
                call -> "unused",
                new LlmClient.CompletionOptions(0.0, schema()));
        return MAPPER.readTree(captured.last[0]);
    }

    private JsonNode capturePlainRequest(String provider) throws Exception {
        Captured captured = build(provider);
        captured.client.complete("give me a spec", new LlmClient.CompletionOptions(0.0, schema()));
        return MAPPER.readTree(captured.last[0]);
    }

    private record Captured(LlmClient client, String[] last) {}

    /** Builds through the real factory, with the endpoint redirected at a recording mock server. */
    private Captured build(String provider) {
        String[] last = new String[1];
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo("http://provider.test/chat/completions"))
                .andRespond(request -> {
                    last[0] = new String(((org.springframework.mock.http.client.MockClientHttpRequest)
                            request).getBodyAsBytes());
                    return withSuccess(COMPLETION_RESPONSE, MediaType.APPLICATION_JSON)
                            .createResponse(request);
                });
        LlmClient client = LlmClientFactory.create(provider, "k", "", 1000, "http://provider.test",
                true, 40, StructuredOutputMode.SCHEMA, MAPPER, builder);
        return new Captured(client, last);
    }

    private static JsonNode schema() {
        try {
            return MAPPER.readTree("""
                    {"type":"object","properties":{"id":{"type":"string"}}}""");
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
