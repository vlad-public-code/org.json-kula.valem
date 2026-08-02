package org.json_kula.valem.core.engine;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.json_kula.valem.core.model.ModelSpec;
import org.json_kula.valem.core.model.TestCase;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The shared "is this assertion a reliable auto-check?" predicate. Its behaviour must stay identical to
 * the historical {@code SpecGenerator.isVerifiableFailure} it was factored out of, so the trust badge's
 * denominator equals what generation repaired against.
 */
class VerificationClassifierTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private JsonNode json(String s) throws Exception { return MAPPER.readTree(s); }

    @Test
    void scalar_expected_value_is_verifiable() throws Exception {
        assertThat(VerificationClassifier.isVerifiableAssertion("$.total", json("100"), Map.of())).isTrue();
        assertThat(VerificationClassifier.isVerifiableAssertion("$.name", json("\"x\""), Map.of())).isTrue();
        assertThat(VerificationClassifier.isVerifiableAssertion("$.ok", json("true"), Map.of())).isTrue();
    }

    @Test
    void whole_array_or_object_expected_value_is_not_verifiable() throws Exception {
        assertThat(VerificationClassifier.isVerifiableAssertion("$.rows", json("[1,2,3]"), Map.of())).isFalse();
        assertThat(VerificationClassifier.isVerifiableAssertion("$.obj", json("{\"a\":1}"), Map.of())).isFalse();
        // A $meta assertion is an object, so it is (by this predicate) un-verifiable — parity with the
        // generation filter, which never gated on $meta failures.
        assertThat(VerificationClassifier.isVerifiableAssertion(
                "$.x", json("{\"$meta\":{\"minimum\":0}}"), Map.of())).isFalse();
    }

    @Test
    void time_dependent_derivation_is_not_verifiable() throws Exception {
        Map<String, String> exprByPath = Map.of(
                "$.age", "$now()",
                "$.stamp", "$millis()",
                "$.total", "a + b");
        assertThat(VerificationClassifier.isVerifiableAssertion("$.age", json("5"), exprByPath)).isFalse();
        assertThat(VerificationClassifier.isVerifiableAssertion("$.stamp", json("5"), exprByPath)).isFalse();
        assertThat(VerificationClassifier.isVerifiableAssertion("$.total", json("5"), exprByPath)).isTrue();
    }

    @Test
    void exprByPath_maps_each_derivation_path_to_its_expression() throws Exception {
        ModelSpec spec = MAPPER.readValue("""
                { "id": "m", "schema": {},
                  "derivations": [ { "path": "$.total", "expr": "a + b" } ] }
                """, ModelSpec.class);
        assertThat(VerificationClassifier.exprByPath(spec)).containsEntry("$.total", "a + b");
    }

    @Test
    void case_is_verifiable_when_any_assertion_is_verifiable() throws Exception {
        TestCase mixed = MAPPER.readValue("""
                { "description": "mix", "given": {},
                  "expect": { "$.rows": [1,2], "$.total": 3 } }
                """, TestCase.class);
        assertThat(VerificationClassifier.isVerifiableCase(mixed, Map.of())).isTrue();

        TestCase allArray = MAPPER.readValue("""
                { "description": "arr only", "given": {}, "expect": { "$.rows": [1,2] } }
                """, TestCase.class);
        assertThat(VerificationClassifier.isVerifiableCase(allArray, Map.of())).isFalse();
    }

    @Test
    void field_failure_classification_delegates_to_the_assertion_predicate() throws Exception {
        var scalarFailure = new TestCaseRunner.FieldFailure("$.total", json("100"), json("60"), "msg");
        var arrayFailure  = new TestCaseRunner.FieldFailure("$.rows", json("[1,2]"), json("[1]"), "msg");
        assertThat(VerificationClassifier.isVerifiableFailure(scalarFailure, Map.of())).isTrue();
        assertThat(VerificationClassifier.isVerifiableFailure(arrayFailure, Map.of())).isFalse();
    }
}
