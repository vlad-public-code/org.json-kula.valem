package org.json_kula.valem.mcp;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.NullNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.json_kula.valem.cli.CliBootstrap;
import org.json_kula.valem.service.ModelOperations;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.util.Set;

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
 * <pre>
 *   java -jar valem-mcp.jar                       # embedded, in-memory (default)
 *   java -jar valem-mcp.jar --url https://host    # remote against valem-web
 * </pre>
 */
public class McpServer {

    /** Protocol versions this server understands; the newest is the default when negotiation misses. */
    private static final String LATEST_PROTOCOL_VERSION = "2025-06-18";
    private static final Set<String> SUPPORTED_PROTOCOL_VERSIONS =
            Set.of("2024-11-05", "2025-03-26", "2025-06-18");

    private static final String SERVER_NAME    = "valem";
    private static final String SERVER_VERSION = "1.0.0";

    // JSON-RPC 2.0 error codes.
    private static final int PARSE_ERROR         = -32700;
    private static final int INVALID_REQUEST     = -32600;
    private static final int METHOD_NOT_FOUND    = -32601;
    private static final int INTERNAL_ERROR      = -32603;
    private static final int RESOURCE_NOT_FOUND  = -32002;   // MCP resource-not-found code

    private final ObjectMapper      mapper;
    private final ToolRegistry      tools;
    private final ResourceRegistry  resources;

    /** Embeddable: drive this server over any stream pair (stdio in {@code main}, a pipe in tests). */
    public McpServer(ModelOperations service, ObjectMapper mapper) {
        this.mapper    = mapper;
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

        ModelOperations service = CliBootstrap.createModelOperations(opts, mapper);
        // Diagnostics go to stderr only — stdout is reserved for the JSON-RPC protocol. The API key
        // (if any) is never printed.
        if (opts.remote()) {
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
              -h, --help         Print this help and exit
              -V, --version      Print the version and exit

            Environment:
              VALEM_URL, VALEM_API_KEY   Fallbacks for --url / --api-key

            With no --url the server runs embedded and in-memory (zero config). In remote mode the pure
            authoring/verify tools (validate_spec, eval_expression, test_spec, dry_run) still run locally.""");
    }

    /**
     * Runs the stdio read/dispatch/write loop until the input stream closes. Each input line is one
     * JSON-RPC message; each non-notification message produces exactly one response line.
     */
    public void run(InputStream in, OutputStream out) throws Exception {
        BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
        Writer writer = new BufferedWriter(new OutputStreamWriter(out, StandardCharsets.UTF_8));

        String line;
        while ((line = reader.readLine()) != null) {
            // Strip a leading UTF-8 BOM (U+FEFF), which strip() does not treat as whitespace. Some
            // launchers (notably Windows PowerShell) prepend one to the first line of the stream.
            if (!line.isEmpty() && line.charAt(0) == 0xFEFF) {
                line = line.substring(1);
            }
            line = line.strip();
            if (line.isEmpty()) continue;

            JsonNode response;
            try {
                JsonNode message = mapper.readTree(line);
                response = handleMessage(message);
            } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
                System.err.println("[valem-mcp] parse error: " + e.getOriginalMessage());
                response = errorResponse(NullNode.getInstance(), PARSE_ERROR, "Parse error: " + e.getOriginalMessage());
            }

            if (response != null) {
                writer.write(mapper.writeValueAsString(response));
                writer.write('\n');
                writer.flush();
            }
        }
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
            return null;
        }
        if (method == null) {
            return errorResponse(idNode, INVALID_REQUEST, "Missing 'method'");
        }

        try {
            return switch (method) {
                case "initialize"     -> successResponse(idNode, initializeResult(params));
                case "ping"           -> successResponse(idNode, mapper.createObjectNode());
                case "tools/list"     -> successResponse(idNode, toolsListResult());
                case "tools/call"     -> successResponse(idNode, toolsCallResult(params));
                case "resources/list" -> successResponse(idNode, resourcesListResult());
                case "resources/read" -> resourcesReadResponse(idNode, params);
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

        ObjectNode result = mapper.createObjectNode();
        result.put("protocolVersion", negotiated);
        // Advertise the tools and resources capabilities (no prompts).
        ObjectNode capabilities = result.putObject("capabilities");
        capabilities.putObject("tools");
        capabilities.putObject("resources");
        ObjectNode serverInfo = result.putObject("serverInfo");
        serverInfo.put("name", SERVER_NAME);
        serverInfo.put("version", SERVER_VERSION);
        result.put("instructions",
                "Valem is a deterministic reactive computation runtime for structured JSON models. "
                + "Use create_model to compile a declarative ModelSpec (schema + derivations + constraints "
                + "+ effects), then mutate base fields — derived fields recompute automatically and "
                + "constraints are enforced. Read merged state with get_state, trace any value with explain, "
                + "and change the model in place with evolve_spec. State is in-memory for this session.");
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

    private JsonNode resourcesReadResponse(JsonNode id, JsonNode params) {
        String uri = params != null ? params.path("uri").asText(null) : null;
        if (uri == null || uri.isBlank()) {
            return errorResponse(id, INVALID_REQUEST, "resources/read requires a 'uri'");
        }
        ObjectNode contents = resources.read(uri);
        if (contents == null) {
            return errorResponse(id, RESOURCE_NOT_FOUND, "Resource not found: " + uri);
        }
        return successResponse(id, contents);
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
