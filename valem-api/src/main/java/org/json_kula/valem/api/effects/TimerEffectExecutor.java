package org.json_kula.valem.api.effects;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.annotation.PreDestroy;
import org.json_kula.jsonata_jvm.JsonataBindings;
import org.json_kula.valem.api.websocket.ModelSubscriptionListener;
import org.json_kula.valem.core.engine.EffectRequest;
import org.json_kula.valem.service.ModelService;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * Shell for {@code executor: timer} effects: schedules the {@code response.set} fold-back at a future
 * time. The clock lives here, never in the pure core — the request carries an absolute
 * {@code fireAtEpochMillis} (from {@code at}) or a relative {@code delayMillis} (from {@code afterMs}).
 * At fire time the {@code response.set} expressions are evaluated against the <em>current</em> model
 * state (with {@code $now} bound) and folded back, driving the {@code statusPath} state machine.
 *
 * <p>Scheduling marks the effect {@code in_flight}, so the dispatcher's guard does not re-arm it while
 * pending. Cancellation on a state change is not yet supported (the timer fires regardless).
 *
 * <p><b>Subscriber-lifecycle pause/resume.</b> Implements {@link ModelSubscriptionListener}: when a
 * model's last WebSocket subscriber disconnects the model is marked <em>paused</em> — its not-yet-fired
 * scheduled fires are cancelled and any subsequent re-arm is skipped (the effect's {@code statusPath}
 * is left {@code in_flight}) — so an unwatched model stops driving external I/O (and, transitively, WS
 * broadcasts) for no observer. When a client (re)connects the pause is lifted and
 * {@link ModelService#reconcileEffects} re-drives any effect still {@code in_flight}, which naturally
 * re-arms the timer exactly like crash recovery re-drives a cold-loaded model. A model that has never
 * been WS-watched is not paused, so headless (REST-only) use keeps its timers running.
 */
public class TimerEffectExecutor extends EffectShell implements ModelSubscriptionListener {

    private final ScheduledExecutorService scheduler;

    // modelId -> not-yet-fired scheduled tasks, so a last-unsubscribe can cancel them without touching
    // the effect's statusPath (an in_flight effect looks "stuck" to reconcileEffects() and re-arms).
    // Each task removes its own future from this set when it fires, so a watched model does not leak
    // completed futures.
    private final ConcurrentHashMap<String, Set<ScheduledFuture<?>>> pendingByModel = new ConcurrentHashMap<>();

    // Models whose last subscriber has left: submit() must not schedule a fire nobody would observe.
    private final Set<String> pausedModels = ConcurrentHashMap.newKeySet();

    public TimerEffectExecutor(ModelService service, EffectMetrics metrics) {
        super(service, metrics);
        this.scheduler = Executors.newScheduledThreadPool(2, r -> {
            Thread t = new Thread(r, "effect-timer");
            t.setDaemon(true);
            return t;
        });
    }

    public void submit(String modelId, EffectRequest.Timer t) {
        // Delay is pure (state-derived); all mutation work happens off the sink thread so the fold-back
        // never runs synchronously/re-entrantly inside the fire cycle.
        if (t.fireAtEpochMillis() == null && t.delayMillis() == null) {
            scheduler.execute(() -> {
                long start = startTimer();
                setPhase(modelId, t.statusPath(), t.dedupeKey(), "failed", "timer has no 'at' or 'afterMs'");
                recordFailure("timer", start);
            });
            return;
        }
        long delay = t.fireAtEpochMillis() != null
                ? t.fireAtEpochMillis() - System.currentTimeMillis()
                : t.delayMillis();
        delay = Math.max(0, delay);

        // Mark in_flight (scheduled) so the guard does not re-arm this timer on later mutations.
        scheduler.execute(() -> setPhase(modelId, t.statusPath(), t.dedupeKey(), "in_flight", null));

        // Paused (no subscriber): leave the effect in_flight so a reconnect's reconcile re-arms it,
        // but do not schedule a fire nobody would observe.
        if (pausedModels.contains(modelId)) return;

        // Each fire removes its own future so a long-watched model does not accumulate completed ones.
        ScheduledFuture<?>[] holder = new ScheduledFuture<?>[1];
        Runnable task = () -> {
            try {
                fire(modelId, t);
            } finally {
                Set<ScheduledFuture<?>> s = pendingByModel.get(modelId);
                if (s != null && holder[0] != null) s.remove(holder[0]);
            }
        };
        Set<ScheduledFuture<?>> pending =
                pendingByModel.computeIfAbsent(modelId, k -> ConcurrentHashMap.newKeySet());
        ScheduledFuture<?> future = scheduler.schedule(task, delay, TimeUnit.MILLISECONDS);
        holder[0] = future;
        pending.add(future);
        // Close two races: the task may already have run (delay 0) before holder/set were assigned, and
        // the last subscriber may have left between the pause check above and scheduling.
        if (future.isDone() || pausedModels.contains(modelId)) {
            future.cancel(false);
            pending.remove(future);
        }
    }

    private void fire(String modelId, EffectRequest.Timer t) {
        // Times the fire-time fold-back work only (not the scheduled wait, which is state-derived).
        long start = startTimer();
        try {
            ObjectNode state = service.getState(modelId, null);   // fire-time state
            JsonataBindings bindings = new JsonataBindings()
                    .bindValue("now", mapper.getNodeFactory().numberNode(System.currentTimeMillis()));
            Map<String, JsonNode> values = evalResponseSet(t.responseSet(), state, bindings);
            // Keyed CAS: if the ticket was closed/changed while the timer was pending, this cancels.
            applyFoldback(modelId, t.effectId(), t.statusPath(), t.dedupeKey(), values);
            recordSuccess("timer", start);
        } catch (Exception e) {
            log.warn("timer effect '{}' on model '{}' failed: {}", t.effectId(), modelId, e.toString());
            setPhase(modelId, t.statusPath(), t.dedupeKey(), "failed", e.getMessage());
            recordFailure("timer", start);
        }
    }

    @Override
    public void onLastUnsubscribe(String modelId) {
        // Mark paused first so an in-flight re-arm (a concurrent submit) skips scheduling, then cancel
        // whatever is already pending.
        pausedModels.add(modelId);
        Set<ScheduledFuture<?>> pending = pendingByModel.remove(modelId);
        if (pending == null || pending.isEmpty()) return;
        int cancelled = 0;
        for (ScheduledFuture<?> f : pending) {
            if (f.cancel(false)) cancelled++;
        }
        if (cancelled > 0) {
            log.debug("cancelled {} pending timer(s) for model '{}' (no subscribers)", cancelled, modelId);
        }
    }

    @Override
    public void onFirstSubscriber(String modelId) {
        pausedModels.remove(modelId);
        // Re-drive off the WS handshake thread: reconcileEffects acquires the model lock, which a
        // long-running mutation may hold, and blocking WS connection establishment on it must be avoided.
        scheduler.execute(() -> {
            int redriven = service.reconcileEffects(modelId);
            if (redriven > 0) {
                log.debug("re-armed {} effect(s) for model '{}' on reconnect", redriven, modelId);
            }
        });
    }

    @PreDestroy
    public void shutdown() {
        scheduler.shutdownNow();
    }
}
