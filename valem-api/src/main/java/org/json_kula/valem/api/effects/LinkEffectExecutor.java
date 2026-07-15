package org.json_kula.valem.api.effects;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.annotation.PreDestroy;
import org.json_kula.jsonata_jvm.JsonataBindings;
import org.json_kula.valem.api.reference.ModelLink;
import org.json_kula.valem.api.reference.ModelResolver;
import org.json_kula.valem.api.reference.WatchManager;
import org.json_kula.valem.core.engine.EffectRequest;
import org.json_kula.valem.service.ModelService;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Shell for composition <b>links</b> — a {@code server} effect whose {@code target} names another
 * model (composition architecture §4). Resolves the coordinate through the {@link ModelResolver},
 * then:
 * <ul>
 *   <li><b>write-link</b> — {@link ModelLink#mutate} the target at {@code targetPath} with
 *       {@code targetBody}, bind its reply as {@code $response}, and fold {@code response.set} back.</li>
 *   <li><b>read-link</b> — {@link ModelLink#getField} at {@code targetRead} (no target mutation), bind
 *       the value as {@code $response}, and fold back.</li>
 * </ul>
 *
 * <p>Runs asynchronously on a virtual thread from the post-commit sink, so the source model's lock is
 * already released when the link fires — each hop acquires exactly one model lock, upholding the
 * deadlock-avoidance invariant (§8.1). Fold-back reuses the inherited keyed CAS via
 * {@link EffectShell#applyFoldback}.
 */
public class LinkEffectExecutor extends EffectShell {

    private final ModelResolver resolver;
    private final WatchManager watchManager;
    private final ExecutorService pool;

    public LinkEffectExecutor(ModelService service, ModelResolver resolver, EffectMetrics metrics) {
        this(service, resolver, metrics, null);
    }

    public LinkEffectExecutor(ModelService service, ModelResolver resolver, EffectMetrics metrics,
                              WatchManager watchManager) {
        super(service, metrics);
        this.resolver = resolver;
        this.watchManager = watchManager;
        this.pool = Executors.newVirtualThreadPerTaskExecutor();
    }

    public void submit(String modelId, EffectRequest.Server s) {
        pool.submit(() -> run(modelId, s));
    }

    private void run(String modelId, EffectRequest.Server s) {
        long start = startTimer();
        try {
            setPhase(modelId, s.statusPath(), s.dedupeKey(), "in_flight", null);

            Optional<ModelLink> link = resolver.resolveLink(s.targetRef());
            if (link.isEmpty()) {
                setPhase(modelId, s.statusPath(), s.dedupeKey(), "failed", "target_unresolved");
                recordFailure("link", start);
                return;
            }
            ModelLink target = link.get();

            JsonNode response;
            if (s.isReadLink()) {
                response = target.getField(s.targetRead());
                if (response == null) response = mapper.nullNode();
                // A watch read-link opens a standing subscription after the initial (snapshot) fold, so
                // the source stays live w.r.t. the target (composition §4.2 snapshot-then-stream).
                if (s.watch() && watchManager != null) watchManager.ensureWatch(modelId, s);
            } else {
                Map<String, JsonNode> writes = new LinkedHashMap<>();
                writes.put(s.targetPath(), s.targetBody() != null ? s.targetBody() : mapper.nullNode());
                ModelLink.MutationReply reply = target.mutate(writes);
                response = reply.result() != null ? reply.result() : mapper.nullNode();
            }

            JsonataBindings bindings = new JsonataBindings().bindValue("response", response);
            Map<String, JsonNode> values = evalResponseSet(s.responseSet(), mapper.nullNode(), bindings);
            applyFoldback(modelId, s.effectId(), s.statusPath(), s.dedupeKey(), values);
            recordSuccess("link", start);

        } catch (Exception e) {
            String code = errorCode(e);
            log.warn("link effect '{}' on model '{}' failed [{}]: {}", s.effectId(), modelId, code, e.toString());
            setPhase(modelId, s.statusPath(), s.dedupeKey(), "failed", code + ": " + e.getMessage());
            recordFailure("link", start);
        }
    }

    /**
     * Maps a target-side failure to the composition error taxonomy (composition architecture §4.3): a
     * constraint rollback / schema violation is {@code target_rejected} (non-retryable); a full
     * mutation queue is {@code target_busy} (retryable); a vanished target is {@code target_unresolved};
     * anything else is a {@code transport_error}.
     */
    private static String errorCode(Throwable e) {
        return switch (e) {
            case org.json_kula.valem.core.engine.ConstraintEvaluator.ConstraintViolationException c -> "target_rejected";
            case org.json_kula.valem.core.engine.SchemaViolationException s -> "target_rejected";
            case org.json_kula.valem.service.MutationQueueFullException q -> "target_busy";
            case org.json_kula.valem.service.ModelNotFoundException n -> "target_unresolved";
            default -> "transport_error";
        };
    }

    @PreDestroy
    public void shutdown() {
        pool.shutdownNow();
    }
}
