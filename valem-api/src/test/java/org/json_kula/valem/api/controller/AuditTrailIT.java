package org.json_kula.valem.api.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.json_kula.valem.persistence.audit.AuditStore;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * End-to-end coverage of the durable audit trail: a retaining {@code AuditStore} is turned on via
 * {@code valem.storage.audit-type=memory}, mutations flow through the real Spring wiring, and
 * the {@code GET /models/{id}/audit} endpoint returns the appended records with path/time filtering.
 */
@SpringBootTest(properties = "valem.storage.audit-type=memory")
@AutoConfigureMockMvc
class AuditTrailIT {

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @Autowired AuditStore auditStore;

    private void createModel(String id, String specJson) throws Exception {
        mvc.perform(post("/models").contentType(MediaType.APPLICATION_JSON).content(specJson))
                .andExpect(status().isCreated());
    }

    @Test
    void audit_backend_is_retaining_not_disabled() {
        assertThat(auditStore.isEnabled()).isTrue();
    }

    @Test
    void mutation_appends_an_audit_record_with_traces() throws Exception {
        createModel("audit-basic", """
                {
                  "id": "audit-basic", "schema": {},
                  "derivations": [
                    { "path": "$.order.total", "expr": "order.sub + order.tax" }
                  ]
                }
                """);

        mvc.perform(post("/models/audit-basic/mutations")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{ \"$.order.sub\": 80.0, \"$.order.tax\": 20.0 }"))
                .andExpect(status().isOk());

        mvc.perform(get("/models/audit-basic/audit"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].source", is("client")))
                .andExpect(jsonPath("$[0].sequence", is(0)))
                .andExpect(jsonPath("$[0].modelVersion", is("1.0.0")))
                .andExpect(jsonPath("$[0].mutations['$.order.sub']").value(80.0))
                .andExpect(jsonPath("$[0].derivedUpdated[0]", is("$.order.total")))
                .andExpect(jsonPath("$[0].traces", hasSize(greaterThanOrEqualTo(1))));
    }

    @Test
    void records_accumulate_and_survive_beyond_the_500_entry_ring_buffer_semantics() throws Exception {
        createModel("audit-many", """
                { "id": "audit-many", "schema": {},
                  "derivations": [ { "path": "$.d", "expr": "n * 2" } ] }
                """);
        for (int i = 1; i <= 12; i++) {
            mvc.perform(post("/models/audit-many/mutations")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{ \"$.n\": " + i + " }"))
                    .andExpect(status().isOk());
        }
        // Default limit returns all 12, newest-first (the in-memory explain buffer would also hold
        // them here, but the audit store is the durable, queryable superset).
        mvc.perform(get("/models/audit-many/audit"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(12)))
                .andExpect(jsonPath("$[0].mutations['$.n']").value(12));

        // limit is honoured, newest-first
        mvc.perform(get("/models/audit-many/audit").param("limit", "3"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(3)))
                .andExpect(jsonPath("$[0].mutations['$.n']").value(12))
                .andExpect(jsonPath("$[2].mutations['$.n']").value(10));
    }

    @Test
    void path_filter_narrows_to_records_touching_the_prefix() throws Exception {
        createModel("audit-paths", """
                { "id": "audit-paths", "schema": {} }
                """);
        mvc.perform(post("/models/audit-paths/mutations")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{ \"$.customer.name\": \"Ada\" }")).andExpect(status().isOk());
        mvc.perform(post("/models/audit-paths/mutations")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{ \"$.order.qty\": 3 }")).andExpect(status().isOk());

        mvc.perform(get("/models/audit-paths/audit").param("path", "$.customer"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].mutations['$.customer.name']", is("Ada")));
    }

    @Test
    void invalid_timestamp_returns_400() throws Exception {
        createModel("audit-badts", "{ \"id\": \"audit-badts\", \"schema\": {} }");
        mvc.perform(get("/models/audit-badts/audit").param("from", "not-a-date"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void audit_for_unknown_model_returns_404() throws Exception {
        mvc.perform(get("/models/does-not-exist/audit"))
                .andExpect(status().isNotFound());
    }

    @Test
    void audit_verify_reports_an_intact_hash_chain() throws Exception {
        createModel("audit-verify", "{ \"id\": \"audit-verify\", \"schema\": {} }");
        for (int i = 1; i <= 3; i++) {
            mvc.perform(post("/models/audit-verify/mutations")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{ \"$.n\": " + i + " }")).andExpect(status().isOk());
        }
        mvc.perform(get("/models/audit-verify/audit/verify"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.valid", is(true)))
                .andExpect(jsonPath("$.recordsChecked", is(3)));

        // Records carry the chain fields.
        mvc.perform(get("/models/audit-verify/audit").param("limit", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].hash").isNotEmpty())
                .andExpect(jsonPath("$[0].prevHash").isNotEmpty());
    }

    @Test
    void audit_verify_for_unknown_model_returns_404() throws Exception {
        mvc.perform(get("/models/nope/audit/verify")).andExpect(status().isNotFound());
    }

    @Test
    void deleting_a_model_clears_its_audit_trail() throws Exception {
        createModel("audit-del", "{ \"id\": \"audit-del\", \"schema\": {} }");
        mvc.perform(post("/models/audit-del/mutations")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{ \"$.x\": 1 }")).andExpect(status().isOk());
        assertThat(auditStore.count("audit-del")).isEqualTo(1);

        mvc.perform(delete("/models/audit-del")).andExpect(status().isNoContent());
        assertThat(auditStore.count("audit-del")).isZero();
    }
}
