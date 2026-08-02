package org.json_kula.valem.api.controller;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.is;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * {@code GET /models/{id}/verification} — the read-only trust-layer report powering the "built &amp;
 * checked against N cases" badge (docs/sandbox/trust-layer.md).
 */
@SpringBootTest
@AutoConfigureMockMvc
class ModelVerificationControllerIT {

    @Autowired MockMvc mvc;

    @Test
    void verification_is_green_when_the_embedded_test_passes() throws Exception {
        mvc.perform(post("/models").contentType(MediaType.APPLICATION_JSON).content("""
                { "id": "verify-green", "schema": {},
                  "derivations": [ { "path": "$.total", "expr": "subtotal + tax" } ],
                  "tests": [ { "description": "80+20", "given": { "$.subtotal": 80, "$.tax": 20 },
                               "expect": { "$.total": 100 } } ] }
                """))
                .andExpect(status().isCreated());

        mvc.perform(get("/models/verify-green/verification"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.modelId", is("verify-green")))
                .andExpect(jsonPath("$.state", is("green")))
                .andExpect(jsonPath("$.checkedCount", is(1)))
                .andExpect(jsonPath("$.passedCount", is(1)))
                .andExpect(jsonPath("$.cases[0].verifiable", is(true)))
                .andExpect(jsonPath("$.cases[0].passed", is(true)));
    }

    @Test
    void verification_is_amber_when_the_embedded_test_fails() throws Exception {
        mvc.perform(post("/models").contentType(MediaType.APPLICATION_JSON).content("""
                { "id": "verify-amber", "schema": {},
                  "derivations": [ { "path": "$.total", "expr": "subtotal - tax" } ],
                  "tests": [ { "description": "should be 100", "given": { "$.subtotal": 80, "$.tax": 20 },
                               "expect": { "$.total": 100 } } ] }
                """))
                .andExpect(status().isCreated());

        mvc.perform(get("/models/verify-amber/verification"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state", is("amber")))
                .andExpect(jsonPath("$.passedCount", is(0)))
                .andExpect(jsonPath("$.cases[0].passed", is(false)));
    }

    @Test
    void verification_is_neutral_when_the_spec_has_no_tests() throws Exception {
        mvc.perform(post("/models").contentType(MediaType.APPLICATION_JSON).content("""
                { "id": "verify-neutral", "schema": {},
                  "derivations": [ { "path": "$.total", "expr": "subtotal + tax" } ] }
                """))
                .andExpect(status().isCreated());

        mvc.perform(get("/models/verify-neutral/verification"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state", is("neutral")))
                .andExpect(jsonPath("$.checkedCount", is(0)));
    }

    @Test
    void verification_404s_for_unknown_model() throws Exception {
        mvc.perform(get("/models/no-such-model/verification"))
                .andExpect(status().isNotFound());
    }
}
