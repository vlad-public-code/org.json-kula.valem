package org.json_kula.valem.mcp;

/**
 * A per-request channel a long-running tool uses to (a) emit MCP {@code notifications/progress} while it
 * works and (b) observe whether the client has cancelled the request (§3.2). Handed to
 * {@link ToolRegistry#call(String, com.fasterxml.jackson.databind.JsonNode, ProgressHandle)} by
 * {@link McpServer}, which backs it with the request's {@code progressToken} and a cancellation flag it
 * flips on receiving {@code notifications/cancelled}.
 *
 * <p>{@link #NONE} is the inert handle used for the ordinary synchronous path (no progress token, never
 * cancelled), so tools that ignore progress behave exactly as before.
 */
interface ProgressHandle {

    /**
     * Emits a progress notification. No-op when the client supplied no {@code progressToken} (there is
     * nothing to correlate against). {@code progress} should be non-decreasing; {@code total} is
     * optional (pass {@code null} when unknown); {@code message} is an optional human-readable status.
     */
    void report(double progress, Double total, String message);

    /** True once the client has sent {@code notifications/cancelled} for this request. */
    boolean cancelled();

    /**
     * Asks the client to present {@code url} to the user out-of-band via a URL-mode
     * {@code elicitation/create} request (§4.3) — used by {@code pair_browser} to hand the developer the
     * verification link directly instead of relaying it through the model. No-op unless the client
     * advertised URL-mode elicitation. Fire-and-forget: pairing still completes via polling regardless of
     * the elicitation response.
     */
    default void elicitUrl(String url, String message) { /* inert unless the server supports it */ }

    ProgressHandle NONE = new ProgressHandle() {
        @Override public void report(double progress, Double total, String message) { /* inert */ }
        @Override public boolean cancelled() { return false; }
    };
}
