package org.json_kula.valem.api.composition;

/**
 * Synchronous composition-topology errors raised at {@code POST /models} / {@code spec/evolve}
 * validation (composition architecture §4.3). Mapped to RFC 7807 Problem Detail (422) by
 * {@code GlobalExceptionHandler}. Asynchronous link-fire failures are <em>not</em> these — they surface
 * as a {@code failed} statusPath with an {@code error.code}.
 */
public sealed class CompositionException extends RuntimeException
        permits CompositionException.UnguardedCycle, CompositionException.UnresolvedLinkTarget {

    protected CompositionException(String message) {
        super(message);
    }

    /**
     * A static link cycle has an edge lacking {@code statusPath} + {@code dedupeKey}. Cycles are
     * permitted (the peer topology is a 2-cycle), but every edge must be edge-triggered so the runtime
     * terminates; an unguarded edge could propagate forever.
     */
    public static final class UnguardedCycle extends CompositionException {
        public UnguardedCycle(String message) { super(message); }
    }

    /** A link {@code target.ref} resolves to no registered model and lazy-binding is disabled. */
    public static final class UnresolvedLinkTarget extends CompositionException {
        public UnresolvedLinkTarget(String message) { super(message); }
    }
}
