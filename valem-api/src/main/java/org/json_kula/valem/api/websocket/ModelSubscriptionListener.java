package org.json_kula.valem.api.websocket;

/**
 * Notified by {@link ValemWebSocketHandler} when a model's live-subscriber count transitions to/from
 * zero, so a shell effect executor (e.g. a scheduled timer) can pause work nobody is watching and
 * resume it when a client reconnects. All methods are no-ops by default; implementations are
 * discovered as Spring beans, so a build with no such executor wired pays no cost.
 */
public interface ModelSubscriptionListener {

    /** The model just gained its first live WebSocket subscriber (including a reconnect). */
    default void onFirstSubscriber(String modelId) {}

    /** The model just lost its last live WebSocket subscriber. */
    default void onLastUnsubscribe(String modelId) {}
}
