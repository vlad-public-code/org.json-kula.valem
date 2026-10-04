package org.json_kula.valem.core.graph;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.json_kula.valem.core.model.ModelSpec;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * {@code validate()} promises to never throw — the generation loop relies on it to turn a bad LLM
 * expression into a repairable error instead of aborting the run.
 *
 * <p>The original motivation was a malformed higher-order call ({@code $filter(items)} with the
 * function argument missing), which made {@code jsonata-jvm-compiler} ≤ 1.0.5 throw an unchecked
 * {@code IndexOutOfBoundsException} out of its codegen; that escaped {@code validate} and crashed
 * generation (found by {@code GenerationEvalIT}). <strong>1.0.7 fixed that upstream</strong>: the
 * expression now translates cleanly and the arity/signature mismatch is reported at
 * <em>evaluation</em> time instead ({@code Argument 1 of function filter does not match function
 * signature}) — which is also what reference JSONata does, since builtin signatures are checked
 * when a function is applied, not when the expression is parsed.
 *
 * <p>So this class now pins the invariant itself rather than the old crash: whatever the compiler
 * does with a malformed call, {@code validate()} completes normally. The
 * {@code catch (RuntimeException)} arm in {@code ModelSpecValidator.validateExpr} stays as the
 * defence that makes that true for any future codegen crash. Note the consequence of the upgrade:
 * this particular shape is no longer a <em>validation</em> error, so the generation loop catches it
 * via a spec's self-test run (which evaluates) rather than via validation.
 */
class ModelSpecValidatorCompilerCrashTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final String MALFORMED_FILTER = """
            { "id": "m",
              "schema": { "properties": { "items": { "type": "array",
                "items": { "properties": { "v": { "type": "number" } } } } } },
              "derivations": [ { "path": "$.mapped", "expr": "$filter(items)" } ] }
            """;

    @Test
    void a_malformed_higher_order_call_does_not_make_validate_throw() throws Exception {
        ModelSpec spec = MAPPER.readValue(MALFORMED_FILTER, ModelSpec.class);

        ModelSpecValidator.ValidationResult[] holder = new ModelSpecValidator.ValidationResult[1];
        assertThatCode(() -> holder[0] = ModelSpecValidator.validate(spec))
                .as("validate() must not propagate a compiler crash")
                .doesNotThrowAnyException();

        assertThat(holder[0]).isNotNull();
    }

    @Test
    void an_expression_that_fails_to_translate_is_reported_as_a_validation_error() throws Exception {
        // The reporting path that the crash arm feeds into, exercised through an expression that is
        // genuinely untranslatable (unbalanced parentheses) rather than merely wrong at runtime —
        // so this stays meaningful independently of which errors the compiler defers to evaluation.
        ModelSpec spec = MAPPER.readValue("""
                { "id": "m",
                  "schema": { "properties": { "items": { "type": "array",
                    "items": { "properties": { "v": { "type": "number" } } } } } },
                  "derivations": [ { "path": "$.mapped", "expr": "$sum(items.v" } ] }
                """, ModelSpec.class);

        ModelSpecValidator.ValidationResult result = ModelSpecValidator.validate(spec);

        assertThat(result.isValid()).isFalse();
        assertThat(result.errors())
                .anyMatch(e -> e.message().contains("could not translate")
                        || e.message().contains("Invalid JSONata expression"));
    }
}
