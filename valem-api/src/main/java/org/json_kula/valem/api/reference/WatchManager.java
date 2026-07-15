package org.json_kula.valem.api.reference;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.TextNode;
import jakarta.annotation.PreDestroy;
import org.json_kula.jsonata_jvm.JsonataBindings;
import org.json_kula.valem.core.engine.EffectRequest;
import org.json_kula.valem.core.engine.ExpressionCache;
import org.json_kula.valem.service.ModelService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Manages standing <b>watch</b> subscriptions for {@code watch: true} read-links (composition
 * architecture §4.2). A watch keeps the source live w.r.t. the target: each change to the target's
 * watched path folds a fresh value into the source (the spreadsheet cross-sheet reference).
 *
 * <p>M6 ships the {@code local} (in-process) watch: it registers a {@link ModelService.ChangeListener}
 * and, when the target commits a change touching the watched path, re-reads the value and folds it into
 * the source — <b>off the target's lock</b>, on a virtual thread (single-lock invariant). Watches are
 * torn down when the source or target is deleted; they are not persisted (re-established from the spec
 * on reload).
 */
public class WatchManager {

    private static final Logger log = LoggerFactory.getLogger(WatchManager.class);

    private final ModelService service;
    private final ObjectMapper mapper = new ObjectMapper();
    private final ExpressionCache expressions = new ExpressionCache();
    private final ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor();

    // key "sourceId effectId" -> the live subscription
    private final Map<String, Subscription> watches = new ConcurrentHashMap<>();

    public WatchManager(ModelService service) {
        this.service = service;
    }

    private record Subscription(String sourceId, String effectId, String targetId, String readPath,
                                Map<String, String> responseSet, String statusPath, AutoCloseable handle) {}

    /** Establishes (idempotently) the watch described by a read-link {@code Server} request. */
    public void ensureWatch(String sourceId, EffectRequest.Server s) {
        if (!s.isReadLink() || !s.watch() || s.targetRef() == null) return;
        String key = sourceId + " " + s.effectId();
        if (watches.containsKey(key)) return;

        String targetId = s.targetRef().identity();
        String readPath = s.targetRead();
        Subscription placeholder = new Subscription(sourceId, s.effectId(), targetId, readPath,
                s.responseSet(), s.statusPath(), null);

        AutoCloseable handle = service.addChangeListener((changedId, result) -> {
            if (!changedId.equals(targetId)) return;
            if (!touchesPath(result, readPath)) return;
            pool.submit(() -> fold(placeholder));
        });
        watches.put(key, new Subscription(sourceId, s.effectId(), targetId, readPath,
                s.responseSet(), s.statusPath(), handle));
    }

    /** Reads the target's current watched value and folds it into the source via {@code response.set}. */
    private void fold(Subscription w) {
        try {
            JsonNode value = service.getFieldValue(w.targetId(), w.readPath());
            if (value == null) value = mapper.nullNode();
            JsonataBindings bindings = new JsonataBindings().bindValue("response", value);

            Map<String, JsonNode> writes = new LinkedHashMap<>();
            for (Map.Entry<String, String> e : w.responseSet().entrySet()) {
                JsonNode v = expressions.get(e.getValue()).evaluate(mapper.nullNode(), bindings);
                writes.put(e.getKey(), v != null ? v : mapper.nullNode());
            }
            if (w.statusPath() != null && !w.statusPath().isBlank()) {
                writes.put(w.statusPath() + ".phase", TextNode.valueOf("applied"));
                writes.put(w.statusPath() + ".at", TextNode.valueOf(Instant.now().toString()));
            }
            if (!writes.isEmpty()) service.mutate(w.sourceId(), writes);
        } catch (Exception e) {
            log.warn("watch fold {}->{} failed: {}", w.targetId(), w.sourceId(), e.toString());
        }
    }

    /** Tears down every watch whose source or target is {@code modelId} (called on model delete). */
    public void teardownForModel(String modelId) {
        watches.entrySet().removeIf(entry -> {
            Subscription w = entry.getValue();
            if (w.sourceId().equals(modelId) || w.targetId().equals(modelId)) {
                close(w.handle());
                return true;
            }
            return false;
        });
    }

    /** Whether a committed change touched (a prefix of) the watched path. */
    private static boolean touchesPath(org.json_kula.valem.core.engine.ModelRuntime.MutationResult r,
                                       String readPath) {
        String needle = readPath.startsWith("$.") ? readPath.substring(2) : readPath;
        for (String p : concat(r.mutatedPaths(), r.derivedUpdated())) {
            String hay = p.startsWith("$.") ? p.substring(2) : p;
            if (hay.startsWith(needle) || needle.startsWith(hay)) return true;
        }
        return false;
    }

    private static Iterable<String> concat(java.util.List<String> a, java.util.List<String> b) {
        java.util.List<String> out = new java.util.ArrayList<>(a);
        out.addAll(b);
        return out;
    }

    private static void close(AutoCloseable h) {
        if (h != null) try { h.close(); } catch (Exception ignored) { /* best effort */ }
    }

    @PreDestroy
    public void shutdown() {
        watches.values().forEach(w -> close(w.handle()));
        watches.clear();
        pool.shutdownNow();
    }
}
