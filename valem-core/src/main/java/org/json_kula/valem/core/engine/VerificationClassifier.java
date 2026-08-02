package org.json_kula.valem.core.engine;

import com.fasterxml.jackson.databind.JsonNode;
import org.json_kula.valem.core.model.DerivationSpec;
import org.json_kula.valem.core.model.ModelSpec;
import org.json_kula.valem.core.model.TestCase;

import java.util.Map;
import java.util.stream.Collectors;

/**
 * Classifies which spec-embedded test assertions are a <em>reliable</em> verification signal — ones a
 * model can deterministically hand-compute — and which are not. This is the single source of truth for
 * the "verifiable" predicate shared by two consumers:
 *
 * <ul>
 *   <li>{@code SpecGenerator} — drops un-verifiable failures so they never gate generation or burn the
 *       retry budget (its former private {@code isVerifiableFailure}); and</li>
 *   <li>{@link SpecVerifier} — counts only verifiable cases as the honest denominator of the trust-layer
 *       badge ("built &amp; checked against N cases"), so the badge count can never diverge from what
 *       generation repaired against.</li>
 * </ul>
 *
 * <p>An assertion is <b>un-verifiable</b> when:
 * <ul>
 *   <li>its expected value is an array or object — a whole computed collection (an amortization
 *       schedule, a grouped map, a {@code {"$meta": {...}}} schema assertion) cannot be hand-computed
 *       exactly, so it always mismatches; or</li>
 *   <li>the derivation at its path depends on the current date/time ({@code $now()} / {@code $millis()})
 *       — its value changes between hand-computation and runtime, so a fixed expectation is wrong.</li>
 * </ul>
 *
 * <p>The predicate is intentionally identical to the historical generation filter: factoring it here is
 * a pure refactor, not a behaviour change.
 */
public final class VerificationClassifier {

    private VerificationClassifier() {}

    /** Builds the {@code derivation path → expression} lookup this classifier needs from a spec. */
    public static Map<String, String> exprByPath(ModelSpec spec) {
        return spec.derivations().stream()
                .collect(Collectors.toMap(DerivationSpec::path, DerivationSpec::expr, (a, b) -> a));
    }

    /**
     * Whether a single {@code expect} assertion (a {@code path → expectedValue} pair) is a reliable,
     * deterministically checkable signal. {@code exprByPath} is the spec's derivation lookup (see
     * {@link #exprByPath}).
     */
    public static boolean isVerifiableAssertion(String path, JsonNode expected,
                                                Map<String, String> exprByPath) {
        if (expected != null && (expected.isArray() || expected.isObject())) {
            return false;   // whole array/object (incl. $meta) cannot be hand-computed reliably
        }
        String expr = exprByPath.get(path);
        if (expr != null && (expr.contains("$now(") || expr.contains("$millis("))) {
            return false;   // time-dependent → non-deterministic expectation
        }
        return true;
    }

    /** Whether a {@link TestCaseRunner.FieldFailure} is a reliable signal (delegates by path + value). */
    public static boolean isVerifiableFailure(TestCaseRunner.FieldFailure f,
                                              Map<String, String> exprByPath) {
        return isVerifiableAssertion(f.path(), f.expected(), exprByPath);
    }

    /**
     * Whether a test case is auto-checkable at all: it has at least one verifiable {@code expect}
     * assertion. A case with only un-verifiable assertions (all-array/object, all {@code $now()}) is
     * excluded from the badge denominator rather than counted as a pass.
     */
    public static boolean isVerifiableCase(TestCase test, Map<String, String> exprByPath) {
        return test.expect().entrySet().stream()
                .anyMatch(e -> isVerifiableAssertion(e.getKey(), e.getValue(), exprByPath));
    }
}
