package org.json_kula.valem.client;

import org.json_kula.valem.client.ValemTypes.ChangeEvent;

/**
 * Callback for {@link ValemClient#subscribe}. Only {@link #onEvent} is required; the lifecycle
 * hooks default to no-ops.
 */
public interface ChangeListener {
    /** Invoked for each committed-mutation {@link ChangeEvent} pushed by the server. */
    void onEvent(ChangeEvent event);

    /** Invoked when a (re)connection opens. */
    default void onOpen() {}

    /** Invoked when the socket closes (before any automatic reconnect). */
    default void onClose() {}

    /** Invoked on a transport or parse error. */
    default void onError(Throwable error) {}
}
