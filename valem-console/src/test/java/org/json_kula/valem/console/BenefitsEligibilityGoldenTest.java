package org.json_kula.valem.console;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.json_kula.valem.core.blob.InMemoryBlobStore;
import org.json_kula.valem.core.engine.ConstraintEvaluator;
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
 * Golden / certification suite for the {@code benefits-eligibility} wedge reference model. As with
 * the insurance model, the expected tiers and benefit amounts are <em>hand-derived from the poverty
 * table</em> ({@code constants}) — an independent oracle, run through the full {@link ModelService}
 * reactive pipeline.
 */
class BenefitsEligibilityGoldenTest {

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
            .configure(SerializationFeature.FAIL_ON_EMPTY_BEANS, false);

    private ModelSpec loadSpec() throws Exception {
        try (InputStream is = getClass().getClassLoader().getResourceAsStream("examples/benefits-eligibility.json")) {
            assertThat(is).as("examples/benefits-eligibility.json on test classpath").isNotNull();
            return MAPPER.readValue(is, ModelSpec.class);
        }
    }

    private ModelService fresh(ModelSpec spec) {
        ModelService service = new ModelService(new ModelRegistry(), new InMemoryBlobStore());
        service.createModel(spec);
        return service;
    }

    @Test
    void spec_is_structurally_valid() throws Exception {
        ModelSpec spec = loadSpec();
        ModelSpecValidator.ValidationResult result = ModelSpecValidator.validate(spec);
        assertThat(result.isValid())
                .as("benefits-eligibility spec must compile: %s", result.findings())
                .isTrue();
        assertThat(spec.tests()).hasSizeGreaterThanOrEqualTo(6);
        assertThat(spec.effects()).hasSize(3);
    }

    @Test
    void golden_dataset_every_case_matches_the_hand_derived_oracle() throws Exception {
        ModelSpec spec = loadSpec();
        for (TestCase tc : spec.tests()) {
            ModelService service = fresh(spec);
            service.mutate(spec.id(), tc.given());
            ObjectNode state = service.getState(spec.id(), null);
            for (Map.Entry<String, JsonNode> e : tc.expect().entrySet()) {
                if (TestCase.isMetaAssertion(e.getValue())) continue;
                JsonNode actual   = state.at(PathConverter.toJsonPointer(e.getKey()));
                JsonNode expected = e.getValue();
                if (expected.isNumber()) {
                    assertThat(actual.doubleValue()).as("[%s] %s", tc.description(), e.getKey())
                            .isCloseTo(expected.doubleValue(), within(0.001));
                } else {
                    assertThat(actual).as("[%s] %s", tc.description(), e.getKey()).isEqualTo(expected);
                }
            }
        }
    }

    @Test
    void poverty_line_and_benefit_recompute_reactively_on_household_change() throws Exception {
        ModelSpec spec = loadSpec();
        ModelService service = fresh(spec);
        service.mutate(spec.id(), Map.of(
                "$.application.age", MAPPER.valueToTree(30),
                "$.application.income", MAPPER.valueToTree(18000),
                "$.application.householdSize", MAPPER.valueToTree(1)));
        // 18000 / 15000 = 1.2 -> partial tier -> $300
        assertThat(service.getState(spec.id(), null)
                .at(PathConverter.toJsonPointer("$.application.monthlyBenefit")).doubleValue())
                .isCloseTo(300.0, within(0.001));

        // Growing the household raises the poverty line (30000), dropping the ratio to 0.6 -> full -> $500
        service.mutate(spec.id(), Map.of("$.application.householdSize", MAPPER.valueToTree(4)));
        ObjectNode after = service.getState(spec.id(), null);
        assertThat(after.at(PathConverter.toJsonPointer("$.application.povertyLine")).doubleValue())
                .isCloseTo(30000.0, within(0.001));
        assertThat(after.at(PathConverter.toJsonPointer("$.application.monthlyBenefit")).doubleValue())
                .isCloseTo(500.0, within(0.001));
    }

    @Test
    void oversized_household_raises_a_soft_flag_without_rolling_back() throws Exception {
        ModelSpec spec = loadSpec();
        ModelService service = fresh(spec);
        // maxHousehold is 12; 15 trips the FLAG constraint but the mutation still commits.
        var outcome = service.mutate(spec.id(), Map.of("$.application.householdSize", MAPPER.valueToTree(15)));
        assertThat(outcome.result().flaggedConstraints())
                .extracting(ConstraintEvaluator.Violation::constraintId)
                .contains("household-size-in-range");
        assertThat(service.getState(spec.id(), null)
                .at(PathConverter.toJsonPointer("$.application.householdSize")).intValue())
                .isEqualTo(15);
    }
}
