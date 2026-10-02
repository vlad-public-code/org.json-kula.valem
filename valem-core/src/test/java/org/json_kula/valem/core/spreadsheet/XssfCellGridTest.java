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
}
