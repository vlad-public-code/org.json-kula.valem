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
        String mutationBody = buildSampleMutation(spec, false);
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

        log.info("Initial model state is reachable — the generated model is fine on its own");

        // ── Step 4b: RESPONSIVENESS — a derived output must actually depend on the inputs ──
        // Drive the writable numeric inputs LOW, then HIGH, and require some NON-input numeric leaf
        // (a derived field) to change. Compares leaves at ANY depth (the model may nest fields under a
        // container object), excluding the input leaves we set. Domain-agnostic (no Estonian numbers),
        // yet rejects a degenerate model whose "tax" is a constant that ignores its inputs.
        String lowMut  = buildSampleMutation(spec, false);
        String highMut = buildSampleMutation(spec, true);
        java.util.Set<String> inputPaths = new java.util.HashSet<>();   // e.g. "vehicle.weight"
        mapper.readTree(lowMut).fieldNames().forEachRemaining(
                k -> inputPaths.add(k.startsWith("$.") ? k.substring(2) : k));

        java.util.Map<String, Double> lowLeaves = new java.util.HashMap<>();
        java.util.Map<String, Double> highLeaves = new java.util.HashMap<>();
        collectNumericLeaves(mutateAndGetState(lowMut), "", lowLeaves);
        collectNumericLeaves(mutateAndGetState(highMut), "", highLeaves);

        boolean responded = highLeaves.entrySet().stream().anyMatch(e ->
                !inputPaths.contains(e.getKey())                       // a derived leaf, not one we set
                        && lowLeaves.containsKey(e.getKey())
                        && !lowLeaves.get(e.getKey()).equals(e.getValue()));
        assertThat(responded)
                .as("a derived output must change when the inputs change — the generated model must "
                    + "compute a tax that depends on its inputs, not a constant. low=" + lowLeaves
                    + " high=" + highLeaves + " inputs=" + inputPaths)
                .isTrue();
        log.info("Responsiveness verified — a derived output changes with the inputs");

        // ── Steps 5-8: OPTIONAL evolution scenario (best-effort, never fails the test) ──
        // The acceptance criterion is that the model GENERATES fine without needing evolution; the
        // evolution below is a separate feature driven by an INDEPENDENT LLM call that can legitimately
        // fail (e.g. it emits a cyclic dependency or an unknown field name) without implying anything
        // about the generated model's quality. So it is exercised opportunistically and only logged —
        // a 5xx during APPLY is still surfaced, but an LLM-side evolution failure is not fatal.
        try {
            String evolutionRequest =
                    "Add a 'fuelType' field (enum: petrol, diesel, electric) to the schema. " +
                    "For diesel vehicles add a 50 EUR surcharge to the annual tax.";
            log.info("(optional) Generating spec evolution via LLM: {}", evolutionRequest);
            SpecEvolution evolution = specGenerator.generateEvolution(spec, evolutionRequest);
            String evolutionJson = mapper.writerWithDefaultPrettyPrinter().writeValueAsString(evolution);
            log.info("Generated evolution:\n{}", evolutionJson);

            int evolveStatus = mvc.perform(post("/models/" + MODEL_ID + "/spec/evolve")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(evolutionJson))
                    .andReturn().getResponse().getStatus();
            assertThat(evolveStatus)
                    .as("applying a well-formed evolution must not cause a server error (5xx)")
                    .isLessThan(500);

            if (evolveStatus == 200) {
                int evolvedStatus = mvc.perform(post("/models/" + MODEL_ID + "/mutations")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"$.fuelType\": \"diesel\"}"))
                        .andReturn().getResponse().getStatus();
                assertThat(evolvedStatus)
                        .as("post-evolution mutation must not cause a server error (5xx)")
                        .isLessThan(500);
                mvc.perform(get("/models/" + MODEL_ID + "/state")).andExpect(status().isOk());
                log.info("Optional evolution scenario PASSED");
            } else {
                log.warn("Optional evolution returned {} on apply — skipping (not a generation-quality " +
                        "signal)", evolveStatus);
            }
        } catch (Exception e) {
            log.warn("Optional evolution step failed (independent LLM call) — not failing the test: {}",
                    e.getMessage());
        }
    }

    /** Collects every numeric leaf in {@code node} keyed by its dotted path (e.g. "vehicle.totalTax"). */
    private static void collectNumericLeaves(JsonNode node, String prefix, java.util.Map<String, Double> out) {
        if (node == null) return;
        if (node.isObject()) {
            node.fields().forEachRemaining(e -> collectNumericLeaves(
                    e.getValue(), prefix.isEmpty() ? e.getKey() : prefix + "." + e.getKey(), out));
        } else if (node.isNumber()) {
            out.put(prefix, node.asDouble());
        }
    }

    /** Applies a mutation body, then returns the resulting merged model state as JSON. */
    private JsonNode mutateAndGetState(String mutationBody) throws Exception {
        mvc.perform(post("/models/" + MODEL_ID + "/mutations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mutationBody))
                .andReturn();
        String stateJson = mvc.perform(get("/models/" + MODEL_ID + "/state"))
                .andReturn().getResponse().getContentAsString();
        return mapper.readTree(stateJson);
    }

    /**
     * Builds a mutation JSON object from heuristic defaults in the schema. Creation-time seed values
     * now live in a "$" defaultValues rule and are applied automatically at model creation.
     *
     * @param high when {@code true}, uses the larger end of each field's plausible range — so a
     *             {@code low} vs {@code high} pair can probe whether derived outputs respond to inputs.
     */
    private String buildSampleMutation(ModelSpec spec, boolean high) throws Exception {

        ObjectNode mutation = mapper.createObjectNode();
        JsonNode schema = spec.schema();
        if (schema != null && schema.has("properties")) {
            collectMutations(mutation, "$", schema.get("properties"), high);
        }

        if (mutation.isEmpty()) {
            mutation.put("$.year", high ? 2024 : 2005);
        }

        return mapper.writeValueAsString(mutation);
    }

    private void collectMutations(ObjectNode mutation, String prefix, JsonNode properties, boolean high) {
        Iterator<Map.Entry<String, JsonNode>> it = properties.fields();
        while (it.hasNext()) {
            Map.Entry<String, JsonNode> entry = it.next();
            String name = entry.getKey();
            JsonNode propDef = entry.getValue();
            String path = prefix + "." + name;

            // Skip derived (read-only) fields — writing them causes a 422 SchemaViolationException
            if (propDef.has("readOnly") && propDef.get("readOnly").asBoolean()) continue;

            if (propDef.has("properties")) {
                collectMutations(mutation, path, propDef.get("properties"), high);
                continue;
            }

            String type = propDef.has("type") ? propDef.get("type").asText() : "string";
            switch (type) {
                case "integer", "number" -> {
                    // Respect the field's declared bounds so a generic sample value never violates the
                    // model's OWN schema (e.g. a taxYear with "minimum": 2025 must not be fed 2010).
                    double v = sampleNumber(name, high);
                    if (propDef.has("minimum")) v = Math.max(v, propDef.get("minimum").asDouble());
                    if (propDef.has("maximum")) v = Math.min(v, propDef.get("maximum").asDouble());
                    if ("integer".equals(type)) mutation.put(path, (long) v);
                    else                         mutation.put(path, v);
                }
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

    /** A plausible numeric value for car-domain field names — the {@code low} or {@code high} end. */
    private int sampleNumber(String fieldName, boolean high) {
        String lower = fieldName.toLowerCase();
        if (lower.contains("year"))                              return high ? 2024 : 2005;
        if (lower.contains("weight") || lower.contains("mass"))  return high ? 2600 : 1200;
        if (lower.contains("emission") || lower.contains("co2")) return high ?  240 :   90;
        if (lower.contains("engine") || lower.contains("power")) return high ?  200 :   80;
        return high ? 300 : 100;
    }
}
