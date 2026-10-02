package org.json_kula.valem.core.spreadsheet.ast;

import java.util.List;

/**
 * The AST for the bounded Excel-formula grammar v1 supports (excel-to-spec-v1-design.md §5.2).
 * Produced by {@code ExcelFormulaParser} from the raw string {@code Cell.getCellFormula()} returns;
 * consumed by {@code ExcelFormulaTranslator} to emit JSONata. Deliberately small — anything outside
 * this set is a named parse-time rejection, never a best-effort guess.
 */
public sealed interface ExcelExpr
        permits ExcelExpr.NumberLit, ExcelExpr.BoolLit, ExcelExpr.CellRef, ExcelExpr.RangeRef,
                ExcelExpr.BinaryOp, ExcelExpr.UnaryNeg, ExcelExpr.Percent, ExcelExpr.FuncCall {

    record NumberLit(double value) implements ExcelExpr {}

    record BoolLit(boolean value) implements ExcelExpr {}

    /**
     * A single-cell reference. {@code col}/{@code row} are 0-based (matching POI's own indexing),
     * even though the formula text is 1-based for rows and letter-based for columns.
     */
    record CellRef(int col, int row, boolean colAbsolute, boolean rowAbsolute) implements ExcelExpr {}

    /** A range like {@code B2:D2} or {@code B2:B50} — always two corner {@link CellRef}s. */
    record RangeRef(CellRef from, CellRef to) implements ExcelExpr {}

    /** {@code op} is one of {@code + - * / ^ = <> < > <= >=}. */
    record BinaryOp(String op, ExcelExpr left, ExcelExpr right) implements ExcelExpr {}

    record UnaryNeg(ExcelExpr operand) implements ExcelExpr {}

    /** Excel's percent suffix: {@code 20%} parses as {@code Percent(NumberLit(20))}, meaning {@code 20/100}. */
    record Percent(ExcelExpr operand) implements ExcelExpr {}

    /** {@code name} is upper-case (Excel function names are case-insensitive; normalized on parse). */
    record FuncCall(String name, List<ExcelExpr> args) implements ExcelExpr {}
}
