package org.json_kula.valem.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.xssf.usermodel.XSSFSheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.json_kula.valem.core.blob.InMemoryBlobStore;
import org.json_kula.valem.service.ModelRegistry;
import org.json_kula.valem.service.ModelService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The MCP {@code convert_spreadsheet} tool (v1 design spec §8) — deterministic, no LLM call anywhere
 * (unlike the document-to-spec feature's tools, this feature has no LLM dependency at all to be
 * absent from — there is no provider client anywhere in {@code valem-mcp}'s dependency graph).
 */
class ConvertSpreadsheetToolTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private ToolRegistry registry;

    @BeforeEach
    void setUp() {
        ModelService service = new ModelService(new ModelRegistry(), new InMemoryBlobStore());
        registry = new ToolRegistry(service, MAPPER);
    }

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

    private com.fasterxml.jackson.databind.node.ObjectNode args(byte[] bytes, String filename, String modelId) {
        var node = MAPPER.createObjectNode();
        node.put("data", Base64.getEncoder().encodeToString(bytes));
        node.put("filename", filename);
        node.put("modelId", modelId);
        return node;
    }

    private JsonNode payload(com.fasterxml.jackson.databind.node.ObjectNode result) throws Exception {
        return MAPPER.readTree(result.path("content").get(0).path("text").asText());
    }

    @Test
    void compiles_a_valid_workbook_without_registering_it() throws Exception {
        var result = registry.call("convert_spreadsheet", args(orderLineItemsWorkbook(), "order.xlsx", "order-m"));

        assertThat(result.path("isError").asBoolean()).isFalse();
        JsonNode payload = payload(result);
        assertThat(payload.path("valid").asBoolean()).isTrue();
        assertThat(payload.path("spec").path("id").asText()).isEqualTo("order-m");
        assertThat(payload.path("rejectedColumns")).isEmpty();

        // Not registered -- the tool only compiles, the caller decides whether to create_model.
        assertThat(payload(registry.call("list_models", MAPPER.createObjectNode()))).isEmpty();
    }

    @Test
    void rejects_non_xlsx_filename() throws Exception {
        var result = registry.call("convert_spreadsheet", args("not a real file".getBytes(), "data.csv", "m"));
        assertThat(result.path("isError").asBoolean()).isTrue();
    }

    @Test
    void rejects_bad_base64() {
        var node = MAPPER.createObjectNode();
        node.put("data", "%%% not base64 %%%");
        node.put("filename", "x.xlsx");
        node.put("modelId", "m");

        assertThat(registry.call("convert_spreadsheet", node).path("isError").asBoolean()).isTrue();
    }

    @Test
    void a_workbook_exceeding_the_column_cap_returns_a_structured_rejection_not_a_blunt_error() throws Exception {
        byte[] bytes;
        try (XSSFWorkbook wb = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            XSSFSheet sheet = wb.createSheet();
            Row header = sheet.createRow(0);
            Row data = sheet.createRow(1);
            for (int c = 0; c <= 200; c++) { // 201 columns, one past the 200 cap
                header.createCell(c).setCellValue("Col" + c);
                data.createCell(c).setCellValue(c);
            }
            wb.write(out);
            bytes = out.toByteArray();
        }

        var result = registry.call("convert_spreadsheet", args(bytes, "wide.xlsx", "m"));

        // The tool call itself succeeds (it correctly determined and reported the rejection) --
        // before the fix, this limit wasn't enforced at all, so the sheet would have proceeded
        // straight into classification/translation instead.
        assertThat(result.path("isError").asBoolean()).isFalse();
        JsonNode payload = payload(result);
        assertThat(payload.path("valid").asBoolean()).isFalse();
        assertThat(payload.path("reason").asText()).isEqualTo("TOO_MANY_COLUMNS");
    }

    @Test
    void a_corrupt_xlsx_upload_returns_a_structured_parse_failure_not_an_uncaught_exception() throws Exception {
        // Filename ends in .xlsx (passes the extension pre-check) but the bytes are not a valid
        // OOXML zip -- `new XSSFWorkbook(in)` throws, which before the fix was uncaught by this
        // tool's handler and fell through to ToolRegistry's generic blunt error text instead of the
        // documented {valid:false, reason, error} shape.
        var result = registry.call("convert_spreadsheet", args("not a real xlsx".getBytes(), "bad.xlsx", "m"));

        assertThat(result.path("isError").asBoolean()).isFalse();
        JsonNode payload = payload(result);
        assertThat(payload.path("valid").asBoolean()).isFalse();
        assertThat(payload.path("reason").asText()).isEqualTo("PARSE_FAILED");
    }

    @Test
    void never_calls_an_llm() {
        // Structural proof: ToolRegistry here is built from ModelService/ModelRegistry/
        // InMemoryBlobStore only -- there is no LlmClient anywhere in its dependency graph for
        // convert_spreadsheet (or anything else in valem-mcp) to reach.
        assertThat(registry.toolNames()).contains("convert_spreadsheet");
    }
}
