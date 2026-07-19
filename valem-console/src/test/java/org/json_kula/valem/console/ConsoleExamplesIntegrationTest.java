package org.json_kula.valem.console;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.json_kula.valem.core.blob.InMemoryBlobStore;
import org.json_kula.valem.core.model.ModelSpec;
import org.json_kula.valem.core.model.TestCase;
import org.json_kula.valem.core.state.PathConverter;
import org.json_kula.valem.service.ModelRegistry;
import org.json_kula.valem.service.ModelService;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * Integration tests that load the real example model specs from valem-ui/src/examples
 * (copied to test-classpath under examples/) and run the assertions embedded in each spec's
 * "tests" array through the full ModelService reactive pipeline.
 */
class ConsoleExamplesIntegrationTest {

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
            .configure(DeserializationFeature.READ_ENUMS_USING_TO_STRING, false)
            .configure(SerializationFeature.FAIL_ON_EMPTY_BEANS, false);

    // ── order-items-price-total ────────────────────────────────────────────────

    @Test
    void order_items_price_total_spec_test_cases() throws Exception {
        ModelSpec spec = loadSpec("order-items-price-total.json");
        assertThat(spec.tests()).isNotEmpty();

        for (TestCase tc : spec.tests()) {
            Context ctx = fresh(spec);
            applyGiven(ctx, tc.given());
            ObjectNode state = ctx.service().getState(ctx.id(), null);
            assertExpect(tc.description(), state, tc.expect());
        }
    }

    // ── car-loan-calculator ────────────────────────────────────────────────────

    @Test
    void car_loan_calculator_spec_test_cases() throws Exception {
        ModelSpec spec = loadSpec("car-loan-calculator.json");
        assertThat(spec.tests()).isNotEmpty();

        for (TestCase tc : spec.tests()) {
            Context ctx = fresh(spec);
            applyGiven(ctx, tc.given());
            ObjectNode state = ctx.service().getState(ctx.id(), null);
            assertExpect(tc.description(), state, tc.expect());

            // Additional: schedule array has 60 rows
            JsonNode schedule = state.path("schedule");
            assertThat(schedule.isArray()).as("schedule must be an array").isTrue();
            assertThat(schedule.size()).as("schedule has 60 months").isEqualTo(60);
        }
    }

    // ── savings-growth ─────────────────────────────────────────────────────────

    @Test
    void savings_growth_spec_test_cases() throws Exception {
        ModelSpec spec = loadSpec("savings-growth.json");
        assertThat(spec.tests()).isNotEmpty();

        for (TestCase tc : spec.tests()) {
            Context ctx = fresh(spec);
            applyGiven(ctx, tc.given());
            ObjectNode state = ctx.service().getState(ctx.id(), null);
            assertExpect(tc.description(), state, tc.expect());

            // Additional: the projection covers year 0 through `years` inclusive.
            JsonNode projection = state.path("projection");
            assertThat(projection.isArray()).as("projection must be an array").isTrue();
            int years = tc.given().get("$.years").asInt();
            assertThat(projection.size()).as("projection has years+1 rows").isEqualTo(years + 1);
        }
    }

    // ── daily-wellness ────────────────────────────────────────────────────────

    @Test
    void daily_wellness_spec_test_cases() throws Exception {
        ModelSpec spec = loadSpec("daily-wellness.json");
        assertThat(spec.tests()).isNotEmpty();

        for (TestCase tc : spec.tests()) {
            Context ctx = fresh(spec);
            applyGiven(ctx, tc.given());
            ObjectNode state = ctx.service().getState(ctx.id(), null);
            assertExpect(tc.description(), state, tc.expect());
        }
    }

    // ── customer-satisfaction-survey ──────────────────────────────────────────

    @Test
    void customer_satisfaction_survey_spec_test_cases() throws Exception {
        ModelSpec spec = loadSpec("customer-satisfaction-survey.json");
        assertThat(spec.tests()).isNotEmpty();

        for (TestCase tc : spec.tests()) {
            Context ctx = fresh(spec);
            applyGiven(ctx, tc.given());
            ObjectNode state = ctx.service().getState(ctx.id(), null);
            assertExpect(tc.description(), state, tc.expect());
        }
    }

    // ── helpers ────────────────────────────────────────────────────────────────

    private record Context(ModelService service, String id) {}

    /**
     * Applies given mutations, handling conditionally read-only fields:
     * tries the full batch first; if a SchemaViolationException fires (some fields are
     * currently read-only), falls back to per-field retry across multiple passes so that
     * "unlock" mutations are applied first and gated fields become writable in later passes.
     */
    private static void applyGiven(Context ctx, Map<String, JsonNode> given) {
        try {
            ctx.service().mutate(ctx.id(), given);
            return;
        } catch (org.json_kula.valem.core.engine.SchemaViolationException ignored) {
            // one or more fields are conditionally read-only; fall back to per-field retry
        }
        Map<String, JsonNode> remaining = new LinkedHashMap<>(given);
        int prevSize = -1;
        while (!remaining.isEmpty() && remaining.size() != prevSize) {
            prevSize = remaining.size();
            Iterator<Map.Entry<String, JsonNode>> it = remaining.entrySet().iterator();
            while (it.hasNext()) {
                Map.Entry<String, JsonNode> e = it.next();
                try {
                    ctx.service().mutate(ctx.id(), Map.of(e.getKey(), e.getValue()));
                    it.remove();
                } catch (org.json_kula.valem.core.engine.SchemaViolationException ignoredInner) {
                    // field still read-only; retry after other unlock mutations are applied
                }
            }
        }
        assertThat(remaining).as("all given mutations applied").isEmpty();
    }

    /** Creates a fresh service, loads the spec (applying creation defaults if any), and returns it. */
    private Context fresh(ModelSpec spec) {
        ModelService service = new ModelService(new ModelRegistry(), new InMemoryBlobStore());
        service.createModel(spec);
        return new Context(service, spec.id());
    }

    private ModelSpec loadSpec(String name) throws Exception {
        try (InputStream is = getClass().getClassLoader().getResourceAsStream("examples/" + name)) {
            assertThat(is).as("test resource not found: examples/" + name).isNotNull();
            return MAPPER.readValue(is, ModelSpec.class);
        }
    }

    /**
     * Asserts every non-meta entry in {@code expect} against the merged {@code state}.
     * Numeric comparisons use a tolerance of 0.01 to accommodate floating-point rounding.
     */
    private static void assertExpect(String description, ObjectNode state, Map<String, JsonNode> expect) {
        for (Map.Entry<String, JsonNode> entry : expect.entrySet()) {
            if (TestCase.isMetaAssertion(entry.getValue())) continue;

            String   jsonPath = entry.getKey();
            JsonNode expected = entry.getValue();
            JsonNode actual   = state.at(PathConverter.toJsonPointer(jsonPath));

            if (expected.isNumber()) {
                assertThat(actual.doubleValue())
                        .as("[%s] %s", description, jsonPath)
                        .isCloseTo(expected.doubleValue(), within(0.01));
            } else {
                assertThat(actual)
                        .as("[%s] %s", description, jsonPath)
                        .isEqualTo(expected);
            }
        }
    }
}
