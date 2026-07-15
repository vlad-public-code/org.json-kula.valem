package org.json_kula.valem.core.engine.spi;

/**
 * The validation surface handed to an {@link EffectKind#validate} implementation. It exposes the same
 * primitive checks the built-in effect validation uses — record an error, compile-check a JSONata
 * expression, verify a canonical writable address — so a plugin validates its spec fragment consistently
 * without depending on {@code ModelSpecValidator} internals. Errors are collected, not thrown; a
 * location string identifies the offending field (e.g. {@code "effects[2].prompt"}).
 */
public interface EffectValidationContext {

    /** Records a validation error at {@code location}. */
    void error(String location, String message);

    /** Compile-checks a JSONata expression, adding an error at {@code location} if it does not parse. */
    void validateExpr(String expr, String location);

    /** Verifies {@code address} is a canonical (`$.`-rooted, bracket-index) writable address. */
    void checkAddress(String address, String location);
}
