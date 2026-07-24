package org.json_kula.valem.core.graph;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.json_kula.valem.core.model.ModelSpec;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Covers the {@code newTests} evolution tier — wholesale replacement of the embedded test list,
 * with {@code null} meaning "carry the existing tests forward untouched".
 */
class SpecEvolutionTestsTierTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private ModelSpec base(String json) throws Exception {
        return MAPPER.readValue(json, ModelSpec.class);
    }

    private SpecEvolution diff(String json) throws Exception {
        return MAPPER.readValue(json, SpecEvolution.class);
    }

    private static final String SPEC = """
            {
              "id": "m", "version": "1.0.0",
              "schema": { "type": "object", "properties": { "net": { "type": "number" }, "gross": { "type": "number" } } },
              "derivations": [ { "path": "$.gross", "expr": "net * 1.2" } ],
              "tests": [
                { "description": "doubles", "given": { "$.net": 10 }, "expect": { "$.gross": 12 } }
              ]
            }
            """;

    @Test
    void newTests_replaces_the_whole_test_list() throws Exception {
        ModelSpec evolved = diff("""
                {
                  "newTests": [
                    { "description": "hundred", "given": { "$.net": 100 }, "expect": { "$.gross": 120 } },
                    { "description": "zero",    "given": { "$.net": 0 },   "expect": { "$.gross": 0 } }
                  ]
                }
                """).applyTo(base(SPEC));

        assertThat(evolved.tests()).hasSize(2);
        assertThat(evolved.tests().get(0).description()).isEqualTo("hundred");
        assertThat(evolved.tests().get(1).description()).isEqualTo("zero");
    }

    @Test
    void absent_newTests_carries_existing_tests_forward() throws Exception {
        ModelSpec evolved = diff("""
                { "upsertDerivations": [ { "path": "$.gross", "expr": "net * 1.3" } ] }
                """).applyTo(base(SPEC));

        assertThat(evolved.tests()).hasSize(1);
        assertThat(evolved.tests().get(0).description()).isEqualTo("doubles");
    }

    @Test
    void empty_newTests_clears_the_test_list() throws Exception {
        ModelSpec evolved = diff("""
                { "newTests": [] }
                """).applyTo(base(SPEC));

        assertThat(evolved.tests()).isEmpty();
    }
}
