package org.json_kula.valem.core.engine;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import org.json_kula.valem.core.blob.InMemoryBlobStore;
import org.json_kula.valem.core.graph.CompiledModel;
import org.json_kula.valem.core.graph.ModelSpecCompiler;
import org.json_kula.valem.core.model.ModelSpec;
import org.json_kula.valem.core.state.ModelState;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A model's {@code library} — named JSONata functions and values bound in every expression.
 *
 * <p>The spine of this class is {@link #library_functions_are_callable_from_every_evaluator()}:
 * {@code EvalBindings.forModel} is a single seam, and these cases are what prove it actually reaches
 * every evaluator rather than only the one that was developed against.
 */
class LibraryTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final JsonNodeFactory F = JsonNodeFactory.instance;

    // ── Binding reach ─────────────────────────────────────────────────────────

    @Test
    void library_functions_are_callable_from_every_evaluator() throws Exception {
        ModelRuntime rt = runtime("""
            { "id": "m", "schema": {},
              "library": "( $double := function($n){ $n * 2 }; $isBig := function($n){ $n > 100 }; [\\"double\\", \\"isBig\\"] )",
              "defaultValues": [ { "path": "$", "expr": "{ \\"base\\": 10 }" } ],
              "derivations": [
                { "path": "$.doubled", "expr": "$double(base)" },
                { "path": "$.lazy",    "expr": "$double(base) + 1", "evaluation": "lazy" }
              ],
              "metaDerivations": [
                { "path": "$.base", "property": "maximum", "expr": "$double(base) * 1000" }
              ],
              "constraints": [
                { "id": "not-big", "expr": "$not($isBig(base))", "message": "too big", "policy": "flag" }
              ] }
            """);

        rt.initialize();
        ModelRuntime.MutationResult small = rt.mutate(Map.of("$.base", F.numberNode(21)));

        assertThat(rt.getValue("$.doubled").asInt()).as("derivation").isEqualTo(42);
        assertThat(rt.getValue("$.lazy").asInt()).as("lazy derivation").isEqualTo(43);
        // A meta-derivation only re-evaluates when one of its inputs is dirty, so it reads `base`
        // as well as calling the library; the generous multiplier keeps the live maximum above the
        // values mutated below (the schema is checked against the PREVIOUS cycle's maximum).
        assertThat(rt.effectiveSchema("$.base").path("maximum").asInt())
                .as("meta-derivation").isEqualTo(42_000);
        assertThat(small.flaggedConstraints()).as("constraint holds for 21").isEmpty();

        ModelRuntime.MutationResult big = rt.mutate(Map.of("$.base", F.numberNode(500)));
        assertThat(big.flaggedConstraints()).as("constraint uses the library").hasSize(1);
    }

    @Test
    void library_function_is_callable_from_a_wildcard_derivation_alongside_parent() throws Exception {
        ModelRuntime rt = runtime("""
            { "id": "m", "schema": {},
              "library": "( $lineTotal := function($price, $qty){ $price * $qty }; [\\"lineTotal\\"] )",
              "derivations": [
                { "path": "$.items[*].total", "expr": "$lineTotal($parent.price, $parent.qty)" }
              ] }
            """);

        rt.mutate(Map.of("$.items", MAPPER.readTree("[{\"price\":3,\"qty\":4},{\"price\":5,\"qty\":6}]")));

        assertThat(rt.getValue("$.items[0].total").asInt()).isEqualTo(12);
        assertThat(rt.getValue("$.items[1].total").asInt()).isEqualTo(30);
    }

    @Test
    void library_function_is_callable_from_a_default_value_expression() throws Exception {
        ModelRuntime rt = runtime("""
            { "id": "m", "schema": {},
              "library": "( $seed := function($n){ $n * 3 }; [\\"seed\\"] )",
              "defaultValues": [ { "path": "$", "expr": "{ \\"balance\\": $seed(100) }" } ] }
            """);

        rt.initialize();

        assertThat(rt.getValue("$.balance").asInt()).isEqualTo(300);
    }

    @Test
    void library_function_is_callable_from_an_effect_trigger() throws Exception {
        ModelRuntime rt = runtime("""
            { "id": "m", "schema": {},
              "library": "( $isBig := function($n){ $n > 100 }; [\\"isBig\\"] )",
              "effects": [
                { "id": "notify", "executor": "caller", "trigger": "$isBig(amount)",
                  "emit": "big", "payload": { "amount": "amount" } }
              ] }
            """);

        assertThat(rt.mutate(Map.of("$.amount", F.numberNode(50))).dispatchedEffects()).isEmpty();
        assertThat(rt.mutate(Map.of("$.amount", F.numberNode(500))).dispatchedEffects()).hasSize(1);
    }

    // ── Exported values ───────────────────────────────────────────────────────

    @Test
    void an_exported_non_function_is_bound_as_a_value() throws Exception {
        ModelRuntime rt = runtime("""
            { "id": "m", "schema": {},
              "library": "( $taxYear := 2026; $bump := function($n){ $n + 1 }; [\\"taxYear\\", \\"bump\\"] )",
              "derivations": [
                { "path": "$.year",    "expr": "base + $taxYear - base" },
                { "path": "$.bumped",  "expr": "$bump(base)" }
              ] }
            """);

        rt.mutate(Map.of("$.base", F.numberNode(1)));

        assertThat(rt.getValue("$.year").asInt()).isEqualTo(2026);
        assertThat(rt.getValue("$.bumped").asInt()).isEqualTo(2);
    }

    // ── $const inside the library ─────────────────────────────────────────────

    @Test
    void library_function_reads_const_from_the_calling_evaluation() throws Exception {
        ModelRuntime rt = runtime("""
            { "id": "m", "schema": {},
              "constants": { "vatRate": 0.2 },
              "library": "( $withVat := function($net){ $net * (1 + $const.vatRate) }; [\\"withVat\\"] )",
              "derivations": [ { "path": "$.gross", "expr": "$withVat(net)" } ] }
            """);

        rt.mutate(Map.of("$.net", F.numberNode(100)));

        assertThat(rt.getValue("$.gross").asDouble()).isEqualTo(120.0);
    }

    @Test
    void an_exported_value_computed_from_const_reflects_that_models_constants() throws Exception {
        // Exported CONSTANTS are evaluated once, at definition time, against the constants in force
        // then — which is why LibraryCache keys on them. Two models sharing a definition but not its
        // constants must not share the frozen value.
        ModelRuntime low = runtime("""
            { "id": "low", "schema": {},
              "constants": { "brackets": [ { "rate": 0.1 }, { "rate": 0.4 } ] },
              "library": "( $maxRate := $max($const.brackets.rate); [\\"maxRate\\"] )",
              "derivations": [ { "path": "$.top", "expr": "base + $maxRate - base" } ] }
            """);
        ModelRuntime high = runtime("""
            { "id": "high", "schema": {},
              "constants": { "brackets": [ { "rate": 0.1 }, { "rate": 0.9 } ] },
              "library": "( $maxRate := $max($const.brackets.rate); [\\"maxRate\\"] )",
              "derivations": [ { "path": "$.top", "expr": "base + $maxRate - base" } ] }
            """);

        low.mutate(Map.of("$.base", F.numberNode(0)));
        high.mutate(Map.of("$.base", F.numberNode(0)));

        assertThat(low.getValue("$.top").asDouble()).isEqualTo(0.4);
        assertThat(high.getValue("$.top").asDouble()).isEqualTo(0.9);
    }

    // ── Composition ───────────────────────────────────────────────────────────

    @Test
    void an_export_can_be_passed_as_a_function_value_and_piped() throws Exception {
        ModelRuntime rt = runtime("""
            { "id": "m", "schema": {},
              "library": "( $double := function($n){ $n * 2 }; [\\"double\\"] )",
              "derivations": [
                { "path": "$.mapped", "expr": "$map(nums, $double)" },
                { "path": "$.piped",  "expr": "single ~> $double" }
              ] }
            """);

        rt.mutate(Map.of("$.nums", MAPPER.readTree("[1,2,3]"), "$.single", F.numberNode(7)));

        assertThat(rt.getValue("$.mapped").toString()).isEqualTo("[2,4,6]");
        assertThat(rt.getValue("$.piped").asInt()).isEqualTo(14);
    }

    @Test
    void a_non_exported_helper_stays_reachable_from_an_export() throws Exception {
        ModelRuntime rt = runtime("""
            { "id": "m", "schema": {},
              "library": "( $half := function($n){ $n / 2 }; $quarter := function($n){ $half($half($n)) }; [\\"quarter\\"] )",
              "derivations": [ { "path": "$.q", "expr": "$quarter(base)" } ] }
            """);

        rt.mutate(Map.of("$.base", F.numberNode(80)));

        assertThat(rt.getValue("$.q").asInt()).isEqualTo(20);
    }

    @Test
    void mutually_recursive_exports_work_from_a_derivation() throws Exception {
        ModelRuntime rt = runtime("""
            { "id": "m", "schema": {},
              "library": "( $isEven := function($n){ $n = 0 ? true : $isOdd($n - 1) }; $isOdd := function($n){ $n = 0 ? false : $isEven($n - 1) }; [\\"isEven\\", \\"isOdd\\"] )",
              "derivations": [ { "path": "$.even", "expr": "$isEven(base)" } ] }
            """);

        rt.mutate(Map.of("$.base", F.numberNode(10)));
        assertThat(rt.getValue("$.even").asBoolean()).isTrue();
        rt.mutate(Map.of("$.base", F.numberNode(7)));
        assertThat(rt.getValue("$.even").asBoolean()).isFalse();
    }

    // ── Reactivity ────────────────────────────────────────────────────────────

    @Test
    void arguments_at_the_call_site_still_produce_dependency_edges() throws Exception {
        // The whole reason a library may not read the document: the field names stay in the
        // DERIVATION's expression, so the dependency graph still sees them and still recomputes.
        ModelRuntime rt = runtime("""
            { "id": "m", "schema": {},
              "library": "( $net := function($sub, $disc){ $sub - $disc }; [\\"net\\"] )",
              "derivations": [ { "path": "$.total", "expr": "$net(order.subtotal, order.discount)" } ] }
            """);

        rt.mutate(Map.of("$.order.subtotal", F.numberNode(100), "$.order.discount", F.numberNode(30)));
        assertThat(rt.getValue("$.total").asInt()).isEqualTo(70);

        rt.mutate(Map.of("$.order.discount", F.numberNode(10)));
        assertThat(rt.getValue("$.total").asInt()).as("recomputed on input change").isEqualTo(90);

        assertThat(rt.model().graph().dependenciesOf("$.total"))
                .as("both arguments are edges")
                .contains("$.order.subtotal", "$.order.discount");
    }

    @Test
    void explain_reports_the_call_site_and_its_input_paths() throws Exception {
        ModelRuntime rt = runtime("""
            { "id": "m", "schema": {},
              "library": "( $net := function($sub, $disc){ $sub - $disc }; [\\"net\\"] )",
              "derivations": [ { "path": "$.total", "expr": "$net(order.subtotal, order.discount)" } ] }
            """);

        rt.mutate(Map.of("$.order.subtotal", F.numberNode(100), "$.order.discount", F.numberNode(30)));

        List<DerivationTrace> traces = rt.explain("$.total");
        assertThat(traces).isNotEmpty();
        assertThat(traces.getLast().expression()).isEqualTo("$net(order.subtotal, order.discount)");
        assertThat(traces.getLast().inputPaths()).contains("$.order.subtotal", "$.order.discount");
    }

    // ── Failure modes ─────────────────────────────────────────────────────────

    @Test
    void a_library_function_erroring_at_runtime_yields_null_and_a_trace_not_a_thrown_mutation() throws Exception {
        ModelRuntime rt = runtime("""
            { "id": "m", "schema": {},
              "library": "( $bad := function($n){ $error(\\"nope\\") }; [\\"bad\\"] )",
              "derivations": [ { "path": "$.x", "expr": "$bad(base)" } ] }
            """);

        rt.mutate(Map.of("$.base", F.numberNode(1)));

        assertThat(rt.getValue("$.x").isNull()).isTrue();
        assertThat(rt.explain("$.x").getLast().errorMessage()).isNotNull();
    }

    // ── Thread safety ─────────────────────────────────────────────────────────

    @Test
    void exports_are_callable_concurrently_from_independent_runtimes() throws Exception {
        String spec = """
            { "id": "m", "schema": {},
              "library": "( $double := function($n){ $n * 2 }; [\\"double\\"] )",
              "derivations": [ { "path": "$.doubled", "expr": "$double(base)" } ] }
            """;
        ModelSpec parsed = MAPPER.readValue(spec, ModelSpec.class);

        int threads = 8, perThread = 25;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger ok = new AtomicInteger();
        try {
            for (int t = 0; t < threads; t++) {
                pool.submit(() -> {
                    start.await();
                    CompiledModel model = ModelSpecCompiler.compile(parsed);
                    ModelRuntime rt = new ModelRuntime(model, new ModelState(model, new InMemoryBlobStore()));
                    for (int i = 1; i <= perThread; i++) {
                        rt.mutate(Map.of("$.base", F.numberNode(i)));
                        if (rt.getValue("$.doubled").asInt() == i * 2) ok.incrementAndGet();
                    }
                    return null;
                });
            }
            start.countDown();
            pool.shutdown();
            assertThat(pool.awaitTermination(60, TimeUnit.SECONDS)).isTrue();
        } finally {
            pool.shutdownNow();
        }
        assertThat(ok.get()).isEqualTo(threads * perThread);
    }

    private ModelRuntime runtime(String specJson) throws Exception {
        ModelSpec spec = MAPPER.readValue(specJson, ModelSpec.class);
        CompiledModel model = ModelSpecCompiler.compile(spec);
        ModelState state = new ModelState(model, new InMemoryBlobStore());
        return new ModelRuntime(model, state);
    }
}
