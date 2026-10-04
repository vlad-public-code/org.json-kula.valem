package org.json_kula.valem.core.spreadsheet;

/**
 * A named, user-facing failure in opening/reading the workbook itself — distinct from
 * {@link UnsupportedFormulaException}, which is about a specific formula/column the table-detection
 * and translation pipeline understood fine but couldn't translate. Mirrors
 * {@code DocumentExtractionException}'s "never a silent partial result" discipline.
 */
public class SpreadsheetExtractionException extends RuntimeException {

    public enum Reason {
        UNSUPPORTED_FORMAT,
        FILE_TOO_LARGE,
        TOO_MANY_ROWS,
        TOO_MANY_COLUMNS,
        EMPTY_WORKBOOK,
        PARSE_FAILED
    }

    private final Reason reason;

    public SpreadsheetExtractionException(Reason reason, String message) {
        super(message);
        this.reason = reason;
    }

    public SpreadsheetExtractionException(Reason reason, String message, Throwable cause) {
        super(message, cause);
        this.reason = reason;
    }

    public Reason reason() {
        return reason;
    }
}
