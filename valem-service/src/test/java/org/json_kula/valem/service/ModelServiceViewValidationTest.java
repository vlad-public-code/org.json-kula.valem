package org.json_kula.valem.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.json_kula.valem.core.blob.InMemoryBlobStore;
import org.json_kula.valem.core.graph.SpecEvolution;
import org.json_kula.valem.core.model.ModelSpec;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Covers M4: invalid viewDefinition is rejected at write time (422), not at first render (500). */
class ModelServiceViewValidationTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private ModelService service;

    @BeforeEach
    void setUp() {
        service = new ModelService(new ModelRegistry(), new InMemoryBlobStore());
    }

    private ModelSpec spec(String json) throws Exception {
        return MAPPER.readValue(json, ModelSpec.class);
    }

    private static final String VALID = """
            {
              "id": "m", "version": "1.0.0", "schema": {},
              "viewDefinition": {
                "renderer": "builtin", "defaultView": "main",
                "views": [ { "id": "main", "label": "Main", "components": [
                    { "id": "qty", "type": "numericField", "bind": "$.qty", "label": "Qty" }
                ] } ]
              }
            }
            """;

    @Test
    void valid_view_definition_is_accepted_on_create() throws Exception {
        service.createModel(spec(VALID));
        assertThat(service.listModels()).contains("m");
    }

    @Test
    void duplicate_view_id_is_rejected_on_create() throws Exception {
        assertThatThrownBy(() -> service.createModel(spec("""
                {
                  "id": "m", "version": "1.0.0", "schema": {},
                  "viewDefinition": { "defaultView": "main", "views": [
                    { "id": "main", "components": [] },
                    { "id": "main", "components": [] }
                  ]}
                }
                """)))
                .isInstanceOf(ModelValidationException.class);
    }

    @Test
    void dangling_default_view_is_rejected_on_create() throws Exception {
        assertThatThrownBy(() -> service.createModel(spec("""
                {
                  "id": "m", "version": "1.0.0", "schema": {},
                  "viewDefinition": { "defaultView": "nope", "views": [
                    { "id": "main", "components": [] }
                  ]}
                }
                """)))
                .isInstanceOf(ModelValidationException.class);
    }

    @Test
    void an_evolution_producing_an_invalid_view_is_rejected() throws Exception {
        service.createModel(spec(VALID));
        SpecEvolution bad = MAPPER.readValue("""
                { "removeViews": ["main"] }
                """, SpecEvolution.class);
        // Removing the only view leaves defaultView 'main' dangling → rejected, model untouched.
        assertThatThrownBy(() -> service.evolveSpec("m", bad))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(service.getSpec("m").viewDefinition().path("views")).hasSize(1);
    }

    @Test
    void a_valid_component_upsert_evolution_succeeds() throws Exception {
        service.createModel(spec(VALID));
        SpecEvolution ok = MAPPER.readValue("""
                { "upsertComponents": [
                    { "viewId": "main", "component": { "id": "qty", "type": "numericField",
                      "bind": "$.qty", "label": "Quantity" } } ]}
                """, SpecEvolution.class);
        ModelSpec evolved = service.evolveSpec("m", ok);
        String label = evolved.viewDefinition().path("views").path(0)
                .path("components").path(0).path("label").asText();
        assertThat(label).isEqualTo("Quantity");
    }
}
