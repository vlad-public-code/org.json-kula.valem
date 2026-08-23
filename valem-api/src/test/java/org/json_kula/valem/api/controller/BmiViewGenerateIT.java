package org.json_kula.valem.api.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.json_kula.valem.core.llm.SpecGenerator;
import org.json_kula.valem.core.llm.SpecGenerator.GenerationResult;
import org.json_kula.valem.core.model.ModelSpec;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Reproduction harness: generate a Body Mass Index model WITH a viewDefinition via the real
 * LLM and dump the resulting view so we can inspect how the model bound read-only display
 * components (statTile / label / progressBar).
 *
 * Skipped automatically unless an LLM is configured (valem.llm.provider/model/api-key).
 */
@SpringBootTest
class BmiViewGenerateIT {

    private static final Logger log = LoggerFactory.getLogger(BmiViewGenerateIT.class);

    private static final String MODEL_ID = "body-mass-index";
    private static final String DOMAIN_DESCRIPTION = "Body Mass Index";

    @Autowired(required = false)
    SpecGenerator specGenerator;

    @Autowired
    ObjectMapper mapper;

    @Test
    void generate_bmi_model_with_view() throws Exception {
        Assumptions.assumeTrue(specGenerator != null,
                "Skipping: no LLM configured — set valem.llm.provider/model/api-key (e.g. -Dvalem.llm.provider=openrouter -Dvalem.llm.model=nvidia/nemotron-3-super-120b-a12b:free -Dvalem.llm.api-key=...)");

        log.info("Generating BMI model spec (with viewDefinition) via LLM...");
        GenerationResult result = specGenerator.generate(MODEL_ID, DOMAIN_DESCRIPTION, true);

        if (result instanceof GenerationResult.Failure failure) {
            log.error("Generation FAILED after {} attempt(s). Last errors: {}",
                    failure.attemptsUsed(), failure.lastErrors());
            log.error("Last raw LLM response:\n{}", failure.lastRawResponse());
        }

        assertThat(result).isInstanceOf(GenerationResult.Success.class);
        GenerationResult.Success success = (GenerationResult.Success) result;
        ModelSpec spec = success.spec();

        String specJson = mapper.writerWithDefaultPrettyPrinter().writeValueAsString(spec);
        log.info("===== FULL GENERATED SPEC =====\n{}", specJson);

        JsonNode view = spec.viewDefinition();
        log.info("===== VIEW DEFINITION =====\n{}",
                mapper.writerWithDefaultPrettyPrinter().writeValueAsString(view));

        assertThat(view).as("view must be generated").isNotNull();
    }
}
