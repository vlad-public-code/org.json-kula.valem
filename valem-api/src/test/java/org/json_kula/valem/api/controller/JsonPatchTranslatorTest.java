package org.json_kula.valem.api.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.NullNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class JsonPatchTranslatorTest {

    private final ObjectMapper om = new ObjectMapper();

    private JsonNode ops(String json) throws Exception { return om.readTree(json); }
    private ObjectNode doc(String json) throws Exception { return (ObjectNode) om.readTree(json); }

    // ── replace ───────────────────────────────────────────────────────────────

    @Test
    void replace_top_level_scalar_emits_single_mutation() throws Exception {
        var mutations = JsonPatchTranslator.translate(
                ops("""
                        [{"op":"replace","path":"/price","value":9.99}]
                        """),
                doc("""
                        {"price":9.99}
                        """));

        assertThat(mutations).containsOnlyKeys("$.price");
        assertThat(mutations.get("$.price").asDouble()).isEqualTo(9.99);
    }

    @Test
    void replace_nested_leaf_emits_single_mutation() throws Exception {
        var mutations = JsonPatchTranslator.translate(
                ops("""
                        [{"op":"replace","path":"/items/0/price","value":2.5}]
                        """),
                doc("""
                        {"items":[{"name":"Apple","price":2.5}]}
                        """));

        assertThat(mutations).containsOnlyKeys("$.items[0].price");
        assertThat(mutations.get("$.items[0].price").asDouble()).isEqualTo(2.5);
    }

    // ── add ───────────────────────────────────────────────────────────────────

    @Test
    void add_append_emits_leaf_mutations_for_new_element_and_parent_array() throws Exception {
        // After patch: items has a new element "B" at index 1
        var mutations = JsonPatchTranslator.translate(
                ops("""
                        [{"op":"add","path":"/items/-","value":{"name":"B","qty":3}}]
                        """),
                doc("""
                        {"items":[{"name":"A","qty":1},{"name":"B","qty":3}]}
                        """));

        // Only new element (index 1) gets leaf mutations; existing element (index 0) does not
        assertThat(mutations).containsKey("$.items[1].name");
        assertThat(mutations).containsKey("$.items[1].qty");
        assertThat(mutations.get("$.items[1].name").asText()).isEqualTo("B");
        assertThat(mutations.get("$.items[1].qty").asInt()).isEqualTo(3);
        assertThat(mutations).doesNotContainKey("$.items[0].name");

        // Parent array included for structural update
        assertThat(mutations).containsKey("$.items");
        assertThat(mutations.get("$.items").size()).isEqualTo(2);
    }

    @Test
    void add_at_index_emits_leaves_for_inserted_and_all_shifted_elements() throws Exception {
        // After insert at index 1: [A, X, C]
        var mutations = JsonPatchTranslator.translate(
                ops("""
                        [{"op":"add","path":"/items/1","value":{"name":"X","qty":5}}]
                        """),
                doc("""
                        {"items":[{"name":"A","qty":1},{"name":"X","qty":5},{"name":"C","qty":2}]}
                        """));

        // Index 1 (new) and index 2 (shifted C) both get leaf mutations
        assertThat(mutations).containsKey("$.items[1].name");
        assertThat(mutations).containsKey("$.items[2].name");
        assertThat(mutations.get("$.items[1].name").asText()).isEqualTo("X");
        assertThat(mutations.get("$.items[2].name").asText()).isEqualTo("C");

        // Index 0 (unchanged A) is not re-emitted
        assertThat(mutations).doesNotContainKey("$.items[0].name");

        // Parent array included
        assertThat(mutations).containsKey("$.items");
        assertThat(mutations.get("$.items").size()).isEqualTo(3);
    }

    @Test
    void add_object_property_emits_single_mutation() throws Exception {
        var mutations = JsonPatchTranslator.translate(
                ops("""
                        [{"op":"add","path":"/note","value":"hello"}]
                        """),
                doc("""
                        {"note":"hello"}
                        """));

        assertThat(mutations).containsOnlyKeys("$.note");
        assertThat(mutations.get("$.note").asText()).isEqualTo("hello");
    }

    // ── remove ────────────────────────────────────────────────────────────────

    @Test
    void remove_array_element_emits_leaves_for_shifted_elements_and_parent() throws Exception {
        // Remove index 0 from [A, B]; B shifts to index 0 in patched doc
        var mutations = JsonPatchTranslator.translate(
                ops("""
                        [{"op":"remove","path":"/items/0"}]
                        """),
                doc("""
                        {"items":[{"name":"B","price":2.0}]}
                        """));

        assertThat(mutations).containsKey("$.items[0].name");
        assertThat(mutations.get("$.items[0].name").asText()).isEqualTo("B");
        assertThat(mutations).containsKey("$.items[0].price");
        assertThat(mutations).containsKey("$.items");
        assertThat(mutations.get("$.items").size()).isEqualTo(1);
    }

    @Test
    void remove_middle_element_emits_leaves_only_for_shifted_tail() throws Exception {
        // Remove index 1 from [A, B, C]; C shifts to index 1 in patched [A, C]
        var mutations = JsonPatchTranslator.translate(
                ops("""
                        [{"op":"remove","path":"/items/1"}]
                        """),
                doc("""
                        {"items":[{"name":"A","price":1.0},{"name":"C","price":3.0}]}
                        """));

        // Only index 1 (shifted C) gets leaf mutations; index 0 (unchanged A) does not
        assertThat(mutations).containsKey("$.items[1].name");
        assertThat(mutations.get("$.items[1].name").asText()).isEqualTo("C");
        assertThat(mutations).doesNotContainKey("$.items[0].name");
        assertThat(mutations).containsKey("$.items");
        assertThat(mutations.get("$.items").size()).isEqualTo(2);
    }

    @Test
    void remove_last_array_element_emits_only_parent_array_for_truncation() throws Exception {
        // Remove index 0 from single-element array; result is []
        var mutations = JsonPatchTranslator.translate(
                ops("""
                        [{"op":"remove","path":"/items/0"}]
                        """),
                doc("""
                        {"items":[]}
                        """));

        // No shifted elements to emit; only parent array needed for truncation
        assertThat(mutations).containsOnlyKeys("$.items");
        assertThat(mutations.get("$.items").isArray()).isTrue();
        assertThat(mutations.get("$.items").size()).isZero();
    }

    @Test
    void remove_object_property_emits_null_mutation() throws Exception {
        var mutations = JsonPatchTranslator.translate(
                ops("""
                        [{"op":"remove","path":"/note"}]
                        """),
                doc("{}"));

        assertThat(mutations).containsOnlyKeys("$.note");
        assertThat(mutations.get("$.note")).isInstanceOf(NullNode.class);
    }

    // ── test op ───────────────────────────────────────────────────────────────

    @Test
    void test_op_produces_no_mutations() throws Exception {
        var mutations = JsonPatchTranslator.translate(
                ops("""
                        [{"op":"test","path":"/price","value":1.0}]
                        """),
                doc("""
                        {"price":1.0}
                        """));

        assertThat(mutations).isEmpty();
    }

    // ── move ──────────────────────────────────────────────────────────────────

    @Test
    void move_between_array_positions_emits_full_array_for_both_source_and_dest() throws Exception {
        // Move item from /items/1 to /other/0; after patch both arrays are updated
        var mutations = JsonPatchTranslator.translate(
                ops("""
                        [{"op":"move","from":"/items/1","path":"/other/0"}]
                        """),
                doc("""
                        {"items":[{"name":"A"}],"other":[{"name":"B"}]}
                        """));

        // Both parent arrays are represented
        assertThat(mutations).containsKey("$.items");
        assertThat(mutations).containsKey("$.other");
    }

    // ── multiple ops ──────────────────────────────────────────────────────────

    @Test
    void multiple_replace_ops_are_all_captured() throws Exception {
        var mutations = JsonPatchTranslator.translate(
                ops("""
                        [
                          {"op":"replace","path":"/a","value":1},
                          {"op":"replace","path":"/b","value":2}
                        ]
                        """),
                doc("""
                        {"a":1,"b":2}
                        """));

        assertThat(mutations).containsKeys("$.a", "$.b");
        assertThat(mutations.get("$.a").asInt()).isEqualTo(1);
        assertThat(mutations.get("$.b").asInt()).isEqualTo(2);
    }

    @Test
    void empty_patch_produces_no_mutations() throws Exception {
        assertThat(JsonPatchTranslator.translate(ops("[]"), doc("{}"))).isEmpty();
    }

    @Test
    void test_and_replace_together_only_replace_produces_mutation() throws Exception {
        var mutations = JsonPatchTranslator.translate(
                ops("""
                        [
                          {"op":"test","path":"/x","value":1},
                          {"op":"replace","path":"/x","value":2}
                        ]
                        """),
                doc("""
                        {"x":2}
                        """));

        assertThat(mutations).containsOnlyKeys("$.x");
        assertThat(mutations.get("$.x").asInt()).isEqualTo(2);
    }
}
