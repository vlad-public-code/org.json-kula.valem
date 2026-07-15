package org.json_kula.valem.api.llm;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.json_kula.valem.core.llm.LlmClient;
import org.json_kula.valem.core.llm.LlmProgressEvent;
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

    public AnthropicLlmClient(String apiKey, String model, int maxTokens, ObjectMapper mapper,
                              RestClient restClient) {
        this.apiKey = apiKey;
        this.model = model;
        this.maxTokens = maxTokens;
        this.mapper = mapper;
        this.restClient = restClient;
    }

    @Override
    public String complete(String prompt) throws LlmException {
        return complete(prompt, (Double) null);
    }

    @Override
    public String complete(String prompt, double temperature) throws LlmException {
        return complete(prompt, Double.valueOf(temperature));
    }

    private String complete(String prompt, Double temperature) throws LlmException {
        log.debug("Calling Anthropic: model={} maxTokens={} temp={} promptLen={}",
                model, maxTokens, temperature, prompt.length());
        try {
            ObjectNode req = mapper.createObjectNode();
            req.put("model", model);
            req.put("max_tokens", maxTokens);
            if (temperature != null) req.put("temperature", temperature.doubleValue());
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
        return completeWithToolsImpl(prompt, toolDefs, executor, null, null);
    }

    @Override
    public String completeWithTools(String prompt, java.util.List<ToolDefinition> toolDefs,
                                    ToolExecutor executor, double temperature) throws LlmException {
        return completeWithToolsImpl(prompt, toolDefs, executor, Double.valueOf(temperature), null);
    }

    @Override
    public String completeWithTools(String prompt, java.util.List<ToolDefinition> toolDefs,
                                    ToolExecutor executor, CompletionOptions options,
                                    Consumer<LlmProgressEvent> onProgress) throws LlmException {
        return completeWithToolsImpl(prompt, toolDefs, executor,
                options == null ? null : options.temperature(), onProgress);
    }

    private String completeWithToolsImpl(String prompt, java.util.List<ToolDefinition> toolDefs,
                                         ToolExecutor executor, Double temperature,
                                         Consumer<LlmProgressEvent> onProgress) throws LlmException {
        log.debug("Calling Anthropic (tools): model={} tools={} temp={}",
                model, toolDefs.stream().map(ToolDefinition::name).toList(), temperature);
        try {
            ArrayNode tools = mapper.createArrayNode();
            for (ToolDefinition tool : toolDefs) {
                ObjectNode toolNode = tools.addObject();
                toolNode.put("name", tool.name());
                toolNode.put("description", tool.description());
                toolNode.set("input_schema", tool.inputSchema());
            }

            ArrayNode messages = mapper.createArrayNode();
            ObjectNode userMsg = messages.addObject();
            userMsg.put("role", "user");
            userMsg.put("content", prompt);

            for (;;) {
                ObjectNode req = mapper.createObjectNode();
                req.put("model", model);
                req.put("max_tokens", maxTokens);
                if (temperature != null) req.put("temperature", temperature.doubleValue());
                req.set("tools", tools);
                req.set("messages", messages);

                String responseJson = sendRequest(req);
                JsonNode response   = mapper.readTree(responseJson);
                String stopReason   = response.path("stop_reason").asText();
                JsonNode content    = response.path("content");

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
        return content.get(0).path("text").asText();
    }
}
