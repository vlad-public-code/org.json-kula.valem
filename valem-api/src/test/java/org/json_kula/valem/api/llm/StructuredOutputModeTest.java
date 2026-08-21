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
 * What each structured-output rung actually puts on the wire.
 *
 * <p>Support across the OpenAI-compatible ecosystem is a ladder, not a yes/no, and a provider that
 * rejects the rung it is sent answers 400 — which the sandbox's failure classifier reads as a
 * configuration fault, making a healthy provider look dead. These assertions are on the serialized
 * request body, because that is the thing the provider actually judges.
 */
class StructuredOutputModeTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final String COMPLETION_RESPONSE = """
            {"choices":[{"message":{"content":"{\\"ok\\":true}"}}]}""";

    @Test
    void schema_mode_sends_json_schema_when_a_schema_is_supplied() throws Exception {
        JsonNode body = capture(StructuredOutputMode.SCHEMA, schema());

        assertThat(body.path("response_format").path("type").asText()).isEqualTo("json_schema");
        assertThat(body.path("response_format").path("json_schema").path("schema").has("properties")).isTrue();
    }

    @Test
    void schema_mode_falls_back_to_json_object_without_a_schema() throws Exception {
        JsonNode body = capture(StructuredOutputMode.SCHEMA, null);

        assertThat(body.path("response_format").path("type").asText()).isEqualTo("json_object");
    }

    @Test
    void json_mode_never_sends_a_schema_even_when_one_is_supplied() throws Exception {
        // The common partial support: JSON mode works, schemas 400.
        JsonNode body = capture(StructuredOutputMode.JSON, schema());

        assertThat(body.path("response_format").path("type").asText()).isEqualTo("json_object");
        assertThat(body.path("response_format").has("json_schema")).isFalse();
    }

    @Test
    void none_mode_omits_response_format_entirely() throws Exception {
        // For a provider that rejects the field itself. Safe because the generator recovers JSON
        // from prose; the cost is a higher retry rate, not a broken pipeline.
        JsonNode body = capture(StructuredOutputMode.NONE, schema());

        assertThat(body.has("response_format")).isFalse();
    }

    @Test
    void the_default_constructor_is_schema_mode() throws Exception {
        // Every provider had exactly this behaviour before the mode existed.
        JsonNode body = captureWith(new ClientFixture(null).client, schema());

        assertThat(body.path("response_format").path("type").asText()).isEqualTo("json_schema");
    }

    // ── The second axis: response_format alongside tools ──────────────────────

    @Test
    void a_provider_that_rejects_the_combination_drops_response_format_only_on_tool_requests() throws Exception {
        // Groq answers 400 "json mode cannot be combined with tool/function calling" to any
        // response_format once tools are present. Spec generation calls with tools by default, so
        // this is the very first request a correctly-configured Groq key makes.
        JsonNode withTools = new ClientFixture(StructuredOutputMode.SCHEMA, false).captureToolRequest(schema());
        assertThat(withTools.has("tools")).isTrue();
        assertThat(withTools.has("response_format"))
                .as("response_format must not ride along with tools").isFalse();

        JsonNode withoutTools = new ClientFixture(StructuredOutputMode.SCHEMA, false).capturePlainRequest(schema());
        assertThat(withoutTools.has("tools")).isFalse();
        assertThat(withoutTools.path("response_format").path("type").asText())
                .as("a tools-free request keeps the configured rung").isEqualTo("json_schema");
    }

    @Test
    void an_ordinary_provider_keeps_response_format_alongside_tools() throws Exception {
        // The default, and what every provider did before the flag existed.
        JsonNode withTools = new ClientFixture(StructuredOutputMode.SCHEMA, true).captureToolRequest(schema());

        assertThat(withTools.path("response_format").path("type").asText()).isEqualTo("json_schema");
    }

    @Test
    void the_two_axes_compose_rather_than_override() throws Exception {
        // NONE already omits the field and the tools flag must not resurrect it; JSON must stay
        // json_object on the requests it does survive on.
        assertThat(new ClientFixture(StructuredOutputMode.NONE, false).capturePlainRequest(schema())
                .has("response_format")).isFalse();
        assertThat(new ClientFixture(StructuredOutputMode.JSON, false).capturePlainRequest(schema())
                .path("response_format").path("type").asText()).isEqualTo("json_object");
        assertThat(new ClientFixture(StructuredOutputMode.JSON, false).captureToolRequest(schema())
                .has("response_format")).isFalse();
    }

    @Test
    void only_groq_is_marked_as_rejecting_the_combination() {
        assertThat(LlmClientFactory.combinesResponseFormatWithTools("groq")).isFalse();
        assertThat(LlmClientFactory.combinesResponseFormatWithTools("GROQ")).isFalse();
        assertThat(LlmClientFactory.combinesResponseFormatWithTools("openai")).isTrue();
        assertThat(LlmClientFactory.combinesResponseFormatWithTools("mistral")).isTrue();
        assertThat(LlmClientFactory.combinesResponseFormatWithTools("ollama")).isTrue();
        assertThat(LlmClientFactory.combinesResponseFormatWithTools(null)).isTrue();
    }

    @Test
    void parse_maps_configured_values_and_defaults_safely() {
        assertThat(StructuredOutputMode.parse("json")).isEqualTo(StructuredOutputMode.JSON);
        assertThat(StructuredOutputMode.parse("JSON_OBJECT")).isEqualTo(StructuredOutputMode.JSON);
        assertThat(StructuredOutputMode.parse("none")).isEqualTo(StructuredOutputMode.NONE);
        assertThat(StructuredOutputMode.parse("false")).isEqualTo(StructuredOutputMode.NONE);
        assertThat(StructuredOutputMode.parse("schema")).isEqualTo(StructuredOutputMode.SCHEMA);
        // Unrecognised and absent both keep today's behaviour rather than silently weakening it.
        assertThat(StructuredOutputMode.parse("wat")).isEqualTo(StructuredOutputMode.SCHEMA);
        assertThat(StructuredOutputMode.parse("")).isEqualTo(StructuredOutputMode.SCHEMA);
        assertThat(StructuredOutputMode.parse(null)).isEqualTo(StructuredOutputMode.SCHEMA);
    }

    // ── Fixture ───────────────────────────────────────────────────────────────

    private JsonNode capture(StructuredOutputMode mode, JsonNode responseSchema) throws Exception {
        return captureWith(new ClientFixture(mode).client, responseSchema);
    }

    private JsonNode captureWith(OpenAiLlmClient client, JsonNode responseSchema) throws Exception {
        ClientFixture.LAST.set(null);
        client.complete("give me a spec",
                new LlmClient.CompletionOptions(0.0, responseSchema));
        return MAPPER.readTree(ClientFixture.LAST.get());
    }

    private static JsonNode schema() {
        try {
            return MAPPER.readTree("""
                    {"type":"object","properties":{"id":{"type":"string"}}}""");
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /** An OpenAiLlmClient wired to a mock server that records the request body it was sent. */
    private static final class ClientFixture {
        static final ThreadLocal<String> LAST = new ThreadLocal<>();

        final OpenAiLlmClient client;

        ClientFixture(StructuredOutputMode mode) {
            this(mode, null);
        }

        /** @param responseFormatWithTools null selects the constructor that predates the flag */
        ClientFixture(StructuredOutputMode mode, Boolean responseFormatWithTools) {
            RestClient.Builder builder = RestClient.builder().baseUrl("http://provider.test");
            MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
            server.expect(requestTo("http://provider.test/chat/completions"))
                    .andRespond(request -> {
                        LAST.set(new String(((org.springframework.mock.http.client.MockClientHttpRequest)
                                request).getBodyAsBytes()));
                        return withSuccess(COMPLETION_RESPONSE, MediaType.APPLICATION_JSON)
                                .createResponse(request);
                    });
            if (responseFormatWithTools != null) {
                this.client = new OpenAiLlmClient("http://provider.test", "k", "m", 1000, 40, mode,
                        responseFormatWithTools, MAPPER, builder.build());
            } else if (mode == null) {
                this.client = new OpenAiLlmClient("http://provider.test", "k", "m", 1000, 40,
                        MAPPER, builder.build());
            } else {
                this.client = new OpenAiLlmClient("http://provider.test", "k", "m", 1000, 40, mode,
                        MAPPER, builder.build());
            }
        }

        JsonNode capturePlainRequest(JsonNode responseSchema) throws Exception {
            LAST.set(null);
            client.complete("give me a spec", new LlmClient.CompletionOptions(0.0, responseSchema));
            return MAPPER.readTree(LAST.get());
        }

        /**
         * Drives one turn of the tool loop. The canned response carries no {@code tool_calls}, so
         * the loop terminates after the single request this captures.
         */
        JsonNode captureToolRequest(JsonNode responseSchema) throws Exception {
            LAST.set(null);
            client.completeWithTools("give me a spec",
                    java.util.List.of(new LlmClient.ToolDefinition(
                            "eval_jsonata", "evaluate an expression", MAPPER.createObjectNode())),
                    call -> "unused",
                    new LlmClient.CompletionOptions(0.0, responseSchema));
            return MAPPER.readTree(LAST.get());
        }
    }
}
