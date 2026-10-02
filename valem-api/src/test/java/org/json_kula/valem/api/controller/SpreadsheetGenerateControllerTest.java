package org.json_kula.valem.api.controller;

import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.xssf.usermodel.XSSFSheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;

import java.io.ByteArrayOutputStream;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * No {@code @MockBean} anywhere in this file — unlike every other generate controller test in this
 * codebase, this feature has no LLM dependency to mock. The whole pipeline runs for real against a
 * real (small, in-test-built) {@code .xlsx}.
 */
@SpringBootTest
@AutoConfigureMockMvc
class SpreadsheetGenerateControllerTest {

    @Autowired MockMvc mvc;

    private static final String XLSX_CONTENT_TYPE =
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";

    private static byte[] orderLineItemsWorkbook() throws Exception {
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

            Row r2 = sheet.createRow(2);
            r2.createCell(0).setCellValue(3);
            r2.createCell(1).setCellValue(20);
            r2.createCell(2).setCellFormula("A3*B3");

            wb.write(out);
            return out.toByteArray();
        }
    }

    private static byte[] workbookWithOneBadColumn() throws Exception {
        try (XSSFWorkbook wb = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            XSSFSheet sheet = wb.createSheet();
            Row header = sheet.createRow(0);
            header.createCell(0).setCellValue("Quantity");
            header.createCell(1).setCellValue("Price");
            header.createCell(2).setCellValue("Total");
            header.createCell(3).setCellValue("Bad");

            Row r1 = sheet.createRow(1);
            r1.createCell(0).setCellValue(2);
            r1.createCell(1).setCellValue(10);
            r1.createCell(2).setCellFormula("A2*B2");
            r1.createCell(3).setCellFormula("VLOOKUP(A2,$A$1:$A$1,1)");

            Row r2 = sheet.createRow(2);
            r2.createCell(0).setCellValue(3);
            r2.createCell(1).setCellValue(20);
            r2.createCell(2).setCellFormula("A3*B3");
            r2.createCell(3).setCellFormula("VLOOKUP(A3,$A$1:$A$1,1)");

            wb.write(out);
            return out.toByteArray();
        }
    }

    @Test
    void compiles_a_valid_workbook_and_returns_a_passing_spec() throws Exception {
        mvc.perform(multipart("/models/generate/spreadsheet/compile")
                        .file(new MockMultipartFile("file", "order.xlsx", XLSX_CONTENT_TYPE,
                                orderLineItemsWorkbook()))
                        .param("modelId", "order-from-xlsx"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.valid").value(true))
                .andExpect(jsonPath("$.spec.id").value("order-from-xlsx"))
                .andExpect(jsonPath("$.rejectedColumns").isEmpty());
    }

    @Test
    void partial_translation_surfaces_rejected_columns_but_still_succeeds() throws Exception {
        mvc.perform(multipart("/models/generate/spreadsheet/compile")
                        .file(new MockMultipartFile("file", "order.xlsx", XLSX_CONTENT_TYPE,
                                workbookWithOneBadColumn()))
                        .param("modelId", "partial"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.valid").value(true))
                .andExpect(jsonPath("$.rejectedColumns[0].header").value("Bad"))
                .andExpect(jsonPath("$.rejectedColumns[0].reason").value("UNSUPPORTED_FUNCTION"));
    }

    @Test
    void rejects_blank_modelId() throws Exception {
        mvc.perform(multipart("/models/generate/spreadsheet/compile")
                        .file(new MockMultipartFile("file", "order.xlsx", XLSX_CONTENT_TYPE,
                                orderLineItemsWorkbook()))
                        .param("modelId", "  "))
                .andExpect(status().isBadRequest());
    }

    @Test
    void rejects_non_xlsx_upload_with_a_named_reason() throws Exception {
        mvc.perform(multipart("/models/generate/spreadsheet/compile")
                        .file(new MockMultipartFile("file", "data.csv", "text/csv", "a,b\n1,2".getBytes()))
                        .param("modelId", "m"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.reason").value("UNSUPPORTED_FORMAT"));
    }
}
