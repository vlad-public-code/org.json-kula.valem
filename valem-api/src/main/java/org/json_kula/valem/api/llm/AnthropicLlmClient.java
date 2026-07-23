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

public class AnthropicLlmClient implements LlmClient {

    private static final Logger log = LoggerFactory.getLogger(AnthropicLlmClient.class);

    private static final String ENDPOINT = "https://api.anthropic.com/v1/messages";
    private static final String ANTHROPIC_VERSION = "2023-06-01";

    private final RestClient restClient;
    private final ObjectMapper mapper;
    private final String apiKey;
    private final String model;
    private final int maxTokens;
    /** When true, the (stable) system context is sent with an ephemeral cache breakpoint. */
    private final boolean promptCacheEnabled;
    /** Hard ceiling on tool-use round-trips before one final tools-withheld request. */
    private final int maxToolIterations;

    private static final int DEFAULT_MAX_TOOL_ITERATIONS = 40;

    public AnthropicLlmClient(String apiKey, String model, int maxTokens, ObjectMapper mapper,
                              RestClient restClient) {
        this(apiKey, model, maxTokens, true, DEFAULT_MAX_TOOL_ITERATIONS, mapper, restClient);
    }

    public AnthropicLlmClient(String apiKey, String model, int maxTokens, boolean promptCacheEnabled,
                              ObjectMapper mapper, RestClient restClient) {
        this(apiKey, model, maxTokens, promptCacheEnabled, DEFAULT_MAX_TOOL_ITERATIONS, mapper, restClient);
    }

    public AnthropicLlmClient(String apiKey, String model, int maxTokens, boolean promptCacheEnabled,
                              int maxToolIterations, ObjectMapper mapper, RestClient restClient) {
        this.apiKey = apiKey;
        this.model = model;
        this.maxTokens = maxTokens;
        this.promptCacheEnabled = promptCacheEnabled;
        this.maxToolIterations = Math.max(1, maxToolIterations);
        this.mapper = mapper;
        this.restClient = restClient;
    }

    private int effectiveMaxTokens(Integer override) {
        return override != null ? override : maxTokens;
    }

    @Override
    public String complete(String prompt) throws LlmException {
        return complete(prompt, null, null, null);
    }

    @Override
    public String complete(String prompt, double temperature) throws LlmException {
        return complete(prompt, null, Double.valueOf(temperature), null);
    }

    @Override
    public String complete(SpecGenerationPrompt.PromptParts parts, CompletionOptions options)
            throws LlmException {
        JsonNode schema = options == null ? null : options.responseSchema();
        Double temperature = options == null ? null : options.temperature();
        Integer maxTokensOverride = options == null ? null : options.maxTokens();
        // No grounding tools on this path, so we can FORCE structured output: the model can only answer
        // by calling submit_spec, giving schema-shaped JSON with no string-repair needed.
        if (schema != null)
            return completeStructured(parts.user(), parts.system(), parts.sessionContext(), schema,
                    temperature, maxTokensOverride);
        return complete(parts.user(), parts.system(), parts.sessionContext(), temperature, maxTokensOverride);
    }

    private String complete(String prompt, String system, Double temperature, Integer maxTokensOverride)
            throws LlmException {
        return complete(prompt, system, null, temperature, maxTokensOverride);
    }

    private String complete(String prompt, String system, String sessionContext, Double temperature,
                            Integer maxTokensOverride) throws LlmException {
        int budget = effectiveMaxTokens(maxTokensOverride);
        log.debug("Calling Anthropic: model={} maxTokens={} temp={} system={} promptLen={}",
                model, budget, temperature, system != null, prompt.length());
        try {
            ObjectNode req = mapper.createObjectNode();
            req.put("model", model);
            req.put("max_tokens", budget);
            if (temperature != null) req.put("temperature", temperature.doubleValue());
            setSystem(req, system, sessionContext);
            ArrayNode messages = req.putArray("messages");
            ObjectNode msg = messages.addObject();
            msg.put("role", "user");
            msg.put("content", prompt);

            String responseJson = sendRequest(req);
            return extractText(responseJson);
        } catch (JsonProcessingException e) {
            log.error("Failed to process Anthropic JSON: {}", e.getMessage());
            throw new LlmException("JSON processing failed: " + e.getMessage(), e);
        } catch (RestClientException e) {
            log.error("Anthropic API call failed: {}", e.getMessage());
            throw new LlmException("Anthropic API call failed: " + e.getMessage(), e);
        }
    }

    @Override
    public String completeWithTools(String prompt, java.util.List<ToolDefinition> toolDefs,
                                    ToolExecutor executor) throws LlmException {
        return completeWithToolsImpl(prompt, null, null, toolDefs, executor, null, null, null, null);
    }

    @Override
    public String completeWithTools(String prompt, java.util.List<ToolDefinition> toolDefs,
                                    ToolExecutor executor, double temperature) throws LlmException {
        return completeWithToolsImpl(prompt, null, null, toolDefs, executor, Double.valueOf(temperature), null, null, null);
    }

    @Override
    public String completeWithTools(String prompt, java.util.List<ToolDefinition> toolDefs,
                                    ToolExecutor executor, CompletionOptions options,
                                    Consumer<LlmProgressEvent> onProgress) throws LlmException {
        return completeWithToolsImpl(prompt, null, null, toolDefs, executor,
                options == null ? null : options.temperature(),
                options == null ? null : options.responseSchema(),
                options == null ? null : options.maxTokens(), onProgress);
    }

    @Override
    public String completeWithTools(SpecGenerationPrompt.PromptParts parts,
                                    java.util.List<ToolDefinition> toolDefs, ToolExecutor executor,
                                    CompletionOptions options, Consumer<LlmProgressEvent> onProgress)
            throws LlmException {
        return completeWithToolsImpl(parts.user(), parts.system(), parts.sessionContext(), toolDefs, executor,
                options == null ? null : options.temperature(),
                options == null ? null : options.responseSchema(),
                options == null ? null : options.maxTokens(), onProgress);
    }

    private String completeWithToolsImpl(String prompt, String system, String sessionContext,
                                         java.util.List<ToolDefinition> toolDefs,
                                         ToolExecutor executor, Double temperature,
                                         JsonNode responseSchema, Integer maxTokensOverride,
                                         Consumer<LlmProgressEvent> onProgress) throws LlmException {
        int budget = effectiveMaxTokens(maxTokensOverride);
        log.debug("Calling Anthropic (tools): model={} tools={} temp={} schema={} system={}",
                model, toolDefs.stream().map(ToolDefinition::name).toList(), temperature,
                responseSchema != null, system != null);
        try {
            ArrayNode tools = mapper.createArrayNode();
            for (ToolDefinition tool : toolDefs) {
                ObjectNode toolNode = tools.addObject();
                toolNode.put("name", tool.name());
                toolNode.put("description", tool.description());
                toolNode.set("input_schema", tool.inputSchema());
            }
            // Structured output alongside grounding tools: offer a submit_spec tool whose input_schema
            // IS the response schema. A submit_spec tool_use IS the answer. Not forced here — the model
            // must stay free to call web_search/eval_jsonata first; if it ends with plain text instead,
            // the terminal path below falls back to today's text handling.
            if (responseSchema != null) addSubmitSpecTool(tools, responseSchema);

            ArrayNode messages = mapper.createArrayNode();
            ObjectNode userMsg = messages.addObject();
            userMsg.put("role", "user");
            userMsg.put("content", prompt);

            int iterations = 0;
            for (;;) {
                // Iteration ceiling: a model that keeps invoking exhausted tools (executors return error
                // strings, they don't stop the loop) would otherwise burn unbounded calls. On hitting the
                // cap, make one final request with tools withheld demanding the answer now.
                if (++iterations > maxToolIterations) {
                    if (onProgress != null)
                        onProgress.accept(new LlmProgressEvent.ToolCompleted(
                                "tool-loop", "iteration cap (" + maxToolIterations + ") reached — forcing final answer"));
                    return finalAnswerWithoutTools(messages, system, sessionContext, temperature, budget, responseSchema);
                }

                ObjectNode req = mapper.createObjectNode();
                req.put("model", model);
                req.put("max_tokens", budget);
                if (temperature != null) req.put("temperature", temperature.doubleValue());
                setSystem(req, system, sessionContext);
                req.set("tools", tools);
                req.set("messages", messages);

                String responseJson = sendRequest(req);
                JsonNode response   = mapper.readTree(responseJson);
                String stopReason   = response.path("stop_reason").asText();
                JsonNode content    = response.path("content");

                // A submit_spec tool_use is terminal: its input is the clean, schema-shaped JSON answer.
                if (responseSchema != null) {
                    String submitted = extractSubmittedSpec(content);
                    if (submitted != null) return submitted;
                }

                if (!"tool_use".equals(stopReason)) {
                    // Terminal response — collect any text blocks
                    StringBuilder sb = new StringBuilder();
                    for (JsonNode block : content) {
                        if ("text".equals(block.path("type").asText()))
                            sb.append(block.path("text").asText());
                    }
                    return sb.toString();
                }

                // The LLM wants to call a tool: record the assistant turn, then execute
                ObjectNode assistantMsg = messages.addObject();
                assistantMsg.put("role", "assistant");
                assistantMsg.set("content", content);

                ArrayNode toolResults = mapper.createArrayNode();
                for (JsonNode block : content) {
                    if (!"tool_use".equals(block.path("type").asText())) continue;
                    String toolCallId = block.path("id").asText();
                    String toolName   = block.path("name").asText();
                    JsonNode input    = block.path("input");

                    if (onProgress != null)
                        onProgress.accept(new LlmProgressEvent.ToolCalling(toolName, toolCallDetail(toolName, input)));
                    String result = executor.execute(new ToolCall(toolCallId, toolName, input));
                    log.debug("Anthropic tool '{}' returned {} chars", toolName, result.length());
                    if (onProgress != null)
                        onProgress.accept(new LlmProgressEvent.ToolCompleted(toolName, toolResultSummary(result)));

                    ObjectNode tr = toolResults.addObject();
                    tr.put("type", "tool_result");
                    tr.put("tool_use_id", toolCallId);
                    tr.put("content", result);
                }

                ObjectNode toolResultMsg = messages.addObject();
                toolResultMsg.put("role", "user");
                toolResultMsg.set("content", toolResults);
            }
        } catch (JsonProcessingException e) {
            log.error("Failed to process Anthropic JSON: {}", e.getMessage());
            throw new LlmException("JSON processing failed: " + e.getMessage(), e);
        } catch (RestClientException e) {
            log.error("Anthropic API call failed: {}", e.getMessage());
            throw new LlmException("Anthropic API call failed: " + e.getMessage(), e);
        }
    }

    /**
     * The tool-loop escape hatch: appends a "produce the final JSON now" user turn and re-requests
     * with {@code tools} withheld, so the model cannot keep calling tools. If it somehow still tries,
     * we throw rather than loop forever.
     */
    private String finalAnswerWithoutTools(ArrayNode messages, String system, String sessionContext,
                                           Double temperature, int budget, JsonNode responseSchema)
            throws JsonProcessingException {
        ObjectNode nudge = messages.addObject();
        nudge.put("role", "user");
        nudge.put("content", "Tool budget exhausted — produce the final JSON now.");

        ObjectNode req = mapper.createObjectNode();
        req.put("model", model);
        req.put("max_tokens", budget);
        if (temperature != null) req.put("temperature", temperature.doubleValue());
        setSystem(req, system, sessionContext);
        req.set("messages", messages);   // no "tools" → the model cannot call any

        String responseJson = sendRequest(req);
        JsonNode response   = mapper.readTree(responseJson);
        if (responseSchema != null) {
            String submitted = extractSubmittedSpec(response.path("content"));
            if (submitted != null) return submitted;
        }
        if ("tool_use".equals(response.path("stop_reason").asText())) {
            throw new LlmException("Anthropic tool loop did not converge: model kept calling tools "
                    + "after the budget was exhausted");
        }
        return extractText(responseJson);
    }

    private static final String SUBMIT_SPEC = "submit_spec";
    private static final String SUBMIT_SPEC_DESC =
            "Submit the final, complete JSON result. Call exactly once, when done.";

    /** Adds the {@code submit_spec} tool whose {@code input_schema} is the desired response schema. */
    private void addSubmitSpecTool(ArrayNode tools, JsonNode responseSchema) {
        ObjectNode t = tools.addObject();
        t.put("name", SUBMIT_SPEC);
        t.put("description", SUBMIT_SPEC_DESC);
        t.set("input_schema", responseSchema);
    }

    /** Returns the serialized {@code input} of a {@code submit_spec} tool_use block, or null. */
    private String extractSubmittedSpec(JsonNode content) throws JsonProcessingException {
        for (JsonNode block : content) {
            if ("tool_use".equals(block.path("type").asText())
                    && SUBMIT_SPEC.equals(block.path("name").asText())) {
                return mapper.writeValueAsString(block.path("input"));
            }
        }
        return null;
    }

    /**
     * Single-shot structured output: offers only {@code submit_spec} and forces it via
     * {@code tool_choice}, so the model cannot answer any other way. Returns the tool input as clean
     * JSON. Used only when no grounding tools are configured (nothing else the model needs to call).
     */
    private String completeStructured(String prompt, String system, String sessionContext, JsonNode schema,
                                      Double temperature, Integer maxTokensOverride) throws LlmException {
        log.debug("Calling Anthropic (forced structured output): model={} temp={} system={}",
                model, temperature, system != null);
        try {
            ArrayNode tools = mapper.createArrayNode();
            addSubmitSpecTool(tools, schema);

            ArrayNode messages = mapper.createArrayNode();
            ObjectNode userMsg = messages.addObject();
            userMsg.put("role", "user");
            userMsg.put("content", prompt);

            ObjectNode req = mapper.createObjectNode();
            req.put("model", model);
            req.put("max_tokens", effectiveMaxTokens(maxTokensOverride));
            if (temperature != null) req.put("temperature", temperature.doubleValue());
            setSystem(req, system, sessionContext);
            req.set("tools", tools);
            ObjectNode toolChoice = req.putObject("tool_choice");
            toolChoice.put("type", "tool");
            toolChoice.put("name", SUBMIT_SPEC);
            req.set("messages", messages);

            String responseJson = sendRequest(req);
            JsonNode response   = mapper.readTree(responseJson);
            String submitted    = extractSubmittedSpec(response.path("content"));
            // Forced tool_choice guarantees a submit_spec call; fall back to text only defensively.
            return submitted != null ? submitted : extractText(responseJson);
        } catch (JsonProcessingException e) {
            log.error("Failed to process Anthropic JSON: {}", e.getMessage());
            throw new LlmException("JSON processing failed: " + e.getMessage(), e);
        } catch (RestClientException e) {
            log.error("Anthropic API call failed: {}", e.getMessage());
            throw new LlmException("Anthropic API call failed: " + e.getMessage(), e);
        }
    }

    /**
     * Attaches the stable context to the request. With prompt caching on it is sent as a block array,
     * each block carrying an {@code ephemeral} {@code cache_control} breakpoint:
     * <ul>
     *   <li>block 1 = {@code system} — the spec-format instructions, identical across every session
     *       with the same view mode (the primary, widely-shared cache prefix);</li>
     *   <li>block 2 = {@code sessionContext} (when present) — content stable within one session but
     *       varying between sessions (the current spec JSON on the evolution path). A second breakpoint
     *       lets it be re-read at ~10% price across the retry loop instead of re-billed each attempt.</li>
     * </ul>
     * The cache prefix then covers {@code tools} + both system blocks. With caching off (a proxy that
     * chokes on block-array {@code system}) the two are concatenated into a single plain string. Prompt
     * caching is GA on the {@code 2023-06-01} API version — no beta header.
     */
    private void setSystem(ObjectNode req, String system, String sessionContext) {
        boolean hasSystem  = system != null && !system.isBlank();
        boolean hasSession = sessionContext != null && !sessionContext.isBlank();
        if (!hasSystem && !hasSession) return;
        if (promptCacheEnabled) {
            ArrayNode sys = req.putArray("system");
            if (hasSystem)  addCachedBlock(sys, system);
            if (hasSession) addCachedBlock(sys, sessionContext);
        } else {
            String combined = hasSystem && hasSession ? system + "\n\n" + sessionContext
                            : hasSystem ? system : sessionContext;
            req.put("system", combined);
        }
    }

    /** Adds a {@code text} system block carrying an {@code ephemeral} cache breakpoint. */
    private static void addCachedBlock(ArrayNode sys, String text) {
        ObjectNode block = sys.addObject();
        block.put("type", "text");
        block.put("text", text);
        block.putObject("cache_control").put("type", "ephemeral");
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
        if (result.startsWith("[") && result.length() < 200) return result;
        return result.length() + " chars";
    }

    private String sendRequest(ObjectNode req) throws JsonProcessingException {
        String body = mapper.writeValueAsString(req);
        // Retry transparently on 429/5xx (transient rate limits / overload), honouring Retry-After.
        String responseJson = LlmRetry.withRetry(() -> restClient.post()
                .uri(ENDPOINT)
                .header("x-api-key", apiKey)
                .header("anthropic-version", ANTHROPIC_VERSION)
                .contentType(MediaType.APPLICATION_JSON)
                .body(body)
                .retrieve()
                .body(String.class), "Anthropic call");
        log.debug("Anthropic responded: {} chars", responseJson != null ? responseJson.length() : 0);
        return responseJson;
    }

    private String extractText(String responseJson) throws JsonProcessingException {
        JsonNode response = mapper.readTree(responseJson);
        JsonNode content  = response.path("content");
        if (!content.isArray() || content.isEmpty()) {
            log.warn("Anthropic returned unexpected response (no content array): {}", responseJson);
            throw new LlmException("Unexpected Anthropic response: content array missing — " + responseJson);
        }
        // Concatenate ALL text blocks (identical to the tool path): a response with a preamble block
        // plus a separate JSON block must not silently drop the JSON by reading only content[0].
        StringBuilder sb = new StringBuilder();
        for (JsonNode block : content) {
            if ("text".equals(block.path("type").asText())) sb.append(block.path("text").asText());
        }
        return sb.toString();
    }
}
