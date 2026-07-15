package org.json_kula.valem.service;

/**
 * Thrown when a {@code SpecEvolution} carries an {@code expectedVersion} precondition that no
 * longer matches the model's live spec version — an optimistic-concurrency conflict (HTTP 409).
 */
public class SpecVersionConflictException extends RuntimeException {

    private final String expected;
    private final String actual;

    public SpecVersionConflictException(String id, String expected, String actual) {
        super("Evolution of model '" + id + "' expected version '" + expected
                + "' but the current version is '" + actual + "'");
        this.expected = expected;
        this.actual = actual;
    }

    public String expected() { return expected; }
    public String actual()   { return actual; }
}
