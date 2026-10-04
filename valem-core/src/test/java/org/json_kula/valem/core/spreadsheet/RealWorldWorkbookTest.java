package org.json_kula.valem.core.spreadsheet;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.xssf.usermodel.XSSFSheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.json_kula.valem.core.engine.TestCaseRunner;
import org.json_kula.valem.core.graph.ModelSpecValidator;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A genuine real-world {@code .xlsx}, not a POI-constructed fixture — the independent-oracle
 * discipline of {@code XssfCellGridTest} applied to an actual downloaded government form rather than
 * a workbook built by this test suite itself.
 *
 * <p>Source: the U.S. Small Business Administration's "Cost-Price Analysis and Budget Justification
 * Worksheet" (Office of Small Business Development Centers), downloaded 2026-10-03 from
 * {@code https://legacy.sba.gov/sites/default/files/2020-10/2021%20CPA%20and%20Budget%20Justification%20Worksheet.xlsx}
 * — a U.S. government work, public domain under 17 U.S.C. §105. Committed verbatim as a test
 * resource, the same discipline the sibling document-to-spec feature used for its real IRS PDF.
 */
class RealWorldWorkbookTest {

    private static final String FIXTURE = "/realworld/sba-sbdc-cpa-budget-justification-worksheet.xlsx";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static XSSFWorkbook openFixture() throws Exception {
        try (InputStream in = RealWorldWorkbookTest.class.getResourceAsStream(FIXTURE)) {
            assertThat(in).as("fixture resource " + FIXTURE).isNotNull();
            return new XSSFWorkbook(in);
        }
    }

    /**
     * The real file as a user would actually upload it, unmodified: 51 worksheets, no formulas on
     * sheet 1 ("Instructions" — a numbered list). {@code XssfCellGrid} always reads the workbook's
     * first sheet (vision doc §4's "one worksheet" non-goal), so this is what the pipeline actually
     * sees, not what a human would consider "the interesting part" of this file. This is a genuine
     * v1 limitation, not a hypothetical one: a real multi-sheet government workbook's first tab is
     * an instructions page, and nothing about this feature detects or works around that — the
     * compiler faithfully (if anticlimactically) compiles whatever sheet 1 happens to be.
     */
    @Test
    void the_real_file_as_uploaded_compiles_whatever_its_first_sheet_happens_to_be() throws Exception {
        try (XSSFWorkbook wb = openFixture()) {
            assertThat(wb.getNumberOfSheets()).isGreaterThan(40);
            assertThat(wb.getSheetName(0)).isEqualTo("Instructions");

            XssfCellGrid grid = new XssfCellGrid(wb);
            SpreadsheetCompiler.CompileResult result =
                    SpreadsheetCompiler.compile(grid, "sba-as-uploaded", MAPPER);

            // No formula cells at all on this sheet -- nothing to reject, nothing to self-test.
            assertThat(result.rejectedColumns()).isEmpty();
            JsonNode itemProps = MAPPER.valueToTree(result.spec().schema())
                    .path("properties").path("items").path("items").path("properties");
            assertThat(itemProps.has("instructions")).isTrue();
        }
    }

    /**
     * The sheet a human actually filled in with real data and real formulas ("Example Budget
     * Justification", sheet index 1) -- re-anchored into a standalone single-sheet workbook because
     * {@code XssfCellGrid} only ever reads sheet 0 (the same v1 scope limit the test above
     * demonstrates). Every name, title, dollar amount, and formula string below is transcribed
     * verbatim from the real fixture (see the row/column dump this was built from); only the
     * absolute row numbers change, the same way they would if a person copied these rows to the top
     * of a fresh sheet. The two formula shapes --
     * {@code =SUM(C8:E8)} (row-local SUM) and {@code =(G8/12)*H8*I8} (row-local arithmetic with
     * division) -- are both squarely within the bounded v1 grammar, so this is the realistic
     * "does our translator survive contact with an actual government form" check.
     */
    @Test
    void the_sheet_a_human_actually_filled_in_compiles_and_matches_pois_own_evaluator() throws Exception {
        byte[] bytes;
        try (XSSFWorkbook wb = new XSSFWorkbook()) {
            XSSFSheet sheet = wb.createSheet();
            Row header = sheet.createRow(0);
            String[] headers = {
                    "Name", "Position/Title", "SBA", "Cash Match", "In-Kind", "Total",
                    "Total Annual Salary", "Number of Months", "%Time on SBDC Program",
                    "Total Aount Required for SBDC Program", // verbatim, including the source's own typo
            };
            for (int c = 0; c < headers.length; c++) header.createCell(c).setCellValue(headers[c]);

            // Row 7 (0-indexed) in the real fixture's "Example Budget Justification" sheet.
            writeDataRow(sheet.createRow(1), "Susan Swarthmore", "State Director",
                    50000, 25000, 0, 75000, 12, 1);
            // Row 8.
            writeDataRow(sheet.createRow(2), "Peter Pomona", "Assistant State Director",
                    52000, 0, 0, 65000, 12, 0.8);
            // Row 9.
            writeDataRow(sheet.createRow(3), "William Wellesley", "Finance Director",
                    60000, 0, 0, 60000, 12, 1);

            try (ByteArrayOutputStream out = new ByteArrayOutputStream()) {
                wb.write(out);
                bytes = out.toByteArray();
            }
        }

        try (XSSFWorkbook wb = new XSSFWorkbook(new ByteArrayInputStream(bytes))) {
            XssfCellGrid grid = new XssfCellGrid(wb);
            SpreadsheetCompiler.CompileResult result =
                    SpreadsheetCompiler.compile(grid, "sba-budget-justification", MAPPER);

            assertThat(result.rejectedColumns()).isEmpty();
            ModelSpecValidator.ValidationResult validation = ModelSpecValidator.validate(result.spec());
            assertThat(validation.isValid()).as("validation errors: %s", validation.errors()).isTrue();

            List<TestCaseRunner.TestResult> testResults =
                    TestCaseRunner.run(result.spec(), result.spec().tests());
            assertThat(testResults).hasSize(1);
            assertThat(testResults.get(0).passed())
                    .as("failures: %s", testResults.get(0).failures()).isTrue();
        }
    }

    /** Excel row N's own {@code =SUM(C<N>:E<N>)} and {@code =(G<N>/12)*H<N>*I<N>} formulas,
     *  re-anchored to this new sheet's row -- same shape as the real fixture, new row number. */
    private static void writeDataRow(Row row, String name, String title, double sba, double cashMatch,
            double inKind, double annualSalary, double months, double percentTime) {
        int excelRow = row.getRowNum() + 1;
        row.createCell(0).setCellValue(name);
        row.createCell(1).setCellValue(title);
        row.createCell(2).setCellValue(sba);
        row.createCell(3).setCellValue(cashMatch);
        row.createCell(4).setCellValue(inKind);
        row.createCell(5).setCellFormula("SUM(C" + excelRow + ":E" + excelRow + ")");
        row.createCell(6).setCellValue(annualSalary);
        row.createCell(7).setCellValue(months);
        row.createCell(8).setCellValue(percentTime);
        row.createCell(9).setCellFormula("(G" + excelRow + "/12)*H" + excelRow + "*I" + excelRow);
    }
}
