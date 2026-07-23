package org.json_kula.valem.core.state;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import org.json_kula.valem.core.blob.InMemoryBlobStore;
import org.json_kula.valem.core.graph.CompiledModel;
import org.json_kula.valem.core.graph.ModelSpecCompiler;
import org.json_kula.valem.core.model.ModelSpec;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ModelStateTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private ModelState state;
    private CompiledModel model;

    @BeforeEach
    void setUp() throws Exception {
        String specJson = """
            {
              "id": "order-model",
              "schema": {},
              "derivations": [
                { "path": "$.order.total", "expr": "order.subtotal + order.tax" }
              ]
            }
            """;
        ModelSpec spec = MAPPER.readValue(specJson, ModelSpec.class);
        model = ModelSpecCompiler.compile(spec);
        state = new ModelState(model, new InMemoryBlobStore());
    }

    // ── Basic read/write ───────────────────────────────────────────────────────

    @Test
    void set_and_get_scalar_value() {
        state.setValue("$.order.subtotal", JsonNodeFactory.instance.numberNode(100.0));
        assertThat(state.getValue("$.order.subtotal").asDouble()).isEqualTo(100.0);
    }

    @Test
    void set_creates_intermediate_objects() {
        state.setValue("$.order.customer.name", JsonNodeFactory.instance.textNode("Alice"));
        assertThat(state.getValue("$.order.customer.name").asText()).isEqualTo("Alice");
    }

    @Test
    void get_missing_path_returns_missing_node() {
        assertThat(state.getValue("$.nonexistent.path").isMissingNode()).isTrue();
    }

    @Test
    void oversized_array_index_is_rejected_before_allocation() {
        // A single small mutation targeting a huge array index must not null-pad the array to that
        // size (audit SEC-1 / MEM-3) — it is rejected up front.
        assertThatThrownBy(() ->
                state.setValue("$.items[900000000]", JsonNodeFactory.instance.numberNode(1)))
                .isInstanceOf(StateLimitExceededException.class);
    }

    @Test
    void modest_array_index_still_pads_as_before() {
        state.setValue("$.items[3]", JsonNodeFactory.instance.textNode("x"));
        assertThat(state.getValue("$.items[3]").asText()).isEqualTo("x");
        assertThat(state.getValue("$.items[0]").isNull()).isTrue();
    }

    @Test
    void set_derived_field_throws() {
        assertThatThrownBy(() ->
                state.setValue("$.order.total", JsonNodeFactory.instance.numberNode(0)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("derived");
    }

    // ── Derived / meta cache ──────────────────────────────────────────────────

    @Test
    void derived_cache_takes_priority_over_base_doc() {
        state.setValue("$.order.subtotal", JsonNodeFactory.instance.numberNode(90.0));
        // Manually plant a derived value (as the evaluator would)
        state.setDerived("$.order.subtotal", JsonNodeFactory.instance.numberNode(999.0));
        assertThat(state.getValue("$.order.subtotal").asDouble()).isEqualTo(999.0);
    }

    @Test
    void set_and_get_meta_cache() {
        state.setMeta("$.order.downPayment#minimum", JsonNodeFactory.instance.numberNode(20.0));
        assertThat(state.getMeta("$.order.downPayment#minimum").asDouble()).isEqualTo(20.0);
    }

    // ── Dirty tracking ─────────────────────────────────────────────────────────

    @Test
    void setValue_marks_path_dirty() {
        state.setValue("$.order.subtotal", JsonNodeFactory.instance.numberNode(50.0));
        assertThat(state.dirtyPaths()).contains("$.order.subtotal");
    }

    @Test
    void clearDirty_empties_dirty_set() {
        state.setValue("$.order.subtotal", JsonNodeFactory.instance.numberNode(50.0));
        state.clearDirty();
        assertThat(state.dirtyPaths()).isEmpty();
    }

    // ── Array fields ───────────────────────────────────────────────────────────

    @Test
    void set_array_element_via_index() {
        state.setValue("$.order.items[0].qty", JsonNodeFactory.instance.numberNode(5));
        assertThat(state.getValue("$.order.items[0].qty").asInt()).isEqualTo(5);
    }

    // ── Snapshot / restore ─────────────────────────────────────────────────────

    @Test
    void snapshot_captures_current_state() {
        state.setValue("$.order.subtotal", JsonNodeFactory.instance.numberNode(100.0));
        Snapshot snap = state.snapshot();
        assertThat(snap.modelId()).isEqualTo("order-model");
        assertThat(snap.baseDoc().at("/order/subtotal").asDouble()).isEqualTo(100.0);
    }

    @Test
    void restore_reverts_to_snapshot() {
        state.setValue("$.order.subtotal", JsonNodeFactory.instance.numberNode(100.0));
        Snapshot snap = state.snapshot();

        state.setValue("$.order.subtotal", JsonNodeFactory.instance.numberNode(999.0));
        assertThat(state.getValue("$.order.subtotal").asDouble()).isEqualTo(999.0);

        state.restore(snap);
        assertThat(state.getValue("$.order.subtotal").asDouble()).isEqualTo(100.0);
        assertThat(state.dirtyPaths()).isEmpty();
    }

    @Test
    void restore_is_a_deep_copy() {
        state.setValue("$.order.subtotal", JsonNodeFactory.instance.numberNode(100.0));
        Snapshot snap = state.snapshot();
        // Modifying state after snapshot must not affect the snapshot
        state.setValue("$.order.subtotal", JsonNodeFactory.instance.numberNode(999.0));
        assertThat(snap.baseDoc().at("/order/subtotal").asDouble()).isEqualTo(100.0);
    }

    // ── Transaction ────────────────────────────────────────────────────────────

    @Test
    void rollback_restores_pre_transaction_state() {
        state.setValue("$.order.subtotal", JsonNodeFactory.instance.numberNode(100.0));
        state.beginTransaction();

        state.setValue("$.order.subtotal", JsonNodeFactory.instance.numberNode(999.0));
        assertThat(state.getValue("$.order.subtotal").asDouble()).isEqualTo(999.0);

        state.rollback();
        assertThat(state.getValue("$.order.subtotal").asDouble()).isEqualTo(100.0);
        assertThat(state.inTransaction()).isFalse();
    }

    @Test
    void commit_keeps_changes_and_closes_transaction() {
        state.setValue("$.order.subtotal", JsonNodeFactory.instance.numberNode(100.0));
        state.beginTransaction();
        state.setValue("$.order.subtotal", JsonNodeFactory.instance.numberNode(200.0));
        state.commit();

        assertThat(state.getValue("$.order.subtotal").asDouble()).isEqualTo(200.0);
        assertThat(state.inTransaction()).isFalse();
    }

    @Test
    void rollback_without_transaction_throws() {
        assertThatThrownBy(state::rollback).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void commit_without_transaction_throws() {
        assertThatThrownBy(state::commit).isInstanceOf(IllegalStateException.class);
    }

    // ── Blob lifecycle ─────────────────────────────────────────────────────────

    @Test
    void storeBlob_writes_blobRef_to_state() throws Exception {
        byte[] data = "image data".getBytes();
        var ref = state.storeBlob("$.user.avatar", new ByteArrayInputStream(data), "image/png");

        assertThat(ref.mediaType()).isEqualTo("image/png");
        assertThat(ref.bytes()).isEqualTo(data.length);
        // The state field should now hold the BlobRef JSON
        var stored = state.getValue("$.user.avatar");
        assertThat(stored.has("$blobId")).isTrue();
    }

    // ── TrackedJsonNode integration ────────────────────────────────────────────

    @Test
    void asRoot_navigates_base_doc() {
        state.setValue("$.order.subtotal", JsonNodeFactory.instance.numberNode(42.0));
        var root = state.asRoot();
        assertThat(root.get("order").get("subtotal").asDouble()).isEqualTo(42.0);
    }

    // ── Reading inside a derived container ──────────────────────────────────────
    //
    // A whole derived array is cached under one key, so an indexed sub-path is neither an exact
    // cache hit nor present in the base doc. getValue must still resolve it — otherwise a reader
    // like TestCaseRunner or explain sees a derived array as unindexable while mergedDocument (what
    // the UI reads) shows it correctly, and the two disagree. This is what made the car-loan and
    // savings-growth examples fail their own $.schedule[0].* / $.projection[N].* self-tests.

    @Test
    void getValue_indexes_into_a_derived_array() {
        var arr = MAPPER.createArrayNode();
        arr.add(MAPPER.createObjectNode().put("month", 1).put("interest", 100.0));
        arr.add(MAPPER.createObjectNode().put("month", 2).put("interest", 90.0));
        state.setDerived("$.schedule", arr);

        assertThat(state.getValue("$.schedule[0].interest").asDouble()).isEqualTo(100.0);
        assertThat(state.getValue("$.schedule[1].month").asInt()).isEqualTo(2);
        assertThat(state.getValue("$.schedule").isArray()).isTrue();
    }

    @Test
    void getValue_indexes_into_a_derived_array_of_scalars() {
        var arr = MAPPER.createArrayNode();
        arr.add(10);
        arr.add(20);
        state.setDerived("$.projection", arr);

        assertThat(state.getValue("$.projection[1]").asInt()).isEqualTo(20);
    }

    @Test
    void getValue_returns_missing_for_an_out_of_range_derived_index() {
        var arr = MAPPER.createArrayNode();
        arr.add(MAPPER.createObjectNode().put("x", 1));
        state.setDerived("$.schedule", arr);

        assertThat(state.getValue("$.schedule[5].x").isMissingNode()).isTrue();
        assertThat(state.getValue("$.schedule[0].nope").isMissingNode()).isTrue();
    }

    @Test
    void getValue_prefers_an_exact_derived_hit_over_navigating_an_ancestor() {
        // A wildcard element derivation caches each element under its own concrete key; that exact
        // key must win rather than being recomputed by navigating a coarser ancestor.
        var arr = MAPPER.createArrayNode();
        arr.add(MAPPER.createObjectNode().put("lineTotal", 5));
        state.setDerived("$.items", arr);
        state.setDerived("$.items[0].lineTotal", JsonNodeFactory.instance.numberNode(999));

        assertThat(state.getValue("$.items[0].lineTotal").asInt()).isEqualTo(999);
    }

    @Test
    void getValue_still_misses_cleanly_when_no_derived_ancestor_exists() {
        assertThat(state.getValue("$.nothing.here[0]").isMissingNode()).isTrue();
    }
}
