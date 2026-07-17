package org.json_kula.valem.api.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.json_kula.valem.api.llm.AnthropicLlmClient;
import org.json_kula.valem.api.llm.BraveSearchBackend;
import org.json_kula.valem.api.llm.ConcurrencyLimitingLlmClient;
import org.json_kula.valem.api.llm.DuckDuckGoSearchBackend;
import org.json_kula.valem.api.llm.LlmInteractionLog;
import org.json_kula.valem.api.llm.MockLlmClient;
import org.json_kula.valem.api.llm.OpenAiLlmClient;
import org.json_kula.valem.api.llm.RecordingLlmClient;
import org.json_kula.valem.api.llm.CompositeWebTool;
import org.json_kula.valem.api.llm.SearchBackend;
import org.json_kula.valem.api.llm.TavilySearchBackend;
import org.json_kula.valem.api.llm.WebFetchTool;
import org.json_kula.valem.api.llm.WebSearchTool;
import org.json_kula.valem.core.llm.JsonataEvalTool;
import org.json_kula.valem.core.llm.LlmClient;
import org.json_kula.valem.core.llm.SpecGenerator;
import org.json_kula.valem.core.llm.WebTool;

import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

@Configuration
@ConditionalOnExpression(
        "${valem.llm.mock:false}" +
        " or !'${valem.llm.api-key:}'.isBlank()" +
        " or 'ollama'.equalsIgnoreCase('${valem.llm.provider:anthropic}')")
public class LlmConfig {

    private static final Logger log = LoggerFactory.getLogger(LlmConfig.class);

    private static final String OPENAI_BASE_URL      = "https://api.openai.com/v1";
    private static final String OLLAMA_BASE_URL      = "http://localhost:11434/v1";
    private static final String OPENROUTER_BASE_URL  = "https://openrouter.ai/api/v1";
    private static final String GROQ_BASE_URL        = "https://api.groq.com/openai/v1";
    private static final String MISTRAL_BASE_URL     = "https://api.mistral.ai/v1";

    @Bean
    LlmClient llmClient(
            @Value("${valem.llm.provider:anthropic}") String provider,
            @Value("${valem.llm.api-key:}") String apiKey,
            @Value("${valem.llm.model:}") String configuredModel,
            @Value("${valem.llm.max-tokens:8192}") int maxTokens,
            @Value("${valem.llm.base-url:}") String baseUrl,
            @Value("${valem.llm.mock:false}") boolean mock,
            @Value("${valem.llm.max-concurrent-requests:0}") int maxConcurrentRequests,
            @Value("${valem.llm.prompt-cache.enabled:true}") boolean promptCacheEnabled,
            @Value("${valem.llm.tool-loop.max-iterations:40}") int toolLoopMaxIterations,
            ObjectMapper mapper,
            RestClient.Builder restClientBuilder,
            LlmInteractionLog interactionLog) {

        // Resolve the model: an explicit valem.llm.model wins; otherwise pick a default
        // appropriate for the chosen provider. The old fixed default (claude-sonnet-4-6) is wrong
        // for every non-Anthropic provider, so setting only the provider + key now "just works".
        String model = configuredModel.isBlank() ? defaultModelFor(provider) : configuredModel;

        LlmClient inner;
        if (mock) {
            inner = new MockLlmClient();
        } else {
            log.info("LLM provider '{}' using model '{}'{}", provider, model,
                    configuredModel.isBlank() ? " (provider default)" : "");
            inner = switch (provider.toLowerCase()) {
                case "openai" -> {
                    String url = baseUrl.isBlank() ? OPENAI_BASE_URL : baseUrl;
                    yield new OpenAiLlmClient(url, apiKey, model, maxTokens, toolLoopMaxIterations, mapper,
                            restClientBuilder.build());
                }
                case "ollama" -> {
                    String url = baseUrl.isBlank() ? OLLAMA_BASE_URL : baseUrl;
                    yield new OpenAiLlmClient(url, apiKey, model, maxTokens, toolLoopMaxIterations, mapper,
                            restClientBuilder.build());
                }
                case "openrouter" -> {
                    String url = baseUrl.isBlank() ? OPENROUTER_BASE_URL : baseUrl;
                    yield new OpenAiLlmClient(url, apiKey, model, maxTokens, toolLoopMaxIterations, mapper,
                            restClientBuilder
                                    .defaultHeader("HTTP-Referer", "https://github.com/vlad-public-code/valem")
                                    .defaultHeader("X-Title", "Valem")
                                    .build());
                }
                case "groq" -> {
                    String url = baseUrl.isBlank() ? GROQ_BASE_URL : baseUrl;
                    yield new OpenAiLlmClient(url, apiKey, model, maxTokens, toolLoopMaxIterations, mapper,
                            restClientBuilder.build());
                }
                case "mistral" -> {
                    String url = baseUrl.isBlank() ? MISTRAL_BASE_URL : baseUrl;
                    yield new OpenAiLlmClient(url, apiKey, model, maxTokens, toolLoopMaxIterations, mapper,
                            restClientBuilder.build());
                }
                case "gemini" -> {
                    String url = baseUrl.isBlank()
                            ? "https://generativelanguage.googleapis.com/v1beta/openai/"
                            : baseUrl;
                    yield new OpenAiLlmClient(url, apiKey, model, maxTokens, toolLoopMaxIterations, mapper,
                            restClientBuilder.build());
                }
                case "cerebras" -> {
                    String url = baseUrl.isBlank() ? "https://api.cerebras.ai/v1" : baseUrl;
                    yield new OpenAiLlmClient(url, apiKey, model, maxTokens, toolLoopMaxIterations, mapper,
                            restClientBuilder.build());
                }
                case "anthropic" -> new AnthropicLlmClient(apiKey, model, maxTokens, promptCacheEnabled,
                        toolLoopMaxIterations, mapper, restClientBuilder.build());
                default -> throw new IllegalArgumentException(
                        "Unknown LLM provider: '" + provider + "'. Valid values: anthropic, openai, ollama, openrouter, groq, mistral, gemini, cerebras");
            };
        }
        LlmClient client = new RecordingLlmClient(inner, interactionLog);
        // Optionally cap simultaneous LLM calls: throttled keys 429 when generations overlap.
        // 0 (default) = unlimited; 1 = fully serialised app-wide.
        if (maxConcurrentRequests > 0) {
            log.info("Limiting LLM concurrency to {} in-flight request(s)", maxConcurrentRequests);
            client = new ConcurrencyLimitingLlmClient(client, maxConcurrentRequests);
        }
        return client;
    }

    /**
     * A sensible default model for each provider, used when {@code valem.llm.model} is not set.
     * These are starting points meant to be overridden per deployment (see {@code configuration.md}).
     * Unknown providers fall back to the Anthropic default (the {@code llmClient} switch rejects them
     * anyway).
     */
    static String defaultModelFor(String provider) {
        return switch (provider == null ? "" : provider.toLowerCase()) {
            case "anthropic"  -> "claude-sonnet-4-6";
            case "openai"     -> "gpt-4o";
            case "mistral"    -> "mistral-large-latest";
            case "groq"       -> "llama-3.3-70b-versatile";
            case "gemini"     -> "gemini-2.0-flash";
            case "cerebras"   -> "llama-3.3-70b";
            case "ollama"     -> "llama3.1";
            case "openrouter" -> "anthropic/claude-3.7-sonnet";
            default           -> "claude-sonnet-4-6";
        };
    }

    /**
     * The tool set offered to the LLM during spec generation. Three tools, all optional:
     * <ul>
     *   <li>{@code eval_jsonata} — evaluate a candidate expression against a sample input (local,
     *       no network); on unless {@code valem.llm.eval-tool.enabled=false}.</li>
     *   <li>{@code web_search} — find authoritative URLs instead of guessing; on unless
     *       {@code valem.llm.web-search.enabled=false}. Backed by a pluggable
     *       {@link SearchBackend} selected via {@code valem.llm.web-search.provider}:
     *       {@code duckduckgo} (default, keyless but fragile against datacenter IPs — see
     *       {@link DuckDuckGoSearchBackend}), {@code brave}, or {@code tavily} (both need
     *       {@code valem.llm.web-search.api-key}, recommended for a public-facing deployment).</li>
     *   <li>{@code web_fetch} — read a page's text; on unless {@code valem.llm.web-fetch.enabled=false}.</li>
     * </ul>
     * The bean exists when at least one tool is enabled (web-fetch or eval-tool — both default on).
     * Web tools are listed before {@code eval_jsonata} so the model is still nudged to search before
     * fetching.
     */
    @Bean
    @ConditionalOnExpression(
            "${valem.llm.web-fetch.enabled:true} or ${valem.llm.eval-tool.enabled:true}")
    WebTool llmTools(
            @Value("${valem.llm.web-fetch.enabled:true}") boolean fetchEnabled,
            @Value("${valem.llm.web-fetch.max-calls:5}") int maxFetchCalls,
            @Value("${valem.llm.web-fetch.max-chars:8000}") int maxChars,
            @Value("${valem.llm.web-search.enabled:true}") boolean searchEnabled,
            @Value("${valem.llm.web-search.provider:duckduckgo}") String searchProvider,
            @Value("${valem.llm.web-search.api-key:}") String searchApiKey,
            @Value("${valem.llm.web-search.max-calls:3}") int maxSearchCalls,
            @Value("${valem.llm.web-search.max-results:5}") int maxSearchResults,
            @Value("${valem.llm.eval-tool.enabled:true}") boolean evalEnabled,
            @Value("${valem.llm.eval-tool.max-calls:25}") int maxEvalCalls,
            ObjectMapper mapper) {

        List<WebTool> tools = new ArrayList<>();
        StringBuilder enabled = new StringBuilder();
        if (fetchEnabled) {
            if (searchEnabled) {
                SearchBackend backend = switch (searchProvider.toLowerCase()) {
                    case "brave" -> new BraveSearchBackend(searchApiKey, mapper);
                    case "tavily" -> new TavilySearchBackend(searchApiKey, mapper);
                    case "duckduckgo" -> new DuckDuckGoSearchBackend();
                    default -> throw new IllegalArgumentException(
                            "Unknown web search provider: '" + searchProvider +
                            "'. Valid values: duckduckgo, brave, tavily");
                };
                tools.add(new WebSearchTool(maxSearchCalls, maxSearchResults, backend));
                enabled.append("web_search[").append(searchProvider).append("] (")
                        .append(maxSearchCalls).append(" calls, ")
                        .append(maxSearchResults).append(" results) + ");
            }
            tools.add(new WebFetchTool(maxFetchCalls, maxChars));
            enabled.append("web_fetch (").append(maxFetchCalls).append(" calls) + ");
        }
        if (evalEnabled) {
            tools.add(new JsonataEvalTool(maxEvalCalls));
            enabled.append("eval_jsonata (").append(maxEvalCalls).append(" calls) + ");
        }
        // Trim the trailing " + " for a clean log line.
        String summary = enabled.length() >= 3 ? enabled.substring(0, enabled.length() - 3) : "(none)";
        log.info("LLM tools: {}", summary);

        return tools.size() == 1 ? tools.get(0) : new CompositeWebTool(tools);
    }

    @Bean
    SpecGenerator specGenerator(
            LlmClient llmClient,
            ObjectMapper mapper,
            @Value("${valem.llm.max-retries:3}") int maxRetries,
            @Value("${valem.llm.max-retries-hard:6}") int maxRetriesHard,
            @Value("${valem.llm.repair-temperature:0.2}") double repairTemperature,
            @Value("${valem.llm.repair-temperature-step:0.15}") double repairTemperatureStep,
            @Value("${valem.llm.repair-temperature-max:0.8}") double repairTemperatureMax,
            @Value("${valem.llm.generation-temperature:0.0}") double generationTemperature,
            @Value("${valem.llm.structured-output.enabled:true}") boolean structuredOutput,
            @Value("${valem.llm.max-tokens:8192}") int maxTokens,
            @Value("${valem.llm.max-tokens-hard:16384}") int maxTokensHard,
            @Autowired(required = false) WebTool webTool) {
        return new SpecGenerator(llmClient, mapper, maxRetries, maxRetriesHard,
                repairTemperature, generationTemperature, structuredOutput,
                maxTokens, maxTokensHard, repairTemperatureStep, repairTemperatureMax, webTool);
    }
}
