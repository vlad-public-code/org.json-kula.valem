package org.json_kula.valem.mcp;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.json_kula.valem.core.blob.InMemoryBlobStore;
import org.json_kula.valem.service.ModelRegistry;
import org.json_kula.valem.service.ModelService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class McpServerTest {

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
            .configure(DeserializationFeature.READ_ENUMS_USING_TO_STRING, false)
            .configure(SerializationFeature.FAIL_ON_EMPTY_BEANS, false);

    private static final String SIMPLE_SPEC = """
            {
              "id": "srv-test", "version": "1.0.0", "schema": {},
              "derivations": [ {"path": "$.total", "expr": "price * qty"} ],
              "constraints": [], "metaDerivations": [], "tests": []
            }
            """;

    private McpServer server;

    @BeforeEach
    void setUp() {
        ModelService service = new ModelService(new ModelRegistry(), new InMemoryBlobStore());
        server = new McpServer(service, MAPPER);
    }

    // ── initialize / handshake ────────────────────────────────────────────────────

    @Test
    void initialize_returns_capabilities_and_server_info() {
        JsonNode resp = server.handleMessage(request(1, "initialize",
                obj().put("protocolVersion", "2025-06-18")));
        assertThat(resp.path("jsonrpc").asText()).isEqualTo("2.0");
        assertThat(resp.path("id").asInt()).isEqualTo(1);
        JsonNode result = resp.path("result");
        assertThat(result.path("protocolVersion").asText()).isEqualTo("2025-06-18");
        assertThat(result.path("capabilities").has("tools")).isTrue();
        assertThat(result.path("capabilities").has("resources")).isTrue();
        assertThat(result.path("serverInfo").path("name").asText()).isEqualTo("valem");
        assertThat(result.path("instructions").asText()).isNotBlank();
    }

    @Test
    void initialize_negotiates_down_to_latest_for_unknown_protocol_version() {
        JsonNode result = server.handleMessage(request(1, "initialize",
                obj().put("protocolVersion", "1999-01-01"))).path("result");
        assertThat(result.path("protocolVersion").asText()).isEqualTo("2025-11-25");
    }

    @Test
    void initialize_echoes_a_supported_older_protocol_version() {
        JsonNode result = server.handleMessage(request(1, "initialize",
                obj().put("protocolVersion", "2024-11-05"))).path("result");
        assertThat(result.path("protocolVersion").asText()).isEqualTo("2024-11-05");
    }

    @Test
    void notification_produces_no_response() {
        ObjectNode note = obj();
        note.put("jsonrpc", "2.0");
        note.put("method", "notifications/initialized");
        assertThat(server.handleMessage(note)).isNull();
    }

    @Test
    void ping_returns_empty_result() {
        JsonNode resp = server.handleMessage(request(7, "ping", null));
        assertThat(resp.path("result").isObject()).isTrue();
        assertThat(resp.path("result").isEmpty()).isTrue();
    }

    // ── tools/list ─────────────────────────────────────────────────────────────

    @Test
    void tools_list_returns_the_tool_surface() {
        JsonNode resp = server.handleMessage(request(2, "tools/list", null));
        JsonNode tools = resp.path("result").path("tools");
        assertThat(tools.isArray()).isTrue();
        List<String> names = new ArrayList<>();
        tools.forEach(t -> names.add(t.path("name").asText()));
        assertThat(names).contains("create_model", "mutate", "get_state", "explain", "evolve_spec");
    }

    // ── tools/call ─────────────────────────────────────────────────────────────

    @Test
    void tools_call_create_then_mutate_flows_through_the_service() {
        server.handleMessage(request(3, "tools/call",
                obj().put("name", "create_model").set("arguments", createArgs())));

        ObjectNode mutateArgs = obj();
        mutateArgs.put("id", "srv-test");
        mutateArgs.putObject("mutations").put("$.price", 6.0).put("$.qty", 7);
        JsonNode resp = server.handleMessage(request(4, "tools/call",
                obj().put("name", "mutate").set("arguments", mutateArgs)));

        JsonNode result = resp.path("result");
        assertThat(result.path("isError").asBoolean()).isFalse();
        JsonNode payload = payload(result);
        assertThat(payload.path("success").asBoolean()).isTrue();
        assertThat(payload.path("derivedUpdated").toString()).contains("$.total");
    }

    @Test
    void tools_call_missing_name_is_a_jsonrpc_error() {
        JsonNode resp = server.handleMessage(request(5, "tools/call", obj()));
        assertThat(resp.path("error").path("code").asInt()).isEqualTo(-32603);
    }

    @Test
    void unknown_method_returns_method_not_found() {
        JsonNode resp = server.handleMessage(request(6, "no/such/method", null));
        assertThat(resp.path("error").path("code").asInt()).isEqualTo(-32601);
        assertThat(resp.path("error").path("message").asText()).contains("no/such/method");
    }

    // ── resources ─────────────────────────────────────────────────────────────

    @Test
    void resources_list_returns_guide_and_examples() {
        JsonNode resp = server.handleMessage(request(20, "resources/list", null));
        JsonNode resources = resp.path("result").path("resources");
        assertThat(resources.isArray()).isTrue();
        List<String> uris = new ArrayList<>();
        resources.forEach(r -> uris.add(r.path("uri").asText()));
        assertThat(uris).contains("valem://guide/model-spec-format", "valem://schema/model-spec");
    }

    @Test
    void resources_read_returns_contents() {
        JsonNode resp = server.handleMessage(request(21, "resources/read",
                obj().put("uri", "valem://guide/model-spec-format")));
        JsonNode item = resp.path("result").path("contents").get(0);
        assertThat(item.path("uri").asText()).isEqualTo("valem://guide/model-spec-format");
        assertThat(item.path("text").asText()).isNotBlank();
    }

    @Test
    void resources_read_unknown_uri_is_resource_not_found() {
        JsonNode resp = server.handleMessage(request(22, "resources/read",
                obj().put("uri", "valem://nope")));
        assertThat(resp.path("error").path("code").asInt()).isEqualTo(-32002);
    }

    @Test
    void resources_read_without_uri_is_invalid_request() {
        JsonNode resp = server.handleMessage(request(23, "resources/read", obj()));
        assertThat(resp.path("error").path("code").asInt()).isEqualTo(-32600);
    }

    @Test
    void resources_templates_list_exposes_the_examples_uri_template() {
        JsonNode resp = server.handleMessage(request(24, "resources/templates/list", null));
        JsonNode templates = resp.path("result").path("resourceTemplates");
        assertThat(templates.isArray()).isTrue();
        List<String> uris = new ArrayList<>();
        templates.forEach(t -> uris.add(t.path("uriTemplate").asText()));
        assertThat(uris).contains("valem://examples/{name}");
    }

    @Test
    void resources_list_includes_the_new_guides() {
        JsonNode resp = server.handleMessage(request(25, "resources/list", null));
        List<String> uris = new ArrayList<>();
        resp.path("result").path("resources").forEach(r -> uris.add(r.path("uri").asText()));
        assertThat(uris).contains("valem://guide/jsonata-gotchas",
                "valem://guide/spec-evolution", "valem://guide/view-system");
    }

    // ── subscribable model-state resource (§4.2) ─────────────────────────────────

    @Test
    void initialize_advertises_resources_subscribe() {
        JsonNode result = server.handleMessage(request(1, "initialize",
                obj().put("protocolVersion", "2025-11-25"))).path("result");
        assertThat(result.path("capabilities").path("resources").path("subscribe").asBoolean()).isTrue();
    }

    @Test
    void resources_templates_list_includes_the_state_template() {
        JsonNode resp = server.handleMessage(request(26, "resources/templates/list", null));
        List<String> templates = new ArrayList<>();
        resp.path("result").path("resourceTemplates").forEach(t -> templates.add(t.path("uriTemplate").asText()));
        assertThat(templates).contains("valem://state/{modelId}");
    }

    @Test
    void resources_read_of_state_uri_returns_merged_state() throws Exception {
        server.handleMessage(request(3, "tools/call",
                obj().put("name", "create_model").set("arguments", createArgs())));
        ObjectNode mutateArgs = obj();
        mutateArgs.put("id", "srv-test");
        mutateArgs.putObject("mutations").put("$.price", 5).put("$.qty", 4);
        server.handleMessage(request(4, "tools/call", obj().put("name", "mutate").set("arguments", mutateArgs)));

        JsonNode resp = server.handleMessage(request(5, "resources/read",
                obj().put("uri", "valem://state/srv-test")));
        JsonNode item = resp.path("result").path("contents").get(0);
        assertThat(item.path("uri").asText()).isEqualTo("valem://state/srv-test");
        JsonNode state = MAPPER.readTree(item.path("text").asText());
        assertThat(state.path("total").asInt()).isEqualTo(20);
    }

    @Test
    void subscribing_to_state_emits_resources_updated_on_mutation() throws Exception {
        ObjectNode mutateArgs = obj();
        mutateArgs.put("id", "srv-test");
        mutateArgs.putObject("mutations").put("$.price", 5).put("$.qty", 4);

        List<JsonNode> lines = runLines(server,
                writeMsg(request(1, "initialize", obj().put("protocolVersion", "2025-11-25"))),
                writeMsg(notification()),
                writeMsg(request(2, "tools/call", obj().put("name", "create_model").set("arguments", createArgs()))),
                writeMsg(request(3, "resources/subscribe", obj().put("uri", "valem://state/srv-test"))),
                writeMsg(request(4, "tools/call", obj().put("name", "mutate").set("arguments", mutateArgs))));

        // subscribe was accepted
        assertThat(lines.stream().anyMatch(n -> n.path("id").asInt(-1) == 3 && n.has("result"))).isTrue();
        // and the mutation produced a resources/updated notification for the state uri
        JsonNode updated = lines.stream()
                .filter(n -> "notifications/resources/updated".equals(n.path("method").asText()))
                .findFirst().orElse(null);
        assertThat(updated).as("resources/updated emitted on mutation").isNotNull();
        assertThat(updated.path("params").path("uri").asText()).isEqualTo("valem://state/srv-test");
    }

    @Test
    void unsubscribe_stops_further_updates() throws Exception {
        ObjectNode m1 = obj();
        m1.put("id", "srv-test");
        m1.putObject("mutations").put("$.price", 5);
        ObjectNode m2 = obj();
        m2.put("id", "srv-test");
        m2.putObject("mutations").put("$.qty", 9);

        List<JsonNode> lines = runLines(server,
                writeMsg(request(1, "initialize", obj().put("protocolVersion", "2025-11-25"))),
                writeMsg(request(2, "tools/call", obj().put("name", "create_model").set("arguments", createArgs()))),
                writeMsg(request(3, "resources/subscribe", obj().put("uri", "valem://state/srv-test"))),
                writeMsg(request(4, "resources/unsubscribe", obj().put("uri", "valem://state/srv-test"))),
                writeMsg(request(5, "tools/call", obj().put("name", "mutate").set("arguments", m2))));

        long updates = lines.stream()
                .filter(n -> "notifications/resources/updated".equals(n.path("method").asText()))
                .count();
        assertThat(updates).as("no updates after unsubscribe").isZero();
    }

    // ── logging capability (§3.5) ─────────────────────────────────────────────────

    @Test
    void initialize_advertises_the_logging_capability() {
        JsonNode result = server.handleMessage(request(1, "initialize",
                obj().put("protocolVersion", "2025-06-18"))).path("result");
        assertThat(result.path("capabilities").has("logging")).isTrue();
    }

    @Test
    void logging_set_level_returns_an_empty_result() {
        JsonNode resp = server.handleMessage(request(30, "logging/setLevel",
                obj().put("level", "warning")));
        assertThat(resp.path("result").isObject()).isTrue();
        assertThat(resp.path("result").isEmpty()).isTrue();
    }

    @Test
    void initialized_notification_emits_a_readiness_log_message() throws Exception {
        String input = String.join("\n",
                writeMsg(request(1, "initialize", obj().put("protocolVersion", "2025-06-18"))),
                writeMsg(notification())
        ) + "\n";
        ByteArrayInputStream in = new ByteArrayInputStream(input.getBytes(StandardCharsets.UTF_8));
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        server.run(in, out);

        List<JsonNode> lines = new ArrayList<>();
        for (String line : out.toString(StandardCharsets.UTF_8).split("\n")) {
            if (!line.isBlank()) lines.add(MAPPER.readTree(line));
        }
        // the initialize response, then a server-initiated notifications/message
        JsonNode logNote = lines.stream()
                .filter(n -> "notifications/message".equals(n.path("method").asText()))
                .findFirst().orElse(null);
        assertThat(logNote).as("a notifications/message log was emitted").isNotNull();
        assertThat(logNote.path("params").path("level").asText()).isEqualTo("info");
        assertThat(logNote.path("params").path("data").asText()).contains("ready");
    }

    // ── pair_browser progress + cancellation (§3.2) ──────────────────────────────

    /** A pairable facade that loops in pairBrowser, reporting progress, eliciting, honouring cancellation. */
    private static final class FakeLongPairable extends ModelService implements BrowserPairable {
        private final boolean waitForCancel;
        private final boolean elicit;
        private final boolean returnPaired;
        FakeLongPairable(boolean waitForCancel) { this(waitForCancel, false, false); }
        FakeLongPairable(boolean waitForCancel, boolean elicit, boolean returnPaired) {
            super(new ModelRegistry(), new InMemoryBlobStore());
            this.waitForCancel = waitForCancel;
            this.elicit = elicit;
            this.returnPaired = returnPaired;
        }
        @Override public PairResult pairBrowser() { return pairBrowser(ProgressHandle.NONE); }
        @Override public PairResult pairBrowser(ProgressHandle progress) {
            int max = waitForCancel ? 200 : 2;
            for (int i = 0; i < max; i++) {
                if (progress.cancelled()) break;
                progress.report(i + 1, (double) max, "waiting " + (i + 1));
                if (elicit) progress.elicitUrl("https://verify.example/pair",
                        "Open this link and enter code WXYZ-1234 to approve pairing.");
                if (waitForCancel) { try { Thread.sleep(20); } catch (InterruptedException e) { break; } }
            }
            return returnPaired ? PairResult.paired("ns-1")
                    : PairResult.pending("http://x/?pair=CODE", "WXYZ-1234", 600);
        }
    }

    private static List<JsonNode> runLines(McpServer srv, String... messages) throws Exception {
        String input = String.join("\n", messages) + "\n";
        ByteArrayInputStream in = new ByteArrayInputStream(input.getBytes(StandardCharsets.UTF_8));
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        srv.run(in, out);
        List<JsonNode> lines = new ArrayList<>();
        for (String line : out.toString(StandardCharsets.UTF_8).split("\n")) {
            if (!line.isBlank()) lines.add(MAPPER.readTree(line));
        }
        return lines;
    }

    @Test
    void pair_browser_emits_progress_notifications_then_the_result() throws Exception {
        McpServer srv = new McpServer(new FakeLongPairable(false), MAPPER);
        ObjectNode call = obj();
        call.put("name", "pair_browser");
        call.putObject("arguments");
        call.putObject("_meta").put("progressToken", "p1");

        List<JsonNode> lines = runLines(srv,
                writeMsg(request(1, "initialize", obj().put("protocolVersion", "2025-06-18"))),
                writeMsg(notification()),
                writeMsg(request(5, "tools/call", call)));

        // progress notifications carrying our token
        long progressCount = lines.stream()
                .filter(n -> "notifications/progress".equals(n.path("method").asText()))
                .peek(n -> assertThat(n.path("params").path("progressToken").asText()).isEqualTo("p1"))
                .count();
        assertThat(progressCount).isGreaterThanOrEqualTo(1);

        // and the final tool result (pending) for id 5
        JsonNode resp = lines.stream().filter(n -> n.path("id").asInt(-1) == 5).findFirst().orElse(null);
        assertThat(resp).isNotNull();
        assertThat(resp.path("result").path("structuredContent").path("status").asText()).isEqualTo("pending");
    }

    @Test
    void pair_browser_cancellation_stops_the_wait_and_sends_no_response() throws Exception {
        McpServer srv = new McpServer(new FakeLongPairable(true), MAPPER);
        ObjectNode call = obj();
        call.put("name", "pair_browser");
        call.putObject("arguments");

        List<JsonNode> lines = runLines(srv,
                writeMsg(request(1, "initialize", obj().put("protocolVersion", "2025-06-18"))),
                writeMsg(notification()),
                writeMsg(request(5, "tools/call", call)),
                writeMsg(cancelled(5)));

        // the cancelled request produces no response envelope for id 5
        boolean anyResponseFor5 = lines.stream().anyMatch(n -> n.path("id").asInt(-1) == 5 && n.has("result"));
        assertThat(anyResponseFor5).as("cancelled request sends no response").isFalse();
    }

    // ── URL-mode elicitation + newest revision (§4.3) ────────────────────────────

    @Test
    void initialize_echoes_the_2025_11_25_revision_and_it_is_the_default() {
        JsonNode echoed = server.handleMessage(request(1, "initialize",
                obj().put("protocolVersion", "2025-11-25"))).path("result");
        assertThat(echoed.path("protocolVersion").asText()).isEqualTo("2025-11-25");
    }

    @Test
    void pair_browser_uses_url_elicitation_when_the_client_supports_it() throws Exception {
        McpServer srv = new McpServer(new FakeLongPairable(false, true, true), MAPPER);
        ObjectNode initParams = obj().put("protocolVersion", "2025-11-25");
        initParams.putObject("capabilities").putObject("elicitation").putObject("url");
        ObjectNode call = obj();
        call.put("name", "pair_browser");
        call.putObject("arguments");

        List<JsonNode> lines = runLines(srv,
                writeMsg(request(1, "initialize", initParams)),
                writeMsg(notification()),
                writeMsg(request(5, "tools/call", call)));

        JsonNode elicit = lines.stream()
                .filter(n -> "elicitation/create".equals(n.path("method").asText()))
                .findFirst().orElse(null);
        assertThat(elicit).as("a URL-mode elicitation/create request was sent").isNotNull();
        assertThat(elicit.path("params").path("mode").asText()).isEqualTo("url");
        assertThat(elicit.path("params").path("url").asText()).isEqualTo("https://verify.example/pair");
        assertThat(elicit.path("params").path("message").asText()).contains("WXYZ-1234");
        assertThat(elicit.path("params").path("elicitationId").asText()).isNotBlank();

        // paired result → the out-of-band interaction is reported complete
        JsonNode complete = lines.stream()
                .filter(n -> "notifications/elicitation/complete".equals(n.path("method").asText()))
                .findFirst().orElse(null);
        assertThat(complete).isNotNull();
        assertThat(complete.path("params").path("elicitationId").asText())
                .isEqualTo(elicit.path("params").path("elicitationId").asText());
    }

    @Test
    void pair_browser_skips_url_elicitation_when_client_did_not_advertise_it() throws Exception {
        McpServer srv = new McpServer(new FakeLongPairable(false, true, true), MAPPER);
        ObjectNode initParams = obj().put("protocolVersion", "2025-11-25");   // no elicitation capability
        ObjectNode call = obj();
        call.put("name", "pair_browser");
        call.putObject("arguments");

        List<JsonNode> lines = runLines(srv,
                writeMsg(request(1, "initialize", initParams)),
                writeMsg(notification()),
                writeMsg(request(5, "tools/call", call)));

        boolean anyElicit = lines.stream()
                .anyMatch(n -> "elicitation/create".equals(n.path("method").asText()));
        assertThat(anyElicit).as("no elicitation without the client capability").isFalse();
    }

    @Test
    void client_response_to_a_server_request_is_swallowed_not_answered() throws Exception {
        // A message shaped like the client's response to our elicitation/create (id + result, no method)
        // must draw no reply — otherwise the server would answer it as a bogus request.
        ObjectNode clientResponse = obj();
        clientResponse.put("jsonrpc", "2.0");
        clientResponse.put("id", "srv-1");
        clientResponse.putObject("result").put("action", "accept");

        List<JsonNode> lines = runLines(server,
                writeMsg(request(1, "initialize", obj().put("protocolVersion", "2025-11-25"))),
                writeMsg(notification()),
                writeMsg(clientResponse));

        boolean anyForSrv1 = lines.stream().anyMatch(n -> "srv-1".equals(n.path("id").asText()));
        assertThat(anyForSrv1).as("no reply to a server-request response").isFalse();
    }

    private static ObjectNode cancelled(int requestId) {
        ObjectNode note = MAPPER.createObjectNode();
        note.put("jsonrpc", "2.0");
        note.put("method", "notifications/cancelled");
        note.putObject("params").put("requestId", requestId).put("reason", "user aborted");
        return note;
    }

    @Test
    void set_level_above_info_suppresses_the_readiness_log() throws Exception {
        String input = String.join("\n",
                writeMsg(request(1, "initialize", obj().put("protocolVersion", "2025-06-18"))),
                writeMsg(request(2, "logging/setLevel", obj().put("level", "error"))),
                writeMsg(notification())
        ) + "\n";
        ByteArrayInputStream in = new ByteArrayInputStream(input.getBytes(StandardCharsets.UTF_8));
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        server.run(in, out);

        boolean anyLog = false;
        for (String line : out.toString(StandardCharsets.UTF_8).split("\n")) {
            if (line.isBlank()) continue;
            if ("notifications/message".equals(MAPPER.readTree(line).path("method").asText())) anyLog = true;
        }
        assertThat(anyLog).as("info readiness log is suppressed when floor is 'error'").isFalse();
    }

    // ── end-to-end stdio loop ─────────────────────────────────────────────────────

    @Test
    void run_processes_newline_delimited_stream_and_skips_notifications() throws Exception {
        String input = String.join("\n",
                writeMsg(request(1, "initialize", obj().put("protocolVersion", "2025-06-18"))),
                writeMsg(notification()),
                writeMsg(request(2, "tools/call", obj().put("name", "create_model").set("arguments", createArgs()))),
                writeMsg(request(3, "tools/list", null))
        ) + "\n";

        ByteArrayInputStream in = new ByteArrayInputStream(input.getBytes(StandardCharsets.UTF_8));
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        server.run(in, out);

        List<JsonNode> responses = new ArrayList<>();
        for (String line : out.toString(StandardCharsets.UTF_8).split("\n")) {
            if (line.isBlank()) continue;
            JsonNode node = MAPPER.readTree(line);
            // Skip server-initiated notifications (readiness log) — count only request responses.
            if (node.has("method") || !node.has("id")) continue;
            responses.add(node);
        }
        // 3 requests → 3 responses; the client notification and the server log produced no responses.
        assertThat(responses).hasSize(3);
        assertThat(responses.get(0).path("id").asInt()).isEqualTo(1);
        assertThat(responses.get(1).path("id").asInt()).isEqualTo(2);
        assertThat(responses.get(1).path("result").path("isError").asBoolean()).isFalse();
        assertThat(responses.get(2).path("result").path("tools").isArray()).isTrue();
    }

    // ── helpers ────────────────────────────────────────────────────────────────

    private static ObjectNode obj() {
        return MAPPER.createObjectNode();
    }

    private static ObjectNode request(int id, String method, JsonNode params) {
        ObjectNode req = MAPPER.createObjectNode();
        req.put("jsonrpc", "2.0");
        req.put("id", id);
        req.put("method", method);
        if (params != null) req.set("params", params);
        return req;
    }

    private static ObjectNode notification() {
        ObjectNode note = MAPPER.createObjectNode();
        note.put("jsonrpc", "2.0");
        note.put("method", "notifications/initialized");
        return note;
    }

    private static ObjectNode createArgs() {
        try {
            ObjectNode args = MAPPER.createObjectNode();
            args.set("spec", MAPPER.readTree(SIMPLE_SPEC));
            return args;
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private static String writeMsg(JsonNode msg) throws Exception {
        return MAPPER.writeValueAsString(msg);
    }

    private static JsonNode payload(JsonNode result) {
        try {
            return MAPPER.readTree(result.path("content").get(0).path("text").asText());
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
