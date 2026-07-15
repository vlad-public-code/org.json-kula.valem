package org.json_kula.valem.core.engine;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * Validates a single JSON value against a subset of JSON Schema keywords.
 *
 * <p>Supported keywords: {@code readOnly}, {@code type}, {@code minimum},
 * {@code maximum}, {@code minLength}, {@code maxLength}, {@code pattern},
 * {@code enum}. Unknown or empty schema objects pass with no violations.
 */
final class SchemaValidator {

    private static final Logger log = LoggerFactory.getLogger(SchemaValidator.class);

    private SchemaValidator() {}

    /**
     * Checks {@code value} against the effective schema for {@code path}.
     *
     * @return list of violations (empty means valid)
     */
    static List<SchemaViolationException.Violation> validate(
            ObjectNode schema, String path, JsonNode value) {

        List<SchemaViolationException.Violation> out = new ArrayList<>();

        // readOnly — no value is acceptable; reject immediately
        JsonNode readOnly = schema.get("readOnly");
        if (readOnly != null && readOnly.asBoolean()) {
            out.add(violation(path, "readOnly", path + " is read-only"));
            return out; // further keyword checks are irrelevant
        }

        // type
        JsonNode typeNode = schema.get("type");
        if (typeNode != null && typeNode.isTextual()) {
            String expected = typeNode.asText();
            if (!typeMatches(expected, value)) {
                out.add(violation(path, "type",
                        path + " must be of type " + expected + " but was " + jsonType(value)));
                return out; // numeric/string keywords don't apply when type is wrong
            }
        }

        // numeric keywords
        if (value != null && value.isNumber()) {
            double num = value.asDouble();
            JsonNode minimum = schema.get("minimum");
            if (minimum != null && num < minimum.asDouble()) {
                out.add(violation(path, "minimum",
                        path + " must be >= " + minimum.asDouble() + " but was " + num));
            }
            JsonNode maximum = schema.get("maximum");
            if (maximum != null && num > maximum.asDouble()) {
                out.add(violation(path, "maximum",
                        path + " must be <= " + maximum.asDouble() + " but was " + num));
            }
        }

        // string keywords
        if (value != null && value.isTextual()) {
            String text = value.asText();
            JsonNode minLen = schema.get("minLength");
            if (minLen != null && text.length() < minLen.asInt()) {
                out.add(violation(path, "minLength",
                        path + " length must be >= " + minLen.asInt() + " but was " + text.length()));
            }
            JsonNode maxLen = schema.get("maxLength");
            if (maxLen != null && text.length() > maxLen.asInt()) {
                out.add(violation(path, "maxLength",
                        path + " length must be <= " + maxLen.asInt() + " but was " + text.length()));
            }
            JsonNode pattern = schema.get("pattern");
            if (pattern != null) {
                checkPattern(path, pattern.asText(), text, out);
            }
        }

        // enum — applies to any primitive type
        JsonNode enumValues = schema.get("enum");
        if (enumValues != null && enumValues.isArray() && value != null) {
            boolean found = false;
            for (JsonNode allowed : enumValues) {
                if (allowed.equals(value)) { found = true; break; }
                if (value.isNumber() && allowed.isNumber() &&
                        Double.compare(value.asDouble(), allowed.asDouble()) == 0) {
                    found = true;
                    break;
                }
            }
            if (!found) {
                out.add(violation(path, "enum",
                        path + " must be one of " + enumValues + " but was " + value));
            }
        }

        return out;
    }

    // ── Pattern matching (ReDoS-hardened; audit SEC-2 / CPU-6) ──────────────────

    /**
     * Bound on the input string length a {@code pattern} keyword will validate. A longer value is
     * rejected rather than fed to the regex engine, since attacker-chosen long inputs amplify
     * catastrophic backtracking. Overridable via {@code valem.limits.regex-max-input}.
     */
    private static final int MAX_PATTERN_INPUT =
            Integer.getInteger("valem.limits.regex-max-input", 100_000);

    /** Wall-clock budget for a single {@code pattern} match. Overridable in milliseconds. */
    private static final long PATTERN_TIMEOUT_NANOS =
            Long.getLong("valem.limits.regex-timeout-ms", 1_000L) * 1_000_000L;

    private static final int MAX_PATTERN_CACHE = 1_000;

    /**
     * Compiled-{@link Pattern} cache — avoids recompiling the regex on every mutation (CPU-6). Bounded
     * access-ordered LRU rather than a clear-on-overflow map, so a spec churning through many distinct
     * patterns evicts only the coldest entries instead of periodically flushing every hot pattern and
     * recompiling them en masse under the model lock.
     */
    private static final Map<String, Pattern> PATTERN_CACHE = Collections.synchronizedMap(
            new LinkedHashMap<>(128, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, Pattern> eldest) {
                    return size() > MAX_PATTERN_CACHE;
                }
            });

    /** Thrown by {@link DeadlineCharSequence} when a match exceeds its time budget. */
    private static final class RegexTimeoutException extends RuntimeException {
        RegexTimeoutException() { super("regex match timed out"); }
    }

    /**
     * Validates {@code text} against {@code regex}, guarding against ReDoS: over-long inputs are
     * rejected up front, the compiled pattern is cached, and the match runs against a
     * deadline-checking {@link CharSequence} so a catastrophic-backtracking pattern is aborted
     * instead of pinning a CPU core under the model lock. A malformed pattern in the schema (author
     * error) is skipped rather than throwing.
     */
    private static void checkPattern(String path, String regex, String text,
                                     List<SchemaViolationException.Violation> out) {
        if (text.length() > MAX_PATTERN_INPUT) {
            out.add(violation(path, "pattern",
                    path + " length " + text.length() + " exceeds the maximum validatable length ("
                            + MAX_PATTERN_INPUT + ")"));
            return;
        }
        Pattern pattern;
        try {
            pattern = compilePattern(regex);
        } catch (PatternSyntaxException e) {
            // Invalid schema pattern (author error). Don't 500, but don't silently pass either: log so
            // the unenforced constraint is visible. Spec create/evolve also rejects malformed patterns
            // up front (ModelSpecValidator), so reaching here means a pattern that slipped past that.
            log.warn("schema pattern /{}/ at {} is invalid ({}); field left unvalidated against it",
                    regex, path, e.getMessage());
            return;
        }
        try {
            Matcher m = pattern.matcher(
                    new DeadlineCharSequence(text, System.nanoTime() + PATTERN_TIMEOUT_NANOS));
            if (!m.matches()) {
                out.add(violation(path, "pattern", path + " must match pattern /" + regex + "/"));
            }
        } catch (RegexTimeoutException e) {
            out.add(violation(path, "pattern",
                    path + " could not be validated against pattern /" + regex + "/ (timed out)"));
        }
    }

    private static Pattern compilePattern(String regex) {
        return PATTERN_CACHE.computeIfAbsent(regex, Pattern::compile);
    }

    /**
     * A read-only {@link CharSequence} view that throws {@link RegexTimeoutException} once a deadline
     * passes. Catastrophic backtracking reads characters super-linearly, so a runaway match trips the
     * deadline instead of running unbounded. {@code java.util.regex} offers no native match timeout.
     */
    private static final class DeadlineCharSequence implements CharSequence {
        // Only sample the clock every CLOCK_CHECK_MASK+1 reads: a benign match on a long input would
        // otherwise pay a System.nanoTime() per character (often costlier than the match itself),
        // while a runaway backtracking match reads far more than 1024 chars before the budget elapses,
        // so the timeout still trips within microseconds of the deadline.
        private static final int CLOCK_CHECK_MASK = 1023;

        private final CharSequence inner;
        private final long deadlineNanos;
        private int reads;

        DeadlineCharSequence(CharSequence inner, long deadlineNanos) {
            this.inner = inner;
            this.deadlineNanos = deadlineNanos;
        }

        @Override public int length() { return inner.length(); }

        @Override public char charAt(int index) {
            // Overflow-safe deadline test: nanoTime()'s origin is arbitrary, so compare the signed
            // difference rather than the absolute values (which can wrap near Long.MAX_VALUE).
            if ((reads++ & CLOCK_CHECK_MASK) == 0 && System.nanoTime() - deadlineNanos > 0) {
                throw new RegexTimeoutException();
            }
            return inner.charAt(index);
        }

        @Override public CharSequence subSequence(int start, int end) {
            return new DeadlineCharSequence(inner.subSequence(start, end), deadlineNanos);
        }

        @Override public String toString() { return inner.toString(); }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private static boolean typeMatches(String schemaType, JsonNode value) {
        if (value == null || value.isNull() || value.isMissingNode()) return "null".equals(schemaType);
        return switch (schemaType) {
            case "string"  -> value.isTextual();
            case "number"  -> value.isNumber();
            case "integer" -> value.isIntegralNumber();
            case "boolean" -> value.isBoolean();
            case "object"  -> value.isObject();
            case "array"   -> value.isArray();
            case "null"    -> value.isNull();
            default        -> true; // unknown type keyword — allow
        };
    }

    private static String jsonType(JsonNode value) {
        if (value == null || value.isNull())    return "null";
        if (value.isTextual())                  return "string";
        if (value.isIntegralNumber())           return "integer";
        if (value.isNumber())                   return "number";
        if (value.isBoolean())                  return "boolean";
        if (value.isObject())                   return "object";
        if (value.isArray())                    return "array";
        return "unknown";
    }

    private static SchemaViolationException.Violation violation(
            String path, String keyword, String message) {
        return new SchemaViolationException.Violation(path, keyword, message);
    }
}
