package org.json_kula.valem.api.llm;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.json_kula.valem.core.llm.LlmClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.mock.http.client.MockClientHttpRequest;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.http.HttpStatus.UNAUTHORIZED;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class AnthropicLlmClientTest {

    private static final String ENDPOINT = "https://api.anthropic.com/v1/messages";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private MockRestServiceServer mockServer;
    private AnthropicLlmClient client;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder();
        mockServer = MockRestServiceServer.bindTo(builder).build();
        client = new AnthropicLlmClient("test-key", "claude-sonnet-4-6", 256, MAPPER, builder.build());
    }

    @Test
    void complete_returns_text_from_response() throws Exception {
        mockServer.expect(requestTo(ENDPOINT))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("x-api-key", "test-key"))
                .andExpect(header("anthropic-version", "2023-06-01"))
                .andRespond(withSuccess("""
                        {"content":[{"type":"text","text":"generated spec JSON"}],"stop_reason":"end_turn"}
                        """, MediaType.APPLICATION_JSON));

        String result = client.complete("build me a spec");

        assertThat(result).isEqualTo("generated spec JSON");
        mockServer.verify();
    }

    @Test
    void complete_sends_prompt_as_user_message_with_correct_model() throws Exception {
        mockServer.expect(requestTo(ENDPOINT))
                .andExpect(req -> {
                    String body = ((MockClientHttpRequest) req).getBodyAsString();
                    assertThat(body).contains("\"role\":\"user\"");
                    assertThat(body).contains("my custom prompt text");
                    assertThat(body).contains("\"model\":\"claude-sonnet-4-6\"");
                    assertThat(body).contains("\"max_tokens\":256");
                })
                .andRespond(withSuccess("""
                        {"content":[{"type":"text","text":"ok"}],"stop_reason":"end_turn"}
                        """, MediaType.APPLICATION_JSON));

        client.complete("my custom prompt text");
        mockServer.verify();
    }

    @Test
    void complete_throws_llm_exception_on_http_error() {
        mockServer.expect(requestTo(ENDPOINT))
                .andRespond(withStatus(UNAUTHORIZED));

        assertThatThrownBy(() -> client.complete("prompt"))
                .isInstanceOf(LlmClient.LlmException.class)
                .hasMessageContaining("Anthropic API call failed");
    }

    @Test
    void complete_throws_llm_exception_on_malformed_json_response() {
        mockServer.expect(requestTo(ENDPOINT))
                .andRespond(withSuccess("not valid json at all", MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> client.complete("prompt"))
                .isInstanceOf(LlmClient.LlmException.class);
    }

    @Test
    void complete_throws_llm_exception_when_content_array_is_missing() {
        mockServer.expect(requestTo(ENDPOINT))
                .andRespond(withSuccess("""
                        {"error":{"type":"authentication_error","message":"Invalid API key"}}
                        """, MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> client.complete("prompt"))
                .isInstanceOf(LlmClient.LlmException.class);
    }
}
