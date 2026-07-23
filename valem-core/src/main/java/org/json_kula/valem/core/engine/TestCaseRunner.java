package org.json_kula.valem.core.engine;

import com.fasterxml.jackson.databind.JsonNode;
import org.json_kula.valem.core.blob.InMemoryBlobStore;
import org.json_kula.valem.core.graph.CompiledModel;
import org.json_kula.valem.core.graph.ModelSpecCompiler;
import org.json_kula.valem.core.model.ModelSpec;
import org.json_kula.valem.core.model.TestCase;
import org.json_kula.valem.core.state.ModelState;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Executes the spec-embedded test cases in a throw-away {@link ModelRuntime}.
 *
 * <p>Each {@link TestCase} describes a set of mutations ({@code given}) and the
 * field values or schema properties expected after those mutations ({@code expect}).
 * Value assertions compare {@link ModelRuntime#getValue} against the expected node.
 * Schema assertions ({@code {"$meta": {...}}}) compare {@link ModelRuntime#effectiveSchema}
 * properties against the expected values.
 */
public final class TestCaseRunner {

    private TestCaseRunner() {}

    /** A field-level assertion that did not hold. */
    public record FieldFailure(String path, JsonNode expected, JsonNode actual, String message) {}

    /** The outcome of a single {@link TestCase}. */
    public record TestResult(String description, boolean passed, List<FieldFailure> failures) {
        public boolean failed() { return !passed; }
    }

    /**
     * Compiles {@code spec} and runs every test case against a fresh runtime.
     * Compilation failures are reported as a single failure per test case.
     *
     * @return one {@link TestResult} per element of {@code tests}; empty list if {@code tests} is empty
     */
    public static List<TestResult> run(ModelSpec spec, List<TestCase> tests) {
        if (tests.isEmpty()) return List.of();

        CompiledModel model;
        try {
            model = ModelSpecCompiler.compile(spec);
        } catch (Exception e) {
            String msg = "Spec compilation failed: " + e.getMessage();
            return tests.stream()
                    .map(t -> new TestResult(t.description(), false,
                            List.of(new FieldFailure("spec", null, null, msg))))
                    .toList();
        }

        List<TestResult> results = new ArrayList<>(tests.size());
        for (TestCase test : tests) {
            results.add(runOne(model, test));
        }
        return List.copyOf(results);
    }

    // ── Internals ─────────────────────────────────────────────────────────────

    private static TestResult runOne(CompiledModel model, TestCase test) {
        ModelRuntime rt = new ModelRuntime(model, new ModelState(model, new InMemoryBlobStore()));

        // Apply defaultValues before the given mutations, exactly as real model creation does
        // (ModelService.createModel runs initialize() right after constructing the runtime).
        // Without this, a spec whose derivations read a defaulted field — e.g. a premium computed
        // against a regionMultiplier seeded to 1.0 until a live rate is fetched — evaluates that
        // field as absent and every dependent derivation comes back null, so the spec fails its
        // own self-tests for a reason that never occurs in production.
        try {
            rt.initialize();
        } catch (Exception e) {
            return failure(test, "initialize", null, null,
                    "Applying defaultValues failed: " + e.getMessage());
        }

        if (test.given() != null && !test.given().isEmpty()) {
            try {
                rt.mutate(test.given());
            } catch (ConstraintEvaluator.ConstraintViolationException cve) {
                return failure(test, "given", null, null,
                        "Mutation rejected by constraint: " + cve.getMessage());
            } catch (Exception e) {
                return failure(test, "given", null, null, "Mutation failed: " + e.getMessage());
            }
        }

        List<FieldFailure> failures = new ArrayList<>();
        for (Map.Entry<String, JsonNode> entry : test.expect().entrySet()) {
            String path     = entry.getKey();
            JsonNode expected = entry.getValue();

            if (TestCase.isMetaAssertion(expected)) {
                checkMeta(rt, path, expected.get("$meta"), failures);
            } else {
                JsonNode actual = rt.getValue(path);
                if (!valuesEqual(expected, actual)) {
                    failures.add(new FieldFailure(path, expected, actual,
                            path + " expected " + expected + " but was " + actual));
                }
            }
        }

        return new TestResult(test.description(), failures.isEmpty(), List.copyOf(failures));
    }

    private static void checkMeta(ModelRuntime rt, String path, JsonNode metaExpected,
                                   List<FieldFailure> failures) {
        com.fasterxml.jackson.databind.node.ObjectNode schema = rt.effectiveSchema(path);
        metaExpected.fields().forEachRemaining(e -> {
            String key       = e.getKey();
            JsonNode expected = e.getValue();
            JsonNode actual   = schema != null ? schema.get(key) : null;
            if (!valuesEqual(expected, actual)) {
                failures.add(new FieldFailure(
                        path + "#" + key, expected, actual,
                        path + " schema." + key + " expected " + expected + " but was " + actual));
            }
        });
    }

    private static boolean valuesEqual(JsonNode expected, JsonNode actual) {
        if (expected == null && actual == null) return true;
        if (expected == null || actual == null) return false;
        if (expected.isNull() && (actual.isNull() || actual.isMissingNode())) return true;
        if (actual.isNull()   || actual.isMissingNode()) return expected.isNull();
        if (expected.isNumber() && actual.isNumber()) {
            return Double.compare(expected.asDouble(), actual.asDouble()) == 0;
        }
        return expected.equals(actual);
    }

    private static TestResult failure(TestCase test, String path,
                                       JsonNode expected, JsonNode actual, String msg) {
        return new TestResult(test.description(), false,
                List.of(new FieldFailure(path, expected, actual, msg)));
    }
}
