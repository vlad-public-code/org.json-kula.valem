package org.json_kula.valem.service;

import java.util.function.Consumer;

/**
 * Optional capability of a {@link ModelOperations} facade: stream post-commit change events for a model
 * so a caller can react without polling. Implemented by the embedded {@link ModelService} (via its
 * in-process change listeners) and by the remote/paired facades (via the server's WebSocket change
 * stream). The MCP server uses it to map a model's changes onto {@code notifications/resources/updated}
 * so an agent in a paired session sees the browser's edits immediately (§4.2).
 */
public interface ChangeSubscribable {

    /**
     * Subscribes to change events for {@code modelId}. The listener is invoked (on some background
     * thread) with the changed model's id after each committed mutation cycle. Returns a handle whose
     * {@link AutoCloseable#close()} cancels the subscription.
     */
    AutoCloseable subscribeChanges(String modelId, Consumer<String> onChange);
}
