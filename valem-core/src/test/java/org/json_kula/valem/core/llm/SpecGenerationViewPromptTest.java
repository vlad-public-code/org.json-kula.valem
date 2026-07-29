package org.json_kula.valem.core.llm;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pins the {@code viewDefinition} guidance in {@link SpecGenerationPrompt#SYSTEM_CONTEXT_VIEW}
 * that stops the LLM producing the two binding defects observed with a real model:
 *
 * <ol>
 *   <li>plain-text fields ({@code label}/{@code placeholder}/{@code helperText}) wrapped in
 *       escaped quotes, which the evaluator passes through verbatim so the literal quote
 *       characters reach the user; and</li>
 *   <li>a display value put in a bare {@code text}/{@code value} expression with no {@code $},
 *       which {@code ViewEvaluator.resolveText}/{@code resolveNode} leave unevaluated (shown as
 *       the literal field name) — unlike the boolean dynamics, which always evaluate.</li>
 * </ol>
 *
 * These are guidance-presence checks: they fail if the value-kind teaching is edited away, which
 * is what let the defects through before.
 */
class SpecGenerationViewPromptTest {

    private final String view = SpecGenerationPrompt.SYSTEM_CONTEXT_VIEW;

    @Test
    void teaches_the_three_field_value_kinds() {
        assertThat(view).contains("FIELD VALUE KINDS");
        // the three kinds are named and distinguished
        assertThat(view).contains("PLAIN-TEXT fields");
        assertThat(view).contains("PATH (bind) fields");
        assertThat(view).contains("EXPRESSION fields");
    }

    @Test
    void warns_against_quoting_plain_text_fields() {
        // label/placeholder/helperText are called out as plain literals, not JSONata
        assertThat(view).contains("label")
                .contains("placeholder")
                .contains("helperText");
        assertThat(view)
                .as("must show the over-quoting anti-pattern so the LLM stops emitting it")
                .contains("renders with the literal quote characters");
    }

    @Test
    void teaches_the_dollar_gate_for_expression_fields() {
        // the crux: a text/value expression is only evaluated when it contains a '$'
        assertThat(view).contains("only evaluated when the string");
        assertThat(view).contains("$string(");
        // and that the boolean dynamics do NOT need a '$'
        assertThat(view).contains("always evaluated");
    }

    @Test
    void steers_display_values_to_bind() {
        assertThat(view)
                .as("the primary mechanism for showing a stored/derived value must be bind")
                .contains("prefer \"bind\": \"$.path\"");
        // statTile/progressBar/gauge value sources are spelled out
        assertThat(view).contains("statTile").contains("progressBar").contains("gauge");
    }

    @Test
    void ships_a_golden_view_exemplar_the_model_can_copy() {
        assertThat(view).contains("GOLDEN EXAMPLE");
        // the exemplar demonstrates the three fixes: plain label, bind for the value, $-badge text
        assertThat(view).contains("\"label\": \"Weight (kg)\"");
        assertThat(view).contains("\"bind\": \"$.bmi\"");
        assertThat(view).contains("$string(bmiCategory)");
    }
}
