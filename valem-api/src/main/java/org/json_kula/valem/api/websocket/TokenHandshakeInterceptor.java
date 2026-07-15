package org.json_kula.valem.api.websocket;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.server.HandshakeInterceptor;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Map;

/**
 * Authenticates the WebSocket handshake.
 *
 * <p>Browsers cannot set custom headers on a WebSocket handshake, so the API key is presented as
 * a {@code ?token=<apiKey>} query parameter and validated here against {@code valem.api.key}.
 * A missing or invalid token causes the handshake to be rejected with HTTP 401.
 *
 * <p>When no API key is configured (development / open mode) the handshake is allowed, mirroring
 * the REST {@code SecurityConfig} behaviour.
 */
public class TokenHandshakeInterceptor implements HandshakeInterceptor {

    private static final Logger log = LoggerFactory.getLogger(TokenHandshakeInterceptor.class);

    private final String requiredKey;

    public TokenHandshakeInterceptor(String requiredKey) {
        this.requiredKey = requiredKey == null ? "" : requiredKey;
    }

    @Override
    public boolean beforeHandshake(ServerHttpRequest request,
                                   ServerHttpResponse response,
                                   WebSocketHandler wsHandler,
                                   Map<String, Object> attributes) {
        if (requiredKey.isBlank()) {
            return true; // open / development mode — consistent with REST SecurityConfig
        }

        String token = extractToken(request.getURI());
        if (token != null && constantTimeEquals(requiredKey, token)) {
            return true;
        }

        log.warn("WebSocket handshake rejected: missing or invalid token (uri={})",
                request.getURI().getPath());
        response.setStatusCode(HttpStatus.UNAUTHORIZED);
        return false;
    }

    @Override
    public void afterHandshake(ServerHttpRequest request,
                               ServerHttpResponse response,
                               WebSocketHandler wsHandler,
                               Exception exception) {
        // no-op
    }

    private static String extractToken(URI uri) {
        String query = uri.getRawQuery();
        if (query == null || query.isBlank()) return null;
        for (String param : query.split("&")) {
            if (param.startsWith("token=")) {
                return java.net.URLDecoder.decode(
                        param.substring("token=".length()), StandardCharsets.UTF_8);
            }
        }
        return null;
    }

    private static boolean constantTimeEquals(String required, String presented) {
        return MessageDigest.isEqual(
                required.getBytes(StandardCharsets.UTF_8),
                presented.getBytes(StandardCharsets.UTF_8));
    }
}
