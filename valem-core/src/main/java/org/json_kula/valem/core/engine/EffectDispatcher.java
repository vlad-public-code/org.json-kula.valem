package org.json_kula.valem.core.engine;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.NullNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.json_kula.jsonata_jvm.JsonataBindings;
import org.json_kula.jsonata_jvm.JsonataEvaluationException;
import org.json_kula.jsonata_jvm.JsonataExpression;
import org.json_kula.valem.core.engine.spi.EffectEvalContext;
import org.json_kula.valem.core.engine.spi.EffectKind;
import org.json_kula.valem.core.engine.spi.EffectKindRegistry;
import org.json_kula.valem.core.graph.CompiledModel;
import org.json_kula.valem.core.model.EffectSpec;
import org.json_kula.valem.core.model.ModelCoordinate;
import org.json_kula.valem.core.model.TargetSpec;
import org.json_kula.valem.core.state.ModelState;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Evaluates effect triggers against the merged document and emits {@link EffectRequest} records for
 * every effect whose synthetic graph node is dirty, whose trigger is truthy, and whose edge / in-flight
 * guard permits firing. Performs <em>no I/O</em> — it only produces request data. The registered
 * {@link EffectSink} (in the api shell) executes each request and folds any response back as a
 * mutation.
 */
public final class EffectDispatcher {

    /** Sink that receives emitted effect requests after the mutation commits. */
    public interface EffectSink {
        void submit(EffectRequest request);
    }

    /**
     * Verdict for an in-flight effect's fold-back, re-evaluated against <em>current</em> state:
     * <ul>
     *   <li>{@code CURRENT} — the fired key still matches; apply the fold-back.</li>
     *   <li>{@code SUPERSEDED} — the input changed while in flight; discard the stale result and
     *       re-fire for the current value (trailing debounce).</li>
     *   <li>{@code CANCELLED} — the trigger no longer holds; discard (this is how a timer/effect is
     *       cancelled when its precondition changed before it landed).</li>
     * </ul>
     */
    public enum FoldbackDecision { CURRENT, SUPERSEDED, CANCELLED }

    /** Matches a {@code { expr }} interpolation segment inside a url/header template. */
    private static final Pattern INTERP = Pattern.compile("\\{([^}]*)\\}");

    private final ExpressionCache cache;

    public EffectDispatcher(ExpressionCache cache) {
        this.cache = cache;
    }

    /**
     * Emits requests for all effects whose synthetic node appears in {@code dirtyNodes} and whose
     * trigger fires. {@code context} is the caller-supplied merged document (shared across the
     * constraint/action/effect phases of one cycle).
     */
    public List<EffectRequest> evaluate(CompiledModel model, ModelState state,
                                        Set<String> dirtyNodes, ObjectNode context) {
        List<EffectRequest> result = new ArrayList<>();
        JsonataBindings bindings = EvalBindings.forModel(model);

        for (EffectSpec effect : model.spec().effects()) {
            String nodeKey = "$effect:" + effect.id();
            if (!dirtyNodes.contains(nodeKey)) continue;
            if (!evalBoolean(effect.trigger(), context, bindings)) continue;

            // Fan-out form: `requests` is a JSONata expression producing an array of already-resolved
            // request descriptors (one per data element). Each carries its own url / dedupeKey / set /
            // statusPath, so each is guarded independently.
            if (effect.requests() != null && !effect.requests().isBlank()) {
                JsonNode arr = evalNode(effect.requests(), context, bindings);
                // JSONata yields a bare object for a single element and an array for many.
                Iterable<JsonNode> descriptors = arr == null ? List.of()
                        : (arr.isArray() ? arr : List.of(arr));
                for (JsonNode descriptor : descriptors) {
                    if (!descriptor.isObject()) continue;
                    JsonNode dedupe = descriptor.get("dedupeKey");
                    String statusPath = descriptor.hasNonNull("statusPath")
                            ? descriptor.get("statusPath").asText() : effect.statusPath();
                    if (guarded(context, statusPath, dedupe)) continue;
                    result.add(buildFromDescriptor(effect, descriptor, statusPath, dedupe));
                }
                continue;
            }

            // Single-request form.
            JsonNode dedupe = effect.dedupeKey() != null
                    ? evalNode(effect.dedupeKey(), context, bindings) : null;
            if (guarded(context, effect.statusPath(), dedupe)) continue;
            result.add(build(effect, context, bindings, dedupe));
        }
        return List.copyOf(result);
    }

    /**
     * Re-drives server effects left {@code pending}/{@code in_flight} — e.g. after a crash/restart while
     * a request was mid-flight. Unlike {@link #evaluate}, this ignores the dirty set and the in-flight
     * guard (that is exactly the state we want to recover), but still requires the trigger to hold so we
     * do not re-fire an effect whose precondition no longer applies. At-least-once: idempotent remotes
     * should be guarded by an {@code Idempotency-Key} header.
     */
    public List<EffectRequest> reconcile(CompiledModel model, ModelState state, ObjectNode context) {
        List<EffectRequest> result = new ArrayList<>();
        JsonataBindings bindings = EvalBindings.forModel(model);

        for (EffectSpec effect : model.spec().effects()) {
            // Non-durable kinds (caller, and any plugin declaring durable()==false) have no
            // server-side in-flight state to recover.
            if (!EffectKindRegistry.get().durable(effect.executor())) continue;
            if (!evalBoolean(effect.trigger(), context, bindings)) continue;

            if (effect.requests() != null && !effect.requests().isBlank()) {
                JsonNode arr = evalNode(effect.requests(), context, bindings);
                Iterable<JsonNode> descriptors = arr == null ? List.of()
                        : (arr.isArray() ? arr : List.of(arr));
                for (JsonNode d : descriptors) {
                    if (!d.isObject()) continue;
                    String statusPath = d.hasNonNull("statusPath")
                            ? d.get("statusPath").asText() : effect.statusPath();
                    if (isStuck(context, statusPath)) {
                        result.add(buildFromDescriptor(effect, d, statusPath, d.get("dedupeKey")));
                    }
                }
                continue;
            }

            if (isStuck(context, effect.statusPath())) {
                JsonNode dedupe = effect.dedupeKey() != null
                        ? evalNode(effect.dedupeKey(), context, bindings) : null;
                result.add(build(effect, context, bindings, dedupe));
            }
        }
        return List.copyOf(result);
    }

    /**
     * Re-evaluates an in-flight effect against current state to decide whether its fold-back should be
     * applied. See {@link FoldbackDecision}. {@code firedKey} is the {@code dedupeKey} value the effect
     * was fired with (may be null when the effect has no dedupeKey — then it can never be SUPERSEDED).
     */
    public FoldbackDecision resolveFoldback(CompiledModel model, ModelState state, String effectId,
                                            JsonNode firedKey) {
        EffectSpec e = findEffect(model, effectId);
        if (e == null) return FoldbackDecision.CANCELLED;
        JsonataBindings bindings = EvalBindings.forModel(model);
        ObjectNode ctx = state.mergedDocument();

        if (!evalBoolean(e.trigger(), ctx, bindings)) return FoldbackDecision.CANCELLED;
        if (e.dedupeKey() != null && !e.dedupeKey().isBlank()) {
            JsonNode currentKey = evalNode(e.dedupeKey(), ctx, bindings);
            return keysEqual(firedKey, currentKey)
                    ? FoldbackDecision.CURRENT : FoldbackDecision.SUPERSEDED;
        }
        return FoldbackDecision.CURRENT;   // no dedupeKey → cannot detect supersession
    }

    /**
     * Builds a fresh request for {@code effectId} against current state, bypassing the dirty set and
     * the in-flight guard — used to re-fire a superseded effect for its now-current value. Returns null
     * if the effect is unknown, is caller-executed, is a fan-out effect (per-element keys), or its
     * trigger is no longer true.
     */
    public EffectRequest buildFor(CompiledModel model, ModelState state, String effectId) {
        EffectSpec e = findEffect(model, effectId);
        if (e == null || !EffectKindRegistry.get().durable(e.executor())) return null;
        if (e.requests() != null && !e.requests().isBlank()) return null;   // fan-out re-dispatch unsupported
        JsonataBindings bindings = EvalBindings.forModel(model);
        ObjectNode ctx = state.mergedDocument();
        if (!evalBoolean(e.trigger(), ctx, bindings)) return null;
        JsonNode dedupe = e.dedupeKey() != null && !e.dedupeKey().isBlank()
                ? evalNode(e.dedupeKey(), ctx, bindings) : null;
        return build(e, ctx, bindings, dedupe);
    }

    private static EffectSpec findEffect(CompiledModel model, String effectId) {
        for (EffectSpec e : model.spec().effects()) {
            if (e.id().equals(effectId)) return e;
        }
        return null;
    }

    private static boolean keysEqual(JsonNode a, JsonNode b) {
        boolean aNull = a == null || a.isNull() || a.isMissingNode();
        boolean bNull = b == null || b.isNull() || b.isMissingNode();
        if (aNull || bNull) return aNull && bNull;
        return a.equals(b);
    }

    private boolean isStuck(ObjectNode context, String statusPath) {
        ObjectNode io = readObjectAt(context, statusPath);
        if (io == null) return false;
        JsonNode phase = io.get("phase");
        return phase != null && phase.isTextual()
                && (phase.asText().equals("pending") || phase.asText().equals("in_flight"));
    }

    /**
     * Reads the edge / in-flight guard from a statusPath sub-document. Returns {@code true} when the
     * effect must NOT fire: it is already {@code pending}/{@code in_flight}, or the current dedupe key
     * equals the one already recorded as fired.
     */
    private boolean guarded(ObjectNode context, String statusPath, JsonNode dedupe) {
        ObjectNode io = readObjectAt(context, statusPath);
        if (io == null) return false;
        JsonNode phase = io.get("phase");
        if (phase != null && phase.isTextual()
                && (phase.asText().equals("pending") || phase.asText().equals("in_flight"))) {
            return true;
        }
        JsonNode storedKey = io.get("key");
        return dedupe != null && storedKey != null && dedupe.equals(storedKey);
    }

    /** Builds a server request from an already-evaluated fan-out descriptor object. */
    private EffectRequest.Server buildFromDescriptor(EffectSpec e, JsonNode d, String statusPath, JsonNode dedupe) {
        String method = d.hasNonNull("method") ? d.get("method").asText() : "GET";
        String url = d.hasNonNull("url") ? d.get("url").asText() : null;

        Map<String, String> headers = new LinkedHashMap<>();
        JsonNode h = d.get("headers");
        if (h != null && h.isObject()) {
            h.fields().forEachRemaining(en -> headers.put(en.getKey(), en.getValue().asText()));
        }
        Map<String, String> set = new LinkedHashMap<>();
        JsonNode s = d.get("set");
        if (s != null && s.isObject()) {
            s.fields().forEachRemaining(en -> set.put(en.getKey(), en.getValue().asText()));
        }
        JsonNode body = d.get("body");   // already-evaluated JSON in the fan-out form

        EffectSpec.PolicySpec p = e.policy();
        return EffectRequest.Server.http(
                e.id(), method, url, headers, body, set,
                p != null ? p.egressProfile() : null,
                p != null ? p.timeoutMs() : 5000,
                p != null ? p.retries() : 0,
                p != null ? p.backoff() : null,
                statusPath, dedupe);
    }

    // ── Request construction ────────────────────────────────────────────────────

    private EffectRequest build(EffectSpec e, ObjectNode ctx, JsonataBindings b, JsonNode dedupe) {
        switch (e.executor()) {
            case EffectKindRegistry.CALLER -> {
                ObjectNode payload = buildPayload(e.payload(), ctx, b);
                return new EffectRequest.Caller(e.id(), e.emit(), payload, e.statusPath(), dedupe);
            }
            case EffectKindRegistry.LLM -> {
                JsonNode promptNode = evalNode(e.prompt(), ctx, b);
                String prompt = promptNode == null ? null
                        : (promptNode.isValueNode() ? promptNode.asText() : promptNode.toString());
                EffectSpec.PolicySpec lp = e.policy();
                return new EffectRequest.Llm(e.id(), prompt, e.responseSchema(), e.responseSet(),
                        lp != null ? lp.model() : null,
                        lp != null ? lp.temperature() : null,
                        e.statusPath(), dedupe);
            }
            case EffectKindRegistry.TIMER -> {
                Long fireAt = null;
                Long delay = null;
                if (e.at() != null && !e.at().isBlank()) {
                    JsonNode v = evalNode(e.at(), ctx, b);
                    if (v != null && v.isNumber()) {
                        fireAt = v.asLong();
                    } else if (v != null && v.isTextual()) {
                        try { fireAt = java.time.Instant.parse(v.asText()).toEpochMilli(); }
                        catch (RuntimeException ignored) { /* leave null; shell records a failure */ }
                    }
                } else if (e.afterMs() != null && !e.afterMs().isBlank()) {
                    JsonNode v = evalNode(e.afterMs(), ctx, b);
                    if (v != null && v.isNumber()) delay = v.asLong();
                }
                return new EffectRequest.Timer(e.id(), fireAt, delay, e.responseSet(), e.statusPath(), dedupe);
            }
            case EffectKindRegistry.SERVER -> { /* fall through to server handling below */ }
            default -> {
                // Plugin-provided kind: resolve via the SPI into a generic Plugin carrier. The validator
                // rejects unknown/disabled kinds, so a null here means a mid-flight config change; skip.
                EffectKind kind = EffectKindRegistry.get().plugin(e.executor());
                return kind == null ? null : kind.resolve(e, evalContext(ctx, b), dedupe);
            }
        }

        EffectSpec.PolicySpec p = e.policy();
        int timeoutMs = p != null ? p.timeoutMs() : 5000;
        int retries   = p != null ? p.retries() : 0;
        String backoff = p != null ? p.backoff() : null;

        // Composition link form: a `target` block names another model instead of a URL. The core does
        // not resolve the coordinate — it parses its shape and hands the ref to the shell.
        if (e.target() != null && e.target().ref() != null && !e.target().ref().isBlank()) {
            TargetSpec t = e.target();
            ModelCoordinate ref = ModelCoordinate.parse(t.ref());
            if (t.isRead()) {
                return EffectRequest.Server.readLink(e.id(), ref, t.read(), t.watchOrFalse(),
                        e.responseSet(), timeoutMs, retries, backoff, e.statusPath(), dedupe, t.binding());
            }
            JsonNode targetBody = (e.body() != null && !e.body().isBlank())
                    ? evalNode(e.body(), ctx, b) : null;
            return EffectRequest.Server.writeLink(e.id(), ref, t.path(), targetBody,
                    e.responseSet(), timeoutMs, retries, backoff, e.statusPath(), dedupe, t.binding());
        }

        EffectSpec.RequestSpec req = e.request();
        String method = req != null && req.method() != null ? req.method() : "GET";
        String url = req != null ? interpolate(req.url(), ctx, b) : null;

        Map<String, String> headers = new LinkedHashMap<>();
        if (req != null) {
            for (Map.Entry<String, String> h : req.headers().entrySet()) {
                headers.put(h.getKey(), interpolate(h.getValue(), ctx, b));
            }
        }
        JsonNode body = req != null && req.body() != null ? evalNode(req.body(), ctx, b) : null;

        return EffectRequest.Server.http(
                e.id(), method, url, headers, body, e.responseSet(),
                p != null ? p.egressProfile() : null,
                timeoutMs, retries, backoff, e.statusPath(), dedupe);
    }

    /** Replaces every {@code { expr }} segment with the rendered value of that JSONata expression. */
    private String interpolate(String template, JsonNode ctx, JsonataBindings b) {
        if (template == null) return null;
        Matcher m = INTERP.matcher(template);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            JsonNode v = evalNode(m.group(1).trim(), ctx, b);
            String rendered = (v == null || v.isNull() || v.isMissingNode()) ? ""
                    : (v.isValueNode() ? v.asText() : v.toString());
            m.appendReplacement(sb, Matcher.quoteReplacement(rendered));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    private ObjectNode buildPayload(Map<String, String> exprs, JsonNode ctx, JsonataBindings b) {
        ObjectNode payload = JsonNodeFactory.instance.objectNode();
        for (Map.Entry<String, String> en : exprs.entrySet()) {
            JsonNode v = evalNode(en.getValue(), ctx, b);
            payload.set(en.getKey(), v != null ? v : NullNode.instance);
        }
        return payload;
    }

    /** Adapts this dispatcher's pure eval helpers to the {@link EffectEvalContext} SPI surface, bound to
     *  the current merged document and model bindings, for a plugin {@link EffectKind#resolve}. */
    private EffectEvalContext evalContext(ObjectNode ctx, JsonataBindings b) {
        return new EffectEvalContext() {
            @Override public ObjectNode context() { return ctx; }
            @Override public JsonNode eval(String expr) { return evalNode(expr, ctx, b); }
            @Override public boolean evalBoolean(String expr) { return EffectDispatcher.this.evalBoolean(expr, ctx, b); }
            @Override public String interpolate(String template) { return EffectDispatcher.this.interpolate(template, ctx, b); }
        };
    }

    // ── Expression helpers ──────────────────────────────────────────────────────

    private boolean evalBoolean(String expr, JsonNode ctx, JsonataBindings b) {
        JsonNode r = evalNode(expr, ctx, b);
        if (r == null || r.isNull() || r.isMissingNode()) return false;
        if (r.isBoolean()) return r.asBoolean();
        if (r.isNumber())  return r.asDouble() != 0;
        if (r.isTextual()) return !r.asText().isEmpty();
        return false;
    }

    private JsonNode evalNode(String expr, JsonNode ctx, JsonataBindings b) {
        if (expr == null) return null;
        try {
            JsonataExpression compiled = cache.get(expr);
            return compiled.evaluate(ctx != null ? ctx : NullNode.instance, b);
        } catch (JsonataEvaluationException ex) {
            return null;
        }
    }

    /**
     * Navigates a plain object address (dot-segments, optional {@code [n]} indices, no wildcards) to
     * an {@link ObjectNode}, or {@code null} if any segment is absent or the target is not an object.
     * Used to read the {@code statusPath} sub-document for the edge / in-flight guard.
     */
    private static ObjectNode readObjectAt(JsonNode root, String address) {
        if (root == null || address == null || address.isBlank()) return null;
        String a = address.startsWith("$.") ? address.substring(2) : address;
        if (a.isEmpty() || a.equals("$")) return root.isObject() ? (ObjectNode) root : null;

        JsonNode cur = root;
        for (String seg : a.split("\\.")) {
            String name = seg;
            Integer idx = null;
            int lb = seg.indexOf('[');
            if (lb >= 0 && seg.endsWith("]")) {
                name = seg.substring(0, lb);
                try {
                    idx = Integer.parseInt(seg.substring(lb + 1, seg.length() - 1));
                } catch (NumberFormatException ex) {
                    return null;
                }
            }
            if (cur == null) return null;
            cur = cur.get(name);
            if (idx != null && cur != null) cur = cur.get(idx);
            if (cur == null) return null;
        }
        return cur.isObject() ? (ObjectNode) cur : null;
    }
}
