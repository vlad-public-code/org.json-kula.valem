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
 * End-to-end test: calls the real LLM (Mistral ministral-14b-2512) to generate a
 * house heating energy consumption model spec, then registers and exercises it via the REST API.
 *
 * Skipped automatically when MISTRAL_API_KEY is not set.
 */
@SpringBootTest
@AutoConfigureMockMvc
class HouseHeatingGenerateIT {

    private static final Logger log = LoggerFactory.getLogger(HouseHeatingGenerateIT.class);

    private static final String MODEL_ID = "house-heating-energy";
    private static final String DOMAIN_DESCRIPTION =
            "Generate model for calculating annual energy consumption for house heating. " +
            "Inputs: floor area (m2, number), roof area (m2, number), wall height (m, number), " +
            "glazing area (m2, number), wall thermal insulation U-value (W/m2K, number), " +
            "ceiling thermal insulation U-value (W/m2K, number), " +
            "glass unit type (string enum: single, double, triple), " +
            "outside temperature (Celsius, number), inside temperature (Celsius, number). " +
            "Derive: glazing U-value from glass unit type (single=5.8, double=2.8, triple=1.1), " +
            "perimeter wall area (4 * sqrt(floor area) * wall height), " +
            "heat loss through walls (wall area * wall U-value * temperature delta), " +
            "heat loss through roof (roof area * ceiling U-value * temperature delta), " +
            "heat loss through glazing (glazing area * glazing U-value * temperature delta), " +
            "total heat loss (W), required heating power (kW), " +
            "annual energy consumption (kWh, assuming 5000 heating hours per year). " +
            "Add constraints: floor area must be positive, temperature delta must be non-negative.";

    @Autowired(required = false)
    SpecGenerator specGenerator;

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;

    @Test
    void generate_house_heating_model_and_register_it() throws Exception {
        Assumptions.assumeTrue(specGenerator != null,
                "Skipping: LLM not configured (set MISTRAL_API_KEY)");

        // ── Step 1: generate spec via LLM ────────────────────────────────────
        log.info("Generating house heating energy model spec via LLM...");
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
                .as("spec must have derivations computing heat loss and energy consumption")
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
                "Add a 'heatingEfficiencyFactor' field (number, default 1.0, range 0.5-2.0) " +
                "representing the efficiency of the heating system (e.g. 0.9 for a gas boiler, " +
                "1.5 for a heat pump). Divide the annual energy consumption by this factor " +
                "to derive an 'effectiveEnergyConsumption' field (kWh).";
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

        // ── Step 7: mutate with the newly introduced efficiency field ─────────────
        // 422 is possible if the LLM chose a different field name; 5xx always fails.
        int evolvedStatus = mvc.perform(post("/models/" + MODEL_ID + "/mutations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"$.heatingEfficiencyFactor\": 1.5}"))
                .andReturn().getResponse().getStatus();

        assertThat(evolvedStatus)
                .as("post-evolution mutation must not cause a server error (5xx)")
                .isLessThan(500);

        if (evolvedStatus == 200) {
            log.info("Post-evolution mutation accepted — heating efficiency factor field active");
        } else {
            log.warn("Post-evolution mutation returned {} " +
                    "(LLM may have used a different field name for heatingEfficiencyFactor)", evolvedStatus);
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
            mutation.put("$.floorArea", 120);
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

            String type = propDef.has("type") ? propDef.get("type").asText() : "object";
            if ("array".equals(type)) continue;

            if (propDef.has("properties")) {
                collectMutations(mutation, path, propDef.get("properties"));
                continue;
            }

            switch (type) {
                case "integer", "number" -> mutation.put(path, sampleNumber(name));
                case "boolean"           -> mutation.put(path, false);
                default -> {
                    if (propDef.has("enum") && propDef.get("enum").isArray()
                            && !propDef.get("enum").isEmpty()) {
                        mutation.put(path, propDef.get("enum").get(0).asText());
                    } else {
                        mutation.put(path, "double");
                    }
                }
            }
        }
    }

    /** Returns a plausible numeric value for house heating domain field names. */
    private double sampleNumber(String fieldName) {
        String lower = fieldName.toLowerCase();
        // U-value checks must come before generic "wall"/"area" checks
        if (lower.contains("uvalue") || lower.contains("u_value") || lower.contains("u-value")
                || lower.contains("insulation") || lower.contains("conductance"))
            return 0.2;
        if (lower.contains("outside") || lower.contains("exterior") || lower.contains("outdoor"))
            return -10;
        if (lower.contains("inside") || lower.contains("interior") || lower.contains("indoor")
                || lower.contains("target") || lower.contains("desired"))
            return 21;
        if (lower.contains("temp") || lower.contains("delta"))
            return 21;
        if (lower.contains("efficiency") || lower.contains("factor") || lower.contains("cop"))
            return 1.0;
        if (lower.contains("hour") || lower.contains("season"))
            return 5000;
        if (lower.contains("floor"))
            return 120;
        if (lower.contains("roof"))
            return 130;
        if (lower.contains("height"))
            return 2.8;
        if (lower.contains("glaz") || lower.contains("window"))
            return 24;
        if (lower.contains("wall") || lower.contains("area"))
            return 120;
        return 100;
    }
}
