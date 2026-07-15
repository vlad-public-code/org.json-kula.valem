package org.json_kula.valem.api.persistence;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.json_kula.valem.core.model.ModelSpec;
import org.json_kula.valem.core.state.Snapshot;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class FilesystemModelStoreTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final JsonNodeFactory NF   = JsonNodeFactory.instance;

    @TempDir Path tempDir;

    private FilesystemModelStore store() {
        return new FilesystemModelStore(tempDir, MAPPER);
    }

    // ── isEnabled ─────────────────────────────────────────────────────────────

    @Test
    void is_enabled_returns_true() {
        assertThat(store().isEnabled()).isTrue();
    }

    // ── modelIds / save / load spec ───────────────────────────────────────────

    @Test
    void modelIds_empty_when_nothing_saved() throws Exception {
        assertThat(store().modelIds()).isEmpty();
    }

    @Test
    void saveSpec_and_loadSpec_round_trip() throws Exception {
        FilesystemModelStore s = store();
        ModelSpec spec = MAPPER.readValue("""
                { "id": "order", "schema": {} }
                """, ModelSpec.class);

        s.saveSpec("order", spec);

        Optional<ModelSpec> loaded = s.loadSpec("order");
        assertThat(loaded).isPresent();
        assertThat(loaded.get().id()).isEqualTo("order");
    }

    @Test
    void modelIds_lists_saved_specs() throws Exception {
        FilesystemModelStore s = store();
        ModelSpec spec = MAPPER.readValue("""
                { "id": "m1", "schema": {} }
                """, ModelSpec.class);
        s.saveSpec("m1", spec);

        assertThat(s.modelIds()).containsExactly("m1");
    }

    @Test
    void loadSpec_returns_empty_for_unknown_model() throws Exception {
        assertThat(store().loadSpec("nonexistent")).isEmpty();
    }

    // ── snapshot round-trip ───────────────────────────────────────────────────

    @Test
    void saveSnapshot_and_loadSnapshot_round_trip() throws Exception {
        FilesystemModelStore s = store();
        // Create a non-trivial snapshot
        ObjectNode baseDoc = NF.objectNode();
        baseDoc.put("qty", 5);
        Snapshot snap = new Snapshot(
                "order", "1.0.0",
                baseDoc,
                Map.of("$.total", NF.numberNode(100.0)),
                Map.of("$.qty#minimum", NF.numberNode(1.0)));

        s.saveSpec("order", MAPPER.readValue("""
                { "id": "order", "schema": {} }
                """, ModelSpec.class));
        s.saveSnapshot("order", snap);

        Optional<Snapshot> loaded = s.loadSnapshot("order");
        assertThat(loaded).isPresent();
        Snapshot restored = loaded.get();

        assertThat(restored.modelId()).isEqualTo("order");
        assertThat(restored.modelVersion()).isEqualTo("1.0.0");
        assertThat(restored.baseDoc().path("qty").asInt()).isEqualTo(5);
        assertThat(restored.derivedCache().get("$.total").asDouble()).isEqualTo(100.0);
        assertThat(restored.metaCache().get("$.qty#minimum").asDouble()).isEqualTo(1.0);
    }

    @Test
    void loadSnapshot_returns_empty_when_no_snapshot_saved() throws Exception {
        FilesystemModelStore s = store();
        s.saveSpec("m", MAPPER.readValue("""
                { "id": "m", "schema": {} }
                """, ModelSpec.class));

        assertThat(s.loadSnapshot("m")).isEmpty();
    }

    // ── delete ────────────────────────────────────────────────────────────────

    @Test
    void delete_removes_model_from_listing() throws Exception {
        FilesystemModelStore s = store();
        s.saveSpec("m", MAPPER.readValue("""
                { "id": "m", "schema": {} }
                """, ModelSpec.class));
        assertThat(s.modelIds()).containsExactly("m");

        s.delete("m");
        assertThat(s.modelIds()).isEmpty();
        assertThat(s.loadSpec("m")).isEmpty();
    }

    @Test
    void delete_is_idempotent_for_nonexistent_model() throws Exception {
        store().delete("nonexistent"); // must not throw
    }

    // ── overwrite ─────────────────────────────────────────────────────────────

    @Test
    void saveSpec_overwrites_previous_spec() throws Exception {
        FilesystemModelStore s = store();
        s.saveSpec("m", MAPPER.readValue("""
                { "id": "m", "schema": {}, "version": "1.0.0" }
                """, ModelSpec.class));
        s.saveSpec("m", MAPPER.readValue("""
                { "id": "m", "schema": {}, "version": "2.0.0" }
                """, ModelSpec.class));

        assertThat(s.loadSpec("m").get().version()).isEqualTo("2.0.0");
        assertThat(s.modelIds()).hasSize(1); // not duplicated
    }
}
