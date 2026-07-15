package org.json_kula.valem.persistence.mongo;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.Updates;
import de.bwaldvogel.mongo.MongoServer;
import de.bwaldvogel.mongo.backend.memory.MemoryBackend;
import org.bson.Document;
import org.json_kula.valem.core.engine.DerivationTrace;
import org.json_kula.valem.core.model.ModelSpec;
import org.json_kula.valem.core.state.Snapshot;
import org.json_kula.valem.persistence.audit.AuditQuery;
import org.json_kula.valem.persistence.audit.AuditRecord;
import org.json_kula.valem.persistence.audit.AuditVerification;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.net.InetSocketAddress;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class MongoStoreIT {

    private static final ObjectMapper    MAPPER = new ObjectMapper();
    private static final JsonNodeFactory NF     = JsonNodeFactory.instance;

    private static MongoServer   mongoServer;
    private static MongoClient   client;
    private static MongoDatabase db;

    private MongoSpecStore  specStore;
    private MongoStateStore stateStore;
    private MongoModelStore store;
    private MongoBlobStore  blobStore;
    private MongoAuditStore auditStore;

    @BeforeAll
    static void startServer() {
        mongoServer = new MongoServer(new MemoryBackend());
        InetSocketAddress addr = mongoServer.bind();
        client = MongoClients.create("mongodb://localhost:" + addr.getPort());
        db     = client.getDatabase("valem_test");
    }

    @AfterAll
    static void stopServer() {
        client.close();
        mongoServer.shutdownNow();
    }

    @BeforeEach
    void setUp() {
        db.getCollection("ss_specs").drop();
        db.getCollection("ss_states").drop();
        db.getCollection("ss_mutations").drop();
        db.getCollection("ss_blob_index").drop();
        db.getCollection("ss_audit").drop();

        specStore  = new MongoSpecStore(db, MAPPER);
        stateStore = new MongoStateStore(db, MAPPER, 100);
        store      = new MongoModelStore(specStore, stateStore);
        blobStore  = new MongoBlobStore(db);
        auditStore = new MongoAuditStore(db, MAPPER);
    }

    // ── audit trail ─────────────────────────────────────────────────────────────

    private AuditRecord auditRec(String path, Instant ts) {
        return AuditRecord.of("order", ts, "1.0.0", "client",
                Map.of(path, NF.numberNode(1)), java.util.List.of("$.total"),
                java.util.List.of(), java.util.List.of(),
                java.util.List.of(DerivationTrace.ofDerivation("$.total", "a+b",
                        java.util.List.of(path), NF.numberNode(3))));
    }

    @Test
    void audit_append_assigns_sequence_and_hash_chain() throws Exception {
        AuditRecord a = auditStore.append(auditRec("$.a", Instant.parse("2026-01-01T00:00:00Z")));
        AuditRecord b = auditStore.append(auditRec("$.b", Instant.parse("2026-01-02T00:00:00Z")));
        assertThat(a.sequence()).isZero();
        assertThat(b.sequence()).isEqualTo(1);
        assertThat(b.prevHash()).isEqualTo(a.hash());
        assertThat(auditStore.count("order")).isEqualTo(2);
    }

    @Test
    void audit_query_is_newest_first_and_filters_by_path_and_time() throws Exception {
        auditStore.append(auditRec("$.customer.name", Instant.parse("2026-01-01T00:00:00Z")));
        auditStore.append(auditRec("$.order.items",   Instant.parse("2026-03-01T00:00:00Z")));

        assertThat(auditStore.query(AuditQuery.all("order"))).hasSize(2);
        assertThat(auditStore.query(new AuditQuery("order", "$.customer", null, null, 100))).hasSize(1);
        assertThat(auditStore.query(new AuditQuery("order", null,
                Instant.parse("2026-02-01T00:00:00Z"), null, 100))).hasSize(1);
    }

    @Test
    void audit_verify_intact_and_detects_tampering() throws Exception {
        auditStore.append(auditRec("$.a", Instant.parse("2026-01-01T00:00:00Z")));
        auditStore.append(auditRec("$.b", Instant.parse("2026-01-02T00:00:00Z")));
        assertThat(auditStore.verify("order").valid()).isTrue();

        // Tamper with the stored record content while leaving its hash field unchanged.
        Document first = db.getCollection("ss_audit")
                .find(Filters.eq("seq", 0L)).first();
        String tampered = first.getString("auditJson")
                .replace("\"source\":\"client\"", "\"source\":\"forged\"");
        db.getCollection("ss_audit").updateOne(Filters.eq("seq", 0L),
                Updates.set("auditJson", tampered));

        AuditVerification broken = auditStore.verify("order");
        assertThat(broken.valid()).isFalse();
        assertThat(broken.firstBrokenSequence()).isZero();
    }

    @Test
    void audit_delete_removes_trail() throws Exception {
        auditStore.append(auditRec("$.a", Instant.now()));
        auditStore.deleteAudit("order");
        assertThat(auditStore.count("order")).isZero();
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
    void modelIds_lists_alphabetically() throws Exception {
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

        long mutCount = db.getCollection("ss_mutations")
                .countDocuments(com.mongodb.client.model.Filters.eq("modelId", "m"));
        assertThat(mutCount).isEqualTo(0);
        assertThat(store.loadSnapshot("m").get().baseDoc().path("x").asInt()).isEqualTo(7);
    }

    @Test
    void compaction_fires_when_threshold_exceeded() throws Exception {
        MongoStateStore lowThresholdState = new MongoStateStore(db, MAPPER, 2);
        MongoModelStore lowThresholdStore = new MongoModelStore(specStore, lowThresholdState);

        ObjectNode baseDoc = NF.objectNode();
        baseDoc.put("n", 0);
        lowThresholdStore.saveSnapshot("m", new Snapshot("m", "1.0.0", baseDoc, Map.of(), Map.of()));

        for (int i = 1; i <= 3; i++) {
            lowThresholdStore.applyMutationPatch("m",
                    patch("[{\"op\":\"add\",\"path\":\"/n\",\"value\":" + i + "}]"),
                    Instant.now());
        }

        long mutCount = db.getCollection("ss_mutations")
                .countDocuments(com.mongodb.client.model.Filters.eq("modelId", "m"));
        assertThat(mutCount).isEqualTo(0);
        assertThat(lowThresholdStore.loadSnapshot("m").get().baseDoc().path("n").asInt()).isEqualTo(3);
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

    // ── blob store ────────────────────────────────────────────────────────────

    @Test
    void store_and_load_blob_round_trip() throws Exception {
        byte[] data = "hello blob".getBytes();
        var ref = blobStore.store(new ByteArrayInputStream(data), "text/plain");
        assertThat(ref.blobId()).startsWith("sha256:");
        assertThat(ref.bytes()).isEqualTo(data.length);
        assertThat(blobStore.exists(ref.blobId())).isTrue();

        byte[] loaded = blobStore.load(ref.blobId()).readAllBytes();
        assertThat(loaded).isEqualTo(data);
    }

    @Test
    void store_same_bytes_twice_returns_same_id() throws Exception {
        byte[] data = "duplicate".getBytes();
        var ref1 = blobStore.store(new ByteArrayInputStream(data), "text/plain");
        var ref2 = blobStore.store(new ByteArrayInputStream(data), "text/plain");
        assertThat(ref1.blobId()).isEqualTo(ref2.blobId());
    }

    @Test
    void delete_blob_removes_from_gridfs() throws Exception {
        byte[] data = "to be deleted".getBytes();
        var ref = blobStore.store(new ByteArrayInputStream(data), "text/plain");
        assertThat(blobStore.exists(ref.blobId())).isTrue();

        blobStore.delete(ref.blobId());
        assertThat(blobStore.exists(ref.blobId())).isFalse();
    }

    @Test
    void exists_returns_false_for_unknown_blob() {
        assertThat(blobStore.exists("sha256:000000")).isFalse();
    }

    @Test
    void large_multi_chunk_blob_streams_through_gridfs() throws Exception {
        // > the 255 KiB GridFS chunk size and the 8 KiB spool buffer, so this exercises the
        // temp-file spool + chunked uploadFromStream/downloadStream path end to end (F-T6).
        byte[] data = new byte[2 * 1024 * 1024];
        for (int i = 0; i < data.length; i++) data[i] = (byte) (i % 253);

        var ref = blobStore.store(new ByteArrayInputStream(data), "application/octet-stream");
        assertThat(ref.bytes()).isEqualTo(data.length);

        byte[] loaded = blobStore.load(ref.blobId()).readAllBytes();
        assertThat(loaded).isEqualTo(data);
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private static ModelSpec spec(String id) throws Exception {
        return MAPPER.readValue("{\"id\":\"" + id + "\",\"schema\":{}}", ModelSpec.class);
    }

    private static ArrayNode patch(String json) throws Exception {
        return MAPPER.readValue(json, ArrayNode.class);
    }
}
