package org.json_kula.valem.core.graph;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.json_kula.valem.core.model.ModelSpec;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * {@code validate()} promises to never throw — the generation loop relies on it to turn a bad LLM
 * expression into a repairable error instead of aborting the run. A malformed higher-order call
 * ({@code $filter(items)} with the function argument missing) makes the JSONata compiler throw an
 * unchecked {@code IndexOutOfBoundsException} from its codegen, which used to escape {@code validate}
 * and crash generation (found by {@code GenerationEvalIT}).
 */
class ModelSpecValidatorCompilerCrashTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void a_malformed_higher_order_call_is_a_validation_error_not_a_crash() throws Exception {
        ModelSpec spec = MAPPER.readValue("""
                { "id": "m",
                  "schema": { "properties": { "items": { "type": "array",
                    "items": { "properties": { "v": { "type": "number" } } } } } },
                  "derivations": [ { "path": "$.mapped", "expr": "$filter(items)" } ] }
                """, ModelSpec.class);

        ModelSpecValidator.ValidationResult[] holder = new ModelSpecValidator.ValidationResult[1];
        assertThatCode(() -> holder[0] = ModelSpecValidator.validate(spec))
                .as("validate() must not propagate a compiler crash")
                .doesNotThrowAnyException();

        assertThat(holder[0].isValid()).isFalse();
        assertThat(holder[0].errors())
                .anyMatch(e -> e.message().contains("could not translate")
                        || e.message().contains("Invalid JSONata expression"));
    }
}
