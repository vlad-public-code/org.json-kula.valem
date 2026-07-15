package org.json_kula.valem.core.engine;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.databind.JsonNode;

import java.util.List;

/**
 * Explainability record for a single derivation or constraint evaluation.
 *
 * <p>A trace captures:
 * <ul>
 *   <li>{@link #targetPath} — the field or constraint that was computed</li>
 *   <li>{@link #expression} — the JSONata expression that was evaluated</li>
 *   <li>{@link #inputPaths} — the base-field paths the expression read from</li>
 *   <li>{@link #result} — the computed value (or {@code null} for constraint passes)</li>
 *   <li>{@link #constraintPassed} — {@code true} if this was a constraint and it passed</li>
 *   <li>{@link #errorMessage} — set when evaluation threw an exception</li>
 * </ul>
 *
 * <p>Traces are collected by {@link ModelRuntime} (Task #16) and exposed via
 * {@code GET /models/{id}/explain/{path}}.
 */
public record DerivationTrace(
        String       targetPath,
        String       expression,
        List<String> inputPaths,
        JsonNode     result,
        Boolean      constraintPassed,
        String       errorMessage
) {
    /** Creates a trace for a successful value derivation. */
    public static DerivationTrace ofDerivation(
            String targetPath, String expression, List<String> inputPaths, JsonNode result) {
        return new DerivationTrace(targetPath, expression, List.copyOf(inputPaths),
                result, null, null);
    }

    /** Creates a trace for a constraint evaluation. */
    public static DerivationTrace ofConstraint(
            String constraintId, String expression, List<String> inputPaths, boolean passed) {
        return new DerivationTrace("$constraint:" + constraintId, expression,
                List.copyOf(inputPaths), null, passed, null);
    }

    /** Creates a trace for an evaluation that threw an exception. */
    public static DerivationTrace ofError(
            String targetPath, String expression, String errorMessage) {
        return new DerivationTrace(targetPath, expression, List.of(), null, null, errorMessage);
    }

    /** Derived convenience accessors — excluded from JSON so records round-trip cleanly. */
    @JsonIgnore
    public boolean isConstraint() { return targetPath != null && targetPath.startsWith("$constraint:"); }
    @JsonIgnore
    public boolean hasError()     { return errorMessage != null; }
}
