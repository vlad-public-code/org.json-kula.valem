package org.json_kula.valem.core.spreadsheet;

import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.xssf.usermodel.XSSFSheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The real {@code XSSFWorkbook} adapter, exercised against workbooks actually built via POI (not a
 * downloaded fixture — see {@code SpreadsheetGenerateIT} / golden fixtures for real-world files),
 * proving POI's own formula evaluator is what backs {@link CellValue.Formula#computedValue()}.
 */
class XssfCellGridTest {

    private static byte[] toBytes(XSSFWorkbook wb) throws IOException {
        try (ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            wb.write(out);
            return out.toByteArray();
        }
    }

    private static void setCell(Row row, int col, Object value) {
        Cell cell = row.createCell(col);
        if (value instanceof String formula && formula.startsWith("=")) {
            cell.setCellFormula(formula.substring(1));
        } else if (value instanceof String s) {
            cell.setCellValue(s);
        } else if (value instanceof Number n) {
            cell.setCellValue(n.doubleValue());
        } else if (value instanceof Boolean b) {
            cell.setCellValue(b);
        }
    }

    @Test
    void reads_literal_and_formula_cells_with_poi_evaluated_values() throws Exception {
        byte[] bytes;
        try (XSSFWorkbook wb = new XSSFWorkbook()) {
            XSSFSheet sheet = wb.createSheet();
            Row header = sheet.createRow(0);
            setCell(header, 0, "Quantity");
            setCell(header, 1, "Price");
            setCell(header, 2, "Total");

            Row r1 = sheet.createRow(1);
            setCell(r1, 0, 2);
            setCell(r1, 1, 10);
            setCell(r1, 2, "=A2*B2");

            Row r2 = sheet.createRow(2);
            setCell(r2, 0, 3);
            setCell(r2, 1, 20);
            setCell(r2, 2, "=A3*B3");

            bytes = toBytes(wb);
        }

        try (XSSFWorkbook wb = new XSSFWorkbook(new ByteArrayInputStream(bytes))) {
            XssfCellGrid grid = new XssfCellGrid(wb);

            assertThat(grid.rowCount()).isEqualTo(3);
            assertThat(grid.columnCount()).isEqualTo(3);
            assertThat(grid.valueAt(0, 0)).isEqualTo(new CellValue.StringValue("Quantity"));
            assertThat(grid.valueAt(1, 0)).isEqualTo(new CellValue.NumberValue(2));

            CellValue totalCell = grid.valueAt(1, 2);
            assertThat(totalCell).isInstanceOf(CellValue.Formula.class);
            CellValue.Formula f = (CellValue.Formula) totalCell;
            assertThat(f.excelFormula()).isEqualTo("A2*B2");
            assertThat(f.computedValue()).isEqualTo(new CellValue.NumberValue(20));
        }
    }

    @Test
    void absent_cells_are_empty() throws Exception {
        byte[] bytes;
        try (XSSFWorkbook wb = new XSSFWorkbook()) {
            XSSFSheet sheet = wb.createSheet();
            setCell(sheet.createRow(0), 0, "A");
            bytes = toBytes(wb);
        }
        try (XSSFWorkbook wb = new XSSFWorkbook(new ByteArrayInputStream(bytes))) {
            XssfCellGrid grid = new XssfCellGrid(wb);
            assertThat(grid.valueAt(5, 5)).isEqualTo(CellValue.EMPTY);
            assertThat(grid.valueAt(0, 5)).isEqualTo(CellValue.EMPTY);
        }
    }

    @Test
    void boolean_formula_result_is_read_correctly() throws Exception {
        byte[] bytes;
        try (XSSFWorkbook wb = new XSSFWorkbook()) {
            XSSFSheet sheet = wb.createSheet();
            Row header = sheet.createRow(0);
            setCell(header, 0, "Value");
            setCell(header, 1, "IsPositive");
            Row r1 = sheet.createRow(1);
            setCell(r1, 0, 5);
            setCell(r1, 1, "=A2>0");
            bytes = toBytes(wb);
        }
        try (XSSFWorkbook wb = new XSSFWorkbook(new ByteArrayInputStream(bytes))) {
            XssfCellGrid grid = new XssfCellGrid(wb);
            CellValue.Formula f = (CellValue.Formula) grid.valueAt(1, 1);
            assertThat(f.computedValue()).isEqualTo(new CellValue.BooleanValue(true));
        }
    }

    @Test
    void full_pipeline_compiles_a_real_poi_built_workbook_end_to_end() throws Exception {
        byte[] bytes;
        try (XSSFWorkbook wb = new XSSFWorkbook()) {
            XSSFSheet sheet = wb.createSheet();
            Row header = sheet.createRow(0);
            setCell(header, 0, "Quantity");
            setCell(header, 1, "Price");
            setCell(header, 2, "Total");

            Row r1 = sheet.createRow(1);
            setCell(r1, 0, 2); setCell(r1, 1, 10); setCell(r1, 2, "=A2*B2");
            Row r2 = sheet.createRow(2);
            setCell(r2, 0, 3); setCell(r2, 1, 20); setCell(r2, 2, "=A3*B3");

            bytes = toBytes(wb);
        }

        try (XSSFWorkbook wb = new XSSFWorkbook(new ByteArrayInputStream(bytes))) {
            XssfCellGrid grid = new XssfCellGrid(wb);
            SpreadsheetCompiler.CompileResult result = SpreadsheetCompiler.compile(
                    grid, "poi-built", new com.fasterxml.jackson.databind.ObjectMapper());

            assertThat(result.rejectedColumns()).isEmpty();
            java.util.List<org.json_kula.valem.core.engine.TestCaseRunner.TestResult> testResults =
                    org.json_kula.valem.core.engine.TestCaseRunner.run(result.spec(), result.spec().tests());
            assertThat(testResults).hasSize(1);
            assertThat(testResults.get(0).passed()).as("failures: %s", testResults.get(0).failures()).isTrue();
        }
    }

    /** Text-function concatenation and a "percent of total" whole-column aggregate, both inside a
     *  per-row formula, checked against POI's own evaluator as the independent oracle — not just
     *  hand-set "computed" values as in the compiler-level unit tests. */
    @Test
    void full_pipeline_compiles_text_and_whole_column_aggregate_formulas_from_a_real_poi_workbook() throws Exception {
        byte[] bytes;
        try (XSSFWorkbook wb = new XSSFWorkbook()) {
            XSSFSheet sheet = wb.createSheet();
            Row header = sheet.createRow(0);
            setCell(header, 0, "First"); setCell(header, 1, "Last"); setCell(header, 2, "Price");
            setCell(header, 3, "FullName"); setCell(header, 4, "ShareOfTotal");

            Row r1 = sheet.createRow(1);
            setCell(r1, 0, "Ada"); setCell(r1, 1, "Lovelace"); setCell(r1, 2, 10);
            setCell(r1, 3, "=A2&\" \"&B2"); setCell(r1, 4, "=C2/SUM($C$2:$C$3)");
            Row r2 = sheet.createRow(2);
            setCell(r2, 0, "Alan"); setCell(r2, 1, "Turing"); setCell(r2, 2, 30);
            setCell(r2, 3, "=A3&\" \"&B3"); setCell(r2, 4, "=C3/SUM($C$2:$C$3)");

            bytes = toBytes(wb);
        }

        try (XSSFWorkbook wb = new XSSFWorkbook(new ByteArrayInputStream(bytes))) {
            XssfCellGrid grid = new XssfCellGrid(wb);
            SpreadsheetCompiler.CompileResult result = SpreadsheetCompiler.compile(
                    grid, "poi-built-text", new com.fasterxml.jackson.databind.ObjectMapper());

            assertThat(result.rejectedColumns()).isEmpty();
            java.util.List<org.json_kula.valem.core.engine.TestCaseRunner.TestResult> testResults =
                    org.json_kula.valem.core.engine.TestCaseRunner.run(result.spec(), result.spec().tests());
            assertThat(testResults).hasSize(1);
            assertThat(testResults.get(0).passed()).as("failures: %s", testResults.get(0).failures()).isTrue();
        }
    }

    /** VLOOKUP against a real POI-evaluated side table, checked against POI's own VLOOKUP
     *  evaluator (not just the hand-set "computed" values the unit tests use). */
    @Test
    void full_pipeline_compiles_vlookup_against_a_side_table_from_a_real_poi_workbook() throws Exception {
        byte[] bytes;
        try (XSSFWorkbook wb = new XSSFWorkbook()) {
            XSSFSheet sheet = wb.createSheet();
            Row header = sheet.createRow(0);
            setCell(header, 0, "Category"); setCell(header, 1, "Rate");
            setCell(header, 5, "A"); setCell(header, 6, 10);

            // Row 2 (Excel) carries both the main table's data row AND the side table's 2nd row --
            // different, non-overlapping columns on the same physical row.
            Row r1 = sheet.createRow(1);
            setCell(r1, 0, "B"); setCell(r1, 1, "=VLOOKUP(A2,$F$1:$G$2,2,FALSE)");
            setCell(r1, 5, "B"); setCell(r1, 6, 20);

            Row r2 = sheet.createRow(2);
            setCell(r2, 0, "A"); setCell(r2, 1, "=VLOOKUP(A3,$F$1:$G$2,2,FALSE)");

            bytes = toBytes(wb);
        }

        try (XSSFWorkbook wb = new XSSFWorkbook(new ByteArrayInputStream(bytes))) {
            XssfCellGrid grid = new XssfCellGrid(wb);
            SpreadsheetCompiler.CompileResult result = SpreadsheetCompiler.compile(
                    grid, "poi-built-vlookup", new com.fasterxml.jackson.databind.ObjectMapper());

            assertThat(result.rejectedColumns()).isEmpty();
            assertThat(result.spec().library()).isNotNull();
            java.util.List<org.json_kula.valem.core.engine.TestCaseRunner.TestResult> testResults =
                    org.json_kula.valem.core.engine.TestCaseRunner.run(result.spec(), result.spec().tests());
            assertThat(testResults).hasSize(1);
            assertThat(testResults.get(0).passed()).as("failures: %s", testResults.get(0).failures()).isTrue();
        }
    }

    /** SUMPRODUCT (a non-CSE weighted-sum/dot-product, the single most common real-world use of
     *  array-formula-style element-wise computation), checked against POI's own evaluator. */
    @Test
    void full_pipeline_compiles_sumproduct_from_a_real_poi_workbook() throws Exception {
        byte[] bytes;
        try (XSSFWorkbook wb = new XSSFWorkbook()) {
            XSSFSheet sheet = wb.createSheet();
            Row header = sheet.createRow(0);
            setCell(header, 0, "Qty"); setCell(header, 1, "Price");
            Row r1 = sheet.createRow(1);
            setCell(r1, 0, 2); setCell(r1, 1, 10);
            Row r2 = sheet.createRow(2);
            setCell(r2, 0, 3); setCell(r2, 1, 20);
            Row summary = sheet.createRow(3);
            setCell(summary, 1, "=SUMPRODUCT($A$2:$A$3,$B$2:$B$3)");

            bytes = toBytes(wb);
        }

        try (XSSFWorkbook wb = new XSSFWorkbook(new ByteArrayInputStream(bytes))) {
            XssfCellGrid grid = new XssfCellGrid(wb);
            SpreadsheetCompiler.CompileResult result = SpreadsheetCompiler.compile(
                    grid, "poi-built-sumproduct", new com.fasterxml.jackson.databind.ObjectMapper());

            assertThat(result.rejectedColumns()).isEmpty();
            java.util.List<org.json_kula.valem.core.engine.TestCaseRunner.TestResult> testResults =
                    org.json_kula.valem.core.engine.TestCaseRunner.run(result.spec(), result.spec().tests());
            assertThat(testResults).hasSize(1);
            assertThat(testResults.get(0).passed()).as("failures: %s", testResults.get(0).failures()).isTrue();
        }
    }

    /**
     * Legacy CSE ({@code Ctrl+Shift+Enter}) array formulas are not translated in v1 — confirmed here
     * to fail SAFELY (a named rejection), not silently or by crashing. POI stores the SAME
     * whole-range formula text, unchanged, on every cell of a multi-cell array-formula group (e.g.
     * {@code A2:A3*2} on both B2 and B3, not row-shifted the way an ordinary fill-down formula
     * would be) — so the uniform-shape check sees genuinely different normalized shapes per row and
     * rejects it as {@code NON_UNIFORM_FORMULA}, before the translator's own "a range may only
     * appear as an aggregate-function argument" check would even get a chance to run.
     */
    @Test
    void a_legacy_cse_array_formula_is_rejected_safely_not_silently_or_by_crashing() throws Exception {
        byte[] bytes;
        try (XSSFWorkbook wb = new XSSFWorkbook()) {
            XSSFSheet sheet = wb.createSheet();
            Row header = sheet.createRow(0);
            setCell(header, 0, "A"); setCell(header, 1, "Doubled");
            Row r1 = sheet.createRow(1);
            setCell(r1, 0, 5);
            Row r2 = sheet.createRow(2);
            setCell(r2, 0, 7);
            // As if the user selected B2:B3 and entered {=A2:A3*2} with Ctrl+Shift+Enter.
            sheet.setArrayFormula("A2:A3*2", org.apache.poi.ss.util.CellRangeAddress.valueOf("B2:B3"));

            bytes = toBytes(wb);
        }

        try (XSSFWorkbook wb = new XSSFWorkbook(new ByteArrayInputStream(bytes))) {
            XssfCellGrid grid = new XssfCellGrid(wb);
            SpreadsheetCompiler.CompileResult result = SpreadsheetCompiler.compile(
                    grid, "poi-built-cse", new com.fasterxml.jackson.databind.ObjectMapper());

            assertThat(result.rejectedColumns()).hasSize(1);
            assertThat(result.rejectedColumns().get(0).header()).isEqualTo("Doubled");
            assertThat(result.rejectedColumns().get(0).reason())
                    .isEqualTo(UnsupportedFormulaException.Reason.NON_UNIFORM_FORMULA);
        }
    }
}
