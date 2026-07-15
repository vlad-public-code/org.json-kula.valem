package org.json_kula.valem.api.reference.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.json_kula.valem.api.reference.ModelLink;
import org.json_kula.valem.core.model.ModelCoordinate;

import java.util.List;
import java.util.Map;

/**
 * The {@code mcp} (JSON-RPC-over-stdio) {@link ModelLink}: drives a model exposed by an MCP server via
 * its {@code mutate} / {@code get_state} / {@code get_field} tools (references design §4.1). A write-link
 * calls {@code mutate} then reads the target's {@code get_state} as the fold-back reply; a read-link
 * calls {@code get_field}.
 */
final class McpModelLink implements ModelLink {

    private final McpTools tools;
    private final String targetId;
    private final ModelCoordinate coordinate;

    McpModelLink(McpTools tools, String targetId, ModelCoordinate coordinate) {
        this.tools = tools;
        this.targetId = targetId;
        this.coordinate = coordinate;
    }

    @Override
    public ModelCoordinate coordinate() {
        return coordinate;
    }

    @Override
    public MutationReply mutate(Map<String, JsonNode> pathValues) {
        ObjectMapper mapper = tools.mapper();
        ObjectNode args = mapper.createObjectNode();
        args.put("id", targetId);
        args.set("mutations", mapper.valueToTree(pathValues));
        tools.callStructured("mutate", args);   // throws McpException on rollback/schema error
        JsonNode state = tools.callStructured("get_state", oneId(targetId));
        return new MutationReply(state != null ? state : mapper.nullNode(), List.of());
    }

    @Override
    public JsonNode getField(String path) {
        ObjectMapper mapper = tools.mapper();
        ObjectNode args = mapper.createObjectNode();
        args.put("id", targetId);
        args.put("path", path);
        JsonNode v = tools.callValue("get_field", args);
        return v != null ? v : mapper.nullNode();
    }

    private ObjectNode oneId(String id) {
        ObjectNode o = tools.mapper().createObjectNode();
        o.put("id", id);
        return o;
    }
}
