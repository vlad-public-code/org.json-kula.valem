package org.json_kula.valem.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import org.json_kula.valem.mcp.McpHttpSession;
import org.json_kula.valem.service.ModelService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Streamable-HTTP MCP transport (§4.1): an MCP endpoint on the server itself, so hosted/remote agents
 * can connect without a local jar and share one server's models with each other and with the REST API.
 *
 * <p>It reuses the exact protocol core the stdio {@code valem-mcp} server runs (via {@link McpHttpSession}
 * → {@code McpServer}); this controller is only the transport shim plus session management:
 *
 * <ul>
 *   <li><b>POST /mcp</b> — a JSON-RPC request (or batch) in, its response(s) out as {@code application/json}
 *       (or {@code 202 Accepted} when the body is only notifications). An {@code initialize} establishes a
 *       session and the response carries an {@code Mcp-Session-Id} header; later calls echo it back.</li>
 *   <li><b>GET /mcp</b> — opens a {@code text/event-stream} the server pushes notifications on
 *       (log/progress/{@code resources/updated}), keyed by {@code Mcp-Session-Id}.</li>
 *   <li><b>DELETE /mcp</b> — terminates a session.</li>
 * </ul>
 *
 * <p>Auth rides the same {@code valem.api.key} gate as every other endpoint (see {@code SecurityConfig}).
 * Per the spec, {@code Origin} is validated to prevent DNS-rebinding: a browser {@code Origin} must be in
 * {@code valem.mcp.allowed-origins} (comma-separated) — unless that list is empty, in which case the
 * endpoint is open, matching the API's open-by-default development posture (restrict it in production).
 */
@RestController
@RequestMapping("/mcp")
public class McpHttpController {

    private static final Logger log = LoggerFactory.getLogger(McpHttpController.class);

    /** Cap on concurrent sessions, so a client cannot exhaust memory by never sending DELETE. */
    private static final int MAX_SESSIONS = 1000;
    /** SSE poll slice; a keepalive comment goes out each time it lapses so disconnects are detected. */
    private static final long SSE_POLL_MS = 15_000;

    private final ModelService service;
    private final ObjectMapper mapper;
    private final List<String> allowedOrigins;

    private final Map<String, McpHttpSession> sessions = new ConcurrentHashMap<>();
    private final ExecutorService sseWorkers = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "valem-mcp-http-sse");
        t.setDaemon(true);
        return t;
    });

    public McpHttpController(ModelService service, ObjectMapper mapper,
                             @Value("${valem.mcp.allowed-origins:}") String origins) {
        this.service = service;
        this.mapper  = mapper;
        this.allowedOrigins = origins == null || origins.isBlank()
                ? List.of()
                : Arrays.stream(origins.split(",")).map(String::trim).filter(s -> !s.isEmpty()).toList();
    }

    // ── POST: client → server messages ────────────────────────────────────────────

    @PostMapping(produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<String> post(@RequestBody String body,
                                       @RequestHeader(value = "Mcp-Session-Id", required = false) String sessionId,
                                       @RequestHeader(value = "Origin", required = false) String origin)
            throws Exception {
        requireOrigin(origin);

        JsonNode payload;
        try {
            payload = mapper.readTree(body);
        } catch (Exception e) {
            return json(HttpStatus.BAD_REQUEST, errorEnvelope("Parse error: " + e.getMessage()));
        }

        // Resolve or create the session. An initialize (single or first-in-batch) mints one.
        boolean hasInitialize = containsInitialize(payload);
        McpHttpSession session;
        boolean transient_ = false;
        if (sessionId != null && !sessionId.isBlank()) {
            session = sessions.get(sessionId);
            if (session == null) {
                return json(HttpStatus.NOT_FOUND, errorEnvelope("Unknown or expired Mcp-Session-Id"));
            }
        } else if (hasInitialize) {
            if (sessions.size() >= MAX_SESSIONS) {
                return json(HttpStatus.TOO_MANY_REQUESTS, errorEnvelope("Too many MCP sessions"));
            }
            session = new McpHttpSession(UUID.randomUUID().toString(), service, mapper);
            sessions.put(session.id(), session);
        } else {
            // Stateless fallback: a one-off session for a lone tool call with no session management.
            session = new McpHttpSession(UUID.randomUUID().toString(), service, mapper);
            transient_ = true;
        }

        try {
            ArrayNode responses = mapper.createArrayNode();
            if (payload.isArray()) {
                for (JsonNode msg : payload) {
                    JsonNode r = session.handle(msg);
                    if (r != null) responses.add(r);
                }
            } else {
                JsonNode r = session.handle(payload);
                if (r != null) responses.add(r);
            }

            if (responses.isEmpty()) {
                return ResponseEntity.accepted().build();   // notifications only → 202, no body
            }
            String out = payload.isArray()
                    ? mapper.writeValueAsString(responses)
                    : mapper.writeValueAsString(responses.get(0));
            ResponseEntity.BodyBuilder b = ResponseEntity.ok().contentType(MediaType.APPLICATION_JSON);
            if (hasInitialize && !transient_) b.header("Mcp-Session-Id", session.id());
            return b.body(out);
        } finally {
            if (transient_) session.close();
        }
    }

    // ── GET: server → client SSE stream ────────────────────────────────────────────

    @GetMapping(produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream(@RequestHeader(value = "Mcp-Session-Id", required = false) String sessionId,
                             @RequestHeader(value = "Origin", required = false) String origin) {
        requireOrigin(origin);
        McpHttpSession session = sessionId == null ? null : sessions.get(sessionId);
        if (session == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Unknown or missing Mcp-Session-Id");
        }
        SseEmitter emitter = new SseEmitter(0L);   // no server-side timeout; ended by client disconnect
        sseWorkers.submit(() -> pumpSse(session, emitter));
        return emitter;
    }

    private void pumpSse(McpHttpSession session, SseEmitter emitter) {
        try {
            while (true) {
                JsonNode msg = session.pollOutbound(SSE_POLL_MS);
                if (msg == null) {
                    emitter.send(SseEmitter.event().comment("keepalive"));   // throws on client disconnect
                } else {
                    emitter.send(SseEmitter.event()
                            .data(mapper.writeValueAsString(msg), MediaType.APPLICATION_JSON));
                }
            }
        } catch (Exception e) {
            emitter.completeWithError(e);   // client disconnected or send failed — end the stream
        }
    }

    // ── DELETE: session termination ────────────────────────────────────────────────

    @DeleteMapping
    public ResponseEntity<Void> terminate(
            @RequestHeader(value = "Mcp-Session-Id", required = false) String sessionId) {
        if (sessionId != null) {
            McpHttpSession session = sessions.remove(sessionId);
            if (session != null) session.close();
        }
        return ResponseEntity.noContent().build();
    }

    // ── helpers ────────────────────────────────────────────────────────────────────

    private void requireOrigin(String origin) {
        if (origin == null || origin.isBlank()) return;        // non-browser client, no Origin to spoof
        if (allowedOrigins.isEmpty()) return;                  // open/dev default (see class Javadoc)
        if (!allowedOrigins.contains(origin)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Origin not allowed");
        }
    }

    private static boolean containsInitialize(JsonNode payload) {
        if (payload.isArray()) {
            for (JsonNode m : payload) {
                if ("initialize".equals(m.path("method").asText(null))) return true;
            }
            return false;
        }
        return "initialize".equals(payload.path("method").asText(null));
    }

    private ResponseEntity<String> json(HttpStatus status, String body) {
        return ResponseEntity.status(status).contentType(MediaType.APPLICATION_JSON).body(body);
    }

    private String errorEnvelope(String message) {
        return "{\"jsonrpc\":\"2.0\",\"id\":null,\"error\":{\"code\":-32700,\"message\":"
                + quote(message) + "}}";
    }

    private String quote(String s) {
        try {
            return mapper.writeValueAsString(s);
        } catch (Exception e) {
            return "\"error\"";
        }
    }
}
