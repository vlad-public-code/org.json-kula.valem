package org.json_kula.valem.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * End-to-end test: launches {@link McpServer#main} as a <b>separate JVM process</b> and drives it over
 * its real stdin/stdout pipes, exactly as an MCP client (Claude Code / Desktop) does. Unlike
 * {@code McpServerTest} — which calls {@code handleMessage}/{@code run} in-process — this exercises the
 * whole path: process bootstrap, {@code main()} wiring of a live {@code ModelService}, OS-level pipe
 * framing, and the newline-delimited JSON-RPC protocol across the process boundary.
 *
 * <p>The child JVM is started from the current test classpath (so the test runs during {@code mvn test},
 * before the shaded jar is packaged); it runs the same {@code main} entry point the jar's manifest names.
 */
class McpServerE2ETest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final String SPEC = """
            {
              "id": "e2e-order", "version": "1.0.0", "schema": {},
              "derivations": [ {"path": "$.total", "expr": "price * qty"} ],
              "constraints": [ {"id": "cap", "expr": "total <= 100", "policy": "rollback"} ],
              "metaDerivations": [], "tests": []
            }
            """;

    @Test
    @Timeout(60)
    void full_lifecycle_over_a_real_process() throws Exception {
        ObjectNode spec = (ObjectNode) MAPPER.readTree(SPEC);

        // A full session: handshake, list, create, mutate, read, explain, a ROLLBACK-violating mutate,
        // an evolution, and a read of the newly-derived field — all through the process boundary.
        List<ObjectNode> requests = List.of(
                request(1, "initialize", params(p -> p.put("protocolVersion", "2025-06-18"))),
                notification("notifications/initialized"),
                request(2, "tools/list", null),
                request(3, "tools/call", toolCall("create_model", args -> args.set("spec", spec))),
                request(4, "tools/call", toolCall("mutate", args -> {
                    args.put("id", "e2e-order");
                    args.putObject("mutations").put("$.price", 10).put("$.qty", 3);
                })),
                request(5, "tools/call", toolCall("get_state", args -> args.put("id", "e2e-order"))),
                request(6, "tools/call", toolCall("explain", args -> {
                    args.put("id", "e2e-order");
                    args.put("path", "$.total");
                })),
                request(7, "tools/call", toolCall("mutate", args -> {
                    args.put("id", "e2e-order");
                    args.putObject("mutations").put("$.qty", 50);   // total → 500, violates cap <= 100
                })),
                request(8, "tools/call", toolCall("evolve_spec", args -> {
                    args.put("id", "e2e-order");
                    args.putObject("evolution").putArray("upsertDerivations").addObject()
                            .put("path", "$.doubleTotal").put("expr", "total * 2");
                })),
                request(9, "tools/call", toolCall("get_field", args -> {
                    args.put("id", "e2e-order");
                    args.put("path", "$.doubleTotal");
                }))
        );

        Map<Integer, JsonNode> byId = exchange(requests);

        // 9 requests carry an id → 9 responses; the notification produced none.
        assertThat(byId).hasSize(9);

        // initialize: negotiated version + server info.
        assertThat(byId.get(1).path("result").path("protocolVersion").asText()).isEqualTo("2025-06-18");
        assertThat(byId.get(1).path("result").path("serverInfo").path("name").asText()).isEqualTo("valem");

        // tools/list surfaces the tool catalogue.
        List<String> toolNames = new ArrayList<>();
        byId.get(2).path("result").path("tools").forEach(t -> toolNames.add(t.path("name").asText()));
        assertThat(toolNames).contains("create_model", "mutate", "get_state", "explain", "evolve_spec");

        // create_model returns the model info (id + counts).
        assertThat(byId.get(3).path("result").path("isError").asBoolean()).isFalse();
        assertThat(payload(byId.get(3)).path("id").asText()).isEqualTo("e2e-order");
        assertThat(payload(byId.get(3)).path("derivationCount").asInt()).isEqualTo(1);
        // object result also carries structuredContent
        assertThat(byId.get(3).path("result").has("structuredContent")).isTrue();

        // mutate runs the reactive pipeline: $.total recomputes.
        assertThat(byId.get(4).path("result").path("isError").asBoolean()).isFalse();
        assertThat(payload(byId.get(4)).path("derivedUpdated").toString()).contains("$.total");

        // get_state reflects base + derived (10 * 3 = 30).
        JsonNode state = payload(byId.get(5));
        assertThat(state.path("price").asInt()).isEqualTo(10);
        assertThat(state.path("total").asInt()).isEqualTo(30);

        // explain returns a trace for the derived field.
        JsonNode traces = payload(byId.get(6));
        assertThat(traces.isArray()).isTrue();
        assertThat(traces.toString()).contains("$.total");

        // ROLLBACK constraint violation surfaces as an isError tool result (not a protocol error),
        // structurally — carrying the violated constraint id.
        JsonNode violation = byId.get(7).path("result");
        assertThat(violation.path("isError").asBoolean()).isTrue();
        assertThat(violation.path("structuredContent").path("violations").get(0).path("constraintId").asText())
                .isEqualTo("cap");

        // evolve_spec succeeds and the new derivation computes against the (rolled-back) state: 30 * 2 = 60.
        assertThat(byId.get(8).path("result").path("isError").asBoolean()).isFalse();
        assertThat(payload(byId.get(9)).asInt()).isEqualTo(60);
    }

    // ── process driver ────────────────────────────────────────────────────────────

    /**
     * Launches the server in a child JVM, writes every request as one line to its stdin, closes stdin
     * (EOF ends the server's read loop), then reads all stdout response lines. Batch write-then-read is
     * deadlock-free here because the exchange is small (well under the OS pipe buffer).
     */
    private Map<Integer, JsonNode> exchange(List<ObjectNode> requests) throws Exception {
        String javaBin = System.getProperty("java.home") + File.separator + "bin" + File.separator + "java";
        String classpath = System.getProperty("java.class.path");

        ProcessBuilder pb = new ProcessBuilder(
                javaBin, "-cp", classpath, "org.json_kula.valem.mcp.McpServer");
        pb.redirectError(ProcessBuilder.Redirect.DISCARD);   // drop the server's stderr diagnostics
        Process process = pb.start();

        try (OutputStream stdin = process.getOutputStream()) {
            for (ObjectNode req : requests) {
                stdin.write(MAPPER.writeValueAsString(req).getBytes(StandardCharsets.UTF_8));
                stdin.write('\n');
            }
            stdin.flush();
        }   // close → EOF → the server finishes and exits

        Map<Integer, JsonNode> byId = new HashMap<>();
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isBlank()) continue;
                JsonNode resp = MAPPER.readTree(line);
                assertThat(resp.path("jsonrpc").asText()).isEqualTo("2.0");
                byId.put(resp.path("id").asInt(), resp);
            }
        }

        assertThat(process.waitFor(30, TimeUnit.SECONDS)).as("server process exits on stdin EOF").isTrue();
        assertThat(process.exitValue()).isZero();
        return byId;
    }

    // ── message builders ────────────────────────────────────────────────────────

    private static ObjectNode request(int id, String method, JsonNode params) {
        ObjectNode req = MAPPER.createObjectNode();
        req.put("jsonrpc", "2.0");
        req.put("id", id);
        req.put("method", method);
        if (params != null) req.set("params", params);
        return req;
    }

    private static ObjectNode notification(String method) {
        ObjectNode note = MAPPER.createObjectNode();
        note.put("jsonrpc", "2.0");
        note.put("method", method);
        return note;
    }

    private static ObjectNode params(java.util.function.Consumer<ObjectNode> fill) {
        ObjectNode p = MAPPER.createObjectNode();
        fill.accept(p);
        return p;
    }

    /** Builds {@code {"name": <tool>, "arguments": {...}}} for a tools/call params object. */
    private static ObjectNode toolCall(String name, java.util.function.Consumer<ObjectNode> fillArgs) {
        ObjectNode p = MAPPER.createObjectNode();
        p.put("name", name);
        fillArgs.accept(p.putObject("arguments"));
        return p;
    }

    /** Parses the tools/call result's single text content block back into JSON. */
    private static JsonNode payload(JsonNode response) throws Exception {
        return MAPPER.readTree(response.path("result").path("content").get(0).path("text").asText());
    }
}
