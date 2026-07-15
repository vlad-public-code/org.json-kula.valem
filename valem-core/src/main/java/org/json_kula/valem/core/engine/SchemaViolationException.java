package org.json_kula.valem.core.engine;

import java.util.List;

/**
 * Thrown by {@link ModelRuntime#mutate} when one or more incoming values violate
 * the effective schema constraints before the mutation is applied.
 *
 * <p>No state change occurs when this exception is thrown — the transaction is
 * never opened.
 */
public final class SchemaViolationException extends RuntimeException {

    /** A single field-level schema constraint that was violated. */
    public record Violation(String path, String keyword, String message) {}

    private final List<Violation> violations;

    public SchemaViolationException(List<Violation> violations) {
        super(buildMessage(violations));
        this.violations = List.copyOf(violations);
    }

    public List<Violation> violations() { return violations; }

    private static String buildMessage(List<Violation> vs) {
        if (vs.size() == 1) return "Schema violation: " + vs.getFirst().message();
        return vs.size() + " schema violations: " +
                vs.stream().map(Violation::message).toList();
    }
}
