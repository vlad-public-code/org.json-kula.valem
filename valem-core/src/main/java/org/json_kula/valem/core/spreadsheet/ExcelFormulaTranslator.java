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

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import static org.json_kula.valem.core.spreadsheet.UnsupportedFormulaException.Reason.CROSS_ROW_REFERENCE;
import static org.json_kula.valem.core.spreadsheet.UnsupportedFormulaException.Reason.DEPENDS_ON_REJECTED_COLUMN;
import static org.json_kula.valem.core.spreadsheet.UnsupportedFormulaException.Reason.LOOKUP_TABLE_CONTAINS_FORMULA;
import static org.json_kula.valem.core.spreadsheet.UnsupportedFormulaException.Reason.UNRESOLVED_REFERENCE;
import static org.json_kula.valem.core.spreadsheet.UnsupportedFormulaException.Reason.UNSUPPORTED_FUNCTION;
import static org.json_kula.valem.core.spreadsheet.UnsupportedFormulaException.Reason.UNSUPPORTED_LOOKUP_MODE;
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
        /** {@code resultType} drives the derived field's JSON Schema {@code type} (§5.4a) — inferred
         *  from the formula's own AST, not hardcoded to "number" as in the pre-text-function version.
         *  A CellRef into another FORMULA column (as opposed to a literal column) defaults to NUMBER:
         *  full cross-formula type propagation isn't implemented in v1 (see {@code inferResultType}). */
        record PerItemDerivation(String jsonataExpr, Set<Integer> dependsOnCols,
                ColumnClassification.LiteralKind resultType) implements ColumnOutcome {}
        record Rejected(UnsupportedFormulaException.Reason reason, String detail) implements ColumnOutcome {}
    }

    /** {@code col} is the absolute 0-based column index — never re-derived by header-text lookup,
     *  which would silently resolve to the WRONG column whenever two columns share a header (or both
     *  fall back to the same {@link #toFieldName} default). */
    public record ColumnResult(int col, String header, String fieldName, ColumnOutcome outcome) {}

    /** {@code lookupTables} are VLOOKUP's {@code table_array} blocks, read verbatim from the grid and
     *  keyed by the library-export name {@code emitVlookup} assigned them (§5.2a); each row is the
     *  table_array's own columns left to right. Assembled into the spec's {@code library} by
     *  {@code SpreadsheetCompiler}, not here — this class stays free of any JSON-building concern. */
    public record TranslationResult(List<ColumnResult> columns, Map<String, Double> constants,
            Map<String, List<List<CellValue>>> lookupTables) {}

    private static final Map<String, String> SIMPLE_FUNCS = Map.of(
            "ABS", "$abs", "ROUND", "$round",
            "UPPER", "$uppercase", "LOWER", "$lowercase", "TRIM", "$trim", "LEN", "$length");

    private final CellGrid grid;
    private final TableDetector.TableBounds bounds;
    private final Map<Integer, String> fieldNamesByCol = new LinkedHashMap<>();
    private final Map<String, Double> constants = new LinkedHashMap<>();
    private final Map<Long, String> constantKeyToName = new LinkedHashMap<>();
    /** Known upfront for every LITERAL column, regardless of which column is processed first —
     *  needed so a formula column can infer its own result type even when it references a literal
     *  column to its RIGHT (not yet reached by the left-to-right translation loop). */
    private final Map<Integer, ColumnClassification.LiteralKind> literalKindByCol = new LinkedHashMap<>();
    private final Map<String, List<List<CellValue>>> lookupTables = new LinkedHashMap<>();
    private final Map<String, String> lookupTableKeyToName = new LinkedHashMap<>();

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

        for (int col = firstCol; col <= bounds.lastCol(); col++) {
            if (classifications.get(col - firstCol) instanceof ColumnClassification.Literal lit) {
                literalKindByCol.put(col, lit.kind());
            }
        }

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
        return new TranslationResult(results, constants, lookupTables);
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
            return new ColumnOutcome.PerItemDerivation(expr, deps, inferResultType(ast));
        } catch (UnsupportedFormulaException e) {
            return new ColumnOutcome.Rejected(e.reason(), e.getMessage());
        }
    }

    // ── Emission ────────────────────────────────────────────────────────────

    private String emit(ExcelExpr expr, int templateRow, Set<Integer> deps) {
        return switch (expr) {
            case NumberLit n -> formatNumber(n.value());
            case BoolLit b -> String.valueOf(b.value());
            case StringLit s -> emitStringLit(s.value());
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
        if ("CONCATENATE".equals(name)) {
            if (call.args().isEmpty()) {
                throw new UnsupportedFormulaException(UNSUPPORTED_FUNCTION,
                        "CONCATENATE() needs at least one argument");
            }
            StringBuilder sb = new StringBuilder("(");
            for (int i = 0; i < call.args().size(); i++) {
                if (i > 0) sb.append(" & ");
                sb.append(emit(call.args().get(i), templateRow, deps));
            }
            return sb.append(")").toString();
        }
        if ("LEFT".equals(name) || "RIGHT".equals(name)) {
            return emitLeftRight(name, call, templateRow, deps);
        }
        if ("MID".equals(name) && call.args().size() == 3) {
            String text = emit(call.args().get(0), templateRow, deps);
            String start = emit(call.args().get(1), templateRow, deps);
            String len = emit(call.args().get(2), templateRow, deps);
            return "$substring(" + text + ", (" + start + ") - 1, " + len + ")";
        }
        if (SIMPLE_FUNCS.containsKey(name)) {
            return emitPlainFuncCall(SIMPLE_FUNCS.get(name), call, templateRow, deps);
        }
        if (isAggregateFunc(name)) {
            return emitRowLocalAggregate(name, call, templateRow, deps);
        }
        if ("VLOOKUP".equals(name)) {
            return emitVlookup(call, templateRow, deps);
        }
        throw new UnsupportedFormulaException(UNSUPPORTED_FUNCTION,
                "Function '" + name + "' is not supported in v1");
    }

    /**
     * {@code VLOOKUP(lookup_value, table_array, col_index_num, range_lookup)} — exact match only
     * (§5.2a). {@code table_array} must be a fully {@code $}-locked range: a small reference table
     * sitting elsewhere on the sheet, read verbatim and embedded in the spec's {@code library} as a
     * named static export (measured empirically to resolve correctly from inside a {@code
     * $.items[*].x} derivation, the same discipline as the {@code $$} probe for whole-column
     * aggregates — see excel-to-spec-v1-design.md §16). Approximate match (the 4th argument omitted
     * or {@code TRUE}) is rejected by name: it requires the lookup column to be sorted ascending,
     * which cannot be verified at compile time, and guessing would risk a silently wrong result —
     * exactly what this feature's whole design exists to avoid.
     */
    private String emitVlookup(FuncCall call, int templateRow, Set<Integer> deps) {
        if (call.args().size() != 4) {
            throw new UnsupportedFormulaException(UNSUPPORTED_FUNCTION,
                    "VLOOKUP() must be called with all 4 arguments in v1, including an explicit "
                    + "FALSE for exact match");
        }
        ExcelExpr lookupValueExpr = call.args().get(0);
        ExcelExpr tableExpr = call.args().get(1);
        ExcelExpr colIndexExpr = call.args().get(2);
        ExcelExpr rangeLookupExpr = call.args().get(3);

        if (!(rangeLookupExpr instanceof BoolLit exact) || exact.value()) {
            throw new UnsupportedFormulaException(UNSUPPORTED_LOOKUP_MODE,
                    "VLOOKUP()'s 4th argument must be a literal FALSE in v1 — approximate match "
                    + "(TRUE or omitted) requires the lookup column to be sorted ascending, which "
                    + "cannot be verified at compile time");
        }
        if (!(tableExpr instanceof RangeRef table) || !table.from().rowAbsolute() || !table.from().colAbsolute()
                || !table.to().rowAbsolute() || !table.to().colAbsolute()) {
            throw new UnsupportedFormulaException(UNSUPPORTED_RANGE_SHAPE,
                    "VLOOKUP()'s table_array must be a fully $-locked range (e.g. $B$2:$D$10) in v1");
        }
        if (!(colIndexExpr instanceof NumberLit colLit) || colLit.value() != Math.rint(colLit.value())) {
            throw new UnsupportedFormulaException(UNSUPPORTED_RANGE_SHAPE,
                    "VLOOKUP()'s col_index_num must be a literal whole number in v1");
        }

        int fromCol = Math.min(table.from().col(), table.to().col());
        int toCol = Math.max(table.from().col(), table.to().col());
        int colIndex = (int) colLit.value();
        int tableCols = toCol - fromCol + 1;
        if (colIndex < 1 || colIndex > tableCols) {
            throw new UnsupportedFormulaException(UNSUPPORTED_RANGE_SHAPE,
                    "VLOOKUP()'s col_index_num " + colIndex + " is outside table_array's " + tableCols
                    + " column(s)");
        }

        String tableName = resolveLookupTable(table, fromCol, toCol);
        String lookupValueJsonata = emit(lookupValueExpr, templateRow, deps);
        return "($" + tableName + "[c1 = (" + lookupValueJsonata + ")].c" + colIndex + ")[0]";
    }

    /** Reads {@code table}'s cells verbatim (deduped by its absolute coordinates, same discipline as
     *  {@code resolveConstant}), rejecting a formula cell inside it by name rather than guessing what
     *  it would evaluate to — a lookup table embedded as a static library export must itself be
     *  static. Row objects are keyed {@code c1, c2, …} (1-based, matching VLOOKUP's own
     *  {@code col_index_num} numbering directly); {@code c1} is always the lookup key column. */
    private String resolveLookupTable(RangeRef table, int fromCol, int toCol) {
        int fromRow = Math.min(table.from().row(), table.to().row());
        int toRow = Math.max(table.from().row(), table.to().row());
        String key = fromRow + ":" + fromCol + ":" + toRow + ":" + toCol;
        String existing = lookupTableKeyToName.get(key);
        if (existing != null) return existing;

        List<List<CellValue>> rows = new java.util.ArrayList<>();
        for (int r = fromRow; r <= toRow; r++) {
            List<CellValue> rowValues = new java.util.ArrayList<>();
            for (int c = fromCol; c <= toCol; c++) {
                CellValue v = grid.valueAt(r, c);
                if (v instanceof CellValue.Formula) {
                    throw new UnsupportedFormulaException(LOOKUP_TABLE_CONTAINS_FORMULA,
                            "VLOOKUP()'s table_array contains a formula cell at row " + (r + 1)
                            + ", column " + TableDetector.columnLetter(c)
                            + " — only a literal lookup table is supported in v1");
                }
                rowValues.add(v);
            }
            rows.add(rowValues);
        }

        String name = "lookup" + TableDetector.columnLetter(fromCol) + (fromRow + 1);
        String unique = name;
        int suffix = 2;
        while (lookupTables.containsKey(unique)) {
            unique = name + suffix++;
        }
        lookupTables.put(unique, rows);
        lookupTableKeyToName.put(key, unique);
        return unique;
    }

    /** {@code LEFT(text[,n])} / {@code RIGHT(text[,n])} — Excel defaults {@code n} to 1 when omitted. */
    private String emitLeftRight(String name, FuncCall call, int templateRow, Set<Integer> deps) {
        if (call.args().isEmpty() || call.args().size() > 2) {
            throw new UnsupportedFormulaException(UNSUPPORTED_FUNCTION, name + "() takes 1 or 2 arguments");
        }
        String text = emit(call.args().get(0), templateRow, deps);
        String n = call.args().size() == 2 ? emit(call.args().get(1), templateRow, deps) : "1";
        return "LEFT".equals(name)
                ? "$substring(" + text + ", 0, " + n + ")"
                : "$substring(" + text + ", $length(" + text + ") - (" + n + "), " + n + ")";
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
        boolean sameRow = !range.from().rowAbsolute() && !range.to().rowAbsolute()
                && range.from().row() == templateRow && range.to().row() == templateRow;
        if (sameRow) {
            return emitSameRowRangeAggregate(excelName, range, deps);
        }
        boolean wholeColumnOverFullSpan = range.from().col() == range.to().col()
                && range.from().rowAbsolute() && range.to().rowAbsolute()
                && range.from().row() == bounds.firstDataRow() && range.to().row() == bounds.lastDataRow();
        if (wholeColumnOverFullSpan) {
            return emitWholeColumnAggregateInPerRow(excelName, range.from().col(), deps);
        }
        throw new UnsupportedFormulaException(UNSUPPORTED_RANGE_SHAPE,
                excelName + "(" + describeRange(range) + "): only a same-row (horizontal) range, or a "
                + "$-locked whole-column range over the table's full data span, is supported inside a "
                + "per-row formula in v1");
    }

    private String emitSameRowRangeAggregate(String excelName, RangeRef range, Set<Integer> deps) {
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
        return aggregateOverTerms(excelName, terms);
    }

    /**
     * A whole-column aggregate used <b>inside</b> a per-row formula (e.g. a "percent of total"
     * pattern: {@code =C2/SUM($C$2:$C$10)} on every row) — distinct from the standalone summary-cell
     * case {@code SpreadsheetCompiler.detectSummaryAggregates} handles.
     *
     * <p>{@code $$} reliably resolves to the document root even from inside a wildcard derivation's
     * own per-item context (measured empirically against the real engine — unlike a bare sibling
     * field name, this is <b>not</b> one of this engine's silent-failure traps), so the aggregate
     * reads every item's own field directly: {@code $sum($$.items.field)}.
     *
     * <p>Restricted to a <b>literal</b> target column in v1: aggregating a column that is itself a
     * formula would need to recompute that formula's own per-item expression inline (the same trick
     * {@code detectSummaryAggregates} uses) while already being nested inside a *different* per-row
     * formula — a materially bigger case, deferred rather than guessed.
     */
    private String emitWholeColumnAggregateInPerRow(String excelName, int col, Set<Integer> deps) {
        String field = fieldNamesByCol.get(col);
        if (field == null) {
            throw new UnsupportedFormulaException(UNRESOLVED_REFERENCE,
                    "Whole-column range in " + excelName + "() includes a column outside the detected table");
        }
        if (!literalKindByCol.containsKey(col)) {
            throw new UnsupportedFormulaException(UNSUPPORTED_RANGE_SHAPE,
                    excelName + "(): a whole-column range inside a per-row formula is only supported "
                    + "when it aggregates a literal column in v1, not a formula column");
        }
        deps.add(col);
        String jsonataFunc = switch (excelName) {
            case "SUM" -> "$sum";
            case "AVERAGE" -> "$average";
            case "MIN" -> "$min";
            case "MAX" -> "$max";
            case "COUNT" -> "$count";
            default -> throw new IllegalStateException("unreachable: " + excelName);
        };
        return jsonataFunc + "($$.items." + field + ")";
    }

    private static String aggregateOverTerms(String excelName, List<String> terms) {
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

    /** JSONata string-literal syntax mirrors JSON's — backslash and double-quote need escaping. */
    private static String emitStringLit(String value) {
        StringBuilder sb = new StringBuilder("\"");
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> sb.append(c);
            }
        }
        return sb.append('"').toString();
    }

    // ── Result-type inference (drives the derived field's JSON Schema "type", §5.4a) ──────────

    /**
     * Infers a formula's result type from its own AST. A {@link CellRef} into another FORMULA
     * column (as opposed to a LITERAL one) defaults to NUMBER — full cross-formula type propagation
     * (e.g. a formula-of-a-formula text chain) is a v1 boundary, not implemented. This only affects
     * the derived field's declared schema {@code type}, never the computed value itself, which is
     * always correct regardless (JSONata doesn't type-check a derivation against its own schema
     * entry at evaluation time).
     */
    private ColumnClassification.LiteralKind inferResultType(ExcelExpr expr) {
        return switch (expr) {
            case NumberLit n -> ColumnClassification.LiteralKind.NUMBER;
            case BoolLit b -> ColumnClassification.LiteralKind.BOOLEAN;
            case StringLit s -> ColumnClassification.LiteralKind.STRING;
            case CellRef ref -> ref.rowAbsolute()
                    ? ColumnClassification.LiteralKind.NUMBER // resolveConstant only accepts numeric constants
                    : literalKindByCol.getOrDefault(ref.col(), ColumnClassification.LiteralKind.NUMBER);
            case RangeRef r -> ColumnClassification.LiteralKind.NUMBER; // only valid as an aggregate-func arg
            case UnaryNeg u -> ColumnClassification.LiteralKind.NUMBER;
            case Percent p -> ColumnClassification.LiteralKind.NUMBER;
            case BinaryOp b -> switch (b.op()) {
                case "&" -> ColumnClassification.LiteralKind.STRING;
                case "=", "<>", "<", ">", "<=", ">=" -> ColumnClassification.LiteralKind.BOOLEAN;
                default -> ColumnClassification.LiteralKind.NUMBER; // + - * / ^
            };
            case FuncCall call -> inferFuncResultType(call);
        };
    }

    private ColumnClassification.LiteralKind inferFuncResultType(FuncCall call) {
        return switch (call.name()) {
            case "IF" -> call.args().size() == 3
                    ? inferResultType(call.args().get(1)) : ColumnClassification.LiteralKind.NUMBER;
            case "AND", "OR", "NOT" -> ColumnClassification.LiteralKind.BOOLEAN;
            case "CONCATENATE", "LEFT", "RIGHT", "MID", "UPPER", "LOWER", "TRIM" ->
                    ColumnClassification.LiteralKind.STRING;
            // By the time this runs, emit() has already validated this VLOOKUP call successfully
            // (inferResultType is only ever called after emit() on the same AST, in
            // translateFormulaColumn) -- safe to re-read the same table_array/col_index shape
            // without re-validating it.
            case "VLOOKUP" -> inferVlookupResultType(call);
            default -> ColumnClassification.LiteralKind.NUMBER; // ROUND/ABS/LEN/SUM/AVERAGE/MIN/MAX/COUNT
        };
    }

    private ColumnClassification.LiteralKind inferVlookupResultType(FuncCall call) {
        RangeRef table = (RangeRef) call.args().get(1);
        int colIndex = (int) ((NumberLit) call.args().get(2)).value();
        int fromCol = Math.min(table.from().col(), table.to().col());
        int fromRow = Math.min(table.from().row(), table.to().row());
        CellValue sample = grid.valueAt(fromRow, fromCol + colIndex - 1);
        return switch (sample) {
            case CellValue.StringValue ignored -> ColumnClassification.LiteralKind.STRING;
            case CellValue.BooleanValue ignored -> ColumnClassification.LiteralKind.BOOLEAN;
            default -> ColumnClassification.LiteralKind.NUMBER;
        };
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
