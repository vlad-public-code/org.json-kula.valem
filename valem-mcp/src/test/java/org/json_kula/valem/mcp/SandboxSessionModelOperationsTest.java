package org.json_kula.valem.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Exercises {@link SandboxSessionModelOperations} — the {@code remote_with_browser} facade — against a
 * hand-rolled fake sandbox server, covering the device-flow pairing state machine (pending -> paired,
 * resuming the same pairing rather than re-minting) and the transparent id-namespacing that lets the
 * agent keep using the plain id it gave {@code create_model}.
 */
class SandboxSessionModelOperationsTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private HttpServer server;
    private final AtomicInteger mintCount = new AtomicInteger();
    private final AtomicInteger pollCount = new AtomicInteger();
    private final List<String> sessionTokensSeen = new CopyOnWriteArrayList<>();
    private volatile int pendingPollsBeforeSuccess = 0;

    private static final String NAMESPACE = "nabc1234567";
    private static final String SESSION_TOKEN = "s-test-token";
    private static final String PAIR_CODE = "AAAA-BBBB";
    private static final String DEVICE_SECRET = "device-secret-xyz";
    private static final String USER_CODE = "CCCC-DDDD";

    private String startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);

        server.createContext("/sandbox/pair", ex -> {
            if ("POST".equals(ex.getRequestMethod()) && ex.getRequestURI().getPath().equals("/sandbox/pair")) {
                mintCount.incrementAndGet();
                String body = ("{\"pairCode\":\"" + PAIR_CODE + "\",\"deviceSecret\":\"" + DEVICE_SECRET
                        + "\",\"userCode\":\"" + USER_CODE + "\","
                        + "\"verificationUri\":\"http://example/?pair=" + PAIR_CODE + "\","
                        + "\"expiresInSec\":600,\"intervalSec\":0}");
                respond(ex, 200, body);
                return;
            }
            respond(ex, 404, "{\"error\":\"unknown_code\"}");
        });

        server.createContext("/sandbox/pair/token", ex -> {
            pollCount.incrementAndGet();
            if (pollCount.get() <= pendingPollsBeforeSuccess) {
                respond(ex, 409, "{\"error\":\"authorization_pending\"}");
            } else {
                respond(ex, 200, "{\"sessionToken\":\"" + SESSION_TOKEN + "\",\"namespaceId\":\"" + NAMESPACE + "\"}");
            }
        });

        server.createContext("/models", ex -> {
            sessionTokensSeen.add(ex.getRequestHeaders().getFirst("X-Session-Token"));
            handleModels(ex);
        });

        server.start();
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    private void handleModels(HttpExchange ex) throws IOException {
        String path = ex.getRequestURI().getPath();
        String method = ex.getRequestMethod();

        if (path.equals("/models") && method.equals("POST")) {
            String body = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            JsonNode node = MAPPER.readTree(body);
            String plainId = node.path("id").asText();
            String namespacedId = NAMESPACE + "__" + plainId;
            respond(ex, 201, "{\"id\":\"" + namespacedId + "\",\"status\":\"created\"}");
            return;
        }
        if (path.matches("/models/[^/]+") && method.equals("GET")) {
            String id = path.substring("/models/".length());
            assertThat(id).startsWith(NAMESPACE + "__"); // proves the wrapper translated the id inbound
            respond(ex, 200, "{\"id\":\"" + id + "\",\"version\":\"1.0.0\",\"derivationCount\":0,"
                    + "\"metaDerivationCount\":0,\"constraintCount\":0,\"effectCount\":0}");
            return;
        }
        if (path.equals("/models") && method.equals("GET")) {
            respond(ex, 200, "[\"" + NAMESPACE + "__loan\",\"other-namespace__someone-elses-model\"]");
            return;
        }
        respond(ex, 404, "{\"error\":\"not_found\"}");
    }

    private void respond(HttpExchange ex, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().add("Content-Type", "application/json");
        ex.sendResponseHeaders(status, bytes.length);
        ex.getResponseBody().write(bytes);
        ex.close();
    }

    @AfterEach
    void stop() {
        if (server != null) server.stop(0);
    }

    // ── pairing state machine ────────────────────────────────────────────────

    @Test
    void pairBrowser_pendingThenPaired() throws IOException {
        pendingPollsBeforeSuccess = 2;
        String baseUrl = startServer();
        SandboxSessionModelOperations ops =
                new SandboxSessionModelOperations(baseUrl, MAPPER, Duration.ofSeconds(30));

        PairResult result = ops.pairBrowser();

        assertThat(result.status()).isEqualTo("paired");
        assertThat(result.namespaceId()).isEqualTo(NAMESPACE);
        assertThat(mintCount.get()).isEqualTo(1);
        assertThat(pollCount.get()).isGreaterThanOrEqualTo(3); // 2 pending + 1 success
    }

    @Test
    void pairBrowser_alreadyPaired_isIdempotent() throws IOException {
        pendingPollsBeforeSuccess = 0;
        String baseUrl = startServer();
        SandboxSessionModelOperations ops =
                new SandboxSessionModelOperations(baseUrl, MAPPER, Duration.ofSeconds(30));

        assertThat(ops.pairBrowser().status()).isEqualTo("paired");
        int mintsAfterFirstPairing = mintCount.get();

        PairResult second = ops.pairBrowser();
        assertThat(second.status()).isEqualTo("already_paired");
        assertThat(second.namespaceId()).isEqualTo(NAMESPACE);
        assertThat(mintCount.get()).isEqualTo(mintsAfterFirstPairing); // no re-mint
    }

    @Test
    void pairBrowser_returnsPendingWithinBudget_andResumesSamePairingNextCall() throws IOException {
        pendingPollsBeforeSuccess = Integer.MAX_VALUE; // never approved
        String baseUrl = startServer();
        SandboxSessionModelOperations ops =
                new SandboxSessionModelOperations(baseUrl, MAPPER, Duration.ofMillis(200));

        PairResult first = ops.pairBrowser();
        assertThat(first.status()).isEqualTo("pending");
        assertThat(first.verificationUri()).contains(PAIR_CODE);
        assertThat(first.userCode()).isEqualTo(USER_CODE);
        assertThat(mintCount.get()).isEqualTo(1);

        PairResult second = ops.pairBrowser();
        assertThat(second.status()).isEqualTo("pending");
        // Same pairing resumed — not a second mint.
        assertThat(mintCount.get()).isEqualTo(1);
    }

    // ── id namespacing transparency ──────────────────────────────────────────

    @Test
    void modelOperations_translateIdsTransparently_onceProven() throws IOException {
        pendingPollsBeforeSuccess = 0;
        String baseUrl = startServer();
        SandboxSessionModelOperations ops =
                new SandboxSessionModelOperations(baseUrl, MAPPER, Duration.ofSeconds(30));
        assertThat(ops.pairBrowser().status()).isEqualTo("paired");

        // getInfo("loan") must translate to GET /models/<namespace>__loan server-side (asserted in the
        // handler), and translate the namespaced id BACK to "loan" in the returned ModelInfo.
        var info = ops.getInfo("loan");
        assertThat(info.id()).isEqualTo("loan");

        // Every REST call after pairing must carry the session token.
        assertThat(sessionTokensSeen).isNotEmpty().allMatch(SESSION_TOKEN::equals);
    }

    @Test
    void listModels_scopesToOwnNamespaceAndStripsPrefix() throws IOException {
        pendingPollsBeforeSuccess = 0;
        String baseUrl = startServer();
        SandboxSessionModelOperations ops =
                new SandboxSessionModelOperations(baseUrl, MAPPER, Duration.ofSeconds(30));
        assertThat(ops.pairBrowser().status()).isEqualTo("paired");

        List<String> ids = ops.listModels();
        assertThat(ids).containsExactly("loan"); // the other session's model is filtered out and never mixed in
    }

    @Test
    void modelOperations_failClearlyBeforePairing() throws IOException {
        String baseUrl = startServer();
        SandboxSessionModelOperations ops =
                new SandboxSessionModelOperations(baseUrl, MAPPER, Duration.ofSeconds(30));

        assertThatThrownBy(() -> ops.getInfo("loan"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("pair_browser");
    }
}
