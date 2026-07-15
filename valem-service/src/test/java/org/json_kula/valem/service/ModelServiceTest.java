package org.json_kula.valem.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.json_kula.valem.core.blob.InMemoryBlobStore;
import org.json_kula.valem.core.engine.ConstraintEvaluator;
import org.json_kula.valem.core.state.Snapshot;
import org.json_kula.valem.view.engine.EvaluatedView;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ModelServiceTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final JsonNodeFactory NF   = JsonNodeFactory.instance;

    /** Simple order spec: total = subtotal + tax, max-total constraint. */
    private static final String ORDER_SPEC = """
            {
              "id": "test-order",
              "version": "1.0.0",
              "schema": {},
              "derivations": [
                {"path": "$.total", "expr": "subtotal + tax"}
              ],
              "constraints": [
                {"id": "max-total", "expr": "total <= 1000", "policy": "rollback"}
              ],
              "actions": [], "metaDerivations": [], "tests": []
            }
            """;

    private ModelService service;

    @BeforeEach
    void setUp() {
        service = new ModelService(new ModelRegistry(), new InMemoryBlobStore());
    }

    // ── createModel ────────────────────────────────────────────────────────────

    @Test
    void createModel_registers_and_makes_model_available() throws Exception {
        service.createModel(spec(ORDER_SPEC));
        assertThat(service.listModels()).contains("test-order");
    }

    @Test
    void createModel_applies_creation_defaults_before_registration() throws Exception {
        String specJson = """
                {
                  "id": "with-init",
                  "version": "1.0.0",
                  "schema": {},
                  "derivations": [
                    {"path": "$.total", "expr": "price * qty"}
                  ],
                  "constraints": [], "actions": [], "metaDerivations": [], "tests": [],
                  "defaultValues": [ {"path": "$", "expr": "{ \\"price\\": 10, \\"qty\\": 3 }"} ]
                }
                """;
        service.createModel(spec(specJson));
        ObjectNode state = service.getState("with-init", null);
        assertThat(state.path("total").asDouble()).isEqualTo(30.0);
    }

    @Test
    void createModel_computes_derivation_whose_only_dependency_was_never_touched() throws Exception {
        // "flag" depends solely on "smoker", which this test never seeds nor mutates — a fresh model
        // must still get one full derivation pass (matching the guarantee loadModel() already gives a
        // cold-loaded model), not leave "flag" permanently null until "smoker" happens to be written.
        String specJson = """
                {
                  "id": "never-touched-dep",
                  "version": "1.0.0",
                  "schema": {},
                  "derivations": [
                    {"path": "$.flag", "expr": "smoker = true ? 1 : 0"}
                  ],
                  "constraints": [], "actions": [], "metaDerivations": [], "tests": []
                }
                """;
        service.createModel(spec(specJson));
        ObjectNode state = service.getState("never-touched-dep", null);
        assertThat(state.path("flag").asInt()).isEqualTo(0);
    }

    @Test
    void createModel_throws_ModelValidationException_on_invalid_spec() throws Exception {
        // Missing required "id" field  → validation error
        String bad = """
                {
                  "id": "",
                  "schema": {}
                }
                """;
        assertThatThrownBy(() -> service.createModel(spec(bad)))
                .isInstanceOf(ModelValidationException.class);
    }

    @Test
    void createModel_throws_ModelAlreadyExistsException_on_duplicate() throws Exception {
        service.createModel(spec(ORDER_SPEC));
        assertThatThrownBy(() -> service.createModel(spec(ORDER_SPEC)))
                .isInstanceOf(ModelAlreadyExistsException.class);
    }

    // ── mutate ─────────────────────────────────────────────────────────────────

    @Test
    void mutate_updates_derived_fields() throws Exception {
        service.createModel(spec(ORDER_SPEC));
        service.mutate("test-order",
                Map.of("$.subtotal", NF.numberNode(200.0), "$.tax", NF.numberNode(20.0)));
        ObjectNode state = service.getState("test-order", null);
        assertThat(state.path("total").asDouble()).isEqualTo(220.0);
    }

    @Test
    void mutate_throws_ModelNotFoundException_on_unknown_id() {
        assertThatThrownBy(() -> service.mutate("ghost",
                Map.of("$.x", NF.numberNode(1))))
                .isInstanceOf(ModelNotFoundException.class);
    }

    @Test
    void mutate_rolls_back_on_constraint_violation() throws Exception {
        service.createModel(spec(ORDER_SPEC));
        service.mutate("test-order",
                Map.of("$.subtotal", NF.numberNode(500.0), "$.tax", NF.numberNode(50.0)));

        // Push total over 1000 — should rollback
        assertThatThrownBy(() -> service.mutate("test-order",
                Map.of("$.subtotal", NF.numberNode(900.0), "$.tax", NF.numberNode(200.0))))
                .isInstanceOf(ConstraintEvaluator.ConstraintViolationException.class);

        // State should still reflect the previous valid mutation
        ObjectNode state = service.getState("test-order", null);
        assertThat(state.path("total").asDouble()).isEqualTo(550.0);
    }

    // ── patchMutate ────────────────────────────────────────────────────────────

    @Test
    void patchMutate_applies_rfc6902_add_operation() throws Exception {
        service.createModel(spec(ORDER_SPEC));
        // Patch must set both fields so the total derivation produces a defined value
        // (JSONata: undefined + number = undefined, undefined <= 1000 = false → constraint fires)
        JsonNode patch = MAPPER.readTree("""
                [
                  {"op":"add","path":"/subtotal","value":150},
                  {"op":"add","path":"/tax","value":0}
                ]
                """);
        service.patchMutate("test-order", patch);
        ObjectNode state = service.getState("test-order", null);
        assertThat(state.path("subtotal").asDouble()).isEqualTo(150.0);
    }

    @Test
    void patchMutate_throws_InvalidPatchException_for_bad_patch() throws Exception {
        service.createModel(spec(ORDER_SPEC));
        JsonNode patch = MAPPER.readTree("[{\"op\":\"remove\",\"path\":\"/no/such/field\"}]");
        assertThatThrownBy(() -> service.patchMutate("test-order", patch))
                .isInstanceOf(InvalidPatchException.class);
    }

    // ── read ───────────────────────────────────────────────────────────────────

    @Test
    void listModels_returns_sorted_ids() throws Exception {
        service.createModel(spec(ORDER_SPEC.replace("\"test-order\"", "\"bravo\"")));
        service.createModel(spec(ORDER_SPEC.replace("\"test-order\"", "\"alpha\"")));
        assertThat(service.listModels()).containsExactly("alpha", "bravo");
    }

    @Test
    void getSpec_returns_registered_spec() throws Exception {
        service.createModel(spec(ORDER_SPEC));
        assertThat(service.getSpec("test-order").id()).isEqualTo("test-order");
        assertThat(service.getSpec("test-order").derivations()).hasSize(1);
    }

    @Test
    void getSpec_throws_on_unknown_model() {
        assertThatThrownBy(() -> service.getSpec("ghost"))
                .isInstanceOf(ModelNotFoundException.class);
    }

    @Test
    void getInfo_returns_correct_counts() throws Exception {
        service.createModel(spec(ORDER_SPEC));
        ModelInfo info = service.getInfo("test-order");
        assertThat(info.id()).isEqualTo("test-order");
        assertThat(info.derivationCount()).isEqualTo(1);
        assertThat(info.constraintCount()).isEqualTo(1);
        assertThat(info.effectCount()).isEqualTo(0);
    }

    @Test
    void getState_includes_derived_values() throws Exception {
        service.createModel(spec(ORDER_SPEC));
        service.mutate("test-order",
                Map.of("$.subtotal", NF.numberNode(100.0), "$.tax", NF.numberNode(8.0)));
        ObjectNode state = service.getState("test-order", null);
        assertThat(state.path("subtotal").asDouble()).isEqualTo(100.0);
        assertThat(state.path("tax").asDouble()).isEqualTo(8.0);
        assertThat(state.path("total").asDouble()).isEqualTo(108.0);
    }

    @Test
    void getFieldValue_returns_derived_value() throws Exception {
        service.createModel(spec(ORDER_SPEC));
        service.mutate("test-order",
                Map.of("$.subtotal", NF.numberNode(50.0), "$.tax", NF.numberNode(5.0)));
        JsonNode total = service.getFieldValue("test-order", "$.total");
        assertThat(total.asDouble()).isEqualTo(55.0);
    }

    @Test
    void getHistory_returns_empty_before_mutations_when_no_initial_state() throws Exception {
        service.createModel(spec(ORDER_SPEC));
        assertThat(service.getHistory("test-order")).isEmpty();
    }

    @Test
    void getHistory_returns_one_timestamp_after_mutation() throws Exception {
        service.createModel(spec(ORDER_SPEC));
        service.mutate("test-order",
                Map.of("$.subtotal", NF.numberNode(1.0), "$.tax", NF.numberNode(0.0)));
        assertThat(service.getHistory("test-order")).hasSize(1);
    }

    // ── snapshot / restore ─────────────────────────────────────────────────────

    @Test
    void snapshot_and_restore_round_trip() throws Exception {
        service.createModel(spec(ORDER_SPEC));
        service.mutate("test-order",
                Map.of("$.subtotal", NF.numberNode(300.0), "$.tax", NF.numberNode(30.0)));

        Snapshot snap = service.snapshot("test-order");

        // Mutate again
        service.mutate("test-order",
                Map.of("$.subtotal", NF.numberNode(500.0), "$.tax", NF.numberNode(50.0)));
        assertThat(service.getState("test-order", null).path("total").asDouble())
                .isEqualTo(550.0);

        // Restore
        service.restore("test-order", snap);
        assertThat(service.getState("test-order", null).path("total").asDouble())
                .isEqualTo(330.0);
    }

    // ── evolveSpec ─────────────────────────────────────────────────────────────

    @Test
    void evolveSpec_adds_new_derivation() throws Exception {
        service.createModel(spec(ORDER_SPEC));
        service.mutate("test-order",
                Map.of("$.subtotal", NF.numberNode(100.0), "$.tax", NF.numberNode(10.0)));

        String evolutionJson = """
                {
                  "upsertDerivations": [
                    {"path": "$.subtotalPlusTax", "expr": "subtotal + tax"}
                  ]
                }
                """;
        service.evolveSpec("test-order",
                MAPPER.readValue(evolutionJson, org.json_kula.valem.core.graph.SpecEvolution.class));

        JsonNode derived = service.getFieldValue("test-order", "$.subtotalPlusTax");
        assertThat(derived).isNotNull();
    }

    @Test
    void evolveSpec_backfills_new_field_on_existing_instance() throws Exception {
        service.createModel(spec(ORDER_SPEC));
        service.mutate("test-order",
                Map.of("$.subtotal", NF.numberNode(100.0), "$.tax", NF.numberNode(0.0)));
        // total = 100 now; $.shipping does not exist yet.

        // Evolution adds a derivation depending on a brand-new base field, and backfills that field.
        String evolutionJson = """
                {
                  "upsertDerivations": [ {"path": "$.grandTotal", "expr": "total + shipping"} ],
                  "backfill": { "$.shipping": 5 }
                }
                """;
        service.evolveSpec("test-order",
                MAPPER.readValue(evolutionJson, org.json_kula.valem.core.graph.SpecEvolution.class));

        ObjectNode state = service.getState("test-order", null);
        assertThat(state.path("shipping").asInt()).isEqualTo(5);             // backfilled
        assertThat(state.path("grandTotal").asDouble()).isEqualTo(105.0);    // derived, not null
    }

    @Test
    void evolveSpec_backfill_does_not_overwrite_existing_value() throws Exception {
        service.createModel(spec(ORDER_SPEC));
        service.mutate("test-order",
                Map.of("$.subtotal", NF.numberNode(100.0), "$.tax", NF.numberNode(0.0),
                        "$.shipping", NF.numberNode(20.0)));

        String evolutionJson = """
                {
                  "upsertDerivations": [ {"path": "$.grandTotal", "expr": "total + shipping"} ],
                  "backfill": { "$.shipping": 5 }
                }
                """;
        service.evolveSpec("test-order",
                MAPPER.readValue(evolutionJson, org.json_kula.valem.core.graph.SpecEvolution.class));

        ObjectNode state = service.getState("test-order", null);
        assertThat(state.path("shipping").asInt()).isEqualTo(20);            // existing value kept
        assertThat(state.path("grandTotal").asDouble()).isEqualTo(120.0);
    }

    // ── mutation persistence ordering under concurrency (F-T2) ─────────────────

    @Test
    void concurrent_mutations_persist_in_committed_order() throws Exception {
        service.createModel(spec(ORDER_SPEC));

        // The persister records the value of $.subtotal in the exact order it is invoked.
        // Because it runs inside the model lock, that order must equal the commit order, and the
        // last recorded value must equal the final committed state (no reordering, none lost).
        java.util.List<Double> persistOrder =
                java.util.Collections.synchronizedList(new java.util.ArrayList<>());
        service.setMutationPersister((modelId, mutations, mutatedAt) ->
                persistOrder.add(mutations.get("$.subtotal").asDouble()));

        int n = 50;
        // One thread per task: the start barrier (ready/go) requires every task to actually be
        // running before it is released, so the pool must be able to run all n concurrently.
        var pool = java.util.concurrent.Executors.newFixedThreadPool(n);
        var ready = new java.util.concurrent.CountDownLatch(n);
        var go    = new java.util.concurrent.CountDownLatch(1);
        var done  = new java.util.concurrent.CountDownLatch(n);
        for (int i = 0; i < n; i++) {
            final double v = i;
            pool.submit(() -> {
                ready.countDown();
                try {
                    go.await();
                    service.mutate("test-order",
                            Map.of("$.subtotal", NF.numberNode(v), "$.tax", NF.numberNode(0.0)));
                } catch (Exception ignored) {
                    // queue-full is acceptable under contention; not all may land
                } finally {
                    done.countDown();
                }
            });
        }
        ready.await();
        go.countDown();
        done.await();
        pool.shutdown();

        // Every committed mutation was persisted, and the last persisted value matches final state.
        double finalSubtotal = service.getState("test-order", null).path("subtotal").asDouble();
        assertThat(persistOrder).isNotEmpty();
        assertThat(persistOrder.get(persistOrder.size() - 1)).isEqualTo(finalSubtotal);
    }

    // ── loadModel re-derivation (F-T3) ─────────────────────────────────────────

    @Test
    void loadModel_recomputes_eager_derivations_when_derivedCache_empty() throws Exception {
        // Simulate a cold restart from an incremental mutation log: the persisted snapshot
        // carries the base document but an EMPTY derivedCache (derived values were never
        // written to the log). getState must still return the EAGER derived field.
        var baseDoc = (ObjectNode) MAPPER.readTree("""
                { "subtotal": 100, "tax": 8 }
                """);
        Snapshot snapshotWithoutDerived = new Snapshot(
                "reloaded-order", "1", baseDoc, Map.of(), Map.of());

        service.loadModel(spec(ORDER_SPEC.replace("\"test-order\"", "\"reloaded-order\"")),
                java.util.Optional.of(snapshotWithoutDerived));

        ObjectNode state = service.getState("reloaded-order", null);
        // total = subtotal + tax must be present without any prior mutation
        assertThat(state.path("total").asDouble()).isEqualTo(108.0);
    }

    // ── deleteModel ────────────────────────────────────────────────────────────

    @Test
    void deleteModel_removes_from_registry() throws Exception {
        service.createModel(spec(ORDER_SPEC));
        assertThat(service.listModels()).contains("test-order");
        service.deleteModel("test-order");
        assertThat(service.listModels()).doesNotContain("test-order");
    }

    @Test
    void deleteModel_throws_on_unknown_model() {
        assertThatThrownBy(() -> service.deleteModel("ghost"))
                .isInstanceOf(ModelNotFoundException.class);
    }

    // ── blob ───────────────────────────────────────────────────────────────────

    @Test
    void uploadBlob_and_downloadBlob_round_trip() throws Exception {
        byte[] content = "hello blob".getBytes(StandardCharsets.UTF_8);
        var ref = service.uploadBlob(new ByteArrayInputStream(content), "text/plain");
        assertThat(ref).isNotNull();
        try (InputStream in = service.downloadBlob(ref.blobId())) {
            assertThat(in.readAllBytes()).isEqualTo(content);
        }
    }

    @Test
    void getBlobForModel_throws_BlobNotReferencedException_when_not_referenced() throws Exception {
        service.createModel(spec(ORDER_SPEC));
        byte[] content = "some-data".getBytes(StandardCharsets.UTF_8);
        var ref = service.uploadBlob(new ByteArrayInputStream(content), "application/octet-stream");
        assertThatThrownBy(() -> service.getBlobForModel("test-order", ref.blobId()))
                .isInstanceOf(BlobNotReferencedException.class);
    }

    // ── getEvaluatedView ──────────────────────────────────────────────────────

    @Test
    void getEvaluatedView_returns_resolved_component_tree() throws Exception {
        String specWithView = """
                {
                  "id": "view-model",
                  "version": "1.0.0",
                  "schema": {},
                  "derivations": [],
                  "constraints": [], "actions": [], "metaDerivations": [], "tests": [],
                  "defaultValues": [ {"path": "$", "expr": "{ \\"name\\": \\"Alice\\" }"} ],
                  "viewDefinition": {
                    "views": [
                      {
                        "id": "main",
                        "label": "Main",
                        "layout": "vertical",
                        "components": [
                          {"id": "nameField", "type": "textField", "label": "Name", "bind": "$.name"},
                          {"id": "greeting",  "type": "staticText",
                           "text": "$string('Hello, ') & name"}
                        ]
                      }
                    ],
                    "defaultView": "main"
                  }
                }
                """;
        service.createModel(spec(specWithView));
        EvaluatedView view = service.getEvaluatedView("view-model", null);

        assertThat(view.modelId()).isEqualTo("view-model");
        assertThat(view.viewId()).isEqualTo("main");
        assertThat(view.components()).hasSize(2);

        // nameField: bound value resolves to "Alice"
        var nameField = view.components().getFirst();
        assertThat(nameField.id()).isEqualTo("nameField");
        assertThat(nameField.bind()).isEqualTo("$.name");
        assertThat(nameField.value().asText()).isEqualTo("Alice");
        assertThat(nameField.visible()).isTrue();
        assertThat(nameField.readOnly()).isFalse();

        // greeting: text JSONata expression evaluated
        var greeting = view.components().get(1);
        assertThat(greeting.id()).isEqualTo("greeting");
        assertThat(greeting.text()).isEqualTo("Hello, Alice");
    }

    @Test
    void getEvaluatedView_throws_ModelNotFoundException_when_no_viewDefinition() throws Exception {
        service.createModel(spec(ORDER_SPEC));
        assertThatThrownBy(() -> service.getEvaluatedView("test-order", null))
                .isInstanceOf(ModelNotFoundException.class);
    }

    @Test
    void getEvaluatedView_with_named_viewId_resolves_correct_view() throws Exception {
        String specWithTwoViews = """
                {
                  "id": "two-view-model",
                  "version": "1.0.0",
                  "schema": {},
                  "derivations": [], "constraints": [], "actions": [], "metaDerivations": [], "tests": [],
                  "viewDefinition": {
                    "views": [
                      {
                        "id": "summary",
                        "label": "Summary",
                        "layout": "vertical",
                        "components": [
                          {"id": "sumField", "type": "label", "label": "Summary label"}
                        ]
                      },
                      {
                        "id": "detail",
                        "label": "Detail",
                        "layout": "vertical",
                        "components": [
                          {"id": "detField", "type": "textField", "label": "Detail field"}
                        ]
                      }
                    ],
                    "defaultView": "summary"
                  }
                }
                """;
        service.createModel(spec(specWithTwoViews));

        EvaluatedView detail = service.getEvaluatedView("two-view-model", "detail");
        assertThat(detail.viewId()).isEqualTo("detail");
        assertThat(detail.title()).isEqualTo("Detail");
        assertThat(detail.components()).hasSize(1);
        assertThat(detail.components().getFirst().id()).isEqualTo("detField");
    }

    @Test
    void getEvaluatedView_throws_ModelNotFoundException_when_viewId_not_found() throws Exception {
        String specWithView = """
                {
                  "id": "single-view-model",
                  "version": "1.0.0",
                  "schema": {},
                  "derivations": [], "constraints": [], "actions": [], "metaDerivations": [], "tests": [],
                  "viewDefinition": {
                    "views": [ { "id": "main", "label": "Main", "components": [] } ],
                    "defaultView": "main"
                  }
                }
                """;
        service.createModel(spec(specWithView));
        assertThatThrownBy(() -> service.getEvaluatedView("single-view-model", "no-such-view"))
                .isInstanceOf(ModelNotFoundException.class)
                .hasMessageContaining("no-such-view");
    }

    // ── helpers ────────────────────────────────────────────────────────────────

    private static org.json_kula.valem.core.model.ModelSpec spec(String json) throws Exception {
        return MAPPER.readValue(json, org.json_kula.valem.core.model.ModelSpec.class);
    }
}
