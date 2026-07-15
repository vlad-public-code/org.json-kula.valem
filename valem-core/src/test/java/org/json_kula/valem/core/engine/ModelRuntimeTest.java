package org.json_kula.valem.core.engine;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import org.json_kula.valem.core.blob.InMemoryBlobStore;
import org.json_kula.valem.core.graph.CompiledModel;
import org.json_kula.valem.core.graph.ModelSpecCompiler;
import org.json_kula.valem.core.model.ModelSpec;
import org.json_kula.valem.core.state.ModelState;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ModelRuntimeTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    // ── Basic mutation + derivation ────────────────────────────────────────────

    @Test
    void mutate_updates_derived_field() throws Exception {
        ModelRuntime rt = runtime("""
            {
              "id": "order", "schema": {},
              "derivations": [
                { "path": "$.order.total", "expr": "order.subtotal + order.tax" }
              ]
            }
            """);

        rt.mutate(Map.of(
                "$.order.subtotal", JsonNodeFactory.instance.numberNode(80.0),
                "$.order.tax",      JsonNodeFactory.instance.numberNode(20.0)));

        assertThat(rt.getValue("$.order.total").asDouble()).isEqualTo(100.0);
    }

    @Test
    void mutate_result_reports_mutated_and_derived_paths() throws Exception {
        ModelRuntime rt = runtime("""
            {
              "id": "m", "schema": {},
              "derivations": [
                { "path": "$.order.total", "expr": "order.subtotal + order.tax" }
              ]
            }
            """);

        var result = rt.mutate(Map.of(
                "$.order.subtotal", JsonNodeFactory.instance.numberNode(90.0),
                "$.order.tax",      JsonNodeFactory.instance.numberNode(10.0)));

        assertThat(result.success()).isTrue();
        assertThat(result.mutatedPaths()).containsExactlyInAnyOrder("$.order.subtotal", "$.order.tax");
        assertThat(result.derivedUpdated()).contains("$.order.total");
    }

    // ── Constraint: rollback ───────────────────────────────────────────────────

    @Test
    void rollback_constraint_reverts_state_on_violation() throws Exception {
        ModelRuntime rt = runtime("""
            {
              "id": "m", "schema": {},
              "derivations": [
                { "path": "$.order.total", "expr": "order.subtotal + order.tax" }
              ],
              "constraints": [
                { "id": "credit-check",
                  "expr": "order.total <= customer.creditLimit",
                  "message": "Over limit", "policy": "rollback" }
              ]
            }
            """);

        // Seed initial valid state
        rt.mutate(Map.of(
                "$.customer.creditLimit", JsonNodeFactory.instance.numberNode(1000.0),
                "$.order.subtotal",       JsonNodeFactory.instance.numberNode(500.0),
                "$.order.tax",            JsonNodeFactory.instance.numberNode(50.0)));

        double totalBefore = rt.getValue("$.order.total").asDouble(); // 550

        // Now attempt a mutation that violates the constraint
        assertThatThrownBy(() -> rt.mutate(Map.of(
                        "$.order.subtotal", JsonNodeFactory.instance.numberNode(2000.0))))
                .isInstanceOf(ConstraintEvaluator.ConstraintViolationException.class);

        // State must be rolled back to 550, not 2000+tax
        assertThat(rt.getValue("$.order.total").asDouble()).isEqualTo(totalBefore);
        assertThat(rt.getValue("$.order.subtotal").asDouble()).isEqualTo(500.0);
    }

    // ── Constraint: flag ──────────────────────────────────────────────────────

    @Test
    void flag_constraint_commits_but_reports_violation() throws Exception {
        ModelRuntime rt = runtime("""
            {
              "id": "m", "schema": {},
              "constraints": [
                { "id": "soft-limit",
                  "expr": "order.amount <= 500",
                  "message": "Over soft limit", "policy": "flag" }
              ]
            }
            """);

        var result = rt.mutate("$.order.amount", JsonNodeFactory.instance.numberNode(600.0));

        assertThat(result.success()).isTrue();
        assertThat(result.hasFlags()).isTrue();
        assertThat(result.flaggedConstraints().getFirst().constraintId()).isEqualTo("soft-limit");
        // State committed despite flag
        assertThat(rt.getValue("$.order.amount").asDouble()).isEqualTo(600.0);
    }

    // ── Snapshot / restore via runtime ────────────────────────────────────────

    @Test
    void snapshot_and_restore_through_runtime() throws Exception {
        ModelRuntime rt = runtime("{ \"id\": \"m\", \"schema\": {} }");

        rt.mutate("$.a.val", JsonNodeFactory.instance.numberNode(1.0));
        var snap = rt.snapshot();

        rt.mutate("$.a.val", JsonNodeFactory.instance.numberNode(99.0));
        assertThat(rt.getValue("$.a.val").asDouble()).isEqualTo(99.0);

        rt.restore(snap);
        assertThat(rt.getValue("$.a.val").asDouble()).isEqualTo(1.0);
    }

    // ── Explain ───────────────────────────────────────────────────────────────

    @Test
    void explain_returns_constraint_traces() throws Exception {
        ModelRuntime rt = runtime("""
            {
              "id": "m", "schema": {},
              "constraints": [
                { "id": "pos", "expr": "x.val > 0", "message": "pos", "policy": "flag" }
              ]
            }
            """);

        rt.mutate("$.x.val", JsonNodeFactory.instance.numberNode(5.0));

        var traces = rt.explain("$constraint:pos");
        assertThat(traces).isNotEmpty();
        assertThat(traces.getFirst().constraintPassed()).isTrue();
    }

    @Test
    void explain_returns_derivation_traces_after_mutation() throws Exception {
        ModelRuntime rt = runtime("""
            {
              "id": "m", "schema": {},
              "derivations": [
                { "path": "$.order.total", "expr": "order.subtotal + order.tax" }
              ]
            }
            """);

        rt.mutate(Map.of(
                "$.order.subtotal", JsonNodeFactory.instance.numberNode(80.0),
                "$.order.tax",      JsonNodeFactory.instance.numberNode(20.0)));

        var traces = rt.explain("$.order.total");
        assertThat(traces).hasSize(1);
        DerivationTrace t = traces.getFirst();
        assertThat(t.targetPath()).isEqualTo("$.order.total");
        assertThat(t.expression()).isEqualTo("order.subtotal + order.tax");
        assertThat(t.result().asDouble()).isEqualTo(100.0);
        assertThat(t.errorMessage()).isNull();
        assertThat(t.constraintPassed()).isNull();
    }

    @Test
    void explain_accumulates_derivation_traces_across_mutations() throws Exception {
        ModelRuntime rt = runtime("""
            {
              "id": "m", "schema": {},
              "derivations": [
                { "path": "$.order.total", "expr": "order.subtotal + order.tax" }
              ]
            }
            """);

        rt.mutate(Map.of(
                "$.order.subtotal", JsonNodeFactory.instance.numberNode(50.0),
                "$.order.tax",      JsonNodeFactory.instance.numberNode(5.0)));
        rt.mutate("$.order.subtotal", JsonNodeFactory.instance.numberNode(100.0));

        var traces = rt.explain("$.order.total");
        assertThat(traces).hasSize(2);
        assertThat(traces.get(0).result().asDouble()).isEqualTo(55.0);
        assertThat(traces.get(1).result().asDouble()).isEqualTo(105.0);
    }

    @Test
    void derivation_traces_included_in_mutation_result() throws Exception {
        ModelRuntime rt = runtime("""
            {
              "id": "m", "schema": {},
              "derivations": [
                { "path": "$.order.total", "expr": "order.subtotal + order.tax" }
              ]
            }
            """);

        var result = rt.mutate(Map.of(
                "$.order.subtotal", JsonNodeFactory.instance.numberNode(60.0),
                "$.order.tax",      JsonNodeFactory.instance.numberNode(6.0)));

        assertThat(result.traces()).hasSize(1);
        assertThat(result.traces().getFirst().targetPath()).isEqualTo("$.order.total");
        assertThat(result.traces().getFirst().result().asDouble()).isEqualTo(66.0);
    }

    // ── Lazy evaluation ───────────────────────────────────────────────────────

    @Test
    void lazy_derivation_is_not_evaluated_during_mutation_cycle() throws Exception {
        ModelRuntime rt = runtime("""
            {
              "id": "m", "schema": {},
              "derivations": [
                { "path": "$.summary", "expr": "subtotal + tax", "evaluation": "lazy" }
              ]
            }
            """);

        var result = rt.mutate(Map.of(
                "$.subtotal", JsonNodeFactory.instance.numberNode(90.0),
                "$.tax",      JsonNodeFactory.instance.numberNode(10.0)));

        // LAZY derivation must NOT appear in derivedUpdated (not computed during mutation)
        assertThat(result.derivedUpdated()).doesNotContain("$.summary");
    }

    @Test
    void lazy_derivation_is_evaluated_on_first_getValue() throws Exception {
        ModelRuntime rt = runtime("""
            {
              "id": "m", "schema": {},
              "derivations": [
                { "path": "$.summary", "expr": "subtotal + tax", "evaluation": "lazy" }
              ]
            }
            """);

        rt.mutate(Map.of(
                "$.subtotal", JsonNodeFactory.instance.numberNode(90.0),
                "$.tax",      JsonNodeFactory.instance.numberNode(10.0)));

        // getValue triggers on-demand evaluation
        assertThat(rt.getValue("$.summary").asDouble()).isEqualTo(100.0);
    }

    @Test
    void lazy_derivation_updates_when_inputs_change() throws Exception {
        ModelRuntime rt = runtime("""
            {
              "id": "m", "schema": {},
              "derivations": [
                { "path": "$.summary", "expr": "subtotal + tax", "evaluation": "lazy" }
              ]
            }
            """);

        rt.mutate(Map.of(
                "$.subtotal", JsonNodeFactory.instance.numberNode(90.0),
                "$.tax",      JsonNodeFactory.instance.numberNode(10.0)));
        assertThat(rt.getValue("$.summary").asDouble()).isEqualTo(100.0);

        // Change an input — summary becomes stale
        rt.mutate("$.tax", JsonNodeFactory.instance.numberNode(20.0));
        // Next read triggers recomputation
        assertThat(rt.getValue("$.summary").asDouble()).isEqualTo(110.0);
    }

    @Test
    void fullState_includes_lazy_derivations() throws Exception {
        ModelRuntime rt = runtime("""
            {
              "id": "m", "schema": {},
              "derivations": [
                { "path": "$.summary", "expr": "subtotal + tax", "evaluation": "lazy" }
              ]
            }
            """);

        rt.mutate(Map.of(
                "$.subtotal", JsonNodeFactory.instance.numberNode(40.0),
                "$.tax",      JsonNodeFactory.instance.numberNode(5.0)));

        var doc = rt.fullState();
        assertThat(doc.path("summary").asDouble()).isEqualTo(45.0);
    }

    @Test
    void lazy_derivation_stale_flag_cleared_after_rollback() throws Exception {
        ModelRuntime rt = runtime("""
            {
              "id": "m", "schema": {},
              "derivations": [
                { "path": "$.summary", "expr": "subtotal + tax", "evaluation": "lazy" }
              ],
              "constraints": [
                { "id": "c1", "expr": "subtotal >= 0", "message": "must be non-negative",
                  "policy": "rollback" }
              ]
            }
            """);

        // Successful mutation — summary becomes stale
        rt.mutate(Map.of(
                "$.subtotal", JsonNodeFactory.instance.numberNode(50.0),
                "$.tax",      JsonNodeFactory.instance.numberNode(5.0)));

        // Force evaluation so summary is cached
        assertThat(rt.getValue("$.summary").asDouble()).isEqualTo(55.0);

        // Rolled-back mutation — subtotal goes negative, constraint fires
        assertThatThrownBy(() -> rt.mutate("$.subtotal", JsonNodeFactory.instance.numberNode(-1.0)))
                .isInstanceOf(ConstraintEvaluator.ConstraintViolationException.class);

        // After rollback, summary is stale (subtotal reverted, but summary still cached
        // at old value — fresh read should recompute to 55 from restored base state)
        assertThat(rt.getValue("$.summary").asDouble()).isEqualTo(55.0);
    }

    // ── Temporal history ──────────────────────────────────────────────────────

    @Test
    void history_is_empty_before_any_mutation() throws Exception {
        ModelRuntime rt = runtime("{ \"id\": \"m\", \"schema\": {} }");
        assertThat(rt.history().size()).isZero();
        assertThat(rt.stateAt(Instant.now())).isEmpty();
    }

    @Test
    void history_records_snapshot_after_each_successful_mutation() throws Exception {
        ModelRuntime rt = runtime("{ \"id\": \"m\", \"schema\": {} }");

        rt.mutate("$.x", JsonNodeFactory.instance.numberNode(1));
        rt.mutate("$.x", JsonNodeFactory.instance.numberNode(2));

        assertThat(rt.history().size()).isEqualTo(2);
        assertThat(rt.history().timestamps()).hasSize(2);
    }

    @Test
    void state_at_before_any_mutation_returns_empty() throws Exception {
        ModelRuntime rt = runtime("{ \"id\": \"m\", \"schema\": {} }");
        Instant beforeMutation = Instant.now().minusSeconds(3600);

        rt.mutate("$.x", JsonNodeFactory.instance.numberNode(5));

        assertThat(rt.stateAt(beforeMutation)).isEmpty();
    }

    @Test
    void state_at_max_returns_latest_state() throws Exception {
        ModelRuntime rt = runtime("{ \"id\": \"m\", \"schema\": {} }");

        rt.mutate("$.x", JsonNodeFactory.instance.numberNode(5));
        rt.mutate("$.x", JsonNodeFactory.instance.numberNode(99));

        var latest = rt.stateAt(Instant.MAX);
        assertThat(latest).isPresent();
        assertThat(latest.get().baseDoc().path("x").asDouble()).isEqualTo(99.0);
    }

    @Test
    void state_at_returns_merged_document_with_derived_values() throws Exception {
        ModelRuntime rt = runtime("""
            { "id": "m", "schema": {},
              "derivations": [{ "path": "$.out", "expr": "val * 3" }] }
            """);

        rt.mutate("$.val", JsonNodeFactory.instance.numberNode(7));
        Instant afterMutation = rt.history().timestamps().getFirst().plusMillis(1);

        var snap = rt.stateAt(afterMutation).orElseThrow();
        // mergedDocument() should splice in derived values
        assertThat(snap.mergedDocument().path("out").asDouble()).isEqualTo(21.0);
    }

    @Test
    void restore_clears_history() throws Exception {
        ModelRuntime rt = runtime("{ \"id\": \"m\", \"schema\": {} }");
        rt.mutate("$.x", JsonNodeFactory.instance.numberNode(1));
        var snap = rt.snapshot();

        rt.mutate("$.x", JsonNodeFactory.instance.numberNode(2));
        assertThat(rt.history().size()).isEqualTo(2);

        rt.restore(snap);
        assertThat(rt.history().size()).isZero();
    }

    @Test
    void failed_mutation_does_not_add_history_entry() throws Exception {
        ModelRuntime rt = runtime("""
            { "id": "m", "schema": {},
              "constraints": [
                { "id": "c1", "expr": "v <= 100", "message": "too big", "policy": "rollback" }
              ] }
            """);

        assertThatThrownBy(() -> rt.mutate("$.v", JsonNodeFactory.instance.numberNode(999)))
                .isInstanceOf(ConstraintEvaluator.ConstraintViolationException.class);

        assertThat(rt.history().size()).isZero();
    }

    // ── Helper ────────────────────────────────────────────────────────────────

    private ModelRuntime runtime(String specJson) throws Exception {
        ModelSpec spec = MAPPER.readValue(specJson, ModelSpec.class);
        CompiledModel model = ModelSpecCompiler.compile(spec);
        ModelState state = new ModelState(model, new InMemoryBlobStore());
        return new ModelRuntime(model, state);
    }
}
