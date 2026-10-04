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
        PARSE_FAILED,
        /**
         * Extracted text exceeds the char-count ceiling — checked on the RESULT, after extraction
         * completes, as a backstop against a small upload producing a pathologically large amount of
         * text (a decompression-ratio outlier, a busy single page, or any other cause), independent
         * of {@link #TOO_MANY_PAGES}. Does not bound the transient memory used *during* extraction
         * itself; for DOCX, POI's own {@code ZipSecureFile} (min-inflate-ratio + max-entry-size) is
         * the primary zip-bomb defense, active by default.
         */
        CONTENT_TOO_LARGE
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
