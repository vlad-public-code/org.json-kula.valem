package org.json_kula.valem.api.config;

import org.json_kula.valem.api.websocket.ValemWebSocketHandler;
import org.json_kula.valem.api.websocket.TokenHandshakeInterceptor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

import java.util.Arrays;

/**
 * Registers the Valem WebSocket endpoint.
 *
 * <p>Clients connect to {@code /models/{modelId}/subscribe} to receive a stream of
 * {@link org.json_kula.valem.api.websocket.ChangeEvent} messages after each
 * committed mutation.
 *
 * <p><b>Authentication.</b> When {@code valem.api.key} is configured, the handshake must
 * carry the key as a {@code ?token=<apiKey>} query parameter (browsers cannot set headers on the
 * WS handshake); see {@link TokenHandshakeInterceptor}.
 *
 * <p><b>Origins.</b> Allowed origins come from {@code valem.websocket.allowed-origins}
 * (comma-separated). When unset the endpoint is same-origin only. Configure {@code *} explicitly
 * to allow any origin (development only).
 */
@Configuration
@EnableWebSocket
public class WebSocketConfig implements WebSocketConfigurer {

    private final ValemWebSocketHandler handler;
    private final String apiKey;
    private final String allowedOrigins;

    public WebSocketConfig(
            ValemWebSocketHandler handler,
            @Value("${valem.api.key:}") String apiKey,
            @Value("${valem.websocket.allowed-origins:}") String allowedOrigins) {
        this.handler = handler;
        this.apiKey = apiKey;
        this.allowedOrigins = allowedOrigins;
    }

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        var reg = registry.addHandler(handler, "/models/*/subscribe")
                .addInterceptors(new TokenHandshakeInterceptor(apiKey));

        String[] origins = parseOrigins(allowedOrigins);
        if (origins.length > 0) {
            reg.setAllowedOrigins(origins);
        }
        // When no origins are configured Spring defaults to same-origin only.
    }

    private static String[] parseOrigins(String csv) {
        if (csv == null || csv.isBlank()) return new String[0];
        return Arrays.stream(csv.split(","))
                .map(String::strip)
                .filter(s -> !s.isEmpty())
                .toArray(String[]::new);
    }
}
