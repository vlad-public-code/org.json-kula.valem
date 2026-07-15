package org.json_kula.valem.api.reference.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * Thin client over an {@link McpTransport}: performs the MCP handshake lazily (once) and calls
 * {@code tools/call}, unwrapping the MCP result envelope
 * ({@code {content:[{type:text,text}], structuredContent?, isError}}). A tool result with
 * {@code isError:true} is raised as an {@link McpTransport.McpException} so callers treat a not-found /
 * rejected tool like any other failure.
 */
final class McpTools {

    private static final String PROTOCOL_VERSION = "2025-06-18";

    private final McpTransport transport;
    private final ObjectMapper mapper;
    private volatile boolean initialized = false;

    McpTools(McpTransport transport, ObjectMapper mapper) {
        this.transport = transport;
        this.mapper = mapper;
    }

    ObjectMapper mapper() {
        return mapper;
    }

    private void ensureInit() {
        if (initialized) return;
        synchronized (this) {
            if (initialized) return;
            ObjectNode params = mapper.createObjectNode();
            params.put("protocolVersion", PROTOCOL_VERSION);
            params.putObject("capabilities");
            ObjectNode clientInfo = params.putObject("clientInfo");
            clientInfo.put("name", "valem-composition");
            clientInfo.put("version", "1.0.0");
            transport.rpc("initialize", params);
            transport.notification("notifications/initialized", null);
            initialized = true;
        }
    }

    /** Calls a tool and returns the raw MCP result object; throws on {@code isError}. */
    JsonNode call(String name, ObjectNode arguments) {
        ensureInit();
        ObjectNode params = mapper.createObjectNode();
        params.put("name", name);
        params.set("arguments", arguments);
        JsonNode result = transport.rpc("tools/call", params);
        if (result == null) throw new McpTransport.McpException("tool '" + name + "' returned no result");
        if (result.path("isError").asBoolean(false)) {
            throw new McpTransport.McpException(
                    name + ": " + result.path("content").path(0).path("text").asText("tool error"));
        }
        return result;
    }

    /** Calls a tool that returns a JSON object payload, returning its {@code structuredContent}. */
    JsonNode callStructured(String name, ObjectNode arguments) {
        return call(name, arguments).get("structuredContent");
    }

    /**
     * Calls a tool whose payload may be any JSON value (object/array/scalar), parsing it from the text
     * content block — structured content is only present for objects, so the text block is authoritative.
     */
    JsonNode callValue(String name, ObjectNode arguments) {
        JsonNode result = call(name, arguments);
        String text = result.path("content").path(0).path("text").asText(null);
        if (text == null) return null;
        try {
            return mapper.readTree(text);
        } catch (Exception e) {
            throw new McpTransport.McpException("could not parse tool '" + name + "' result: " + e);
        }
    }

    void close() {
        transport.close();
    }
}
