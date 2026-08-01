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

import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * End-to-end regression for the descendant→ancestor dirty-propagation fix (DirtyPropagator).
 *
 * <p>A derivation that aggregates a whole array — {@code $count(arr[$ != ""])} — extracts a dependency
 * on the bare array path {@code $.arr}, not the wildcard {@code $.arr[*]}. Before the fix, mutating an
 * element by index ({@code $.arr[0]}) built the array but left the aggregate stale (0); only a whole-
 * array write refreshed it. After the fix, element-index writes correctly re-evaluate the aggregate.
 */
class ArrayIndexMutationTest {

    private static final ObjectMapper M = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    private static final String SPEC = """
        {"id":"arr-index-repro","version":"1.0.0",
         "schema":{"type":"object","properties":{
           "arr":{"type":"array","items":{"type":"string"}},
           "count":{"type":"number","readOnly":true},
           "joined":{"type":"string","readOnly":true}}},
         "derivations":[
           {"path":"$.count","expr":"$count(arr[$ != \\"\\"])"},
           {"path":"$.joined","expr":"$join(arr[$ != \\"\\"], \\"-\\")"}
         ]}""";

    private ObjectNode createAndMutate(Map<String, Object> given) throws Exception {
        ModelSpec spec = M.readValue(SPEC, ModelSpec.class);
        ModelService svc = new ModelService(new ModelRegistry(), new InMemoryBlobStore());
        svc.createModel(spec);
        Map<String, JsonNode> muts = new LinkedHashMap<>();
        given.forEach((k, v) -> muts.put(k, M.valueToTree(v)));
        svc.mutate(spec.id(), muts);
        return svc.getState(spec.id(), null);
    }

    @Test
    void element_index_mutations_refresh_a_whole_array_aggregate() throws Exception {
        Map<String, Object> given = new LinkedHashMap<>();
        given.put("$.arr[0]", "a");
        given.put("$.arr[1]", "b");
        ObjectNode state = createAndMutate(given);

        assertThat(state.path("arr").toString()).isEqualTo("[\"a\",\"b\"]");
        assertThat(state.path("count").asInt()).as("aggregate recomputed after index writes").isEqualTo(2);
        assertThat(state.path("joined").asText()).isEqualTo("a-b");
    }

    @Test
    void whole_array_mutation_still_works() throws Exception {
        ObjectNode state = createAndMutate(Map.of("$.arr", java.util.List.of("a", "b", "c")));
        assertThat(state.path("count").asInt()).isEqualTo(3);
        assertThat(state.path("joined").asText()).isEqualTo("a-b-c");
    }

    @Test
    void separate_index_mutations_across_two_calls_stay_consistent() throws Exception {
        ModelSpec spec = M.readValue(SPEC, ModelSpec.class);
        ModelService svc = new ModelService(new ModelRegistry(), new InMemoryBlobStore());
        svc.createModel(spec);

        svc.mutate(spec.id(), Map.of("$.arr[0]", M.valueToTree("a")));
        assertThat(svc.getState(spec.id(), null).path("count").asInt()).isEqualTo(1);

        svc.mutate(spec.id(), Map.of("$.arr[1]", M.valueToTree("b")));
        assertThat(svc.getState(spec.id(), null).path("count").asInt()).isEqualTo(2);
    }
}
