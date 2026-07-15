package org.json_kula.valem.persistence;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class MutationLogReplayTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static ArrayNode patch(String json) throws Exception {
        return (ArrayNode) MAPPER.readTree(json);
    }

    @Test
    void add_creates_intermediate_objects() throws Exception {
        ObjectNode doc = MAPPER.createObjectNode();
        MutationLogReplay.apply(doc, patch(
                "[{\"op\":\"add\",\"path\":\"/order/customer/name\",\"value\":\"Ada\"}]"));
        assertThat(doc.at("/order/customer/name").asText()).isEqualTo("Ada");
    }

    @Test
    void add_creates_intermediate_arrays_for_numeric_segments() throws Exception {
        ObjectNode doc = MAPPER.createObjectNode();
        MutationLogReplay.apply(doc, patch(
                "[{\"op\":\"add\",\"path\":\"/items/0/price\",\"value\":5}]"));
        assertThat(doc.at("/items").isArray()).isTrue();
        assertThat(doc.at("/items/0/price").asInt()).isEqualTo(5);
    }

    @Test
    void replace_behaves_like_add() throws Exception {
        ObjectNode doc = MAPPER.createObjectNode();
        doc.put("qty", 1);
        MutationLogReplay.apply(doc, patch("[{\"op\":\"replace\",\"path\":\"/qty\",\"value\":42}]"));
        assertThat(doc.path("qty").asInt()).isEqualTo(42);
    }

    @Test
    void ops_are_applied_in_order() throws Exception {
        ObjectNode doc = MAPPER.createObjectNode();
        MutationLogReplay.apply(doc, patch(
                "[{\"op\":\"add\",\"path\":\"/x\",\"value\":1},"
                + "{\"op\":\"add\",\"path\":\"/x\",\"value\":2}]"));
        assertThat(doc.path("x").asInt()).isEqualTo(2);
    }

    @Test
    void remove_deletes_existing_leaf() throws Exception {
        ObjectNode doc = MAPPER.createObjectNode();
        doc.put("a", 1);
        doc.put("b", 2);
        MutationLogReplay.apply(doc, patch("[{\"op\":\"remove\",\"path\":\"/a\"}]"));
        assertThat(doc.has("a")).isFalse();
        assertThat(doc.path("b").asInt()).isEqualTo(2);
    }

    @Test
    void remove_of_absent_path_is_a_no_op() throws Exception {
        ObjectNode doc = MAPPER.createObjectNode();
        doc.put("keep", 1);
        MutationLogReplay.apply(doc, patch("[{\"op\":\"remove\",\"path\":\"/nope/missing\"}]"));
        assertThat(doc.path("keep").asInt()).isEqualTo(1);
    }

    @Test
    void unsupported_op_is_skipped_not_thrown() throws Exception {
        ObjectNode doc = MAPPER.createObjectNode();
        doc.put("x", 1);
        // "move" is never emitted by Valem — it must be skipped, not throw, and not corrupt state.
        MutationLogReplay.apply(doc, patch(
                "[{\"op\":\"move\",\"from\":\"/x\",\"path\":\"/y\"},"
                + "{\"op\":\"add\",\"path\":\"/z\",\"value\":9}]"));
        assertThat(doc.path("x").asInt()).isEqualTo(1);
        assertThat(doc.path("z").asInt()).isEqualTo(9);
    }

    @Test
    void null_or_non_array_patch_is_a_no_op() {
        ObjectNode doc = MAPPER.createObjectNode();
        doc.put("x", 1);
        assertThat(MutationLogReplay.apply(doc, null).path("x").asInt()).isEqualTo(1);
        assertThat(MutationLogReplay.apply(doc, MAPPER.createObjectNode()).path("x").asInt()).isEqualTo(1);
    }
}
