package org.json_kula.valem.core.spreadsheet;

import org.json_kula.valem.core.spreadsheet.ast.ExcelExpr;
import org.json_kula.valem.core.spreadsheet.ast.ExcelExpr.BinaryOp;
import org.json_kula.valem.core.spreadsheet.ast.ExcelExpr.BoolLit;
import org.json_kula.valem.core.spreadsheet.ast.ExcelExpr.CellRef;
import org.json_kula.valem.core.spreadsheet.ast.ExcelExpr.FuncCall;
import org.json_kula.valem.core.spreadsheet.ast.ExcelExpr.NumberLit;
import org.json_kula.valem.core.spreadsheet.ast.ExcelExpr.Percent;
import org.json_kula.valem.core.spreadsheet.ast.ExcelExpr.RangeRef;
import org.json_kula.valem.core.spreadsheet.ast.ExcelExpr.StringLit;
import org.json_kula.valem.core.spreadsheet.ast.ExcelExpr.UnaryNeg;

/**
 * Rewrites every <em>relative</em> {@link CellRef} in a formula's AST so its row becomes an offset
 * from the formula's own row, leaving absolute-row references untouched (excel-to-spec-v1-design.md
 * §5.3). Two rows' formulas have "the same shape" exactly when their normalized trees are
 * {@code equals()} — records give us structural equality for free, so no separate shape-key
 * rendering is needed.
 */
final class ShapeNormalizer {

    private ShapeNormalizer() {}

    static ExcelExpr normalize(ExcelExpr expr, int formulaRow) {
        return switch (expr) {
            case CellRef ref -> ref.rowAbsolute() ? ref
                    : new CellRef(ref.col(), ref.row() - formulaRow, ref.colAbsolute(), ref.rowAbsolute());
            case RangeRef r -> new RangeRef(
                    (CellRef) normalize(r.from(), formulaRow), (CellRef) normalize(r.to(), formulaRow));
            case BinaryOp b -> new BinaryOp(b.op(), normalize(b.left(), formulaRow), normalize(b.right(), formulaRow));
            case UnaryNeg u -> new UnaryNeg(normalize(u.operand(), formulaRow));
            case Percent p -> new Percent(normalize(p.operand(), formulaRow));
            case FuncCall f -> new FuncCall(f.name(), f.args().stream().map(a -> normalize(a, formulaRow)).toList());
            case NumberLit n -> n;
            case BoolLit b -> b;
            case StringLit s -> s;
        };
    }
}
