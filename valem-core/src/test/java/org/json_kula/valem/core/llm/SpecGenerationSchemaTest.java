package org.json_kula.valem.core.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class SpecGenerationSchemaTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void model_spec_schema_describes_the_well_known_sections() {
        JsonNode s = SpecGenerationSchema.modelSpec(MAPPER);
        assertThat(s.at("/type").asText()).isEqualTo("object");
        JsonNode props = s.at("/properties");
        assertThat(props.has("id")).isTrue();
        assertThat(props.has("schema")).isTrue();
        assertThat(props.has("derivations")).isTrue();
        assertThat(props.has("constraints")).isTrue();
        assertThat(props.has("tests")).isTrue();
        // a derivation item constrains path + expr
        assertThat(s.at("/properties/derivations/items/properties/expr/type").asText()).isEqualTo("string");
        assertThat(s.at("/required").toString()).contains("id").contains("schema");
    }

    @Test
    void spec_evolution_schema_describes_the_diff_fields() {
        JsonNode s = SpecGenerationSchema.specEvolution(MAPPER);
        JsonNode props = s.at("/properties");
        assertThat(props.has("upsertDerivations")).isTrue();
        assertThat(props.has("removeDerivations")).isTrue();
        assertThat(props.has("newSchema")).isTrue();
        assertThat(props.has("backfill")).isTrue();
        // it must NOT carry top-level spec-only keys like "id"
        assertThat(props.has("id")).isFalse();
        assertThat(s.at("/properties/removeDerivations/items/type").asText()).isEqualTo("string");
    }
}
