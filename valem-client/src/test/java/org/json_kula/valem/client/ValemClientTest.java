package org.json_kula.valem.client;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.json_kula.valem.client.ValemTypes.AuditQuery;
import org.json_kula.valem.client.ValemTypes.ChangeEvent;
import org.json_kula.valem.client.ValemTypes.CreateModelResponse;
import org.json_kula.valem.client.ValemTypes.MutationResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ValemClientTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private HttpServer server;

    record Recorded(String method, String uri, String body, com.sun.net.httpserver.Headers headers) {}

    /** Starts a mock server; {@code handler} maps each recorded request to (status, contentType, body). */
    private String startServer(List<Recorded> sink, Function<Recorded, Object[]> handler) throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", (HttpExchange ex) -> {
            String body = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            Recorded rec = new Recorded(ex.getRequestMethod(),
                    ex.getRequestURI().toString(), body, ex.getRequestHeaders());
            sink.add(rec);
            Object[] out = handler.apply(rec); // [int status, String contentType|null, String body|null]
            int status = (int) out[0];
            String responseBody = out[2] == null ? "" : (String) out[2];
            byte[] bytes = responseBody.getBytes(StandardCharsets.UTF_8);
            if (out[1] != null) ex.getResponseHeaders().add("Content-Type", (String) out[1]);
            if (bytes.length == 0) {
                ex.sendResponseHeaders(status, -1);
            } else {
                ex.sendResponseHeaders(status, bytes.length);
                ex.getResponseBody().write(bytes);
            }
            ex.close();
        });
        server.start();
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @AfterEach
    void stop() {
        if (server != null) server.stop(0);
    }

    private ValemClient client(String baseUrl, String apiKey) {
        return new ValemClient(baseUrl, apiKey, HttpClient.newHttpClient(), null,
                MAPPER, new long[]{10, 10});
    }

    // ── pure helpers ─────────────────────────────────────────────────────────

    @Test
    void addressToPointer_converts_canonical_addresses() {
        assertThat(ValemClient.addressToPointer("$.order.total")).isEqualTo("/order/total");
        assertThat(ValemClient.addressToPointer("$.items[0].qty")).isEqualTo("/items/0/qty");
        assertThat(ValemClient.addressToPointer("$")).isEqualTo("");
    }

    @Test
    void buildSubscribeUri_maps_scheme_and_includes_token_and_paths() {
        ValemClient c = new ValemClient("https://api.example.com/", "secret");
        URI uri = c.buildSubscribeUri("m1", List.of("$.a", "$.b"));
        assertThat(uri.toString()).startsWith("wss://api.example.com/models/m1/subscribe?");
        assertThat(uri.toString()).contains("token=secret");
        assertThat(uri.getRawQuery()).contains("paths=");
    }

    // ── REST ───────────────────────────────────────────────────────────────────

    @Test
    void createModel_posts_json_and_parses_response() throws Exception {
        List<Recorded> reqs = new ArrayList<>();
        String base = startServer(reqs, r -> new Object[]{201, "application/json",
                "{\"id\":\"m1\",\"status\":\"created\"}"});
        CreateModelResponse res = client(base, null)
                .createModel(MAPPER.readTree("{\"id\":\"m1\",\"schema\":{}}"));
        assertThat(res).isEqualTo(new CreateModelResponse("m1", "created"));
        assertThat(reqs.get(0).method()).isEqualTo("POST");
        assertThat(reqs.get(0).uri()).isEqualTo("/models");
        assertThat(MAPPER.readTree(reqs.get(0).body()).path("id").asText()).isEqualTo("m1");
    }

    @Test
    void apiKey_is_sent_as_bearer_header() throws Exception {
        List<Recorded> reqs = new ArrayList<>();
        String base = startServer(reqs, r -> new Object[]{200, "application/json", "[]"});
        client(base, "k123").listModels();
        assertThat(reqs.get(0).headers().getFirst("Authorization")).isEqualTo("Bearer k123");
    }

    @Test
    void mutate_posts_address_map_and_forwards_x_view() throws Exception {
        List<Recorded> reqs = new ArrayList<>();
        String base = startServer(reqs, r -> new Object[]{200, "application/json",
                "{\"success\":true,\"mutatedPaths\":[\"$.n\"],\"derivedUpdated\":[\"$.d\"]," +
                "\"flaggedConstraints\":[],\"dispatchedEffects\":[],\"traces\":[]}"});
        MutationResponse res = client(base, null).mutate("m1", Map.of("$.n", 5), "main");
        assertThat(res.success()).isTrue();
        assertThat(res.derivedUpdated()).containsExactly("$.d");
        Recorded r = reqs.get(0);
        assertThat(r.uri()).isEqualTo("/models/m1/mutations");
        assertThat(r.headers().getFirst("X-View")).isEqualTo("main");
        assertThat(MAPPER.readTree(r.body()).path("$.n").asInt()).isEqualTo(5);
    }

    @Test
    void setField_sends_json_patch_add_with_pointer() throws Exception {
        List<Recorded> reqs = new ArrayList<>();
        String base = startServer(reqs, r -> new Object[]{200, "application/json",
                "{\"success\":true,\"mutatedPaths\":[],\"derivedUpdated\":[]," +
                "\"flaggedConstraints\":[],\"dispatchedEffects\":[],\"traces\":[]}"});
        client(base, null).setField("m1", "$.order.qty", MAPPER.getNodeFactory().numberNode(3));
        Recorded r = reqs.get(0);
        assertThat(r.uri()).isEqualTo("/models/m1/mutations/patch");
        assertThat(r.headers().getFirst("Content-Type")).isEqualTo("application/json-patch+json");
        var ops = MAPPER.readTree(r.body());
        assertThat(ops.get(0).path("op").asText()).isEqualTo("add");
        assertThat(ops.get(0).path("path").asText()).isEqualTo("/order/qty");
        assertThat(ops.get(0).path("value").asInt()).isEqualTo(3);
    }

    @Test
    void audit_builds_query_string_from_filters() throws Exception {
        List<Recorded> reqs = new ArrayList<>();
        String base = startServer(reqs, r -> new Object[]{200, "application/json", "[]"});
        client(base, null).audit("m1", new AuditQuery("$.order",
                Instant.parse("2026-01-01T00:00:00Z"), null, 10));
        String uri = reqs.get(0).uri();
        assertThat(uri).startsWith("/models/m1/audit?");
        assertThat(uri).contains("path=");
        assertThat(uri).contains("from=");
        assertThat(uri).contains("limit=10");
    }

    @Test
    void verifyAudit_calls_verify_endpoint_and_parses_result() throws Exception {
        List<Recorded> reqs = new ArrayList<>();
        String base = startServer(reqs, r -> new Object[]{200, "application/json",
                "{\"valid\":true,\"recordsChecked\":3,\"detail\":\"ok\"}"});
        var v = client(base, null).verifyAudit("m1");
        assertThat(v.valid()).isTrue();
        assertThat(v.recordsChecked()).isEqualTo(3);
        assertThat(reqs.get(0).uri()).isEqualTo("/models/m1/audit/verify");
    }

    @Test
    void non_2xx_throws_ValemException_with_status_and_body() throws Exception {
        List<Recorded> reqs = new ArrayList<>();
        String base = startServer(reqs, r -> new Object[]{409, "text/plain", "constraint violated"});
        assertThatThrownBy(() -> client(base, null).mutate("m1", Map.of("$.n", 1)))
                .isInstanceOfSatisfying(ValemException.class, e -> {
                    assertThat(e.status()).isEqualTo(409);
                    assertThat(e.body()).contains("constraint violated");
                });
    }

    @Test
    void delete_204_resolves_without_body() throws Exception {
        List<Recorded> reqs = new ArrayList<>();
        String base = startServer(reqs, r -> new Object[]{204, null, null});
        client(base, null).deleteModel("m1");
        assertThat(reqs.get(0).method()).isEqualTo("DELETE");
    }

    // ── WebSocket reconnect (fake connector) ────────────────────────────────────

    /** Minimal no-op WebSocket for the fake connector. */
    static final class NoopWebSocket implements WebSocket {
        volatile boolean aborted = false;
        volatile boolean closeSent = false;
        @Override public CompletableFuture<WebSocket> sendText(CharSequence data, boolean last) { return CompletableFuture.completedFuture(this); }
        @Override public CompletableFuture<WebSocket> sendBinary(java.nio.ByteBuffer data, boolean last) { return CompletableFuture.completedFuture(this); }
        @Override public CompletableFuture<WebSocket> sendPing(java.nio.ByteBuffer message) { return CompletableFuture.completedFuture(this); }
        @Override public CompletableFuture<WebSocket> sendPong(java.nio.ByteBuffer message) { return CompletableFuture.completedFuture(this); }
        @Override public CompletableFuture<WebSocket> sendClose(int statusCode, String reason) { closeSent = true; return CompletableFuture.completedFuture(this); }
        @Override public void request(long n) {}
        @Override public String getSubprotocol() { return ""; }
        @Override public boolean isOutputClosed() { return false; }
        @Override public boolean isInputClosed() { return false; }
        @Override public void abort() { aborted = true; }
    }

    @Test
    void subscribe_delivers_events_and_reconnects_then_stops_on_close() throws Exception {
        AtomicReference<WebSocket.Listener> lastListener = new AtomicReference<>();
        CountDownLatch connectLatch = new CountDownLatch(2);   // initial + one reconnect
        int[] connects = {0};

        WsConnector fake = (uri, listener) -> {
            connects[0]++;
            lastListener.set(listener);
            connectLatch.countDown();
            return CompletableFuture.completedFuture(new NoopWebSocket());
        };
        ValemClient c = new ValemClient("http://localhost:8080", "tok",
                HttpClient.newHttpClient(), fake, MAPPER, new long[]{10, 10});

        List<String> events = new ArrayList<>();
        int[] opens = {0};
        Subscription sub = c.subscribe("m1", new ChangeListener() {
            @Override public void onEvent(ChangeEvent e) { events.add(e.modelId()); }
            @Override public void onOpen() { opens[0]++; }
        }, List.of("$.order"));

        assertThat(connects[0]).isEqualTo(1);
        WebSocket.Listener l = lastListener.get();
        NoopWebSocket ws = new NoopWebSocket();
        l.onOpen(ws);
        assertThat(opens[0]).isEqualTo(1);

        // deliver a change event as a single (last=true) text frame
        String frame = "{\"modelId\":\"m1\",\"mutatedPaths\":[],\"derivedUpdated\":[]," +
                "\"flaggedConstraints\":[],\"dispatchedEffects\":[]}";
        CompletionStage<?> stage = l.onText(ws, frame, true);
        assertThat(events).containsExactly("m1");

        // unexpected close -> reconnect fires on the backoff tick
        l.onClose(ws, 1006, "gone");
        assertThat(connectLatch.await(2, TimeUnit.SECONDS)).isTrue();
        assertThat(connects[0]).isEqualTo(2);

        // explicit close prevents further reconnects
        sub.close();
        WebSocket.Listener afterClose = lastListener.get();
        afterClose.onClose(new NoopWebSocket(), 1006, "gone again");
        Thread.sleep(60);
        assertThat(connects[0]).isEqualTo(2);
        c.close();
    }
}
