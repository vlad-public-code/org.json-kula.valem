package org.json_kula.valem.mcp;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.NullNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.json_kula.valem.cli.CliBootstrap;
import org.json_kula.valem.service.ChangeSubscribable;
import org.json_kula.valem.service.ModelOperations;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Valem MCP server — exposes the Valem runtime ({@link ModelOperations}) over the Model
 * Context Protocol so any MCP-compatible agent (Claude Code, Claude Desktop, and others) can use
 * Valem as the structured-state backend for a session: create a model from a spec, mutate base
 * fields, read the reactively-computed merged state, and trace why any value is what it is.
 *
 * <h2>Transport</h2>
 * <p>MCP over <b>stdio</b>: newline-delimited JSON-RPC 2.0. One JSON message per line on {@code stdin};
 * one JSON response per line on {@code stdout}. Messages never contain embedded newlines (JSON string
 * values escape them), so line framing is unambiguous. <b>Only protocol messages go to stdout</b> —
 * all diagnostics are written to {@code stderr}, which the transport ignores.
 *
 * <h2>Handshake</h2>
 * <p>{@code initialize} → server capabilities + info; {@code notifications/initialized} (no reply);
 * {@code tools/list} → the {@link ToolRegistry} surface; {@code tools/call} → execute a tool.
 * {@code ping} → {@code {}}. Unknown methods return JSON-RPC error {@code -32601}.
 *
 * <h2>Storage &amp; modes</h2>
 * <p>By default all state is in memory for the life of the process (mirrors {@code valem-console});
 * nothing is persisted. Pass {@code --url <base>} (optionally {@code --api-key <key>}, or the
 * {@code VALEM_URL}/{@code VALEM_API_KEY} environment variables) to drive a durable, shared
 * {@code valem-web} server instead — model operations then hit the server while the pure
 * authoring/verify tools ({@code validate_spec}, {@code eval_expression}, {@code test_spec},
 * {@code dry_run}) still run locally.
 *
 * <p>Adding {@code --browser} to {@code --url} selects <b>{@code remote_with_browser}</b> mode: instead
 * of an API key, the MCP pairs with a browser tab on that host over a device-flow handshake (the
 * {@code pair_browser} tool mints/prints a verification link + confirmation code, and resumes polling
 * until the developer approves it) and then drives the same <b>shared, live session</b> the browser is
 * on — so an agent-authored {@code evolve_spec} lands on the model the developer is looking at, and the
 * browser re-renders automatically. See the MCP guide's "pair with the sandbox" section.
 *
 * <pre>
 *   java -jar valem-mcp.jar                                # embedded, in-memory (default)
 *   java -jar valem-mcp.jar --url https://host             # remote against valem-web
 *   java -jar valem-mcp.jar --url https://host --browser   # remote_with_browser (paired sandbox session)
 * </pre>
 */
public class McpServer {

    /**
     * Protocol versions this server understands; the newest is the default when negotiation misses.
     * {@code 2025-11-25} (the current spec revision, verified against modelcontextprotocol.io) adds
     * URL-mode elicitation, which {@code pair_browser} uses to hand the developer the verification link
     * directly (§4.3).
     */
    private static final String LATEST_PROTOCOL_VERSION = "2025-11-25";
    private static final Set<String> SUPPORTED_PROTOCOL_VERSIONS =
            Set.of("2024-11-05", "2025-03-26", "2025-06-18", "2025-11-25");

    private static final String SERVER_NAME    = "valem";
    private static final String SERVER_VERSION = "1.0.0";

    // JSON-RPC 2.0 error codes.
    private static final int PARSE_ERROR         = -32700;
    private static final int INVALID_REQUEST     = -32600;
    private static final int METHOD_NOT_FOUND    = -32601;
    private static final int INTERNAL_ERROR      = -32603;
    private static final int RESOURCE_NOT_FOUND  = -32002;   // MCP resource-not-found code

    /** Syslog severities the {@code logging} capability recognises, low → high (§3.5). */
    private static final List<String> LOG_LEVELS = List.of(
            "debug", "info", "notice", "warning", "error", "critical", "alert", "emergency");

    /** URI scheme for a model's subscribable merged-state resource (§4.2): {@code valem://state/<id>}. */
    private static final String STATE_URI_PREFIX = "valem://state/";

    private final ObjectMapper      mapper;
    private final ModelOperations   service;
    private final ToolRegistry      tools;
    private final ResourceRegistry  resources;

    /** Active {@code resources/subscribe} handles keyed by resource uri (§4.2). */
    private final Map<String, AutoCloseable> subscriptions = new ConcurrentHashMap<>();

    /**
     * Where server-initiated messages (log/progress/resource-update notifications, elicitation requests)
     * are delivered. Defaults to the stdio writer; the Streamable-HTTP transport (§4.1) swaps in a
     * per-session sink so the same protocol core drives an HTTP endpoint. Thread-safe: a synchronized
     * writer for stdio, a concurrent queue for HTTP.
     */
    private volatile java.util.function.Consumer<JsonNode> notificationSink = this::writeMessage;

    /** The minimum log severity the client wants (set via {@code logging/setLevel}); default {@code info}. */
    private volatile String minLogLevel = "info";

    // ── Async dispatch state (§3.2), set up per run() ─────────────────────────────
    /** The output writer for the active {@link #run}; every write goes through {@link #writeMessage}. */
    private Writer runWriter;
    /** Guards {@link #runWriter} so worker progress writes never interleave with response writes. */
    private final Object writeLock = new Object();
    /** In-flight long-running requests by id, each with a flag flipped on {@code notifications/cancelled}. */
    private final Map<String, AtomicBoolean> inFlight = new ConcurrentHashMap<>();
    /** Worker pool for long-poll tools (pair_browser); daemon threads so JVM exit is never blocked. */
    private ExecutorService workers;

    // ── Elicitation (§4.3), negotiated at initialize ──────────────────────────────
    /** True once the client advertised the {@code elicitation} capability with URL mode support. */
    private volatile boolean clientSupportsUrlElicitation;
    /** Monotonic id source for server-initiated requests (elicitation/create). */
    private final java.util.concurrent.atomic.AtomicLong serverRequestSeq = new java.util.concurrent.atomic.AtomicLong();

    /** Embeddable: drive this server over any stream pair (stdio in {@code main}, a pipe in tests). */
    public McpServer(ModelOperations service, ObjectMapper mapper) {
        this.mapper    = mapper;
        this.service   = service;
        this.tools     = new ToolRegistry(service, mapper);
        this.resources = new ResourceRegistry(mapper);
    }

    public static void main(String[] args) throws Exception {
        ObjectMapper mapper = new ObjectMapper()
                .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
                .configure(DeserializationFeature.READ_ENUMS_USING_TO_STRING, false)
                .configure(SerializationFeature.FAIL_ON_EMPTY_BEANS, false);

        CliBootstrap.Options opts = CliBootstrap.parse(args, System::getenv);
        if (opts.help()) {
            printUsage(System.out);
            return;
        }
        if (opts.version()) {
            System.out.println(SERVER_NAME + " " + SERVER_VERSION);
            return;
        }
        if (!opts.unknown().isEmpty()) {
            System.err.println("[valem-mcp] unknown argument(s): " + opts.unknown());
            printUsage(System.err);
            return;
        }
        if (opts.browser() && !opts.remote()) {
            System.err.println("[valem-mcp] --browser has no effect without --url (or VALEM_URL) — "
                    + "running embedded; pass --url <host> --browser for remote_with_browser mode");
        }

        // remote_with_browser is its own facade (device-flow pairing + session protocol) built here
        // rather than by CliBootstrap — it has no ModelOperations to hand back until pairing completes,
        // unlike the embedded/plain-remote facades CliBootstrap builds eagerly.
        ModelOperations service = opts.remoteWithBrowser()
                ? new SandboxSessionModelOperations(opts.url(), mapper)
                : CliBootstrap.createModelOperations(opts, mapper);
        // Diagnostics go to stderr only — stdout is reserved for the JSON-RPC protocol. The API key
        // (if any) is never printed; neither is a session token or device secret once pairing runs.
        if (opts.remoteWithBrowser()) {
            System.err.println("[valem-mcp] remote_with_browser mode: driving " + opts.url()
                    + " — call the pair_browser tool to start pairing");
        } else if (opts.remote()) {
            System.err.println("[valem-mcp] remote mode: driving " + opts.url());
        }
        new McpServer(service, mapper).run(System.in, System.out);
    }

    private static void printUsage(java.io.PrintStream out) {
        out.println("""
            valem-mcp — Valem MCP server (JSON-RPC 2.0 over stdio)

            Usage:
              java -jar valem-mcp.jar [options]

            Options:
              --url <base>       Drive a remote valem-web server (default: embedded, in-memory)
              --api-key <key>    API key for the remote server
              --browser          With --url: pair with a browser tab on that host instead of an API key
                                 (remote_with_browser mode — e.g. the hosted Valem sandbox)
              -h, --help         Print this help and exit
              -V, --version      Print the version and exit

            Environment:
              VALEM_URL, VALEM_API_KEY   Fallbacks for --url / --api-key

            With no --url the server runs embedded and in-memory (zero config). In remote mode the pure
            authoring/verify tools (validate_spec, eval_expression, test_spec, dry_run) still run locally.
            In remote_with_browser mode (--url + --browser), call the pair_browser tool to obtain a
            verification link and confirmation code for the developer to approve in their browser; once
            approved, model operations drive that shared session.""");
    }

    /**
     * Runs the stdio read/dispatch/write loop until the input stream closes. Each input line is one
     * JSON-RPC message. Most requests are handled inline on the read thread; a long-poll tool
     * ({@code pair_browser}) is dispatched to a worker so the read loop stays free to receive
     * {@code notifications/cancelled} and so the tool can emit {@code notifications/progress} (§3.2).
     */
    public void run(InputStream in, OutputStream out) throws Exception {
        BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
        this.runWriter = new BufferedWriter(new OutputStreamWriter(out, StandardCharsets.UTF_8));
        this.workers = Executors.newCachedThreadPool(r -> {
            Thread t = new Thread(r, "valem-mcp-worker");
            t.setDaemon(true);
            return t;
        });

        try {
            String line;
            while ((line = reader.readLine()) != null) {
                // Strip a leading UTF-8 BOM (U+FEFF), which strip() does not treat as whitespace. Some
                // launchers (notably Windows PowerShell) prepend one to the first line of the stream.
                if (!line.isEmpty() && line.charAt(0) == 0xFEFF) {
                    line = line.substring(1);
                }
                line = line.strip();
                if (line.isEmpty()) continue;

                try {
                    dispatch(mapper.readTree(line));
                } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
                    System.err.println("[valem-mcp] parse error: " + e.getOriginalMessage());
                    writeMessage(errorResponse(NullNode.getInstance(), PARSE_ERROR,
                            "Parse error: " + e.getOriginalMessage()));
                }
            }
        } finally {
            closeAllSubscriptions();
            workers.shutdown();
            try {
                workers.awaitTermination(5, TimeUnit.SECONDS);   // let a just-finished pairing flush
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    // ── Reusable protocol entry points (shared by the stdio loop and the HTTP transport, §4.1) ─────

    /**
     * Handles one client message and returns the JSON-RPC response, or {@code null} for a notification.
     * Server-initiated messages (notifications, elicitation) go to the configured
     * {@link #setNotificationSink notification sink}. This is the transport-agnostic core the stdio loop
     * and the Streamable-HTTP endpoint both call.
     */
    public JsonNode handle(JsonNode message) {
        return handleMessage(message);
    }

    /** Redirects server-initiated messages (used by the HTTP transport to fan out to an SSE stream). */
    public void setNotificationSink(java.util.function.Consumer<JsonNode> sink) {
        this.notificationSink = sink != null ? sink : this::writeMessage;
    }

    /** Cancels all active resource subscriptions (session teardown). */
    public void closeAllSubscriptions() {
        subscriptions.keySet().forEach(this::closeSubscription);
    }

    /**
     * Routes one parsed message: long-poll tool calls go to a worker, everything else (requests and
     * notifications) is handled inline via {@link #handleMessage}.
     */
    private void dispatch(JsonNode message) {
        if (message != null && message.isObject()) {
            JsonNode idNode = message.get("id");
            boolean hasId = idNode != null && !idNode.isNull();
            boolean hasMethod = message.hasNonNull("method");
            // A message with an id and result/error but no method is the client's RESPONSE to a
            // server-initiated request (elicitation/create). We fire those and do not need the answer,
            // so drop it rather than let handleMessage mistake it for a request (§4.3).
            if (hasId && !hasMethod && (message.has("result") || message.has("error"))) {
                return;
            }
            JsonNode params = message.get("params");
            if (hasId && "tools/call".equals(message.path("method").asText(null))
                    && params != null && tools.isLongRunning(params.path("name").asText(null))) {
                dispatchLongRunning(idNode, params);
                return;
            }
        }
        JsonNode response = handleMessage(message);
        if (response != null) writeMessage(response);
    }

    /**
     * Runs a long-poll tool on a worker thread. The read loop keeps running so a
     * {@code notifications/cancelled} for this id can flip the cancellation flag mid-wait; the tool
     * emits {@code notifications/progress} against the request's {@code progressToken} if one was given.
     */
    private void dispatchLongRunning(JsonNode idNode, JsonNode params) {
        String key = idNode.toString();
        AtomicBoolean cancelled = new AtomicBoolean(false);
        inFlight.put(key, cancelled);
        JsonNode meta = params.path("_meta");
        JsonNode progressToken = meta.has("progressToken") ? meta.get("progressToken") : null;
        String name = params.path("name").asText(null);
        JsonNode arguments = params.get("arguments");
        java.util.concurrent.atomic.AtomicReference<String> elicitationId = new java.util.concurrent.atomic.AtomicReference<>();

        ProgressHandle handle = new ProgressHandle() {
            @Override public void report(double progress, Double total, String message) {
                if (progressToken == null || cancelled.get()) return;
                notificationSink.accept(progressNotification(progressToken, progress, total, message));
            }
            @Override public boolean cancelled() { return cancelled.get(); }
            @Override public void elicitUrl(String url, String message) {
                if (!clientSupportsUrlElicitation || cancelled.get() || elicitationId.get() != null) return;
                String id = "elicit-" + serverRequestSeq.incrementAndGet();
                elicitationId.set(id);
                notificationSink.accept(urlElicitationRequest(id, url, message));
            }
        };

        workers.submit(() -> {
            try {
                ObjectNode result = tools.call(name, arguments, handle);
                if (!cancelled.get()) writeMessage(successResponse(idNode, result));
                // Once paired, tell the client the out-of-band interaction it opened is done (§4.3).
                String eid = elicitationId.get();
                if (eid != null && !cancelled.get() && isPairedResult(result)) {
                    notificationSink.accept(elicitationCompleteNotification(eid));
                }
            } catch (RuntimeException e) {
                String msg = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
                if (!cancelled.get()) writeMessage(errorResponse(idNode, INTERNAL_ERROR, msg));
            } finally {
                inFlight.remove(key);
            }
        });
    }

    /** True when a tool result's structured content reports a completed pairing. */
    private static boolean isPairedResult(ObjectNode result) {
        String status = result.path("structuredContent").path("status").asText("");
        return "paired".equals(status) || "already_paired".equals(status);
    }

    /** Builds a URL-mode {@code elicitation/create} request (2025-11-25). */
    private ObjectNode urlElicitationRequest(String elicitationId, String url, String message) {
        ObjectNode req = mapper.createObjectNode();
        req.put("jsonrpc", "2.0");
        req.put("id", "srv-" + serverRequestSeq.incrementAndGet());
        req.put("method", "elicitation/create");
        ObjectNode p = req.putObject("params");
        p.put("mode", "url");
        p.put("elicitationId", elicitationId);
        p.put("url", url);
        p.put("message", message);
        return req;
    }

    /** Builds the {@code notifications/elicitation/complete} notification for a finished URL elicitation. */
    private ObjectNode elicitationCompleteNotification(String elicitationId) {
        ObjectNode note = mapper.createObjectNode();
        note.put("jsonrpc", "2.0");
        note.put("method", "notifications/elicitation/complete");
        note.putObject("params").put("elicitationId", elicitationId);
        return note;
    }

    /** Marks the in-flight request named by {@code notifications/cancelled} as cancelled, if present. */
    private void handleCancelled(JsonNode params) {
        JsonNode requestId = params != null ? params.get("requestId") : null;
        if (requestId == null || requestId.isNull()) return;
        AtomicBoolean flag = inFlight.get(requestId.toString());
        if (flag != null) flag.set(true);
    }

    /** Writes one JSON-RPC message as a line, flushing. Serialised so worker + read-thread writes never mix. */
    private void writeMessage(JsonNode message) {
        if (runWriter == null) return;   // no stdio transport active (e.g. a direct handleMessage call)
        synchronized (writeLock) {
            try {
                runWriter.write(mapper.writeValueAsString(message));
                runWriter.write('\n');
                runWriter.flush();
            } catch (java.io.IOException e) {
                System.err.println("[valem-mcp] write failed: " + e.getMessage());
            }
        }
    }

    private ObjectNode progressNotification(JsonNode progressToken, double progress, Double total, String message) {
        ObjectNode note = mapper.createObjectNode();
        note.put("jsonrpc", "2.0");
        note.put("method", "notifications/progress");
        ObjectNode p = note.putObject("params");
        p.set("progressToken", progressToken);
        p.put("progress", progress);
        if (total != null)   p.put("total", total);
        if (message != null) p.put("message", message);
        return note;
    }

    /**
     * Handles one parsed JSON-RPC message. Returns the response object for a request, or {@code null}
     * for a notification (a message with no {@code id}) — notifications must not be answered.
     */
    JsonNode handleMessage(JsonNode message) {
        if (message == null || !message.isObject()) {
            return errorResponse(NullNode.getInstance(), INVALID_REQUEST, "Request must be a JSON object");
        }

        JsonNode idNode = message.get("id");
        boolean isNotification = idNode == null || idNode.isNull();
        String method = message.path("method").asText(null);
        JsonNode params = message.get("params");

        // Notifications (initialized, cancelled, …) are processed for side effects only, never answered.
        if (isNotification) {
            // On the client's initialized signal, emit a one-line readiness log (§3.5) — surfaces mode
            // info that otherwise only reaches stderr. Enqueued; run() flushes it after this message.
            if ("notifications/initialized".equals(method)) {
                enqueueLog("info", "logger", readinessMessage());
            } else if ("notifications/cancelled".equals(method)) {
                handleCancelled(params);   // flip an in-flight long-poll's cancellation flag (§3.2)
            }
            return null;
        }
        if (method == null) {
            return errorResponse(idNode, INVALID_REQUEST, "Missing 'method'");
        }

        try {
            return switch (method) {
                case "initialize"                -> successResponse(idNode, initializeResult(params));
                case "ping"                      -> successResponse(idNode, mapper.createObjectNode());
                case "tools/list"                -> successResponse(idNode, toolsListResult());
                case "tools/call"                -> successResponse(idNode, toolsCallResult(params));
                case "resources/list"            -> successResponse(idNode, resourcesListResult());
                case "resources/templates/list"  -> successResponse(idNode, resourcesTemplatesListResult());
                case "resources/read"            -> resourcesReadResponse(idNode, params);
                case "resources/subscribe"       -> resourcesSubscribeResponse(idNode, params);
                case "resources/unsubscribe"     -> successResponse(idNode, resourcesUnsubscribeResult(params));
                case "logging/setLevel"          -> successResponse(idNode, setLogLevelResult(params));
                default -> errorResponse(idNode, METHOD_NOT_FOUND, "Method not found: " + method);
            };
        } catch (Exception e) {
            String msg = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
            System.err.println("[valem-mcp] error handling '" + method + "': " + msg);
            return errorResponse(idNode, INTERNAL_ERROR, msg);
        }
    }

    // ── Method results ────────────────────────────────────────────────────────────

    private ObjectNode initializeResult(JsonNode params) {
        String requested = params != null ? params.path("protocolVersion").asText(null) : null;
        String negotiated = (requested != null && SUPPORTED_PROTOCOL_VERSIONS.contains(requested))
                ? requested : LATEST_PROTOCOL_VERSION;

        // Record whether the client can accept URL-mode elicitation. Per the spec, `elicitation: {}`
        // means form mode only; URL mode requires an explicit `elicitation.url` member — and the server
        // MUST NOT send a mode the client did not advertise.
        JsonNode elicitation = params != null ? params.path("capabilities").path("elicitation") : null;
        this.clientSupportsUrlElicitation = elicitation != null && elicitation.isObject()
                && elicitation.has("url");

        ObjectNode result = mapper.createObjectNode();
        result.put("protocolVersion", negotiated);
        // Advertise the tools and resources capabilities (no prompts).
        ObjectNode capabilities = result.putObject("capabilities");
        capabilities.putObject("tools");
        // resources.subscribe: clients may subscribe to a model's state resource and get
        // notifications/resources/updated when it changes (in a paired session, on the browser's edits).
        capabilities.putObject("resources").put("subscribe", true);
        // logging: the server emits notifications/message; clients may raise the floor via logging/setLevel.
        capabilities.putObject("logging");
        ObjectNode serverInfo = result.putObject("serverInfo");
        serverInfo.put("name", SERVER_NAME);
        serverInfo.put("version", SERVER_VERSION);
        String instructions =
                "Valem is a deterministic reactive computation runtime for structured JSON models. "
                + "Use create_model to compile a declarative ModelSpec (schema + derivations + constraints "
                + "+ effects), then mutate base fields — derived fields recompute automatically and "
                + "constraints are enforced. Read merged state with get_state, trace any value with explain, "
                + "and change the model in place with evolve_spec. State is in-memory for this session.";
        if (tools.toolNames().contains("pair_browser")) {
            instructions +=
                " This server is in remote_with_browser mode: call pair_browser first to get a "
                + "verification link + confirmation code for the developer to approve in their browser; "
                + "model operations fail until that pairing completes. Once paired, create_model/mutate/"
                + "evolve_spec/get_state/explain all drive the SAME live session the browser has open, so "
                + "the developer's own entered data is visible to get_state/explain, and an evolve_spec "
                + "pushes the browser to re-render automatically — no copy-paste. Before evolve_spec, "
                + "prefer dry_run(evolvedSpec, currentInputs) to preview whether the change would strand "
                + "any of the developer's entered data; the server also rejects a stranding evolution "
                + "with an error as a backstop.";
        }
        result.put("instructions", instructions);
        return result;
    }

    private ObjectNode toolsListResult() {
        ObjectNode result = mapper.createObjectNode();
        result.set("tools", tools.listNode());
        return result;
    }

    private ObjectNode toolsCallResult(JsonNode params) {
        if (params == null || !params.isObject()) {
            throw new IllegalArgumentException("tools/call requires params with 'name' and 'arguments'");
        }
        String name = params.path("name").asText(null);
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("tools/call requires a 'name'");
        }
        return tools.call(name, params.get("arguments"));
    }

    private ObjectNode resourcesListResult() {
        ObjectNode result = mapper.createObjectNode();
        result.set("resources", resources.listNode());
        return result;
    }

    private ObjectNode resourcesTemplatesListResult() {
        ObjectNode result = mapper.createObjectNode();
        result.set("resourceTemplates", resources.templatesNode());
        return result;
    }

    /**
     * Handles {@code logging/setLevel}: records the minimum severity the client wants and returns an
     * empty result. Unknown levels are ignored (the floor stays put).
     */
    private ObjectNode setLogLevelResult(JsonNode params) {
        String level = params != null ? params.path("level").asText(null) : null;
        if (level != null && LOG_LEVELS.contains(level)) {
            this.minLogLevel = level;
        }
        return mapper.createObjectNode();
    }

    private JsonNode resourcesReadResponse(JsonNode id, JsonNode params) {
        String uri = params != null ? params.path("uri").asText(null) : null;
        if (uri == null || uri.isBlank()) {
            return errorResponse(id, INVALID_REQUEST, "resources/read requires a 'uri'");
        }
        // A model's state resource (valem://state/<id>) is read live from the service (§4.2).
        if (uri.startsWith(STATE_URI_PREFIX)) {
            String modelId = uri.substring(STATE_URI_PREFIX.length());
            try {
                JsonNode state = service.getState(modelId, null);
                ObjectNode contents = mapper.createObjectNode();
                ObjectNode item = contents.putArray("contents").addObject();
                item.put("uri", uri);
                item.put("mimeType", "application/json");
                item.put("text", mapper.writerWithDefaultPrettyPrinter().writeValueAsString(state));
                return successResponse(id, contents);
            } catch (Exception e) {
                String msg = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
                return errorResponse(id, RESOURCE_NOT_FOUND, "Cannot read " + uri + ": " + msg);
            }
        }
        ObjectNode contents = resources.read(uri);
        if (contents == null) {
            return errorResponse(id, RESOURCE_NOT_FOUND, "Resource not found: " + uri);
        }
        return successResponse(id, contents);
    }

    /**
     * Handles {@code resources/subscribe}: for a {@code valem://state/<id>} uri, opens a change
     * subscription (when the facade supports it) that emits {@code notifications/resources/updated} on
     * every committed mutation — so a paired agent sees the browser's edits without polling (§4.2).
     */
    private JsonNode resourcesSubscribeResponse(JsonNode id, JsonNode params) {
        String uri = params != null ? params.path("uri").asText(null) : null;
        if (uri == null || uri.isBlank()) {
            return errorResponse(id, INVALID_REQUEST, "resources/subscribe requires a 'uri'");
        }
        if (uri.startsWith(STATE_URI_PREFIX) && service instanceof ChangeSubscribable subscribable) {
            String modelId = uri.substring(STATE_URI_PREFIX.length());
            try {
                // Replace any existing subscription for this uri.
                closeSubscription(uri);
                AutoCloseable handle = subscribable.subscribeChanges(modelId,
                        changedId -> notificationSink.accept(resourceUpdatedNotification(STATE_URI_PREFIX + changedId)));
                subscriptions.put(uri, handle);
            } catch (RuntimeException e) {
                // Not paired yet, or the backend cannot stream — accept the subscribe as best-effort
                // (updates simply won't fire) rather than failing the request.
                System.err.println("[valem-mcp] subscribe to " + uri + " inactive: " + e.getMessage());
            }
        }
        return successResponse(id, mapper.createObjectNode());
    }

    private ObjectNode resourcesUnsubscribeResult(JsonNode params) {
        String uri = params != null ? params.path("uri").asText(null) : null;
        if (uri != null) closeSubscription(uri);
        return mapper.createObjectNode();
    }

    private void closeSubscription(String uri) {
        AutoCloseable handle = subscriptions.remove(uri);
        if (handle != null) {
            try { handle.close(); } catch (Exception ignored) { /* best effort */ }
        }
    }

    private ObjectNode resourceUpdatedNotification(String uri) {
        ObjectNode note = mapper.createObjectNode();
        note.put("jsonrpc", "2.0");
        note.put("method", "notifications/resources/updated");
        note.putObject("params").put("uri", uri);
        return note;
    }

    // ── Logging (§3.5) ────────────────────────────────────────────────────────────

    /**
     * Emits a {@code notifications/message} to the notification sink, unless {@code level} is below the
     * client-requested floor. Data is a plain string (the simplest allowed {@code data} shape).
     */
    private void enqueueLog(String level, String logger, String message) {
        if (LOG_LEVELS.indexOf(level) < LOG_LEVELS.indexOf(minLogLevel)) return;
        ObjectNode note = mapper.createObjectNode();
        note.put("jsonrpc", "2.0");
        note.put("method", "notifications/message");
        ObjectNode params = note.putObject("params");
        params.put("level", level);
        params.put("logger", logger);
        params.put("data", message);
        notificationSink.accept(note);
    }

    /** A short readiness line describing the tool count and (if applicable) remote_with_browser mode. */
    private String readinessMessage() {
        String base = "valem-mcp ready: " + tools.toolNames().size() + " tools";
        if (tools.toolNames().contains("pair_browser")) {
            base += " (remote_with_browser mode — call pair_browser to start pairing)";
        }
        return base;
    }

    // ── JSON-RPC envelopes ────────────────────────────────────────────────────────

    private ObjectNode successResponse(JsonNode id, JsonNode result) {
        ObjectNode response = mapper.createObjectNode();
        response.put("jsonrpc", "2.0");
        response.set("id", id);
        response.set("result", result);
        return response;
    }

    private ObjectNode errorResponse(JsonNode id, int code, String message) {
        ObjectNode response = mapper.createObjectNode();
        response.put("jsonrpc", "2.0");
        response.set("id", id == null ? NullNode.getInstance() : id);
        ObjectNode error = response.putObject("error");
        error.put("code", code);
        error.put("message", message);
        return response;
    }
}
