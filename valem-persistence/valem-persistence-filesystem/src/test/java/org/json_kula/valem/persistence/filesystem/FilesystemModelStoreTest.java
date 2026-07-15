package org.json_kula.valem.persistence.filesystem;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.json_kula.valem.core.model.ModelSpec;
import org.json_kula.valem.core.state.Snapshot;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class FilesystemModelStoreTest {

    private static final ObjectMapper     MAPPER = new ObjectMapper();
    private static final JsonNodeFactory  NF     = JsonNodeFactory.instance;

    @TempDir Path tempDir;

    private FilesystemModelStore store() {
        return new FilesystemModelStore(tempDir, MAPPER);
    }

    private FilesystemModelStore store(int threshold) {
        return new FilesystemModelStore(tempDir, MAPPER, threshold);
    }

    // ── isEnabled ─────────────────────────────────────────────────────────────

    @Test
    void is_enabled_returns_true() {
        assertThat(store().isEnabled()).isTrue();
    }

    // ── spec round-trip ───────────────────────────────────────────────────────

    @Test
    void saveSpec_and_loadSpec_round_trip() throws Exception {
        FilesystemModelStore s = store();
        ModelSpec spec = spec("order");
        s.saveSpec("order", spec);
        assertThat(s.loadSpec("order")).isPresent()
                .map(ModelSpec::id).hasValue("order");
    }

    @Test
    void loadSpec_returns_empty_for_unknown_model() throws Exception {
        assertThat(store().loadSpec("nonexistent")).isEmpty();
    }

    @Test
    void modelIds_lists_only_models_with_saved_specs() throws Exception {
        FilesystemModelStore s = store();
        s.saveSpec("a", spec("a"));
        s.saveSpec("b", spec("b"));
        assertThat(s.modelIds()).containsExactly("a", "b");
    }

    @Test
    void saveSpec_overwrites_previous() throws Exception {
        FilesystemModelStore s = store();
        s.saveSpec("m", MAPPER.readValue("{\"id\":\"m\",\"schema\":{},\"version\":\"1.0\"}", ModelSpec.class));
        s.saveSpec("m", MAPPER.readValue("{\"id\":\"m\",\"schema\":{},\"version\":\"2.0\"}", ModelSpec.class));
        assertThat(s.loadSpec("m").get().version()).isEqualTo("2.0");
        assertThat(s.modelIds()).hasSize(1);
    }

    // ── snapshot round-trip ───────────────────────────────────────────────────

    @Test
    void saveSnapshot_and_loadSnapshot_round_trip() throws Exception {
        FilesystemModelStore s = store();
        s.saveSpec("order", spec("order"));
        ObjectNode baseDoc = NF.objectNode();
        baseDoc.put("qty", 5);
        Snapshot snap = new Snapshot("order", "1.0.0", baseDoc,
                Map.of("$.total", NF.numberNode(100.0)),
                Map.of("$.qty#minimum", NF.numberNode(1.0)));
        s.saveSnapshot("order", snap);

        Optional<Snapshot> loaded = s.loadSnapshot("order");
        assertThat(loaded).isPresent();
        assertThat(loaded.get().baseDoc().path("qty").asInt()).isEqualTo(5);
        assertThat(loaded.get().derivedCache().get("$.total").asDouble()).isEqualTo(100.0);
    }

    @Test
    void loadSnapshot_returns_empty_when_no_snapshot_saved() throws Exception {
        FilesystemModelStore s = store();
        s.saveSpec("m", spec("m"));
        assertThat(s.loadSnapshot("m")).isEmpty();
    }

    // ── incremental mutation log ──────────────────────────────────────────────

    @Test
    void applyMutationPatch_then_loadSnapshot_returns_patched_state() throws Exception {
        FilesystemModelStore s = store();
        s.saveSpec("m", spec("m"));
        ObjectNode baseDoc = NF.objectNode();
        baseDoc.put("qty", 1);
        s.saveSnapshot("m", new Snapshot("m", "1.0.0", baseDoc, Map.of(), Map.of()));

        // Patch: set qty to 42
        s.applyMutationPatch("m",
                MAPPER.readValue("[{\"op\":\"add\",\"path\":\"/qty\",\"value\":42}]",
                        com.fasterxml.jackson.databind.node.ArrayNode.class),
                Instant.now());

        Optional<Snapshot> loaded = s.loadSnapshot("m");
        assertThat(loaded).isPresent();
        assertThat(loaded.get().baseDoc().path("qty").asInt()).isEqualTo(42);
    }

    @Test
    void multiple_patches_are_applied_in_order() throws Exception {
        FilesystemModelStore s = store();
        s.saveSpec("m", spec("m"));
        ObjectNode baseDoc = NF.objectNode();
        baseDoc.put("x", 0);
        s.saveSnapshot("m", new Snapshot("m", "1.0.0", baseDoc, Map.of(), Map.of()));

        s.applyMutationPatch("m",
                MAPPER.readValue("[{\"op\":\"add\",\"path\":\"/x\",\"value\":1}]",
                        com.fasterxml.jackson.databind.node.ArrayNode.class),
                Instant.now());
        s.applyMutationPatch("m",
                MAPPER.readValue("[{\"op\":\"add\",\"path\":\"/x\",\"value\":2}]",
                        com.fasterxml.jackson.databind.node.ArrayNode.class),
                Instant.now());

        assertThat(s.loadSnapshot("m").get().baseDoc().path("x").asInt()).isEqualTo(2);
    }

    @Test
    void saveSnapshot_clears_mutation_log() throws Exception {
        FilesystemModelStore s = store();
        s.saveSpec("m", spec("m"));
        ObjectNode baseDoc = NF.objectNode();
        baseDoc.put("x", 0);
        s.saveSnapshot("m", new Snapshot("m", "1.0.0", baseDoc, Map.of(), Map.of()));

        s.applyMutationPatch("m",
                MAPPER.readValue("[{\"op\":\"add\",\"path\":\"/x\",\"value\":99}]",
                        com.fasterxml.jackson.databind.node.ArrayNode.class),
                Instant.now());

        // Explicit full snapshot (e.g. POST /snapshot) clears the log
        ObjectNode newDoc = NF.objectNode();
        newDoc.put("x", 7);
        s.saveSnapshot("m", new Snapshot("m", "1.0.0", newDoc, Map.of(), Map.of()));

        Path mutFile = tempDir.resolve("m/mutations.jsonl");
        assertThat(Files.size(mutFile)).isEqualTo(0);
        assertThat(s.loadSnapshot("m").get().baseDoc().path("x").asInt()).isEqualTo(7);
    }

    @Test
    void compaction_fires_when_threshold_exceeded_and_log_is_truncated() throws Exception {
        // Use threshold=2 so a 3rd patch triggers compaction
        FilesystemModelStore s = store(2);
        s.saveSpec("m", spec("m"));
        ObjectNode baseDoc = NF.objectNode();
        baseDoc.put("n", 0);
        s.saveSnapshot("m", new Snapshot("m", "1.0.0", baseDoc, Map.of(), Map.of()));

        for (int i = 1; i <= 3; i++) {
            s.applyMutationPatch("m",
                    MAPPER.readValue("[{\"op\":\"add\",\"path\":\"/n\",\"value\":" + i + "}]",
                            com.fasterxml.jackson.databind.node.ArrayNode.class),
                    Instant.now());
        }

        // After compaction, log should be empty and state correct
        Path mutFile = tempDir.resolve("m/mutations.jsonl");
        assertThat(Files.size(mutFile)).isEqualTo(0);
        assertThat(s.loadSnapshot("m").get().baseDoc().path("n").asInt()).isEqualTo(3);
    }

    @Test
    void mutations_after_compaction_are_preserved() throws Exception {
        // F-T7: compaction removes only the merged prefix, so mutations appended afterwards still
        // replay onto the new baseline. threshold=2 → the 3rd patch triggers compaction.
        FilesystemModelStore s = store(2);
        s.saveSpec("m", spec("m"));
        ObjectNode baseDoc = NF.objectNode();
        baseDoc.put("n", 0);
        s.saveSnapshot("m", new Snapshot("m", "1.0.0", baseDoc, Map.of(), Map.of()));

        for (int i = 1; i <= 3; i++) {
            s.applyMutationPatch("m",
                    MAPPER.readValue("[{\"op\":\"add\",\"path\":\"/n\",\"value\":" + i + "}]",
                            com.fasterxml.jackson.databind.node.ArrayNode.class),
                    Instant.now());
        }
        // Two more mutations after compaction has fired.
        s.applyMutationPatch("m",
                MAPPER.readValue("[{\"op\":\"add\",\"path\":\"/n\",\"value\":4}]",
                        com.fasterxml.jackson.databind.node.ArrayNode.class),
                Instant.now());
        s.applyMutationPatch("m",
                MAPPER.readValue("[{\"op\":\"add\",\"path\":\"/n\",\"value\":5}]",
                        com.fasterxml.jackson.databind.node.ArrayNode.class),
                Instant.now());

        FilesystemModelStore restarted = new FilesystemModelStore(tempDir, MAPPER, 2);
        assertThat(restarted.loadSnapshot("m").get().baseDoc().path("n").asInt()).isEqualTo(5);
    }

    @Test
    void load_after_crash_before_compaction_still_correct() throws Exception {
        // threshold=2, write 2 patches (no compaction yet), then reload without compacting
        FilesystemModelStore s = store(5);
        s.saveSpec("m", spec("m"));
        ObjectNode baseDoc = NF.objectNode();
        baseDoc.put("v", 0);
        s.saveSnapshot("m", new Snapshot("m", "1.0.0", baseDoc, Map.of(), Map.of()));

        s.applyMutationPatch("m",
                MAPPER.readValue("[{\"op\":\"add\",\"path\":\"/v\",\"value\":10}]",
                        com.fasterxml.jackson.databind.node.ArrayNode.class),
                Instant.now());
        s.applyMutationPatch("m",
                MAPPER.readValue("[{\"op\":\"add\",\"path\":\"/v\",\"value\":20}]",
                        com.fasterxml.jackson.databind.node.ArrayNode.class),
                Instant.now());

        // Simulate restart: new store instance pointing at same directory
        FilesystemModelStore restarted = new FilesystemModelStore(tempDir, MAPPER, 5);
        assertThat(restarted.loadSnapshot("m").get().baseDoc().path("v").asInt()).isEqualTo(20);
    }

    // ── F-T5: create-as-you-go replay of nested/array-creating mutations ─────────

    @Test
    void replay_reconstructs_nested_object_path_absent_from_baseline() throws Exception {
        FilesystemModelStore s = store();
        s.saveSpec("m", spec("m"));
        // Baseline has no "order" object at all.
        s.saveSnapshot("m", new Snapshot("m", "1.0.0", NF.objectNode(), Map.of(), Map.of()));

        // A live mutation $.order.customer.name = "Ada" auto-creates the nested objects; the
        // persisted RFC 6902 "add /order/customer/name" must reconstruct them on replay rather
        // than being silently dropped for a missing parent.
        s.applyMutationPatch("m",
                MAPPER.readValue("[{\"op\":\"add\",\"path\":\"/order/customer/name\",\"value\":\"Ada\"}]",
                        com.fasterxml.jackson.databind.node.ArrayNode.class),
                Instant.now());

        Optional<Snapshot> loaded = s.loadSnapshot("m");
        assertThat(loaded).isPresent();
        assertThat(loaded.get().baseDoc().at("/order/customer/name").asText()).isEqualTo("Ada");
    }

    @Test
    void replay_reconstructs_new_array_element_absent_from_baseline() throws Exception {
        FilesystemModelStore s = store();
        s.saveSpec("m", spec("m"));
        s.saveSnapshot("m", new Snapshot("m", "1.0.0", NF.objectNode(), Map.of(), Map.of()));

        // $.items[0].price = 5 — creates the array and the element object on replay.
        s.applyMutationPatch("m",
                MAPPER.readValue("[{\"op\":\"add\",\"path\":\"/items/0/price\",\"value\":5}]",
                        com.fasterxml.jackson.databind.node.ArrayNode.class),
                Instant.now());
        // A second mutation adds a second element.
        s.applyMutationPatch("m",
                MAPPER.readValue("[{\"op\":\"add\",\"path\":\"/items/1/price\",\"value\":7}]",
                        com.fasterxml.jackson.databind.node.ArrayNode.class),
                Instant.now());

        Snapshot loaded = s.loadSnapshot("m").orElseThrow();
        assertThat(loaded.baseDoc().at("/items").size()).isEqualTo(2);
        assertThat(loaded.baseDoc().at("/items/0/price").asInt()).isEqualTo(5);
        assertThat(loaded.baseDoc().at("/items/1/price").asInt()).isEqualTo(7);
    }

    @Test
    void replay_remove_of_absent_path_is_a_no_op() throws Exception {
        FilesystemModelStore s = store();
        s.saveSpec("m", spec("m"));
        ObjectNode baseDoc = NF.objectNode();
        baseDoc.put("keep", 1);
        s.saveSnapshot("m", new Snapshot("m", "1.0.0", baseDoc, Map.of(), Map.of()));

        // remove of a path that never existed must not throw and must not lose "keep".
        s.applyMutationPatch("m",
                MAPPER.readValue("[{\"op\":\"remove\",\"path\":\"/nope/missing\"}]",
                        com.fasterxml.jackson.databind.node.ArrayNode.class),
                Instant.now());

        Snapshot loaded = s.loadSnapshot("m").orElseThrow();
        assertThat(loaded.baseDoc().path("keep").asInt()).isEqualTo(1);
    }

    // ── delete ────────────────────────────────────────────────────────────────

    @Test
    void delete_removes_spec_snapshot_and_mutations() throws Exception {
        FilesystemModelStore s = store();
        s.saveSpec("m", spec("m"));
        ObjectNode baseDoc = NF.objectNode();
        s.saveSnapshot("m", new Snapshot("m", "1.0.0", baseDoc, Map.of(), Map.of()));
        s.applyMutationPatch("m",
                MAPPER.readValue("[{\"op\":\"add\",\"path\":\"/x\",\"value\":1}]",
                        com.fasterxml.jackson.databind.node.ArrayNode.class),
                Instant.now());

        s.delete("m");

        assertThat(s.modelIds()).isEmpty();
        assertThat(s.loadSpec("m")).isEmpty();
        assertThat(s.loadSnapshot("m")).isEmpty();
    }

    @Test
    void delete_is_idempotent_for_nonexistent_model() throws Exception {
        store().delete("nonexistent"); // must not throw
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private static ModelSpec spec(String id) throws Exception {
        return MAPPER.readValue("{\"id\":\"" + id + "\",\"schema\":{}}", ModelSpec.class);
    }
}
