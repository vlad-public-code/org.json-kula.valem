package org.json_kula.valem.api.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.json_kula.valem.core.llm.LlmClient;
import org.json_kula.valem.core.llm.LlmProgressEvent;
import org.json_kula.valem.core.llm.SpecGenerationPrompt;
import org.json_kula.valem.core.llm.SpecGenerator;
import org.json_kula.valem.core.llm.SpecGenerator.GenerationResult;
import org.json_kula.valem.core.llm.WebTool;
import org.json_kula.valem.core.model.ModelSpec;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.RestClient;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Real-LLM regression test for the sandbox report: "fuel refueling log with consumption graph" ended
 * in {@code Response was not valid JSON: No content to map due to end-of-input}.
 *
 * <p>That message means the generator was handed an <b>empty</b> response body. Several provider
 * behaviours produce one, and this test runs the real prompt against both providers the sandbox is
 * wired to so a regression in any of them shows up as a failure rather than as a bad model:
 * <ul>
 *   <li>a <b>reasoning</b> model ({@code gemini-2.5-flash}, Groq's {@code openai/gpt-oss-*}) bills its
 *       chain of thought against the same {@code max_tokens} budget as the answer, so a budget
 *       reasoning alone exhausts comes back {@code finish_reason: "length"} with {@code content: ""}
 *       — or, on Gemini 3.x, with no {@code content} field at all;</li>
 *   <li>a provider that returns {@code content} as an array of typed chunks, or that reports
 *       {@code finish_reason: "stop"} on a message that still carries {@code tool_calls} — both read
 *       as empty to a naive accessor;</li>
 *   <li>a completion cut off before its first character.</li>
 * </ul>
 *
 * <p>The generator is built with the <b>sandbox's</b> token budget (4096, the tight one behind the
 * report), not valem-api's roomier default, so what fails here is what a sandbox user sees.
 *
 * <p><b>Provider capacity — expect skips here, and read them.</b> Free tiers run out, in two
 * different ways, and neither is a defect this test should redden the build for:
 * <ul>
 *   <li><b>Gemini: 20 requests per DAY per model</b> (measured —
 *       {@code "limit: 20, model: gemini-2.5-flash"}). One generation costs 4-10 calls, so this
 *       parameter can be run two or three times a day before it starts aborting on quota. That
 *       ceiling is also why Gemini is the sandbox's FALLBACK rather than its primary.</li>
 *   <li><b>Groq</b>, once in this chain: 8,000 tokens per minute, admitted on
 *       {@code prompt + max_tokens}, against a system prompt of ~7.9k tokens — every call refused
 *       before the model saw it, which is why it is gone entirely.</li>
 *   <li><b>OpenRouter</b> serves {@code :free} models from a shared upstream pool, so a
 *       "temporarily rate-limited upstream" 429 is normal weather rather than an exhausted
 *       allowance; {@code LlmRetry}'s backoff absorbs it and only a sustained one aborts.</li>
 * </ul>
 * Both abort (skip) with the provider's own message. Only a genuine capacity refusal does — a 400,
 * a retired model id, or a bad key fails, because those are defects on our side of the wire.
 *
 * <p>Skipped unless the provider's key is supplied. Run with:
 * <pre>{@code
 * mvn test -pl valem-api -Dtest=FuelLogMultiProviderGenerateIT \
 *     -Dvalem.it.mistral.api-key=<key> -Dvalem.it.gemini.api-key=<key>
 * }</pre>
 * Keys may also come from the {@code MISTRAL_API_KEY} / {@code GEMINI_API_KEY} environment variables.
 */
@SpringBootTest(properties = {
        // Boot the context without an LLM of its own: this test builds its clients per provider.
        "valem.llm.mock=true",
        // Local tools only. eval_jsonata and get_domain_guidance are what the generator leans on for
        // correctness (and they keep the tool loop — where the empty-response bug lives — in play);
        // the network tools would make the test depend on a search key and a scrapeable back end.
        "valem.llm.web-fetch.enabled=false",
        "valem.llm.web-search.enabled=false",
})
class FuelLogMultiProviderGenerateIT {

    private static final Logger log = LoggerFactory.getLogger(FuelLogMultiProviderGenerateIT.class);

    /** The exact sandbox prompt from the bug report — deliberately terse, as a real user types it. */
    private static final String DOMAIN_DESCRIPTION = "fuel refueling log with consumption graph";
    private static final String MODEL_ID = "fuel-refueling-log";

    // Sandbox generation budget (valem-sandbox/application.properties), which is what reproduces it.
    // Keep these in step with that file: the point of this test is to run what a sandbox user runs.
    // 8192 rather than the original 4096 because a reasoning model bills its chain of thought against
    // the same budget — at 4096 gemini-2.5-flash spent ~3,100 tokens thinking and truncated the spec
    // it was still writing. It is also what the truncation recovery doubles from, so the two numbers
    // have to move together (see the note above valem.llm.max-tokens in the sandbox config).
    private static final int MAX_TOKENS       = 8192;
    private static final int MAX_TOKENS_HARD  = 16384;
    private static final int MAX_RETRIES      = 4;
    private static final int MAX_RETRIES_HARD = 10;

    /** Per-slot overrides, mirroring {@code valem.sandbox.llm.providers[n].*}. */
    private static final Map<String, String> SLOT_REASONING_EFFORT = Map.of("gemini", "low");

    /**
     * Pinned models, mirroring the sandbox slots. OpenRouter's is pinned here for the same reason it
     * is pinned there: a {@code :free} id is the one most likely to move, and an unpinned slot would
     * silently start testing whatever the library default happens to be.
     */
    private static final Map<String, String> SLOT_MODEL =
            Map.of("openrouter", "nvidia/nemotron-3-super-120b-a12b:free");

    /** The reported symptom, matched by name so a re-occurrence is unmistakable in the report. */
    private static final String EMPTY_RESPONSE_SYMPTOM = "No content to map due to end-of-input";

    @Autowired ObjectMapper mapper;
    @Autowired RestClient.Builder restClientBuilder;
    @Autowired org.json_kula.valem.service.ModelService modelService;
    @Autowired(required = false) WebTool webTool;

    @ParameterizedTest(name = "{0} generates a valid spec for \"" + DOMAIN_DESCRIPTION + "\"")
    @ValueSource(strings = {"openrouter", "gemini", "mistral"})
    void generates_valid_spec_with_view(String provider) throws Exception {
        String apiKey = apiKey(provider);
        Assumptions.assumeTrue(apiKey != null && !apiKey.isBlank(),
                "Skipping " + provider + " — set -Dvalem.it." + provider + ".api-key or "
                + provider.toUpperCase(Locale.ROOT) + "_API_KEY");

        ResponseRecorder recorder = new ResponseRecorder(clientFor(provider, apiKey));
        SpecGenerator generator = generatorFor(recorder);

        log.info("[{}] generating \"{}\" (model={}, maxTokens={}, reasoningEffort={})",
                provider, DOMAIN_DESCRIPTION,
                modelFor(provider).isBlank() ? "provider default" : modelFor(provider), MAX_TOKENS,
                SLOT_REASONING_EFFORT.getOrDefault(provider, "provider default"));
        GenerationResult result;
        try {
            result = generator.generate(MODEL_ID, DOMAIN_DESCRIPTION, true, progressLogger(provider));
        } catch (LlmClient.LlmException e) {
            // Only a CAPACITY refusal is skippable: the key's plan cannot carry a request this size,
            // which is not something the code can fix. Everything else — a 400 for an unsupported
            // parameter combination, a dead model id, a bad key — is exactly the class of bug this
            // test exists to catch, so it must fail rather than quietly turn into a green skip.
            if (isCapacityRefusal(e)) {
                Assumptions.abort("[" + provider + "] the key's plan cannot carry a request this size "
                                  + "(not a generation defect): " + e.getMessage());
            }
            throw e;
        }

        recorder.logEmptyResponses(provider);

        if (result instanceof GenerationResult.Failure failure) {
            log.error("[{}] generation FAILED after {} attempt(s). Errors: {}",
                    provider, failure.attemptsUsed(), failure.lastErrors());
            log.error("[{}] last raw response ({} chars):\n{}", provider,
                    failure.lastRawResponse() == null ? 0 : failure.lastRawResponse().length(),
                    failure.lastRawResponse());
            assertThat(failure.lastErrors().stream().map(e -> e.message()).toList())
                    .as("empty-response regression: the model returned nothing and the generator "
                        + "reported it as unparseable JSON")
                    .noneMatch(m -> m.contains(EMPTY_RESPONSE_SYMPTOM));
        }

        assertThat(result)
                .as("%s must produce a structurally valid spec for a terse, chart-shaped prompt", provider)
                .isInstanceOf(GenerationResult.Success.class);

        GenerationResult.Success success = (GenerationResult.Success) result;
        ModelSpec spec = success.spec();
        log.info("[{}] SUCCESS after {} attempt(s):\n{}", provider, success.attemptsUsed(),
                mapper.writerWithDefaultPrettyPrinter().writeValueAsString(spec));

        // The prompt asks for a log (a list of fill-ups) that drives a graph, so the spec must carry
        // both halves: something computed per entry, and a chart to show it in.
        assertThat(spec.derivations())
                .as("a consumption model must derive something (l/100km, cost, totals)")
                .isNotEmpty();
        JsonNode view = spec.viewDefinition();
        assertThat(view).as("includeView=true must yield a viewDefinition").isNotNull();
        assertThat(chartComponents(view))
                .as("\"with consumption graph\" must produce a dataChart; view was:\n%s", view)
                .isNotEmpty();
        // A chart with no series plots nothing — the mistake that renders as an empty box.
        assertThat(chartComponents(view))
                .allSatisfy(chart -> {
                    assertThat(chart.path("bind").asText())
                            .as("a chart plots an array, so it must bind one: %s", chart)
                            .startsWith("$.");
                    assertThat(chart.path("chartSeries"))
                            .as("a chart with no series renders an empty box: %s", chart)
                            .isNotEmpty();
                });

        // "Valid" means the sandbox can actually create it: compiling + seeding the initial state is
        // the same gate POST /models applies, and it is where a spec that merely parses still 409s.
        modelService.createModel(spec.withId(MODEL_ID + "-" + provider));
        log.info("[{}] model registered — the spec is usable, not merely parseable", provider);
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    /**
     * Whether {@code e} is the provider saying "this request is bigger than your plan allows" — a
     * 413/429 whose body names a token ceiling. Deliberately narrow: a 400, a 404 on a retired model
     * id, or a 401 must fail the test, because those are defects on our side of the wire.
     */
    private static boolean isCapacityRefusal(Throwable e) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (t instanceof HttpStatusCodeException http) {
                int status = http.getStatusCode().value();
                if (status != 413 && status != 429) return false;
                String body = http.getResponseBodyAsString().toLowerCase(Locale.ROOT);
                return body.contains("rate_limit") || body.contains("tokens per minute")
                       || body.contains("too large") || body.contains("quota");
            }
        }
        return false;
    }

    /** Every {@code dataChart} component anywhere in the view tree. */
    private static List<JsonNode> chartComponents(JsonNode view) {
        List<JsonNode> found = new ArrayList<>();
        collectCharts(view, found);
        return found;
    }

    private static void collectCharts(JsonNode node, List<JsonNode> found) {
        if (node == null) return;
        if (node.isObject()) {
            if ("dataChart".equals(node.path("type").asText())) found.add(node);
            node.forEach(child -> collectCharts(child, found));
        } else if (node.isArray()) {
            node.forEach(child -> collectCharts(child, found));
        }
    }

    private LlmClient clientFor(String provider, String apiKey) {
        return LlmClientFactory.create(
                provider, apiKey, modelFor(provider), MAX_TOKENS,
                /* baseUrl = provider default */ "", /* promptCache */ true,
                /* toolLoopMaxIterations */ 40, StructuredOutputMode.SCHEMA,
                SLOT_REASONING_EFFORT.get(provider), mapper, restClientBuilder);
    }

    private SpecGenerator generatorFor(LlmClient client) {
        return new SpecGenerator(client, mapper, MAX_RETRIES, MAX_RETRIES_HARD,
                /* repairTemperature */ 0.2, /* generationTemperature */ 0.0,
                /* structuredOutput */ true, MAX_TOKENS, MAX_TOKENS_HARD,
                /* repairTemperatureStep */ 0.15, /* repairTemperatureMax */ 0.8, webTool);
    }

    private static Consumer<LlmProgressEvent> progressLogger(String provider) {
        return event -> log.info("[{}] {}", provider, event);
    }

    /**
     * The pinned model, or a {@code -Dvalem.it.<provider>.model=...} override. The override exists to
     * make comparing candidate models a one-flag job — which is how the OpenRouter slot's model was
     * chosen — rather than an edit-recompile loop.
     */
    private static String modelFor(String provider) {
        String override = System.getProperty("valem.it." + provider + ".model");
        if (override != null && !override.isBlank()) return override;
        return SLOT_MODEL.getOrDefault(provider, "");
    }

    private static String apiKey(String provider) {
        String fromProperty = System.getProperty("valem.it." + provider + ".api-key");
        if (fromProperty != null && !fromProperty.isBlank()) return fromProperty;
        return System.getenv(provider.toUpperCase(Locale.ROOT) + "_API_KEY");
    }

    /**
     * Wraps the provider client to keep every raw response, so a failing run says which attempt came
     * back empty — the one thing the generator's own error message ("not valid JSON") cannot tell you.
     */
    private static final class ResponseRecorder implements LlmClient {
        private final LlmClient delegate;
        private final List<String> responses = new ArrayList<>();

        ResponseRecorder(LlmClient delegate) { this.delegate = delegate; }

        private String record(String response) {
            responses.add(response);
            // Dump every raw response when asked. A malformed response is often only diagnosable
            // from the exact bytes — our JSON repair passes can turn one kind of malformation into a
            // DIFFERENT, still-broken document, and by then the original is gone.
            String dir = System.getProperty("valem.it.dump-responses");
            if (dir != null && !dir.isBlank()) {
                try {
                    java.nio.file.Path out = java.nio.file.Path.of(dir);
                    java.nio.file.Files.createDirectories(out);
                    java.nio.file.Files.writeString(
                            out.resolve("response-" + responses.size() + ".txt"),
                            response == null ? "" : response);
                } catch (Exception e) {
                    log.warn("could not dump raw response: {}", e.toString());
                }
            }
            return response;
        }

        void logEmptyResponses(String provider) {
            for (int i = 0; i < responses.size(); i++) {
                String r = responses.get(i);
                log.info("[{}] response {}/{}: {} chars{}", provider, i + 1, responses.size(),
                        r == null ? 0 : r.length(),
                        r == null || r.isBlank() ? "  <<< EMPTY" : "");
            }
        }

        @Override public String complete(String prompt) { return record(delegate.complete(prompt)); }

        @Override public String complete(SpecGenerationPrompt.PromptParts parts, CompletionOptions options) {
            return record(delegate.complete(parts, options));
        }

        @Override public String completeWithTools(SpecGenerationPrompt.PromptParts parts,
                                                  List<ToolDefinition> tools, ToolExecutor executor,
                                                  CompletionOptions options,
                                                  Consumer<LlmProgressEvent> onProgress) {
            return record(delegate.completeWithTools(parts, tools, executor, options, onProgress));
        }

        @Override public org.json_kula.valem.core.llm.LlmDescriptor describe() { return delegate.describe(); }
    }
}
