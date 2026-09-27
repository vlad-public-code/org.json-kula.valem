package org.json_kula.valem.api.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.json_kula.valem.core.document.DocumentScanner;
import org.json_kula.valem.core.llm.SpecGenerator;
import org.junit.jupiter.api.Assumptions;
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
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * End-to-end test: calls a real LLM (whatever {@code valem.llm.provider}/{@code valem.llm.api-key}
 * are configured to) to scan a small fixture PDF for a formula, then compile the confirmed candidate
 * into a certified {@code ModelSpec} — exercising docs/design/llm/document-to-spec-v1-design.md §4.4
 * end to end, the one item its own test plan (§8) still listed as not written.
 *
 * <p>Skipped automatically when no LLM is configured (see {@link #skipIfNoLlm()}).
 */
@SpringBootTest
@AutoConfigureMockMvc
class DocumentGenerateIT {

    private static final Logger log = LoggerFactory.getLogger(DocumentGenerateIT.class);

    private static final String MODEL_ID = "insurance-coinsurance-from-doc";
    private static final String CLAUSE_TEXT =
            "Section 4.2 Coinsurance. After the deductible is met, the plan pays 80 percent of the "
            + "allowed amount for in-network care, and the member is responsible for the remaining "
            + "20 percent coinsurance, up to the annual out-of-pocket maximum.";
    private static final String USER_ASK = "What is my coinsurance percentage for in-network care?";

    @Autowired(required = false) SpecGenerator specGenerator;
    @Autowired(required = false) DocumentScanner documentScanner;

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;

    private void skipIfNoLlm() {
        Assumptions.assumeTrue(specGenerator != null && documentScanner != null,
                "Skipping: LLM not configured (set valem.llm.api-key / valem.llm.provider)");
    }

    /** A small multi-page fixture: two irrelevant pages, the target clause on page 2. */
    private static byte[] fixturePdf() throws Exception {
        try (PDDocument doc = new PDDocument(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            String[] pages = {
                    "Section 1.1 Definitions. \"Plan\" means the health benefit plan described herein. "
                    + "\"Member\" means an individual enrolled under this plan.",
                    CLAUSE_TEXT,
                    "Section 7.1 Appeals. A member may appeal a denied claim within 180 days of the "
                    + "denial notice by submitting a written request to the plan administrator."
            };
            for (String text : pages) {
                PDPage page = new PDPage(PDRectangle.A4);
                doc.addPage(page);
                try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                    cs.beginText();
                    cs.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 11);
                    cs.newLineAtOffset(50, 700);
                    for (String line : wrap(text, 90)) {
                        cs.showText(line);
                        cs.newLineAtOffset(0, -14);
                    }
                    cs.endText();
                }
            }
            doc.save(out);
            return out.toByteArray();
        }
    }

    private static java.util.List<String> wrap(String text, int width) {
        java.util.List<String> lines = new java.util.ArrayList<>();
        StringBuilder line = new StringBuilder();
        for (String word : text.split(" ")) {
            if (line.length() + word.length() + 1 > width) {
                lines.add(line.toString());
                line.setLength(0);
            }
            if (!line.isEmpty()) line.append(' ');
            line.append(word);
        }
        if (!line.isEmpty()) lines.add(line.toString());
        return lines;
    }

    @Test
    void scans_a_pdf_and_compiles_the_confirmed_candidate_into_a_certified_spec() throws Exception {
        skipIfNoLlm();

        // ── Step 1: scan ──────────────────────────────────────────────────────
        log.info("Scanning fixture PDF via real LLM for: {}", USER_ASK);
        MvcResult scanResult = mvc.perform(multipart("/models/generate/document/scan")
                        .file(new MockMultipartFile("file", "policy.pdf", "application/pdf", fixturePdf()))
                        .param("prompt", USER_ASK))
                .andExpect(status().isOk())
                .andReturn();

        JsonNode scan = mapper.readTree(scanResult.getResponse().getContentAsString());
        log.info("Scan result: {}", scan.toPrettyString());

        assertThat(scan.path("found").asBoolean())
                .as("scan should find the coinsurance clause: %s", scan)
                .isTrue();
        JsonNode candidates = scan.path("candidates");
        assertThat(candidates.isArray() && !candidates.isEmpty()).isTrue();

        JsonNode top = candidates.get(0);
        String quote = top.path("quote").asText();
        int page = top.path("page").asInt();
        String locationLabel = scan.path("locationLabel").asText();
        assertThat(quote).isNotBlank();
        assertThat(page).isGreaterThan(0);
        assertThat(locationLabel).isEqualTo("page");
        log.info("Top candidate: page={} quote={}", page, quote);

        // ── Step 2: compile the confirmed candidate ──────────────────────────
        String compileRequest = mapper.writeValueAsString(new DocumentGenerateController.DocumentCompileRequest(
                MODEL_ID, USER_ASK, quote, page, locationLabel, false));

        MvcResult compileResult = mvc.perform(post("/models/generate/document/compile")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(compileRequest))
                .andReturn();

        JsonNode compiled = mapper.readTree(compileResult.getResponse().getContentAsString());
        if (!compiled.path("valid").asBoolean(false)) {
            log.error("Compile FAILED: {}", compiled.toPrettyString());
        }
        assertThat(compileResult.getResponse().getStatus()).isEqualTo(200);
        assertThat(compiled.path("valid").asBoolean()).isTrue();

        JsonNode spec = compiled.path("spec");
        log.info("Compiled spec:\n{}", spec.toPrettyString());
        assertThat(spec.path("id").asText()).isEqualTo(MODEL_ID);
        assertThat(spec.path("derivations").isArray() && !spec.path("derivations").isEmpty())
                .as("compiled spec must derive something from the coinsurance rate")
                .isTrue();

        JsonNode verification = compiled.path("verification");
        log.info("Verification report: {}", verification);
    }

    // ── Real-world fixture: an actual government PDF, not a synthetic one ──────

    private static final String REAL_PDF_PATH = "src/test/resources/fixtures/irs-rp-2025-32.pdf";
    private static final String REAL_MODEL_ID = "qbi-threshold-from-irs-doc";
    private static final String REAL_USER_ASK =
            "What is the qualified business income threshold amount for a married couple filing "
            + "jointly?";

    /**
     * Same flow as the synthetic-fixture test above, but against a real, publicly published
     * government PDF (IRS Revenue Procedure 2025-32 — the 2026 annual inflation adjustments; see
     * {@code RealWorldDocumentTest} in {@code valem-core} for provenance) instead of a generated one
     * — real embedded fonts, real page layout, a real multi-provision document where the target
     * table sits among dozens of similarly-shaped ones (exactly the "real-world quirks a synthetic
     * fixture can't reproduce" case).
     */
    @Test
    void scans_a_real_government_pdf_and_compiles_a_qbi_threshold_model() throws Exception {
        skipIfNoLlm();

        byte[] realPdf = Files.readAllBytes(Path.of(REAL_PDF_PATH));
        log.info("Scanning real IRS PDF ({} bytes) via real LLM for: {}", realPdf.length, REAL_USER_ASK);

        MvcResult scanResult = mvc.perform(multipart("/models/generate/document/scan")
                        .file(new MockMultipartFile("file", "irs-rp-2025-32.pdf", "application/pdf", realPdf))
                        .param("prompt", REAL_USER_ASK))
                .andExpect(status().isOk())
                .andReturn();

        JsonNode scan = mapper.readTree(scanResult.getResponse().getContentAsString());
        log.info("Real-document scan result: {}", scan.toPrettyString());

        assertThat(scan.path("found").asBoolean())
                .as("scan should find the QBI threshold table: %s", scan)
                .isTrue();
        JsonNode candidates = scan.path("candidates");
        assertThat(candidates.isArray() && !candidates.isEmpty()).isTrue();

        JsonNode top = candidates.get(0);
        String quote = top.path("quote").asText();
        int page = top.path("page").asInt();
        String locationLabel = scan.path("locationLabel").asText();
        assertThat(quote).isNotBlank();
        assertThat(page).isGreaterThan(0);
        log.info("Top real-document candidate: page={} quote={}", page, quote);

        String compileRequest = mapper.writeValueAsString(new DocumentGenerateController.DocumentCompileRequest(
                REAL_MODEL_ID, REAL_USER_ASK, quote, page, locationLabel, false));

        MvcResult compileResult = mvc.perform(post("/models/generate/document/compile")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(compileRequest))
                .andReturn();

        JsonNode compiled = mapper.readTree(compileResult.getResponse().getContentAsString());
        if (!compiled.path("valid").asBoolean(false)) {
            log.error("Real-document compile FAILED: {}", compiled.toPrettyString());
        }
        assertThat(compileResult.getResponse().getStatus()).isEqualTo(200);
        assertThat(compiled.path("valid").asBoolean()).isTrue();

        JsonNode spec = compiled.path("spec");
        log.info("Compiled real-document spec:\n{}", spec.toPrettyString());
        assertThat(spec.path("id").asText()).isEqualTo(REAL_MODEL_ID);
    }
}
