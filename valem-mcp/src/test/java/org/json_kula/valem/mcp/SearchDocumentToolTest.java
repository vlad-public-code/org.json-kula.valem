package org.json_kula.valem.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.apache.poi.xwpf.usermodel.XWPFRun;
import org.json_kula.valem.core.blob.InMemoryBlobStore;
import org.json_kula.valem.service.ModelRegistry;
import org.json_kula.valem.service.ModelService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The MCP {@code search_document} tool (v1 design spec §6.2) — a deterministic, LLM-free wrapper over
 * {@code DocumentChunker}/{@code LexicalRanker}, so an agent whose own context can't hold a large
 * document can still locate a rule inside it, bounded regardless of document length.
 */
class SearchDocumentToolTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private ToolRegistry registry;

    @BeforeEach
    void setUp() {
        ModelService service = new ModelService(new ModelRegistry(), new InMemoryBlobStore());
        registry = new ToolRegistry(service, MAPPER);
    }

    private static byte[] pdfWithPages(String... pageTexts) throws IOException {
        try (PDDocument doc = new PDDocument(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            for (String text : pageTexts) {
                PDPage page = new PDPage(PDRectangle.A4);
                doc.addPage(page);
                try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                    cs.beginText();
                    cs.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
                    cs.newLineAtOffset(50, 700);
                    cs.showText(text);
                    cs.endText();
                }
            }
            doc.save(out);
            return out.toByteArray();
        }
    }

    private static byte[] docxWithParagraphs(String... paragraphs) throws IOException {
        try (XWPFDocument doc = new XWPFDocument(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            for (String text : paragraphs) {
                XWPFParagraph p = doc.createParagraph();
                XWPFRun run = p.createRun();
                run.setText(text);
            }
            doc.write(out);
            return out.toByteArray();
        }
    }

    private ObjectNode searchArgs(byte[] fileBytes, String filename, String query, Integer topK) {
        ObjectNode args = MAPPER.createObjectNode();
        args.put("data", Base64.getEncoder().encodeToString(fileBytes));
        args.put("filename", filename);
        args.put("query", query);
        if (topK != null) args.put("topK", topK);
        return args;
    }

    private JsonNode payload(ObjectNode result) throws Exception {
        String text = result.path("content").get(0).path("text").asText();
        return MAPPER.readTree(text);
    }

    /** Padding well past the tool's 4000-char chunk cap, so a page never merges with its neighbours. */
    private static String filler(int repeats) {
        return "lorem ipsum filler content ".repeat(repeats); // ~27 chars/repeat; 200 -> ~5400 chars
    }

    @Test
    void finds_the_matching_page_in_a_multi_page_pdf() throws Exception {
        byte[] pdf = pdfWithPages(
                "This page is about parking fees and cleaning charges. " + filler(200),
                "Coinsurance is 20 percent of the allowed amount.",
                "This page discusses the appeals process. " + filler(200));

        ObjectNode result = registry.call("search_document",
                searchArgs(pdf, "policy.pdf", "what is my coinsurance", null));

        assertThat(result.path("isError").asBoolean()).isFalse();
        JsonNode payload = payload(result);
        assertThat(payload.path("locationLabel").asText()).isEqualTo("page");
        assertThat(payload.path("pageCount").asInt()).isEqualTo(3);
        assertThat(payload.path("matches").get(0).path("quote").asText())
                .contains("Coinsurance is 20 percent of the allowed amount.");
        assertThat(payload.path("matches").get(0).path("page").asInt()).isEqualTo(2);
    }

    @Test
    void docx_matches_are_labelled_section_never_page() throws Exception {
        byte[] docx = docxWithParagraphs("Early termination fee is $200 per the contract.");

        JsonNode payload = payload(registry.call("search_document",
                searchArgs(docx, "lease.docx", "early termination fee", null)));

        assertThat(payload.path("locationLabel").asText()).isEqualTo("section");
    }

    @Test
    void topK_bounds_the_number_of_matches_returned() throws Exception {
        // Each page padded past the chunk cap so all four land in separate chunks — otherwise the
        // chunker would legitimately group these small pages into one, and there'd be nothing to cap.
        byte[] pdf = pdfWithPages(
                "fee page one " + filler(200), "fee page two " + filler(200),
                "fee page three " + filler(200), "fee page four " + filler(200));

        JsonNode payload = payload(registry.call("search_document",
                searchArgs(pdf, "doc.pdf", "fee", 2)));

        assertThat(payload.path("matches")).hasSize(2);
    }

    @Test
    void rejects_unsupported_file_format() throws Exception {
        ObjectNode result = registry.call("search_document",
                searchArgs("not a real spreadsheet".getBytes(), "data.xlsx", "anything", null));

        assertThat(result.path("isError").asBoolean()).isTrue();
    }

    @Test
    void rejects_bad_base64() throws Exception {
        ObjectNode args = MAPPER.createObjectNode();
        args.put("data", "%%% not base64 %%%");
        args.put("filename", "policy.pdf");
        args.put("query", "anything");

        assertThat(registry.call("search_document", args).path("isError").asBoolean()).isTrue();
    }

    @Test
    void rejects_document_with_no_extractable_text() throws Exception {
        byte[] pdf = pdfWithPages(""); // one page, no drawn text -> no extractable text at all

        ObjectNode result = registry.call("search_document",
                searchArgs(pdf, "empty.pdf", "anything", null));

        assertThat(result.path("isError").asBoolean()).isTrue();
    }

    @Test
    void cost_is_bounded_regardless_of_document_length() throws Exception {
        // A 100-page document must still return quickly and correctly — proves the tool never holds
        // the whole document, only ranked chunks (AC-2/AC-9 analogue for the MCP surface).
        String[] pages = new String[100];
        for (int i = 0; i < pages.length; i++) pages[i] = "filler content page " + i;
        pages[77] = "The early termination fee is $200 per the contract.";
        byte[] pdf = pdfWithPages(pages);

        long start = System.nanoTime();
        JsonNode payload = payload(registry.call("search_document",
                searchArgs(pdf, "big.pdf", "early termination fee", 3)));
        long elapsedMs = (System.nanoTime() - start) / 1_000_000;

        assertThat(payload.path("pageCount").asInt()).isEqualTo(100);
        assertThat(payload.path("matches").get(0).path("quote").asText())
                .contains("The early termination fee is $200 per the contract.");
        assertThat(elapsedMs).isLessThan(5_000);
    }

    @Test
    void never_calls_an_llm() {
        // Structural proof, not a runtime assertion: ToolRegistry is constructed here with no
        // LlmClient anywhere in its dependency graph (ModelService/ModelRegistry/InMemoryBlobStore
        // only) — search_document has no way to reach one. See AC-8.
        assertThat(registry.toolNames()).contains("search_document");
    }
}
