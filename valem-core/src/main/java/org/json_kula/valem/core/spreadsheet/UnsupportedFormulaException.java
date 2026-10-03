package org.json_kula.valem.core.spreadsheet;

/**
 * A formula (or a range/reference inside one) uses a construct outside the v1 bounded grammar
 * (excel-to-spec-v1-design.md §5.2) — always carries a specific, named {@link Reason}, never a
 * generic parse failure (vision doc AC-2: "never a generic failure").
 */
public class UnsupportedFormulaException extends RuntimeException {

    public enum Reason {
        UNSUPPORTED_FUNCTION,
        UNSUPPORTED_OPERATOR,
        CROSS_SHEET_REFERENCE,
        UNSUPPORTED_RANGE_SHAPE,
        MALFORMED_FORMULA,
        // Column-level reasons, raised by the classifier/translator orchestration rather than the
        // parser itself, but carried on the same exception type for a uniform rejection shape.
        MIXED_CONTENT,
        EMPTY_CELL_IN_COLUMN,
        NON_UNIFORM_FORMULA,
        CROSS_ROW_REFERENCE,
        UNRESOLVED_REFERENCE,
        NAMED_RANGE_UNSUPPORTED,
        DEPENDS_ON_REJECTED_COLUMN,
        UNSUPPORTED_LOOKUP_MODE,
        LOOKUP_TABLE_CONTAINS_FORMULA
    }

    private final Reason reason;

    public UnsupportedFormulaException(Reason reason, String message) {
        super(message);
        this.reason = reason;
    }

    public Reason reason() {
        return reason;
    }
}
