package org.json_kula.valem.client;

/**
 * Thrown for any non-2xx HTTP response from the Valem API, or on transport/serialisation
 * failure. Carries the HTTP {@link #status} (0 for transport errors) and the raw response {@link #body}.
 */
public class ValemException extends RuntimeException {

    private final int status;
    private final String body;

    public ValemException(int status, String body) {
        super("Valem HTTP " + status + ": " + body);
        this.status = status;
        this.body = body;
    }

    public ValemException(String message, Throwable cause) {
        super(message, cause);
        this.status = 0;
        this.body = message;
    }

    /** HTTP status code, or 0 for a transport/serialisation error. */
    public int status() { return status; }

    /** Raw response body (possibly a JSON Problem Detail), or the error message for transport failures. */
    public String body() { return body; }
}
