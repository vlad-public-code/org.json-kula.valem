package org.json_kula.valem.api.llm;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.json_kula.valem.core.llm.LlmClient;
import org.springframework.web.client.RestClient;

/**
 * Builds a provider {@link LlmClient} from configuration values: the provider→base-URL table, the
 * per-provider default model, and the client selection.
 *
 * <p>Extracted from {@code LlmConfig} so a host application can construct additional provider
 * clients — a routing or failover layer needs several, and the same table — without duplicating the
 * mapping and letting the two copies drift. {@code LlmConfig} still owns the bean, the
 * {@code valem.llm.*} property surface, and every decorator it applies; this class is only the
 * switch, moved.
 *
 * <p>Stateless and side-effect free: it neither logs nor reads configuration, so a caller can build
 * as many clients as it needs and decide for itself what to say about them.
 */
public final class LlmClientFactory {

    private LlmClientFactory() {}

    private static final String OPENAI_BASE_URL      = "https://api.openai.com/v1";
    private static final String OLLAMA_BASE_URL      = "http://localhost:11434/v1";
    private static final String OPENROUTER_BASE_URL  = "https://openrouter.ai/api/v1";
    private static final String GROQ_BASE_URL        = "https://api.groq.com/openai/v1";
    private static final String MISTRAL_BASE_URL     = "https://api.mistral.ai/v1";
    private static final String GEMINI_BASE_URL      = "https://generativelanguage.googleapis.com/v1beta/openai/";
    private static final String CEREBRAS_BASE_URL    = "https://api.cerebras.ai/v1";

    /** Provider names this factory can build, for error messages and validation. */
    public static final String VALID_PROVIDERS =
            "anthropic, openai, ollama, openrouter, groq, mistral, gemini, cerebras";

    /**
     * Creates the provider client for {@code provider}.
     *
     * @param provider              provider name; case-insensitive, one of {@link #VALID_PROVIDERS}
     * @param apiKey                the provider credential
     * @param model                 model id; blank selects {@link #defaultModelFor}
     * @param maxTokens             completion token budget
     * @param baseUrl               API base URL; blank selects {@link #defaultBaseUrlFor}
     * @param promptCacheEnabled    Anthropic prompt caching; ignored by other providers
     * @param toolLoopMaxIterations cap on tool-calling round trips
     * @throws IllegalArgumentException if {@code provider} is not recognised
     */
    public static LlmClient create(String provider, String apiKey, String model, int maxTokens,
                                   String baseUrl, boolean promptCacheEnabled,
                                   int toolLoopMaxIterations, ObjectMapper mapper,
                                   RestClient.Builder restClientBuilder) {
        return create(provider, apiKey, model, maxTokens, baseUrl, promptCacheEnabled,
                toolLoopMaxIterations, StructuredOutputMode.SCHEMA, mapper, restClientBuilder);
    }

    /**
     * As above, choosing how much structured-output constraint to ask for.
     *
     * @param structuredOutput see {@link StructuredOutputMode}; ignored by Anthropic, which has no
     *                         {@code response_format}
     */
    public static LlmClient create(String provider, String apiKey, String model, int maxTokens,
                                   String baseUrl, boolean promptCacheEnabled,
                                   int toolLoopMaxIterations, StructuredOutputMode structuredOutput,
                                   ObjectMapper mapper, RestClient.Builder restClientBuilder) {
        String resolvedModel = model == null || model.isBlank() ? defaultModelFor(provider) : model;
        String url = baseUrl == null || baseUrl.isBlank() ? defaultBaseUrlFor(provider) : baseUrl;
        String key = provider == null ? "" : provider.toLowerCase();

        return switch (key) {
            case "openai", "ollama", "groq", "mistral", "gemini", "cerebras" ->
                    new OpenAiLlmClient(url, apiKey, resolvedModel, maxTokens, toolLoopMaxIterations,
                            structuredOutput, combinesResponseFormatWithTools(key),
                            mapper, restClientBuilder.build());
            // OpenRouter asks integrators to identify themselves; the headers are attribution only.
            case "openrouter" ->
                    new OpenAiLlmClient(url, apiKey, resolvedModel, maxTokens, toolLoopMaxIterations,
                            structuredOutput, combinesResponseFormatWithTools(key), mapper,
                            restClientBuilder
                                    .defaultHeader("HTTP-Referer", "https://github.com/vlad-public-code/valem")
                                    .defaultHeader("X-Title", "Valem")
                                    .build());
            case "anthropic" ->
                    new AnthropicLlmClient(apiKey, resolvedModel, maxTokens, promptCacheEnabled,
                            toolLoopMaxIterations, mapper, restClientBuilder.build());
            default -> throw new IllegalArgumentException(
                    "Unknown LLM provider: '" + provider + "'. Valid values: " + VALID_PROVIDERS);
        };
    }

    /**
     * Whether {@code provider} accepts {@code response_format} on a request that also carries
     * {@code tools}.
     *
     * <p>Almost every OpenAI-compatible provider does, and the ones that struggle with structured
     * output struggle by rung — which is what {@link StructuredOutputMode} is for. <b>Groq is
     * different</b>: it answers {@code 400 "json mode cannot be combined with tool/function calling"}
     * to any {@code response_format} whenever tools are present, measured across
     * {@code json_object} and {@code json_schema} on every one of its chat models. That is a fixed
     * provider rule rather than a per-deployment capability, so it belongs in this table next to the
     * base URLs, not in an operator's configuration: the spec-generation tool loop is on by default,
     * so without this a correctly-configured Groq key fails its very first call.
     *
     * <p>Only the tool-carrying requests are affected. Plain completions and the tool loop's final
     * tools-withheld answer keep whatever {@link StructuredOutputMode} was configured.
     */
    public static boolean combinesResponseFormatWithTools(String provider) {
        return !"groq".equalsIgnoreCase(provider == null ? "" : provider.trim());
    }

    /** True when {@code provider} is one this factory can build. */
    public static boolean isKnownProvider(String provider) {
        return switch (provider == null ? "" : provider.toLowerCase()) {
            case "anthropic", "openai", "ollama", "openrouter", "groq", "mistral", "gemini", "cerebras" -> true;
            default -> false;
        };
    }

    /**
     * A sensible default model for each provider, used when no model is configured.
     * These are starting points meant to be overridden per deployment (see {@code configuration.md}).
     * Unknown providers fall back to the Anthropic default ({@link #create} rejects them anyway).
     */
    public static String defaultModelFor(String provider) {
        return switch (provider == null ? "" : provider.toLowerCase()) {
            case "anthropic"  -> "claude-sonnet-4-6";
            case "openai"     -> "gpt-4o";
            case "mistral"    -> "mistral-large-latest";
            // Groq retired the llama-3.3-70b-versatile default that used to live here; it now
            // answers model_not_found. gpt-oss-120b is a listed production model that does tool
            // calling, which spec generation needs.
            case "groq"       -> "openai/gpt-oss-120b";
            case "gemini"     -> "gemini-2.0-flash";
            case "cerebras"   -> "llama-3.3-70b";
            case "ollama"     -> "llama3.1";
            case "openrouter" -> "anthropic/claude-3.7-sonnet";
            default           -> "claude-sonnet-4-6";
        };
    }

    /**
     * The API base URL for each provider, used when none is configured. Anthropic returns
     * {@code null}: {@link AnthropicLlmClient} owns its own endpoint and takes no base URL.
     */
    public static String defaultBaseUrlFor(String provider) {
        return switch (provider == null ? "" : provider.toLowerCase()) {
            case "openai"     -> OPENAI_BASE_URL;
            case "ollama"     -> OLLAMA_BASE_URL;
            case "openrouter" -> OPENROUTER_BASE_URL;
            case "groq"       -> GROQ_BASE_URL;
            case "mistral"    -> MISTRAL_BASE_URL;
            case "gemini"     -> GEMINI_BASE_URL;
            case "cerebras"   -> CEREBRAS_BASE_URL;
            default           -> null;      // anthropic and unknown: no base URL concept
        };
    }
}
