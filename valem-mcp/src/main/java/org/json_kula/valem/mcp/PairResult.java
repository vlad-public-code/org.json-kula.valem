package org.json_kula.valem.mcp;

/**
 * The result of one {@link BrowserPairable#pairBrowser()} call, surfaced to the agent verbatim as the
 * {@code pair_browser} tool's structured content.
 *
 * @param status          one of {@code "paired"}, {@code "already_paired"}, {@code "pending"}
 * @param verificationUri present when {@code "pending"}: the link to show the developer
 * @param userCode        present when {@code "pending"}: the confirmation code shown on both ends
 * @param expiresInSec    present when {@code "pending"}: seconds left before this pairing expires
 * @param namespaceId     present when {@code "paired"}/{@code "already_paired"}: the shared session's
 *                        non-secret namespace id (never the session token itself)
 */
record PairResult(String status, String verificationUri, String userCode, Long expiresInSec, String namespaceId) {

    static PairResult alreadyPaired(String namespaceId) {
        return new PairResult("already_paired", null, null, null, namespaceId);
    }

    static PairResult paired(String namespaceId) {
        return new PairResult("paired", null, null, null, namespaceId);
    }

    static PairResult pending(String verificationUri, String userCode, long expiresInSec) {
        return new PairResult("pending", verificationUri, userCode, expiresInSec, null);
    }
}
