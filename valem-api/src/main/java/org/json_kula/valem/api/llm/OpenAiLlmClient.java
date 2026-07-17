package org.json_kula.valem.api.llm;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.json_kula.valem.core.llm.LlmClient;
import org.json_kula.valem.core.llm.LlmProgressEvent;
import org.json_kula.valem.core.llm.SpecGenerationPrompt;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.util.function.Consumer;

/**
 * LlmClient implementation for the OpenAI Chat Completions API format.
 *
 * Works with OpenAI and any compatible provider (Ollama, LM Studio, etc.)
 * by setting a different baseUrl. apiKey may be blank for local providers.
 */
public class OpenAiLlmClient implements LlmClient {

    private static final Logger log = LoggerFactory.getLogger(OpenAiLlmClient.class);

    private final RestClient restClient;
    private final ObjectMapper mapper;
    private final String apiKey;
    private final String model;
    private final int maxTokens;
    private final String endpoint;
    /** Hard ceiling on tool-call round-trips before one final tools-withheld request. */
    private final int maxToolIterations;

    private static final int DEFAULT_MAX_TOOL_ITERATIONS = 40;

    public OpenAiLlmClient(String baseUrl, String apiKey, String model, int maxTokens,
                           ObjectMapper mapper, RestClient restClient) {
        this(baseUrl, apiKey, model, maxTokens, DEFAULT_MAX_TOOL_ITERATIONS, mapper, restClient);
    }

    public OpenAiLlmClient(String baseUrl, String apiKey, String model, int maxTokens,
                           int maxToolIterations, ObjectMapper mapper, RestClient restClient) {
        this.apiKey = apiKey;
        this.model = model;
        this.maxTokens = maxTokens;
        this.maxToolIterations = Math.max(1, maxToolIterations);
        this.mapper = mapper;
        this.restClient = restClient;
        this.endpoint = baseUrl.stripTrailing() + "/chat/completions";
    }

    private int effectiveMaxTokens(Integer override) {
        return override != null ? override : maxTokens;
    }

    @Override
    public String complete(String prompt) throws LlmException {
        return completeJson(prompt, null, null, null, null);
    }

    @Override
    public String complete(String prompt, double temperature) throws LlmException {
        return completeJson(prompt, null, temperature, null, null);
    }

    @Override
    public String complete(String prompt, CompletionOptions options) throws LlmException {
        return completeJson(prompt, null,
                options == null ? null : options.temperature(),
                options == null ? null : options.responseSchema(),
                options == null ? null : options.maxTokens());
    }

    @Override
    public String complete(SpecGenerationPrompt.PromptParts parts, CompletionOptions options)
            throws LlmException {
        return completeJson(parts.user(), parts.system(),
                options == null ? null : options.temperature(),
                options == null ? null : options.responseSchema(),
                options == null ? null : options.maxTokens());
    }

    private String completeJson(String prompt, String system, Double temperature, JsonNode responseSchema,
                                Integer maxTokensOverride) throws LlmException {
        int budget = effectiveMaxTokens(maxTokensOverride);
        log.debug("Calling OpenAI-compatible API: endpoint={} model={} maxTokens={} temp={} schema={} system={} promptLen={}",
                endpoint, model, budget, temperature, responseSchema != null, system != null, prompt.length());
        try {
            ObjectNode req = mapper.createObjectNode();
            req.put("model", model);
            req.put("max_tokens", budget);
            // Force structured JSON output — without this, weaker OpenAI-compatible models (e.g.
            // mistral-small) answer in conversational prose and the spec parse fails.
            setResponseFormat(req, responseSchema);
            if (temperature != null) req.put("temperature", temperature.doubleValue());
            ArrayNode messages = req.putArray("messages");
            // A distinct system role improves instruction adherence; OpenAI-compatible providers
            // auto-cache long stable prefixes, so no explicit cache control is needed.
            addSystemMessage(messages, system);
            ObjectNode msg = messages.addObject();
            msg.put("role", "user");
            msg.put("content", prompt);

            String responseJson = sendRequest(req);
            return extractContent(responseJson);
        } catch (JsonProcessingException e) {
            log.error("Failed to process OpenAI-compatible JSON: {}", e.getMessage());
            throw new LlmException("JSON processing failed: " + e.getMessage(), e);
        } catch (RestClientException e) {
            log.error("OpenAI-compatible API call failed: {}", e.getMessage());
            throw new LlmException("API call failed: " + e.getMessage(), e);
        }
    }

    @Override
    public String completeWithTools(String prompt, java.util.List<ToolDefinition> toolDefs,
                                    ToolExecutor executor) throws LlmException {
        return completeWithToolsImpl(prompt, null, toolDefs, executor, null, null, null, null);
    }

    @Override
    public String completeWithTools(String prompt, java.util.List<ToolDefinition> toolDefs,
                                    ToolExecutor executor, double temperature) throws LlmException {
        return completeWithToolsImpl(prompt, null, toolDefs, executor, temperature, null, null, null);
    }

    @Override
    public String completeWithTools(String prompt, java.util.List<ToolDefinition> toolDefs,
                                    ToolExecutor executor, CompletionOptions options) throws LlmException {
        return completeWithToolsImpl(prompt, null, toolDefs, executor,
                options == null ? null : options.temperature(),
                options == null ? null : options.responseSchema(),
                options == null ? null : options.maxTokens(), null);
    }

    @Override
    public String completeWithTools(String prompt, java.util.List<ToolDefinition> toolDefs,
                                    ToolExecutor executor, CompletionOptions options,
                                    Consumer<LlmProgressEvent> onProgress) throws LlmException {
        return completeWithToolsImpl(prompt, null, toolDefs, executor,
                options == null ? null : options.temperature(),
                options == null ? null : options.responseSchema(),
                options == null ? null : options.maxTokens(), onProgress);
    }

    @Override
    public String completeWithTools(SpecGenerationPrompt.PromptParts parts,
                                    java.util.List<ToolDefinition> toolDefs, ToolExecutor executor,
                                    CompletionOptions options, Consumer<LlmProgressEvent> onProgress)
            throws LlmException {
        return completeWithToolsImpl(parts.user(), parts.system(), toolDefs, executor,
                options == null ? null : options.temperature(),
                options == null ? null : options.responseSchema(),
                options == null ? null : options.maxTokens(), onProgress);
    }

    private String completeWithToolsImpl(String prompt, String system,
                                         java.util.List<ToolDefinition> toolDefs,
                                         ToolExecutor executor, Double temperature,
                                         JsonNode responseSchema, Integer maxTokensOverride,
                                         Consumer<LlmProgressEvent> onProgress) throws LlmException {
        int budget = effectiveMaxTokens(maxTokensOverride);
        log.debug("Calling OpenAI-compatible API (tools): endpoint={} model={} tools={} temp={} schema={} system={}",
                endpoint, model, toolDefs.stream().map(ToolDefinition::name).toList(), temperature,
                responseSchema != null, system != null);
        try {
            ArrayNode tools = mapper.createArrayNode();
            for (ToolDefinition tool : toolDefs) {
                ObjectNode toolNode = tools.addObject();
                toolNode.put("type", "function");
                ObjectNode func = toolNode.putObject("function");
                func.put("name", tool.name());
                func.put("description", tool.description());
                func.set("parameters", tool.inputSchema());
            }

            ArrayNode messages = mapper.createArrayNode();
            addSystemMessage(messages, system);
            ObjectNode userMsg = messages.addObject();
            userMsg.put("role", "user");
            userMsg.put("content", prompt);

            int iterations = 0;
            for (;;) {
                // Iteration ceiling: withhold tools for one final request when the cap is hit so a model
                // stuck calling exhausted tools cannot loop unbounded.
                if (++iterations > maxToolIterations) {
                    if (onProgress != null)
                        onProgress.accept(new LlmProgressEvent.ToolCompleted(
                                "tool-loop", "iteration cap (" + maxToolIterations + ") reached — forcing final answer"));
                    return finalAnswerWithoutTools(messages, budget, temperature, responseSchema);
                }

                ObjectNode req = mapper.createObjectNode();
                req.put("model", model);
                req.put("max_tokens", budget);
                // Structured JSON for the final answer (tool_calls turns still emit function calls).
                setResponseFormat(req, responseSchema);
                if (temperature != null) req.put("temperature", temperature.doubleValue());
                req.set("tools", tools);
                req.set("messages", messages);

                String responseJson  = sendRequest(req);
                JsonNode response    = mapper.readTree(responseJson);
                JsonNode choices     = response.path("choices");
                if (!choices.isArray() || choices.isEmpty())
                    throw new LlmException("Unexpected response: choices missing — " + responseJson);

                JsonNode choice      = choices.get(0);
                String finishReason  = choice.path("finish_reason").asText();
                JsonNode message     = choice.path("message");

                if (!"tool_calls".equals(finishReason)) {
                    // Terminal response — return the text content
                    return message.path("content").asText();
                }

                // Add the assistant message (including tool_calls) to the conversation
                messages.add(message.deepCopy());

                // Execute each tool call and add tool-response messages
                for (JsonNode toolCall : message.path("tool_calls")) {
                    String toolCallId = toolCall.path("id").asText();
                    String toolName   = toolCall.path("function").path("name").asText();
                    String argsText   = toolCall.path("function").path("arguments").asText("{}");
                    JsonNode arguments;
                    try {
                        arguments = mapper.readTree(argsText);
                    } catch (JsonProcessingException e) {
                        arguments = mapper.createObjectNode();
                    }

                    if (onProgress != null)
                        onProgress.accept(new LlmProgressEvent.ToolCalling(toolName, toolCallDetail(toolName, arguments)));
                    String result = executor.execute(new ToolCall(toolCallId, toolName, arguments));
                    log.debug("OpenAI tool '{}' returned {} chars", toolName, result.length());
                    if (onProgress != null)
                        onProgress.accept(new LlmProgressEvent.ToolCompleted(toolName, toolResultSummary(result)));

                    ObjectNode toolMsg = messages.addObject();
                    toolMsg.put("role", "tool");
                    toolMsg.put("tool_call_id", toolCallId);
                    toolMsg.put("content", result);
                }
            }
        } catch (JsonProcessingException e) {
            log.error("Failed to process OpenAI-compatible JSON: {}", e.getMessage());
            throw new LlmException("JSON processing failed: " + e.getMessage(), e);
        } catch (RestClientException e) {
            log.error("OpenAI-compatible API call failed: {}", e.getMessage());
            throw new LlmException("API call failed: " + e.getMessage(), e);
        }
    }

    /**
     * Tool-loop escape hatch: appends a "produce the final JSON now" user turn and re-requests with
     * {@code tools} omitted, so the model must answer. If it still asks for a tool call, throw.
     */
    private String finalAnswerWithoutTools(ArrayNode messages, int budget, Double temperature,
                                           JsonNode responseSchema) throws JsonProcessingException {
        ObjectNode nudge = messages.addObject();
        nudge.put("role", "user");
        nudge.put("content", "Tool budget exhausted — produce the final JSON now.");

        ObjectNode req = mapper.createObjectNode();
        req.put("model", model);
        req.put("max_tokens", budget);
        setResponseFormat(req, responseSchema);
        if (temperature != null) req.put("temperature", temperature.doubleValue());
        req.set("messages", messages);   // no "tools" → the model cannot request a call

        String responseJson = sendRequest(req);
        JsonNode response   = mapper.readTree(responseJson);
        JsonNode choices    = response.path("choices");
        if (!choices.isArray() || choices.isEmpty())
            throw new LlmException("Unexpected response: choices missing — " + responseJson);
        JsonNode choice = choices.get(0);
        if ("tool_calls".equals(choice.path("finish_reason").asText())) {
            throw new LlmException("OpenAI-compatible tool loop did not converge: model kept calling "
                    + "tools after the budget was exhausted");
        }
        return choice.path("message").path("content").asText();
    }

    /** Prepends a {@code system}-role message when a system context is present. */
    private static void addSystemMessage(ArrayNode messages, String system) {
        if (system == null || system.isBlank()) return;
        ObjectNode sys = messages.addObject();
        sys.put("role", "system");
        sys.put("content", system);
    }

    private static String toolCallDetail(String toolName, JsonNode arguments) {
        return switch (toolName) {
            case "web_search" -> arguments.path("query").asText("...");
            case "web_fetch"  -> arguments.path("url").asText("...");
            case "eval_jsonata" -> {
                String expr = arguments.path("expr").asText("...");
                yield expr.length() > 80 ? expr.substring(0, 80) + "..." : expr;
            }
            default -> arguments.toString();
        };
    }

    private static String toolResultSummary(String result) {
        if (result.startsWith("[") && result.length() < 200) return result; // budget/error message
        return result.length() + " chars";
    }

    /**
     * Sets {@code response_format}. With a schema → {@code json_schema} (provider structured output);
     * otherwise → {@code json_object} (valid JSON, unconstrained shape). {@code strict} is left
     * {@code false}: a ModelSpec embeds an arbitrary JSON Schema in its own {@code schema} field, which
     * strict mode (requiring {@code additionalProperties:false} everywhere) cannot represent — so the
     * schema is shape guidance, not a hard contract, and the {@code ModelSpecValidator} stays the
     * source of truth.
     */
    private void setResponseFormat(ObjectNode req, JsonNode responseSchema) {
        if (responseSchema == null) {
            req.putObject("response_format").put("type", "json_object");
            return;
        }
        ObjectNode rf = req.putObject("response_format");
        rf.put("type", "json_schema");
        ObjectNode js = rf.putObject("json_schema");
        js.put("name", "valem_spec");
        js.put("strict", false);
        js.set("schema", responseSchema);
    }

    private String sendRequest(ObjectNode req) throws JsonProcessingException {
        String body = mapper.writeValueAsString(req);
        // Retry transparently on 429/5xx — the web-fetch tool loop makes many round-trips per
        // generation, so transient rate limits are common and must not abort the whole spec.
        String responseJson = LlmRetry.withRetry(() -> {
            var requestSpec = restClient.post()
                    .uri(endpoint)
                    .contentType(MediaType.APPLICATION_JSON);
            if (apiKey != null && !apiKey.isBlank())
                requestSpec = requestSpec.header("Authorization", "Bearer " + apiKey);
            return requestSpec.body(body).retrieve().body(String.class);
        }, "OpenAI-compatible call");
        log.debug("OpenAI-compatible API responded: {} chars",
                responseJson != null ? responseJson.length() : 0);
        return responseJson;
    }

    private String extractContent(String responseJson) throws JsonProcessingException {
        JsonNode response = mapper.readTree(responseJson);
        JsonNode choices  = response.path("choices");
        if (!choices.isArray() || choices.isEmpty()) {
            log.warn("OpenAI-compatible API returned unexpected response (no choices): {}", responseJson);
            throw new LlmException("Unexpected response: choices array missing — " + responseJson);
        }
        return choices.get(0).path("message").path("content").asText();
    }
}
