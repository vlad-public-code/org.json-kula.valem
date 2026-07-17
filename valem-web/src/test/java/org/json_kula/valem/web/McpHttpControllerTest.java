package org.json_kula.valem.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.annotation.DirtiesContext;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * End-to-end test of the in-server Streamable-HTTP MCP endpoint (§4.1): drives {@code /mcp} over real
 * HTTP through the full Spring stack, proving the transport reuses the MCP protocol core and shares the
 * server's {@link org.json_kula.valem.service.ModelService} with the REST API.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)   // isolate created models from other tests
class McpHttpControllerTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final String SPEC = """
            { "id": "http-order", "version": "1.0.0", "schema": {},
              "derivations": [ {"path": "$.total", "expr": "price * qty"} ],
              "constraints": [], "metaDerivations": [], "tests": [] }
            """;

    @Autowired
    TestRestTemplate rest;

    @Test
    void initialize_then_create_and_read_over_http_sharing_state_with_rest() throws Exception {
        // initialize → 200 with a session id header and the negotiated version
        ResponseEntity<String> init = post(rpc(1, "initialize",
                MAPPER.createObjectNode().put("protocolVersion", "2025-11-25")), null);
        assertThat(init.getStatusCode().value()).isEqualTo(200);
        String sessionId = init.getHeaders().getFirst("Mcp-Session-Id");
        assertThat(sessionId).as("initialize mints a session id").isNotBlank();
        assertThat(MAPPER.readTree(init.getBody()).path("result").path("protocolVersion").asText())
                .isEqualTo("2025-11-25");

        // notifications/initialized → 202 Accepted, no body
        ResponseEntity<String> initialized = post(notification("notifications/initialized"), sessionId);
        assertThat(initialized.getStatusCode().value()).isEqualTo(202);

        // create_model via MCP
        ObjectNode createArgs = MAPPER.createObjectNode();
        createArgs.set("spec", MAPPER.readTree(SPEC));
        ResponseEntity<String> created = post(toolCall(2, "create_model", createArgs), sessionId);
        assertThat(created.getStatusCode().value()).isEqualTo(200);
        assertThat(MAPPER.readTree(created.getBody()).path("result").path("isError").asBoolean()).isFalse();

        // mutate via MCP
        ObjectNode mutateArgs = MAPPER.createObjectNode();
        mutateArgs.put("id", "http-order");
        mutateArgs.putObject("mutations").put("$.price", 10).put("$.qty", 3);
        post(toolCall(3, "mutate", mutateArgs), sessionId);

        // get_state via MCP reflects the derived value
        ObjectNode stateArgs = MAPPER.createObjectNode();
        stateArgs.put("id", "http-order");
        ResponseEntity<String> state = post(toolCall(4, "get_state", stateArgs), sessionId);
        JsonNode structured = MAPPER.readTree(state.getBody()).path("result").path("structuredContent");
        assertThat(structured.path("total").asInt()).isEqualTo(30);

        // the model created over MCP is visible on the shared REST API
        ResponseEntity<String> models = rest.getForEntity("/models", String.class);
        assertThat(models.getBody()).contains("http-order");

        // DELETE terminates the session
        HttpHeaders h = new HttpHeaders();
        h.set("Mcp-Session-Id", sessionId);
        ResponseEntity<Void> deleted = rest.exchange("/mcp", HttpMethod.DELETE, new HttpEntity<>(h), Void.class);
        assertThat(deleted.getStatusCode().value()).isEqualTo(204);
    }

    @Test
    void tools_list_over_http_exposes_the_surface() throws Exception {
        ResponseEntity<String> init = post(rpc(1, "initialize",
                MAPPER.createObjectNode().put("protocolVersion", "2025-11-25")), null);
        String sessionId = init.getHeaders().getFirst("Mcp-Session-Id");

        ResponseEntity<String> list = post(rpc(2, "tools/list", null), sessionId);
        JsonNode tools = MAPPER.readTree(list.getBody()).path("result").path("tools");
        assertThat(tools.isArray()).isTrue();
        java.util.List<String> names = new java.util.ArrayList<>();
        tools.forEach(t -> names.add(t.path("name").asText()));
        assertThat(names).contains("create_model", "mutate", "get_state", "patch_model", "get_audit");
    }

    @Test
    void unknown_session_id_is_not_found() throws Exception {
        ResponseEntity<String> resp = post(rpc(9, "tools/list", null), "no-such-session");
        assertThat(resp.getStatusCode().value()).isEqualTo(404);
    }

    // ── helpers ────────────────────────────────────────────────────────────────

    private ResponseEntity<String> post(JsonNode message, String sessionId) throws Exception {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        if (sessionId != null) headers.set("Mcp-Session-Id", sessionId);
        HttpEntity<String> entity = new HttpEntity<>(MAPPER.writeValueAsString(message), headers);
        return rest.postForEntity("/mcp", entity, String.class);
    }

    private static ObjectNode rpc(int id, String method, JsonNode params) {
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

    private static ObjectNode toolCall(int id, String name, JsonNode arguments) {
        ObjectNode params = MAPPER.createObjectNode();
        params.put("name", name);
        params.set("arguments", arguments);
        return rpc(id, "tools/call", params);
    }
}
