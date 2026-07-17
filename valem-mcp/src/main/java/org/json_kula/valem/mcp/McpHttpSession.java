package org.json_kula.valem.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.json_kula.valem.service.ModelOperations;

import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * A Streamable-HTTP MCP session (§4.1). It reuses the transport-agnostic {@link McpServer} protocol
 * core — the same tools, resources, negotiation, and structured errors the stdio server exposes —
 * routing its server-initiated messages (log/progress/resource-update notifications, elicitation) to a
 * per-session <b>outbox</b> that the HTTP endpoint drains onto an SSE stream.
 *
 * <p>One session per {@code Mcp-Session-Id}. All sessions share the backing {@link ModelOperations}, so
 * a hosted server's models are shared across connected agents and with the REST API — the point of an
 * in-server MCP endpoint (no local jar, multiple clients on one server). Per-session state is only the
 * protocol state (negotiated version, log floor, resource subscriptions).
 *
 * <p>Transport-agnostic and Spring-free: the Spring {@code @RestController} that speaks HTTP is a thin
 * shell over this class, keeping the protocol logic unit-testable without a servlet container.
 */
public final class McpHttpSession {

    private final String id;
    private final McpServer server;
    private final BlockingQueue<JsonNode> outbox = new LinkedBlockingQueue<>();

    public McpHttpSession(String id, ModelOperations service, ObjectMapper mapper) {
        this.id = id;
        this.server = new McpServer(service, mapper);
        this.server.setNotificationSink(outbox::add);
    }

    public String id() {
        return id;
    }

    /** Handles one client message; returns the JSON-RPC response, or {@code null} for a notification. */
    public JsonNode handle(JsonNode message) {
        return server.handle(message);
    }

    /**
     * Waits up to {@code timeoutMs} for the next server-initiated message (a notification or elicitation
     * request) to stream to the client, or {@code null} if none arrived in the window.
     */
    public JsonNode pollOutbound(long timeoutMs) throws InterruptedException {
        return outbox.poll(timeoutMs, TimeUnit.MILLISECONDS);
    }

    /** Non-blocking drain of one queued outbound message, or {@code null} if the outbox is empty. */
    public JsonNode pollOutbound() {
        return outbox.poll();
    }

    /** Tears the session down (cancels any resource subscriptions). */
    public void close() {
        server.closeAllSubscriptions();
    }
}
