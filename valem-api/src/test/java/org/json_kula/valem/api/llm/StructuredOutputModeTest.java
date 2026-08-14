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
            RestClient.Builder builder = RestClient.builder().baseUrl("http://provider.test");
            MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
            server.expect(requestTo("http://provider.test/chat/completions"))
                    .andRespond(request -> {
                        LAST.set(new String(((org.springframework.mock.http.client.MockClientHttpRequest)
                                request).getBodyAsBytes()));
                        return withSuccess(COMPLETION_RESPONSE, MediaType.APPLICATION_JSON)
                                .createResponse(request);
                    });
            this.client = mode == null
                    ? new OpenAiLlmClient("http://provider.test", "k", "m", 1000, 40,
                            MAPPER, builder.build())
                    : new OpenAiLlmClient("http://provider.test", "k", "m", 1000, 40, mode,
                            MAPPER, builder.build());
        }
    }
}
