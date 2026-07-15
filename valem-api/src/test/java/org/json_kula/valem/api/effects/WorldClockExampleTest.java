package org.json_kula.valem.api.effects;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpServer;
import org.json_kula.valem.service.ModelService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.is;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * End-to-end test of the shipped UI example {@code world-clock.json} — a minimal, focused pair of
 * effect kinds ({@code server}/http and {@code timer}, deliberately no {@code llm} or {@code caller})
 * that compose into a <em>self-refreshing poll</em>: the {@code server} effect fetches the current
 * date/time for the selected country's timezone from a real public API, and the {@code timer} effect
 * re-arms every 10s by bumping a {@code clock.tick} counter the {@code server} effect is keyed to, so
 * the fetch re-fires each tick.
 *
 * <p>Like {@code PackageDeliveryTrackerExampleTest} before it, this drives the real REST surface end
 * to end ({@code POST /models}, {@code GET /models/{id}/view}, {@code POST /models/{id}/mutations}).
 * The example file is the single source of truth — the test loads it, rewrites the server effect's URL
 * to a local stub and shortens the timer's delay so several ticks fire during the test, and asserts
 * both the first fetch and the recurring re-fetch driven by the timer.
 */
@SpringBootTest(properties = {"valem.effects.allow-private-ips=true", "valem.effects.allow-insecure-http=true"})
@AutoConfigureMockMvc
class WorldClockExampleTest {

    @Autowired MockMvc mvc;
    @Autowired ModelService service;
    @Autowired ObjectMapper mapper;

    private HttpServer stub;
    private int port;
    /** One request per fetch — lets the test observe that the timer re-fires the lookup. */
    private final AtomicInteger requests = new AtomicInteger(0);

    @BeforeEach
    void startStub() throws Exception {
        stub = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        // Each call returns a distinct, incrementing datetime so the test can see the value change on
        // each recurring re-fetch (mimicking a live clock advancing).
        stub.createContext("/api/Time/current/zone", exchange -> {
            int n = requests.incrementAndGet();
            byte[] body = ("{ \"dateTime\": \"2026-07-12T00:00:0" + (n % 10)
                    + "\", \"time\": \"00:0" + (n % 10) + "\", \"dayOfWeek\": \"Sunday\" }")
                    .getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        stub.start();
        port = stub.getAddress().getPort();
    }

    @AfterEach
    void stopStub() {
        if (stub != null) stub.stop(0);
    }

    @Test
    void world_clock_example_fetches_time_and_the_timer_keeps_refreshing_it() throws Exception {
        ObjectNode root = (ObjectNode) mapper.readTree(loadExample());
        // Strip the UI-only metadata fields (not part of ModelSpec).
        root.remove("_name");
        root.remove("_description");

        String id = "world-clock-" + System.nanoTime();
        root.put("id", id);

        // Point the server effect at the local stub and shorten the timer so several ticks fire fast.
        for (JsonNode e : root.withArray("effects")) {
            String executor = e.path("executor").asText();
            if ("server".equals(executor)) {
                String url = e.path("request").path("url").asText()
                        .replace("https://timeapi.io", "http://127.0.0.1:" + port);
                ((ObjectNode) e.get("request")).put("url", url);
            } else if ("timer".equals(executor)) {
                ((ObjectNode) e).put("afterMs", "250");
            }
        }

        mvc.perform(post("/models")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(root)))
                .andExpect(status().isCreated());

        // 1. VIEW (before) — a fresh clock: no zone selected, tick 0, no time fetched yet.
        mvc.perform(get("/models/" + id + "/view"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.viewId", is("main")))
                .andExpect(jsonPath("$.title", is("World Clock")))
                .andExpect(jsonPath("$.components[0].id", is("countryField")))
                .andExpect(jsonPath("$.components[6].id", is("tickLabel")))
                .andExpect(jsonPath("$.components[6].value", is(0)));

        // 2. FORM SUBMIT — pick a country, exactly as the React renderer's onChange would.
        mvc.perform(post("/models/" + id + "/mutations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{ \"$.location.timeZone\": \"Europe/London\" }"))
                .andExpect(status().isOk());

        // 3. HTTP (server) EFFECT — the time lookup fires and folds the fetched date/time back.
        assertThat(awaitTextMatches(id, "$.clock.dateTime", "2026-07-12T00:00:0\\d")).isTrue();
        assertThat(service.getFieldValue(id, "$.clock.ioFetch.phase").asText()).isEqualTo("applied");
        assertThat(service.getFieldValue(id, "$.clock.dayOfWeek").asText()).isEqualTo("Sunday");
        String firstFetched = service.getFieldValue(id, "$.clock.dateTime").asText();

        // VIEW (after http fold-back) — the form reflects the fetched time.
        mvc.perform(get("/models/" + id + "/view"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.components[0].value", is("Europe/London")))
                .andExpect(jsonPath("$.components[2].id", is("zoneLabel")))
                .andExpect(jsonPath("$.components[2].value", is("Europe/London")))
                .andExpect(jsonPath("$.components[3].id", is("dateTimeLabel")));

        // 4. TIMER EFFECT — every 250ms it bumps clock.tick, which re-fires the keyed http lookup.
        assertThat(awaitTick(id, 2)).isTrue();
        // The recurring timer re-fetched: more than the single initial request reached the stub, and
        // the folded datetime advanced from the first value.
        assertThat(requests.get()).as("timer re-fired the http lookup").isGreaterThanOrEqualTo(2);
        assertThat(awaitTextChanged(id, "$.clock.dateTime", firstFetched))
                .as("the clock kept refreshing to a new value").isTrue();
    }

    private String loadExample() throws Exception {
        for (String candidate : new String[]{
                "../valem-ui/src/examples/world-clock.json",
                "valem-ui/src/examples/world-clock.json"}) {
            Path p = Path.of(candidate);
            if (Files.exists(p)) return Files.readString(p);
        }
        throw new AssertionError("could not locate world-clock.json (cwd=" + Path.of(".").toAbsolutePath() + ")");
    }

    /** Polls a text field until it matches {@code regex}, up to ~5s. */
    private boolean awaitTextMatches(String id, String path, String regex) throws InterruptedException {
        for (int i = 0; i < 100; i++) {
            JsonNode v = service.getFieldValue(id, path);
            if (v != null && v.isTextual() && v.asText().matches(regex)) return true;
            Thread.sleep(50);
        }
        return false;
    }

    /** Polls a text field until it differs from {@code previous} (proving a recurring re-fetch), ~5s. */
    private boolean awaitTextChanged(String id, String path, String previous) throws InterruptedException {
        for (int i = 0; i < 100; i++) {
            JsonNode v = service.getFieldValue(id, path);
            if (v != null && v.isTextual() && !v.asText().equals(previous)) return true;
            Thread.sleep(50);
        }
        return false;
    }

    /** Polls clock.tick until it reaches at least {@code min} (proving the timer re-armed), ~5s. */
    private boolean awaitTick(String id, int min) throws InterruptedException {
        for (int i = 0; i < 100; i++) {
            JsonNode v = service.getFieldValue(id, "$.clock.tick");
            if (v != null && v.isNumber() && v.asInt() >= min) return true;
            Thread.sleep(50);
        }
        return false;
    }
}
