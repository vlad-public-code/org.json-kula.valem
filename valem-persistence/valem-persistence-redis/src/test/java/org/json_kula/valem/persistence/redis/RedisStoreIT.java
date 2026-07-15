package org.json_kula.valem.persistence.redis;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.github.fppt.jedismock.RedisServer;
import io.lettuce.core.RedisClient;
import io.lettuce.core.api.StatefulRedisConnection;
import org.json_kula.valem.core.model.ModelSpec;
import org.json_kula.valem.core.state.Snapshot;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class RedisStoreIT {

    private static final ObjectMapper    MAPPER = new ObjectMapper();
    private static final JsonNodeFactory NF     = JsonNodeFactory.instance;

    private static RedisServer                           server;
    private static RedisClient                           client;
    private static StatefulRedisConnection<String, String> conn;

    private RedisSpecStore  specStore;
    private RedisStateStore stateStore;
    private RedisModelStore store;

    @BeforeAll
    static void startServer() throws Exception {
        server = RedisServer.newRedisServer().start();
        client = RedisClient.create("redis://localhost:" + server.getBindPort());
        conn   = client.connect();
    }

    @AfterAll
    static void stopServer() throws Exception {
        conn.close();
        client.shutdown();
        server.stop();
    }

    @BeforeEach
    void setUp() {
        conn.sync().flushall();
        specStore  = new RedisSpecStore(conn, MAPPER);
        stateStore = new RedisStateStore(conn, MAPPER, 100);
        store      = new RedisModelStore(specStore, stateStore);
    }

    // ── isEnabled ─────────────────────────────────────────────────────────────

    @Test
    void is_enabled_returns_true() {
        assertThat(store.isEnabled()).isTrue();
    }

    // ── spec round-trip ───────────────────────────────────────────────────────

    @Test
    void saveSpec_and_loadSpec_round_trip() throws Exception {
        store.saveSpec("order", spec("order"));
        assertThat(store.loadSpec("order")).isPresent()
                .map(ModelSpec::id).hasValue("order");
    }

    @Test
    void loadSpec_returns_empty_for_unknown() throws Exception {
        assertThat(store.loadSpec("nonexistent")).isEmpty();
    }

    @Test
    void modelIds_lists_saved_models_alphabetically() throws Exception {
        store.saveSpec("b", spec("b"));
        store.saveSpec("a", spec("a"));
        assertThat(store.modelIds()).containsExactly("a", "b");
    }

    @Test
    void saveSpec_overwrites_previous() throws Exception {
        store.saveSpec("m", MAPPER.readValue("{\"id\":\"m\",\"schema\":{},\"version\":\"1.0\"}", ModelSpec.class));
        store.saveSpec("m", MAPPER.readValue("{\"id\":\"m\",\"schema\":{},\"version\":\"2.0\"}", ModelSpec.class));
        assertThat(store.loadSpec("m").get().version()).isEqualTo("2.0");
        assertThat(store.modelIds()).hasSize(1);
    }

    // ── snapshot round-trip ───────────────────────────────────────────────────

    @Test
    void saveSnapshot_and_loadSnapshot_round_trip() throws Exception {
        ObjectNode baseDoc = NF.objectNode();
        baseDoc.put("qty", 5);
        store.saveSnapshot("order", new Snapshot("order", "1.0.0", baseDoc,
                Map.of("$.total", NF.numberNode(100.0)), Map.of()));

        Optional<Snapshot> loaded = store.loadSnapshot("order");
        assertThat(loaded).isPresent();
        assertThat(loaded.get().baseDoc().path("qty").asInt()).isEqualTo(5);
        assertThat(loaded.get().derivedCache().get("$.total").asDouble()).isEqualTo(100.0);
    }

    @Test
    void loadSnapshot_returns_empty_when_none_saved() throws Exception {
        assertThat(store.loadSnapshot("m")).isEmpty();
    }

    // ── incremental mutation log ──────────────────────────────────────────────

    @Test
    void applyMutationPatch_then_loadSnapshot_returns_patched_state() throws Exception {
        ObjectNode baseDoc = NF.objectNode();
        baseDoc.put("qty", 1);
        store.saveSnapshot("m", new Snapshot("m", "1.0.0", baseDoc, Map.of(), Map.of()));

        store.applyMutationPatch("m", patch("[{\"op\":\"add\",\"path\":\"/qty\",\"value\":42}]"),
                Instant.now());

        assertThat(store.loadSnapshot("m").get().baseDoc().path("qty").asInt()).isEqualTo(42);
    }

    @Test
    void multiple_patches_applied_in_order() throws Exception {
        ObjectNode baseDoc = NF.objectNode();
        baseDoc.put("x", 0);
        store.saveSnapshot("m", new Snapshot("m", "1.0.0", baseDoc, Map.of(), Map.of()));

        store.applyMutationPatch("m", patch("[{\"op\":\"add\",\"path\":\"/x\",\"value\":1}]"), Instant.now());
        store.applyMutationPatch("m", patch("[{\"op\":\"add\",\"path\":\"/x\",\"value\":2}]"), Instant.now());

        assertThat(store.loadSnapshot("m").get().baseDoc().path("x").asInt()).isEqualTo(2);
    }

    @Test
    void saveSnapshot_clears_mutation_log() throws Exception {
        ObjectNode baseDoc = NF.objectNode();
        baseDoc.put("x", 0);
        store.saveSnapshot("m", new Snapshot("m", "1.0.0", baseDoc, Map.of(), Map.of()));
        store.applyMutationPatch("m", patch("[{\"op\":\"add\",\"path\":\"/x\",\"value\":99}]"), Instant.now());

        ObjectNode newDoc = NF.objectNode();
        newDoc.put("x", 7);
        store.saveSnapshot("m", new Snapshot("m", "1.0.0", newDoc, Map.of(), Map.of()));

        assertThat(conn.sync().llen(RedisStateStore.KEY_PREFIX_MUTATIONS + "m")).isEqualTo(0);
        assertThat(store.loadSnapshot("m").get().baseDoc().path("x").asInt()).isEqualTo(7);
    }

    @Test
    void compaction_fires_when_threshold_exceeded() throws Exception {
        RedisStateStore lowThreshold = new RedisStateStore(conn, MAPPER, 2);
        RedisModelStore lowStore     = new RedisModelStore(specStore, lowThreshold);

        ObjectNode baseDoc = NF.objectNode();
        baseDoc.put("n", 0);
        lowStore.saveSnapshot("m", new Snapshot("m", "1.0.0", baseDoc, Map.of(), Map.of()));

        for (int i = 1; i <= 3; i++) {
            lowStore.applyMutationPatch("m",
                    patch("[{\"op\":\"add\",\"path\":\"/n\",\"value\":" + i + "}]"),
                    Instant.now());
        }

        assertThat(conn.sync().llen(RedisStateStore.KEY_PREFIX_MUTATIONS + "m")).isEqualTo(0);
        assertThat(lowStore.loadSnapshot("m").get().baseDoc().path("n").asInt()).isEqualTo(3);
    }

    // ── delete ────────────────────────────────────────────────────────────────

    @Test
    void delete_removes_spec_and_state() throws Exception {
        store.saveSpec("m", spec("m"));
        ObjectNode baseDoc = NF.objectNode();
        store.saveSnapshot("m", new Snapshot("m", "1.0.0", baseDoc, Map.of(), Map.of()));
        store.applyMutationPatch("m", patch("[{\"op\":\"add\",\"path\":\"/x\",\"value\":1}]"), Instant.now());

        store.delete("m");

        assertThat(store.modelIds()).isEmpty();
        assertThat(store.loadSpec("m")).isEmpty();
        assertThat(store.loadSnapshot("m")).isEmpty();
    }

    @Test
    void delete_is_idempotent_for_nonexistent_model() throws Exception {
        store.delete("nonexistent");
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private static ModelSpec spec(String id) throws Exception {
        return MAPPER.readValue("{\"id\":\"" + id + "\",\"schema\":{}}", ModelSpec.class);
    }

    private static ArrayNode patch(String json) throws Exception {
        return MAPPER.readValue(json, ArrayNode.class);
    }
}
