package org.json_kula.valem.core.graph;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.json_kula.valem.core.engine.TestCaseRunner;
import org.json_kula.valem.core.model.ModelSpec;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Validates the bundled shipping-cost-estimator.json example: structurally valid and its
 * embedded self-tests pass, the same guarantee the other bundled examples rely on.
 */
class ShippingCostEstimatorExampleTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void spec_is_valid_and_embedded_tests_pass() throws Exception {
        File specFile = resolveSpecFile();
        JsonNode raw = MAPPER.readTree(specFile);
        // Bundled examples carry documentation-only "_name"/"_description" fields that every real
        // client (ExamplePicker, CreatePanel) strips before POSTing; ModelSpec has no such fields.
        ObjectNode clean = raw.deepCopy();
        clean.remove("_name");
        clean.remove("_description");
        ModelSpec spec = MAPPER.treeToValue(clean, ModelSpec.class);

        ModelSpecValidator.ValidationResult validation = ModelSpecValidator.validate(spec);
        assertThat(validation.isValid())
                .withFailMessage(() -> "Validation errors: " + validation.errors())
                .isTrue();

        List<TestCaseRunner.TestResult> results = TestCaseRunner.run(spec, spec.tests());
        for (TestCaseRunner.TestResult r : results) {
            assertThat(r.passed())
                    .withFailMessage(() -> r.description() + " failed: " + r.failures())
                    .isTrue();
        }
    }

    private static File resolveSpecFile() {
        String[] candidates = {
                "../valem-ui/src/examples/shipping-cost-estimator.json",
                "valem-ui/src/examples/shipping-cost-estimator.json",
        };
        for (String c : candidates) {
            File f = new File(c);
            if (f.exists()) return f;
        }
        throw new IllegalStateException("shipping-cost-estimator.json not found relative to working dir");
    }
}
