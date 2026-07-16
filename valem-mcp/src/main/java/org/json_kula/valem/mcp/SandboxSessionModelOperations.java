package org.json_kula.valem.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.json_kula.valem.cli.RemoteModelOperations;
import org.json_kula.valem.cli.RemoteOperationException;
import org.json_kula.valem.client.ValemClient;
import org.json_kula.valem.core.engine.DerivationTrace;
import org.json_kula.valem.core.graph.SpecEvolution;
import org.json_kula.valem.core.model.BlobRef;
import org.json_kula.valem.core.model.ModelSpec;
import org.json_kula.valem.core.state.Snapshot;
import org.json_kula.valem.service.ModelInfo;
import org.json_kula.valem.service.ModelOperations;
import org.json_kula.valem.service.ModelService;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * The {@code remote_with_browser} mode's {@link ModelOperations} facade: pairs with a hosted
 * sandbox's browser session over the device-flow handshake, then routes every
 * live-registry operation to that <b>shared session</b> over REST with {@code X-Session-Token} auth —
 * the MCP is, from the server's point of view, just another sandbox client.
 *
 * <h2>Id namespacing</h2>
 * <p>The sandbox scopes every model id to the session's namespace ({@code <namespaceId>__<userModelId>}
 * — see the sandbox's {@code SandboxModelId}). This facade keeps that transparent to the agent exactly
 * as embedded/plain-remote mode are: {@link #createModel} sends the agent's plain id as-is (the server
 * namespaces it), and every other id-taking method translates the agent's plain id to the namespaced
 * form before the call ({@link #toServer}) and translates any id the server hands back to the plain
 * form the agent used ({@link #fromServer}) — so the agent never has to think about namespacing, and
 * {@code get_state}/{@code explain}/{@code evolve_spec}/etc. all "just work" with the id
 * {@code create_model} was given, mirroring the sandbox browser's own {@code SessionService}.
 *
 * <h2>Before pairing</h2>
 * <p>Every model operation throws {@link IllegalStateException} until {@link #pairBrowser()} completes
 * successfully — there is no session to operate on yet. The pure authoring tools
 * ({@code validate_spec}, {@code eval_expression}, {@code test_spec}, {@code dry_run}) never reach this
 * class in any mode (see {@code ToolRegistry}), so they work before, during, and after pairing.
 */
final class SandboxSessionModelOperations implements ModelOperations, BrowserPairable {

    /** How long one {@code pair_browser} call blocks waiting for the developer to approve. */
    private static final Duration POLL_BUDGET = Duration.ofSeconds(60);

    private final String baseUrl;
    private final ObjectMapper mapper;
    private final HttpClient http = HttpClient.newHttpClient();

    // null until paired; a volatile single-assignment publish is enough (set once, read many).
    private volatile RemoteModelOperations delegate;
    private volatile String namespaceId;
    private volatile PendingPairing pending;

    private record PendingPairing(String pairCode, String deviceSecret, String userCode,
                                  String verificationUri, Instant expiresAt, int intervalSec) {}

    // Exactly three outcomes — the factories keep an impossible paired-and-failed state
    // unconstructible (mirrors PairResult's style).
    private record PollOutcome(boolean paired, boolean failed,
                               String sessionToken, String namespaceId, String error) {
        static PollOutcome paired(String sessionToken, String namespaceId) {
            return new PollOutcome(true, false, sessionToken, namespaceId, null);
        }
        static PollOutcome pending() {
            return new PollOutcome(false, false, null, null, "authorization_pending");
        }
        static PollOutcome failed(String error) {
            return new PollOutcome(false, true, null, null, error);
        }
    }

    private final Duration pollBudget;

    SandboxSessionModelOperations(String baseUrl, ObjectMapper mapper) {
        this(baseUrl, mapper, POLL_BUDGET);
    }

    /** Test-only seam: a shorter poll budget so pairing tests don't have to wait a full minute. */
    SandboxSessionModelOperations(String baseUrl, ObjectMapper mapper, Duration pollBudget) {
        this.baseUrl = baseUrl.replaceAll("/+$", "");
        this.mapper  = mapper;
        this.pollBudget = pollBudget;
    }

    // ── Pairing ───────────────────────────────────────────────────────────────

    @Override
    public synchronized PairResult pairBrowser() {
        if (delegate != null) return PairResult.alreadyPaired(namespaceId);

        PendingPairing p = pending;
        if (p == null || Instant.now().isAfter(p.expiresAt())) {
            p = mint();
            pending = p;
        }

        Instant deadline = minInstant(Instant.now().plus(pollBudget), p.expiresAt());
        while (true) {
            // Poll first, sleep after: on the documented "call again once approved" resume path the
            // approval has usually already happened, and this call blocks the single-threaded MCP
            // loop — an up-front interval would burn it for nothing (RFC 8628's interval spaces
            // consecutive polls, it is not a pre-delay).
            PollOutcome outcome = poll(p.pairCode(), p.deviceSecret());
            if (outcome.paired()) {
                ValemClient client = new ValemClient(baseUrl, outcome.sessionToken(), "X-Session-Token");
                this.delegate = new RemoteModelOperations(client, mapper);
                this.namespaceId = outcome.namespaceId();
                this.pending = null;
                return PairResult.paired(namespaceId);
            }
            if (outcome.failed()) {
                this.pending = null;
                throw new IllegalStateException(
                        "Pairing failed (" + outcome.error() + ") — call pair_browser again for a fresh link.");
            }
            // authorization_pending — keep polling until the local budget or the pairing's TTL lapses
            if (!Instant.now().isBefore(deadline)) break;
            sleep(Math.max(1, p.intervalSec()) * 1000L);
        }
        long remaining = Math.max(0, Duration.between(Instant.now(), p.expiresAt()).toSeconds());
        return PairResult.pending(p.verificationUri(), p.userCode(), remaining);
    }

    private PendingPairing mint() {
        JsonNode resp = postJson("/sandbox/pair", null);
        return new PendingPairing(
                text(resp, "pairCode"), text(resp, "deviceSecret"), text(resp, "userCode"),
                text(resp, "verificationUri"),
                Instant.now().plusSeconds(resp.path("expiresInSec").asLong(600)),
                resp.path("intervalSec").asInt(3));
    }

    private PollOutcome poll(String pairCode, String deviceSecret) {
        ObjectNode body = mapper.createObjectNode();
        body.put("pairCode", pairCode);
        body.put("deviceSecret", deviceSecret);
        HttpResponse<String> res = rawPost("/sandbox/pair/token", body);
        if (res.statusCode() == 200) {
            JsonNode json = readTree(res.body());
            return PollOutcome.paired(text(json, "sessionToken"), text(json, "namespaceId"));
        }
        if (res.statusCode() == 409) {
            return PollOutcome.pending();
        }
        String error = "pairing_failed";
        try { error = readTree(res.body()).path("error").asText(error); } catch (RuntimeException ignored) { /* keep default */ }
        return PollOutcome.failed(error);
    }

    // ── ModelOperations: id-translating delegation ───────────────────────────

    @Override
    public void createModel(ModelSpec spec) {
        requireDelegate().createModel(spec); // server namespaces the plain id from the body itself
    }

    @Override
    public ModelService.MutationOutcome mutate(String id, Map<String, JsonNode> mutations) {
        return requireDelegate().mutate(toServer(id), mutations);
    }

    @Override
    public ModelService.MutationOutcome patchMutate(String id, JsonNode patchDoc) {
        return requireDelegate().patchMutate(toServer(id), patchDoc);
    }

    @Override
    public List<String> listModels() {
        List<String> all = requireDelegate().listModels();
        String ns = namespaceId;
        if (ns == null) return all;
        String prefix = ns + "__";
        // Scope the agent's view to this paired session's own models, mirroring the browser's library
        // (the server's list endpoint is not itself session-scoped).
        return all.stream().filter(i -> i.startsWith(prefix)).map(this::fromServer).toList();
    }

    @Override
    public ModelSpec getSpec(String id) {
        return withTranslatedId(requireDelegate().getSpec(toServer(id)));
    }

    @Override
    public ModelInfo getInfo(String id) {
        ModelInfo info = requireDelegate().getInfo(toServer(id));
        return new ModelInfo(fromServer(info.id()), info.version(), info.derivationCount(),
                info.metaDerivationCount(), info.constraintCount(), info.effectCount());
    }

    @Override
    public com.fasterxml.jackson.databind.node.ObjectNode getState(String id, Instant at) {
        return requireDelegate().getState(toServer(id), at);
    }

    @Override
    public JsonNode getFieldValue(String id, String path) {
        return requireDelegate().getFieldValue(toServer(id), path);
    }

    @Override
    public List<String> getHistory(String id) {
        return requireDelegate().getHistory(toServer(id));
    }

    @Override
    public com.fasterxml.jackson.databind.node.ObjectNode getEffectiveSchema(String id, String path) {
        return requireDelegate().getEffectiveSchema(toServer(id), path);
    }

    @Override
    public List<DerivationTrace> explain(String id, String path) {
        return requireDelegate().explain(toServer(id), path);
    }

    @Override
    public Snapshot snapshot(String id) {
        Snapshot s = requireDelegate().snapshot(toServer(id));
        return new Snapshot(fromServer(s.modelId()), s.modelVersion(), s.baseDoc(), s.derivedCache(), s.metaCache());
    }

    @Override
    public void restore(String id, Snapshot snapshot) {
        Snapshot translated = new Snapshot(toServer(snapshot.modelId()), snapshot.modelVersion(),
                snapshot.baseDoc(), snapshot.derivedCache(), snapshot.metaCache());
        requireDelegate().restore(toServer(id), translated);
    }

    @Override
    public ModelSpec evolveSpec(String id, SpecEvolution evolution) {
        return withTranslatedId(requireDelegate().evolveSpec(toServer(id), evolution));
    }

    @Override
    public JsonNode getView(String id, String viewId) {
        return requireDelegate().getView(toServer(id), viewId);
    }

    @Override
    public void deleteModel(String id) {
        requireDelegate().deleteModel(toServer(id));
    }

    @Override
    public BlobRef uploadBlob(InputStream data, String mediaType) throws IOException {
        return requireDelegate().uploadBlob(data, mediaType);
    }

    @Override
    public InputStream downloadBlob(String blobId) throws IOException {
        return requireDelegate().downloadBlob(blobId);
    }

    @Override
    public InputStream getBlobForModel(String modelId, String blobId) throws IOException {
        return requireDelegate().getBlobForModel(toServer(modelId), blobId);
    }

    // ── Id namespacing helpers ────────────────────────────────────────────────

    private RemoteModelOperations requireDelegate() {
        RemoteModelOperations d = delegate;
        if (d == null) {
            throw new IllegalStateException(
                    "Not paired with a browser session yet — call pair_browser and have the developer "
                    + "open the link and approve it first.");
        }
        return d;
    }

    private String toServer(String id) {
        String ns = namespaceId;
        if (ns == null || id == null) return id;
        String prefix = ns + "__";
        return id.startsWith(prefix) ? id : prefix + id;
    }

    private String fromServer(String id) {
        String ns = namespaceId;
        if (ns == null || id == null) return id;
        String prefix = ns + "__";
        return id.startsWith(prefix) ? id.substring(prefix.length()) : id;
    }

    private ModelSpec withTranslatedId(ModelSpec spec) {
        return new ModelSpec(fromServer(spec.id()), spec.version(), spec.schema(), spec.derivations(),
                spec.metaDerivations(), spec.constraints(), spec.tests(), spec.defaultValues(),
                spec.constants(), spec.viewDefinition(), spec.effects(), spec.template(), spec.lineage());
    }

    // ── Raw HTTP for the pre-session pairing endpoints (no ValemClient yet) ──

    private HttpResponse<String> rawPost(String path, JsonNode body) {
        try {
            HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(baseUrl + path))
                    .header("Content-Type", "application/json")
                    .POST(body == null ? HttpRequest.BodyPublishers.noBody()
                            : HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body)));
            return http.send(b.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new RemoteOperationException(0, "Pairing request failed: " + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RemoteOperationException(0, "Pairing request interrupted");
        }
    }

    private JsonNode postJson(String path, JsonNode body) {
        HttpResponse<String> res = rawPost(path, body);
        if (res.statusCode() / 100 != 2) throw new RemoteOperationException(res.statusCode(), res.body());
        return readTree(res.body());
    }

    private JsonNode readTree(String s) {
        try {
            return mapper.readTree(s);
        } catch (Exception e) {
            throw new RemoteOperationException(0, "Malformed pairing response: " + e.getMessage());
        }
    }

    private static String text(JsonNode node, String field) {
        JsonNode v = node.get(field);
        return v == null ? null : v.asText();
    }

    private static Instant minInstant(Instant a, Instant b) {
        return a.isBefore(b) ? a : b;
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
