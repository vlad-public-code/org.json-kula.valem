package org.json_kula.valem.core.spreadsheet;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.json_kula.valem.core.model.DefaultValueSpec;
import org.json_kula.valem.core.model.DerivationSpec;
import org.json_kula.valem.core.model.ModelSpec;
import org.json_kula.valem.core.model.TestCase;
import org.json_kula.valem.core.spreadsheet.ExcelFormulaTranslator.ColumnOutcome;
import org.json_kula.valem.core.spreadsheet.ExcelFormulaTranslator.ColumnResult;
import org.json_kula.valem.core.spreadsheet.ExcelFormulaTranslator.TranslationResult;
import org.json_kula.valem.core.spreadsheet.TableDetector.TableBounds;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Orchestrates the one deterministic pass (excel-to-spec-v1-design.md §3): detect the table, classify
 * and translate every column, then assemble a {@link ModelSpec} from whatever survives — a column
 * rejected elsewhere on the sheet does not fail the whole compile (vision doc AC-3).
 *
 * <p>Also detects the one whole-column-aggregate shape v1 supports as a standalone summary cell: a
 * formula in the row immediately below the table, in the SAME column as the data it aggregates,
 * whose only argument is a range spanning exactly that column's full data-row span. Deliberately
 * narrow — see {@link #detectSummaryAggregates} — because a general "any aggregate cell anywhere on
 * the sheet" detector is a layout-inference problem of its own (vision doc Open Question 2's sibling).
 */
public final class SpreadsheetCompiler {

    private SpreadsheetCompiler() {}

    public record RejectedColumn(String header, UnsupportedFormulaException.Reason reason, String detail) {}

    public record CompileResult(ModelSpec spec, List<RejectedColumn> rejectedColumns) {}

    private static final List<String> AGGREGATE_FUNCS = List.of("SUM", "AVERAGE", "MIN", "MAX", "COUNT");

    public static CompileResult compile(CellGrid grid, String modelId, ObjectMapper mapper) {
        TableBounds bounds = TableDetector.detect(grid);
        List<ColumnClassification> classifications = ColumnClassifier.classify(grid, bounds);
        TranslationResult translation = new ExcelFormulaTranslator(grid, bounds).translate(classifications);

        List<ColumnResult> surviving = new ArrayList<>();
        List<RejectedColumn> rejected = new ArrayList<>();
        for (ColumnResult cr : translation.columns()) {
            if (cr.outcome() instanceof ColumnOutcome.Rejected r) {
                rejected.add(new RejectedColumn(cr.header(), r.reason(), r.detail()));
            } else {
                surviving.add(cr);
            }
        }
        if (surviving.isEmpty()) {
            throw new UnsupportedFormulaException(UnsupportedFormulaException.Reason.MIXED_CONTENT,
                    "No column could be translated");
        }

        JsonNodeFactory nf = mapper.getNodeFactory();
        ObjectNode schema = buildSchema(nf, surviving);
        List<DerivationSpec> derivations = buildDerivations(surviving);
        List<TestCase> tests = new ArrayList<>();
        DefaultValueSpec seed = buildSeedDefaultValue(grid, bounds, surviving, mapper, tests, nf);

        List<AggregateResult> aggregates = detectSummaryAggregates(grid, bounds, surviving, schema);
        derivations = new ArrayList<>(derivations);
        for (AggregateResult a : aggregates) {
            derivations.add(a.derivation());
        }
        addAggregateExpectations(grid, aggregates, tests);

        ModelSpec spec = new ModelSpec(
                modelId, "1.0.0", schema,
                derivations,
                List.of(),               // metaDerivations
                List.of(),               // constraints — nothing in a spreadsheet's cells specifies one (§6)
                tests,
                List.of(seed),
                toConstantsMap(translation.constants(), mapper),
                null,                    // viewDefinition
                List.of(),               // effects
                null, List.of(), null);  // template, lineage, library

        return new CompileResult(spec, rejected);
    }

    // ── Schema ──────────────────────────────────────────────────────────────

    private static ObjectNode buildSchema(JsonNodeFactory nf, List<ColumnResult> surviving) {
        ObjectNode itemProps = nf.objectNode();
        for (ColumnResult cr : surviving) {
            ObjectNode prop = nf.objectNode();
            switch (cr.outcome()) {
                case ColumnOutcome.LiteralField lit -> prop.put("type", jsonTypeOf(lit.kind()));
                case ColumnOutcome.PerItemDerivation ignored -> {
                    prop.put("type", "number");
                    prop.put("readOnly", true);
                }
                case ColumnOutcome.Rejected ignored -> { /* unreachable: not in `surviving` */ }
            }
            itemProps.set(cr.fieldName(), prop);
        }
        ObjectNode itemSchema = nf.objectNode();
        itemSchema.put("type", "object");
        itemSchema.set("properties", itemProps);

        ObjectNode itemsArray = nf.objectNode();
        itemsArray.put("type", "array");
        itemsArray.set("items", itemSchema);

        ObjectNode rootProps = nf.objectNode();
        rootProps.set("items", itemsArray);

        ObjectNode root = nf.objectNode();
        root.put("type", "object");
        root.set("properties", rootProps);
        return root;
    }

    private static String jsonTypeOf(ColumnClassification.LiteralKind kind) {
        return switch (kind) {
            case NUMBER -> "number";
            case BOOLEAN -> "boolean";
            case STRING -> "string";
        };
    }

    // ── Derivations ─────────────────────────────────────────────────────────

    private static List<DerivationSpec> buildDerivations(List<ColumnResult> surviving) {
        List<DerivationSpec> out = new ArrayList<>();
        for (ColumnResult cr : surviving) {
            if (cr.outcome() instanceof ColumnOutcome.PerItemDerivation d) {
                out.add(new DerivationSpec("$.items[*]." + cr.fieldName(), d.jsonataExpr(), null, null));
            }
        }
        return out;
    }

    // ── Seed data (defaultValues "$") + the AC-4 self-test given/expect ───────

    private static DefaultValueSpec buildSeedDefaultValue(CellGrid grid, TableBounds bounds,
            List<ColumnResult> surviving, ObjectMapper mapper, List<TestCase> testsOut, JsonNodeFactory nf) {
        ArrayNode items = nf.arrayNode();
        Map<String, JsonNode> expect = new LinkedHashMap<>();

        int rowIndex = 0;
        for (int row = bounds.firstDataRow(); row <= bounds.lastDataRow(); row++, rowIndex++) {
            ObjectNode item = nf.objectNode();
            for (ColumnResult cr : surviving) {
                int col = bounds.firstCol() + indexOfColumn(bounds, cr);
                if (cr.outcome() instanceof ColumnOutcome.LiteralField) {
                    item.set(cr.fieldName(), literalJson(nf, grid.valueAt(row, col)));
                }
                // formula fields are derived -- not part of the seed, but DO belong in `expect`.
            }
            items.add(item);
            for (ColumnResult cr : surviving) {
                if (cr.outcome() instanceof ColumnOutcome.PerItemDerivation) {
                    int col = bounds.firstCol() + indexOfColumn(bounds, cr);
                    CellValue.Formula f = (CellValue.Formula) grid.valueAt(row, col);
                    expect.put("$.items[" + rowIndex + "]." + cr.fieldName(), literalJson(nf, f.computedValue()));
                }
            }
        }

        ObjectNode seedRoot = nf.objectNode();
        seedRoot.set("items", items);
        String seedJson;
        try {
            seedJson = mapper.writeValueAsString(seedRoot);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to serialize seed data", e);
        }

        if (!expect.isEmpty()) {
            // No `given` mutation: the defaultValues rule above already seeds this exact data at
            // model creation (TestCaseRunner.runOne calls initialize() before applying `given`), so
            // re-sending it as a mutation would be redundant -- and mutating path "$" directly isn't
            // the same operation as the container-creation seed, so it must not be relied on here.
            testsOut.add(new TestCase(
                    "Compiled formulas reproduce the source workbook's own computed values", Map.of(), expect));
        }
        return new DefaultValueSpec("$", seedJson, "Seeded from the uploaded workbook's own rows");
    }

    private static int indexOfColumn(TableBounds bounds, ColumnResult cr) {
        return bounds.headers().indexOf(cr.header());
    }

    private static JsonNode literalJson(JsonNodeFactory nf, CellValue v) {
        return switch (v) {
            case CellValue.NumberValue n -> nf.numberNode(n.value());
            case CellValue.BooleanValue b -> nf.booleanNode(b.value());
            case CellValue.StringValue s -> nf.textNode(s.value());
            case CellValue.Formula f -> literalJson(nf, f.computedValue());
            case CellValue.Empty ignored -> nf.nullNode();
        };
    }

    // ── Whole-column aggregate summary cells (§5.5's second half, scoped down) ─

    /** A detected summary-cell aggregate: the derivation to add, and the summary cell it came from
     *  (so its own computed value can anchor the self-test's {@code expect} — never re-derived). */
    private record AggregateResult(DerivationSpec derivation, int summaryRow, int summaryCol) {}

    private static List<AggregateResult> detectSummaryAggregates(CellGrid grid, TableBounds bounds,
            List<ColumnResult> surviving, ObjectNode schema) {
        int summaryRow = bounds.lastDataRow() + 1;
        if (summaryRow >= grid.rowCount()) return List.of();

        List<AggregateResult> out = new ArrayList<>();
        ObjectNode rootProps = (ObjectNode) schema.get("properties");

        for (ColumnResult cr : surviving) {
            int col = bounds.firstCol() + indexOfColumn(bounds, cr);
            if (!(grid.valueAt(summaryRow, col) instanceof CellValue.Formula f)) continue;

            var parsed = ExcelFormulaParser.parseSafely(f.excelFormula());
            if (parsed.isEmpty()) continue;
            if (!(parsed.get() instanceof org.json_kula.valem.core.spreadsheet.ast.ExcelExpr.FuncCall call)) continue;
            if (!AGGREGATE_FUNCS.contains(call.name()) || call.args().size() != 1) continue;
            if (!(call.args().get(0) instanceof org.json_kula.valem.core.spreadsheet.ast.ExcelExpr.RangeRef range)) continue;
            if (range.from().col() != col || range.to().col() != col) continue;
            if (range.from().row() != bounds.firstDataRow() || range.to().row() != bounds.lastDataRow()) continue;

            String fieldName = call.name().toLowerCase(Locale.ROOT) + capitalize(cr.fieldName());
            String jsonataFunc = switch (call.name()) {
                case "SUM" -> "$sum";
                case "AVERAGE" -> "$average";
                case "MIN" -> "$min";
                case "MAX" -> "$max";
                case "COUNT" -> "$count";
                default -> throw new IllegalStateException();
            };
            rootProps.set(fieldName, JsonNodeFactory.instance.objectNode()
                    .put("type", "number").put("readOnly", true));
            // A dotted path into an already-DERIVED field (`items.<derivedField>`) builds no
            // dependency edge in this engine and silently evaluates to nothing — a documented trap
            // (see the class javadoc). A plain base/literal field has no such issue. So: aggregating
            // a literal column reads it directly; aggregating a formula column recomputes that
            // column's own per-item expression inline inside the aggregate instead of reading the
            // separately-derived field, exactly the pattern the shipped order-items-price-total
            // example already uses for its own grand total.
            String aggregateArg = switch (cr.outcome()) {
                case ColumnOutcome.LiteralField ignored -> "items." + cr.fieldName();
                case ColumnOutcome.PerItemDerivation d -> "items.(" + d.jsonataExpr().replace("$parent.", "") + ")";
                case ColumnOutcome.Rejected ignored -> throw new IllegalStateException("unreachable: not in `surviving`");
            };
            DerivationSpec derivation = new DerivationSpec("$." + fieldName,
                    jsonataFunc + "(" + aggregateArg + ")", null, null);
            out.add(new AggregateResult(derivation, summaryRow, col));
        }
        return out;
    }

    private static void addAggregateExpectations(CellGrid grid, List<AggregateResult> aggregates,
            List<TestCase> tests) {
        if (aggregates.isEmpty() || tests.isEmpty()) return;
        Map<String, JsonNode> given = tests.get(0).given();
        Map<String, JsonNode> expect = new LinkedHashMap<>(tests.get(0).expect());

        for (AggregateResult a : aggregates) {
            if (grid.valueAt(a.summaryRow(), a.summaryCol()) instanceof CellValue.Formula f) {
                expect.put(a.derivation().path(), literalJson(JsonNodeFactory.instance, f.computedValue()));
            }
        }
        tests.set(0, new TestCase(tests.get(0).description(), given, expect));
    }

    private static String capitalize(String s) {
        return s.isEmpty() ? s : s.substring(0, 1).toUpperCase(Locale.ROOT) + s.substring(1);
    }

    private static Map<String, JsonNode> toConstantsMap(Map<String, Double> constants, ObjectMapper mapper) {
        if (constants.isEmpty()) return Map.of();
        Map<String, JsonNode> out = new LinkedHashMap<>();
        constants.forEach((k, v) -> out.put(k, mapper.getNodeFactory().numberNode(v)));
        return out;
    }
}
