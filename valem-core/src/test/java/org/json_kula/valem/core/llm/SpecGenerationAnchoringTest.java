package org.json_kula.valem.core.llm;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Two generation-quality nudges:
 *
 * <ul>
 *   <li><b>Domain anchoring</b> — a terse prompt (a bare noun phrase) gets an instruction to model
 *       the domain's standard definition, so "Body Mass Index" cannot drift into an unrelated model;
 *       a detailed prompt is left untouched.</li>
 *   <li><b>Unit consistency</b> — the always-sent system context calls out dimensional consistency
 *       and asks for a self-test that a unit slip would break (the cm-vs-m failure class).</li>
 * </ul>
 */
class SpecGenerationAnchoringTest {

    @Test
    void a_terse_domain_description_gets_an_anchoring_hint() {
        String hint = SpecGenerationPrompt.domainAnchoringHint("Body Mass Index");
        assertThat(hint).isNotEmpty();
        assertThat(hint).contains("STANDARD").contains("do not").contains("unit");
    }

    @Test
    void a_detailed_domain_description_is_left_alone() {
        String detailed = "Calculate annual heating energy for a house from floor area, wall U-value, "
                + "glazing area, indoor and outdoor temperature, and heating hours per year.";
        assertThat(SpecGenerationPrompt.domainAnchoringHint(detailed)).isEmpty();
    }

    @Test
    void the_anchoring_hint_appears_in_the_initial_prompt_for_a_terse_domain() {
        String prompt = SpecGenerationPrompt.initialPrompt("bmi", "Body Mass Index", true);
        assertThat(prompt).contains("This description is brief");
    }

    @Test
    void the_system_context_teaches_unit_consistency() {
        assertThat(SpecGenerationPrompt.SYSTEM_CONTEXT)
                .contains("Units and dimensional consistency")
                .contains("Never mix cm and m")
                .contains("22.86");
    }
}
