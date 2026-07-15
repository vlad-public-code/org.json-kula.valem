package org.json_kula.valem.core.engine;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.json_kula.valem.core.model.ModelSpec;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class TestCaseRunnerTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    // ── Passing tests ─────────────────────────────────────────────────────────

    @Test
    void passes_when_derivation_produces_expected_value() throws Exception {
        var results = run("""
                {
                  "id": "m", "schema": {},
                  "derivations": [
                    { "path": "$.order.total", "expr": "order.subtotal + order.tax" }
                  ],
                  "tests": [
                    {
                      "description": "basic total",
                      "given":  { "$.order.subtotal": 80, "$.order.tax": 20 },
                      "expect": { "$.order.total": 100 }
                    }
                  ]
                }
                """);

        assertThat(results).hasSize(1);
        assertThat(results.getFirst().passed()).isTrue();
        assertThat(results.getFirst().failures()).isEmpty();
    }

    @Test
    void passes_when_base_field_matches() throws Exception {
        var results = run("""
                {
                  "id": "m", "schema": {},
                  "tests": [
                    {
                      "description": "base field check",
                      "given":  { "$.x.val": 42 },
                      "expect": { "$.x.val": 42 }
                    }
                  ]
                }
                """);

        assertThat(results.getFirst().passed()).isTrue();
    }

    @Test
    void numeric_comparison_ignores_int_vs_double_type() throws Exception {
        var results = run("""
                {
                  "id": "m", "schema": {},
                  "derivations": [
                    { "path": "$.order.total", "expr": "order.subtotal + order.tax" }
                  ],
                  "tests": [
                    {
                      "description": "int vs double",
                      "given":  { "$.order.subtotal": 90, "$.order.tax": 10 },
                      "expect": { "$.order.total": 100.0 }
                    }
                  ]
                }
                """);

        assertThat(results.getFirst().passed()).isTrue();
    }

    @Test
    void empty_tests_list_returns_empty_results() throws Exception {
        var results = run("""
                { "id": "m", "schema": {} }
                """);

        assertThat(results).isEmpty();
    }

    // ── Failing tests ─────────────────────────────────────────────────────────

    @Test
    void fails_when_expression_produces_wrong_value() throws Exception {
        var results = run("""
                {
                  "id": "m", "schema": {},
                  "derivations": [
                    { "path": "$.order.total", "expr": "order.subtotal - order.tax" }
                  ],
                  "tests": [
                    {
                      "description": "wrong expr",
                      "given":  { "$.order.subtotal": 80, "$.order.tax": 20 },
                      "expect": { "$.order.total": 100 }
                    }
                  ]
                }
                """);

        assertThat(results.getFirst().passed()).isFalse();
        assertThat(results.getFirst().description()).isEqualTo("wrong expr");

        TestCaseRunner.FieldFailure f = results.getFirst().failures().getFirst();
        assertThat(f.path()).isEqualTo("$.order.total");
        assertThat(f.expected().asDouble()).isEqualTo(100.0);
        assertThat(f.actual().asDouble()).isEqualTo(60.0);
        assertThat(f.message()).contains("$.order.total").contains("expected").contains("100");
    }

    @Test
    void multiple_field_failures_are_all_reported() throws Exception {
        var results = run("""
                {
                  "id": "m", "schema": {},
                  "tests": [
                    {
                      "description": "multi-field",
                      "given":  { "$.a": 1, "$.b": 2 },
                      "expect": { "$.a": 99, "$.b": 99 }
                    }
                  ]
                }
                """);

        assertThat(results.getFirst().passed()).isFalse();
        assertThat(results.getFirst().failures()).hasSize(2);
    }

    @Test
    void multiple_tests_are_each_independently_evaluated() throws Exception {
        var results = run("""
                {
                  "id": "m", "schema": {},
                  "derivations": [
                    { "path": "$.v.double", "expr": "v.n * 2" }
                  ],
                  "tests": [
                    {
                      "description": "pass",
                      "given":  { "$.v.n": 5 },
                      "expect": { "$.v.double": 10 }
                    },
                    {
                      "description": "fail",
                      "given":  { "$.v.n": 5 },
                      "expect": { "$.v.double": 999 }
                    }
                  ]
                }
                """);

        assertThat(results).hasSize(2);
        assertThat(results.get(0).passed()).isTrue();
        assertThat(results.get(1).passed()).isFalse();
    }

    @Test
    void constraint_violation_in_given_is_reported_as_failure() throws Exception {
        var results = run("""
                {
                  "id": "m", "schema": {},
                  "constraints": [
                    { "id": "pos", "expr": "x.val > 0", "message": "must be positive", "policy": "rollback" }
                  ],
                  "tests": [
                    {
                      "description": "negative val",
                      "given":  { "$.x.val": -5 },
                      "expect": { "$.x.val": -5 }
                    }
                  ]
                }
                """);

        assertThat(results.getFirst().passed()).isFalse();
        assertThat(results.getFirst().failures().getFirst().path()).isEqualTo("given");
        assertThat(results.getFirst().failures().getFirst().message()).contains("constraint");
    }

    // ── $meta assertions ──────────────────────────────────────────────────────

    @Test
    void meta_assertion_checks_effective_schema_property() throws Exception {
        var results = run("""
                {
                  "id": "m", "schema": {
                    "properties": { "x": { "properties": { "val": { "minimum": 0 } } } }
                  },
                  "tests": [
                    {
                      "description": "schema minimum",
                      "given":  {},
                      "expect": { "$.x.val": { "$meta": { "minimum": 0 } } }
                    }
                  ]
                }
                """);

        assertThat(results.getFirst().passed()).isTrue();
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private List<TestCaseRunner.TestResult> run(String specJson) throws Exception {
        ModelSpec spec = MAPPER.readValue(specJson, ModelSpec.class);
        return TestCaseRunner.run(spec, spec.tests());
    }
}
