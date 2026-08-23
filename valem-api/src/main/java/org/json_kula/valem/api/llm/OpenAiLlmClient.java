package org.json_kula.valem.api.llm;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.json_kula.valem.core.llm.LlmClient;
import org.json_kula.valem.core.llm.LlmDescriptor;
import org.json_kula.valem.core.llm.LlmProgressEvent;
import org.json_kula.valem.core.llm.SpecGenerationPrompt;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.HttpServerErrorException;
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
    /** How much structured-output constraint this provider is asked for. */
    private final StructuredOutputMode structuredOutput;
    /**
     * Whether this provider tolerates {@code response_format} on a request that also carries
     * {@code tools}. Groq rejects the combination outright — see the constructor.
     */
    private final boolean responseFormatWithTools;
    /** Provider label for {@link #describe()}; this class serves many, so it must be told which. */
    private final String provider;
    /**
     * {@code reasoning_effort} to send, or {@code null} to omit the field entirely.
     *
     * <p>Only meaningful for a model that reasons before answering, and it is the difference between a
     * usable spec and an empty one on a tight budget: a reasoning model bills its chain of thought
     * against the same {@code max_tokens} as the answer. Measured on {@code gemini-2.5-flash} at a
     * 4096-token budget for one spec generation — default effort: 3,095 thinking tokens, 984 left for
     * the answer, truncated; {@code low}: 837 thinking tokens and a complete spec. Sent verbatim, so
     * the accepted values are the provider's ({@code none}/{@code low}/{@code medium}/{@code high} for
     * Gemini and Groq's gpt-oss). Left {@code null} by default: a provider that does not know the field
     * may answer 400, which is indistinguishable from a dead key.
     */
    private final String reasoningEffort;

    private static final int DEFAULT_MAX_TOOL_ITERATIONS = 40;
    /**
     * What a client built without a provider name calls itself. Honest rather than wrong: this class
     * speaks the OpenAI wire format to a dozen back ends, and only {@link LlmClientFactory} knows
     * which one a given instance points at.
     */
    private static final String UNNAMED_PROVIDER = "openai-compatible";

    public OpenAiLlmClient(String baseUrl, String apiKey, String model, int maxTokens,
                           ObjectMapper mapper, RestClient restClient) {
        this(baseUrl, apiKey, model, maxTokens, DEFAULT_MAX_TOOL_ITERATIONS, mapper, restClient);
    }

    public OpenAiLlmClient(String baseUrl, String apiKey, String model, int maxTokens,
                           int maxToolIterations, ObjectMapper mapper, RestClient restClient) {
        this(baseUrl, apiKey, model, maxTokens, maxToolIterations, StructuredOutputMode.SCHEMA,
                mapper, restClient);
    }

    /**
     * @param structuredOutput how much {@code response_format} constraint to send; use anything but
     *                         {@link StructuredOutputMode#SCHEMA} for a provider that rejects it
     */
    public OpenAiLlmClient(String baseUrl, String apiKey, String model, int maxTokens,
                           int maxToolIterations, StructuredOutputMode structuredOutput,
                           ObjectMapper mapper, RestClient restClient) {
        this(baseUrl, apiKey, model, maxTokens, maxToolIterations, structuredOutput, true,
                mapper, restClient);
    }

    /**
     * As above, for a provider that refuses {@code response_format} and {@code tools} in the same
     * request.
     *
     * <p>This is a second, independent axis to {@link StructuredOutputMode}. That enum says how much
     * shape constraint a provider can express at all; this flag says whether it will express any of
     * it while tools are on the table. Groq answers
     * {@code 400 "json mode cannot be combined with tool/function calling"} to <em>every</em>
     * {@code response_format} value — {@code json_object} and {@code json_schema} alike, on every
     * model — so for it the two features are mutually exclusive rather than laddered. Turning the
     * whole mode down to {@link StructuredOutputMode#NONE} would be the blunt fix, but it also
     * strips the constraint from the plain completions and the tool-loop's final answer, where the
     * provider accepts it perfectly well.
     *
     * @param responseFormatWithTools {@code false} to omit {@code response_format} from any request
     *                                that carries {@code tools}; the tools-free requests keep the
     *                                configured mode
     */
    public OpenAiLlmClient(String baseUrl, String apiKey, String model, int maxTokens,
                           int maxToolIterations, StructuredOutputMode structuredOutput,
                           boolean responseFormatWithTools,
                           ObjectMapper mapper, RestClient restClient) {
        this(UNNAMED_PROVIDER, baseUrl, apiKey, model, maxTokens, maxToolIterations, structuredOutput,
                responseFormatWithTools, mapper, restClient);
    }

    /**
     * As above, naming the provider this instance points at so the generation log can report which
     * model answered.
     *
     * @param provider provider label for {@link #describe()} ({@code groq}, {@code mistral}, …);
     *                 blank falls back to {@code openai-compatible}. Note this is the <b>first</b>
     *                 parameter and {@code baseUrl} is the second — they are both strings, so pass
     *                 them by name at the call site if there is any doubt.
     */
    public OpenAiLlmClient(String provider, String baseUrl, String apiKey, String model, int maxTokens,
                           int maxToolIterations, StructuredOutputMode structuredOutput,
                           boolean responseFormatWithTools,
                           ObjectMapper mapper, RestClient restClient) {
        this(provider, baseUrl, apiKey, model, maxTokens, maxToolIterations, structuredOutput,
                responseFormatWithTools, null, mapper, restClient);
    }

    /**
     * As above, asking a reasoning model for a specific {@code reasoning_effort} — see
     * {@link #reasoningEffort}. Blank or {@code null} omits the field.
     */
    public OpenAiLlmClient(String provider, String baseUrl, String apiKey, String model, int maxTokens,
                           int maxToolIterations, StructuredOutputMode structuredOutput,
                           boolean responseFormatWithTools, String reasoningEffort,
                           ObjectMapper mapper, RestClient restClient) {
        this.reasoningEffort = reasoningEffort == null || reasoningEffort.isBlank()
                ? null : reasoningEffort.trim();
        this.provider = provider == null || provider.isBlank() ? UNNAMED_PROVIDER : provider;
        this.structuredOutput = structuredOutput != null ? structuredOutput : StructuredOutputMode.SCHEMA;
        this.responseFormatWithTools = responseFormatWithTools;
        this.apiKey = apiKey;
        this.model = model;
        this.maxTokens = maxTokens;
        this.maxToolIterations = Math.max(1, maxToolIterations);
        this.mapper = mapper;
        this.restClient = restClient;
        this.endpoint = baseUrl.stripTrailing() + "/chat/completions";
    }

    @Override
    public LlmDescriptor describe() {
        return new LlmDescriptor(provider, model);
    }

    private int effectiveMaxTokens(Integer override) {
        return override != null ? override : maxTokens;
    }

    @Override
    public String complete(String prompt) throws LlmException {
        return completeJson(prompt, null, null, null, null, null);
    }

    @Override
    public String complete(String prompt, double temperature) throws LlmException {
        return completeJson(prompt, null, null, temperature, null, null);
    }

    @Override
    public String complete(String prompt, CompletionOptions options) throws LlmException {
        return completeJson(prompt, null, null,
                options == null ? null : options.temperature(),
                options == null ? null : options.responseSchema(),
                options == null ? null : options.maxTokens());
    }

    @Override
    public String complete(SpecGenerationPrompt.PromptParts parts, CompletionOptions options)
            throws LlmException {
        return completeJson(parts.user(), parts.system(), parts.sessionContext(),
                options == null ? null : options.temperature(),
                options == null ? null : options.responseSchema(),
                options == null ? null : options.maxTokens());
    }

    private String completeJson(String prompt, String system, String sessionContext, Double temperature,
                                JsonNode responseSchema, Integer maxTokensOverride) throws LlmException {
        int budget = effectiveMaxTokens(maxTokensOverride);
        log.debug("Calling OpenAI-compatible API: endpoint={} model={} maxTokens={} temp={} schema={} system={} promptLen={}",
                endpoint, model, budget, temperature, responseSchema != null, system != null, prompt.length());
        try {
            ObjectNode req = mapper.createObjectNode();
            req.put("model", model);
            req.put("max_tokens", budget);
        setReasoningEffort(req);
            // Force structured JSON output — without this, weaker OpenAI-compatible models (e.g.
            // mistral-small) answer in conversational prose and the spec parse fails.
            setResponseFormat(req, responseSchema, false);
            if (temperature != null) req.put("temperature", temperature.doubleValue());
            ArrayNode messages = req.putArray("messages");
            // A distinct system role improves instruction adherence; OpenAI-compatible providers
            // auto-cache long stable prefixes, so no explicit cache control is needed. The
            // session-stable context (e.g. the current spec on the evolution path) is a second system
            // message so it stays part of the auto-cached prefix, ahead of the volatile user turn.
            addSystemMessage(messages, system);
            addSystemMessage(messages, sessionContext);
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
        return completeWithToolsImpl(prompt, null, null, toolDefs, executor, null, null, null, null);
    }

    @Override
    public String completeWithTools(String prompt, java.util.List<ToolDefinition> toolDefs,
                                    ToolExecutor executor, double temperature) throws LlmException {
        return completeWithToolsImpl(prompt, null, null, toolDefs, executor, temperature, null, null, null);
    }

    @Override
    public String completeWithTools(String prompt, java.util.List<ToolDefinition> toolDefs,
                                    ToolExecutor executor, CompletionOptions options) throws LlmException {
        return completeWithToolsImpl(prompt, null, null, toolDefs, executor,
                options == null ? null : options.temperature(),
                options == null ? null : options.responseSchema(),
                options == null ? null : options.maxTokens(), null);
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
        log.debug("Calling OpenAI-compatible API (tools): endpoint={} model={} tools={} temp={} schema={} system={}",
                endpoint, model, toolDefs.stream().map(ToolDefinition::name).toList(), temperature,
                responseSchema != null, system != null);
        try {
            ArrayNode tools = mapper.createArrayNode();
            java.util.Set<String> offered = new java.util.HashSet<>();
            for (ToolDefinition tool : toolDefs) {
                offered.add(tool.name());
                ObjectNode toolNode = tools.addObject();
                toolNode.put("type", "function");
                ObjectNode func = toolNode.putObject("function");
                func.put("name", tool.name());
                func.put("description", tool.description());
                func.set("parameters", tool.inputSchema());
            }

            ArrayNode messages = mapper.createArrayNode();
            addSystemMessage(messages, system);
            addSystemMessage(messages, sessionContext);
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
        setReasoningEffort(req);
                // Structured JSON for the final answer (tool_calls turns still emit function calls).
                setResponseFormat(req, responseSchema, true);
                if (temperature != null) req.put("temperature", temperature.doubleValue());
                req.set("tools", tools);
                req.set("messages", messages);

                String responseJson  = sendRequest(req);
                JsonNode response    = mapper.readTree(responseJson);
                JsonNode choices     = response.path("choices");
                if (!choices.isArray() || choices.isEmpty())
                    throw providerError(response, responseJson);

                JsonNode choice      = choices.get(0);
                String finishReason  = choice.path("finish_reason").asText();
                JsonNode message     = choice.path("message");

                // A tool-call turn is normally announced by finish_reason, but some providers report
                // "stop" on a message that still carries tool_calls; treating that as terminal returns
                // the message's empty content and ends the generation with nothing. "length" is the one
                // case that stays terminal even with tool_calls present: those calls were cut off
                // mid-emission, so executing them would feed the model back its own truncated request.
                boolean hasToolCalls = message.path("tool_calls").isArray()
                        && !message.path("tool_calls").isEmpty();
                boolean toolTurn = "tool_calls".equals(finishReason)
                        || (hasToolCalls && !"length".equals(finishReason));
                if (!toolTurn) {
                    return terminalContent(message, finishReason, response);
                }

                // Add the assistant message (including tool_calls) to the conversation
                messages.add(message.deepCopy());

                // Execute each tool call and add tool-response messages
                for (JsonNode toolCall : message.path("tool_calls")) {
                    String toolCallId = toolCall.path("id").asText();
                    String toolName   = toolCall.path("function").path("name").asText();

                    // The offered set is authoritative. A model can name a tool that was NOT offered
                    // — a weak one hallucinates them (including its own delimiter, "tool_call_begin|")
                    // and, more importantly, keeps asking for the NETWORK tools that the generator
                    // deliberately withholds on repair attempts. Routing by name alone executed them
                    // anyway, silently defeating the withholding: the session-scoped fetch budget got
                    // spent on repairs and the prompt-prefix cache-bleed risk came back. Refuse, and
                    // tell the model, rather than running it.
                    if (offered != null && !offered.contains(toolName)) {
                        log.warn("Model asked for tool '{}', which was not offered on this request; refusing",
                                toolName);
                        ObjectNode refusal = messages.addObject();
                        refusal.put("role", "tool");
                        refusal.put("tool_call_id", toolCallId);
                        refusal.put("content", "[tool '" + toolName + "' is not available on this "
                                + "request — use only the tools listed for this turn]");
                        continue;
                    }
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
        setReasoningEffort(req);
        // No "tools" on this request, so even a provider that rejects the combination takes it.
        setResponseFormat(req, responseSchema, false);
        if (temperature != null) req.put("temperature", temperature.doubleValue());
        req.set("messages", messages);   // no "tools" → the model cannot request a call

        String responseJson = sendRequest(req);
        JsonNode response   = mapper.readTree(responseJson);
        JsonNode choices    = response.path("choices");
        if (!choices.isArray() || choices.isEmpty())
            throw providerError(response, responseJson);
        JsonNode choice = choices.get(0);
        if ("tool_calls".equals(choice.path("finish_reason").asText())) {
            throw new LlmException("OpenAI-compatible tool loop did not converge: model kept calling "
                    + "tools after the budget was exhausted");
        }
        return terminalContent(choice.path("message"), choice.path("finish_reason").asText(), response);
    }

    /**
     * The assistant text of a terminal response, tolerant of the content shapes that otherwise read as
     * empty, and loud when there genuinely is nothing.
     *
     * <p>Empty text is the failure mode behind {@code "Response was not valid JSON: No content to map
     * due to end-of-input"}: the caller gets {@code ""}, and the only clue about why is on this side of
     * the wire. Two provider behaviours produce it:
     * <ul>
     *   <li>a <b>reasoning</b> model (Groq's {@code openai/gpt-oss-*}) bills its chain of thought
     *       against the same {@code max_tokens} budget as the answer and returns it in a separate
     *       {@code reasoning} field, so a budget reasoning alone exhausts comes back
     *       {@code finish_reason: "length"} with {@code content: ""};</li>
     *   <li>a provider that returns {@code content} as an array of typed chunks rather than a plain
     *       string, which {@code asText()} reads as empty — handled by {@link #contentText}.</li>
     * </ul>
     * Reasoning text is never returned as the answer: it is deliberation, not output. It is only
     * counted, so the log says which of the two happened.
     */
    private String terminalContent(JsonNode message, String finishReason, JsonNode response) {
        String content = contentText(message.path("content"));
        if (!content.isBlank()) return content;

        int reasoningTokens = response.path("usage").path("completion_tokens_details")
                .path("reasoning_tokens").asInt(0);
        log.warn("OpenAI-compatible model '{}' returned an EMPTY response: finish_reason={} "
                 + "completion_tokens={} reasoning_tokens={}{}",
                model, finishReason,
                response.path("usage").path("completion_tokens").asInt(0), reasoningTokens,
                "length".equals(finishReason)
                        ? " — the token budget ran out" + (reasoningTokens > 0
                                ? " while the model was still reasoning; raise max_tokens or lower "
                                  + "reasoning_effort" : "; raise max_tokens")
                        : "");
        return "";
    }

    /**
     * Reads an OpenAI-compatible {@code content} field as text. Usually a plain string; some providers
     * (and every multimodal-shaped response) send an array of typed parts instead, whose text lives in
     * each element's {@code text} field. A {@code null}/absent content is the empty string — never the
     * literal {@code "null"} {@code NullNode.asText()} would hand back.
     */
    static String contentText(JsonNode content) {
        if (content == null || content.isNull() || content.isMissingNode()) return "";
        if (content.isArray()) {
            StringBuilder sb = new StringBuilder();
            for (JsonNode part : content) {
                if (part.isTextual()) sb.append(part.asText());
                else if (part.hasNonNull("text")) sb.append(part.path("text").asText());
            }
            return sb.toString();
        }
        return content.asText("");
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
     *
     * @param toolsPresent whether this request also carries {@code tools}; a provider that rejects
     *                     the combination gets no {@code response_format} on those requests
     */
    /** Adds {@code reasoning_effort} when one is configured; omits the field entirely otherwise. */
    private void setReasoningEffort(ObjectNode req) {
        if (reasoningEffort != null) req.put("reasoning_effort", reasoningEffort);
    }

    private void setResponseFormat(ObjectNode req, JsonNode responseSchema, boolean toolsPresent) {
        // NONE omits the field entirely, and JSON never asks for a schema — both for providers that
        // answer 400 to what they do not support, which is indistinguishable from a dead key.
        if (structuredOutput == StructuredOutputMode.NONE) return;
        if (toolsPresent && !responseFormatWithTools) return;

        if (responseSchema == null || structuredOutput == StructuredOutputMode.JSON) {
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
            String responseBody = requestSpec.body(body).retrieve().body(String.class);
            rethrowInBodyError(responseBody);
            return responseBody;
        }, "OpenAI-compatible call");
        log.debug("OpenAI-compatible API responded: {} chars",
                responseJson != null ? responseJson.length() : 0);
        return responseJson;
    }

    /**
     * The exception for a response that carries no {@code choices}.
     *
     * <p>A gateway can answer <b>HTTP 200 with an error body</b> — OpenRouter does it for an upstream
     * outage: {@code {"error":{"message":"Upstream error from Nvidia: Service temporarily
     * overloaded","code":502}}}. Nothing in the transport layer objects, so the old message
     * ("choices missing" plus the raw JSON) framed a provider outage as a malformed response and sent
     * whoever read it looking for a parsing bug. Lead with what the provider actually said.
     */
    private LlmException providerError(JsonNode response, String responseJson) {
        JsonNode error = response.path("error");
        if (error.isObject() || error.isTextual()) {
            String message = error.isTextual() ? error.asText() : error.path("message").asText("");
            String code    = error.path("code").asText("");
            return new LlmException("Provider returned an error instead of a completion"
                    + (code.isBlank() ? "" : " (code " + code + ")") + ": "
                    + (message.isBlank() ? responseJson : message));
        }
        return new LlmException("Unexpected response: choices missing — " + responseJson);
    }

    /**
     * Re-raises a provider error that arrived with <b>HTTP 200</b> as the status it really is, from
     * inside the retry supplier so the existing machinery handles it.
     *
     * <p>A gateway can answer 200 with an error body — OpenRouter does it for an upstream outage:
     * {@code {"error":{"message":"Upstream error from Nvidia: Service temporarily overloaded",
     * "code":502}}}. Because the transport succeeded, {@link LlmRetry} never saw it and a transient
     * outage aborted the whole generation on its first occurrence; the sandbox router could not read
     * a status out of it either. Converting it back into an {@link HttpStatusCodeException} makes
     * both work again with no special cases: 429/5xx are retried with backoff here, and if they
     * persist the router classifies and fails over on the real code.
     *
     * <p>Only retryable codes are converted. A 4xx in the body is a genuine bad request that retrying
     * cannot fix, so it propagates as an {@link LlmException} for the caller to report.
     */
    private void rethrowInBodyError(String responseBody) {
        if (responseBody == null || responseBody.isBlank()) return;
        JsonNode response;
        try {
            response = mapper.readTree(responseBody);
        } catch (JsonProcessingException e) {
            return;                                   // not JSON: let the normal parse path report it
        }
        if (!response.isObject() || response.path("choices").isArray()) return;
        JsonNode error = response.path("error");
        if (!error.isObject() && !error.isTextual()) return;

        int code = error.path("code").asInt(0);
        if (code == 429 || (code >= 500 && code < 600)) {
            String message = error.isTextual() ? error.asText() : error.path("message").asText("");
            log.warn("Provider '{}' returned HTTP 200 carrying an error (code {}): {}",
                    provider, code, message);
            throw HttpServerErrorException.create(
                    HttpStatusCode.valueOf(code), message, HttpHeaders.EMPTY,
                    responseBody.getBytes(java.nio.charset.StandardCharsets.UTF_8), null);
        }
    }

    private String extractContent(String responseJson) throws JsonProcessingException {
        JsonNode response = mapper.readTree(responseJson);
        JsonNode choices  = response.path("choices");
        if (!choices.isArray() || choices.isEmpty()) {
            log.warn("OpenAI-compatible API returned unexpected response (no choices): {}", responseJson);
            throw providerError(response, responseJson);
        }
        JsonNode choice = choices.get(0);
        return terminalContent(choice.path("message"), choice.path("finish_reason").asText(), response);
    }
}
