package org.json_kula.valem.api.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.xssf.usermodel.XSSFSheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.io.ByteArrayOutputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.is;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * End-to-end test for Excel → spec: unlike every {@code *GenerateIT} sibling in this package, there
 * is no LLM anywhere in this feature's path (excel-to-spec-v1-design.md §1), so there is nothing to
 * skip-if-unconfigured — this always runs. Exercises the full real-world journey through the live
 * HTTP stack (not {@code TestCaseRunner} in-process, the way the unit/compiler-level tests already
 * do): upload a real {@code .xlsx} → compile → register → mutate → verify, against a workbook
 * deliberately combining several of this feature's own features in one sheet (a literal column, a
 * text-function formula, VLOOKUP against a side table, an arithmetic formula depending on the
 * VLOOKUP result, and a SUMPRODUCT whole-column aggregate) — proving they all survive the real HTTP
 * + JSON (de)serialization + registration round trip together, not just in isolation.
 */
@SpringBootTest
@AutoConfigureMockMvc
class SpreadsheetGenerateIT {

    private static final Logger log = LoggerFactory.getLogger(SpreadsheetGenerateIT.class);
    private static final String MODEL_ID = "e2e-excel-kitchen-sink";
    private static final String XLSX_CONTENT_TYPE =
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;

    /**
     * Columns A:E are the main table (Category, Qty, Label, Rate, Total); G:H is a small `$`-locked
     * VLOOKUP side table (col F left empty as the gap TableDetector's header-row scan needs). Row 4
     * (0-indexed row 3) is the summary row: only column E is populated, with a SUMPRODUCT combining
     * two of the table's own columns (Qty and Rate) rather than "this column's own total" — the
     * shape {@code detectSumproductAggregates} specifically handles.
     *
     * <pre>
     * Category  Qty  Label   Rate  Total        G    H
     *                                            A   100
     *   B        2   B x2    =VLOOKUP(A,G:H,2,F) =D*B B   200
     *   A        3   A x3    =VLOOKUP(A,G:H,2,F) =D*B
     *                                       =SUMPRODUCT($B$2:$B$3,$D$2:$D$3)
     * </pre>
     */
    private static byte[] kitchenSinkWorkbook() throws Exception {
        try (XSSFWorkbook wb = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            XSSFSheet sheet = wb.createSheet();

            Row header = sheet.createRow(0);
            header.createCell(0).setCellValue("Category");
            header.createCell(1).setCellValue("Qty");
            header.createCell(2).setCellValue("Label");
            header.createCell(3).setCellValue("Rate");
            header.createCell(4).setCellValue("Total");
            header.createCell(6).setCellValue("A");
            header.createCell(7).setCellValue(100);

            Row r1 = sheet.createRow(1);
            r1.createCell(0).setCellValue("B");
            r1.createCell(1).setCellValue(2);
            r1.createCell(2).setCellFormula("A2&\" x\"&B2");
            r1.createCell(3).setCellFormula("VLOOKUP(A2,$G$1:$H$2,2,FALSE)");
            r1.createCell(4).setCellFormula("D2*B2");
            r1.createCell(6).setCellValue("B");
            r1.createCell(7).setCellValue(200);

            Row r2 = sheet.createRow(2);
            r2.createCell(0).setCellValue("A");
            r2.createCell(1).setCellValue(3);
            r2.createCell(2).setCellFormula("A3&\" x\"&B3");
            r2.createCell(3).setCellFormula("VLOOKUP(A3,$G$1:$H$2,2,FALSE)");
            r2.createCell(4).setCellFormula("D3*B3");

            Row summary = sheet.createRow(3);
            summary.createCell(4).setCellFormula("SUMPRODUCT($B$2:$B$3,$D$2:$D$3)");

            wb.write(out);
            return out.toByteArray();
        }
    }

    @Test
    void uploads_compiles_registers_mutates_and_verifies_a_multi_feature_workbook() throws Exception {
        // ── Step 1: upload and compile ───────────────────────────────────────────
        MvcResult compileResult = mvc.perform(multipart("/models/generate/spreadsheet/compile")
                        .file(new MockMultipartFile("file", "kitchen-sink.xlsx", XLSX_CONTENT_TYPE,
                                kitchenSinkWorkbook()))
                        .param("modelId", MODEL_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.valid").value(true))
                .andExpect(jsonPath("$.rejectedColumns").isEmpty())
                .andReturn();

        JsonNode compileBody = mapper.readTree(compileResult.getResponse().getContentAsString());
        JsonNode spec = compileBody.get("spec");
        log.info("Compiled spec:\n{}", mapper.writerWithDefaultPrettyPrinter().writeValueAsString(spec));

        assertThat(spec.get("id").asText()).isEqualTo(MODEL_ID);
        assertThat(spec.has("library"))
                .as("the VLOOKUP side table must have produced a library export").isTrue();

        // ── Step 2: register the compiled spec via POST /models ─────────────────
        mvc.perform(post("/models")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(spec)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(MODEL_ID));

        // ── Step 3: seeded state is correct — every feature, combined, through the ──
        //            real HTTP + registration path (not TestCaseRunner in-process).
        mvc.perform(get("/models/" + MODEL_ID + "/state"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].category").value("B"))
                .andExpect(jsonPath("$.items[0].label").value("B x2"))
                .andExpect(jsonPath("$.items[0].rate").value(200))
                .andExpect(jsonPath("$.items[0].total").value(400))
                .andExpect(jsonPath("$.items[1].category").value("A"))
                .andExpect(jsonPath("$.items[1].label").value("A x3"))
                .andExpect(jsonPath("$.items[1].rate").value(100))
                .andExpect(jsonPath("$.items[1].total").value(300))
                .andExpect(jsonPath("$.sumproductQtyRate").value(700)); // 2*200 + 3*100

        // ── Step 4: the registered spec is self-consistent ──────────────────────
        mvc.perform(get("/models/" + MODEL_ID + "/verification"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.modelId", is(MODEL_ID)))
                .andExpect(jsonPath("$.state", is("green")));

        // ── Step 5: mutate item[0].qty and confirm dirty-propagation through the ───
        //            real HTTP mutate path — including the VLOOKUP's $$-bound library
        //            table and the SUMPRODUCT aggregate, both reactive, not just
        //            correct at creation time.
        mvc.perform(post("/models/" + MODEL_ID + "/mutations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"$.items[0].qty\": 5}"))
                .andExpect(status().isOk());

        mvc.perform(get("/models/" + MODEL_ID + "/state"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].qty").value(5))
                .andExpect(jsonPath("$.items[0].total").value(1000))  // 5*200
                .andExpect(jsonPath("$.sumproductQtyRate").value(1300)); // 5*200 + 3*100

        log.info("Kitchen-sink workbook: upload -> compile -> register -> mutate -> verify, all PASSED");
    }
}
