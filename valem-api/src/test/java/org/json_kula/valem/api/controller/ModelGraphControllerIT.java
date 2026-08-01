package org.json_kula.valem.api.controller;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.is;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * {@code GET /models/{id}/graph} — the read-only dependency-graph projection powering the
 * "Why is this number?" surface (docs/sandbox/why-this-number.md).
 */
@SpringBootTest
@AutoConfigureMockMvc
class ModelGraphControllerIT {

    @Autowired MockMvc mvc;

    @Test
    void graph_endpoint_returns_nodes_edges_and_expressions() throws Exception {
        mvc.perform(post("/models").contentType(MediaType.APPLICATION_JSON).content("""
                { "id": "graph-it-1", "schema": {},
                  "derivations": [ { "path": "$.total", "expr": "subtotal + tax" } ],
                  "constraints": [ { "id": "cap", "expr": "total <= 100", "message": "over cap", "policy": "rollback" } ] }
                """))
                .andExpect(status().isCreated());

        mvc.perform(get("/models/graph-it-1/graph"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.modelId", is("graph-it-1")))
                // the derived node carries its kind + expression
                .andExpect(jsonPath("$.nodes[?(@.key == '$.total')].kind", hasItem("DERIVED")))
                .andExpect(jsonPath("$.nodes[?(@.key == '$.total')].expression", hasItem("subtotal + tax")))
                // base inputs are present as BASE
                .andExpect(jsonPath("$.nodes[?(@.key == '$.subtotal')].kind", hasItem("BASE")))
                // constraint split out by prefix, message used as label
                .andExpect(jsonPath("$.nodes[?(@.key == '$constraint:cap')].kind", hasItem("CONSTRAINT")))
                .andExpect(jsonPath("$.nodes[?(@.key == '$constraint:cap')].label", hasItem("over cap")))
                // edge direction: input feeds the derived value (filter yields the matching edge)
                .andExpect(jsonPath("$.edges[?(@.from == '$.subtotal' && @.to == '$.total')].from", hasItem("$.subtotal")));
    }

    @Test
    void graph_endpoint_404s_for_unknown_model() throws Exception {
        mvc.perform(get("/models/no-such-model/graph"))
                .andExpect(status().isNotFound());
    }
}
