package org.json_kula.valem.api.effects;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.TextNode;
import org.json_kula.valem.api.authz.EffectApprovalRegistry;
import org.json_kula.valem.core.engine.EffectRequest;
import org.json_kula.valem.service.ModelService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The single {@link ModelService.EffectExecutor} registered on the service: routes each emitted
 * {@link EffectRequest} to the shell for its kind. A {@code Server} request is either an HTTP effect
 * ({@code url}) or a composition <b>link</b> ({@code target}); the latter is routed to the
 * {@link LinkEffectExecutor}. Caller effects are surfaced in the mutation response, so the server does
 * nothing with them here. A {@link EffectRequest.Plugin} (any non-built-in {@code executor} kind) is
 * routed to the registered {@link EffectExecutor} bean whose {@link EffectExecutor#kind()} matches.
 *
 * <p>Before routing, an <b>inherited-effect approval</b> gate ({@link EffectApprovalRegistry}) runs: an
 * effect inherited from a different owner's template that has not been approved is left <b>inert</b> —
 * its {@code statusPath} shows {@code pending} / {@code effect_approval_required} and it is not
 * dispatched (multi-tenant-authorization §4.2).
 */
public class CompositeEffectExecutor implements ModelService.EffectExecutor {

    private static final Logger log = LoggerFactory.getLogger(CompositeEffectExecutor.class);

    private final HttpEffectExecutor http;
    private final LinkEffectExecutor link;
    private final LlmEffectExecutor llm;
    private final TimerEffectExecutor timer;
    private final EffectApprovalRegistry approvals;
    private final ModelService service;
    private final Map<String, EffectExecutor> pluginExecutors;   // plugin kind -> shell executor

    /** Convenience: no plugin effect executors (built-in kinds only). */
    public CompositeEffectExecutor(HttpEffectExecutor http, LinkEffectExecutor link,
                                   LlmEffectExecutor llm, TimerEffectExecutor timer,
                                   EffectApprovalRegistry approvals, ModelService service) {
        this(http, link, llm, timer, approvals, service, List.of());
    }

    public CompositeEffectExecutor(HttpEffectExecutor http, LinkEffectExecutor link,
                                   LlmEffectExecutor llm, TimerEffectExecutor timer,
                                   EffectApprovalRegistry approvals, ModelService service,
                                   List<EffectExecutor> pluginExecutors) {
        this.http = http;
        this.link = link;
        this.llm = llm;
        this.timer = timer;
        this.approvals = approvals;
        this.service = service;
        Map<String, EffectExecutor> byKind = new LinkedHashMap<>();
        for (EffectExecutor ex : pluginExecutors) {
            EffectExecutor prev = byKind.put(ex.kind(), ex);
            if (prev != null) {
                log.warn("two effect executors registered for kind '{}': {} overrides {}",
                        ex.kind(), ex.getClass().getName(), prev.getClass().getName());
            }
        }
        this.pluginExecutors = byKind;
    }

    @Override
    public void submit(String modelId, EffectRequest request) {
        if (approvals.isBlocked(modelId, request.effectId())) {
            markPendingApproval(modelId, request);
            return;   // inert until the owner approves — graceful degrade, not an error
        }
        switch (request) {
            case EffectRequest.Server s -> {
                if (s.isLink()) link.submit(modelId, s);
                else            http.submit(modelId, s);
            }
            case EffectRequest.Llm l    -> llm.submit(modelId, l);
            case EffectRequest.Timer t  -> timer.submit(modelId, t);
            case EffectRequest.Caller c -> { /* surfaced in the mutation response; no server action */ }
            case EffectRequest.Plugin p -> {
                EffectExecutor ex = pluginExecutors.get(p.kind());
                if (ex != null) {
                    ex.submit(modelId, p);
                } else {
                    // Kind resolved by an EffectKind on the classpath but no shell executor bean for it.
                    log.warn("no effect executor registered for plugin kind '{}' (effect '{}' on '{}')",
                            p.kind(), p.effectId(), modelId);
                    markPluginUnroutable(modelId, p);
                }
            }
        }
    }

    /** Marks a plugin effect {@code failed} when no executor bean is registered for its kind. */
    private void markPluginUnroutable(String modelId, EffectRequest.Plugin p) {
        String statusPath = p.statusPath();
        if (statusPath == null || statusPath.isBlank()) return;
        Map<String, JsonNode> m = new LinkedHashMap<>();
        m.put(statusPath + ".phase", TextNode.valueOf("failed"));
        m.put(statusPath + ".error", TextNode.valueOf("no_executor_for_kind:" + p.kind()));
        m.put(statusPath + ".at", TextNode.valueOf(Instant.now().toString()));
        try {
            service.mutate(modelId, m);
        } catch (Exception e) {
            log.warn("could not mark plugin effect '{}' unroutable on '{}': {}",
                    p.effectId(), modelId, e.toString());
        }
    }

    /**
     * Writes {@code blocked} / {@code effect_approval_required} to the effect's statusPath, if any. Uses
     * {@code blocked} rather than {@code pending} deliberately: {@code pending}/{@code in_flight} are the
     * effect-dispatch in-flight guard's own phases, and reusing them here would wedge the effect so it
     * could never re-fire after the owner approves it. {@code blocked} leaves the edge free to re-trigger.
     */
    private void markPendingApproval(String modelId, EffectRequest request) {
        String statusPath = request.statusPath();
        if (statusPath == null || statusPath.isBlank()) return;
        Map<String, JsonNode> m = new LinkedHashMap<>();
        m.put(statusPath + ".phase", TextNode.valueOf("blocked"));
        m.put(statusPath + ".error", TextNode.valueOf("effect_approval_required"));
        m.put(statusPath + ".at", TextNode.valueOf(Instant.now().toString()));
        try {
            service.mutate(modelId, m);
        } catch (Exception e) {
            log.warn("could not mark effect '{}' pending-approval on '{}': {}",
                    request.effectId(), modelId, e.toString());
        }
    }
}
