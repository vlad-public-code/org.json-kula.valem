package org.json_kula.valem.core.state;

import com.fasterxml.jackson.core.JsonPointer;

import java.util.ArrayList;
import java.util.List;

/**
 * Converts between JsonPath expressions (RFC 9535) and Jackson JsonPointer / segment lists.
 *
 * <p>All public APIs in Valem use JsonPath notation for field paths.
 * Jackson internals (reads via {@code JsonNode.at()}, writes via pointer navigation)
 * use JsonPointer; this class provides the bridge.
 *
 * <p>Examples:
 * <pre>
 *   toJsonPointer("$.order.total")         → /order/total
 *   toJsonPointer("$.order.items[0].qty")  → /order/items/0/qty
 *   toSegments("$.order.items[*].qty")     → ["order", "items", "[*]", "qty"]
 *   fromJsonPointer(/order/items/0/qty)    → $.order.items[0].qty
 * </pre>
 */
public final class PathConverter {

    private PathConverter() {}

    // ── JsonPath → JsonPointer ────────────────────────────────────────────────

    /**
     * Converts a singular JsonPath expression to a {@link JsonPointer}.
     * Wildcard segments ({@code [*]}) are not representable; call this only for concrete paths.
     */
    public static JsonPointer toJsonPointer(String jsonPath) {
        if (jsonPath == null || jsonPath.isBlank()) return JsonPointer.empty();
        List<String> segments = toSegments(jsonPath);
        StringBuilder sb = new StringBuilder();
        for (String seg : segments) {
            sb.append('/').append(rfc6901Escape(seg));
        }
        return JsonPointer.compile(sb.toString());
    }

    // ── JsonPath → segments ────────────────────────────────────────────────────

    /**
     * Splits a JsonPath expression into individual segments, stripping the leading {@code $.} root.
     * Bracket-index notation ({@code [N]}) is converted to bare numeric segments.
     * Wildcard bracket ({@code [*]}) becomes the sentinel {@code "[*]"}.
     *
     * <p>Examples:
     * <pre>
     *   "$.order.items[*].qty"  → ["order", "items", "[*]", "qty"]
     *   "$.order.items[0].qty"  → ["order", "items", "0", "qty"]
     * </pre>
     */
    public static List<String> toSegments(String jsonPath) {
        if (jsonPath == null || jsonPath.isBlank()) return List.of();
        String s = stripRoot(jsonPath);
        if (s.isEmpty()) return List.of();

        // Single-pass scanner (audit CPU-1): replaces three per-call regex compilations
        // (two replaceAll + one split) with an allocation-cheap character walk. Semantics match the
        // old replaceAll/split exactly — a numeric bracket [N] and a wildcard [*] start a new segment
        // (with any chars immediately following the bracket continuing it), any other bracket content
        // stays literal within the current identifier, and blank segments are dropped.
        List<String> segments = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        int n = s.length();
        for (int i = 0; i < n; i++) {
            char c = s.charAt(i);
            if (c == '.') {
                if (cur.length() > 0) { segments.add(cur.toString()); cur.setLength(0); }
            } else if (c == '[') {
                int close = s.indexOf(']', i);
                if (close < 0) {
                    cur.append(c); // malformed — keep literal
                } else {
                    String inside = s.substring(i + 1, close);
                    if ("*".equals(inside) || isNumeric(inside)) {
                        // A [N] or [*] bracket begins a fresh segment (matching the old replaceAll: [N]
                        // → ".N", [*] → ".[*]"). Flush the current identifier, then *seed* the
                        // accumulator with the bracket token rather than emitting it standalone, so any
                        // characters immediately following the bracket (the malformed "a[0]b" form)
                        // continue that same segment exactly as the old split-on-'.' produced "0b".
                        if (cur.length() > 0) { segments.add(cur.toString()); cur.setLength(0); }
                        cur.append("*".equals(inside) ? "[*]" : inside);
                        i = close;
                    } else {
                        // non-index bracket: preserve literally as part of the identifier
                        cur.append(s, i, close + 1);
                        i = close;
                    }
                }
            } else {
                cur.append(c);
            }
        }
        if (cur.length() > 0) segments.add(cur.toString());
        return List.copyOf(segments);
    }

    /**
     * Splits an RFC 6901 JSON Pointer into its unescaped segments.
     *
     * <p>Examples:
     * <pre>
     *   "/order/items/0/qty" → ["order", "items", "0", "qty"]
     *   ""  (whole document) → []
     * </pre>
     *
     * <p>Unlike {@link #toSegments(String)} (which parses JsonPath addresses), this consumes the
     * pointer form stored in the incremental mutation log, so a persisted patch op can be replayed
     * against the base document.
     */
    public static List<String> segmentsFromPointer(String pointer) {
        if (pointer == null || pointer.isEmpty()) return List.of();
        String p = pointer.startsWith("/") ? pointer.substring(1) : pointer;
        if (p.isEmpty()) return List.of();
        String[] parts = p.split("/", -1);
        List<String> segments = new ArrayList<>(parts.length);
        for (String part : parts) {
            segments.add(part.replace("~1", "/").replace("~0", "~"));
        }
        return List.copyOf(segments);
    }

    // ── Address canonicalisation (DEC-6 / E-T1) ────────────────────────────────

    /**
     * Normalises a Valem <b>address</b> (a path appearing as data — a spec {@code path} field,
     * a {@code defaultValues} path, a mutation/patch key, a view {@code bind}) to its canonical JSON
     * Path form: {@code $.}-rooted with bracket array indices.
     *
     * <p>Tolerant of both the canonical bracket form and the legacy dot-index form on input and
     * always <em>emits</em> brackets, so it can produce the suggested rewrite for a rejected
     * non-canonical address. Note this is only the mechanical converter — the address <em>dialect</em>
     * is enforced by {@code ModelSpecValidator}, which rejects non-canonical addresses (DEC-6).
     *
     * <pre>
     *   "$.items[0].name" → "$.items[0].name"   (already canonical)
     *   "$.items.0.name"  → "$.items[0].name"   (legacy dot-index normalised)
     *   "items.0.name"    → "$.items[0].name"   (root added)
     *   "$.items[*].qty"  → "$.items[*].qty"    (wildcard preserved)
     *   "$"               → "$"                 (document root)
     * </pre>
     *
     * <p>This is for addresses only. JSONata expression bodies ({@code expr}/{@code trigger}/…) are
     * never passed through here — they may use any navigation JSONata accepts and are left untouched.
     */
    public static String toCanonicalAddress(String address) {
        List<String> segments = toSegments(address);
        if (segments.isEmpty()) return "$";
        StringBuilder sb = new StringBuilder("$");
        for (String seg : segments) {
            if ("[*]".equals(seg)) {
                sb.append("[*]");
            } else if (isNumeric(seg)) {
                sb.append('[').append(seg).append(']');
            } else {
                sb.append('.').append(seg);
            }
        }
        return sb.toString();
    }

    /**
     * Returns {@code true} if {@code address} is already in canonical JSON Path form (equal to
     * {@link #toCanonicalAddress(String)} of itself) — i.e. {@code $.}-rooted with bracket indices
     * and no legacy dot-index segments. Blank input is not canonical.
     */
    public static boolean isCanonicalAddress(String address) {
        if (address == null || address.isBlank()) return false;
        return address.equals(toCanonicalAddress(address));
    }

    // ── JsonPointer → JsonPath ─────────────────────────────────────────────────

    /**
     * Converts a {@link JsonPointer} to a JsonPath expression.
     * Array indices become bracket notation (e.g. {@code /items/0} → {@code $.items[0]}).
     */
    public static String fromJsonPointer(JsonPointer ptr) {
        String raw = ptr.toString();
        if (raw.isEmpty()) return "$";
        String[] parts = raw.substring(1).split("/");
        StringBuilder sb = new StringBuilder("$");
        for (String part : parts) {
            String token = part.replace("~1", "/").replace("~0", "~");
            if (isNumeric(token)) {
                sb.append('[').append(token).append(']');
            } else {
                sb.append('.').append(token);
            }
        }
        return sb.toString();
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private static String stripRoot(String jsonPath) {
        String s = jsonPath.strip();
        if (s.startsWith("$.")) return s.substring(2);
        if (s.startsWith("$"))  return s.substring(1);
        return s;
    }

    private static boolean isNumeric(String s) {
        if (s == null || s.isEmpty()) return false;
        for (char c : s.toCharArray()) if (c < '0' || c > '9') return false;
        return true;
    }

    private static String rfc6901Escape(String token) {
        return token.replace("~", "~0").replace("/", "~1");
    }
}
