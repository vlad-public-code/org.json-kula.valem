package org.json_kula.valem.core.state;

/**
 * Thrown when a write would exceed a hard structural limit on the state document — currently an
 * array index beyond {@link ModelState#maxArrayIndex()}. Guards against a single small mutation
 * (e.g. {@code $.items[900000000]}) null-padding an array to that index and exhausting the heap
 * (audit SEC-1 / MEM-3). The same guard covers live mutations, default-value application, and
 * incremental-log replay, so a poisoned persisted patch cannot OOM the process on restart.
 *
 * <p>The API layer maps this to HTTP 422 (Unprocessable Entity).
 */
public final class StateLimitExceededException extends RuntimeException {
    public StateLimitExceededException(String message) {
        super(message);
    }
}
