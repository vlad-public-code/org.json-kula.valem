package org.json_kula.valem.api.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.json_kula.valem.core.engine.TestCaseRunner;
import org.json_kula.valem.core.graph.ModelSpecValidator;
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

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * Generation-quality eval harness (suggestion #5). Runs a small suite of domain prompts through the
 * real LLM and scores each on: generated, structurally valid, <b>view-lint clean</b>, embedded tests
 * pass, registers via POST /models, and a sample mutation applies without a 422/5xx. Emits a
 * scorecard so a prompt/model change can be judged against a repeatable baseline rather than a single
 * anecdote.
 *
 * <p>Skipped automatically when the LLM is not configured (set a Mistral key + provider). Not run in
 * the normal suite budget — invoke explicitly with {@code -Dtest=GenerationEvalIT}.
 */
@SpringBootTest
@AutoConfigureMockMvc
class GenerationEvalIT {

    private static final Logger log = LoggerFactory.getLogger(GenerationEvalIT.class);

    /** A domain prompt to score. Terse ones also exercise the domain-anchoring nudge. */
    record Scenario(String id, String prompt) {}

    /** Per-scenario result row. */
    record Scorecard(String id, boolean generated, boolean valid, boolean viewClean,
                     int viewFindings, int failingTests, boolean registered, boolean mutationOk) {
        int score() {
            int s = 0;
            if (generated) s++;
            if (valid) s++;
            if (viewClean) s++;
            if (failingTests == 0) s++;
            if (registered) s++;
            if (mutationOk) s++;
            return s;
        }
    }

    private static final List<Scenario> SCENARIOS = List.of(
            new Scenario("body-mass-index", "Body Mass Index"),
            new Scenario("tip-calculator",
                    "Restaurant tip calculator: inputs are the bill amount and the tip percentage; "
                    + "compute the tip amount and the total to pay."),
            new Scenario("mortgage-payment",
                    "Monthly mortgage payment from loan principal, annual interest rate percent, and "
                    + "term in years, using the standard amortization formula.")
    );

    @Autowired(required = false)
    SpecGenerator specGenerator;

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;

    @Test
    void score_llm_generation_across_domains() throws Exception {
        Assumptions.assumeTrue(specGenerator != null,
                "Skipping: LLM not configured (set the Mistral key + provider=mistral)");

        List<Scorecard> cards = new ArrayList<>();
        for (Scenario s : SCENARIOS) {
            try {
                cards.add(score(s));
            } catch (Throwable t) {
                // A harness must isolate a single scenario's failure so the scorecard still completes
                // and records it as a data point rather than aborting the whole run.
                log.error("Scenario '{}' threw during scoring", s.id(), t);
                cards.add(new Scorecard(s.id(), false, false, false, -1, -1, false, false));
            }
        }

        // ── Scorecard ────────────────────────────────────────────────────────
        StringBuilder table = new StringBuilder("\n===== GENERATION EVAL SCORECARD =====\n");
        table.append(String.format("%-20s %4s %5s %9s %6s %9s %5s  %s%n",
                "domain", "gen", "valid", "viewClean", "tests", "register", "mut", "score"));
        for (Scorecard c : cards) {
            table.append(String.format("%-20s %4s %5s %9s %6s %9s %5s  %d/6%n",
                    c.id(), yn(c.generated()), yn(c.valid()),
                    c.viewClean() ? "yes" : ("no(" + c.viewFindings() + ")"),
                    c.failingTests() == 0 ? "ok" : ("x" + c.failingTests()),
                    yn(c.registered()), yn(c.mutationOk()), c.score()));
        }
        double avg = cards.stream().mapToInt(Scorecard::score).average().orElse(0);
        long viewClean = cards.stream().filter(Scorecard::viewClean).count();
        table.append(String.format("avg score: %.2f/6   view-clean: %d/%d%n",
                avg, viewClean, cards.size()));
        log.info(table.toString());

        // ── Aggregate gate (loose — the scorecard is the deliverable) ─────────
        // Robust against LLM nondeterminism: don't demand every domain succeed (the harness exists to
        // MEASURE that), but the run must complete without a crash and the majority must be usable.
        long usable = cards.stream().filter(c -> c.generated() && c.valid()).count();
        assertThat(usable)
                .as("at least a majority of domains must generate a structurally-valid spec")
                .isGreaterThanOrEqualTo((SCENARIOS.size() + 1) / 2);
    }

    private Scorecard score(Scenario s) throws Exception {
        log.info("Scoring scenario '{}'...", s.id());
        GenerationResult result = specGenerator.generate(s.id(), s.prompt(), true);
        if (!(result instanceof GenerationResult.Success success)) {
            return new Scorecard(s.id(), false, false, false, -1, -1, false, false);
        }
        ModelSpec spec = success.spec();
        boolean valid = ModelSpecValidator.validate(spec).isValid();
        List<ModelSpecValidator.ValidationError> viewFindings = ModelSpecValidator.lintView(spec);
        if (!viewFindings.isEmpty()) {
            log.warn("[{}] view-lint findings:\n  {}", s.id(), viewFindings.stream()
                    .map(e -> e.location() + ": " + e.message())
                    .reduce((a, b) -> a + "\n  " + b).orElse(""));
        }
        int failingTests = (int) TestCaseRunner.run(spec, spec.tests()).stream()
                .filter(TestCaseRunner.TestResult::failed).count();

        String specJson = mapper.writeValueAsString(spec);
        boolean registered = mvc.perform(post("/models")
                        .contentType(MediaType.APPLICATION_JSON).content(specJson))
                .andReturn().getResponse().getStatus() == 201;

        boolean mutationOk = false;
        if (registered) {
            int status = mvc.perform(post("/models/" + spec.id() + "/mutations")
                            .contentType(MediaType.APPLICATION_JSON).content(sampleMutation(spec)))
                    .andReturn().getResponse().getStatus();
            mutationOk = status != 422 && status < 500;
        }
        return new Scorecard(s.id(), true, valid, viewFindings.isEmpty(),
                viewFindings.size(), failingTests, registered, mutationOk);
    }

    private String sampleMutation(ModelSpec spec) throws Exception {
        ObjectNode m = mapper.createObjectNode();
        JsonNode schema = spec.schema();
        if (schema != null && schema.has("properties")) {
            Iterator<Map.Entry<String, JsonNode>> it = schema.get("properties").fields();
            while (it.hasNext()) {
                Map.Entry<String, JsonNode> e = it.next();
                JsonNode def = e.getValue();
                if (def.path("readOnly").asBoolean(false)) continue;
                String type = def.path("type").asText("");
                if ("array".equals(type) || "object".equals(type) || def.has("properties")) continue;
                String path = "$." + e.getKey();
                switch (type) {
                    case "integer", "number" -> m.put(path, 10);
                    case "boolean" -> m.put(path, true);
                    default -> {
                        if (def.has("enum") && def.get("enum").isArray() && !def.get("enum").isEmpty())
                            m.put(path, def.get("enum").get(0).asText());
                        else m.put(path, "x");
                    }
                }
            }
        }
        return mapper.writeValueAsString(m);
    }

    private static String yn(boolean b) { return b ? "yes" : "no"; }
}
