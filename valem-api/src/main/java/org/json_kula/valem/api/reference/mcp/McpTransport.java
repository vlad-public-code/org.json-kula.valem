package org.json_kula.valem.api.reference.mcp;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * A Model Context Protocol JSON-RPC 2.0 transport (references design §4.1, {@code mcp} transport). The
 * mcp transport is orthogonal to a repository's <em>class</em> — the same server can be wired local- or
 * web-class. Implementations frame messages however the channel requires (stdio line-framing for a
 * child process; an in-process pipe for tests).
 */
public interface McpTransport extends AutoCloseable {

    /** Sends a JSON-RPC <em>request</em> and returns its {@code result}, or throws {@link McpException} on error. */
    JsonNode rpc(String method, JsonNode params);

    /** Sends a JSON-RPC <em>notification</em> (no id, no reply). */
    void notification(String method, JsonNode params);

    @Override
    void close();

    /** A failed MCP call — a JSON-RPC error or a closed channel. */
    class McpException extends RuntimeException {
        public McpException(String message) { super(message); }
        public McpException(String message, Throwable cause) { super(message, cause); }
    }
}
