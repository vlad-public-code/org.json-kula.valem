package org.json_kula.valem.core.engine;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.json_kula.valem.core.blob.InMemoryBlobStore;
import org.json_kula.valem.core.graph.CompiledModel;
import org.json_kula.valem.core.graph.DependencyGraph;
import org.json_kula.valem.core.graph.ModelSpecCompiler;
import org.json_kula.valem.core.model.ModelSpec;
import org.json_kula.valem.core.state.ModelState;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class DerivationEvaluatorTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private ExpressionCache cache;
    private DerivationEvaluator evaluator;

    @BeforeEach
    void setUp() {
        cache     = new ExpressionCache();
        evaluator = new DerivationEvaluator(cache);
    }

    // ── evalExpression ─────────────────────────────────────────────────────────

    @Test
    void eval_arithmetic_sum() throws Exception {
        ModelState state = stateFrom("{}", "{ \"id\":\"m\", \"schema\":{} }");
        state.setValue("$.order.subtotal", JsonNodeFactory.instance.numberNode(80.0));
        state.setValue("$.order.tax",      JsonNodeFactory.instance.numberNode(20.0));

        double result = evaluator.evalExpression("order.subtotal + order.tax", state).asDouble();
        assertThat(result).isEqualTo(100.0);
    }

    @Test
    void eval_missing_field_returns_null_node() throws Exception {
        ModelState state = stateFrom("{}", "{ \"id\":\"m\", \"schema\":{} }");
        var result = evaluator.evalExpression("nonexistent.field", state);
        // JSONata returns null/undefined for missing paths
        assertThat(result.isNull() || result.isMissingNode()).isTrue();
    }

    // ── evaluate (full lifecycle) ──────────────────────────────────────────────

    @Test
    void evaluates_dirty_derived_field() throws Exception {
        String specJson = """
            {
              "id": "m", "schema": {},
              "derivations": [
                { "path": "$.order.total", "expr": "order.subtotal + order.tax" }
              ]
            }
            """;
        CompiledModel model = ModelSpecCompiler.compile(MAPPER.readValue(specJson, ModelSpec.class));
        ModelState state = new ModelState(model, new InMemoryBlobStore());

        state.setValue("$.order.subtotal", JsonNodeFactory.instance.numberNode(90.0));
        state.setValue("$.order.tax",      JsonNodeFactory.instance.numberNode(10.0));

        // Simulate dirty propagation result
        Set<String> dirty = model.graph().transitivelyDependentOn("$.order.subtotal");
        dirty = new java.util.HashSet<>(dirty);
        dirty.addAll(model.graph().transitivelyDependentOn("$.order.tax"));

        List<String> evaluated = evaluator.evaluate(model, state, dirty);

        assertThat(evaluated).contains("$.order.total");
        assertThat(state.getDerived("$.order.total").asDouble()).isEqualTo(100.0);
    }

    @Test
    void skips_non_dirty_derived_field() throws Exception {
        String specJson = """
            {
              "id": "m", "schema": {},
              "derivations": [
                { "path": "$.order.total",     "expr": "order.subtotal + order.tax" },
                { "path": "$.order.amountDue", "expr": "order.total - order.discount" }
              ]
            }
            """;
        CompiledModel model = ModelSpecCompiler.compile(MAPPER.readValue(specJson, ModelSpec.class));
        ModelState state = new ModelState(model, new InMemoryBlobStore());

        state.setValue("$.order.discount", JsonNodeFactory.instance.numberNode(5.0));

        // Only order.amountDue is dirty (discount changed, not subtotal/tax)
        Set<String> dirty = model.graph().transitivelyDependentOn("$.order.discount");

        List<String> evaluated = evaluator.evaluate(model, state, dirty);

        // order.total was NOT dirty so should not be evaluated
        assertThat(evaluated).doesNotContain("$.order.total");
        assertThat(evaluated).contains("$.order.amountDue");
    }

    @Test
    void chained_derivations_see_upstream_derived_value() throws Exception {
        // total = subtotal + tax (level 1)
        // amountDue = total - discount (level 2 — reads the DERIVED total, not a base field)
        String specJson = """
            {
              "id": "m", "schema": {},
              "derivations": [
                { "path": "$.order.total",     "expr": "order.subtotal + order.tax" },
                { "path": "$.order.amountDue", "expr": "order.total - order.discount" }
              ]
            }
            """;
        CompiledModel model = ModelSpecCompiler.compile(MAPPER.readValue(specJson, ModelSpec.class));
        ModelState state = new ModelState(model, new InMemoryBlobStore());

        state.setValue("$.order.subtotal", JsonNodeFactory.instance.numberNode(100.0));
        state.setValue("$.order.tax",      JsonNodeFactory.instance.numberNode(10.0));
        state.setValue("$.order.discount", JsonNodeFactory.instance.numberNode(5.0));

        Set<String> dirty = new java.util.HashSet<>(model.graph().transitivelyDependentOn("$.order.subtotal"));
        dirty.addAll(model.graph().transitivelyDependentOn("$.order.tax"));
        dirty.addAll(model.graph().transitivelyDependentOn("$.order.discount"));

        evaluator.evaluate(model, state, dirty);

        // total = 100 + 10 = 110
        assertThat(state.getDerived("$.order.total").asDouble()).isEqualTo(110.0);
        // amountDue reads the derived total (110), not the base doc (which has no total field)
        assertThat(state.getDerived("$.order.amountDue").asDouble()).isEqualTo(105.0);
    }

    @Test
    void three_level_derivation_chain_produces_correct_result() throws Exception {
        // a = x * 2 (level 1)
        // b = a + 1 (level 2, reads derived a)
        // c = b * b (level 3, reads derived b)
        String specJson = """
            {
              "id": "m", "schema": {},
              "derivations": [
                { "path": "$.a", "expr": "x * 2" },
                { "path": "$.b", "expr": "a + 1" },
                { "path": "$.c", "expr": "b * b" }
              ]
            }
            """;
        CompiledModel model = ModelSpecCompiler.compile(MAPPER.readValue(specJson, ModelSpec.class));
        ModelState state = new ModelState(model, new InMemoryBlobStore());

        state.setValue("$.x", JsonNodeFactory.instance.numberNode(5.0));

        Set<String> dirty = new java.util.HashSet<>(model.graph().transitivelyDependentOn("$.x"));

        evaluator.evaluate(model, state, dirty);

        // a = 5*2 = 10; b = 10+1 = 11; c = 11*11 = 121
        assertThat(state.getDerived("$.a").asDouble()).isEqualTo(10.0);
        assertThat(state.getDerived("$.b").asDouble()).isEqualTo(11.0);
        assertThat(state.getDerived("$.c").asDouble()).isEqualTo(121.0);
    }

    // ── Parallel level evaluation ──────────────────────────────────────────────

    @Test
    void array_shrink_produces_no_phantom_derived_elements() throws Exception {
        // C-T3: a wildcard derivation on items[*]; after the array shrinks, stale derived entries
        // (e.g. $.items[2].lineTotal) must NOT reappear as phantom elements in the merged document,
        // and this must hold from the length-aware splice alone (no manual stale-entry clear).
        String specJson = """
            {
              "id": "m", "schema": {},
              "derivations": [
                { "path": "$.items[*].lineTotal", "expr": "$parent.price * $parent.qty" }
              ]
            }
            """;
        CompiledModel model = ModelSpecCompiler.compile(MAPPER.readValue(specJson, ModelSpec.class));
        ModelState state = new ModelState(model, new InMemoryBlobStore());

        // Three items → evaluate wildcard derivation for all three.
        for (int i = 0; i < 3; i++) {
            state.setValue("$.items[" + i + "].price", JsonNodeFactory.instance.numberNode(2));
            state.setValue("$.items[" + i + "].qty",   JsonNodeFactory.instance.numberNode(i + 1));
        }
        Set<String> allNodes = new java.util.HashSet<>();
        model.graph().evaluationLevels().forEach(allNodes::addAll);
        evaluator.evaluate(model, state, allNodes);
        assertThat(state.mergedDocument().at("/items").size()).isEqualTo(3);
        assertThat(state.mergedDocument().at("/items/2/lineTotal").asInt()).isEqualTo(6);

        // Shrink the base array to a single element. Do NOT re-evaluate: the stale
        // $.items[1]/$.items[2] derived entries remain in the cache.
        state.setValue("$.items", MAPPER.readTree("[ { \"price\": 5, \"qty\": 4 } ]"));

        var merged = state.mergedDocument();
        assertThat(merged.at("/items").size()).isEqualTo(1);
        assertThat(merged.at("/items/1").isMissingNode()).isTrue();
        assertThat(merged.at("/items/2").isMissingNode()).isTrue();
    }

    @Test
    void two_independent_derivations_produce_correct_results() throws Exception {
        // total = subtotal + tax; discount = retail * rate
        // These derivations share no edges, so they sit at the same level and are
        // evaluated in parallel when both are dirty.
        String specJson = """
            {
              "id": "m", "schema": {},
              "derivations": [
                { "path": "$.order.total",    "expr": "order.subtotal + order.tax" },
                { "path": "$.order.discount", "expr": "order.retail * order.rate" }
              ]
            }
            """;
        CompiledModel model = ModelSpecCompiler.compile(MAPPER.readValue(specJson, ModelSpec.class));
        ModelState state = new ModelState(model, new InMemoryBlobStore());

        state.setValue("$.order.subtotal", JsonNodeFactory.instance.numberNode(80.0));
        state.setValue("$.order.tax",      JsonNodeFactory.instance.numberNode(20.0));
        state.setValue("$.order.retail",   JsonNodeFactory.instance.numberNode(200.0));
        state.setValue("$.order.rate",     JsonNodeFactory.instance.numberNode(0.15));

        Set<String> dirty = new java.util.HashSet<>();
        dirty.addAll(model.graph().transitivelyDependentOn("$.order.subtotal"));
        dirty.addAll(model.graph().transitivelyDependentOn("$.order.tax"));
        dirty.addAll(model.graph().transitivelyDependentOn("$.order.retail"));
        dirty.addAll(model.graph().transitivelyDependentOn("$.order.rate"));

        List<String> evaluated = evaluator.evaluate(model, state, dirty);

        assertThat(evaluated).containsExactlyInAnyOrder("$.order.total", "$.order.discount");
        assertThat(state.getDerived("$.order.total").asDouble()).isEqualTo(100.0);
        assertThat(state.getDerived("$.order.discount").asDouble()).isEqualTo(30.0);
    }

    @Test
    void independent_derivations_occupy_same_level_in_graph() throws Exception {
        String specJson = """
            {
              "id": "m", "schema": {},
              "derivations": [
                { "path": "$.a.result", "expr": "a.x + a.y" },
                { "path": "$.b.result", "expr": "b.x * b.y" }
              ]
            }
            """;
        CompiledModel model = ModelSpecCompiler.compile(MAPPER.readValue(specJson, ModelSpec.class));

        // Both DERIVED nodes should share the same level since neither depends on the other
        boolean bothShareLevel = model.graph().evaluationLevels().stream()
                .anyMatch(level -> level.stream()
                        .filter(k -> model.graph().nodeInfo(k).kind() ==
                                     DependencyGraph.NodeKind.DERIVED)
                        .count() == 2);

        assertThat(bothShareLevel).isTrue();
    }

    @Test
    void traces_collected_for_parallel_evaluations() throws Exception {
        String specJson = """
            {
              "id": "m", "schema": {},
              "derivations": [
                { "path": "$.x.out", "expr": "x.in * 2" },
                { "path": "$.y.out", "expr": "y.in + 10" }
              ]
            }
            """;
        CompiledModel model = ModelSpecCompiler.compile(MAPPER.readValue(specJson, ModelSpec.class));
        ModelState state = new ModelState(model, new InMemoryBlobStore());

        state.setValue("$.x.in", JsonNodeFactory.instance.numberNode(5.0));
        state.setValue("$.y.in", JsonNodeFactory.instance.numberNode(3.0));

        Set<String> dirty = new java.util.HashSet<>();
        dirty.addAll(model.graph().transitivelyDependentOn("$.x.in"));
        dirty.addAll(model.graph().transitivelyDependentOn("$.y.in"));

        List<DerivationTrace> traces = new ArrayList<>();
        evaluator.evaluate(model, state, dirty, traces);

        assertThat(traces).hasSize(2);
        assertThat(traces.stream().map(DerivationTrace::targetPath))
                .containsExactlyInAnyOrder("$.x.out", "$.y.out");
    }

    // ── B-T1: one full merged-document materialization per cycle ───────────────

    @Test
    void merged_document_materialized_once_regardless_of_level_count() throws Exception {
        // The per-cycle merged document is deep-copied exactly once no matter how many
        // topological levels of derivations must be evaluated (previously: one copy per level).
        // The count must not scale with the number of levels.
        assertThat(fullMaterializations(2)).isEqualTo(1);
        assertThat(fullMaterializations(6)).isEqualTo(1);
    }

    /**
     * Builds a chain d0..d(levels-1) where each derivation reads the previous one — forcing
     * {@code levels} distinct topological levels — then counts how many times the base document
     * is deep-copied during a single {@code evaluateAndMerge} pass.
     */
    private int fullMaterializations(int levels) throws Exception {
        StringBuilder derivs = new StringBuilder("{ \"path\": \"$.d0\", \"expr\": \"n + 1\" }");
        for (int i = 1; i < levels; i++) {
            derivs.append(", { \"path\": \"$.d").append(i)
                  .append("\", \"expr\": \"d").append(i - 1).append(" + 1\" }");
        }
        String specJson = "{ \"id\": \"m\", \"schema\": {}, \"derivations\": [" + derivs + "] }";
        CompiledModel model = ModelSpecCompiler.compile(MAPPER.readValue(specJson, ModelSpec.class));
        ModelState state = new ModelState(model, new InMemoryBlobStore());

        // Swap in a counting base document so each full deep copy (the merged-document
        // materialization) is observable.
        CountingObjectNode counting = new CountingObjectNode();
        var baseDocField = ModelState.class.getDeclaredField("baseDoc");
        baseDocField.setAccessible(true);
        baseDocField.set(state, counting);

        state.setValue("$.n", JsonNodeFactory.instance.numberNode(0));

        Set<String> dirty = new java.util.HashSet<>();
        model.graph().evaluationLevels().forEach(dirty::addAll);

        counting.copies = 0;
        var outcome = evaluator.evaluateAndMerge(model, state, dirty, null);

        // Sanity: semantics unchanged — d0=1, d1=2, …, d(levels-1)=levels.
        assertThat(state.getDerived("$.d" + (levels - 1)).asInt()).isEqualTo(levels);
        assertThat(outcome.merged().at("/d" + (levels - 1)).asInt()).isEqualTo(levels);
        return counting.copies;
    }

    /** An {@link ObjectNode} that counts how many times it is deep-copied. */
    static final class CountingObjectNode extends ObjectNode {
        int copies = 0;
        CountingObjectNode() { super(JsonNodeFactory.instance); }
        @Override public ObjectNode deepCopy() { copies++; return super.deepCopy(); }
    }

    // ── ArrayPathExpander ──────────────────────────────────────────────────────

    @Test
    void expand_no_wildcard_returns_path_unchanged() throws Exception {
        ModelState state = stateFrom("{}", "{ \"id\":\"m\", \"schema\":{} }");
        List<String> result = ArrayPathExpander.expand("$.order.total", state);
        assertThat(result).containsExactly("$.order.total");
    }

    @Test
    void expand_wildcard_over_two_element_array() throws Exception {
        ModelState state = stateFrom("{}", "{ \"id\":\"m\", \"schema\":{} }");
        // Set up items array with 2 elements
        state.setValue("$.order.items[0].qty", JsonNodeFactory.instance.numberNode(1));
        state.setValue("$.order.items[1].qty", JsonNodeFactory.instance.numberNode(2));

        List<String> result = ArrayPathExpander.expand("$.order.items[*].qty", state);
        assertThat(result).containsExactly("$.order.items[0].qty", "$.order.items[1].qty");
    }

    @Test
    void expand_wildcard_over_empty_array_returns_empty() throws Exception {
        ModelState state = stateFrom("{}", "{ \"id\":\"m\", \"schema\":{} }");
        // "order.items" doesn't exist
        List<String> result = ArrayPathExpander.expand("$.order.items[*].qty", state);
        assertThat(result).isEmpty();
    }

    // ── Helpers ────────────────────────────────────────────────────────────────

    private ModelState stateFrom(String stateJson, String specJson) throws Exception {
        CompiledModel model = ModelSpecCompiler.compile(MAPPER.readValue(specJson, ModelSpec.class));
        ModelState state = new ModelState(model, new InMemoryBlobStore());
        // Seed base fields from stateJson if provided
        var root = MAPPER.readTree(stateJson);
        if (root.isObject()) {
            root.fields().forEachRemaining(e ->
                state.setValue(e.getKey(), e.getValue()));
        }
        return state;
    }
}
