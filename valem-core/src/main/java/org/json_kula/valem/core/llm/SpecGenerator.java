package org.json_kula.valem.core.llm;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.json_kula.valem.core.blob.InMemoryBlobStore;
import org.json_kula.valem.core.engine.ConstraintEvaluator;
import org.json_kula.valem.core.engine.ModelRuntime;
import org.json_kula.valem.core.engine.TestCaseRunner;
import org.json_kula.valem.core.graph.CompiledModel;
import org.json_kula.valem.core.graph.ModelSpecCompiler;
import org.json_kula.valem.core.graph.ModelSpecValidator;
import org.json_kula.valem.core.graph.SpecEvolution;
import org.json_kula.valem.core.model.DerivationSpec;
import org.json_kula.valem.core.model.ModelSpec;
import org.json_kula.valem.core.state.ModelState;
import org.json_kula.valem.core.state.PathConverter;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import java.util.stream.Collectors;

/**
 * Drives the LLM-based spec generation / repair feedback loop.
 *
 * <p>The loop:
 * <ol>
 *   <li>Build an initial (or repair) prompt.</li>
 *   <li>Send it to the {@link LlmClient}.</li>
 *   <li>Parse the response as {@link ModelSpec} JSON.</li>
 *   <li>Validate with {@link ModelSpecValidator}.</li>
 *   <li>If valid, return the spec.</li>
 *   <li>If not, build a repair prompt with the error list and retry (up to {@code maxRetries}).</li>
 * </ol>
 *
 * <p>Also supports incremental evolution via {@link #generateEvolution}.
 */
public final class SpecGenerator {

    private static final int DEFAULT_MAX_RETRIES = 3;
    /**
     * Default sampling temperature for repair attempts. Slightly <em>above</em> the (typically 0)
     * generation temperature on purpose: after a deterministic attempt failed, a little randomness
     * helps the model escape the rut and try a different fix rather than re-emitting the same output.
     */
    private static final double DEFAULT_REPAIR_TEMPERATURE = 0.2;
    /** Default per-repeat increase in repair temperature (see {@link #repairTemperatureStep}). */
    private static final double DEFAULT_REPAIR_TEMPERATURE_STEP = 0.15;
    /** Default ceiling the escalating repair temperature is clamped to. */
    private static final double DEFAULT_REPAIR_TEMPERATURE_MAX = 0.8;

    private final LlmClient   llm;
    private final ObjectMapper mapper;
    private final int          maxRetries;      // base budget — always attempted
    private final int          maxRetriesHard;  // ceiling — extra attempts only while converging
    private final double       repairTemperature; // sampling temperature on the FIRST repair attempt
    // Each subsequent repair attempt raises the temperature by this step (clamped to
    // repairTemperatureMax), so a model stuck re-emitting the same failing output gets progressively
    // more room to escape the rut. 0 = flat repair temperature (old behavior).
    private final double       repairTemperatureStep;
    private final double       repairTemperatureMax;
    // Sampling temperature for the INITIAL attempt; null = provider default. A low value (~0) makes
    // the first attempt deterministic and structured rather than creative.
    private final Double       generationTemperature;
    // When true, send the ModelSpec/SpecEvolution JSON Schema as the provider response_format so the
    // model's output shape is provider-enforced (clients without native support ignore it).
    private final boolean      structuredOutput;
    // Adaptive truncation recovery: the client's configured max_tokens and the hard ceiling to raise it
    // to on the first truncation (both null = feature off → the old "smaller spec" fallback only).
    private final Integer      maxTokens;
    private final Integer      maxTokensHard;
    private final WebTool      webTool;   // null → no tool use

    public SpecGenerator(LlmClient llm, ObjectMapper mapper) {
        this(llm, mapper, DEFAULT_MAX_RETRIES, null);
    }

    public SpecGenerator(LlmClient llm, ObjectMapper mapper, int maxRetries) {
        this(llm, mapper, maxRetries, null);
    }

    public SpecGenerator(LlmClient llm, ObjectMapper mapper, int maxRetries, WebTool webTool) {
        // Default hard ceiling = twice the base budget, so a converging "hard" spec gets extra tries.
        this(llm, mapper, maxRetries, 2 * maxRetries, webTool);
    }

    public SpecGenerator(LlmClient llm, ObjectMapper mapper, int maxRetries, int maxRetriesHard,
                         WebTool webTool) {
        this(llm, mapper, maxRetries, maxRetriesHard, DEFAULT_REPAIR_TEMPERATURE, webTool);
    }

    public SpecGenerator(LlmClient llm, ObjectMapper mapper, int maxRetries, int maxRetriesHard,
                         double repairTemperature, WebTool webTool) {
        // Backward-compatible defaults: provider-default generation temperature, no structured output.
        this(llm, mapper, maxRetries, maxRetriesHard, repairTemperature, null, false, webTool);
    }

    public SpecGenerator(LlmClient llm, ObjectMapper mapper, int maxRetries, int maxRetriesHard,
                         double repairTemperature, Double generationTemperature,
                         boolean structuredOutput, WebTool webTool) {
        this(llm, mapper, maxRetries, maxRetriesHard, repairTemperature, generationTemperature,
                structuredOutput, null, null, webTool);
    }

    public SpecGenerator(LlmClient llm, ObjectMapper mapper, int maxRetries, int maxRetriesHard,
                         double repairTemperature, Double generationTemperature,
                         boolean structuredOutput, Integer maxTokens, Integer maxTokensHard,
                         WebTool webTool) {
        this(llm, mapper, maxRetries, maxRetriesHard, repairTemperature, generationTemperature,
                structuredOutput, maxTokens, maxTokensHard,
                DEFAULT_REPAIR_TEMPERATURE_STEP, DEFAULT_REPAIR_TEMPERATURE_MAX, webTool);
    }

    public SpecGenerator(LlmClient llm, ObjectMapper mapper, int maxRetries, int maxRetriesHard,
                         double repairTemperature, Double generationTemperature,
                         boolean structuredOutput, Integer maxTokens, Integer maxTokensHard,
                         double repairTemperatureStep, double repairTemperatureMax, WebTool webTool) {
        this.llm                   = llm;
        this.mapper                = mapper;
        this.maxRetries            = maxRetries;
        this.maxRetriesHard        = Math.max(maxRetries, maxRetriesHard);
        this.repairTemperature     = repairTemperature;
        this.generationTemperature = generationTemperature;
        this.structuredOutput      = structuredOutput;
        this.maxTokens             = maxTokens;
        this.maxTokensHard         = maxTokensHard;
        this.repairTemperatureStep = Math.max(0.0, repairTemperatureStep);
        this.repairTemperatureMax  = Math.max(repairTemperature, repairTemperatureMax);
        this.webTool               = webTool;
    }

    // ── Result types ──────────────────────────────────────────────────────────

    /** The outcome of a generation attempt. */
    public sealed interface GenerationResult permits GenerationResult.Success, GenerationResult.Failure {

        record Success(ModelSpec spec, int attemptsUsed) implements GenerationResult {}

        record Failure(
                String lastRawResponse,
                List<ModelSpecValidator.ValidationError> lastErrors,
                int attemptsUsed) implements GenerationResult {}
    }

    // ── Generation ────────────────────────────────────────────────────────────

    /**
     * Generates a {@link ModelSpec} from a natural-language domain description.
     *
     * @param modelId           the desired model id
     * @param domainDescription what the model should do (plain text)
     * @return a {@link GenerationResult} — check {@code instanceof Success} to confirm
     */
    public GenerationResult generate(String modelId, String domainDescription) {
        return generate(modelId, domainDescription, false, e -> {});
    }

    /**
     * Generates a {@link ModelSpec} from a natural-language domain description.
     *
     * @param modelId           the desired model id
     * @param domainDescription what the model should do (plain text)
     * @param includeView       when {@code true}, uses view-aware prompts throughout the retry loop
     * @return a {@link GenerationResult} — check {@code instanceof Success} to confirm
     */
    public GenerationResult generate(String modelId, String domainDescription, boolean includeView) {
        return generate(modelId, domainDescription, includeView, e -> {});
    }

    /**
     * Generates a {@link ModelSpec} with real-time progress events streamed to {@code onProgress}.
     *
     * @param modelId           the desired model id
     * @param domainDescription what the model should do (plain text)
     * @param includeView       when {@code true}, uses view-aware prompts throughout the retry loop
     * @param onProgress        receives {@link LlmProgressEvent}s during generation; never null
     * @return a {@link GenerationResult} — check {@code instanceof Success} to confirm
     */
    public GenerationResult generate(String modelId, String domainDescription, boolean includeView,
                                     Consumer<LlmProgressEvent> onProgress) {
        SpecGenerationPrompt.PromptParts prompt =
                SpecGenerationPrompt.initialPromptParts(modelId, domainDescription, includeView);
        String rawResp  = null;
        int    attempts = 0;
        // Errors from the repaired JSON on the last attempt; more actionable than raw-response errors.
        List<ModelSpecValidator.ValidationError> lastErrors = List.of();
        // One executor per generate() call: shared across retries so the call limit is
        // exhausted over the whole session, not reset on each retry.
        LlmClient.ToolExecutor executor = webTool != null ? webTool.newExecutor() : null;
        // Structured-output schema (when enabled): provider-enforced ModelSpec shape.
        JsonNode responseSchema = structuredOutput ? SpecGenerationSchema.modelSpec(mapper) : null;

        // Adaptive budget: always try up to maxRetries; beyond that, keep going (up to
        // maxRetriesHard) only while the spec is still CONVERGING — i.e. the error count strictly
        // dropped versus the previous attempt. A stuck spec (flat/rising error count) stops at the
        // base budget so we don't burn LLM calls on a hard spec the model can't fix.
        int prevErrorCount = Integer.MAX_VALUE;
        // Best structurally-valid spec seen (fewest failing embedded self-tests). Embedded tests are
        // a self-verification aid, not a hard gate: if the budget runs out with tests still failing we
        // return this rather than failing generation outright — a valid spec beats no spec.
        ModelSpec bestEffortSpec = null;
        int       bestEffortFailCount = Integer.MAX_VALUE;
        // Adaptive truncation recovery: the first truncation retries the SAME prompt with a raised token
        // budget; a second truncation falls back to the "smaller spec" prompt.
        int       truncationCount = 0;
        Integer   maxTokensOverride = null;

        for (int i = 0; i < maxRetriesHard; i++) {
            attempts++;
            if (i > 0) onProgress.accept(new LlmProgressEvent.Retrying(attempts, maxRetriesHard));
            onProgress.accept(new LlmProgressEvent.LlmRequesting(attempts));
            rawResp = callLlm(prompt, executor, i, responseSchema, maxTokensOverride, onProgress);
            String cleanJson    = extractJson(rawResp);
            String repairedJson = repairConstraintPolicy(
                    fixExpressions(repairJson(collapseStringNewlines(cleanJson))), mapper);

            SpecGenerationPrompt.PromptParts nextPrompt;
            int    currentErrorCount;

            ModelSpec spec;
            try {
                spec = mapper.readValue(repairedJson, ModelSpec.class);
            } catch (JsonProcessingException parseEx) {
                // Malformed JSON — check whether the response looks truncated
                boolean truncated = isLikelyTruncated(rawResp);
                lastErrors = List.of(new ModelSpecValidator.ValidationError(
                        "root", "Response was not valid JSON: " + parseEx.getOriginalMessage(),
                        ModelSpecValidator.Severity.ERROR));
                onProgress.accept(new LlmProgressEvent.ValidationFailed(attempts,
                        List.of("Response was not valid JSON: " + parseEx.getOriginalMessage())));
                if (truncated) {
                    truncationCount++;
                    Integer elevated = elevatedMaxTokens();
                    if (truncationCount == 1 && elevated != null && elevated > (maxTokens == null ? 0 : maxTokens)) {
                        // First truncation with headroom: keep the SAME prompt, just raise the budget.
                        maxTokensOverride = elevated;
                        nextPrompt = prompt;
                    } else {
                        // Repeated truncation (or no headroom): demand a smaller spec, reset the budget.
                        maxTokensOverride = null;
                        nextPrompt = SpecGenerationPrompt.repairPromptTruncatedParts(
                                modelId, domainDescription, includeView);
                    }
                } else {
                    maxTokensOverride = null;
                    nextPrompt = SpecGenerationPrompt.repairPromptParts(modelId, rawResp, lastErrors, includeView);
                }
                // A parse failure is not measurable "progress"; never let it unlock extra budget.
                currentErrorCount = Integer.MAX_VALUE;
                if (stopAfterBaseBudget(i, currentErrorCount, prevErrorCount)) break;
                prevErrorCount = currentErrorCount;
                prompt = nextPrompt;
                continue;
            }

            // A parseable response resets any transient token-budget elevation.
            maxTokensOverride = null;

            onProgress.accept(new LlmProgressEvent.Validating(attempts));
            ModelSpecValidator.ValidationResult validation = ModelSpecValidator.validate(spec);
            lastErrors = validation.errors();
            if (validation.isValid()) {
                // Initial-state gate: the freshly-created state (after "$" defaultValues) must satisfy
                // every ROLLBACK constraint — the same check ModelService.create() applies — or the
                // spec would 409 at POST /models. The prompt already instructs this, but models often
                // zero-seed a field a constraint requires positive; re-prompt with the concrete
                // violation so the retry loop repairs the defaults deterministically.
                List<ModelSpecValidator.ValidationError> initErrors = initialStateRollbackErrors(spec);
                if (!initErrors.isEmpty()) {
                    lastErrors = initErrors;
                    onProgress.accept(new LlmProgressEvent.ValidationFailed(attempts,
                            initErrors.stream().limit(5)
                                    .map(e -> e.location() + ": " + e.message()).toList()));
                    nextPrompt = SpecGenerationPrompt.repairPromptParts(
                            modelId, repairedJson, annotateErrors(initErrors), includeView);
                    currentErrorCount = initErrors.size();
                    if (stopAfterBaseBudget(i, currentErrorCount, prevErrorCount)) break;
                    prevErrorCount = currentErrorCount;
                    prompt = nextPrompt;
                    continue;
                }

                onProgress.accept(new LlmProgressEvent.TestRunning(attempts));
                List<TestCaseRunner.TestResult> failed = TestCaseRunner.run(spec, spec.tests())
                        .stream().filter(TestCaseRunner.TestResult::failed).toList();
                // Drop failures the model cannot hand-compute deterministically (whole-array/object
                // assertions, $now()-dependent fields) — they are not a reliable gate, so they must
                // not consume the retry budget or block a structurally-valid spec.
                List<TestCaseRunner.TestResult> verifiable = retainVerifiableFailures(failed, spec);
                if (verifiable.isEmpty()) {
                    return new GenerationResult.Success(
                            markDerivedFieldsReadOnly(spec), attempts);
                }
                onProgress.accept(new LlmProgressEvent.TestFailed(attempts, verifiable.size()));
                if (verifiable.size() < bestEffortFailCount) {
                    bestEffortFailCount = verifiable.size();
                    bestEffortSpec = spec;
                }
                nextPrompt = SpecGenerationPrompt.testRepairPromptParts(
                        modelId, repairedJson, verifiable, spec.derivations(), includeView);
                currentErrorCount = verifiable.size();
            } else {
                onProgress.accept(new LlmProgressEvent.ValidationFailed(attempts,
                        validation.errors().stream()
                                .limit(5)
                                .map(e -> e.location() + ": " + e.message())
                                .toList()));
                nextPrompt = SpecGenerationPrompt.repairPromptParts(
                        modelId, repairedJson, annotateErrors(validation.errors()), includeView);
                currentErrorCount = validation.errors().size();
            }

            if (stopAfterBaseBudget(i, currentErrorCount, prevErrorCount)) break;
            prevErrorCount = currentErrorCount;
            prompt = nextPrompt;
        }

        // Budget exhausted. If we produced a structurally-valid spec whose only problem was failing
        // embedded self-tests, return it (best-effort) — the tests improved the spec across attempts
        // but should not block generation when the model cannot make them all pass.
        if (bestEffortSpec != null) {
            return new GenerationResult.Success(
                    markDerivedFieldsReadOnly(bestEffortSpec), attempts);
        }
        // Final attempt failed on structural/parse errors — return failure with the last error list.
        return new GenerationResult.Failure(rawResp, lastErrors, attempts);
    }

    /**
     * Whether to stop the retry loop after the attempt at index {@code i}. Within the base budget
     * ({@code i + 1 < maxRetries}) we always continue. Past it, we continue only while the spec is
     * converging — the current attempt produced strictly fewer errors than the previous one.
     */
    private boolean stopAfterBaseBudget(int i, int currentErrorCount, int prevErrorCount) {
        if (i + 1 < maxRetries) return false;          // still within the guaranteed base budget
        return currentErrorCount >= prevErrorCount;    // not converging → stop
    }

    /**
     * Checks whether the freshly-created initial state (after "$" defaultValues) satisfies every
     * ROLLBACK constraint — the same gate {@code ModelService.create()} applies via
     * {@link ModelRuntime#initialize()}. Returns a repair-guiding error when it does not, or an empty
     * list when the model would register cleanly. A spec that cannot be compiled/initialised here for
     * an unrelated reason yields an empty list — structural validation already passed and the create
     * path / embedded tests surface anything else, so this gate never blocks on non-constraint issues.
     */
    private List<ModelSpecValidator.ValidationError> initialStateRollbackErrors(ModelSpec spec) {
        try {
            CompiledModel model = ModelSpecCompiler.compile(spec);
            ModelRuntime runtime = new ModelRuntime(model, new ModelState(model, new InMemoryBlobStore()));
            runtime.initialize();
            return List.of();
        } catch (ConstraintEvaluator.ConstraintViolationException cve) {
            return List.of(new ModelSpecValidator.ValidationError(
                    "defaultValues",
                    "Initial state violates rollback constraint(s) at creation: " + cve.getMessage()
                    + ". Seed defaultValues at \"$\" so the initial state satisfies every rollback "
                    + "constraint — e.g. a positive value for any field a constraint requires to be > 0. "
                    + "Do NOT zero-seed such a field.",
                    ModelSpecValidator.Severity.ERROR));
        } catch (RuntimeException e) {
            return List.of();
        }
    }

    // ── Evolution ─────────────────────────────────────────────────────────────

    /**
     * Generates a {@link SpecEvolution} diff from a natural-language change request.
     *
     * @param currentSpec      the spec to evolve
     * @param evolutionRequest natural-language description of the desired changes
     * @return the parsed {@link SpecEvolution}, or throws on repeated failure
     */
    public SpecEvolution generateEvolution(ModelSpec currentSpec, String evolutionRequest) {
        return generateEvolution(currentSpec, evolutionRequest, e -> {});
    }

    /**
     * Generates a {@link SpecEvolution} with real-time progress events streamed to {@code onProgress}.
     *
     * @param currentSpec      the spec to evolve
     * @param evolutionRequest natural-language description of the desired changes
     * @param onProgress       receives {@link LlmProgressEvent}s during generation; never null
     * @return the parsed {@link SpecEvolution}, or throws on repeated failure
     */
    public SpecEvolution generateEvolution(ModelSpec currentSpec, String evolutionRequest,
                                           Consumer<LlmProgressEvent> onProgress) {
        return generateEvolution(currentSpec, evolutionRequest, false, onProgress);
    }

    /**
     * Generates a {@link SpecEvolution} with real-time progress events, optionally evolving the
     * {@code viewDefinition} too.
     *
     * @param currentSpec      the spec to evolve
     * @param evolutionRequest natural-language description of the desired changes
     * @param includeView      when {@code true}, uses view-aware evolution prompts throughout the loop
     *                         so the model can add/replace {@code newViewDefinition}/{@code
     *                         upsertComponents} — and repair prompts keep the view catalog in scope
     * @param onProgress       receives {@link LlmProgressEvent}s during generation; never null
     * @return the parsed {@link SpecEvolution}, or throws on repeated failure
     */
    public SpecEvolution generateEvolution(ModelSpec currentSpec, String evolutionRequest,
                                           boolean includeView, Consumer<LlmProgressEvent> onProgress) {
        String currentSpecJson = writeJson(currentSpec);
        // Tell the model which paths are already computed so it upserts by the same path rather than
        // redeclaring a derived field as a writable property.
        List<String> derivedPaths = currentSpec.derivations().stream()
                .map(DerivationSpec::path).toList();
        SpecGenerationPrompt.PromptParts prompt = SpecGenerationPrompt.evolutionPromptParts(
                currentSpec.id(), currentSpecJson, evolutionRequest, includeView, derivedPaths);
        // One executor per call, shared across retries, so tool budgets span the whole session.
        LlmClient.ToolExecutor executor = webTool != null ? webTool.newExecutor() : null;
        // Structured-output schema (when enabled): provider-enforced SpecEvolution shape.
        JsonNode responseSchema = structuredOutput ? SpecGenerationSchema.specEvolution(mapper) : null;

        // Same adaptive budget + best-effort fallback as generate(): try the base budget, extend only
        // while converging, and return the best structurally-valid evolution even if its merged spec's
        // embedded self-tests still fail (a valid evolution beats throwing).
        int prevErrorCount = Integer.MAX_VALUE;
        SpecEvolution bestEffort = null;
        int bestEffortFailCount = Integer.MAX_VALUE;
        String lastError = "no valid SpecEvolution produced";
        int attempts = 0;

        for (int i = 0; i < maxRetriesHard; i++) {
            attempts++;
            if (i > 0) onProgress.accept(new LlmProgressEvent.Retrying(attempts, maxRetriesHard));
            onProgress.accept(new LlmProgressEvent.LlmRequesting(attempts));
            String rawResp      = callLlm(prompt, executor, i, responseSchema, null, onProgress);
            String repairedJson = repairConstraintPolicy(
                    fixExpressions(repairJson(collapseStringNewlines(extractJson(rawResp)))), mapper);
            SpecEvolution evolution = parseEvolution(repairedJson);

            SpecGenerationPrompt.PromptParts nextPrompt;
            int    currentErrorCount;

            if (evolution == null) {
                lastError  = "Response was not a valid SpecEvolution JSON object.";
                onProgress.accept(new LlmProgressEvent.ValidationFailed(attempts, List.of(lastError)));
                nextPrompt = SpecGenerationPrompt.evolutionRepairPromptParts(
                        currentSpec.id(), currentSpecJson, evolutionRequest, rawResp, lastError,
                        includeView, derivedPaths);
                currentErrorCount = Integer.MAX_VALUE; // a parse failure is not measurable progress
            } else {
                onProgress.accept(new LlmProgressEvent.Validating(attempts));
                ModelSpec merged;
                try {
                    // applyTo runs ModelSpecValidator (which compiles every expression) and throws on
                    // any structural/expression error.
                    merged = evolution.applyTo(currentSpec);
                } catch (IllegalArgumentException e) {
                    lastError    = e.getMessage();
                    onProgress.accept(new LlmProgressEvent.ValidationFailed(attempts, List.of(lastError)));
                    String hint  = hintFor(lastError);
                    nextPrompt   = SpecGenerationPrompt.evolutionRepairPromptParts(
                            currentSpec.id(), currentSpecJson, evolutionRequest, repairedJson,
                            hint == null ? lastError : lastError + hint, includeView, derivedPaths);
                    // Structured error count for the convergence gate: a validation failure carries the
                    // exact error list; a shape violation is a single error. No message string-parsing.
                    currentErrorCount = (e instanceof SpecEvolution.SpecEvolutionException se)
                            ? Math.max(1, se.errors().size()) : 1;
                    if (stopAfterBaseBudget(i, currentErrorCount, prevErrorCount)) break;
                    prevErrorCount = currentErrorCount;
                    prompt = nextPrompt;
                    continue;
                }

                // Keep the evolved schema consistent with the merged derivations: a newly derived
                // field declared in the evolution's newSchema must be readOnly. The merged spec's
                // schema IS the evolution's newSchema node by reference, so marking it propagates back
                // into the returned evolution. Skip when the evolution does not change the schema.
                if (evolution.newSchema() != null) {
                    markDerivedFieldsReadOnly(merged);
                }

                // Verify the merged spec against its embedded self-tests (carried from the base spec):
                // catches an evolution that compiles but breaks an existing derivation's value. Drop
                // un-verifiable assertions (whole-array/object, $now()-dependent) — same as generate().
                List<TestCaseRunner.TestResult> failed = TestCaseRunner.run(merged, merged.tests())
                        .stream().filter(TestCaseRunner.TestResult::failed).toList();
                List<TestCaseRunner.TestResult> verifiable = retainVerifiableFailures(failed, merged);
                onProgress.accept(new LlmProgressEvent.TestRunning(attempts));
                if (verifiable.isEmpty()) {
                    return evolution;   // verified (or only un-verifiable assertions remained)
                }
                onProgress.accept(new LlmProgressEvent.TestFailed(attempts, verifiable.size()));
                if (verifiable.size() < bestEffortFailCount) {
                    bestEffortFailCount = verifiable.size();
                    bestEffort = evolution;
                }
                lastError  = verifiable.size() + " embedded self-test(s) failed after applying the evolution";
                String feedback = "The evolved spec passed structural validation but failed embedded "
                        + "self-tests:\n" + SpecGenerationPrompt.testFailureFeedback(verifiable, merged.derivations())
                        + "\nEither a derivation expr is wrong or a test's expect value is wrong — fix "
                        + "whichever does not match.";
                nextPrompt = SpecGenerationPrompt.evolutionRepairPromptParts(
                        currentSpec.id(), currentSpecJson, evolutionRequest, repairedJson, feedback,
                        includeView, derivedPaths);
                currentErrorCount = verifiable.size();
            }

            if (stopAfterBaseBudget(i, currentErrorCount, prevErrorCount)) break;
            prevErrorCount = currentErrorCount;
            prompt = nextPrompt;
        }

        // Best-effort: a structurally-valid evolution whose only remaining problem was failing
        // self-tests (the repair attempts still improved it). A valid evolution beats no evolution.
        if (bestEffort != null) return bestEffort;
        throw new LlmClient.LlmException(
                "Failed to generate a valid SpecEvolution after " + maxRetriesHard
                + " attempts: " + lastError);
    }

    /**
     * Keeps only failing self-test assertions that are a <em>reliable</em> signal — ones the model can
     * deterministically hand-compute — so the rest never block generation or burn the retry budget.
     * A field failure is dropped (treated as un-verifiable) when:
     * <ul>
     *   <li>its expected value is an array or object — a whole computed collection (e.g. an
     *       amortization schedule) cannot be hand-computed exactly, so it always mismatches; or</li>
     *   <li>the derivation at its path depends on the current date/time ({@code $now()}/{@code $millis()})
     *       — its value changes between hand-computation and runtime, so a fixed expectation is wrong.</li>
     * </ul>
     * Returns the test results that still carry at least one verifiable field failure (with their
     * failure lists narrowed to the verifiable ones), or an empty list if none remain.
     */
    static List<TestCaseRunner.TestResult> retainVerifiableFailures(
            List<TestCaseRunner.TestResult> failed, ModelSpec spec) {
        Map<String, String> exprByPath = spec.derivations().stream()
                .collect(Collectors.toMap(DerivationSpec::path, DerivationSpec::expr, (a, b) -> a));
        List<TestCaseRunner.TestResult> out = new ArrayList<>();
        for (TestCaseRunner.TestResult t : failed) {
            List<TestCaseRunner.FieldFailure> verifiable = t.failures().stream()
                    .filter(f -> isVerifiableFailure(f, exprByPath))
                    .toList();
            if (!verifiable.isEmpty())
                out.add(new TestCaseRunner.TestResult(t.description(), false, verifiable));
        }
        return out;
    }

    private static boolean isVerifiableFailure(TestCaseRunner.FieldFailure f,
                                               Map<String, String> exprByPath) {
        JsonNode expected = f.expected();
        if (expected != null && (expected.isArray() || expected.isObject()))
            return false;   // a whole array/object cannot be hand-computed reliably
        String expr = exprByPath.get(f.path());
        if (expr != null && (expr.contains("$now(") || expr.contains("$millis(")))
            return false;   // time-dependent → non-deterministic expectation
        return true;
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    /**
     * Sends a prompt to the LLM, using tool-calling when a web tool and executor are present.
     * On repair attempts ({@code attemptIndex > 0}) an escalating temperature ({@link
     * #repairTemperatureFor}) is requested so a stuck model escapes the previous attempts' rut instead
     * of re-emitting them; the initial attempt uses {@link #generationTemperature} ({@code null} =
     * provider default). When
     * {@code responseSchema} is non-null and structured output is enabled, it is sent as the provider
     * {@code response_format} so the output shape is provider-enforced.
     */
    private String callLlm(SpecGenerationPrompt.PromptParts prompt, LlmClient.ToolExecutor executor,
                           int attemptIndex, JsonNode responseSchema, Integer maxTokensOverride,
                           Consumer<LlmProgressEvent> onProgress) {
        // Attempt 0 is the initial generation (generationTemperature; null = provider default). Each
        // repair attempt escalates the temperature so a stuck model gets more room to escape the rut.
        // Box so the ternary's type is Double — a (double : Double) ternary would unbox a null
        // generationTemperature and NPE.
        Double temperature = attemptIndex == 0
                ? generationTemperature
                : Double.valueOf(repairTemperatureFor(attemptIndex));
        JsonNode schema    = structuredOutput ? responseSchema : null;
        LlmClient.CompletionOptions options =
                new LlmClient.CompletionOptions(temperature, schema, maxTokensOverride);
        return webTool != null && executor != null
                ? llm.completeWithTools(prompt, webTool.definitions(), executor, options, onProgress)
                : llm.complete(prompt, options);
    }

    /**
     * The repair temperature for the repair attempt at 1-based {@code attemptIndex} (attempt 1 = first
     * repair): {@code repairTemperature + (attemptIndex - 1) × step}, clamped to
     * {@code repairTemperatureMax}. Rising each retry lets a model that keeps re-emitting the same
     * failing output diverge instead of repeating it.
     */
    double repairTemperatureFor(int attemptIndex) {
        double t = repairTemperature + (attemptIndex - 1) * repairTemperatureStep;
        return Math.min(t, repairTemperatureMax);
    }

    /**
     * The raised token budget for a first-truncation retry: {@code min(2 × maxTokens, maxTokensHard)},
     * or {@code null} when the feature is off (no configured budgets). Keeping the same prompt with a
     * bigger budget preserves the work instead of demanding a permanently smaller spec.
     */
    private Integer elevatedMaxTokens() {
        if (maxTokens == null || maxTokensHard == null) return null;
        return Math.min(2 * maxTokens, maxTokensHard);
    }

    /**
     * Ensures every derived field's schema property is marked {@code "readOnly": true}. LLMs often
     * declare a computed field in the schema as an ordinary (writable) property <em>and</em> define a
     * derivation for it; the runtime then rejects any write to that path (a derived field is read-only)
     * — so a schema-aware client/tool that trusts the schema and writes the field gets an error. Making
     * the schema consistent with the derivations closes that gap. Mutates the schema node in place.
     */
    public static ModelSpec markDerivedFieldsReadOnly(ModelSpec spec) {
        if (!(spec.schema() instanceof ObjectNode schema)) return spec;
        for (DerivationSpec d : spec.derivations()) {
            markReadOnly(schema, PathConverter.toSegments(d.path()));
        }
        return spec;
    }

    /** Navigates the JSON Schema along {@code segments} and sets {@code readOnly: true} on the leaf. */
    private static void markReadOnly(ObjectNode schema, List<String> segments) {
        ObjectNode node = schema;
        for (int i = 0; i < segments.size(); i++) {
            String seg = segments.get(i);
            boolean last = (i == segments.size() - 1);
            if ("[*]".equals(seg) || isDigits(seg)) {
                if (!(node.get("items") instanceof ObjectNode itemSchema)) return; // array element
                node = itemSchema;
                if (last) node.put("readOnly", true);
                continue;
            }
            if (!(node.get("properties") instanceof ObjectNode props)) return;
            if (!(props.get(seg) instanceof ObjectNode field)) return;
            if (last) { field.put("readOnly", true); return; }
            node = field;
        }
    }

    private static boolean isDigits(String s) {
        if (s == null || s.isEmpty()) return false;
        for (int i = 0; i < s.length(); i++) if (!Character.isDigit(s.charAt(i))) return false;
        return true;
    }

    /**
     * Strips markdown code fences that some LLMs add around JSON output.
     *
     * <p>Searches for the fence anywhere in the response, not only at position 0, so
     * preamble text added by some models on repair retries is handled correctly.
     * Falls back to brace-extraction when no fence is present.
     */
    public static String extractJson(String raw) {
        if (raw == null) return "";
        String trimmed = raw.strip();

        // Find the first ``` fence anywhere in the response
        int firstFence = trimmed.indexOf("```");
        if (firstFence >= 0) {
            int firstNewline = trimmed.indexOf('\n', firstFence);
            int lastFence    = trimmed.lastIndexOf("```");
            if (firstNewline >= 0 && lastFence > firstNewline) {
                return trimmed.substring(firstNewline + 1, lastFence).strip();
            }
        }

        // No fence: extract the outermost JSON object by brace matching
        int firstBrace = trimmed.indexOf('{');
        int lastBrace  = trimmed.lastIndexOf('}');
        if (firstBrace >= 0 && lastBrace > firstBrace) {
            return trimmed.substring(firstBrace, lastBrace + 1);
        }

        return trimmed;
    }

    /**
     * Repairs the most common LLM JSON generation mistakes:
     * <ol>
     *   <li>A missing closing quote before a structural character, e.g. {@code "type":"boolean}}
     *       instead of {@code "type":"boolean"}}.</li>
     *   <li>An unquoted non-primitive value where a JSON string is expected, e.g. action payload
     *       values like {@code "amount": loanAmount - downPayment}. Excludes valid JSON primitives
     *       ({@code true}, {@code false}, {@code null}, numbers) from quoting.</li>
     * </ol>
     */
    public static String repairJson(String json) {
        return json
                // (1) Add missing closing quote on simple word values before structural chars
                .replaceAll("\"([A-Za-z][A-Za-z0-9_-]{0,49})([},\\]])", "\"$1\"$2")
                // (2) Quote unquoted non-primitive values (JSONata expressions in action payloads).
                // The (?<!\\) lookbehind ensures we only match top-level JSON keys (unescaped
                // double-quotes), NOT escaped quotes inside a JSON string value like \"month\".
                .replaceAll(
                        "((?<!\\\\)\"[^\"\\n]+(?<!\\\\)\"\\s*:\\s*)(?!\"|\\{|\\[|true\\b|false\\b|null\\b|-?[\\d.])([A-Za-z$][^,\\n\\r}\\]]*?)([,\\n\\r}\\]])",
                        "$1\"$2\"$3");
    }

    /**
     * Collapses literal newlines and carriage returns inside JSON string values into spaces.
     *
     * <p>Some LLMs (e.g. Mistral) pretty-print multi-line JSONata expressions directly inside
     * JSON strings, producing literal line breaks that make the document invalid JSON.
     * This pass normalises them to single spaces before parsing.
     */
    public static String collapseStringNewlines(String json) {
        StringBuilder sb = new StringBuilder(json.length());
        boolean inString = false;
        for (int i = 0; i < json.length(); i++) {
            char c = json.charAt(i);
            if (c == '"') {
                // Count consecutive preceding backslashes: odd = escaped quote (not a delimiter)
                int backslashes = 0;
                for (int j = i - 1; j >= 0 && json.charAt(j) == '\\'; j--) backslashes++;
                if (backslashes % 2 == 0) inString = !inString;
            }
            if (inString && (c == '\n' || c == '\r')) {
                sb.append(' ');
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    /**
     * Replaces invented or incorrect JSONata functions that LLMs hallucinate with their
     * correct equivalents.
     *
     * <p>LLMs frequently invent {@code $currentYear()} and use non-existent functions like
     * {@code $toInteger} (→ {@code $number}) and {@code $power} (→ {@code **} operator).
     * Also fixes {@code function($m) (...)} → {@code function($m) {...}} because our JSONata
     * JVM runtime requires {@code {}} for the function body, not {@code ()}.
     */
    public static String fixExpressions(String json) {
        return ExpressionRepairer.fixDocument(json);
    }

    /**
     * The raw whole-document JSONata-syntax passes (phase 1 string replacements + the structure-aware
     * phase 2 passes). Historically this was {@code fixExpressions}; it now runs only in two narrow
     * cases — on a single re-encoded expression string ({@link ExpressionRepairer#repair}) and as the
     * last-ditch rescue when a response will not parse as a JSON object
     * ({@link ExpressionRepairer#fixDocument}). Applying it to a whole parseable document is what used
     * to corrupt non-expression strings, so the parse-and-walk path is preferred whenever possible.
     */
    static String rawExpressionPasses(String json) {
        // Phase 1: simple string replacements (applied globally to the JSON)
        String s = json
                .replace("$currentYear()", "$substring($now(), 0, 4)~>$number()")
                .replace("$year($now())", "$substring($now(), 0, 4)~>$number()")
                .replace("$getYear()", "$substring($now(), 0, 4)~>$number()")
                .replace("$toInteger(", "$number(")   // $toInteger does not exist; $number does
                // $power(base, exp) does not exist; ** is the exponentiation operator
                .replaceAll("\\$power\\(([^,()]+),\\s*([^)()]+)\\)", "($1) ** ($2)")
                // ** (-identifier) — negative exponent wrapped in parens e.g. ** (-n)
                .replaceAll("\\*\\*\\s*\\(-(\\$?\\w[\\w.]*)\\)", "** (0 - $1)")
                // ** -identifier — negative exponent without parens e.g. ** -n
                .replaceAll("\\*\\*\\s*-\\s*(\\$?\\w[\\w.]*)", "** (0 - $1)")
                // !== → != (JavaScript strict not-equal → JSONata not-equal)
                .replace("!==", "!=")
                // "a mod b" → "a % b" — JSONata's modulo operator is %, not the SQL/BASIC "mod"
                // keyword. Bounded to operand context (identifier/number/$/closing-bracket on the
                // left, identifier/number/$/opening-bracket on the right) so words like "model" or a
                // description's prose "mod" are not touched.
                .replaceAll("(?<=[\\w$.)\\]])\\s+mod\\s+(?=[\\w$.(\\[])", " % ")
                // ==, === → = (JavaScript equality → JSONata equality);
                // negative lookbehind guards :=, <=, >=, != so they are not touched
                .replaceAll("(?<![:<>!])==+", "=");

        // Phase 2: structure-aware passes (order matters). balanceExpressionParens runs LAST so it
        // closes any paren deficit the earlier passes leave (or the LLM produced).
        return balanceExpressionParens(fixObjectSemicolons(fixFunctionSequenceBodies(fixFunctionBodyCommas(
                fixLambdaBodies(fixBindingCommas(fixNotKeyword(convertPowerToReduce(wrapPowerExpressions(s)))))))));
    }

    /**
     * Appends the missing trailing close-parens to a JSONata sequence expression whose JSON string
     * value begins with {@code (} but contains more {@code (} than {@code )}. LLMs hit this on long
     * {@code ($a := …; $reduce(…))} expressions where the final {@code )} is dropped — the runtime
     * then reports "Expected RPAREN but reached end of expression".
     *
     * <p>Tightly gated to avoid corrupting non-expression strings: a value is balanced only when it
     * (1) starts with {@code (}, (2) contains a {@code $} (a JSONata variable/function sigil — prose
     * never does), and (3) has a positive {@code (} deficit. Parens inside JSONata string literals
     * (single-quoted {@code '…'} and JSON-escaped {@code \"…\"}) are ignored, and only the exact
     * deficit of {@code )} is appended — never more.
     */
    static String balanceExpressionParens(String json) {
        StringBuilder out = new StringBuilder(json.length() + 8);
        int i = 0, n = json.length();
        while (i < n) {
            char c = json.charAt(i);
            if (c != '"') { out.append(c); i++; continue; }

            // Scan a JSON string [i .. closeQuote], counting JSONata parens in its content.
            int parens = 0, closeQuote = -1, j = i + 1;
            boolean inSq = false, inDq = false;          // JSONata '…' and \"…\" literals
            boolean firstSeen = false, startsWithParen = false, hasDollar = false;
            while (j < n) {
                char d = json.charAt(j);
                if (d == '\\' && j + 1 < n) {             // JSON escape
                    if (json.charAt(j + 1) == '"' && !inSq) inDq = !inDq; // \" toggles JSONata dq literal
                    firstSeen = true;
                    j += 2;
                    continue;
                }
                if (d == '"') { closeQuote = j; break; }  // unescaped quote ends the JSON string
                if (!firstSeen) { firstSeen = true; startsWithParen = (d == '('); }
                if (d == '$') hasDollar = true;
                if (!inDq) {
                    if (d == '\'') inSq = !inSq;
                    else if (!inSq) {
                        if (d == '(') parens++;
                        else if (d == ')') parens--;
                    }
                }
                j++;
            }
            if (closeQuote < 0) { out.append(json, i, n); return out.toString(); } // malformed

            out.append(json, i, closeQuote);              // opening quote + content
            if (startsWithParen && hasDollar && parens > 0) {
                for (int k = 0; k < parens; k++) out.append(')');
            }
            out.append('"');
            i = closeQuote + 1;
        }
        return out.toString();
    }

    /**
     * Converts {@code not identifier} to {@code $not(identifier)}.
     *
     * <p>LLMs frequently use {@code not} as a keyword (Python/SQL style) inside JSONata
     * expressions. Our runtime only knows {@code $not()} as a built-in function.
     * Only replaces bare {@code not <word>} patterns; complex negations like {@code not (expr)}
     * are left for the LLM to fix via the repair prompt.
     */
    static String fixNotKeyword(String json) {
        return json
                // not (expr) → $not(expr)  — the ( is already there, just prepend $not
                .replaceAll("(?<![A-Za-z0-9_$])not\\s*\\(", "\\$not(")
                // not identifier → $not(identifier)
                .replaceAll("(?<![A-Za-z0-9_$])not\\s+(\\w[\\w.]*)", "\\$not($1)");
    }

    /**
     * Converts comma-separated binding blocks to semicolon-separated ones inside JSON strings.
     *
     * <p>Our JSONata JVM runtime requires {@code ;} to separate sequential expressions in a
     * block: {@code ($a := x; $b := y; expr)}. LLMs (especially Mistral) use {@code ,} instead,
     * causing "Expected RPAREN but found COMMA" at compile time.
     *
     * <p>A comma is converted to {@code ;} only when all of the following are true:
     * <ul>
     *   <li>It is inside a JSON string value.</li>
     *   <li>It is at a parenthesis-depth level where a {@code :=} binding was already seen.</li>
     *   <li>That parenthesis level was opened by a standalone {@code (}, not a function call
     *       (so {@code $fn(a, b)} arguments are never touched).</li>
     *   <li>It is not inside an array literal {@code [...]}.</li>
     * </ul>
     */
    static String fixBindingCommas(String json) {
        StringBuilder sb = new StringBuilder(json.length());
        boolean inJsonStr = false;

        // Expression-level tracking (active only inside JSON strings)
        int      parenDepth      = 0;
        int      bracketDepth    = 0;
        int      braceDepth      = 0;
        int[]    parenKind       = new int[64];     // 0 = block, 1 = function call
        boolean[] hasBinding     = new boolean[64];
        int[]    parenBraceDepth = new int[64];     // brace depth when each paren was opened

        for (int i = 0; i < json.length(); i++) {
            char c = json.charAt(i);

            // ── JSON string boundary tracking ─────────────────────────────────
            if (c == '"') {
                int backslashes = 0;
                for (int j = i - 1; j >= 0 && json.charAt(j) == '\\'; j--) backslashes++;
                if (backslashes % 2 == 0) {
                    inJsonStr = !inJsonStr;
                    if (!inJsonStr) { parenDepth = 0; bracketDepth = 0; braceDepth = 0; }
                }
                sb.append(c);
                continue;
            }

            if (!inJsonStr) { sb.append(c); continue; }

            // ── Inside a JSON string: track expression structure ──────────────
            switch (c) {
                case '(' -> {
                    parenDepth++;
                    if (parenDepth < parenKind.length) {
                        // Function call: immediately preceded by an identifier char or ')'
                        char prev = (i > 0) ? json.charAt(i - 1) : 0;
                        parenKind[parenDepth]        = (Character.isLetterOrDigit(prev) || prev == '_' || prev == '$' || prev == ')') ? 1 : 0;
                        hasBinding[parenDepth]       = false;
                        parenBraceDepth[parenDepth]  = braceDepth;
                    }
                }
                case ')' -> { if (parenDepth > 0) parenDepth--; }
                case '{' -> braceDepth++;
                case '}' -> { if (braceDepth > 0) braceDepth--; }
                case '[' -> bracketDepth++;
                case ']' -> { if (bracketDepth > 0) bracketDepth--; }
                default  -> {
                    // Detect := at current depth
                    if (c == ':' && i + 1 < json.length() && json.charAt(i + 1) == '=' && parenDepth < hasBinding.length) {
                        hasBinding[parenDepth] = true;
                    }
                    // Convert comma → semicolon when in a binding block.
                    // Guard braceDepth: if we entered a {} object literal since this paren opened,
                    // the comma is an object-literal separator — leave it alone.
                    if (c == ',' && bracketDepth == 0
                            && parenDepth > 0 && parenDepth < parenKind.length
                            && parenKind[parenDepth] == 0
                            && hasBinding[parenDepth]
                            && braceDepth == parenBraceDepth[parenDepth]) {
                        sb.append(';');
                        continue;
                    }
                }
            }
            sb.append(c);
        }
        return sb.toString();
    }

    /**
     * Converts semicolons used as object-literal separators back to commas inside JSONata
     * {@code {...}} objects embedded in JSON string values.
     *
     * <p>Some LLMs (e.g. Mistral on repair retries) emit {@code \{"A": 50; "B": 100\}} with
     * semicolons instead of commas. After {@link #fixFunctionSequenceBodies} wraps multi-statement
     * function bodies in {@code (...)}, any semicolon still at the direct {@code \{}\}} level
     * (not inside nested {@code ()}) belongs to an object literal and must be a comma.
     *
     * <p>Runs inside JSON string values only; JSON structural characters are unaffected.
     */
    static String fixObjectSemicolons(String json) {
        StringBuilder sb = new StringBuilder(json.length());
        boolean inJsonStr = false;

        int braceDepth   = 0;
        int parenDepth   = 0;
        int bracketDepth = 0;
        int[] braceParenDepth = new int[64];   // parenDepth when each brace was opened

        for (int i = 0; i < json.length(); i++) {
            char c = json.charAt(i);

            if (c == '"') {
                int bs = 0;
                for (int j = i - 1; j >= 0 && json.charAt(j) == '\\'; j--) bs++;
                if (bs % 2 == 0) {
                    inJsonStr = !inJsonStr;
                    if (!inJsonStr) { braceDepth = 0; parenDepth = 0; bracketDepth = 0; }
                }
                sb.append(c);
                continue;
            }

            if (!inJsonStr) { sb.append(c); continue; }

            switch (c) {
                case '{' -> {
                    if (braceDepth < braceParenDepth.length) {
                        braceParenDepth[braceDepth] = parenDepth;
                    }
                    braceDepth++;
                }
                case '}' -> { if (braceDepth > 0) braceDepth--; }
                case '(' -> parenDepth++;
                case ')' -> { if (parenDepth > 0) parenDepth--; }
                case '[' -> bracketDepth++;
                case ']' -> { if (bracketDepth > 0) bracketDepth--; }
                default  -> {
                    // A semicolon at the direct level of a {} block (no extra parens since the
                    // brace opened) is an object-literal separator that should be a comma.
                    if (c == ';' && braceDepth > 0 && bracketDepth == 0
                            && braceDepth <= braceParenDepth.length
                            && parenDepth == braceParenDepth[braceDepth - 1]) {
                        sb.append(',');
                        continue;
                    }
                }
            }
            sb.append(c);
        }
        return sb.toString();
    }

    /**
     * Converts comma-separated binding statements to semicolon-separated ones inside JSONata
     * {@code function($params) \{...\}} bodies.
     *
     * <p>LLMs (e.g. Mistral) often emit function bodies with {@code ,} as the sequence separator
     * instead of the required {@code ;}: {@code function($acc, $m) \{$a := x, $b := y, $a + $b\}}.
     * This pass converts those commas to {@code ;} so that later passes
     * ({@link #fixFunctionSequenceBodies}) can detect and wrap the multi-statement body correctly.
     *
     * <p>A comma is converted only when:
     * <ul>
     *   <li>It is at the direct top level of the function body {@code \{...\}} — not inside nested
     *       {@code ()}, {@code []}, or inner {@code \{...\}}.</li>
     *   <li>The function body contains at least one {@code :=} binding at the same level (so plain
     *       JSONata object literals like {@code function($x) \{"a": 1, "b": 2\}} are left alone).</li>
     * </ul>
     */
    static String fixFunctionBodyCommas(String json) {
        java.util.regex.Pattern p = java.util.regex.Pattern.compile("function\\([^)]*\\)\\s*\\{");
        java.util.regex.Matcher m = p.matcher(json);
        char[] chars = json.toCharArray();

        while (m.find()) {
            int openBrace  = m.end() - 1;
            int braceDepth = 1, parenD = 0, bracketD = 0;
            int i = openBrace + 1;
            boolean hasBinding = false;
            List<Integer> directCommas = new ArrayList<>();

            while (i < chars.length) {
                char c = chars[i];
                if      (c == '{') braceDepth++;
                else if (c == '}') { braceDepth--; if (braceDepth == 0) break; }
                else if (c == '(') parenD++;
                else if (c == ')') { if (parenD > 0) parenD--; }
                else if (c == '[') bracketD++;
                else if (c == ']') { if (bracketD > 0) bracketD--; }
                else if (c == ':' && i + 1 < chars.length && chars[i + 1] == '='
                        && braceDepth == 1 && parenD == 0 && bracketD == 0) {
                    hasBinding = true;
                }
                else if (c == ',' && braceDepth == 1 && parenD == 0 && bracketD == 0) {
                    directCommas.add(i);
                }
                i++;
            }

            if (hasBinding && !directCommas.isEmpty()) {
                for (int pos : directCommas) chars[pos] = ';';
            }
        }
        return new String(chars);
    }

    /**
     * Wraps multi-statement function bodies in {@code ()} so the runtime can evaluate them.
     *
     * <p>Our JSONata JVM runtime treats {@code \{...\}} as a function body containing a single
     * expression. When LLMs generate multi-statement bodies like
     * {@code function($m) \{$a := x; $b := y; result\}}, the {@code ;} causes
     * "Expected RBRACE but found SEMICOLON". Wrapping fixes it:
     * {@code function($m) \{($a := x; $b := y; result)\}}.
     *
     * <p>Only fires when {@code ;} appears at the top level of the brace body (not nested inside
     * {@code ()}, {@code []}, or inner {@code \{\}}).
     */
    static String fixFunctionSequenceBodies(String json) {
        java.util.regex.Pattern p = java.util.regex.Pattern.compile("function\\([^)]*\\)\\s*\\{");
        java.util.regex.Matcher m = p.matcher(json);
        StringBuilder result = new StringBuilder();
        int lastEnd = 0;

        while (m.find()) {
            int openBrace = m.end() - 1;
            int braceDepth = 1, parenD = 0, bracketD = 0;
            int i = openBrace + 1;
            boolean hasSemicolon = false;

            while (i < json.length()) {
                char c = json.charAt(i);
                if      (c == '{') braceDepth++;
                else if (c == '}') { braceDepth--; if (braceDepth == 0) break; }
                else if (c == '(') parenD++;
                else if (c == ')') parenD--;
                else if (c == '[') bracketD++;
                else if (c == ']') bracketD--;
                else if (c == ';' && braceDepth == 1 && parenD == 0 && bracketD == 0)
                    hasSemicolon = true;
                i++;
            }

            if (braceDepth == 0 && hasSemicolon) {
                result.append(json, lastEnd, openBrace + 1); // up to and including {
                result.append('(');
                result.append(json, openBrace + 1, i);       // body content
                result.append(')');
                result.append('}');
                lastEnd = i + 1;
            }
        }
        result.append(json, lastEnd, json.length());
        return result.toString();
    }

    /**
     * Wraps {@code (base) ** (exp)} in extra parentheses: {@code ((base) ** (exp))}.
     *
     * <p>The JSONata JVM runtime treats {@code **} as lower-precedence than {@code +}, {@code -},
     * {@code *}, and {@code /}. This means {@code a - (b + c) ** n} is parsed as
     * {@code (a - (b+c)) ** n} instead of {@code a - ((b+c) ** n)}, causing a parse error when
     * the parser expects {@code )} but finds {@code **} after the parenthesised base. Wrapping
     * the entire power subexpression in extra parens forces the correct grouping.
     *
     * <p>Only fires when the base ends with {@code )} and the exponent starts with {@code (}.
     * Operates inside JSON string values only; JSON structural characters are unaffected.
     */
    static String wrapPowerExpressions(String json) {
        StringBuilder result   = new StringBuilder(json.length() + 32);
        boolean       inString = false;
        int           strStart = 0; // position in result right after the opening "

        for (int i = 0; i < json.length(); ) {
            char c = json.charAt(i);

            // Track JSON string boundaries
            if (c == '"') {
                int bs = 0;
                for (int j = i - 1; j >= 0 && json.charAt(j) == '\\'; j--) bs++;
                if (bs % 2 == 0) {
                    if (!inString) {
                        inString = true;
                        result.append(c);
                        strStart = result.length(); // content begins after the opening "
                        i++;
                        continue;
                    } else {
                        inString = false;
                        result.append(c);
                        i++;
                        continue;
                    }
                }
            }

            if (!inString) { result.append(c); i++; continue; }

            // Inside a JSON string: detect ) ** (expr) or ) ** identifier patterns
            if (c == ')') {
                int j = i + 1;
                while (j < json.length() && json.charAt(j) == ' ') j++;

                if (j + 1 < json.length() && json.charAt(j) == '*' && json.charAt(j + 1) == '*') {
                    int k = j + 2;
                    while (k < json.length() && json.charAt(k) == ' ') k++;

                    // Determine the end of the exponent
                    int expEnd = -1;
                    if (k < json.length() && json.charAt(k) == '(') {
                        // Parenthesised exponent: scan for matching )
                        int depth = 1;
                        expEnd = k + 1;
                        while (expEnd < json.length() && depth > 0) {
                            char ec = json.charAt(expEnd);
                            if      (ec == '(') depth++;
                            else if (ec == ')') depth--;
                            if (depth > 0) expEnd++;
                        }
                        if (depth != 0) expEnd = -1; // unbalanced — skip
                    } else if (k < json.length()) {
                        char first = json.charAt(k);
                        if (Character.isLetterOrDigit(first) || first == '_' || first == '$') {
                            // Simple identifier/number exponent
                            expEnd = k;
                            while (expEnd + 1 < json.length()) {
                                char ec = json.charAt(expEnd + 1);
                                if (Character.isLetterOrDigit(ec) || ec == '_' || ec == '$' || ec == '.') expEnd++;
                                else break;
                            }
                        }
                    }

                    if (expEnd >= 0) {
                        // Find the opening ( of the base by scanning backward in result
                        int depth     = 1;
                        int baseStart = result.length() - 1;
                        while (baseStart >= strStart && depth > 0) {
                            char bc = result.charAt(baseStart);
                            if      (bc == ')') depth++;
                            else if (bc == '(') depth--;
                            if (depth > 0) baseStart--;
                        }

                        if (depth == 0 && baseStart >= strStart) {
                            // Skip if already wrapped: ( immediately precedes the base and
                            // ) immediately follows the exponent → avoid double-wrapping
                            boolean alreadyWrapped =
                                    baseStart > strStart && result.charAt(baseStart - 1) == '('
                                    && expEnd + 1 < json.length() && json.charAt(expEnd + 1) == ')';
                            if (!alreadyWrapped) {
                                result.insert(baseStart, '(');           // open wrapper
                                strStart++; // insert shifts all indices after baseStart by 1
                                for (int s = i; s <= expEnd; s++) result.append(json.charAt(s));
                                result.append(')');                      // close wrapper
                                i = expEnd + 1;
                                continue;
                            }
                        }
                    }
                }
            }

            result.append(c);
            i++;
        }
        return result.toString();
    }

    /**
     * Converts {@code ((base) ** exp)} patterns to {@code $reduce}-based power computations.
     *
     * <p>The JSONata JVM runtime does not support {@code **} inside parenthesised expressions.
     * After {@link #wrapPowerExpressions} adds the outer {@code ()} for grouping, the {@code **}
     * is still rejected. This step converts the wrapped form to a {@code $reduce} loop:
     *
     * <ul>
     *   <li>{@code ((base) ** n)} →
     *       {@code $reduce([1..n], function($acc, $m) \{$acc * (base)\}, 1)}</li>
     *   <li>{@code ((base) ** (0 - n))} →
     *       {@code (1 / $reduce([1..n], function($acc, $m) \{$acc * (base)\}, 1))}</li>
     * </ul>
     *
     * <p>Only fires for the double-parenthesis patterns created by {@code wrapPowerExpressions}.
     * Operates inside JSON string values only.
     */
    static String convertPowerToReduce(String json) {
        StringBuilder result   = new StringBuilder(json.length() + 64);
        boolean       inString = false;

        for (int i = 0; i < json.length(); ) {
            char c = json.charAt(i);

            // Track JSON string boundaries
            if (c == '"') {
                int bs = 0;
                for (int j = i - 1; j >= 0 && json.charAt(j) == '\\'; j--) bs++;
                if (bs % 2 == 0) { inString = !inString; result.append(c); i++; continue; }
            }

            if (!inString) { result.append(c); i++; continue; }

            // Inside JSON string: detect ((base) ** exp) written by wrapPowerExpressions
            if (c == '(' && i + 1 < json.length() && json.charAt(i + 1) == '(') {
                // Scan for the closing ) of the inner (base)
                int innerOpen  = i + 1;
                int innerClose = innerOpen + 1;
                int depth      = 1;
                while (innerClose < json.length() && depth > 0) {
                    char ec = json.charAt(innerClose);
                    if      (ec == '(') depth++;
                    else if (ec == ')') depth--;
                    if (depth > 0) innerClose++;
                }

                if (depth == 0) {
                    int j = innerClose + 1;
                    while (j < json.length() && json.charAt(j) == ' ') j++;

                    if (j + 1 < json.length() && json.charAt(j) == '*' && json.charAt(j + 1) == '*') {
                        int k = j + 2;
                        while (k < json.length() && json.charAt(k) == ' ') k++;

                        String base    = json.substring(innerOpen, innerClose + 1); // "(1 + $r)"
                        boolean negExp = false;
                        String  expVar = null;
                        int     afterExp = -1;

                        if (k < json.length() && json.charAt(k) == '(') {
                            // Check for (0 - identifier) pattern from the negative-exponent fix
                            java.util.regex.Matcher neg = java.util.regex.Pattern.compile(
                                    "^\\(0\\s*-\\s*(\\$?\\w[\\w.]*)\\)").matcher(json.substring(k));
                            if (neg.find()) {
                                negExp   = true;
                                expVar   = neg.group(1);
                                afterExp = k + neg.end();
                            }
                        } else if (k < json.length()) {
                            char first = json.charAt(k);
                            if (Character.isLetterOrDigit(first) || first == '_' || first == '$') {
                                int expEnd = k;
                                while (expEnd + 1 < json.length()) {
                                    char ec = json.charAt(expEnd + 1);
                                    if (Character.isLetterOrDigit(ec) || ec == '_' || ec == '$' || ec == '.') expEnd++;
                                    else break;
                                }
                                expVar   = json.substring(k, expEnd + 1);
                                afterExp = expEnd + 1;
                            }
                        }

                        if (expVar != null && afterExp >= 0
                                && afterExp < json.length() && json.charAt(afterExp) == ')') {
                            if (negExp) {
                                // ((base) ** (0 - n)) → (1 / $reduce([1..n], fn, 1))
                                result.append("(1 / $reduce([1..").append(expVar)
                                      .append("], function($acc, $m) {$acc * ").append(base)
                                      .append("}, 1))");
                            } else {
                                // ((base) ** n) → $reduce([1..n], fn, 1)
                                result.append("$reduce([1..").append(expVar)
                                      .append("], function($acc, $m) {$acc * ").append(base)
                                      .append("}, 1)");
                            }
                            i = afterExp + 1;
                            continue;
                        }
                    }
                }
            }

            result.append(c);
            i++;
        }
        return result.toString();
    }

    /**
     * Converts {@code function($params) (...)} to {@code function($params) {...}}.
     *
     * <p>Our JSONata JVM runtime requires braces for the function body. LLMs often write
     * parentheses instead, which causes "Expected LBRACE but found LPAREN" at compile time.
     * Balanced-paren counting ensures the correct closing {@code )} is replaced even when the
     * body contains nested parentheses (e.g. {@code ($m - 1)}).
     */
    static String fixLambdaBodies(String json) {
        java.util.regex.Pattern p = java.util.regex.Pattern.compile("function\\([^)]*\\)\\s*\\(");
        java.util.regex.Matcher m = p.matcher(json);
        char[] chars = json.toCharArray();
        while (m.find()) {
            int openParen = m.end() - 1;
            if (chars[openParen] != '(') continue; // already replaced by a prior iteration
            int depth = 1;
            int i = openParen + 1;
            while (i < chars.length) {
                char c = chars[i];
                if (c == '(') depth++;
                else if (c == ')') {
                    depth--;
                    if (depth == 0) break;
                }
                i++;
            }
            if (depth == 0) {
                chars[openParen] = '{';
                chars[i] = '}';
            }
        }
        return new String(chars);
    }

    /**
     * Adds a default {@code "policy": "rollback"} to constraint objects that are missing the field.
     *
     * <p>LLMs frequently omit the required {@code policy} field from constraint specs. The
     * validator rejects any constraint where {@code policy} is {@code null}. This repair runs on
     * the Jackson tree so it handles all whitespace and escaping variants safely.
     *
     * @return the repaired JSON string, or the original if it cannot be parsed as a JSON object
     */
    public static String repairConstraintPolicy(String json, ObjectMapper mapper) {
        try {
            JsonNode root = mapper.readTree(json);
            if (!root.isObject()) return json;
            // Cover a full spec's "constraints" and a SpecEvolution's "upsertConstraints".
            defaultConstraintPolicy(root.path("constraints"));
            defaultConstraintPolicy(root.path("upsertConstraints"));
            return mapper.writeValueAsString(root);
        } catch (JsonProcessingException e) {
            return json;
        }
    }

    /** Adds a default {@code "policy":"rollback"} to every policy-less constraint object in an array. */
    private static void defaultConstraintPolicy(JsonNode constraints) {
        if (!constraints.isArray()) return;
        for (JsonNode constraint : constraints) {
            if (constraint.isObject() && !constraint.has("policy")) {
                ((ObjectNode) constraint).put("policy", "rollback");
            }
        }
    }

    /**
     * Enriches validation errors with human-readable hints for JVM-codegen failures that are
     * otherwise opaque to an LLM.
     *
     * <p>"variable $X is already defined in method __blockN" means the expression uses
     * JSONata's {@code :=} to rebind a variable that was already bound in an outer scope.
     * LLMs frequently trigger this by using {@code $map} with outer-scope variable mutation
     * (e.g. amortization schedules). The hint points them to the {@code $reduce} pattern.
     */
    static List<ModelSpecValidator.ValidationError> annotateErrors(
            List<ModelSpecValidator.ValidationError> errors) {
        return errors.stream().map(e -> {
            String hint = hintFor(e.message());
            return hint == null ? e
                    : new ModelSpecValidator.ValidationError(e.location(), e.message() + hint, e.severity());
        }).toList();
    }

    /**
     * Returns a targeted " — FIX: …" hint for a known JVM-codegen / parse failure whose raw message
     * is otherwise opaque to an LLM, or {@code null} if no specific hint applies. Surgical, rule-named
     * hints make the repair loop converge instead of re-emitting the same mistake.
     */
    private static String hintFor(String message) {
        if (message.contains("is already defined in method __block")) {
            return " — FIX: variables are immutable in JSONata; you cannot rebind an outer-scope "
                    + "variable inside a lambda. Replace $map + outer-variable reassignment with "
                    + "$reduce (see Pattern B in system instructions).";
        }
        if (message.contains("Expected RPAREN") || message.contains("Expected LBRACE")
                || message.contains("reached end of expression")) {
            return " — FIX: unbalanced brackets. Count every ( ) { } [ ] and close them all. Keep a "
                    + "computed array a SINGLE $reduce(...) call and reference already-derived fields "
                    + "directly rather than wrapping it in an outer ( …; $reduce(…) ) sequence.";
        }
        if (message.contains("Unexpected token 'between'")) {
            return " — FIX: JSONata has no 'between' operator; write (x >= lo and x <= hi).";
        }
        if (message.contains("Unexpected token 'mod'")) {
            return " — FIX: JSONata modulo is % , not the 'mod' keyword: a % b.";
        }
        if (message.contains("Unexpected token 'in'")) {
            return " — FIX: JSONata has no 'in' operator; write (x = a or x = b or x = c).";
        }
        return null;
    }

    /**
     * Returns {@code true} when a raw LLM response looks like it was cut off before the JSON
     * object was completed — either the closing {@code }} is missing or brace depth is non-zero.
     *
     * <p>Used to choose a "generate a shorter spec" repair prompt instead of sending the
     * broken truncated text back to the model.
     */
    private static boolean isLikelyTruncated(String raw) {
        if (raw == null || raw.isBlank()) return false;
        String stripped = raw.strip();
        // If there's a code fence, strip only the closing fence for the end check
        String tail = stripped.endsWith("```") ? stripped.substring(0, stripped.length() - 3).strip() : stripped;
        if (!tail.endsWith("}")) return true;

        // Count brace depth outside JSON strings; depth != 0 means unbalanced
        int depth = 0;
        boolean inStr = false;
        for (int i = 0; i < stripped.length(); i++) {
            char c = stripped.charAt(i);
            if (c == '"') {
                int bs = 0;
                for (int j = i - 1; j >= 0 && stripped.charAt(j) == '\\'; j--) bs++;
                if (bs % 2 == 0) inStr = !inStr;
            }
            if (!inStr) {
                if      (c == '{') depth++;
                else if (c == '}') depth--;
            }
        }
        return depth != 0;
    }

    private SpecEvolution parseEvolution(String json) {
        try {
            return mapper.readValue(json, SpecEvolution.class);
        } catch (JsonProcessingException e) {
            return null;
        }
    }

    private String writeJson(Object obj) {
        try {
            return mapper.writeValueAsString(obj);
        } catch (JsonProcessingException e) {
            return "{}";
        }
    }
}
