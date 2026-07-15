package org.json_kula.valem.client;

import java.net.URI;
import java.net.http.WebSocket;
import java.util.concurrent.CompletableFuture;

/**
 * Pluggable WebSocket connector. The default implementation uses the JDK
 * {@link java.net.http.HttpClient}; tests inject a fake to drive the reconnect logic deterministically
 * without a real socket.
 */
@FunctionalInterface
public interface WsConnector {
    CompletableFuture<WebSocket> connect(URI uri, WebSocket.Listener listener);
}
