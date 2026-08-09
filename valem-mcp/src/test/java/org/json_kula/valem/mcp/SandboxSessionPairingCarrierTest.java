package org.json_kula.valem.mcp;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import jdk.jfr.Recording;
import jdk.jfr.consumer.RecordedEvent;
import jdk.jfr.consumer.RecordedFrame;
import jdk.jfr.consumer.RecordingFile;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.lang.reflect.Modifier;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pins the carrier-thread contract of {@code pair_browser}: the pairing wait must never block a virtual
 * thread's <b>carrier</b>.
 *
 * <h2>The outage this guards against</h2>
 * A host may run {@link SandboxSessionModelOperations#pairBrowser} on a virtual thread — the sandbox's own
 * hosted {@code /mcp} endpoint does, since Spring Boot with {@code spring.threads.virtual.enabled=true}
 * serves every request on one. The call then blocks for the whole poll budget, on
 * {@link java.net.http.HttpClient#send} and on {@link Thread#sleep}, waiting for the developer to approve.
 *
 * <p>Through Java 21 a virtual thread that blocks while holding a <i>monitor</i> cannot unmount, so a
 * {@code synchronized} pairing method pins its carrier for that entire wait. Where the scheduler's
 * parallelism is 1 — a container limited to a fraction of a CPU reports a single processor — that one
 * pinned carrier stalls every other request in the JVM. The hosted endpoint routes its own calls back
 * through that same server over loopback, so the mint/poll requests pairing is waiting on can never be
 * scheduled: pairing self-deadlocks past its budget, the platform health check starts timing out, and the
 * instance is restarted. Observed in production as "the service goes down whenever an agent pairs", with
 * a {@code reason:MONITOR} pinned-thread trace naming this method.
 *
 * <p>The behavioural check below runs the real pairing wait against a fake sandbox and asserts JFR
 * recorded no {@code jdk.VirtualThreadPinned} event inside it. It is exact on the JDK the deployment
 * targets; the companion structural check keeps a later {@code synchronized} from creeping back on a JDK
 * where monitors no longer pin and the first check would pass vacuously.
 */
class SandboxSessionPairingCarrierTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** Long enough that a pinned park is unambiguous, short enough to keep the test quick. */
    private static final long HANDLER_DELAY_MS = 150;

    private HttpServer server;

    @AfterEach
    void tearDown() {
        if (server != null) server.stop(0);
    }

    @Test
    void pairingWaitDoesNotPinItsCarrierThread() throws Exception {
        String baseUrl = startFakeSandbox();
        // A budget above the handler delays so the wait really blocks, but short enough that even the
        // regressed (deadlocking) shape finishes rather than hanging the suite.
        SandboxSessionModelOperations ops =
                new SandboxSessionModelOperations(baseUrl, MAPPER, Duration.ofSeconds(2));

        Recording recording = new Recording();
        recording.enable("jdk.VirtualThreadPinned").withoutThreshold().withStackTrace();
        recording.start();

        AtomicReference<PairResult> result = new AtomicReference<>();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread pairing = Thread.ofVirtual().name("pairing-under-test").start(() -> {
            try {
                result.set(ops.pairBrowser());
            } catch (Throwable t) {
                failure.set(t);
            }
        });
        pairing.join();
        recording.stop();

        assertThat(failure.get()).isNull();
        // The fake sandbox never approves, so the budget lapses and the facade reports back the link —
        // i.e. the wait really was exercised, not short-circuited.
        assertThat(result.get()).isNotNull();
        assertThat(result.get().status()).isEqualTo("pending");

        assertThat(pinnedInsidePairing(recording))
                .describedAs("pair_browser parked while pinned to its carrier — a blocking wait is "
                        + "holding a monitor, which stalls every other request on a one-carrier host")
                .isEmpty();
    }

    /**
     * The structural half of the contract: the pairing entry point must not be {@code synchronized}.
     * Mutual exclusion belongs on a {@link java.util.concurrent.locks.ReentrantLock}, which a virtual
     * thread can unmount under.
     */
    @Test
    void pairBrowserIsNotSynchronized() throws Exception {
        for (var method : List.of(
                SandboxSessionModelOperations.class.getDeclaredMethod("pairBrowser"),
                SandboxSessionModelOperations.class.getDeclaredMethod("pairBrowser", ProgressHandle.class))) {
            assertThat(Modifier.isSynchronized(method.getModifiers()))
                    .describedAs("%s must not be synchronized — it blocks for the whole poll budget and "
                            + "would pin the carrier of a virtual thread", method)
                    .isFalse();
        }
    }

    /** JFR pinning events whose stack passes through the pairing facade. */
    private static List<String> pinnedInsidePairing(Recording recording) throws IOException {
        Path dump = Files.createTempFile("valem-pairing-pinning", ".jfr");
        try {
            recording.dump(dump);
            try (RecordingFile file = new RecordingFile(dump)) {
                java.util.List<String> hits = new java.util.ArrayList<>();
                while (file.hasMoreEvents()) {
                    RecordedEvent event = file.readEvent();
                    if (!"jdk.VirtualThreadPinned".equals(event.getEventType().getName())) continue;
                    if (event.getStackTrace() == null) continue;
                    for (RecordedFrame frame : event.getStackTrace().getFrames()) {
                        if (SandboxSessionModelOperations.class.getName()
                                .equals(frame.getMethod().getType().getName())) {
                            hits.add(frame.getMethod().getName());
                            break;
                        }
                    }
                }
                return hits;
            }
        } finally {
            recording.close();
            Files.deleteIfExists(dump);
        }
    }

    /**
     * A sandbox that mints a pairing and then never approves it, with every response deliberately slow so
     * the facade spends real time blocked inside the pairing call.
     */
    private String startFakeSandbox() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/sandbox/pair", ex -> {
            delay();
            if (ex.getRequestURI().getPath().equals("/sandbox/pair")) {
                respond(ex, 200, "{\"pairCode\":\"AAAA-BBBB\",\"deviceSecret\":\"secret\","
                        + "\"userCode\":\"CCCC-DDDD\",\"verificationUri\":\"http://example/?pair=AAAA-BBBB\","
                        + "\"expiresInSec\":600,\"intervalSec\":1}");
            } else {
                respond(ex, 409, "{\"error\":\"authorization_pending\"}");   // /sandbox/pair/token
            }
        });
        // Platform threads: the fake sandbox must stay responsive regardless of what the test's virtual
        // thread is doing, so a stall can only come from the code under test.
        server.setExecutor(java.util.concurrent.Executors.newFixedThreadPool(4));
        server.start();
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    private static void delay() {
        try {
            Thread.sleep(HANDLER_DELAY_MS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static void respond(HttpExchange ex, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().add("Content-Type", "application/json");
        ex.sendResponseHeaders(status, bytes.length);
        ex.getResponseBody().write(bytes);
        ex.close();
    }
}
