package org.json_kula.valem.core.spreadsheet;

import org.json_kula.valem.core.spreadsheet.ast.ExcelExpr;
import org.json_kula.valem.core.spreadsheet.ast.ExcelExpr.BinaryOp;
import org.json_kula.valem.core.spreadsheet.ast.ExcelExpr.BoolLit;
import org.json_kula.valem.core.spreadsheet.ast.ExcelExpr.CellRef;
import org.json_kula.valem.core.spreadsheet.ast.ExcelExpr.FuncCall;
import org.json_kula.valem.core.spreadsheet.ast.ExcelExpr.NumberLit;
import org.json_kula.valem.core.spreadsheet.ast.ExcelExpr.Percent;
import org.json_kula.valem.core.spreadsheet.ast.ExcelExpr.RangeRef;
import org.json_kula.valem.core.spreadsheet.ast.ExcelExpr.UnaryNeg;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import static org.json_kula.valem.core.spreadsheet.UnsupportedFormulaException.Reason.CROSS_ROW_REFERENCE;
import static org.json_kula.valem.core.spreadsheet.UnsupportedFormulaException.Reason.DEPENDS_ON_REJECTED_COLUMN;
import static org.json_kula.valem.core.spreadsheet.UnsupportedFormulaException.Reason.UNRESOLVED_REFERENCE;
import static org.json_kula.valem.core.spreadsheet.UnsupportedFormulaException.Reason.UNSUPPORTED_FUNCTION;
import static org.json_kula.valem.core.spreadsheet.UnsupportedFormulaException.Reason.UNSUPPORTED_RANGE_SHAPE;

/**
 * Resolves each surviving column to a field name, translates every {@code FormulaColumn}'s template
 * row into one per-item JSONata expression (excel-to-spec-v1-design.md §5.2-§5.4), and runs the
 * rejection cascade: a formula referencing a column that is itself rejected becomes rejected too,
 * iterated to a fixed point.
 *
 * <p>Whole-column aggregate ranges (a total spanning every data row) are deliberately <b>not</b>
 * handled here — only row-local (same-row) ranges are supported inside a per-row formula in v1. A
 * standalone summary cell outside the table is a separate, simpler case handled by
 * {@code SpreadsheetCompiler} (design spec §5.5's whole-column case, scoped down for v1 — see that
 * class's javadoc for why).
 */
public final class ExcelFormulaTranslator {

    /** One surviving (non-rejected) column's resolved field name and content. */
    public sealed interface ColumnOutcome permits ColumnOutcome.LiteralField, ColumnOutcome.PerItemDerivation,
            ColumnOutcome.Rejected {
        record LiteralField(ColumnClassification.LiteralKind kind) implements ColumnOutcome {}
        record PerItemDerivation(String jsonataExpr, Set<Integer> dependsOnCols) implements ColumnOutcome {}
        record Rejected(UnsupportedFormulaException.Reason reason, String detail) implements ColumnOutcome {}
    }

    /** {@code col} is the absolute 0-based column index — never re-derived by header-text lookup,
     *  which would silently resolve to the WRONG column whenever two columns share a header (or both
     *  fall back to the same {@link #toFieldName} default). */
    public record ColumnResult(int col, String header, String fieldName, ColumnOutcome outcome) {}

    public record TranslationResult(List<ColumnResult> columns, Map<String, Double> constants) {}

    private static final Map<String, String> SIMPLE_FUNCS = Map.of(
            "ABS", "$abs", "ROUND", "$round");

    private final CellGrid grid;
    private final TableDetector.TableBounds bounds;
    private final Map<Integer, String> fieldNamesByCol = new LinkedHashMap<>();
    private final Map<String, Double> constants = new LinkedHashMap<>();
    private final Map<Long, String> constantKeyToName = new LinkedHashMap<>();

    public ExcelFormulaTranslator(CellGrid grid, TableDetector.TableBounds bounds) {
        this.grid = grid;
        this.bounds = bounds;
        // Two columns can legitimately reduce to the same field name (two headers both literally
        // "Total", or two different headers that both collapse under toFieldName, e.g. "Unit-Price"
        // and "Unit Price"). Without disambiguation, one column's schema property/derivation would
        // silently overwrite the other's — same dedup discipline as resolveConstant's $const names.
        Set<String> usedFieldNames = new java.util.HashSet<>();
        for (int col = bounds.firstCol(); col <= bounds.lastCol(); col++) {
            String name = toFieldName(bounds.headers().get(col - bounds.firstCol()));
            String unique = name;
            int suffix = 2;
            while (!usedFieldNames.add(unique)) {
                unique = name + suffix++;
            }
            fieldNamesByCol.put(col, unique);
        }
    }

    public TranslationResult translate(List<ColumnClassification> classifications) {
        Map<Integer, ColumnOutcome> outcomes = new LinkedHashMap<>();
        int firstCol = bounds.firstCol();

        for (int col = bounds.firstCol(); col <= bounds.lastCol(); col++) {
            ColumnClassification c = classifications.get(col - firstCol);
            outcomes.put(col, switch (c) {
                case ColumnClassification.Literal lit -> new ColumnOutcome.LiteralField(lit.kind());
                case ColumnClassification.Rejected rej -> new ColumnOutcome.Rejected(rej.reason(), rej.detail());
                case ColumnClassification.FormulaColumn fc -> translateFormulaColumn(col, fc);
            });
        }

        cascadeRejections(outcomes);

        List<ColumnResult> results = new java.util.ArrayList<>();
        for (int col = bounds.firstCol(); col <= bounds.lastCol(); col++) {
            results.add(new ColumnResult(col, bounds.headers().get(col - firstCol), fieldNamesByCol.get(col),
                    outcomes.get(col)));
        }
        return new TranslationResult(results, constants);
    }

    private void cascadeRejections(Map<Integer, ColumnOutcome> outcomes) {
        boolean changed = true;
        while (changed) {
            changed = false;
            for (var entry : outcomes.entrySet()) {
                if (!(entry.getValue() instanceof ColumnOutcome.PerItemDerivation d)) continue;
                for (int depCol : d.dependsOnCols()) {
                    if (outcomes.get(depCol) instanceof ColumnOutcome.Rejected) {
                        entry.setValue(new ColumnOutcome.Rejected(DEPENDS_ON_REJECTED_COLUMN,
                                "Depends on column '" + fieldNamesByCol.get(depCol) + "', which was rejected"));
                        changed = true;
                        break;
                    }
                }
            }
        }
    }

    private ColumnOutcome translateFormulaColumn(int col, ColumnClassification.FormulaColumn fc) {
        int templateRow = fc.templateRow();
        CellValue.Formula f = (CellValue.Formula) grid.valueAt(templateRow, col);
        try {
            ExcelExpr ast = ExcelFormulaParser.parse(f.excelFormula());
            Set<Integer> deps = new LinkedHashSet<>();
            String expr = emit(ast, templateRow, deps);
            return new ColumnOutcome.PerItemDerivation(expr, deps);
        } catch (UnsupportedFormulaException e) {
            return new ColumnOutcome.Rejected(e.reason(), e.getMessage());
        }
    }

    // ── Emission ────────────────────────────────────────────────────────────

    private String emit(ExcelExpr expr, int templateRow, Set<Integer> deps) {
        return switch (expr) {
            case NumberLit n -> formatNumber(n.value());
            case BoolLit b -> String.valueOf(b.value());
            case CellRef ref -> emitCellRef(ref, templateRow, deps);
            case RangeRef r -> throw new UnsupportedFormulaException(UNSUPPORTED_RANGE_SHAPE,
                    "A range reference may only appear directly as an aggregate-function argument");
            case UnaryNeg u -> "-(" + emit(u.operand(), templateRow, deps) + ")";
            case Percent p -> "((" + emit(p.operand(), templateRow, deps) + ")/100)";
            case BinaryOp b -> "(" + emit(b.left(), templateRow, deps) + " " + jsonataOp(b.op()) + " "
                    + emit(b.right(), templateRow, deps) + ")";
            case FuncCall call -> emitFuncCall(call, templateRow, deps);
        };
    }

    private String emitCellRef(CellRef ref, int templateRow, Set<Integer> deps) {
        if (!ref.rowAbsolute()) {
            if (ref.row() != templateRow) {
                throw new UnsupportedFormulaException(CROSS_ROW_REFERENCE,
                        "Formula references a different row (relative reference not on its own row), "
                        + "which is not supported in v1");
            }
            String field = fieldNamesByCol.get(ref.col());
            if (field == null) {
                throw new UnsupportedFormulaException(UNRESOLVED_REFERENCE,
                        "Reference to column outside the detected table");
            }
            deps.add(ref.col());
            // $parent, not a bare field name: a $.items[*].x derivation's own context does not
            // resolve a bare sibling field — it fails SILENTLY (no error, just a missing/null
            // result), a known trap in this engine's wildcard-derivation semantics. $parent is how
            // such a derivation reaches its own item's other fields.
            return "$parent." + field;
        }
        return "$const." + resolveConstant(ref);
    }

    private String resolveConstant(CellRef ref) {
        long key = (long) ref.row() * 100_000 + ref.col();
        String existing = constantKeyToName.get(key);
        if (existing != null) return existing;

        CellValue value = grid.valueAt(ref.row(), ref.col());
        if (!(value instanceof CellValue.NumberValue nv)) {
            throw new UnsupportedFormulaException(UNRESOLVED_REFERENCE,
                    "Absolute reference does not point at a numeric constant cell");
        }
        String name = constantNameFor(ref);
        // Dedup by name: a different cell landing on the same derived name (e.g. two "Rate" labels)
        // gets disambiguated. At this point the (row,col) key is known new (checked above), so any
        // existing entry under this name belongs to a different cell.
        String unique = name;
        int suffix = 2;
        while (constants.containsKey(unique)) {
            unique = name + suffix++;
        }
        constants.put(unique, nv.value());
        constantKeyToName.put(key, unique);
        return unique;
    }

    /** Left-adjacent text label, lowerCamelCased, or a deterministic positional fallback (§5.4). */
    private String constantNameFor(CellRef ref) {
        if (ref.col() > 0) {
            CellValue left = grid.valueAt(ref.row(), ref.col() - 1);
            if (left instanceof CellValue.StringValue s && !s.value().isBlank()) {
                return toFieldName(s.value());
            }
        }
        return "const" + TableDetector.columnLetter(ref.col()) + (ref.row() + 1);
    }

    private String emitFuncCall(FuncCall call, int templateRow, Set<Integer> deps) {
        String name = call.name();
        if ("IF".equals(name) && call.args().size() == 3) {
            return "(" + emit(call.args().get(0), templateRow, deps) + " ? "
                    + emit(call.args().get(1), templateRow, deps) + " : "
                    + emit(call.args().get(2), templateRow, deps) + ")";
        }
        if ("AND".equals(name) || "OR".equals(name)) {
            String joiner = "AND".equals(name) ? " and " : " or ";
            if (call.args().isEmpty()) {
                throw new UnsupportedFormulaException(UNSUPPORTED_FUNCTION, name + "() needs at least one argument");
            }
            StringBuilder sb = new StringBuilder("(");
            for (int i = 0; i < call.args().size(); i++) {
                if (i > 0) sb.append(joiner);
                sb.append(emit(call.args().get(i), templateRow, deps));
            }
            return sb.append(")").toString();
        }
        if ("NOT".equals(name) && call.args().size() == 1) {
            return "$not(" + emit(call.args().get(0), templateRow, deps) + ")";
        }
        if (SIMPLE_FUNCS.containsKey(name)) {
            return emitPlainFuncCall(SIMPLE_FUNCS.get(name), call, templateRow, deps);
        }
        if (isAggregateFunc(name)) {
            return emitRowLocalAggregate(name, call, templateRow, deps);
        }
        throw new UnsupportedFormulaException(UNSUPPORTED_FUNCTION,
                "Function '" + name + "' is not supported in v1");
    }

    private String emitPlainFuncCall(String jsonataName, FuncCall call, int templateRow, Set<Integer> deps) {
        StringBuilder sb = new StringBuilder(jsonataName).append("(");
        for (int i = 0; i < call.args().size(); i++) {
            if (i > 0) sb.append(", ");
            sb.append(emit(call.args().get(i), templateRow, deps));
        }
        return sb.append(")").toString();
    }

    private static boolean isAggregateFunc(String name) {
        return switch (name) {
            case "SUM", "AVERAGE", "MIN", "MAX", "COUNT" -> true;
            default -> false;
        };
    }

    private String emitRowLocalAggregate(String excelName, FuncCall call, int templateRow, Set<Integer> deps) {
        if (call.args().size() != 1 || !(call.args().get(0) instanceof RangeRef range)) {
            throw new UnsupportedFormulaException(UNSUPPORTED_FUNCTION,
                    excelName + "() is only supported with a single range argument in v1");
        }
        if (range.from().row() != templateRow || range.to().row() != templateRow
                || range.from().rowAbsolute() || range.to().rowAbsolute()) {
            throw new UnsupportedFormulaException(UNSUPPORTED_RANGE_SHAPE,
                    excelName + "(" + describeRange(range) + "): only a same-row (horizontal) range is "
                    + "supported inside a per-row formula in v1 — a whole-column total belongs in a "
                    + "separate summary cell outside the table");
        }
        int fromCol = range.from().col();
        int toCol = range.to().col();
        if (fromCol > toCol) { int tmp = fromCol; fromCol = toCol; toCol = tmp; }

        List<String> terms = new java.util.ArrayList<>();
        for (int c = fromCol; c <= toCol; c++) {
            String field = fieldNamesByCol.get(c);
            if (field == null) {
                throw new UnsupportedFormulaException(UNRESOLVED_REFERENCE,
                        "Range in " + excelName + "() includes a column outside the detected table");
            }
            deps.add(c);
            terms.add("$parent." + field); // same silent-failure trap as emitCellRef — see its comment
        }
        return switch (excelName) {
            case "SUM" -> "(" + String.join(" + ", terms) + ")";
            case "AVERAGE" -> "$average([" + String.join(", ", terms) + "])";
            case "MIN" -> "$min([" + String.join(", ", terms) + "])";
            case "MAX" -> "$max([" + String.join(", ", terms) + "])";
            case "COUNT" -> "$count([" + String.join(", ", terms) + "])";
            default -> throw new IllegalStateException("unreachable: " + excelName);
        };
    }

    private static String describeRange(RangeRef r) {
        return TableDetector.columnLetter(r.from().col()) + (r.from().row() + 1) + ":"
                + TableDetector.columnLetter(r.to().col()) + (r.to().row() + 1);
    }

    private static String jsonataOp(String excelOp) {
        return switch (excelOp) {
            case "=" -> "=";
            case "<>" -> "!=";
            case "^" -> "**";
            default -> excelOp; // + - * / < > <= >= already match JSONata
        };
    }

    private static String formatNumber(double v) {
        if (v == Math.rint(v) && !Double.isInfinite(v)) return String.valueOf((long) v);
        return String.valueOf(v);
    }

    /**
     * Header text -> lowerCamelCase field name, e.g. "Unit Price" -> "unitPrice".
     *
     * <p>Never returns a name starting with a digit: the result is spliced unquoted into JSONata
     * paths ({@code $parent.<field>}, {@code $.items[*].<field>}), where a leading digit is a parse
     * error — a header like "2024 Revenue" or "1st Payment" would otherwise compile to a syntax
     * error far from this code instead of the named rejection this feature always aims for.
     */
    static String toFieldName(String header) {
        String[] words = header.trim().split("[^A-Za-z0-9]+");
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < words.length; i++) {
            String w = words[i];
            if (w.isEmpty()) continue;
            if (sb.isEmpty()) {
                sb.append(w.substring(0, 1).toLowerCase(Locale.ROOT)).append(w.substring(1));
            } else {
                sb.append(w.substring(0, 1).toUpperCase(Locale.ROOT)).append(w.substring(1));
            }
        }
        if (sb.isEmpty()) return "field";
        if (Character.isDigit(sb.charAt(0))) sb.insert(0, 'f');
        return sb.toString();
    }
}
