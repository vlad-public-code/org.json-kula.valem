package org.json_kula.valem.api.controller;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** {@code GET /models/{id}/library} and the create-path error mapping for a bad library. */
@SpringBootTest
@AutoConfigureMockMvc
class ModelLibraryControllerTest {

    @Autowired MockMvc mvc;

    @Test
    void returns_the_export_vocabulary() throws Exception {
        create("lib-ctrl-1", """
            { "id": "lib-ctrl-1", "schema": {},
              "library": { "description": "Money helpers.",
                           "define": "( $money := function($n){ $round($n, 2) }; $year := 2026; [\\"money\\", \\"year\\"] )" },
              "derivations": [ { "path": "$.rounded", "expr": "$money(raw)" } ] }
            """);

        mvc.perform(get("/models/lib-ctrl-1/library"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.description", is("Money helpers.")))
                .andExpect(jsonPath("$.layers", hasSize(1)))
                .andExpect(jsonPath("$.layers[0].origin", is("local")))
                .andExpect(jsonPath("$.exports", hasSize(2)))
                .andExpect(jsonPath("$.exports[?(@.name == 'money')].kind", is(java.util.List.of("function"))))
                .andExpect(jsonPath("$.exports[?(@.name == 'year')].kind", is(java.util.List.of("constant"))))
                .andExpect(jsonPath("$.exports[?(@.name == 'year')].value", is(java.util.List.of(2026))));
    }

    @Test
    void returns_404_when_the_model_declares_no_library() throws Exception {
        create("lib-ctrl-2", """
            { "id": "lib-ctrl-2", "schema": {} }
            """);

        mvc.perform(get("/models/lib-ctrl-2/library")).andExpect(status().isNotFound());
    }

    @Test
    void a_library_that_reads_the_document_is_a_422_not_a_500() throws Exception {
        mvc.perform(post("/models")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    { "id": "lib-ctrl-3", "schema": {},
                      "library": "( $net := function(){ order.subtotal }; [\\"net\\"] )" }
                    """))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.errors[0].location", is("library.define")))
                .andExpect(jsonPath("$.errors[0].message",
                        containsString("always evaluates to nothing")));
    }

    @Test
    void a_call_to_an_undefined_function_is_a_422() throws Exception {
        mvc.perform(post("/models")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    { "id": "lib-ctrl-4", "schema": {},
                      "library": "( $money := function($n){ $round($n, 2) }; [\\"money\\"] )",
                      "derivations": [ { "path": "$.x", "expr": "$monye(raw)" } ] }
                    """))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.errors[0].message", containsString("$monye is not defined")));
    }

    private void create(String id, String spec) throws Exception {
        mvc.perform(post("/models").contentType(MediaType.APPLICATION_JSON).content(spec))
                .andExpect(status().isCreated());
    }
}
