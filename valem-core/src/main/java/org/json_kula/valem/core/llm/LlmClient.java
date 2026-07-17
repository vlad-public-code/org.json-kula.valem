package org.json_kula.valem.core.llm;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.List;
import java.util.function.Consumer;

/**
 * Minimal interface for a text-completion LLM.
 *
 * <p>Implementations may call a provider API, a local model, or a stub for testing.
 * Only a single synchronous method is required; streaming is handled at the application layer.
 *
 * <p>Implementations that support native tool calling should override
 * {@link #completeWithTools}; others inherit the default no-op fallback.
 */
@FunctionalInterface
public interface LlmClient {

    /**
     * Sends {@code prompt} to the model and returns the raw text response.
     *
     * @param prompt the full prompt to send
     * @return the model's raw text output
     * @throws LlmException if the LLM call fails (network, auth, rate limit, etc.)
     */
    String complete(String prompt) throws LlmException;

    /**
     * Same as {@link #complete(String)} but requests a specific sampling {@code temperature}
     * (0 = deterministic). The default ignores it and delegates to {@link #complete(String)};
     * provider clients (Anthropic, OpenAI-compatible) override it. Used to make repair attempts
     * more focused than the initial creative generation.
     */
    default String complete(String prompt, double temperature) throws LlmException {
        return complete(prompt);
    }

    /**
     * Generates a response, transparently executing any tool calls the LLM makes
     * before returning the final text.
     *
     * <p>All of {@code tools} are offered to the model; the single {@code executor} routes each
     * invocation by {@link ToolCall#name()}. The default implementation ignores the tools and
     * delegates to {@link #complete}. Implementations that support native tool use (Anthropic,
     * OpenAI) should override this.
     */
    default String completeWithTools(String prompt, List<ToolDefinition> tools, ToolExecutor executor)
            throws LlmException {
        return complete(prompt);
    }

    /** Tool-calling variant of {@link #complete(String, double)}. */
    default String completeWithTools(String prompt, List<ToolDefinition> tools, ToolExecutor executor,
                                     double temperature) throws LlmException {
        return completeWithTools(prompt, tools, executor);
    }

    /**
     * Completion with full {@link CompletionOptions} — sampling temperature and an optional JSON
     * response schema (provider "structured output"). The default honours only the temperature and
     * ignores the schema, delegating to the simpler methods; clients that support native structured
     * output (the OpenAI-compatible client) override this to send the schema as {@code response_format}.
     */
    default String complete(String prompt, CompletionOptions options) throws LlmException {
        return options != null && options.temperature() != null
                ? complete(prompt, options.temperature()) : complete(prompt);
    }

    /** Tool-calling variant of {@link #complete(String, CompletionOptions)}. */
    default String completeWithTools(String prompt, List<ToolDefinition> tools, ToolExecutor executor,
                                     CompletionOptions options) throws LlmException {
        return options != null && options.temperature() != null
                ? completeWithTools(prompt, tools, executor, options.temperature())
                : completeWithTools(prompt, tools, executor);
    }

    /**
     * Tool-calling variant with a real-time progress callback. Fires
     * {@link LlmProgressEvent.ToolCalling} before each tool invocation and
     * {@link LlmProgressEvent.ToolCompleted} after. Implementations that do not override this
     * fall back to {@link #completeWithTools(String, List, ToolExecutor, CompletionOptions)}.
     */
    default String completeWithTools(String prompt, List<ToolDefinition> tools, ToolExecutor executor,
                                     CompletionOptions options, Consumer<LlmProgressEvent> onProgress)
            throws LlmException {
        return completeWithTools(prompt, tools, executor, options);
    }

    // ── System/user split (prompt caching) ─────────────────────────────────────

    /**
     * Completion from a {@link SpecGenerationPrompt.PromptParts} (separate {@code system} + {@code
     * user}). The default concatenates them and delegates, so every existing implementation keeps
     * working unchanged; provider clients override this to send the system context as a distinct role
     * (Anthropic caches its stable prefix; OpenAI-compatible providers auto-cache long prefixes).
     */
    default String complete(SpecGenerationPrompt.PromptParts parts, CompletionOptions options)
            throws LlmException {
        return complete(parts.concatenated(), options);
    }

    /** Tool-calling variant of {@link #complete(SpecGenerationPrompt.PromptParts, CompletionOptions)}. */
    default String completeWithTools(SpecGenerationPrompt.PromptParts parts, List<ToolDefinition> tools,
                                     ToolExecutor executor, CompletionOptions options,
                                     Consumer<LlmProgressEvent> onProgress) throws LlmException {
        return completeWithTools(parts.concatenated(), tools, executor, options, onProgress);
    }

    // ── Tool-calling types ────────────────────────────────────────────────────

    /**
     * Per-call completion options. {@code temperature} {@code null} = provider default;
     * {@code responseSchema} {@code null} = no structured-output constraint (plain JSON object);
     * {@code maxTokens} {@code null} = the client's configured default (raised transiently on a
     * truncation retry so a too-tight budget does not force a permanently smaller spec).
     */
    record CompletionOptions(Double temperature, JsonNode responseSchema, Integer maxTokens) {
        public CompletionOptions(Double temperature, JsonNode responseSchema) {
            this(temperature, responseSchema, null);
        }
    }

    /** Describes a single tool the LLM may call. */
    record ToolDefinition(String name, String description, JsonNode inputSchema) {}

    /** A single tool invocation requested by the LLM. */
    record ToolCall(String id, String name, JsonNode arguments) {}

    /** Executes a tool call and returns the result text to feed back to the LLM. */
    @FunctionalInterface
    interface ToolExecutor {
        String execute(ToolCall call);
    }

    /** Unchecked exception thrown when an LLM call fails. */
    class LlmException extends RuntimeException {
        public LlmException(String message, Throwable cause) { super(message, cause); }
        public LlmException(String message) { super(message); }
    }
}
