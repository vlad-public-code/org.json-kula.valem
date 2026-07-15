package org.json_kula.valem.api.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.json_kula.valem.core.llm.LlmClient.CompletionOptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.mock.http.client.MockClientHttpRequest;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class OpenAiLlmClientTest {

    private static final String ENDPOINT = "https://api.example.com/v1/chat/completions";
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String OK_RESPONSE = """
            {"choices":[{"message":{"role":"assistant","content":"{}"},"finish_reason":"stop"}]}""";

    private MockRestServiceServer mockServer;
    private OpenAiLlmClient client;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder();
        mockServer = MockRestServiceServer.bindTo(builder).build();
        client = new OpenAiLlmClient("https://api.example.com/v1", "k", "m", 256, MAPPER, builder.build());
    }

    @Test
    void uses_json_object_response_format_by_default() throws Exception {
        mockServer.expect(requestTo(ENDPOINT)).andExpect(req -> {
            JsonNode body = MAPPER.readTree(((MockClientHttpRequest) req).getBodyAsString());
            assertThat(body.at("/response_format/type").asText()).isEqualTo("json_object");
        }).andRespond(withSuccess(OK_RESPONSE, MediaType.APPLICATION_JSON));

        client.complete("hi");
        mockServer.verify();
    }

    @Test
    void uses_json_schema_response_format_when_schema_supplied() throws Exception {
        JsonNode schema = MAPPER.readTree(
                "{\"type\":\"object\",\"properties\":{\"id\":{\"type\":\"string\"}}}");
        mockServer.expect(requestTo(ENDPOINT)).andExpect(req -> {
            JsonNode body = MAPPER.readTree(((MockClientHttpRequest) req).getBodyAsString());
            assertThat(body.at("/response_format/type").asText()).isEqualTo("json_schema");
            assertThat(body.at("/response_format/json_schema/name").asText()).isEqualTo("valem_spec");
            assertThat(body.at("/response_format/json_schema/strict").asBoolean()).isFalse();
            assertThat(body.at("/response_format/json_schema/schema/properties/id/type").asText())
                    .isEqualTo("string");
            assertThat(body.at("/temperature").asDouble()).isEqualTo(0.0);
        }).andRespond(withSuccess(OK_RESPONSE, MediaType.APPLICATION_JSON));

        client.complete("hi", new CompletionOptions(0.0, schema));
        mockServer.verify();
    }
}
