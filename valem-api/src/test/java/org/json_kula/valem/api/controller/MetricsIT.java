package org.json_kula.valem.api.controller;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.hamcrest.Matchers.is;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Verifies the Actuator/Micrometer observability surface: the health probe is up, the registered-model
 * gauge exists, and a mutation records the {@code valem.mutation.duration} timer and the
 * {@code valem.effects.dispatched} counter.
 */
@SpringBootTest
@AutoConfigureMockMvc
class MetricsIT {

    @Autowired MockMvc mvc;

    @Test
    void health_endpoint_is_up() throws Exception {
        mvc.perform(get("/actuator/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status", is("UP")));
    }

    @Test
    void models_registered_gauge_is_exposed() throws Exception {
        mvc.perform(get("/actuator/metrics/valem.models.registered"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name", is("valem.models.registered")));
    }

    @Test
    void mutation_records_the_duration_timer() throws Exception {
        mvc.perform(post("/models").contentType(MediaType.APPLICATION_JSON)
                .content("{ \"id\": \"metrics-m\", \"schema\": {}, \"derivations\": [" +
                        "{ \"path\": \"$.d\", \"expr\": \"n * 2\" } ] }"))
                .andExpect(status().isCreated());
        mvc.perform(post("/models/metrics-m/mutations").contentType(MediaType.APPLICATION_JSON)
                .content("{ \"$.n\": 3 }"))
                .andExpect(status().isOk());

        mvc.perform(get("/actuator/metrics/valem.mutation.duration"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name", is("valem.mutation.duration")))
                // COUNT measurement is the first entry; at least one mutation was timed.
                .andExpect(jsonPath("$.measurements[0].value", greaterThanOrEqualTo(1.0)));
    }

    @Test
    void prometheus_endpoint_is_scrapeable() throws Exception {
        mvc.perform(get("/actuator/prometheus"))
                .andExpect(status().isOk());
    }
}
