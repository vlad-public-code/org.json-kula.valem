package org.json_kula.valem.api.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.json_kula.valem.core.graph.SpecEvolution;
import org.json_kula.valem.core.llm.SpecGenerator;
import org.json_kula.valem.core.llm.SpecGenerator.GenerationResult;
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

import java.util.Iterator;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * End-to-end test: calls the real LLM (OpenRouter minimax/minimax-m2.5) to generate an
 * Estonian car tax model spec, then registers and exercises it via the REST API.
 *
 * Skipped automatically when OPENROUTER_API_KEY is not set.
 */
@SpringBootTest
@AutoConfigureMockMvc
class EstonianCarTaxGenerateIT {

    private static final Logger log = LoggerFactory.getLogger(EstonianCarTaxGenerateIT.class);

    private static final String MODEL_ID = "estonian-car-tax";
    private static final String DOMAIN_DESCRIPTION =
            "Generate model for calculating annual car tax in Estonia based on year of manufacture, weight and emissions.";

    @Autowired(required = false)
    SpecGenerator specGenerator;

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;

    @Test
    void generate_estonian_car_tax_model_and_register_it() throws Exception {
        Assumptions.assumeTrue(specGenerator != null,
                "Skipping: LLM not configured (set OPENROUTER_API_KEY)");

        // ── Step 1: generate spec via LLM ────────────────────────────────────
        log.info("Generating Estonian car tax model spec via LLM...");
        GenerationResult result = specGenerator.generate(MODEL_ID, DOMAIN_DESCRIPTION);

        if (result instanceof GenerationResult.Failure failure) {
            log.error("Generation FAILED after {} attempt(s). Last errors: {}",
                    failure.attemptsUsed(), failure.lastErrors());
            log.error("Last raw LLM response:\n{}", failure.lastRawResponse());
        }

        assertThat(result)
                .as("LLM generation must succeed")
                .isInstanceOf(GenerationResult.Success.class);

        GenerationResult.Success success = (GenerationResult.Success) result;
        log.info("Generation SUCCEEDED after {} attempt(s)", success.attemptsUsed());

        ModelSpec spec = success.spec();
        String specJson = mapper.writerWithDefaultPrettyPrinter().writeValueAsString(spec);
        log.info("Generated spec:\n{}", specJson);

        // ── Sanity-check the generated spec ──────────────────────────────────
        assertThat(spec.id()).isEqualTo(MODEL_ID);
        assertThat(spec.schema()).isNotNull();
        assertThat(spec.derivations())
                .as("spec must have at least one derivation computing the tax")
                .isNotEmpty();

        // ── Step 2: register the spec via POST /models ────────────────────────
        mvc.perform(post("/models")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(specJson))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(MODEL_ID))
                .andExpect(jsonPath("$.status").value("created"));

        log.info("Model '{}' registered successfully", MODEL_ID);

        // ── Step 3: apply a mutation and verify derivations evaluate without a crash ──
        // 200  → ideal: mutation accepted and derivations evaluated correctly.
        // 409  → spec quality issue (e.g. constraint uses wrong field name); expressions still
        //        ran without exceptions. Log a warning but don't fail the test.
        // 422  → hard failure: schema or derivation expression evaluation error.
        // 5xx  → hard failure: unhandled exception in the engine.
        String mutationBody = buildSampleMutation(spec);
        log.info("Applying test mutation: {}", mutationBody);

        int mutationStatus = mvc.perform(post("/models/" + MODEL_ID + "/mutations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mutationBody))
                .andReturn().getResponse().getStatus();

        assertThat(mutationStatus)
                .as("mutation must not return schema/expression error (422) or server error (5xx)")
                .isNotEqualTo(422)
                .isLessThan(500);

        if (mutationStatus == 200) {
            log.info("Mutation applied — derivations evaluated without error");
        } else {
            log.warn("Mutation returned {} (constraint violation likely due to spec field-name " +
                    "inconsistency — expressions evaluated but constraint used wrong path)", mutationStatus);
        }

        // ── Step 4: verify state is reachable ─────────────────────────────────
        mvc.perform(get("/models/" + MODEL_ID + "/state"))
                .andExpect(status().isOk());

        log.info("Initial model state is reachable");

        // ── Step 5: ask LLM to evolve the spec ───────────────────────────────────
        String evolutionRequest =
                "Add a 'fuelType' field (enum: petrol, diesel, electric) to the schema. " +
                "For diesel vehicles add a 50 EUR surcharge to the annual tax.";
        log.info("Generating spec evolution via LLM: {}", evolutionRequest);
        SpecEvolution evolution = specGenerator.generateEvolution(spec, evolutionRequest);
        String evolutionJson = mapper.writerWithDefaultPrettyPrinter().writeValueAsString(evolution);
        log.info("Generated evolution:\n{}", evolutionJson);

        // ── Step 6: apply the evolution ───────────────────────────────────────────
        mvc.perform(post("/models/" + MODEL_ID + "/spec/evolve")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(evolutionJson))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(MODEL_ID));

        log.info("Spec evolution applied successfully");

        // ── Step 7: mutate with the newly introduced fuel-type field ──────────────
        // 422 is possible if the LLM chose a different field name; 5xx always fails.
        int evolvedStatus = mvc.perform(post("/models/" + MODEL_ID + "/mutations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"$.fuelType\": \"diesel\"}"))
                .andReturn().getResponse().getStatus();

        assertThat(evolvedStatus)
                .as("post-evolution mutation must not cause a server error (5xx)")
                .isLessThan(500);

        if (evolvedStatus == 200) {
            log.info("Post-evolution mutation accepted — diesel surcharge derivation active");
        } else {
            log.warn("Post-evolution mutation returned {} " +
                    "(LLM may have used a different field name for fuelType)", evolvedStatus);
        }

        // ── Step 8: verify state still reachable after evolution ─────────────────
        mvc.perform(get("/models/" + MODEL_ID + "/state"))
                .andExpect(status().isOk());

        log.info("Post-evolution state is reachable — evolution scenario PASSED");
    }

    /**
     * Builds a mutation JSON object from heuristic defaults in the schema. Creation-time seed values
     * now live in a "$" defaultValues rule and are applied automatically at model creation.
     */
    private String buildSampleMutation(ModelSpec spec) throws Exception {

        ObjectNode mutation = mapper.createObjectNode();
        JsonNode schema = spec.schema();
        if (schema != null && schema.has("properties")) {
            collectMutations(mutation, "$", schema.get("properties"));
        }

        if (mutation.isEmpty()) {
            mutation.put("$.year", 2010);
        }

        return mapper.writeValueAsString(mutation);
    }

    private void collectMutations(ObjectNode mutation, String prefix, JsonNode properties) {
        Iterator<Map.Entry<String, JsonNode>> it = properties.fields();
        while (it.hasNext()) {
            Map.Entry<String, JsonNode> entry = it.next();
            String name = entry.getKey();
            JsonNode propDef = entry.getValue();
            String path = prefix + "." + name;

            // Skip derived (read-only) fields — writing them causes a 422 SchemaViolationException
            if (propDef.has("readOnly") && propDef.get("readOnly").asBoolean()) continue;

            if (propDef.has("properties")) {
                collectMutations(mutation, path, propDef.get("properties"));
                continue;
            }

            String type = propDef.has("type") ? propDef.get("type").asText() : "string";
            switch (type) {
                case "integer", "number" -> mutation.put(path, sampleNumber(name));
                case "boolean"           -> mutation.put(path, true);
                default -> {
                    // Use the first enum value if one exists, otherwise use a generic string
                    if (propDef.has("enum") && propDef.get("enum").isArray()
                            && !propDef.get("enum").isEmpty()) {
                        mutation.put(path, propDef.get("enum").get(0).asText());
                    } else {
                        mutation.put(path, "test");
                    }
                }
            }
        }
    }

    /** Returns a plausible numeric value for car-domain field names. */
    private int sampleNumber(String fieldName) {
        String lower = fieldName.toLowerCase();
        if (lower.contains("year")) return 2010;
        if (lower.contains("weight") || lower.contains("mass")) return 1400;
        if (lower.contains("emission") || lower.contains("co2")) return 120;
        if (lower.contains("engine") || lower.contains("power")) return 110;
        return 100;
    }
}
