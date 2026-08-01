package org.json_kula.valem.console;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.json_kula.valem.core.blob.InMemoryBlobStore;
import org.json_kula.valem.core.model.ModelSpec;
import org.json_kula.valem.service.ModelRegistry;
import org.json_kula.valem.service.ModelService;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * End-to-end coverage for honouring JSON Schema {@code default} at model creation
 * (SchemaDefaultApplier, wired into ModelRuntime.initialize).
 */
class SchemaDefaultSeedingTest {

    private static final ObjectMapper M = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    private ObjectNode create(String specJson) throws Exception {
        ModelSpec spec = M.readValue(specJson, ModelSpec.class);
        ModelService svc = new ModelService(new ModelRegistry(), new InMemoryBlobStore());
        svc.createModel(spec);
        return svc.getState(spec.id(), null);
    }

    private ObjectNode createThenMutate(String specJson, Map<String, Object> given) throws Exception {
        ModelSpec spec = M.readValue(specJson, ModelSpec.class);
        ModelService svc = new ModelService(new ModelRegistry(), new InMemoryBlobStore());
        svc.createModel(spec);
        java.util.Map<String, JsonNode> muts = new java.util.HashMap<>();
        given.forEach((k, v) -> muts.put(k, M.valueToTree(v)));
        svc.mutate(spec.id(), muts);
        return svc.getState(spec.id(), null);
    }

    @Test
    void schema_default_seeds_a_field_and_feeds_a_derivation() throws Exception {
        String spec = """
            {"id":"sd1","version":"1.0.0",
             "schema":{"type":"object","properties":{
               "rate":{"type":"number","default":0.22},
               "gross":{"type":"number","default":100},
               "tax":{"type":"number","readOnly":true}}},
             "derivations":[{"path":"$.tax","expr":"gross * rate"}]}""";
        ObjectNode state = create(spec);
        assertThat(state.path("rate").asDouble()).isEqualTo(0.22);
        assertThat(state.path("gross").asDouble()).isEqualTo(100);
        assertThat(state.path("tax").asDouble()).isEqualTo(22.0); // derivation saw both defaults
    }

    @Test
    void defaultValues_take_precedence_over_schema_default() throws Exception {
        String spec = """
            {"id":"sd2","version":"1.0.0",
             "schema":{"type":"object","properties":{"rate":{"type":"number","default":0.22}}},
             "defaultValues":[{"path":"$","expr":"{ \\"rate\\": 0.99 }"}]}""";
        ObjectNode state = create(spec);
        assertThat(state.path("rate").asDouble()).as("defaultValues win over schema default").isEqualTo(0.99);
    }

    @Test
    void a_caller_mutation_overrides_the_schema_default() throws Exception {
        String spec = """
            {"id":"sd3","version":"1.0.0",
             "schema":{"type":"object","properties":{
               "rate":{"type":"number","default":0.22},
               "gross":{"type":"number"},
               "tax":{"type":"number","readOnly":true}}},
             "derivations":[{"path":"$.tax","expr":"gross * rate"}]}""";
        ObjectNode state = createThenMutate(spec, Map.of("$.gross", 200));
        assertThat(state.path("rate").asDouble()).isEqualTo(0.22);
        assertThat(state.path("tax").asDouble()).isEqualTo(44.0);
    }

    @Test
    void an_array_default_is_not_shared_across_models_of_the_same_spec() throws Exception {
        // Regression: the schema `default` node is owned by the (shared, per-spec) schema. Seeding it
        // by reference let an in-place element mutation on one model corrupt the default for every
        // other model built from the same spec. Each model must get its own deep copy.
        String json = """
            {"id":"sdarr","version":"1.0.0",
             "schema":{"type":"object","properties":{
               "board":{"type":"array","items":{"type":"string"},"default":["","",""]}}}}""";
        ModelSpec spec = M.readValue(json, ModelSpec.class);

        ModelService svc1 = new ModelService(new ModelRegistry(), new InMemoryBlobStore());
        svc1.createModel(spec);
        svc1.mutate(spec.id(), Map.of("$.board[0]", M.valueToTree("X")));
        assertThat(svc1.getState(spec.id(), null).path("board").toString()).isEqualTo("[\"X\",\"\",\"\"]");

        // A second, independent model from the same spec must start from a pristine default.
        ModelService svc2 = new ModelService(new ModelRegistry(), new InMemoryBlobStore());
        svc2.createModel(spec);
        assertThat(svc2.getState(spec.id(), null).path("board").toString())
                .as("second model's array default must not carry the first model's mutation")
                .isEqualTo("[\"\",\"\",\"\"]");
    }

    @Test
    void nested_object_defaults_are_seeded() throws Exception {
        String spec = """
            {"id":"sd4","version":"1.0.0",
             "schema":{"type":"object","properties":{
               "cfg":{"type":"object","properties":{"limit":{"type":"number","default":5}}}}}}""";
        ObjectNode state = create(spec);
        assertThat(state.at("/cfg/limit").asInt()).isEqualTo(5);
    }
}
