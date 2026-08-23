package org.json_kula.valem.core.engine;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.json_kula.valem.core.model.ModelSpec;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pins the JSONata facts the spec-generation prompt teaches models, so the prompt cannot quietly drift
 * away from what the engine actually does.
 *
 * <p>Every fact here matters because getting it wrong FAILS SILENTLY. A bare sibling name in a
 * wildcard derivation resolves at the document root, finds nothing, and produces an empty column with
 * no error. A JavaScript array method parses, evaluates to nothing, and makes a rollback constraint
 * report itself violated against a perfectly good state. Both are what an LLM writes by default, and
 * both are what a reviewer skims straight past — so the prompt names them, and these tests are the
 * evidence behind what it says.
 */
class PromptTaughtJsonataFactsTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** Two fill-ups, 500 km apart: row 1 burned 45 L over that distance → 9.0 L/100km. */
    private static final String GIVEN_ENTRIES =
            "[ {\"odometer\":1000,\"liters\":40}, {\"odometer\":1500,\"liters\":45} ]";

    private static TestCaseRunner.TestResult runWith(String derivations, String expect)
            throws Exception {
        String specJson = """
                {
                  "id": "fuel", "version": "1",
                  "schema": { "type": "object", "properties": { "entries": { "type": "array",
                    "items": { "type": "object", "properties": {
                      "odometer": { "type": "number" }, "liters": { "type": "number" } } } } } },
                  "defaultValues": [ { "path": "$.entries", "expr": "[]" } ],
                  "derivations": [ %s ],
                  "tests": [ { "description": "two fill-ups",
                               "given": { "$.entries": %s }, "expect": %s } ]
                }
                """.formatted(derivations, GIVEN_ENTRIES, expect);
        ModelSpec spec = MAPPER.readValue(specJson, ModelSpec.class);
        List<TestCaseRunner.TestResult> results = TestCaseRunner.run(spec, spec.tests());
        return results.getFirst();
    }

    @Test
    void bare_sibling_name_in_a_wildcard_derivation_resolves_at_the_root_and_yields_nothing()
            throws Exception {
        // The trap, stated as a test: no exception, no validation error — just an empty column.
        var result = runWith(
                "{ \"path\": \"$.entries[*].cost\", \"expr\": \"liters * 2\" }",
                "{ \"$.entries[1].cost\": 90 }");

        assertThat(result.passed())
                .as("a bare sibling name must NOT resolve to the row — if this starts passing, the "
                    + "prompt's $parent rule is obsolete and should be relaxed")
                .isFalse();
    }

    @Test
    void parent_qualified_sibling_name_reads_the_row() throws Exception {
        var result = runWith(
                "{ \"path\": \"$.entries[*].cost\", \"expr\": \"$parent.liters * 2\" }",
                "{ \"$.entries[1].cost\": 90 }");

        assertThat(result.failures()).isEmpty();
    }

    @Test
    void previous_row_is_reachable_by_value_and_chains_into_a_later_derivation() throws Exception {
        // There is no row-index binding, so "the previous fill-up" is expressed as "the largest
        // odometer reading below mine". The second derivation then reads the first one's output
        // through $parent, and row 0 — which has no predecessor — falls through to the guard.
        var result = runWith("""
                        { "path": "$.entries[*].distance",
                          "expr": "$parent.odometer - $max(entries[odometer < $parent.odometer].odometer)" },
                        { "path": "$.entries[*].consumption",
                          "expr": "$parent.distance > 0 ? $round($parent.liters * 100 / $parent.distance, 2) : 0" }
                        """,
                """
                        { "$.entries[1].distance": 500, "$.entries[1].consumption": 9.0,
                          "$.entries[0].consumption": 0 }
                        """);

        assertThat(result.failures()).isEmpty();
    }

    // ── JavaScript array methods (the silent-constraint trap) ─────────────────

    /** A rollback constraint over {@code entries}, checked against one perfectly valid row. */
    private static TestCaseRunner.TestResult constraintOverOneGoodRow(String expr) throws Exception {
        String specJson = """
                {
                  "id": "fuel", "version": "1",
                  "schema": { "type": "object", "properties": { "entries": { "type": "array",
                    "items": { "type": "object", "properties": { "liters": { "type": "number" } } } } } },
                  "constraints": [ { "id": "positive-liters", "expr": "%s",
                                     "message": "every fill-up must add fuel", "policy": "rollback" } ],
                  "tests": [ { "description": "one good row",
                               "given": { "$.entries": [ { "liters": 5 } ] },
                               "expect": { "$.entries[0].liters": 5 } } ]
                }
                """.formatted(expr.replace("\"", "\\\""));
        ModelSpec spec = MAPPER.readValue(specJson, ModelSpec.class);
        return TestCaseRunner.run(spec, spec.tests()).getFirst();
    }

    @Test
    void javascript_every_evaluates_to_nothing_and_fails_a_satisfied_constraint() throws Exception {
        // No compile error, no "unknown function" — the constraint simply never holds. This is why
        // the prompt calls .every()/.some()/.length out by name instead of trusting the model to know.
        var result = constraintOverOneGoodRow("entries.every(function($e) { $e.liters > 0 })");

        assertThat(result.passed())
                .as("if this starts passing, JSONata grew JS array methods and the prompt's warning "
                    + "is obsolete")
                .isFalse();
        assertThat(result.failures().getFirst().message()).contains("Constraint violated");
    }

    @Test
    void the_count_of_violations_form_the_prompt_teaches_holds() throws Exception {
        assertThat(constraintOverOneGoodRow("$count(entries[liters <= 0]) = 0").failures()).isEmpty();
        assertThat(constraintOverOneGoodRow("$count(entries[liters > 0]) > 0").failures()).isEmpty();
        assertThat(constraintOverOneGoodRow("$count(entries) > 0").failures()).isEmpty();
    }
}
