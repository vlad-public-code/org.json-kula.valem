package org.json_kula.valem.api.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Thread-safety tests for {@link ModelController}.
 *
 * <p>Mutations are serialized via {@code synchronized(rt)} in {@link org.json_kula.valem.service.ModelService}.
 * Reads are unsynchronized. These tests verify:
 * <ol>
 *   <li>No 5xx under concurrent mutations (no corruption, no deadlock).</li>
 *   <li>Derived state is always consistent with the base field that produced it
 *       (atomic pipeline guarantee: no partial writes survive the lock).</li>
 *   <li>Concurrent reads during active mutations never return a 5xx.</li>
 *   <li>Every mutation response lists the derivation that was re-evaluated.</li>
 * </ol>
 */
@SpringBootTest
@AutoConfigureMockMvc
class ModelControllerConcurrencyTest {

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;

    private static final int TIMEOUT_SEC = 30;

    // ── Test 1: concurrent mutations ──────────────────────────────────────────

    /**
     * 50 virtual threads each write a unique value to {@code $.counter} simultaneously.
     *
     * <p>Expected: every request returns 200 (no 5xx from concurrent access).
     * After all threads finish the derived field {@code $.doubled} must equal
     * {@code counter * 2} — proving that each mutation committed atomically and no
     * partial intermediate state was left behind.
     */
    @Test
    void concurrent_mutations_produce_no_5xx_and_derived_state_remains_consistent()
            throws Exception {

        String id = "conc-mut-consistency";
        createModel(id, """
                {
                  "id": "%s",
                  "schema": {},
                  "derivations": [
                    { "path": "$.doubled", "expr": "counter * 2" }
                  ]
                }
                """.formatted(id));

        int threads = 50;
        CountDownLatch start    = new CountDownLatch(1);
        CountDownLatch done     = new CountDownLatch(threads);
        List<Integer>  statuses = new CopyOnWriteArrayList<>();

        for (int i = 0; i < threads; i++) {
            final int value = i;
            Thread.ofVirtual().start(() -> {
                try {
                    start.await();
                    int status = mvc.perform(post("/models/" + id + "/mutations")
                                    .contentType(MediaType.APPLICATION_JSON)
                                    .content("{ \"$.counter\": " + value + " }"))
                            .andReturn().getResponse().getStatus();
                    statuses.add(status);
                } catch (Exception e) {
                    statuses.add(500);
                } finally {
                    done.countDown();
                }
            });
        }

        start.countDown();
        assertThat(done.await(TIMEOUT_SEC, TimeUnit.SECONDS))
                .as("all %d mutation threads must finish within %d s", threads, TIMEOUT_SEC)
                .isTrue();

        assertThat(statuses)
                .as("every concurrent mutation must succeed (no 5xx, no deadlock)")
                .hasSize(threads)
                .allSatisfy(s -> assertThat(s).isEqualTo(200));

        // The winning write (whichever committed last) must have an atomically
        // consistent derived value. A race condition would leave doubled != counter * 2.
        JsonNode state = getState(id);
        double counter = state.path("counter").asDouble();
        double doubled  = state.path("doubled").asDouble();
        assertThat(doubled)
                .as("$.doubled must equal $.counter * 2 — atomic pipeline guarantee")
                .isEqualTo(counter * 2);
    }

    // ── Test 2: concurrent reads during mutations ─────────────────────────────

    /**
     * 20 mutation threads and 20 reader threads run simultaneously.
     *
     * <p>Expected: no 5xx from either group. Readers may observe any consistent
     * snapshot (stale reads are acceptable); corrupt JSON or server errors are not.
     */
    @Test
    void concurrent_reads_during_mutations_never_produce_5xx() throws Exception {
        String id = "conc-read-write";
        createModel(id, """
                {
                  "id": "%s",
                  "schema": {},
                  "derivations": [
                    { "path": "$.score", "expr": "value * 10" }
                  ]
                }
                """.formatted(id));

        int mutators = 20;
        int readers  = 20;
        int total    = mutators + readers;

        CountDownLatch start          = new CountDownLatch(1);
        CountDownLatch done           = new CountDownLatch(total);
        List<Integer>  mutatorResults = new CopyOnWriteArrayList<>();
        List<Integer>  readerResults  = new CopyOnWriteArrayList<>();

        for (int i = 0; i < mutators; i++) {
            final int value = i;
            Thread.ofVirtual().start(() -> {
                try {
                    start.await();
                    int status = mvc.perform(post("/models/" + id + "/mutations")
                                    .contentType(MediaType.APPLICATION_JSON)
                                    .content("{ \"$.value\": " + value + " }"))
                            .andReturn().getResponse().getStatus();
                    mutatorResults.add(status);
                } catch (Exception e) {
                    mutatorResults.add(500);
                } finally {
                    done.countDown();
                }
            });
        }

        for (int i = 0; i < readers; i++) {
            Thread.ofVirtual().start(() -> {
                try {
                    start.await();
                    int status = mvc.perform(get("/models/" + id + "/state"))
                            .andReturn().getResponse().getStatus();
                    readerResults.add(status);
                } catch (Exception e) {
                    readerResults.add(500);
                } finally {
                    done.countDown();
                }
            });
        }

        start.countDown();
        assertThat(done.await(TIMEOUT_SEC, TimeUnit.SECONDS))
                .as("all %d threads must finish within %d s", total, TIMEOUT_SEC)
                .isTrue();

        assertThat(mutatorResults)
                .as("no mutation must produce a 5xx under concurrent reads")
                .hasSize(mutators)
                .allSatisfy(s -> assertThat(s).isEqualTo(200));

        assertThat(readerResults)
                .as("no read must produce a 5xx during concurrent mutations")
                .hasSize(readers)
                .allSatisfy(s -> assertThat(s).isEqualTo(200));
    }

    // ── Test 3: derivation always re-evaluated ────────────────────────────────

    /**
     * 30 threads concurrently mutate {@code $.a} and {@code $.b}.
     *
     * <p>Expected: every mutation response includes {@code "$.result"} in
     * {@code derivedUpdated}. Since dirty propagation is path-based (not value-based),
     * the derivation must be re-evaluated regardless of whether the incoming values
     * differ from the previous ones.
     */
    @Test
    void every_concurrent_mutation_response_lists_re_evaluated_derivation() throws Exception {
        String id = "conc-derive-reported";
        createModel(id, """
                {
                  "id": "%s",
                  "schema": {},
                  "derivations": [
                    { "path": "$.result", "expr": "a + b" }
                  ]
                }
                """.formatted(id));

        int threads = 30;
        CountDownLatch start          = new CountDownLatch(1);
        CountDownLatch done           = new CountDownLatch(threads);
        AtomicInteger  deriveMissed   = new AtomicInteger();

        for (int i = 0; i < threads; i++) {
            final int val = i;
            Thread.ofVirtual().start(() -> {
                try {
                    start.await();
                    MvcResult r = mvc.perform(post("/models/" + id + "/mutations")
                                    .contentType(MediaType.APPLICATION_JSON)
                                    .content("{ \"$.a\": " + val + ", \"$.b\": " + val + " }"))
                            .andReturn();
                    JsonNode body = mapper.readTree(r.getResponse().getContentAsString());
                    boolean found = false;
                    for (JsonNode node : body.path("derivedUpdated")) {
                        if ("$.result".equals(node.asText())) { found = true; break; }
                    }
                    if (!found) deriveMissed.incrementAndGet();
                } catch (Exception e) {
                    deriveMissed.incrementAndGet();
                } finally {
                    done.countDown();
                }
            });
        }

        start.countDown();
        assertThat(done.await(TIMEOUT_SEC, TimeUnit.SECONDS))
                .as("all %d derivation-check threads must finish within %d s", threads, TIMEOUT_SEC)
                .isTrue();

        assertThat(deriveMissed.get())
                .as("every mutation response must include '$.result' in derivedUpdated")
                .isZero();
    }

    // ── Test 4: rollback constraint under concurrency ─────────────────────────

    /**
     * 20 threads send valid mutations; 20 threads send mutations that violate a
     * ROLLBACK constraint. Verifies that violations are cleanly rejected with 409
     * and that the constraint-passing mutations still commit correctly (no deadlock
     * or corruption caused by interleaved rollbacks).
     */
    @Test
    void rollback_constraints_under_concurrency_produce_409_without_corrupting_state()
            throws Exception {

        String id = "conc-rollback";
        createModel(id, """
                {
                  "id": "%s",
                  "schema": {},
                  "constraints": [
                    { "id": "non-negative", "expr": "v >= 0",
                      "message": "v must be non-negative", "policy": "rollback" }
                  ]
                }
                """.formatted(id));

        int valid   = 20;
        int invalid = 20;
        int total   = valid + invalid;

        CountDownLatch start          = new CountDownLatch(1);
        CountDownLatch done           = new CountDownLatch(total);
        List<Integer>  validResults   = new CopyOnWriteArrayList<>();
        List<Integer>  invalidResults = new CopyOnWriteArrayList<>();

        for (int i = 0; i < valid; i++) {
            final int value = i;
            Thread.ofVirtual().start(() -> {
                try {
                    start.await();
                    int s = mvc.perform(post("/models/" + id + "/mutations")
                                    .contentType(MediaType.APPLICATION_JSON)
                                    .content("{ \"$.v\": " + value + " }"))
                            .andReturn().getResponse().getStatus();
                    validResults.add(s);
                } catch (Exception e) {
                    validResults.add(500);
                } finally {
                    done.countDown();
                }
            });
        }

        for (int i = 0; i < invalid; i++) {
            final int value = -(i + 1); // guaranteed negative
            Thread.ofVirtual().start(() -> {
                try {
                    start.await();
                    int s = mvc.perform(post("/models/" + id + "/mutations")
                                    .contentType(MediaType.APPLICATION_JSON)
                                    .content("{ \"$.v\": " + value + " }"))
                            .andReturn().getResponse().getStatus();
                    invalidResults.add(s);
                } catch (Exception e) {
                    invalidResults.add(500);
                } finally {
                    done.countDown();
                }
            });
        }

        start.countDown();
        assertThat(done.await(TIMEOUT_SEC, TimeUnit.SECONDS))
                .as("all %d threads must finish within %d s", total, TIMEOUT_SEC)
                .isTrue();

        assertThat(validResults)
                .as("valid mutations must not produce 5xx under concurrent rollbacks")
                .hasSize(valid)
                .allSatisfy(s -> assertThat(s).isEqualTo(200));

        assertThat(invalidResults)
                .as("constraint-violating mutations must be rejected with 409")
                .hasSize(invalid)
                .allSatisfy(s -> assertThat(s).isEqualTo(409));

        // After all rollbacks, the model's value must be non-negative (constraint holds).
        JsonNode state = getState(id);
        assertThat(state.path("v").asInt())
                .as("state must satisfy the constraint after concurrent rollbacks")
                .isGreaterThanOrEqualTo(0);
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private void createModel(String id, String specJson) throws Exception {
        mvc.perform(post("/models")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(specJson))
                .andExpect(status().isCreated());
    }

    private JsonNode getState(String id) throws Exception {
        MvcResult r = mvc.perform(get("/models/" + id + "/state"))
                .andExpect(status().isOk())
                .andReturn();
        return mapper.readTree(r.getResponse().getContentAsString());
    }
}
