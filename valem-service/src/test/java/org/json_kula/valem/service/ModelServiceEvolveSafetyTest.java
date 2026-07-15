package org.json_kula.valem.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import org.json_kula.valem.core.blob.InMemoryBlobStore;
import org.json_kula.valem.core.graph.SpecEvolution;
import org.json_kula.valem.core.model.ModelSpec;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Covers M3: optimistic-concurrency precondition + state-vs-new-schema safety walk. */
class ModelServiceEvolveSafetyTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final JsonNodeFactory NF = JsonNodeFactory.instance;

    private ModelService service;

    @BeforeEach
    void setUp() {
        service = new ModelService(new ModelRegistry(), new InMemoryBlobStore());
    }

    private ModelSpec spec(String json) throws Exception {
        return MAPPER.readValue(json, ModelSpec.class);
    }

    private SpecEvolution evolution(String json) throws Exception {
        return MAPPER.readValue(json, SpecEvolution.class);
    }

    private static final String QTY_SPEC = """
            {
              "id": "m",
              "version": "1.0.0",
              "schema": {
                "type": "object",
                "properties": { "qty": { "type": "integer" } }
              }
            }
            """;

    // ── expectedVersion ─────────────────────────────────────────────────────────

    @Test
    void matching_expected_version_evolves() throws Exception {
        service.createModel(spec(QTY_SPEC));
        ModelSpec evolved = service.evolveSpec("m", evolution("""
                { "expectedVersion": "1.0.0", "newVersion": "1.1.0" }
                """));
        assertThat(evolved.version()).isEqualTo("1.1.0");
    }

    @Test
    void mismatched_expected_version_conflicts() throws Exception {
        service.createModel(spec(QTY_SPEC));
        assertThatThrownBy(() -> service.evolveSpec("m", evolution("""
                { "expectedVersion": "9.9.9", "newVersion": "1.1.0" }
                """)))
                .isInstanceOf(SpecVersionConflictException.class);
        // model untouched
        assertThat(service.getSpec("m").version()).isEqualTo("1.0.0");
    }

    @Test
    void absent_expected_version_skips_the_check() throws Exception {
        service.createModel(spec(QTY_SPEC));
        ModelSpec evolved = service.evolveSpec("m", evolution("""
                { "newVersion": "1.1.0" }
                """));
        assertThat(evolved.version()).isEqualTo("1.1.0");
    }

    // ── state-vs-new-schema walk ────────────────────────────────────────────────

    @Test
    void retype_with_conforming_state_passes() throws Exception {
        service.createModel(spec(QTY_SPEC));
        service.mutate("m", java.util.Map.of("$.qty", NF.numberNode(3)));
        // integer → string, but seed a conforming string first via backfill? No — existing value
        // is an int, so retype to string must FAIL (covered below). Here retype to "number" keeps it valid.
        ModelSpec evolved = service.evolveSpec("m", evolution("""
                { "upsertSchemaNodes": [ { "path": "$.qty", "schema": { "type": "number" } } ] }
                """));
        assertThat(evolved.schema().path("properties").path("qty").path("type").asText()).isEqualTo("number");
    }

    @Test
    void retype_that_strands_existing_state_is_rejected() throws Exception {
        service.createModel(spec(QTY_SPEC));
        service.mutate("m", java.util.Map.of("$.qty", NF.numberNode(3)));   // qty = 3 (integer)

        assertThatThrownBy(() -> service.evolveSpec("m", evolution("""
                { "upsertSchemaNodes": [ { "path": "$.qty", "schema": { "type": "string" } } ] }
                """)))
                .isInstanceOf(SchemaStateIncompatibleException.class)
                .satisfies(ex -> assertThat(((SchemaStateIncompatibleException) ex).incompatibilities())
                        .anySatisfy(i -> assertThat(i.path()).isEqualTo("$.qty")));

        // model untouched: still integer, qty still readable
        assertThat(service.getSpec("m").schema().path("properties").path("qty").path("type").asText())
                .isEqualTo("integer");
    }

    @Test
    void non_schema_evolution_skips_the_walk() throws Exception {
        service.createModel(spec(QTY_SPEC));
        service.mutate("m", java.util.Map.of("$.qty", NF.numberNode(3)));
        // Adding a derivation does not touch the schema, so no state walk runs.
        ModelSpec evolved = service.evolveSpec("m", evolution("""
                { "upsertDerivations": [ { "path": "$.doubled", "expr": "qty * 2" } ] }
                """));
        assertThat(evolved.derivations()).anySatisfy(d -> assertThat(d.path()).isEqualTo("$.doubled"));
    }
}
