package org.json_kula.valem.api.llm;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.json_kula.valem.core.llm.LlmClient;
import org.json_kula.valem.core.llm.SpecGenerationPrompt;
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
    void promptParts_sends_system_block_array_with_cache_control_and_user_only_message() throws Exception {
        mockServer.expect(requestTo(ENDPOINT))
                .andExpect(req -> {
                    String body = ((MockClientHttpRequest) req).getBodyAsString();
                    // system is a block array carrying an ephemeral cache breakpoint
                    assertThat(body).contains("\"system\":[{");
                    assertThat(body).contains("\"cache_control\":{\"type\":\"ephemeral\"}");
                    assertThat(body).contains("SYSTEM RULES HERE");
                    // the user message contains only the user part, not the system context
                    int userIdx = body.indexOf("USER TASK HERE");
                    int roleIdx = body.indexOf("\"role\":\"user\"");
                    assertThat(userIdx).isGreaterThan(0);
                    assertThat(roleIdx).isGreaterThan(0);
                    // the system rules must not appear inside the messages array (only in "system")
                    assertThat(body.indexOf("SYSTEM RULES HERE")).isLessThan(roleIdx);
                })
                .andRespond(withSuccess("""
                        {"content":[{"type":"text","text":"ok"}],"stop_reason":"end_turn"}
                        """, MediaType.APPLICATION_JSON));

        var parts = new SpecGenerationPrompt.PromptParts("SYSTEM RULES HERE", "USER TASK HERE");
        client.complete(parts, new LlmClient.CompletionOptions(null, null));
        mockServer.verify();
    }

    @Test
    void promptParts_with_session_context_send_two_cached_system_blocks_before_user() throws Exception {
        mockServer.expect(requestTo(ENDPOINT))
                .andExpect(req -> {
                    String body = ((MockClientHttpRequest) req).getBodyAsString();
                    // two system blocks, each carrying its own ephemeral cache breakpoint
                    assertThat(body).contains("\"system\":[{");
                    int cacheBreakpoints = body.split("\"cache_control\":\\{\"type\":\"ephemeral\"\\}", -1).length - 1;
                    assertThat(cacheBreakpoints).isEqualTo(2);
                    assertThat(body).contains("GLOBAL SYSTEM").contains("SESSION SPEC");
                    // both stable tiers precede the user turn; the volatile user text is in the message
                    int roleIdx = body.indexOf("\"role\":\"user\"");
                    assertThat(body.indexOf("SESSION SPEC")).isLessThan(roleIdx);
                    assertThat(body.indexOf("VOLATILE USER")).isGreaterThan(roleIdx);
                })
                .andRespond(withSuccess("""
                        {"content":[{"type":"text","text":"ok"}],"stop_reason":"end_turn"}
                        """, MediaType.APPLICATION_JSON));

        var parts = new SpecGenerationPrompt.PromptParts("GLOBAL SYSTEM", "SESSION SPEC", "VOLATILE USER");
        client.complete(parts, new LlmClient.CompletionOptions(null, null));
        mockServer.verify();
    }

    @Test
    void session_context_with_cache_disabled_concatenates_into_a_single_plain_system() throws Exception {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        AnthropicLlmClient noCache =
                new AnthropicLlmClient("test-key", "claude-sonnet-4-6", 256, false, MAPPER, builder.build());
        server.expect(requestTo(ENDPOINT))
                .andExpect(req -> {
                    String body = ((MockClientHttpRequest) req).getBodyAsString();
                    assertThat(body).contains("\"system\":\"SYS\\n\\nCTX\"");
                    assertThat(body).doesNotContain("cache_control");
                })
                .andRespond(withSuccess("""
                        {"content":[{"type":"text","text":"ok"}],"stop_reason":"end_turn"}
                        """, MediaType.APPLICATION_JSON));

        noCache.complete(new SpecGenerationPrompt.PromptParts("SYS", "CTX", "U"),
                new LlmClient.CompletionOptions(null, null));
        server.verify();
    }

    @Test
    void promptParts_with_cache_disabled_sends_plain_string_system() throws Exception {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        AnthropicLlmClient noCache =
                new AnthropicLlmClient("test-key", "claude-sonnet-4-6", 256, false, MAPPER, builder.build());
        server.expect(requestTo(ENDPOINT))
                .andExpect(req -> {
                    String body = ((MockClientHttpRequest) req).getBodyAsString();
                    assertThat(body).contains("\"system\":\"SYS\"");
                    assertThat(body).doesNotContain("cache_control");
                })
                .andRespond(withSuccess("""
                        {"content":[{"type":"text","text":"ok"}],"stop_reason":"end_turn"}
                        """, MediaType.APPLICATION_JSON));

        noCache.complete(new SpecGenerationPrompt.PromptParts("SYS", "U"),
                new LlmClient.CompletionOptions(null, null));
        server.verify();
    }

    @Test
    void complete_concatenates_multiple_text_blocks() throws Exception {
        mockServer.expect(requestTo(ENDPOINT))
                .andRespond(withSuccess("""
                        {"content":[{"type":"text","text":"preamble "},
                                    {"type":"text","text":"{\\"spec\\":true}"}],
                         "stop_reason":"end_turn"}
                        """, MediaType.APPLICATION_JSON));

        String result = client.complete("give me two blocks");
        assertThat(result).isEqualTo("preamble {\"spec\":true}");
        mockServer.verify();
    }

    @Test
    void structured_output_forces_submit_spec_and_returns_its_input() throws Exception {
        mockServer.expect(requestTo(ENDPOINT))
                .andExpect(req -> {
                    String body = ((MockClientHttpRequest) req).getBodyAsString();
                    assertThat(body).contains("\"tool_choice\":{\"type\":\"tool\",\"name\":\"submit_spec\"}");
                    assertThat(body).contains("\"name\":\"submit_spec\"");
                })
                .andRespond(withSuccess("""
                        {"content":[{"type":"tool_use","name":"submit_spec","id":"t1",
                                     "input":{"id":"x","schema":{}}}],
                         "stop_reason":"tool_use"}
                        """, MediaType.APPLICATION_JSON));

        var schema = MAPPER.readTree("{\"type\":\"object\"}");
        var opts   = new LlmClient.CompletionOptions(null, schema);
        String result = client.complete(new SpecGenerationPrompt.PromptParts("SYS", "make a spec"), opts);

        assertThat(result).isEqualTo("{\"id\":\"x\",\"schema\":{}}");
        mockServer.verify();
    }

    @Test
    void structured_output_with_tools_treats_submit_spec_tool_use_as_terminal() throws Exception {
        mockServer.expect(requestTo(ENDPOINT))
                .andExpect(req -> {
                    String body = ((MockClientHttpRequest) req).getBodyAsString();
                    // submit_spec offered alongside the grounding tool, but NOT forced
                    assertThat(body).contains("\"name\":\"submit_spec\"");
                    assertThat(body).doesNotContain("tool_choice");
                })
                .andRespond(withSuccess("""
                        {"content":[{"type":"tool_use","name":"submit_spec","id":"t9",
                                     "input":{"id":"y"}}],
                         "stop_reason":"tool_use"}
                        """, MediaType.APPLICATION_JSON));

        var schema  = MAPPER.readTree("{\"type\":\"object\"}");
        var opts    = new LlmClient.CompletionOptions(null, schema);
        var toolDef = new LlmClient.ToolDefinition("web_search", "search",
                MAPPER.readTree("{\"type\":\"object\"}"));
        String result = client.completeWithTools("find it", java.util.List.of(toolDef),
                call -> "should-not-run", opts, e -> {});

        assertThat(result).isEqualTo("{\"id\":\"y\"}");
        mockServer.verify();
    }

    @Test
    void tool_loop_ceiling_forces_a_final_request_without_tools() throws Exception {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        // cap = 1: one tool round-trip, then the forced final answer.
        AnthropicLlmClient capped =
                new AnthropicLlmClient("k", "claude-sonnet-4-6", 256, true, 1, MAPPER, builder.build());

        // iteration 1: model asks for a tool
        server.expect(requestTo(ENDPOINT)).andExpect(method(HttpMethod.POST))
                .andRespond(withSuccess("""
                        {"content":[{"type":"tool_use","name":"web_search","id":"t1","input":{"query":"x"}}],
                         "stop_reason":"tool_use"}
                        """, MediaType.APPLICATION_JSON));
        // iteration 2 (> cap): final request must withhold tools and demand the answer
        server.expect(requestTo(ENDPOINT))
                .andExpect(req -> {
                    String body = ((MockClientHttpRequest) req).getBodyAsString();
                    assertThat(body).doesNotContain("\"tools\"");
                    assertThat(body).contains("produce the final JSON now");
                })
                .andRespond(withSuccess("""
                        {"content":[{"type":"text","text":"FINAL"}],"stop_reason":"end_turn"}
                        """, MediaType.APPLICATION_JSON));

        var tool = new LlmClient.ToolDefinition("web_search", "s", MAPPER.readTree("{\"type\":\"object\"}"));
        String out = capped.completeWithTools("go", java.util.List.of(tool),
                call -> "tool-result", new LlmClient.CompletionOptions(null, null), e -> {});

        assertThat(out).isEqualTo("FINAL");
        server.verify();
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
