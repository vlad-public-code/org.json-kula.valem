package org.json_kula.valem.api.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.json_kula.valem.core.llm.LlmClient;
import org.json_kula.valem.core.llm.LlmDescriptor;
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
    void a_gemini_client_built_by_the_factory_omits_response_format_when_tools_are_present()
            throws Exception {
        // Gemini's OpenAI-compatible endpoint answers 400 "Function calling with a response mime type:
        // 'application/json' is unsupported" — the same mutual exclusion Groq has, different wording.
        JsonNode body = captureToolRequest("gemini");

        assertThat(body.has("tools")).isTrue();
        assertThat(body.has("response_format")).isFalse();
    }

    @Test
    void an_openrouter_client_built_by_the_factory_omits_response_format_when_tools_are_present()
            throws Exception {
        // OpenRouter is the dangerous member of this table: unlike Groq and Gemini, which answer 400,
        // it ACCEPTS response_format alongside tools and then silently never calls a tool. Measured
        // with a prompt that demanded one: with response_format → finish_reason "stop", zero
        // tool_calls; without it → finish_reason "tool_calls". Nothing errors, so a whole generation
        // runs with eval_jsonata and get_domain_guidance quietly unavailable. Only a request-shape
        // assertion like this one catches that — a green end-to-end run will not.
        JsonNode body = captureToolRequest("openrouter");

        assertThat(body.has("tools")).isTrue();
        assertThat(body.has("response_format")).isFalse();
    }

    @Test
    void every_provider_that_cannot_combine_response_format_with_tools_is_listed() {
        // One place to see the whole rule, so adding a provider forces a decision about it.
        assertThat(LlmClientFactory.combinesResponseFormatWithTools("groq")).isFalse();
        assertThat(LlmClientFactory.combinesResponseFormatWithTools("gemini")).isFalse();
        assertThat(LlmClientFactory.combinesResponseFormatWithTools("openrouter")).isFalse();
        assertThat(LlmClientFactory.combinesResponseFormatWithTools("mistral")).isTrue();
        assertThat(LlmClientFactory.combinesResponseFormatWithTools("openai")).isTrue();
        assertThat(LlmClientFactory.combinesResponseFormatWithTools("anthropic")).isTrue();
    }

    @Test
    void the_openrouter_default_model_is_one_openrouter_still_lists() {
        // anthropic/claude-3.7-sonnet used to be the default and is now delisted. Like the Gemini
        // check below, this is a re-check reminder rather than a guarantee — a test cannot know what
        // a provider retired this morning.
        assertThat(LlmClientFactory.defaultModelFor("openrouter"))
                .isEqualTo("nvidia/nemotron-3-super-120b-a12b:free");
    }

    @Test
    void the_gemini_default_model_is_one_google_still_serves() {
        // gemini-2.0-flash used to be the default here and now answers 404 "no longer available".
        // A dated id has a shelf life; this test is the reminder to re-check it, not a guarantee.
        assertThat(LlmClientFactory.defaultModelFor("gemini")).isEqualTo("gemini-2.5-flash");
    }

    @Test
    void the_built_client_knows_which_provider_it_points_at() {
        // OpenAiLlmClient speaks the same wire format to a dozen back ends, so only the factory can
        // tell it which one it is — and without that the generation log cannot name the model.
        assertThat(build("groq").client.describe())
                .isEqualTo(new LlmDescriptor("groq", "openai/gpt-oss-120b"));
        assertThat(build("mistral").client.describe())
                .isEqualTo(new LlmDescriptor("mistral", "mistral-large-latest"));
        assertThat(build("anthropic").client.describe())
                .isEqualTo(new LlmDescriptor("anthropic", "claude-sonnet-4-6"));
    }

    @Test
    void a_client_built_without_a_provider_name_says_so_rather_than_guessing() {
        OpenAiLlmClient unnamed = new OpenAiLlmClient("http://provider.test", "k", "m", 100, 40,
                MAPPER, RestClient.builder().build());

        assertThat(unnamed.describe()).isEqualTo(new LlmDescriptor("openai-compatible", "m"));
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
