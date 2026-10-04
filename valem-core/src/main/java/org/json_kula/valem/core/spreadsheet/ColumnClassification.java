package org.json_kula.valem.core.spreadsheet;

/**
 * Per-column outcome of {@link ColumnClassifier} (excel-to-spec-v1-design.md §5.1/§5.3). Reference
 * resolution, constant naming, the rejection cascade, and row-local-vs-aggregate range handling all
 * happen later, in {@code ExcelFormulaTranslator} — this stage only decides literal vs.
 * formula-candidate vs. structurally rejected.
 */
public sealed interface ColumnClassification
        permits ColumnClassification.Literal, ColumnClassification.FormulaColumn, ColumnClassification.Rejected {

    enum LiteralKind { NUMBER, BOOLEAN, STRING }

    record Literal(String header, LiteralKind kind) implements ColumnClassification {}

    /**
     * Every data-row cell is a formula, and every row's formula has the same shape once relative
     * references are normalized to an offset from their own row (§5.3). {@code templateRow} (0-based,
     * absolute) is the row whose formula the translator uses as the shape template.
     */
    record FormulaColumn(String header, int templateRow) implements ColumnClassification {}

    record Rejected(String header, UnsupportedFormulaException.Reason reason, String detail)
            implements ColumnClassification {}
}
