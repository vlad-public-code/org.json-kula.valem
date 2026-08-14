package org.json_kula.valem.core.engine;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import org.json_kula.valem.core.blob.InMemoryBlobStore;
import org.json_kula.valem.core.graph.CompiledModel;
import org.json_kula.valem.core.graph.ModelSpecCompiler;
import org.json_kula.valem.core.model.ModelSpec;
import org.json_kula.valem.core.state.ModelState;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The per-evaluation wall-clock budget.
 *
 * <p>Derivations, constraints and effect triggers are evaluated <b>inside the model lock</b>, so a
 * non-terminating expression does not merely fail one request — it holds the lock, and where Loom
 * gives parallelism 1 it can wedge the service. The budget turns that into one failed field.
 */
class ExpressionTimeoutTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final JsonNodeFactory F = JsonNodeFactory.instance;

    // ── Configuration ─────────────────────────────────────────────────────────

    @Test
    void fallsBackToTheLibraryDefaultWhenNothingOverridesIt() {
        assertThat(System.getProperty("valem.limits.expression-timeout-ms")).isNull();
        assertThat(System.getenv("VALEM_LIMITS_EXPRESSION_TIMEOUT_MS")).isNull();
        assertThat(ExpressionCache.resolveTimeoutMs()).isEqualTo(2_000);
        assertThat(ExpressionCache.FALLBACK_TIMEOUT_MS).isEqualTo(2_000);
    }

    @Test
    void aSystemPropertyOverridesTheDefault() {
        System.setProperty("valem.limits.expression-timeout-ms", "500");
        try {
            assertThat(ExpressionCache.resolveTimeoutMs()).isEqualTo(500);
        } finally {
            System.clearProperty("valem.limits.expression-timeout-ms");
        }
    }

    @Test
    void zeroDisablesTheTimeoutRatherThanBeingFloored() {
        // The escape hatch for a deployment whose evaluations are genuinely unbounded and which
        // accepts the lock contention; it must survive resolution as 0, not be raised to a minimum.
        System.setProperty("valem.limits.expression-timeout-ms", "0");
        try {
            assertThat(ExpressionCache.resolveTimeoutMs()).isZero();
        } finally {
            System.clearProperty("valem.limits.expression-timeout-ms");
        }
    }

    @Test
    void aNegativeValueIsNormalisedToDisabled() {
        System.setProperty("valem.limits.expression-timeout-ms", "-5");
        try {
            assertThat(ExpressionCache.resolveTimeoutMs()).isZero();
        } finally {
            System.clearProperty("valem.limits.expression-timeout-ms");
        }
    }

    @Test
    void aMalformedOverrideFallsBackInsteadOfStoppingTheEngine() {
        // Read in a static initialiser: throwing here would fail every model in the process.
        System.setProperty("valem.limits.expression-timeout-ms", "soon");
        try {
            assertThat(ExpressionCache.resolveTimeoutMs()).isEqualTo(2_000);
        } finally {
            System.clearProperty("valem.limits.expression-timeout-ms");
        }
    }

    // ── Enforcement ───────────────────────────────────────────────────────────

    /**
     * The budget is resolved per compilation, so a test can install a short one and use an
     * expression that is merely slow rather than pathological. Each enforcement case uses a
     * <b>distinct</b> expression string: the compiled-expression cache is process-wide and an
     * instance keeps the budget it was compiled with, so a shared string would carry another test's
     * setting.
     */
    private static final String SLOW =
            "$sum($map([1..3000000], function($i) { $length($string($i * base)) }))";

    @Test
    @Timeout(value = 120, unit = TimeUnit.SECONDS)
    void a_runaway_derivation_times_out_and_degrades_to_a_null_value_and_an_error_trace() throws Exception {
        System.setProperty("valem.limits.expression-timeout-ms", "20");
        try {
            ModelRuntime rt = runtime("""
                { "id": "timeout-a", "schema": {},
                  "derivations": [ { "path": "$.slow", "expr": "%s + 1" } ] }
                """.formatted(SLOW));

            rt.mutate(Map.of("$.base", F.numberNode(2)));

            assertThat(rt.getValue("$.slow").isNull())
                    .as("a timed-out derivation yields null, like any other evaluation error").isTrue();
            assertThat(rt.explain("$.slow").getLast().errorMessage())
                    .as("and the trace says why").containsIgnoringCase("timeout");
        } finally {
            System.clearProperty("valem.limits.expression-timeout-ms");
        }
    }

    /**
     * A mutation whose derivation runs away still <b>commits and returns</b>. This is the property
     * that matters: the evaluation happens inside the model lock, so the alternative is not a slow
     * request but a held lock.
     */
    @Test
    @Timeout(value = 120, unit = TimeUnit.SECONDS)
    void the_mutation_still_completes_rather_than_hanging() throws Exception {
        System.setProperty("valem.limits.expression-timeout-ms", "20");
        try {
            ModelRuntime rt = runtime("""
                { "id": "timeout-b", "schema": {},
                  "derivations": [
                    { "path": "$.slow",  "expr": "%s + 2" },
                    { "path": "$.quick", "expr": "base * 10" }
                  ] }
                """.formatted(SLOW));

            ModelRuntime.MutationResult result = rt.mutate(Map.of("$.base", F.numberNode(3)));

            assertThat(result.success()).isTrue();
            assertThat(rt.getValue("$.quick").asInt())
                    .as("an unrelated derivation is unaffected").isEqualTo(30);
        } finally {
            System.clearProperty("valem.limits.expression-timeout-ms");
        }
    }

    /** The budget bounds a library call too: an export shares the calling evaluation's deadline. */
    @Test
    @Timeout(value = 120, unit = TimeUnit.SECONDS)
    void a_runaway_library_function_is_bounded_by_the_callers_budget() throws Exception {
        System.setProperty("valem.limits.expression-timeout-ms", "20");
        try {
            ModelRuntime rt = runtime("""
                { "id": "timeout-c", "schema": {},
                  "library": "( $grind := function($n){ $sum($map([1..3000000], function($i){ $length($string($i * $n)) })) }; [\\"grind\\"] )",
                  "derivations": [ { "path": "$.slow", "expr": "$grind(base) + 3" } ] }
                """);

            rt.mutate(Map.of("$.base", F.numberNode(2)));

            assertThat(rt.getValue("$.slow").isNull()).isTrue();
            assertThat(rt.explain("$.slow").getLast().errorMessage()).containsIgnoringCase("timeout");
        } finally {
            System.clearProperty("valem.limits.expression-timeout-ms");
        }
    }

    /** With the budget disabled the same expression runs to completion. */
    @Test
    @Timeout(value = 120, unit = TimeUnit.SECONDS)
    void zero_lets_a_long_evaluation_finish() throws Exception {
        System.setProperty("valem.limits.expression-timeout-ms", "0");
        try {
            ModelRuntime rt = runtime("""
                { "id": "timeout-d", "schema": {},
                  "derivations": [ { "path": "$.slow", "expr": "%s + 4" } ] }
                """.formatted(SLOW));

            rt.mutate(Map.of("$.base", F.numberNode(2)));

            assertThat(rt.getValue("$.slow").isNull())
                    .as("no budget, so the evaluation completes").isFalse();
        } finally {
            System.clearProperty("valem.limits.expression-timeout-ms");
        }
    }

    /** An ordinary expression is nowhere near the budget, even a small one. */
    @Test
    void a_normal_expression_is_unaffected() throws Exception {
        ModelRuntime rt = runtime("""
            { "id": "timeout-e", "schema": {},
              "derivations": [ { "path": "$.doubled", "expr": "$sum($map([1..1000], function($i){ $i * base }))" } ] }
            """);

        rt.mutate(Map.of("$.base", F.numberNode(2)));

        assertThat(rt.getValue("$.doubled").asInt()).isEqualTo(1001000);
    }

    private ModelRuntime runtime(String specJson) throws Exception {
        ModelSpec spec = MAPPER.readValue(specJson, ModelSpec.class);
        CompiledModel model = ModelSpecCompiler.compile(spec);
        ModelState state = new ModelState(model, new InMemoryBlobStore());
        return new ModelRuntime(model, state);
    }
}
