package org.json_kula.valem.client;

/**
 * Handle for an active {@link ValemClient#subscribe} WebSocket. Closing it stops the socket and
 * prevents any further automatic reconnect. Idempotent.
 */
public interface Subscription extends AutoCloseable {
    @Override
    void close();
}
