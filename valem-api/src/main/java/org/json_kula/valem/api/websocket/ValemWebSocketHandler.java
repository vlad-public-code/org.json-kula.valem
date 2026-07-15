package org.json_kula.valem.api.websocket;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.ConcurrentWebSocketSessionDecorator;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.io.IOException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * WebSocket handler for the {@code /models/{modelId}/subscribe} endpoint.
 *
 * <p>Clients connect to receive a stream of {@link ChangeEvent} messages (as JSON text frames)
 * after each successful mutation on the model they subscribed to.
 *
 * <p><b>Per-path filtering</b> — clients may subscribe to a subset of paths by passing a
 * comma-separated {@code paths} query parameter:
 * <pre>
 *   ws://host/models/order/subscribe?paths=$.order.total,$.order.items
 * </pre>
 * An event is forwarded to the session only when at least one path in {@code mutatedPaths}
 * or {@code derivedUpdated} starts with one of the filter prefixes.  Events that carry
 * constraint violations or dispatched actions are always forwarded regardless of the filter.
 * Omitting the {@code paths} parameter subscribes to all events (current default behaviour).
 *
 * <p>The model ID is extracted from the WebSocket URI path. Subscription sessions are
 * stored per model ID in a {@link ConcurrentHashMap}. The handler is thread-safe.
 */
@Component
public class ValemWebSocketHandler extends TextWebSocketHandler {

    private static final Logger log = LoggerFactory.getLogger(ValemWebSocketHandler.class);

    private final ObjectMapper mapper;
    private final List<ModelSubscriptionListener> subscriptionListeners;

    // Bounds on a single slow consumer before it is evicted rather than allowed to stall the
    // broadcaster or grow the heap (audit CPU-7 / MEM-8).
    private static final int  SEND_TIME_LIMIT_MS  = 5_000;
    private static final int  SEND_BUFFER_LIMIT   = 512 * 1024;

    // modelId → set of active WebSocket sessions (each wrapped in a bounded, thread-safe decorator)
    private final ConcurrentHashMap<String, Set<WebSocketSession>> sessions =
            new ConcurrentHashMap<>();

    // sessionId → set of path-prefix filters (null entry = no filter, receive all)
    private final ConcurrentHashMap<String, Set<String>> sessionFilters =
            new ConcurrentHashMap<>();

    public ValemWebSocketHandler(ObjectMapper mapper, List<ModelSubscriptionListener> subscriptionListeners) {
        this.mapper = mapper;
        this.subscriptionListeners = subscriptionListeners;
    }

    // ── Spring WebSocket lifecycle ────────────────────────────────────────────

    @Override
    public void afterConnectionEstablished(WebSocketSession session) {
        String modelId = extractModelId(session);
        // Wrap once at establishment: the decorator buffers and time-bounds sends per session, so a
        // slow client is evicted instead of blocking delivery to every other subscriber (CPU-7).
        WebSocketSession bounded = new ConcurrentWebSocketSessionDecorator(
                session, SEND_TIME_LIMIT_MS, SEND_BUFFER_LIMIT);
        // Add the session and detect the 0->1 transition atomically (compute holds the per-key bin
        // lock), so two concurrent first-connects cannot both observe an empty set and double-fire
        // onFirstSubscriber, and a concurrent close cannot orphan this session into a removed set.
        boolean[] firstSubscriber = {false};
        sessions.compute(modelId, (k, set) -> {
            if (set == null) set = ConcurrentHashMap.newKeySet();
            if (set.isEmpty()) firstSubscriber[0] = true;
            set.add(bounded);
            return set;
        });

        Set<String> filter = parsePathFilter(session);
        if (filter != null) {
            sessionFilters.put(session.getId(), filter);
        }

        log.debug("WS connected: model={} session={} filter={}", modelId, session.getId(), filter);
        // First subscriber (including a reconnect) — let listeners resume any paused work (e.g. a
        // timer effect an executor stopped re-scheduling while nobody was watching).
        if (firstSubscriber[0]) {
            for (ModelSubscriptionListener l : subscriptionListeners) l.onFirstSubscriber(modelId);
        }
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        String modelId = extractModelId(session);
        // Remove the session and drop the map entry iff it became empty, atomically — so the emptiness
        // check and the map removal cannot interleave with a concurrent connect (which would otherwise
        // leave a live session in a set no longer reachable from the map).
        boolean[] lastUnsubscribe = {false};
        sessions.computeIfPresent(modelId, (k, set) -> {
            // The stored session is a decorator; match by id since Spring passes the raw session here.
            set.removeIf(ws -> ws.getId().equals(session.getId()));
            if (set.isEmpty()) {
                lastUnsubscribe[0] = true;
                return null;   // drop the mapping
            }
            return set;
        });
        sessionFilters.remove(session.getId());
        log.debug("WS closed: model={} session={} status={}", modelId, session.getId(), status);
        if (lastUnsubscribe[0]) {
            for (ModelSubscriptionListener l : subscriptionListeners) l.onLastUnsubscribe(modelId);
        }
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) {
        // Subscriptions are read-only push streams; inbound text messages are ignored.
    }

    // ── Broadcast ─────────────────────────────────────────────────────────────

    /**
     * Broadcasts a {@link ChangeEvent} to all sessions subscribed to the given model,
     * applying per-session path filters.
     *
     * <p>Sessions that have been closed are removed automatically.
     */
    public void broadcast(String modelId, ChangeEvent event) {
        Set<WebSocketSession> set = sessions.get(modelId);
        if (set == null || set.isEmpty()) return;

        // Serialise the event exactly once — the JSON is identical for every subscriber; filters only
        // decide whether to send it (audit CPU-7). Previously it was re-serialised per session.
        final String json;
        try {
            json = mapper.writeValueAsString(event);
        } catch (IOException e) {
            log.error("Failed to serialise ChangeEvent for model {}", modelId, e);
            return;
        }

        set.removeIf(ws -> {
            if (!ws.isOpen()) return true;

            Set<String> filter = sessionFilters.get(ws.getId());
            if (!eventMatchesFilter(event, filter)) return false; // skip; keep session

            try {
                ws.sendMessage(new TextMessage(json)); // decorator is thread-safe + bounded
                return false;
            } catch (Exception e) {
                // Includes the decorator's SessionLimitExceededException for a slow/over-buffered
                // consumer — evict it and move on rather than let it stall the broadcast.
                log.warn("Failed to send WS message to session {}: {}", ws.getId(), e.getMessage());
                try { ws.close(); } catch (IOException ignore) { /* best effort */ }
                return true;
            }
        });
    }

    // ── Filter logic ──────────────────────────────────────────────────────────

    /**
     * Returns {@code true} when the event should be forwarded to a session with the given filter.
     *
     * <ul>
     *   <li>A {@code null} filter means "no filter" — all events pass.</li>
     *   <li>Otherwise an event passes if any path in {@code mutatedPaths} or
     *       {@code derivedUpdated} starts with one of the filter prefixes.</li>
     *   <li>Events with constraint violations or dispatched actions always pass,
     *       so clients never miss safety-relevant notifications.</li>
     * </ul>
     */
    static boolean eventMatchesFilter(ChangeEvent event, Set<String> filterPaths) {
        if (filterPaths == null) return true;

        // Constraint violations and dispatched (caller) effects are always forwarded
        if (!event.flaggedConstraints().isEmpty() || !event.dispatchedEffects().isEmpty()) return true;

        for (String prefix : filterPaths) {
            for (String p : event.mutatedPaths()) {
                if (p.startsWith(prefix)) return true;
            }
            for (String p : event.derivedUpdated()) {
                if (p.startsWith(prefix)) return true;
            }
        }
        return false;
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    /** Extracts the model ID from the URI path {@code /models/{modelId}/subscribe}. */
    private static String extractModelId(WebSocketSession session) {
        String path = session.getUri() != null ? session.getUri().getPath() : "";
        String[] parts = path.split("/");
        return parts.length >= 3 ? parts[2] : "unknown";
    }

    /**
     * Parses the {@code paths} query parameter from the session URI.
     * Returns {@code null} if the parameter is absent or blank (subscribe to all).
     */
    private static Set<String> parsePathFilter(WebSocketSession session) {
        if (session.getUri() == null) return null;
        String query = session.getUri().getRawQuery();
        if (query == null || query.isBlank()) return null;

        for (String param : query.split("&")) {
            if (param.startsWith("paths=")) {
                String raw = param.substring("paths=".length());
                String decoded = URLDecoder.decode(raw, StandardCharsets.UTF_8);
                if (decoded.isBlank()) return null;
                Set<String> paths = Arrays.stream(decoded.split(","))
                        .map(String::strip)
                        .filter(s -> !s.isEmpty())
                        .collect(Collectors.toSet());
                return paths.isEmpty() ? null : paths;
            }
        }
        return null;
    }
}
