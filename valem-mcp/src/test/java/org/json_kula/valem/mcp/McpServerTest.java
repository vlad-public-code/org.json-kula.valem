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
        assertThat(result.path("protocolVersion").asText()).isEqualTo("2025-06-18");
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
            if (!line.isBlank()) responses.add(MAPPER.readTree(line));
        }
        // 3 requests → 3 responses; the notification produced none.
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
