package org.json_kula.valem.api.spreadsheet;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.xssf.usermodel.XSSFSheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.json_kula.valem.core.spreadsheet.SpreadsheetCompiler;
import org.json_kula.valem.core.spreadsheet.SpreadsheetExtractionException;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;

import java.io.ByteArrayOutputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SpreadsheetCompileServiceTest {

    private static byte[] simpleWorkbook() throws Exception {
        try (XSSFWorkbook wb = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            XSSFSheet sheet = wb.createSheet();
            Row header = sheet.createRow(0);
            header.createCell(0).setCellValue("Quantity");
            header.createCell(1).setCellValue("Price");
            header.createCell(2).setCellValue("Total");

            Row r1 = sheet.createRow(1);
            r1.createCell(0).setCellValue(2);
            r1.createCell(1).setCellValue(10);
            r1.createCell(2).setCellFormula("A2*B2");

            wb.write(out);
            return out.toByteArray();
        }
    }

    @Test
    void rejects_empty_upload() {
        SpreadsheetCompileService service = new SpreadsheetCompileService(1000, 10, 10, new ObjectMapper());
        MockMultipartFile file = new MockMultipartFile("file", "x.xlsx",
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", new byte[0]);

        assertThatThrownBy(() -> service.compile(file, "m"))
                .isInstanceOf(SpreadsheetExtractionException.class)
                .satisfies(e -> assertThat(((SpreadsheetExtractionException) e).reason())
                        .isEqualTo(SpreadsheetExtractionException.Reason.EMPTY_WORKBOOK));
    }

    @Test
    void rejects_non_xlsx_filename() throws Exception {
        SpreadsheetCompileService service = new SpreadsheetCompileService(10_000_000, 1000, 100, new ObjectMapper());
        MockMultipartFile file = new MockMultipartFile("file", "data.csv", "text/csv", "a,b\n1,2".getBytes());

        assertThatThrownBy(() -> service.compile(file, "m"))
                .isInstanceOf(SpreadsheetExtractionException.class)
                .satisfies(e -> assertThat(((SpreadsheetExtractionException) e).reason())
                        .isEqualTo(SpreadsheetExtractionException.Reason.UNSUPPORTED_FORMAT));
    }

    @Test
    void rejects_oversized_file() throws Exception {
        SpreadsheetCompileService service = new SpreadsheetCompileService(10, 1000, 100, new ObjectMapper());
        MockMultipartFile file = new MockMultipartFile("file", "x.xlsx",
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", simpleWorkbook());

        assertThatThrownBy(() -> service.compile(file, "m"))
                .isInstanceOf(SpreadsheetExtractionException.class)
                .satisfies(e -> assertThat(((SpreadsheetExtractionException) e).reason())
                        .isEqualTo(SpreadsheetExtractionException.Reason.FILE_TOO_LARGE));
    }

    @Test
    void rejects_workbook_exceeding_row_cap() throws Exception {
        SpreadsheetCompileService service = new SpreadsheetCompileService(10_000_000, 1, 100, new ObjectMapper());
        MockMultipartFile file = new MockMultipartFile("file", "x.xlsx",
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", simpleWorkbook());

        assertThatThrownBy(() -> service.compile(file, "m"))
                .isInstanceOf(SpreadsheetExtractionException.class)
                .satisfies(e -> assertThat(((SpreadsheetExtractionException) e).reason())
                        .isEqualTo(SpreadsheetExtractionException.Reason.TOO_MANY_ROWS));
    }

    @Test
    void compiles_a_valid_workbook_within_limits() throws Exception {
        SpreadsheetCompileService service = new SpreadsheetCompileService(10_000_000, 1000, 100, new ObjectMapper());
        MockMultipartFile file = new MockMultipartFile("file", "x.xlsx",
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", simpleWorkbook());

        SpreadsheetCompiler.CompileResult result = service.compile(file, "order-m");

        assertThat(result.rejectedColumns()).isEmpty();
        assertThat(result.spec().id()).isEqualTo("order-m");
    }
}
