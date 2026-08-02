package org.json_kula.valem.core.engine;

import com.fasterxml.jackson.databind.JsonNode;
import org.json_kula.valem.core.model.ModelSpec;
import org.json_kula.valem.core.model.TestCase;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Runs a model's embedded self-tests and projects the outcome into a {@link VerificationReport} for the
 * trust-layer badge (docs/sandbox/trust-layer.md). A read-only, pure function of the spec — the
 * verification counterpart of {@code GraphProjection.project}.
 *
 * <p>The heavy lifting is reused: {@link TestCaseRunner} already compiles the spec into a throw-away
 * runtime, applies {@code defaultValues} + {@code given}, and compares against {@code expect}; this
 * class only classifies each case (via {@link VerificationClassifier}) and tallies the honest counts.
 * The badge's "passed" sense matches generation's gate: a verifiable case passes when it has no
 * <em>verifiable</em> failures, so an un-verifiable assertion never turns the badge amber.
 */
public final class SpecVerifier {

    private SpecVerifier() {}

    /**
     * Runs the spec's embedded tests against a fresh runtime (reusing {@code cache} so already-compiled
     * expressions are not re-javac'd) and builds the report.
     */
    public static VerificationReport verify(ModelSpec spec, ExpressionCache cache) {
        List<TestCaseRunner.TestResult> results = TestCaseRunner.run(spec, spec.tests(), cache);
        return report(spec, results);
    }

    /** As {@link #verify(ModelSpec, ExpressionCache)} but with a private cache. */
    public static VerificationReport verify(ModelSpec spec) {
        return verify(spec, new ExpressionCache());
    }

    /**
     * Builds the report from an already-computed result list (one per {@code spec.tests()} element, in
     * order — the shape {@link TestCaseRunner#run} returns). Lets the generation loop reuse the results
     * it already ran rather than re-running the tests.
     */
    public static VerificationReport report(ModelSpec spec, List<TestCaseRunner.TestResult> results) {
        Map<String, String> exprByPath = VerificationClassifier.exprByPath(spec);
        List<TestCase> tests = spec.tests();

        List<VerificationReport.CaseReport> cases = new ArrayList<>(tests.size());
        int checked = 0, passed = 0, unverifiable = 0;

        for (int i = 0; i < tests.size(); i++) {
            TestCase test = tests.get(i);
            // Defensive: results is one-per-test in order, but never index past the end.
            TestCaseRunner.TestResult result = i < results.size() ? results.get(i) : null;

            boolean verifiable = VerificationClassifier.isVerifiableCase(test, exprByPath);

            List<TestCaseRunner.FieldFailure> verifiableFailures = result == null ? List.of()
                    : result.failures().stream()
                        .filter(f -> VerificationClassifier.isVerifiableFailure(f, exprByPath))
                        .toList();

            Boolean casePassed = verifiable ? verifiableFailures.isEmpty() : null;

            Map<String, JsonNode> actual = new LinkedHashMap<>();
            for (TestCaseRunner.FieldFailure f : verifiableFailures) {
                actual.put(f.path(), f.actual());
            }

            String reason = verifiable ? null
                    : "no auto-checkable assertion (whole array/object result or a time-dependent field)";

            cases.add(new VerificationReport.CaseReport(
                    test.description(), verifiable, casePassed,
                    test.given(), test.expect(), Map.copyOf(actual), reason));

            if (verifiable) {
                checked++;
                if (verifiableFailures.isEmpty()) passed++;
            } else {
                unverifiable++;
            }
        }

        VerificationReport.State state =
                checked == 0            ? VerificationReport.State.NEUTRAL
                : passed == checked     ? VerificationReport.State.GREEN
                                        : VerificationReport.State.AMBER;

        return new VerificationReport(
                spec.id(), spec.version(), state, checked, passed, unverifiable, List.copyOf(cases));
    }
}
