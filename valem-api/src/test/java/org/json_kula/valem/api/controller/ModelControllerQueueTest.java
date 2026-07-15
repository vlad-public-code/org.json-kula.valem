package org.json_kula.valem.api.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.json_kula.valem.core.engine.ModelRuntime;
import org.json_kula.valem.service.ModelService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Verifies the bounded per-model mutation queue introduced in {@link ModelService}.
 *
 * <p>Runs with {@code valem.mutation-queue-size=3} so tests can reliably fill
 * the queue without needing hundreds of threads. That means up to 3 concurrent
 * mutations are allowed (1 executing + 2 waiting); the 4th is immediately rejected
 * with HTTP 429.
 */
@SpringBootTest(properties = "valem.mutation-queue-size=3")
@AutoConfigureMockMvc
class ModelControllerQueueTest {

    @Autowired MockMvc       mvc;
    @Autowired ModelService  service;

    private static final int CAPACITY    = 3;
    private static final int TIMEOUT_SEC = 15;

    // ── Test 1: overflow returns 429 and queue drains after release ───────────

    /**
     * Fills the queue to capacity by holding the model's JVM monitor from test code
     * (bypassing the semaphore so all CAPACITY permits are available to mutation threads).
     * Once full, a (CAPACITY+1)th request must return 429 immediately.
     * After releasing the monitor every queued request must complete with 200.
     */
    @Test
    void overflow_returns_429_and_queue_drains_after_release() throws Exception {
        String id = "queue-overflow";
        createModel(id);

        ModelRuntime rt = service.registry().find(id).orElseThrow();

        CountDownLatch holdLatch = new CountDownLatch(1); // released to let holder exit
        CountDownLatch lockHeld  = new CountDownLatch(1); // signals holder has the lock

        // Hold the JVM monitor without consuming a semaphore permit
        Thread holder = Thread.ofVirtual().start(() -> {
            synchronized (rt) {
                lockHeld.countDown();
                try { holdLatch.await(); }
                catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            }
        });
        lockHeld.await();

        // Launch CAPACITY mutation threads — each acquires a semaphore permit, then
        // blocks on synchronized(rt) because the holder owns the JVM lock
        CountDownLatch waitersDone   = new CountDownLatch(CAPACITY);
        List<Integer>  waiterResults = new CopyOnWriteArrayList<>();
        for (int i = 0; i < CAPACITY; i++) {
            Thread.ofVirtual().start(() -> {
                try {
                    int s = mvc.perform(post("/models/" + id + "/mutations")
                                    .contentType(MediaType.APPLICATION_JSON)
                                    .content("{\"$.x\": 1}"))
                            .andReturn().getResponse().getStatus();
                    waiterResults.add(s);
                } catch (Exception e) {
                    waiterResults.add(500);
                } finally {
                    waitersDone.countDown();
                }
            });
        }

        // Spin until all CAPACITY semaphore permits are consumed
        long deadline = System.currentTimeMillis() + TIMEOUT_SEC * 1000L;
        while (service.availableMutationSlots(id) > 0 && System.currentTimeMillis() < deadline) {
            Thread.sleep(5);
        }
        assertThat(service.availableMutationSlots(id))
                .as("all semaphore permits must be acquired before testing overflow")
                .isZero();

        // Queue is full — overflow request must be rejected immediately with 429
        int overflowStatus = mvc.perform(post("/models/" + id + "/mutations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"$.x\": 2}"))
                .andReturn().getResponse().getStatus();
        assertThat(overflowStatus)
                .as("overflow request when queue is full must return 429")
                .isEqualTo(429);

        // Release the holder — queued mutations should proceed and all return 200
        holdLatch.countDown();
        assertThat(waitersDone.await(TIMEOUT_SEC, TimeUnit.SECONDS))
                .as("queued mutations must complete after JVM lock is released")
                .isTrue();
        assertThat(waiterResults)
                .as("all queued mutations must succeed")
                .hasSize(CAPACITY)
                .allSatisfy(s -> assertThat(s).isEqualTo(200));

        // Queue is fully drained — a fresh mutation must succeed
        int drainedStatus = mvc.perform(post("/models/" + id + "/mutations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"$.x\": 3}"))
                .andReturn().getResponse().getStatus();
        assertThat(drainedStatus)
                .as("mutation after queue drained must succeed")
                .isEqualTo(200);

        holder.join(2_000);
    }

    // ── Test 2: queues are independent per model ──────────────────────────────

    /**
     * Floods model A's queue to capacity, then verifies that model B continues
     * to accept mutations — each model's semaphore is independent.
     */
    @Test
    void queue_limits_are_independent_per_model() throws Exception {
        String idA = "queue-model-a";
        String idB = "queue-model-b";
        createModel(idA);
        createModel(idB);

        ModelRuntime rtA = service.registry().find(idA).orElseThrow();

        CountDownLatch holdLatch = new CountDownLatch(1);
        CountDownLatch lockHeld  = new CountDownLatch(1);

        Thread holder = Thread.ofVirtual().start(() -> {
            synchronized (rtA) {
                lockHeld.countDown();
                try { holdLatch.await(); }
                catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            }
        });
        lockHeld.await();

        // Flood model A's queue to capacity
        for (int i = 0; i < CAPACITY; i++) {
            Thread.ofVirtual().start(() -> {
                try {
                    mvc.perform(post("/models/" + idA + "/mutations")
                                    .contentType(MediaType.APPLICATION_JSON)
                                    .content("{\"$.x\": 1}"))
                            .andReturn();
                } catch (Exception ignored) {}
            });
        }

        long deadline = System.currentTimeMillis() + TIMEOUT_SEC * 1000L;
        while (service.availableMutationSlots(idA) > 0 && System.currentTimeMillis() < deadline) {
            Thread.sleep(5);
        }
        assertThat(service.availableMutationSlots(idA))
                .as("model A queue must be full before testing model B")
                .isZero();

        // Model B must be unaffected
        int statusB = mvc.perform(post("/models/" + idB + "/mutations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"$.x\": 1}"))
                .andReturn().getResponse().getStatus();
        assertThat(statusB)
                .as("model B must accept mutations while model A is at capacity")
                .isEqualTo(200);

        holdLatch.countDown(); // cleanup
        holder.join(2_000);
    }

    // ── Test 3: exactly CAPACITY concurrent mutations all succeed ─────────────

    /**
     * Sends exactly CAPACITY mutation requests simultaneously.
     * None should be rejected — the limit applies only when exceeded.
     */
    @Test
    void mutations_up_to_capacity_all_succeed() throws Exception {
        String id = "queue-at-capacity";
        createModel(id);

        CountDownLatch start   = new CountDownLatch(1);
        CountDownLatch done    = new CountDownLatch(CAPACITY);
        List<Integer>  results = new CopyOnWriteArrayList<>();

        for (int i = 0; i < CAPACITY; i++) {
            final int val = i;
            Thread.ofVirtual().start(() -> {
                try {
                    start.await();
                    int s = mvc.perform(post("/models/" + id + "/mutations")
                                    .contentType(MediaType.APPLICATION_JSON)
                                    .content("{\"$.x\": " + val + "}"))
                            .andReturn().getResponse().getStatus();
                    results.add(s);
                } catch (Exception e) {
                    results.add(500);
                } finally {
                    done.countDown();
                }
            });
        }

        start.countDown();
        assertThat(done.await(TIMEOUT_SEC, TimeUnit.SECONDS))
                .as("all %d concurrent mutations must finish within %d s", CAPACITY, TIMEOUT_SEC)
                .isTrue();
        assertThat(results)
                .as("all %d concurrent mutations within capacity must succeed", CAPACITY)
                .hasSize(CAPACITY)
                .allSatisfy(s -> assertThat(s).isEqualTo(200));
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private void createModel(String id) throws Exception {
        mvc.perform(post("/models")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                { "id": "%s", "schema": {}, "derivations": [] }
                                """.formatted(id)))
                .andExpect(status().isCreated());
    }
}
