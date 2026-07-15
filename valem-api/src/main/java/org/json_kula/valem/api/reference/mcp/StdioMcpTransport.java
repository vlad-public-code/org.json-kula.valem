package org.json_kula.valem.api.reference.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicLong;

/**
 * {@link McpTransport} over newline-delimited JSON-RPC 2.0 on a byte-stream pair — the framing an MCP
 * stdio server speaks (one JSON message per line). Backs both a launched child process (its
 * stdin/stdout) and an in-process pipe (tests). Requests are serialized: one in-flight request at a
 * time per transport, which matches a single-threaded stdio server.
 */
public class StdioMcpTransport implements McpTransport {

    private final BufferedReader in;
    private final Writer out;
    private final ObjectMapper mapper;
    private final Process process;   // nullable — set when we launched a child process
    private final AtomicLong ids = new AtomicLong(1);
    private final Object lock = new Object();

    public StdioMcpTransport(InputStream in, OutputStream out, ObjectMapper mapper, Process process) {
        this.in = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
        this.out = new BufferedWriter(new OutputStreamWriter(out, StandardCharsets.UTF_8));
        this.mapper = mapper;
        this.process = process;
    }

    @Override
    public JsonNode rpc(String method, JsonNode params) {
        synchronized (lock) {
            long id = ids.getAndIncrement();
            ObjectNode req = mapper.createObjectNode();
            req.put("jsonrpc", "2.0");
            req.put("id", id);
            req.put("method", method);
            if (params != null) req.set("params", params);
            try {
                writeLine(req);
                // Read until the response with our id (skip any server notifications).
                while (true) {
                    String line = in.readLine();
                    if (line == null) throw new McpException("MCP transport closed while awaiting '" + method + "'");
                    line = line.strip();
                    if (line.isEmpty()) continue;
                    JsonNode msg = mapper.readTree(line);
                    JsonNode idNode = msg.get("id");
                    if (idNode == null || idNode.isNull()) continue;      // a notification
                    if (idNode.asLong() != id) continue;                  // not our response
                    if (msg.hasNonNull("error")) {
                        throw new McpException(method + ": " + msg.get("error").path("message").asText());
                    }
                    return msg.get("result");
                }
            } catch (McpException e) {
                throw e;
            } catch (Exception e) {
                throw new McpException("MCP '" + method + "' failed: " + e, e);
            }
        }
    }

    @Override
    public void notification(String method, JsonNode params) {
        synchronized (lock) {
            ObjectNode n = mapper.createObjectNode();
            n.put("jsonrpc", "2.0");
            n.put("method", method);
            if (params != null) n.set("params", params);
            try {
                writeLine(n);
            } catch (Exception e) {
                throw new McpException("MCP notification '" + method + "' failed: " + e, e);
            }
        }
    }

    private void writeLine(JsonNode message) throws Exception {
        out.write(mapper.writeValueAsString(message));
        out.write('\n');
        out.flush();
    }

    @Override
    public void close() {
        try { out.close(); } catch (Exception ignored) { }
        try { in.close(); } catch (Exception ignored) { }
        if (process != null) process.destroy();
    }
}
