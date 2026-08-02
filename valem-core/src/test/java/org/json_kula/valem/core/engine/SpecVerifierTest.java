package org.json_kula.valem.core.engine;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.json_kula.valem.core.model.ModelSpec;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link SpecVerifier} projects the self-test run into the trust-layer report. Focus: the honest
 * counts and the green / amber / neutral state, including the exclusion of un-verifiable cases.
 */
class SpecVerifierTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private VerificationReport verify(String specJson) throws Exception {
        return SpecVerifier.verify(MAPPER.readValue(specJson, ModelSpec.class));
    }

    @Test
    void green_when_every_verifiable_case_passes() throws Exception {
        var report = verify("""
                {
                  "id": "m", "version": "3", "schema": {},
                  "derivations": [ { "path": "$.total", "expr": "order.subtotal + order.tax" } ],
                  "tests": [
                    { "description": "80+20", "given": { "$.order.subtotal": 80, "$.order.tax": 20 },
                      "expect": { "$.total": 100 } },
                    { "description": "10+5", "given": { "$.order.subtotal": 10, "$.order.tax": 5 },
                      "expect": { "$.total": 15 } }
                  ]
                }
                """);

        assertThat(report.state()).isEqualTo(VerificationReport.State.GREEN);
        assertThat(report.modelId()).isEqualTo("m");
        assertThat(report.specVersion()).isEqualTo("3");
        assertThat(report.checkedCount()).isEqualTo(2);
        assertThat(report.passedCount()).isEqualTo(2);
        assertThat(report.unverifiableCount()).isZero();
        assertThat(report.cases()).allMatch(c -> c.verifiable() && Boolean.TRUE.equals(c.passed()));
    }

    @Test
    void amber_when_a_verifiable_case_fails_and_actual_is_reported() throws Exception {
        var report = verify("""
                {
                  "id": "m", "version": "1", "schema": {},
                  "derivations": [ { "path": "$.total", "expr": "order.subtotal - order.tax" } ],
                  "tests": [
                    { "description": "should be 100", "given": { "$.order.subtotal": 80, "$.order.tax": 20 },
                      "expect": { "$.total": 100 } }
                  ]
                }
                """);

        assertThat(report.state()).isEqualTo(VerificationReport.State.AMBER);
        assertThat(report.checkedCount()).isEqualTo(1);
        assertThat(report.passedCount()).isZero();
        var c = report.cases().getFirst();
        assertThat(c.verifiable()).isTrue();
        assertThat(c.passed()).isFalse();
        assertThat(c.actual().get("$.total").asDouble()).isEqualTo(60.0);
    }

    @Test
    void neutral_when_the_spec_has_no_tests() throws Exception {
        var report = verify("""
                { "id": "m", "version": "1", "schema": {} }
                """);
        assertThat(report.state()).isEqualTo(VerificationReport.State.NEUTRAL);
        assertThat(report.checkedCount()).isZero();
        assertThat(report.cases()).isEmpty();
    }

    @Test
    void neutral_when_all_cases_are_unverifiable() throws Exception {
        // A whole-array assertion and a $now()-dependent field: neither is auto-checkable, so the badge
        // must not claim a green pass — it reports neutral with the cases surfaced as excluded.
        var report = verify("""
                {
                  "id": "m", "version": "1", "schema": {},
                  "derivations": [
                    { "path": "$.rows", "expr": "[a, b]" },
                    { "path": "$.today", "expr": "$now()" }
                  ],
                  "tests": [
                    { "description": "array result", "given": { "$.a": 1, "$.b": 2 },
                      "expect": { "$.rows": [1, 2] } },
                    { "description": "time field", "given": {},
                      "expect": { "$.today": "2020-01-01" } }
                  ]
                }
                """);

        assertThat(report.state()).isEqualTo(VerificationReport.State.NEUTRAL);
        assertThat(report.checkedCount()).isZero();
        assertThat(report.unverifiableCount()).isEqualTo(2);
        assertThat(report.cases()).allMatch(c -> !c.verifiable() && c.passed() == null && c.reason() != null);
    }

    @Test
    void counts_split_verifiable_and_unverifiable_cases() throws Exception {
        var report = verify("""
                {
                  "id": "m", "version": "1", "schema": {},
                  "derivations": [
                    { "path": "$.total", "expr": "a + b" },
                    { "path": "$.rows", "expr": "[a, b]" }
                  ],
                  "tests": [
                    { "description": "checkable", "given": { "$.a": 3, "$.b": 4 },
                      "expect": { "$.total": 7 } },
                    { "description": "array only", "given": { "$.a": 1, "$.b": 2 },
                      "expect": { "$.rows": [1, 2] } }
                  ]
                }
                """);

        assertThat(report.state()).isEqualTo(VerificationReport.State.GREEN);
        assertThat(report.checkedCount()).isEqualTo(1);
        assertThat(report.passedCount()).isEqualTo(1);
        assertThat(report.unverifiableCount()).isEqualTo(1);
    }

    @Test
    void an_unverifiable_failing_assertion_does_not_turn_the_badge_amber() throws Exception {
        // One case with a verifiable assertion that passes AND an un-verifiable (array) assertion that
        // fails. Consistent with generation's gate, the un-verifiable failure does not count against it.
        var report = verify("""
                {
                  "id": "m", "version": "1", "schema": {},
                  "derivations": [
                    { "path": "$.total", "expr": "a + b" },
                    { "path": "$.rows", "expr": "[a, b]" }
                  ],
                  "tests": [
                    { "description": "total right, rows wrong", "given": { "$.a": 80, "$.b": 20 },
                      "expect": { "$.total": 100, "$.rows": [1, 2] } }
                  ]
                }
                """);

        assertThat(report.state()).isEqualTo(VerificationReport.State.GREEN);
        assertThat(report.checkedCount()).isEqualTo(1);
        assertThat(report.passedCount()).isEqualTo(1);
        assertThat(report.cases().getFirst().passed()).isTrue();
    }
}
