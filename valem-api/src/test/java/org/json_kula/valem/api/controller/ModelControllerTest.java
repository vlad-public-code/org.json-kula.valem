package org.json_kula.valem.api.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.json_kula.valem.core.blob.BlobStore;
import org.json_kula.valem.core.engine.ModelRuntime;
import org.json_kula.valem.core.model.BlobRef;
import org.json_kula.valem.api.registry.ModelRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.io.ByteArrayInputStream;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class ModelControllerTest {

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @Autowired BlobStore blobStore;
    @Autowired ModelRegistry registry;

    // ── Create model ──────────────────────────────────────────────────────────

    @Test
    void create_model_returns_201() throws Exception {
        mvc.perform(post("/models")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        { "id": "ctrl-test-1", "schema": {} }
                        """))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", containsString("/models/ctrl-test-1")))
                .andExpect(jsonPath("$.id", is("ctrl-test-1")))
                .andExpect(jsonPath("$.status", is("created")));
    }

    @Test
    void create_model_with_invalid_spec_returns_422() throws Exception {
        mvc.perform(post("/models")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        { "id": "", "schema": {} }
                        """))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.errors", hasSize(1)));
    }

    @Test
    void create_duplicate_model_returns_409() throws Exception {
        String spec = """
                { "id": "ctrl-dup-test", "schema": {} }
                """;
        mvc.perform(post("/models")
                .contentType(MediaType.APPLICATION_JSON)
                .content(spec))
                .andExpect(status().isCreated());

        mvc.perform(post("/models")
                .contentType(MediaType.APPLICATION_JSON)
                .content(spec))
                .andExpect(status().isConflict());
    }

    // ── Mutate ────────────────────────────────────────────────────────────────

    @Test
    void mutate_updates_field_and_returns_200() throws Exception {
        createModel("ctrl-mutate-1", """
                {
                  "id": "ctrl-mutate-1", "schema": {},
                  "derivations": [
                    { "path": "$.order.total", "expr": "order.sub + order.tax" }
                  ]
                }
                """);

        mvc.perform(post("/models/ctrl-mutate-1/mutations")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        { "$.order.sub": 80.0, "$.order.tax": 20.0 }
                        """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success", is(true)))
                .andExpect(jsonPath("$.derivedUpdated[0]", is("$.order.total")));
    }

    @Test
    void mutate_constraint_violation_returns_409() throws Exception {
        createModel("ctrl-cv-test", """
                {
                  "id": "ctrl-cv-test", "schema": {},
                  "constraints": [
                    { "id": "limit", "expr": "x.val <= 100",
                      "message": "Too big", "policy": "rollback" }
                  ]
                }
                """);

        mvc.perform(post("/models/ctrl-cv-test/mutations")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        { "$.x.val": 200 }
                        """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.violations", hasSize(1)));
    }

    // ── State ─────────────────────────────────────────────────────────────────

    @Test
    void get_state_returns_merged_document() throws Exception {
        createModel("ctrl-state-test", """
                {
                  "id": "ctrl-state-test", "schema": {},
                  "derivations": [
                    { "path": "$.calc.doubled", "expr": "input.val * 2" }
                  ]
                }
                """);
        mvc.perform(post("/models/ctrl-state-test/mutations")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        { "$.input.val": 5.0 }
                        """))
                .andExpect(status().isOk());

        mvc.perform(get("/models/ctrl-state-test/state"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.calc.doubled").value(10.0));
    }

    // ── Snapshot / restore ────────────────────────────────────────────────────

    @Test
    void snapshot_and_restore_round_trips_state() throws Exception {
        createModel("ctrl-snap-test", """
                { "id": "ctrl-snap-test", "schema": {} }
                """);
        mutate("ctrl-snap-test", """
                { "$.a.val": 42 }
                """);

        String snapJson = mvc.perform(post("/models/ctrl-snap-test/snapshot"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        mutate("ctrl-snap-test", """
                { "$.a.val": 99 }
                """);

        mvc.perform(post("/models/ctrl-snap-test/restore")
                .contentType(MediaType.APPLICATION_JSON)
                .content(snapJson))
                .andExpect(status().isNoContent());

        mvc.perform(get("/models/ctrl-snap-test/state"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.a.val", is(42)));
    }

    // ── Delete ────────────────────────────────────────────────────────────────

    @Test
    void delete_model_returns_204() throws Exception {
        createModel("ctrl-del-test", """
                { "id": "ctrl-del-test", "schema": {} }
                """);

        mvc.perform(delete("/models/ctrl-del-test"))
                .andExpect(status().isNoContent());

        mvc.perform(get("/models/ctrl-del-test/state"))
                .andExpect(status().isNotFound());
    }

    // ── Temporal history ──────────────────────────────────────────────────────

    @Test
    void history_is_empty_before_any_mutation() throws Exception {
        createModel("ctrl-hist-empty", "{ \"id\": \"ctrl-hist-empty\", \"schema\": {} }");

        mvc.perform(get("/models/ctrl-hist-empty/history"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray())
                .andExpect(jsonPath("$").isEmpty());
    }

    @Test
    void history_grows_after_mutations() throws Exception {
        createModel("ctrl-hist-grow", "{ \"id\": \"ctrl-hist-grow\", \"schema\": {} }");

        mutate("ctrl-hist-grow", "{ \"$.v\": 1 }");
        mutate("ctrl-hist-grow", "{ \"$.v\": 2 }");

        mvc.perform(get("/models/ctrl-hist-grow/history"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(2)));
    }

    @Test
    void state_at_valid_past_time_returns_200() throws Exception {
        createModel("ctrl-hist-time", "{ \"id\": \"ctrl-hist-time\", \"schema\": {} }");
        mutate("ctrl-hist-time", "{ \"$.v\": 42 }");

        // Query well in the future — should return the latest (and only) snapshot
        mvc.perform(get("/models/ctrl-hist-time/state")
                        .param("at", "2099-01-01T00:00:00Z"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.v", is(42)));
    }

    @Test
    void state_at_before_any_mutation_returns_404() throws Exception {
        createModel("ctrl-hist-before", "{ \"id\": \"ctrl-hist-before\", \"schema\": {} }");
        mutate("ctrl-hist-before", "{ \"$.v\": 1 }");

        // Query before any mutations were recorded
        mvc.perform(get("/models/ctrl-hist-before/state")
                        .param("at", "2000-01-01T00:00:00Z"))
                .andExpect(status().isNotFound());
    }

    @Test
    void state_at_invalid_timestamp_returns_400() throws Exception {
        createModel("ctrl-hist-bad-ts", "{ \"id\": \"ctrl-hist-bad-ts\", \"schema\": {} }");

        mvc.perform(get("/models/ctrl-hist-bad-ts/state")
                        .param("at", "not-a-timestamp"))
                .andExpect(status().isBadRequest());
    }

    // ── Model-scoped blob ─────────────────────────────────────────────────────

    @Test
    void get_blob_returns_404_when_model_not_found() throws Exception {
        mvc.perform(get("/models/{id}/blobs/{blobId}", "nonexistent-model", "sha256:abc"))
                .andExpect(status().isNotFound());
    }

    @Test
    void get_blob_returns_404_when_blob_not_referenced_by_model() throws Exception {
        createModel("ctrl-blob-unref", """
                { "id": "ctrl-blob-unref", "schema": {} }
                """);

        mvc.perform(get("/models/{id}/blobs/{blobId}", "ctrl-blob-unref", "sha256:deadbeef"))
                .andExpect(status().isNotFound());
    }

    @Test
    void get_blob_streams_content_when_blob_is_referenced() throws Exception {
        createModel("ctrl-blob-ref", """
                { "id": "ctrl-blob-ref", "schema": {} }
                """);

        // Store a blob and write its ref directly into the model state
        byte[] data = "hello-blob".getBytes();
        BlobRef ref = blobStore.store(new ByteArrayInputStream(data), "text/plain");

        ModelRuntime rt = registry.find("ctrl-blob-ref").orElseThrow();
        synchronized (rt) {
            rt.stateView().setValue("$.attachment", ref.toJsonNode());
        }

        // Use URI template expansion so MockMvc handles the encoding correctly
        mvc.perform(get("/models/{id}/blobs/{blobId}", "ctrl-blob-ref", ref.blobId()))
                .andExpect(status().isOk())
                .andExpect(content().bytes(data));
    }

    @Test
    void get_blob_returns_404_when_blob_id_present_in_store_but_not_in_model_state() throws Exception {
        createModel("ctrl-blob-wrong-model", """
                { "id": "ctrl-blob-wrong-model", "schema": {} }
                """);

        // Blob exists in the store but is not referenced by this model
        byte[] data = "other-data".getBytes();
        BlobRef ref = blobStore.store(new ByteArrayInputStream(data), "text/plain");

        mvc.perform(get("/models/{id}/blobs/{blobId}", "ctrl-blob-wrong-model", ref.blobId()))
                .andExpect(status().isNotFound());
    }

    // ── JSON Patch mutations ──────────────────────────────────────────────────

    private static final String JSON_PATCH = "application/json-patch+json";

    private static final String ITEMS_SPEC_TEMPLATE = """
            {
              "id": "%s", "schema": {},
              "derivations": [
                { "path": "$.total",              "expr": "$sum(items.(price * qty))" },
                { "path": "$.items[*].lineTotal", "expr": "$parent.price * $parent.qty" }
              ]
            }
            """;

    @Test
    void patch_adds_array_item_and_recalculates_total() throws Exception {
        String id = "ctrl-patch-add";
        createModel(id, ITEMS_SPEC_TEMPLATE.formatted(id));
        mutate(id, """
                { "$.items[0].name": "Apple", "$.items[0].price": 1.5, "$.items[0].qty": 4 }
                """); // total = 6.0

        patchMutate(id, """
                [{"op":"add","path":"/items/-","value":{"name":"Bread","price":2.75,"qty":2}}]
                """);

        mvc.perform(get("/models/" + id + "/state"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(11.5)); // 1.5*4 + 2.75*2
    }

    @Test
    void patch_removes_array_item_and_recalculates_total() throws Exception {
        String id = "ctrl-patch-remove";
        createModel(id, ITEMS_SPEC_TEMPLATE.formatted(id));
        mutate(id, """
                {
                  "$.items[0].name": "Apple",  "$.items[0].price": 1.5,  "$.items[0].qty": 4,
                  "$.items[1].name": "Bread",  "$.items[1].price": 2.75, "$.items[1].qty": 2
                }
                """); // total = 11.5

        patchMutate(id, """
                [{"op":"remove","path":"/items/0"}]
                """);

        mvc.perform(get("/models/" + id + "/state"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(5.5)); // 2.75*2
    }

    @Test
    void patch_replaces_leaf_field_and_recalculates_total() throws Exception {
        String id = "ctrl-patch-replace";
        createModel(id, ITEMS_SPEC_TEMPLATE.formatted(id));
        mutate(id, """
                { "$.items[0].name": "Apple", "$.items[0].price": 1.5, "$.items[0].qty": 4 }
                """); // total = 6.0

        patchMutate(id, """
                [{"op":"replace","path":"/items/0/qty","value":10}]
                """);

        mvc.perform(get("/models/" + id + "/state"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(15.0)); // 1.5*10
    }

    @Test
    void per_item_line_total_is_derived_alongside_order_total() throws Exception {
        String id = "ctrl-line-total";
        createModel(id, ITEMS_SPEC_TEMPLATE.formatted(id));
        mutate(id, """
                {
                  "$.items[0].name": "Apple", "$.items[0].price": 1.5,  "$.items[0].qty": 4,
                  "$.items[1].name": "Bread",  "$.items[1].price": 2.75, "$.items[1].qty": 2
                }
                """);

        mvc.perform(get("/models/" + id + "/state"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(11.5))          // 1.5*4 + 2.75*2
                .andExpect(jsonPath("$.items[0].lineTotal").value(6.0))   // 1.5*4
                .andExpect(jsonPath("$.items[1].lineTotal").value(5.5));  // 2.75*2
    }

    @Test
    void patch_invalid_path_returns_422() throws Exception {
        String id = "ctrl-patch-invalid";
        createModel(id, ITEMS_SPEC_TEMPLATE.formatted(id));

        mvc.perform(post("/models/" + id + "/mutations/patch")
                .contentType(JSON_PATCH)
                .content("""
                        [{"op":"remove","path":"/nonexistent/99"}]
                        """))
                .andExpect(status().isUnprocessableEntity());
    }

    @Test
    void patch_unknown_model_returns_404() throws Exception {
        mvc.perform(post("/models/no-such-model/mutations/patch")
                .contentType(JSON_PATCH)
                .content("""
                        [{"op":"add","path":"/x","value":1}]
                        """))
                .andExpect(status().isNotFound());
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private void createModel(String id, String specJson) throws Exception {
        mvc.perform(post("/models")
                .contentType(MediaType.APPLICATION_JSON)
                .content(specJson))
                .andExpect(status().isCreated());
    }

    private void mutate(String id, String mutationsJson) throws Exception {
        mvc.perform(post("/models/" + id + "/mutations")
                .contentType(MediaType.APPLICATION_JSON)
                .content(mutationsJson))
                .andExpect(status().isOk());
    }

    private void patchMutate(String id, String patchJson) throws Exception {
        mvc.perform(post("/models/" + id + "/mutations/patch")
                .contentType(JSON_PATCH)
                .content(patchJson))
                .andExpect(status().isOk());
    }
}
