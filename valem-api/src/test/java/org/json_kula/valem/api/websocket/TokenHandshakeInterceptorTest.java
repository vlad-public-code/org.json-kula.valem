package org.json_kula.valem.api.websocket;

import org.junit.jupiter.api.Test;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.http.server.ServletServerHttpRequest;
import org.springframework.http.server.ServletServerHttpResponse;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.util.HashMap;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for WebSocket handshake authentication ({@link TokenHandshakeInterceptor}).
 */
class TokenHandshakeInterceptorTest {

    private static final String KEY = "s3cr3t-key";

    private boolean handshake(TokenHandshakeInterceptor interceptor, String query, MockHttpServletResponse rawResp) {
        MockHttpServletRequest rawReq = new MockHttpServletRequest("GET", "/models/order/subscribe");
        if (query != null) rawReq.setQueryString(query);
        ServerHttpRequest req = new ServletServerHttpRequest(rawReq);
        ServerHttpResponse resp = new ServletServerHttpResponse(rawResp);
        return interceptor.beforeHandshake(req, resp, null, new HashMap<>());
    }

    @Test
    void rejects_handshake_without_token() {
        var interceptor = new TokenHandshakeInterceptor(KEY);
        var resp = new MockHttpServletResponse();
        assertThat(handshake(interceptor, null, resp)).isFalse();
        assertThat(resp.getStatus()).isEqualTo(401);
    }

    @Test
    void rejects_handshake_with_invalid_token() {
        var interceptor = new TokenHandshakeInterceptor(KEY);
        var resp = new MockHttpServletResponse();
        assertThat(handshake(interceptor, "token=wrong", resp)).isFalse();
        assertThat(resp.getStatus()).isEqualTo(401);
    }

    @Test
    void accepts_handshake_with_valid_token() {
        var interceptor = new TokenHandshakeInterceptor(KEY);
        var resp = new MockHttpServletResponse();
        assertThat(handshake(interceptor, "token=" + KEY, resp)).isTrue();
    }

    @Test
    void accepts_handshake_with_valid_token_among_other_params() {
        var interceptor = new TokenHandshakeInterceptor(KEY);
        var resp = new MockHttpServletResponse();
        assertThat(handshake(interceptor, "paths=$.a,$.b&token=" + KEY, resp)).isTrue();
    }

    @Test
    void open_mode_allows_handshake_when_no_key_configured() {
        var interceptor = new TokenHandshakeInterceptor("");
        var resp = new MockHttpServletResponse();
        assertThat(handshake(interceptor, null, resp)).isTrue();
    }
}
