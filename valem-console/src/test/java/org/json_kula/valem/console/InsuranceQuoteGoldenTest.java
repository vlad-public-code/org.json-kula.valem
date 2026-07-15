package org.json_kula.valem.console;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.json_kula.valem.core.blob.InMemoryBlobStore;
import org.json_kula.valem.core.graph.ModelSpecValidator;
import org.json_kula.valem.core.model.ModelSpec;
import org.json_kula.valem.core.model.TestCase;
import org.json_kula.valem.core.state.PathConverter;
import org.json_kula.valem.service.ModelRegistry;
import org.json_kula.valem.service.ModelService;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * Golden / certification suite for the {@code insurance-quote} beachhead reference model.
 *
 * <p>This is the "model certification" idea made concrete: the premium expectations in the spec's
 * {@code tests} array are <em>hand-derived from the actuarial rate table</em> (the model's
 * {@code constants}), so they are an <b>independent oracle</b> — not assertions the LLM wrote about
 * its own formula. Running them through the full {@link ModelService} reactive pipeline establishes
 * that the model faithfully computes the intended domain rules (derivations + eligibility) before it
 * would ever govern a real quote.
 */
class InsuranceQuoteGoldenTest {

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
            .configure(SerializationFeature.FAIL_ON_EMPTY_BEANS, false);

    private ModelSpec loadSpec() throws Exception {
        try (InputStream is = getClass().getClassLoader().getResourceAsStream("examples/insurance-quote.json")) {
            assertThat(is).as("examples/insurance-quote.json on test classpath").isNotNull();
            return MAPPER.readValue(is, ModelSpec.class);
        }
    }

    private ModelService freshService(ModelSpec spec) {
        ModelService service = new ModelService(new ModelRegistry(), new InMemoryBlobStore());
        service.createModel(spec);
        return service;
    }

    @Test
    void spec_is_structurally_valid() throws Exception {
        ModelSpec spec = loadSpec();
        ModelSpecValidator.ValidationResult result = ModelSpecValidator.validate(spec);
        assertThat(result.isValid())
                .as("insurance-quote spec must compile: %s", result.findings())
                .isTrue();
        assertThat(spec.tests()).as("golden dataset present").hasSizeGreaterThanOrEqualTo(6);
        assertThat(spec.effects()).as("all three effect kinds demonstrated").hasSize(3);
    }

    @Test
    void golden_dataset_every_case_matches_the_hand_derived_oracle() throws Exception {
        ModelSpec spec = loadSpec();

        for (TestCase tc : spec.tests()) {
            ModelService service = freshService(spec);
            service.mutate(spec.id(), tc.given());
            ObjectNode state = service.getState(spec.id(), null);

            for (Map.Entry<String, JsonNode> e : tc.expect().entrySet()) {
                if (TestCase.isMetaAssertion(e.getValue())) continue;
                JsonNode actual   = state.at(PathConverter.toJsonPointer(e.getKey()));
                JsonNode expected = e.getValue();
                if (expected.isNumber()) {
                    assertThat(actual.doubleValue())
                            .as("[%s] %s", tc.description(), e.getKey())
                            .isCloseTo(expected.doubleValue(), within(0.001));
                } else {
                    assertThat(actual)
                            .as("[%s] %s", tc.description(), e.getKey())
                            .isEqualTo(expected);
                }
            }
        }
    }

    @Test
    void chained_derivations_recompute_reactively_on_coverage_change() throws Exception {
        ModelSpec spec = loadSpec();
        ModelService service = freshService(spec);

        service.mutate(spec.id(), Map.of(
                "$.quote.applicant.age", MAPPER.valueToTree(40),
                "$.quote.applicant.smoker", MAPPER.valueToTree(false),
                "$.quote.coverage", MAPPER.valueToTree(100000)));
        assertThat(service.getState(spec.id(), null)
                .at(PathConverter.toJsonPointer("$.quote.annualPremium")).doubleValue())
                .isCloseTo(150.0, within(0.001));   // 100 units x 1.5 x ageFactor 1.0

        // Doubling coverage must ripple units -> baseAnnual -> annualPremium -> monthlyPremium.
        service.mutate(spec.id(), Map.of("$.quote.coverage", MAPPER.valueToTree(200000)));
        ObjectNode after = service.getState(spec.id(), null);
        assertThat(after.at(PathConverter.toJsonPointer("$.quote.annualPremium")).doubleValue())
                .isCloseTo(300.0, within(0.001));
        assertThat(after.at(PathConverter.toJsonPointer("$.quote.monthlyPremium")).doubleValue())
                .isCloseTo(25.0, within(0.001));
    }

    @Test
    void positive_coverage_invariant_rolls_back() throws Exception {
        ModelSpec spec = loadSpec();
        ModelService service = freshService(spec);
        // coverage-positive is a rollback constraint. Zero passes the schema (minimum 0) but trips
        // the `> 0` invariant, so it exercises the constraint rather than pre-transaction schema rejection.
        try {
            service.mutate(spec.id(), Map.of("$.quote.coverage", MAPPER.valueToTree(0)));
            org.junit.jupiter.api.Assertions.fail("expected a rollback constraint violation");
        } catch (org.json_kula.valem.core.engine.ConstraintEvaluator.ConstraintViolationException expected) {
            // state must be unchanged (rolled back)
            JsonNode cov = service.getState(spec.id(), null)
                    .at(PathConverter.toJsonPointer("$.quote.coverage"));
            assertThat(cov.isMissingNode() || cov.isNull()).isTrue();
        }
    }
}
