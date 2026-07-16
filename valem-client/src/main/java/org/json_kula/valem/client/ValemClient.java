package org.json_kula.valem.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.core.type.TypeReference;
import org.json_kula.valem.client.ValemTypes.AuditQuery;
import org.json_kula.valem.client.ValemTypes.AuditRecord;
import org.json_kula.valem.client.ValemTypes.AuditVerification;
import org.json_kula.valem.client.ValemTypes.ChangeEvent;
import org.json_kula.valem.client.ValemTypes.CreateModelResponse;
import org.json_kula.valem.client.ValemTypes.DerivationTrace;
import org.json_kula.valem.client.ValemTypes.ModelInfo;
import org.json_kula.valem.client.ValemTypes.MutationResponse;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.WebSocket;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Thin, standalone Java client for the Valem REST + WebSocket API. One instance can drive many
 * models and is safe to share across threads. REST calls return parsed DTOs and throw
 * {@link ValemException} on any non-2xx response; {@link #subscribe} opens a reconnecting
 * WebSocket.
 *
 * <p>Depends only on the JDK ({@link java.net.http.HttpClient}) and Jackson — it does not pull in the
 * Valem engine, so a consumer app stays lightweight.
 */
public final class ValemClient implements AutoCloseable {

    private static final long[] DEFAULT_BACKOFF_MS = {500, 1000, 2000, 4000, 8000};

    private final String        baseUrl;   // no trailing slash
    private final String        apiKey;    // nullable
    private final String        authHeaderName;  // e.g. "Authorization" or "X-Session-Token"
    private final String        authHeaderValue; // precomputed, e.g. "Bearer <key>" or the bare token; null when no key
    private final HttpClient    http;
    private final ObjectMapper  mapper;
    private final WsConnector   wsConnector;
    private final long[]        backoffMs;

    private volatile ScheduledExecutorService reconnectScheduler;

    public ValemClient(String baseUrl) {
        this(baseUrl, null);
    }

    public ValemClient(String baseUrl, String apiKey) {
        this(baseUrl, apiKey, HttpClient.newHttpClient(), null, new ObjectMapper(), DEFAULT_BACKOFF_MS);
    }

    /**
     * A client authenticating with a custom header instead of {@code Authorization: Bearer} — e.g.
     * a Valem sandbox's {@code X-Session-Token} (device-flow paired sessions, sent with no value
     * prefix). The WebSocket handshake is unaffected: it already authenticates via a plain
     * {@code ?token=} query parameter, which a session-token host can accept identically.
     */
    public ValemClient(String baseUrl, String apiKey, String authHeaderName) {
        this(baseUrl, apiKey, authHeaderName, "", HttpClient.newHttpClient(), null,
                new ObjectMapper(), DEFAULT_BACKOFF_MS);
    }

    /** Full constructor — the {@code wsConnector}/{@code backoffMs} seams exist for testing. */
    ValemClient(String baseUrl, String apiKey, HttpClient http,
                     WsConnector wsConnector, ObjectMapper mapper, long[] backoffMs) {
        this(baseUrl, apiKey, "Authorization", "Bearer ", http, wsConnector, mapper, backoffMs);
    }

    /** Full constructor with a pluggable auth header — the seam {@link #ValemClient(String, String, String)} uses. */
    ValemClient(String baseUrl, String apiKey, String authHeaderName, String authHeaderPrefix,
                     HttpClient http, WsConnector wsConnector, ObjectMapper mapper, long[] backoffMs) {
        if (baseUrl == null || baseUrl.isBlank()) throw new IllegalArgumentException("baseUrl required");
        this.baseUrl         = baseUrl.replaceAll("/+$", "");
        this.apiKey          = (apiKey == null || apiKey.isBlank()) ? null : apiKey;
        this.authHeaderName  = authHeaderName;
        this.authHeaderValue = this.apiKey == null ? null : authHeaderPrefix + this.apiKey;
        this.http        = http;
        this.mapper      = mapper;
        this.backoffMs   = backoffMs.clone();
        this.wsConnector = wsConnector != null ? wsConnector
                : (uri, listener) -> this.http.newWebSocketBuilder().buildAsync(uri, listener);
    }

    // ── Models ────────────────────────────────────────────────────────────────

    public List<String> listModels() {
        return get("/models", new TypeReference<List<String>>() {});
    }

    public CreateModelResponse createModel(JsonNode spec) {
        return send("POST", "/models", spec, "application/json", CreateModelResponse.class);
    }

    public ModelInfo getModel(String id) {
        return get("/models/" + enc(id), ModelInfo.class);
    }

    public JsonNode getSpec(String id) {
        return get("/models/" + enc(id) + "/spec", JsonNode.class);
    }

    public void deleteModel(String id) {
        send("DELETE", "/models/" + enc(id), null, null, Void.class);
    }

    // ── State ─────────────────────────────────────────────────────────────────

    public JsonNode getState(String id) {
        return getState(id, null);
    }

    public JsonNode getState(String id, Instant at) {
        String q = at == null ? "" : "?at=" + enc(at.toString());
        return get("/models/" + enc(id) + "/state" + q, JsonNode.class);
    }

    public JsonNode getField(String id, String path) {
        return get("/models/" + enc(id) + "/state/" + enc(path), JsonNode.class);
    }

    public List<String> history(String id) {
        return get("/models/" + enc(id) + "/history", new TypeReference<List<String>>() {});
    }

    public JsonNode effectiveSchema(String id, String path) {
        return get("/models/" + enc(id) + "/schema/" + enc(path), JsonNode.class);
    }

    // ── Mutations ─────────────────────────────────────────────────────────────

    public MutationResponse mutate(String id, Map<String, ?> mutations) {
        return mutate(id, mutations, null);
    }

    public MutationResponse mutate(String id, Map<String, ?> mutations, String viewId) {
        JsonNode body = mapper.valueToTree(mutations);
        return send("POST", "/models/" + enc(id) + "/mutations", body, "application/json",
                MutationResponse.class, viewHeaders(viewId));
    }

    /** Apply an RFC 6902 JSON Patch (an array of op objects). */
    public MutationResponse patch(String id, JsonNode ops) {
        return patch(id, ops, null);
    }

    public MutationResponse patch(String id, JsonNode ops, String viewId) {
        return send("POST", "/models/" + enc(id) + "/mutations/patch", ops,
                "application/json-patch+json", MutationResponse.class, viewHeaders(viewId));
    }

    /** Convenience: set one field by canonical address, via a JSON Patch {@code add}. */
    public MutationResponse setField(String id, String address, JsonNode value) {
        var op = mapper.createObjectNode();
        op.put("op", "add");
        op.put("path", addressToPointer(address));
        op.set("value", value);
        return patch(id, mapper.createArrayNode().add(op), null);
    }

    // ── Explain / audit ─────────────────────────────────────────────────────────

    public List<DerivationTrace> explain(String id, String path) {
        return get("/models/" + enc(id) + "/explain/" + enc(path),
                new TypeReference<List<DerivationTrace>>() {});
    }

    public List<AuditRecord> audit(String id) {
        return audit(id, AuditQuery.all());
    }

    /** Durable, append-only audit trail (newest-first). */
    public List<AuditRecord> audit(String id, AuditQuery query) {
        StringBuilder q = new StringBuilder();
        if (query != null) {
            appendParam(q, "path",  query.pathPrefix());
            appendParam(q, "from",  query.from() == null ? null : query.from().toString());
            appendParam(q, "to",    query.to()   == null ? null : query.to().toString());
            appendParam(q, "limit", query.limit() == null ? null : query.limit().toString());
        }
        String path = "/models/" + enc(id) + "/audit" + (q.length() == 0 ? "" : "?" + q);
        return get(path, new TypeReference<List<AuditRecord>>() {});
    }

    /** Verify the tamper-evident hash chain of a model's audit trail. */
    public AuditVerification verifyAudit(String id) {
        return get("/models/" + enc(id) + "/audit/verify", AuditVerification.class);
    }

    // ── Snapshot / evolve / view ────────────────────────────────────────────────

    public JsonNode snapshot(String id) {
        return send("POST", "/models/" + enc(id) + "/snapshot", null, null, JsonNode.class);
    }

    public void restore(String id, JsonNode snapshot) {
        send("POST", "/models/" + enc(id) + "/restore", snapshot, "application/json", Void.class);
    }

    public JsonNode evolveSpec(String id, JsonNode evolution) {
        return send("POST", "/models/" + enc(id) + "/spec/evolve", evolution, "application/json", JsonNode.class);
    }

    public JsonNode getView(String id, String viewId) {
        String path = viewId == null
                ? "/models/" + enc(id) + "/view"
                : "/models/" + enc(id) + "/view/" + enc(viewId);
        return get(path, JsonNode.class);
    }

    // ── Blobs ─────────────────────────────────────────────────────────────────

    /**
     * Uploads a binary blob via multipart {@code POST /blobs} and returns the parsed {@code BlobRef}
     * JSON ({@code {"$blobId":..,"$mediaType":..,"$bytes":..}}).
     */
    public JsonNode uploadBlob(byte[] data, String mediaType) {
        String mt = (mediaType == null || mediaType.isBlank()) ? "application/octet-stream" : mediaType;
        String boundary = "----valem" + Long.toHexString(System.nanoTime());
        byte[] body = multipartBody(boundary, data, mt);

        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(baseUrl + "/blobs"));
        b.header("Content-Type", "multipart/form-data; boundary=" + boundary);
        if (authHeaderValue != null) b.header(authHeaderName, authHeaderValue);
        b.POST(HttpRequest.BodyPublishers.ofByteArray(body));

        HttpResponse<String> res;
        try {
            res = http.send(b.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            throw new ValemException("Request failed: POST /blobs", e);
        }
        if (res.statusCode() / 100 != 2) {
            throw new ValemException(res.statusCode(), res.body() == null ? "" : res.body());
        }
        return readValue(res.body(), JsonNode.class);
    }

    /** Streams a stored blob by id ({@code GET /blobs/{blobId}}) and returns its bytes. */
    public byte[] downloadBlob(String blobId) {
        return getBytes("/blobs/" + enc(blobId));
    }

    /**
     * Streams a blob only if it is referenced by the given model's current state
     * ({@code GET /models/{id}/blobs/{blobId}}); a 404 becomes a {@link ValemException}.
     */
    public byte[] getModelBlob(String modelId, String blobId) {
        return getBytes("/models/" + enc(modelId) + "/blobs/" + enc(blobId));
    }

    private byte[] getBytes(String path) {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(baseUrl + path));
        if (authHeaderValue != null) b.header(authHeaderName, authHeaderValue);
        b.GET();
        HttpResponse<byte[]> res;
        try {
            res = http.send(b.build(), HttpResponse.BodyHandlers.ofByteArray());
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            throw new ValemException("Request failed: GET " + path, e);
        }
        if (res.statusCode() / 100 != 2) {
            byte[] body = res.body();
            throw new ValemException(res.statusCode(),
                    body == null ? "" : new String(body, StandardCharsets.UTF_8));
        }
        return res.body();
    }

    /** Assembles a {@code multipart/form-data} body with a {@code mediaType} field and a {@code file} part. */
    private static byte[] multipartBody(String boundary, byte[] data, String mediaType) {
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        try {
            String preamble =
                    "--" + boundary + "\r\n"
                    + "Content-Disposition: form-data; name=\"mediaType\"\r\n\r\n"
                    + mediaType + "\r\n"
                    + "--" + boundary + "\r\n"
                    + "Content-Disposition: form-data; name=\"file\"; filename=\"blob\"\r\n"
                    + "Content-Type: " + mediaType + "\r\n\r\n";
            out.write(preamble.getBytes(StandardCharsets.UTF_8));
            out.write(data);
            out.write(("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new ValemException("Failed to assemble multipart body", e);
        }
        return out.toByteArray();
    }

    // ── WebSocket subscription ────────────────────────────────────────────────

    public Subscription subscribe(String id, ChangeListener listener) {
        return subscribe(id, listener, null);
    }

    /**
     * Opens a reconnecting WebSocket to {@code /models/{id}/subscribe}. Reconnects with exponential
     * backoff after an unexpected close/error until {@link Subscription#close()} is called. The API
     * key is sent as {@code ?token=}; {@code paths} applies a server-side prefix filter.
     */
    public Subscription subscribe(String id, ChangeListener listener, List<String> paths) {
        URI uri = buildSubscribeUri(id, paths);
        AtomicBoolean closed = new AtomicBoolean(false);
        AtomicInteger attempt = new AtomicInteger(0);
        AtomicReference<WebSocket> current = new AtomicReference<>();
        AtomicReference<ScheduledFuture<?>> pending = new AtomicReference<>();

        Runnable[] connectHolder = new Runnable[1];
        connectHolder[0] = () -> {
            if (closed.get()) return;
            WebSocket.Listener wsListener = new ReconnectingListener(
                    listener, closed, attempt, () -> scheduleReconnect(closed, attempt, pending, connectHolder[0]));
            wsConnector.connect(uri, wsListener).whenComplete((ws, err) -> {
                if (err != null) {
                    if (!closed.get()) {
                        listener.onError(err);
                        scheduleReconnect(closed, attempt, pending, connectHolder[0]);
                    }
                } else {
                    current.set(ws);
                    if (closed.get()) ws.abort();
                }
            });
        };
        connectHolder[0].run();

        return () -> {
            closed.set(true);
            ScheduledFuture<?> p = pending.getAndSet(null);
            if (p != null) p.cancel(false);
            WebSocket ws = current.getAndSet(null);
            if (ws != null) {
                try { ws.sendClose(WebSocket.NORMAL_CLOSURE, "client closed"); }
                catch (RuntimeException ignore) { ws.abort(); }
            }
        };
    }

    /** Exposed for testing/advanced use: the ws(s):// subscription URI. */
    URI buildSubscribeUri(String id, List<String> paths) {
        String wsBase = baseUrl.replaceFirst("^http", "ws");
        StringBuilder q = new StringBuilder();
        if (apiKey != null) appendParam(q, "token", apiKey);
        if (paths != null && !paths.isEmpty()) appendParam(q, "paths", String.join(",", paths));
        return URI.create(wsBase + "/models/" + enc(id) + "/subscribe" + (q.length() == 0 ? "" : "?" + q));
    }

    private void scheduleReconnect(AtomicBoolean closed, AtomicInteger attempt,
                                   AtomicReference<ScheduledFuture<?>> pending, Runnable connect) {
        if (closed.get()) return;
        int n = attempt.getAndIncrement();
        long delay = backoffMs[Math.min(n, backoffMs.length - 1)];
        pending.set(scheduler().schedule(connect, delay, TimeUnit.MILLISECONDS));
    }

    private ScheduledExecutorService scheduler() {
        ScheduledExecutorService s = reconnectScheduler;
        if (s == null) {
            synchronized (this) {
                if (reconnectScheduler == null) {
                    reconnectScheduler = Executors.newSingleThreadScheduledExecutor(r -> {
                        Thread t = new Thread(r, "valem-ws-reconnect");
                        t.setDaemon(true);
                        return t;
                    });
                }
                s = reconnectScheduler;
            }
        }
        return s;
    }

    /** The {@link WebSocket.Listener} that accumulates text frames and drives reconnect on close/error. */
    private final class ReconnectingListener implements WebSocket.Listener {
        private final ChangeListener listener;
        private final AtomicBoolean closed;
        private final AtomicInteger attempt;
        private final Runnable reconnect;
        private final StringBuilder buffer = new StringBuilder();

        ReconnectingListener(ChangeListener listener, AtomicBoolean closed,
                             AtomicInteger attempt, Runnable reconnect) {
            this.listener  = listener;
            this.closed    = closed;
            this.attempt   = attempt;
            this.reconnect = reconnect;
        }

        @Override public void onOpen(WebSocket webSocket) {
            attempt.set(0);
            try { listener.onOpen(); } catch (RuntimeException ignore) { /* isolate user callback */ }
            webSocket.request(1);
        }

        @Override public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
            buffer.append(data);
            if (last) {
                String frame = buffer.toString();
                buffer.setLength(0);
                try {
                    // The topic also carries discriminated non-mutation frames (kind:"spec-evolved",
                    // pushed after POST /models/{id}/spec/evolve). ChangeEvent models mutation frames
                    // only — anything else would deserialize with all-null lists, so skip it rather
                    // than hand the listener a half-empty event.
                    JsonNode node = mapper.readTree(frame);
                    if ("mutation".equals(node.path("kind").asText("mutation"))) {
                        listener.onEvent(mapper.treeToValue(node, ChangeEvent.class));
                    }
                } catch (Exception e) {
                    listener.onError(e);
                }
            }
            webSocket.request(1);
            return null;
        }

        @Override public CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason) {
            try { listener.onClose(); } catch (RuntimeException ignore) { /* isolate */ }
            if (!closed.get()) reconnect.run();
            return null;
        }

        @Override public void onError(WebSocket webSocket, Throwable error) {
            listener.onError(error);
            if (!closed.get()) reconnect.run();
        }
    }

    @Override
    public void close() {
        ScheduledExecutorService s = reconnectScheduler;
        if (s != null) s.shutdownNow();
    }

    // ── HTTP plumbing ─────────────────────────────────────────────────────────

    private <T> T get(String path, Class<T> type) {
        return send("GET", path, null, null, type);
    }

    private <T> T get(String path, TypeReference<T> type) {
        HttpResponse<String> res = execute("GET", path, null, null, Map.of());
        return readValue(res.body(), type);
    }

    private <T> T send(String method, String path, JsonNode body, String contentType, Class<T> type) {
        return send(method, path, body, contentType, type, Map.of());
    }

    private <T> T send(String method, String path, JsonNode body, String contentType,
                       Class<T> type, Map<String, String> extraHeaders) {
        HttpResponse<String> res = execute(method, path, body, contentType, extraHeaders);
        if (type == Void.class) return null;
        return readValue(res.body(), type);
    }

    private HttpResponse<String> execute(String method, String path, JsonNode body,
                                         String contentType, Map<String, String> extraHeaders) {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(baseUrl + path));
        HttpRequest.BodyPublisher publisher = body == null
                ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofString(toJson(body));
        b.method(method, publisher);
        if (body != null) b.header("Content-Type", contentType == null ? "application/json" : contentType);
        if (authHeaderValue != null) b.header(authHeaderName, authHeaderValue);
        extraHeaders.forEach(b::header);

        HttpResponse<String> res;
        try {
            res = http.send(b.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            throw new ValemException("Request failed: " + method + " " + path, e);
        }
        if (res.statusCode() / 100 != 2) {
            throw new ValemException(res.statusCode(), res.body() == null ? "" : res.body());
        }
        return res;
    }

    private Map<String, String> viewHeaders(String viewId) {
        return viewId == null ? Map.of() : Map.of("X-View", viewId);
    }

    private String toJson(JsonNode node) {
        try { return mapper.writeValueAsString(node); }
        catch (Exception e) { throw new ValemException("Failed to serialise request body", e); }
    }

    private <T> T readValue(String body, Class<T> type) {
        if (type == Void.class || body == null || body.isBlank()) return null;
        try { return mapper.readValue(body, type); }
        catch (Exception e) { throw new ValemException("Failed to parse response: " + e.getMessage(), e); }
    }

    private <T> T readValue(String body, TypeReference<T> type) {
        if (body == null || body.isBlank()) return null;
        try { return mapper.readValue(body, type); }
        catch (Exception e) { throw new ValemException("Failed to parse response: " + e.getMessage(), e); }
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private static void appendParam(StringBuilder q, String key, String value) {
        if (value == null || value.isEmpty()) return;
        if (q.length() > 0) q.append('&');
        q.append(key).append('=').append(enc(value));
    }

    private static String enc(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }

    /** Converts a canonical JSON Path address ("$.a.b" / "$.items[0].qty") to an RFC 6901 pointer. */
    static String addressToPointer(String address) {
        String a = address.startsWith("$.") ? address.substring(2)
                : address.startsWith("$") ? address.substring(1) : address;
        a = a.replaceAll("\\[(\\d+)\\]", ".$1"); // items[0] -> items.0
        if (a.isEmpty()) return "";
        StringBuilder out = new StringBuilder();
        for (String seg : a.split("\\.")) {
            if (seg.isEmpty()) continue;
            out.append('/').append(seg.replace("~", "~0").replace("/", "~1"));
        }
        return out.toString();
    }
}
