package org.json_kula.valem.core.spreadsheet;

import org.json_kula.valem.core.spreadsheet.ColumnClassification.LiteralKind;
import org.json_kula.valem.core.spreadsheet.ast.ExcelExpr;

import java.util.ArrayList;
import java.util.List;

/**
 * Classifies each column within a detected {@link TableDetector.TableBounds} as a literal column, a
 * formula column (every row's formula the same shape — §5.3), or rejected with a specific reason.
 * Reference resolution (field names, constants, the rejection cascade, range-shape handling) is the
 * translator's job, not this stage's.
 */
public final class ColumnClassifier {

    private ColumnClassifier() {}

    public static List<ColumnClassification> classify(CellGrid grid, TableDetector.TableBounds bounds) {
        List<ColumnClassification> out = new ArrayList<>();
        for (int col = bounds.firstCol(); col <= bounds.lastCol(); col++) {
            out.add(classifyColumn(grid, bounds, col));
        }
        return out;
    }

    private static ColumnClassification classifyColumn(CellGrid grid, TableDetector.TableBounds bounds, int col) {
        String header = bounds.headers().get(col - bounds.firstCol());

        boolean anyFormula = false;
        boolean anyLiteral = false;
        LiteralKind literalKind = null;
        boolean mixedLiteralKinds = false;

        for (int row = bounds.firstDataRow(); row <= bounds.lastDataRow(); row++) {
            CellValue v = grid.valueAt(row, col);
            if (v instanceof CellValue.Formula) {
                anyFormula = true;
            } else if (v instanceof CellValue.NumberValue) {
                anyLiteral = true;
                if (literalKind == null) literalKind = LiteralKind.NUMBER;
                else if (literalKind != LiteralKind.NUMBER) mixedLiteralKinds = true;
            } else if (v instanceof CellValue.BooleanValue) {
                anyLiteral = true;
                if (literalKind == null) literalKind = LiteralKind.BOOLEAN;
                else if (literalKind != LiteralKind.BOOLEAN) mixedLiteralKinds = true;
            } else if (v instanceof CellValue.StringValue) {
                anyLiteral = true;
                if (literalKind == null) literalKind = LiteralKind.STRING;
                else if (literalKind != LiteralKind.STRING) mixedLiteralKinds = true;
            } else {
                return new ColumnClassification.Rejected(header,
                        UnsupportedFormulaException.Reason.EMPTY_CELL_IN_COLUMN,
                        "Row " + (row + 1) + " is empty in this column");
            }
        }

        if (anyFormula && anyLiteral) {
            return new ColumnClassification.Rejected(header, UnsupportedFormulaException.Reason.MIXED_CONTENT,
                    "Column mixes literal values and formulas");
        }
        if (anyLiteral) {
            if (mixedLiteralKinds) {
                return new ColumnClassification.Rejected(header, UnsupportedFormulaException.Reason.MIXED_CONTENT,
                        "Column mixes literal value types (e.g. numbers and text)");
            }
            return new ColumnClassification.Literal(header, literalKind);
        }

        return classifyFormulaColumn(grid, bounds, col, header);
    }

    private static ColumnClassification classifyFormulaColumn(CellGrid grid, TableDetector.TableBounds bounds,
                                                               int col, String header) {
        ExcelExpr templateNormalized = null;
        int templateRow = bounds.firstDataRow();

        for (int row = bounds.firstDataRow(); row <= bounds.lastDataRow(); row++) {
            CellValue.Formula f = (CellValue.Formula) grid.valueAt(row, col);
            ExcelExpr parsed;
            try {
                parsed = ExcelFormulaParser.parse(f.excelFormula());
            } catch (UnsupportedFormulaException e) {
                return new ColumnClassification.Rejected(header, e.reason(),
                        "Row " + (row + 1) + ": " + e.getMessage());
            }
            ExcelExpr normalized = ShapeNormalizer.normalize(parsed, row);
            if (templateNormalized == null) {
                templateNormalized = normalized;
                templateRow = row;
            } else if (!templateNormalized.equals(normalized)) {
                return new ColumnClassification.Rejected(header,
                        UnsupportedFormulaException.Reason.NON_UNIFORM_FORMULA,
                        "Row " + (row + 1) + "'s formula shape differs from row " + (templateRow + 1) + "'s");
            }
        }
        return new ColumnClassification.FormulaColumn(header, templateRow);
    }
}
