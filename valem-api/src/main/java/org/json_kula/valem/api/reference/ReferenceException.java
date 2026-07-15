package org.json_kula.valem.api.reference;

/**
 * Reference/resolution errors raised at create / branch / promote time (references design §10.1).
 * Mapped to RFC 7807 Problem Detail by {@code GlobalExceptionHandler}. Distinct from the async
 * link-fire failures (which surface on a {@code statusPath}).
 */
public sealed class ReferenceException extends RuntimeException
        permits ReferenceException.UnresolvedReference,
                ReferenceException.TemplateCycle,
                ReferenceException.DigestMismatch,
                ReferenceException.ReferenceLocalityViolation,
                ReferenceException.PromotionClosureFailure,
                ReferenceException.DemoteUnsupported {

    protected ReferenceException(String message) {
        super(message);
    }

    /** No repository in the chain holds a version satisfying the coordinate. */
    public static final class UnresolvedReference extends ReferenceException {
        public UnresolvedReference(String message) { super(message); }
    }

    /** A {@code template} lineage is not a DAG (a template transitively references itself). */
    public static final class TemplateCycle extends ReferenceException {
        public TemplateCycle(String message) { super(message); }
    }

    /** The served artifact's content hash does not match a pinned {@code sha256:} coordinate. */
    public static final class DigestMismatch extends ReferenceException {
        public DigestMismatch(String message) { super(message); }
    }

    /** A web model references a local-only target — structurally impossible for a third-party resolver (§6). */
    public static final class ReferenceLocalityViolation extends ReferenceException {
        public ReferenceLocalityViolation(String message) { super(message); }
    }

    /** Promote blocked: the model's reference closure includes a non-web-resolvable target (§7.1). */
    public static final class PromotionClosureFailure extends ReferenceException {
        public PromotionClosureFailure(String message) { super(message); }
    }

    /** A web→local move was attempted; published coordinates are a one-way ratchet — branch instead (§7.2). */
    public static final class DemoteUnsupported extends ReferenceException {
        public DemoteUnsupported(String message) { super(message); }
    }
}
