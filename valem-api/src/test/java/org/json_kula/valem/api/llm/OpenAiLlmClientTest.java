package org.json_kula.valem.api.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.json_kula.valem.core.llm.LlmClient;
import org.json_kula.valem.core.llm.LlmClient.CompletionOptions;
import org.json_kula.valem.core.llm.SpecGenerationPrompt;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.mock.http.client.MockClientHttpRequest;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
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

    @Test
    void prompt_parts_send_system_and_session_context_as_two_system_messages_before_user()
            throws Exception {
        mockServer.expect(requestTo(ENDPOINT)).andExpect(req -> {
            JsonNode body = MAPPER.readTree(((MockClientHttpRequest) req).getBodyAsString());
            JsonNode messages = body.at("/messages");
            // system (global) + sessionContext (current spec) both as system roles, before the user turn
            assertThat(messages.get(0).at("/role").asText()).isEqualTo("system");
            assertThat(messages.get(0).at("/content").asText()).isEqualTo("GLOBAL SYSTEM");
            assertThat(messages.get(1).at("/role").asText()).isEqualTo("system");
            assertThat(messages.get(1).at("/content").asText()).isEqualTo("SESSION SPEC");
            assertThat(messages.get(2).at("/role").asText()).isEqualTo("user");
            assertThat(messages.get(2).at("/content").asText()).isEqualTo("VOLATILE USER");
        }).andRespond(withSuccess(OK_RESPONSE, MediaType.APPLICATION_JSON));

        client.complete(new SpecGenerationPrompt.PromptParts("GLOBAL SYSTEM", "SESSION SPEC", "VOLATILE USER"),
                new CompletionOptions(null, null));
        mockServer.verify();
    }

    @Test
    void prompt_parts_without_session_context_send_single_system_message() throws Exception {
        mockServer.expect(requestTo(ENDPOINT)).andExpect(req -> {
            JsonNode body = MAPPER.readTree(((MockClientHttpRequest) req).getBodyAsString());
            JsonNode messages = body.at("/messages");
            assertThat(messages.size()).isEqualTo(2);   // one system + one user, no empty session block
            assertThat(messages.get(0).at("/role").asText()).isEqualTo("system");
            assertThat(messages.get(1).at("/role").asText()).isEqualTo("user");
        }).andRespond(withSuccess(OK_RESPONSE, MediaType.APPLICATION_JSON));

        client.complete(new SpecGenerationPrompt.PromptParts("SYS", "USER"), new CompletionOptions(null, null));
        mockServer.verify();
    }

    // ── reasoning_effort ──────────────────────────────────────────────────────

    @Test
    void reasoning_effort_is_omitted_unless_configured() throws Exception {
        // A provider that does not know the field answers 400, which looks exactly like a dead key —
        // so an unconfigured client must not send it at all, not send an empty value.
        mockServer.expect(requestTo(ENDPOINT)).andExpect(req -> {
            JsonNode body = MAPPER.readTree(((MockClientHttpRequest) req).getBodyAsString());
            assertThat(body.has("reasoning_effort")).isFalse();
        }).andRespond(withSuccess(OK_RESPONSE, MediaType.APPLICATION_JSON));

        client.complete("hi");
        mockServer.verify();
    }

    @Test
    void configured_reasoning_effort_is_sent_on_every_request_shape() throws Exception {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        OpenAiLlmClient reasoning = new OpenAiLlmClient("gemini", "https://api.example.com/v1", "k",
                "m", 256, 40, StructuredOutputMode.SCHEMA, true, "low", MAPPER, builder.build());

        // Both the plain completion and the tool-loop request carry it: a reasoning model spends its
        // budget thinking on every call, not only the ones without tools.
        for (int i = 0; i < 2; i++) {
            server.expect(requestTo(ENDPOINT)).andExpect(req -> {
                JsonNode body = MAPPER.readTree(((MockClientHttpRequest) req).getBodyAsString());
                assertThat(body.path("reasoning_effort").asText()).isEqualTo("low");
            }).andRespond(withSuccess(OK_RESPONSE, MediaType.APPLICATION_JSON));
        }

        reasoning.complete("hi");
        reasoning.completeWithTools("hi",
                List.of(new LlmClient.ToolDefinition("eval_jsonata", "evaluate", MAPPER.createObjectNode())),
                call -> "2");
        server.verify();
    }

    @Test
    void a_transient_provider_error_returned_with_http_200_is_retried() {
        // OpenRouter answers 200 with {"error":{...,"code":502}} for an upstream outage. Because the
        // transport succeeded, LlmRetry never saw it and one flaky moment aborted a whole generation.
        mockServer.expect(requestTo(ENDPOINT)).andRespond(withSuccess(
                "{\"error\":{\"message\":\"Upstream error from Nvidia: Service temporarily overloaded\","
                + "\"code\":502}}", MediaType.APPLICATION_JSON));
        mockServer.expect(requestTo(ENDPOINT)).andRespond(withSuccess(OK_RESPONSE, MediaType.APPLICATION_JSON));

        assertThat(client.complete("hi")).isEqualTo("{}");
        mockServer.verify();
    }

    @Test
    void a_non_retryable_provider_error_returned_with_http_200_is_not_retried() {
        // A 4xx in the body is a genuine bad request; retrying cannot fix it, so it must surface.
        mockServer.expect(requestTo(ENDPOINT)).andRespond(withSuccess(
                "{\"error\":{\"message\":\"model not found\",\"code\":404}}", MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> client.complete("hi"))
                .isInstanceOf(LlmClient.LlmException.class)
                .hasMessageContaining("Provider returned an error")
                .hasMessageContaining("code 404")
                .hasMessageContaining("model not found");
        mockServer.verify();
    }

    // ── Empty / non-string content (the "No content to map due to end-of-input" family) ────────

    @Test
    void content_returned_as_typed_chunks_is_joined_into_text() {
        // Some providers answer with an array of typed parts instead of a plain string; asText() on
        // that array is "", which reached the generator as an empty response.
        mockServer.expect(requestTo(ENDPOINT)).andRespond(withSuccess(
                "{\"choices\":[{\"finish_reason\":\"stop\",\"message\":{\"role\":\"assistant\","
                + "\"content\":[{\"type\":\"text\",\"text\":\"{\\\"id\\\":\"},"
                + "{\"type\":\"text\",\"text\":\"\\\"x\\\"}\"}]}}]}",
                MediaType.APPLICATION_JSON));

        assertThat(client.complete("hi")).isEqualTo("{\"id\":\"x\"}");
        mockServer.verify();
    }

    @Test
    void null_content_reads_as_empty_not_the_string_null() {
        // NullNode.asText() is the four-character string "null", which parses as a JSON null and would
        // hand the generator a null spec rather than a recognisable empty response.
        mockServer.expect(requestTo(ENDPOINT)).andRespond(withSuccess(
                "{\"choices\":[{\"finish_reason\":\"stop\","
                + "\"message\":{\"role\":\"assistant\",\"content\":null}}]}",
                MediaType.APPLICATION_JSON));

        assertThat(client.complete("hi")).isEmpty();
        mockServer.verify();
    }

    @Test
    void reasoning_model_that_spent_the_whole_budget_thinking_yields_an_empty_string() {
        // Groq's openai/gpt-oss-* bill chain-of-thought against max_tokens and return it in a separate
        // "reasoning" field: budget gone, content "". Reasoning is deliberation, never the answer, so
        // the caller must get "" — which its truncation recovery retries with a bigger budget — and
        // never the chain of thought masquerading as a spec.
        mockServer.expect(requestTo(ENDPOINT)).andRespond(withSuccess(
                "{\"choices\":[{\"finish_reason\":\"length\",\"message\":{\"role\":\"assistant\","
                + "\"content\":\"\",\"reasoning\":\"Let me think about the schema first...\"}}],"
                + "\"usage\":{\"completion_tokens\":4096,"
                + "\"completion_tokens_details\":{\"reasoning_tokens\":4096}}}",
                MediaType.APPLICATION_JSON));

        assertThat(client.complete("hi")).isEmpty();
        mockServer.verify();
    }

    @Test
    void tool_calls_reported_with_finish_reason_stop_are_still_executed() {
        // A provider may announce a tool turn only through the message body. Treating that as terminal
        // returns its empty content and ends generation with nothing.
        mockServer.expect(requestTo(ENDPOINT)).andRespond(withSuccess(
                "{\"choices\":[{\"finish_reason\":\"stop\",\"message\":{\"role\":\"assistant\","
                + "\"content\":\"\",\"tool_calls\":[{\"id\":\"c1\",\"type\":\"function\","
                + "\"function\":{\"name\":\"eval_jsonata\",\"arguments\":\"{\\\"expr\\\":\\\"1+1\\\"}\"}}]}}]}",
                MediaType.APPLICATION_JSON));
        mockServer.expect(requestTo(ENDPOINT)).andRespond(withSuccess(OK_RESPONSE, MediaType.APPLICATION_JSON));

        List<String> executed = new ArrayList<>();
        String answer = client.completeWithTools("hi",
                List.of(new LlmClient.ToolDefinition("eval_jsonata", "evaluate", MAPPER.createObjectNode())),
                call -> { executed.add(call.name()); return "2"; });

        assertThat(executed).containsExactly("eval_jsonata");
        assertThat(answer).isEqualTo("{}");
        mockServer.verify();
    }

    @Test
    void a_tool_that_was_not_offered_is_refused_rather_than_executed() {
        // The generator withholds the NETWORK tools on repair attempts on purpose: their budget is
        // session-scoped and re-offering them is where prompt-prefix cache bleed leaks in. But the
        // withholding only chose what to OFFER — a model that named web_search anyway had it executed,
        // because the executor routes by name over every tool it knows. ToolWithholdingIT caught it
        // live: web_search ran five times on attempt 2.
        mockServer.expect(requestTo(ENDPOINT)).andRespond(withSuccess(
                "{\"choices\":[{\"finish_reason\":\"tool_calls\",\"message\":{\"role\":\"assistant\","
                + "\"content\":\"\",\"tool_calls\":[{\"id\":\"c1\",\"type\":\"function\","
                + "\"function\":{\"name\":\"web_search\",\"arguments\":\"{\\\"query\\\":\\\"x\\\"}\"}}]}}]}",
                MediaType.APPLICATION_JSON));
        mockServer.expect(requestTo(ENDPOINT)).andRespond(withSuccess(OK_RESPONSE, MediaType.APPLICATION_JSON));

        List<String> executed = new ArrayList<>();
        String answer = client.completeWithTools("hi",
                // Only eval_jsonata is offered — exactly what a repair attempt gets.
                List.of(new LlmClient.ToolDefinition("eval_jsonata", "evaluate", MAPPER.createObjectNode())),
                call -> { executed.add(call.name()); return "result"; });

        assertThat(executed).as("a tool that was not offered must never reach the executor").isEmpty();
        assertThat(answer).isEqualTo("{}");
        mockServer.verify();
    }

    @Test
    void truncated_tool_calls_are_not_executed() {
        // finish_reason "length" means the tool call itself was cut off mid-emission; replaying it
        // would feed the model back its own truncated request instead of ending the turn.
        mockServer.expect(requestTo(ENDPOINT)).andRespond(withSuccess(
                "{\"choices\":[{\"finish_reason\":\"length\",\"message\":{\"role\":\"assistant\","
                + "\"content\":\"\",\"tool_calls\":[{\"id\":\"c1\",\"type\":\"function\","
                + "\"function\":{\"name\":\"eval_jsonata\",\"arguments\":\"{\\\"expr\\\":\\\"1+\"}}]}}]}",
                MediaType.APPLICATION_JSON));

        List<String> executed = new ArrayList<>();
        String answer = client.completeWithTools("hi",
                List.of(new LlmClient.ToolDefinition("eval_jsonata", "evaluate", MAPPER.createObjectNode())),
                call -> { executed.add(call.name()); return "2"; });

        assertThat(executed).isEmpty();
        assertThat(answer).isEmpty();
        mockServer.verify();
    }
}
