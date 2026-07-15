package org.json_kula.valem.core.engine;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import org.json_kula.valem.core.blob.InMemoryBlobStore;
import org.json_kula.valem.core.graph.CompiledModel;
import org.json_kula.valem.core.graph.ModelSpecCompiler;
import org.json_kula.valem.core.model.ModelSpec;
import org.json_kula.valem.core.state.DirtyPropagator;
import org.json_kula.valem.core.state.ModelState;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ConstraintEvaluatorTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private ExpressionCache cache;
    private ConstraintEvaluator evaluator;

    @BeforeEach
    void setUp() {
        cache     = new ExpressionCache();
        evaluator = new ConstraintEvaluator(cache);
    }

    // ── Global constraint (no path) ────────────────────────────────────────────

    @Test
    void passing_global_constraint_returns_no_violations() throws Exception {
        CompiledModel model = compile("""
            {
              "id": "m", "schema": {},
              "constraints": [
                { "id": "credit-check",
                  "expr": "order.total <= customer.creditLimit",
                  "message": "Over limit", "policy": "rollback" }
              ]
            }
            """);
        ModelState state = new ModelState(model, new InMemoryBlobStore());
        state.setValue("$.order.total",          JsonNodeFactory.instance.numberNode(500.0));
        state.setValue("$.customer.creditLimit", JsonNodeFactory.instance.numberNode(1000.0));

        Set<String> dirty = allConstraintsDirty(model, state);
        List<ConstraintEvaluator.Violation> result = evaluator.evaluate(model, state, dirty, null);
        assertThat(result).isEmpty();
    }

    @Test
    void failing_rollback_constraint_throws() throws Exception {
        CompiledModel model = compile("""
            {
              "id": "m", "schema": {},
              "constraints": [
                { "id": "credit-check",
                  "expr": "order.total <= customer.creditLimit",
                  "message": "Over credit limit", "policy": "rollback" }
              ]
            }
            """);
        ModelState state = new ModelState(model, new InMemoryBlobStore());
        state.setValue("$.order.total",          JsonNodeFactory.instance.numberNode(2000.0));
        state.setValue("$.customer.creditLimit", JsonNodeFactory.instance.numberNode(1000.0));

        Set<String> dirty = allConstraintsDirty(model, state);

        assertThatThrownBy(() -> evaluator.evaluate(model, state, dirty, null))
                .isInstanceOf(ConstraintEvaluator.ConstraintViolationException.class)
                .satisfies(ex -> {
                    var cve = (ConstraintEvaluator.ConstraintViolationException) ex;
                    assertThat(cve.violations()).hasSize(1);
                    assertThat(cve.violations().getFirst().constraintId()).isEqualTo("credit-check");
                    assertThat(cve.violations().getFirst().message()).isEqualTo("Over credit limit");
                });
    }

    @Test
    void failing_flag_constraint_returns_violation_without_throwing() throws Exception {
        CompiledModel model = compile("""
            {
              "id": "m", "schema": {},
              "constraints": [
                { "id": "preferred-limit",
                  "expr": "order.total <= customer.preferredLimit",
                  "message": "Over preferred limit", "policy": "flag" }
              ]
            }
            """);
        ModelState state = new ModelState(model, new InMemoryBlobStore());
        state.setValue("$.order.total",             JsonNodeFactory.instance.numberNode(600.0));
        state.setValue("$.customer.preferredLimit", JsonNodeFactory.instance.numberNode(500.0));

        Set<String> dirty = allConstraintsDirty(model, state);
        List<ConstraintEvaluator.Violation> result = evaluator.evaluate(model, state, dirty, null);
        assertThat(result).hasSize(1);
        assertThat(result.getFirst().constraintId()).isEqualTo("preferred-limit");
    }

    // ── Scalar path constraint ─────────────────────────────────────────────────

    @Test
    void scalar_path_constraint_passes_when_field_satisfies() throws Exception {
        CompiledModel model = compile("""
            {
              "id": "m", "schema": {},
              "constraints": [
                { "id": "positive-amount",
                  "expr": "$ > 0",
                  "message": "Must be positive", "policy": "rollback",
                  "path": "$.loan.amount" }
              ]
            }
            """);
        ModelState state = new ModelState(model, new InMemoryBlobStore());
        state.setValue("$.loan.amount", JsonNodeFactory.instance.numberNode(100.0));

        Set<String> dirty = allConstraintsDirty(model, state);
        assertThat(evaluator.evaluate(model, state, dirty, null)).isEmpty();
    }

    @Test
    void scalar_path_constraint_fails_when_field_violates() throws Exception {
        CompiledModel model = compile("""
            {
              "id": "m", "schema": {},
              "constraints": [
                { "id": "positive-amount",
                  "expr": "$ > 0",
                  "message": "Must be positive", "policy": "rollback",
                  "path": "$.loan.amount" }
              ]
            }
            """);
        ModelState state = new ModelState(model, new InMemoryBlobStore());
        state.setValue("$.loan.amount", JsonNodeFactory.instance.numberNode(-5.0));

        Set<String> dirty = allConstraintsDirty(model, state);
        assertThatThrownBy(() -> evaluator.evaluate(model, state, dirty, null))
                .isInstanceOf(ConstraintEvaluator.ConstraintViolationException.class);
    }

    // ── Trace recording ────────────────────────────────────────────────────────

    @Test
    void traces_recorded_for_each_evaluated_constraint() throws Exception {
        CompiledModel model = compile("""
            {
              "id": "m", "schema": {},
              "constraints": [
                { "id": "c1", "expr": "a.val > 0",  "message": "pos", "policy": "flag" },
                { "id": "c2", "expr": "a.val < 100", "message": "lt",  "policy": "flag" }
              ]
            }
            """);
        ModelState state = new ModelState(model, new InMemoryBlobStore());
        state.setValue("$.a.val", JsonNodeFactory.instance.numberNode(50.0));

        List<DerivationTrace> traces = new ArrayList<>();
        Set<String> dirty = allConstraintsDirty(model, state);
        evaluator.evaluate(model, state, dirty, traces);

        assertThat(traces).hasSize(2);
        assertThat(traces.stream().map(DerivationTrace::targetPath).toList())
                .containsExactlyInAnyOrder("$constraint:c1", "$constraint:c2");
        assertThat(traces.stream().allMatch(t -> t.constraintPassed())).isTrue();
    }

    // ── Helpers ────────────────────────────────────────────────────────────────

    private CompiledModel compile(String json) throws Exception {
        return ModelSpecCompiler.compile(MAPPER.readValue(json, ModelSpec.class));
    }

    /** Marks all constraint synthetic nodes dirty so they are evaluated. */
    private Set<String> allConstraintsDirty(CompiledModel model, ModelState state) {
        Set<String> dirty = new HashSet<>();
        for (String node : model.graph().nodes()) {
            if (node.startsWith("$constraint:")) dirty.add(node);
        }
        return dirty;
    }

    private ConstraintEvaluator.Violation violation(String id, String msg) {
        return new ConstraintEvaluator.Violation(id, msg, null);
    }
}
