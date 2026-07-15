package org.json_kula.valem.cli;

/**
 * A remote-mode failure surfaced to a CLI dispatcher. Carries the HTTP {@code status} and the
 * server-reported message verbatim, so that a dispatcher — which surfaces {@link #getMessage()} —
 * produces the same error text a caller would see in embedded mode. The one structured exception the
 * dispatchers special-case (a ROLLBACK {@code ConstraintViolationException}) is re-raised as its own
 * typed exception by {@code RemoteModelOperations} rather than wrapped here, so structured error
 * output is identical across modes too.
 */
public final class RemoteOperationException extends RuntimeException {

    private final int status;

    public RemoteOperationException(int status, String message) {
        super(message);
        this.status = status;
    }

    /** HTTP status code, or {@code 0} for a transport/parse error. */
    public int status() { return status; }
}
