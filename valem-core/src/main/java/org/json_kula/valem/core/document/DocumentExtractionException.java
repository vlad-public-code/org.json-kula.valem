package org.json_kula.valem.core.document;

/**
 * A named, user-facing extraction failure — never a silent partial result (vision doc AC-1/AC-7:
 * "predictable degradation, never silent truncation").
 */
public class DocumentExtractionException extends Exception {

    public enum Reason {
        UNSUPPORTED_FORMAT,
        FILE_TOO_LARGE,
        TOO_MANY_PAGES,
        EMPTY_DOCUMENT,
        PARSE_FAILED
    }

    private final Reason reason;

    public DocumentExtractionException(Reason reason, String message) {
        super(message);
        this.reason = reason;
    }

    public DocumentExtractionException(Reason reason, String message, Throwable cause) {
        super(message, cause);
        this.reason = reason;
    }

    public Reason reason() {
        return reason;
    }
}
