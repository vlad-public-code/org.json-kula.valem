package org.json_kula.valem.mcp;

/**
 * Implemented by a {@code ModelOperations} facade that can pair the local MCP process with a browser
 * session on a hosted Valem web host (the {@code remote_with_browser} mode). {@link ToolRegistry}
 * exposes the {@code pair_browser} tool only when the active facade implements this — embedded and
 * plain {@code --url} remote mode never see it.
 */
interface BrowserPairable {

    /**
     * Advances the device-flow pairing handshake one step:
     * <ul>
     *   <li>not yet paired, no pairing in flight — mints a new one and polls for a bounded window;</li>
     *   <li>a pairing is already in flight (the developer hasn't approved yet) — resumes polling the
     *       <b>same</b> pairing rather than minting a new one;</li>
     *   <li>already paired — returns immediately, idempotently.</li>
     * </ul>
     * Never blocks longer than a bounded local budget; if the developer hasn't approved within that
     * window the result reports {@code "pending"} with the same link/code so the caller can tell the
     * developer to approve it and simply call this tool again.
     */
    PairResult pairBrowser();

    /**
     * Same as {@link #pairBrowser()} but reports progress while waiting and aborts early if the client
     * cancels the request (§3.2). The default ignores the handle (for facades that do not support
     * progress); {@link SandboxSessionModelOperations} overrides it to emit a heartbeat each poll and
     * honour cancellation.
     */
    default PairResult pairBrowser(ProgressHandle progress) {
        return pairBrowser();
    }
}
