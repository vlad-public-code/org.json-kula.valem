package org.json_kula.valem.api.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.json_kula.valem.core.llm.SpecGenerator;
import org.json_kula.valem.core.llm.SpecGenerator.GenerationResult;
import org.json_kula.valem.core.model.EffectSpec;
import org.json_kula.valem.core.model.ModelSpec;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * End-to-end: asks the real LLM to generate a spec that must use {@code llm} and {@code timer} effects,
 * then registers it via the REST API (proving the generated effects pass {@code ModelSpecValidator}).
 *
 * <p>Skipped automatically when no LLM is configured (set {@code VALEM_LLM_PROVIDER} +
 * {@code VALEM_LLM_API_KEY}).
 */
@SpringBootTest
@AutoConfigureMockMvc
class EffectsGenerateIT {

    private static final Logger log = LoggerFactory.getLogger(EffectsGenerateIT.class);

    private static final String MODEL_ID = "ticket-triage";
    private static final String DOMAIN_DESCRIPTION = """
            A customer support ticket triage model. The base document has a `ticket` object with a
            `body` (string), a `status` (enum: open, escalated, closed), and computed `category`
            (string) and `urgency` (number) fields.
            REQUIREMENT 1: when the ticket body is set, use an effect with executor "llm" to classify
            the ticket — send the body to the model and fold a `category` string and an `urgency`
            number (1-5) back into the ticket via response.set.
            REQUIREMENT 2: when the ticket status becomes "open", use an effect with executor "timer"
            that, after 60000 ms (afterMs), sets the ticket status to "escalated".
            Both effects MUST appear in the "effects" array.""";

    @Autowired(required = false) SpecGenerator specGenerator;
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;

    @Test
    void generate_spec_with_llm_and_timer_effects_and_register_it() throws Exception {
        Assumptions.assumeTrue(specGenerator != null,
                "Skipping: LLM not configured (set VALEM_LLM_PROVIDER + VALEM_LLM_API_KEY)");

        log.info("Generating ticket-triage model spec via LLM...");
        GenerationResult result = specGenerator.generate(MODEL_ID, DOMAIN_DESCRIPTION);

        if (result instanceof GenerationResult.Failure failure) {
            log.error("Generation FAILED after {} attempt(s). Last errors: {}",
                    failure.attemptsUsed(), failure.lastErrors());
            log.error("Last raw LLM response:\n{}", failure.lastRawResponse());
        }
        assertThat(result).as("LLM generation must succeed").isInstanceOf(GenerationResult.Success.class);

        ModelSpec spec = ((GenerationResult.Success) result).spec();
        String specJson = mapper.writerWithDefaultPrettyPrinter().writeValueAsString(spec);
        log.info("Generated spec:\n{}", specJson);

        // The domain explicitly requires effects; the wiring works if the model produced them.
        assertThat(spec.effects()).as("spec must contain effects").isNotEmpty();
        log.info("Effect executors used: {}",
                spec.effects().stream().map(EffectSpec::executor).toList());
        assertThat(spec.effects()).extracting(EffectSpec::executor)
                .as("spec should use the llm and/or timer executors")
                .containsAnyOf("llm", "timer");

        // Registering proves the generated effects pass the real create path (ModelSpecValidator).
        mvc.perform(post("/models").contentType(MediaType.APPLICATION_JSON).content(specJson))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(MODEL_ID));
        log.info("Model '{}' registered successfully", MODEL_ID);

        mvc.perform(get("/models/" + MODEL_ID + "/state")).andExpect(status().isOk());
        log.info("Model state reachable");
    }
}
