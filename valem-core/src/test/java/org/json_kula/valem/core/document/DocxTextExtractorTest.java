package org.json_kula.valem.core.document;

import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.apache.poi.xwpf.usermodel.XWPFRun;
import org.json_kula.valem.core.document.ExtractedDocument;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DocxTextExtractorTest {

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

    @Test
    void supports_docx_extension_only() {
        DocxTextExtractor extractor = new DocxTextExtractor();
        assertThat(extractor.supports("lease.docx", null)).isTrue();
        assertThat(extractor.supports("lease.DOCX", null)).isTrue();
        assertThat(extractor.supports("lease.pdf", "application/pdf")).isFalse();
        assertThat(extractor.supports("lease.doc", null)).isFalse(); // legacy .doc explicitly out of scope
    }

    @Test
    void extracted_document_is_labelled_section_never_page() throws Exception {
        byte[] docx = docxWithParagraphs("Early termination fee is $200.");
        ExtractedDocument doc = new DocxTextExtractor()
                .extract(new ByteArrayInputStream(docx), "lease.docx");

        assertThat(doc.sourceKind()).isEqualTo(ExtractedDocument.SourceKind.DOCX);
        assertThat(doc.locationLabel()).isEqualTo("section");
    }

    @Test
    void groups_short_paragraphs_into_one_pseudo_page() throws Exception {
        byte[] docx = docxWithParagraphs("Clause one.", "Clause two.", "Clause three.");
        ExtractedDocument doc = new DocxTextExtractor()
                .extract(new ByteArrayInputStream(docx), "lease.docx");

        assertThat(doc.pageCount()).isEqualTo(1);
        assertThat(doc.pages().get(0).text())
                .contains("Clause one.", "Clause two.", "Clause three.");
    }

    @Test
    void splits_into_multiple_pseudo_pages_once_the_character_budget_is_exceeded() throws Exception {
        // Each paragraph ~4500 chars, well past the 4000-char pseudo-page budget -> separate pages.
        String big1 = "A".repeat(4500);
        String big2 = "B".repeat(4500);
        byte[] docx = docxWithParagraphs(big1, big2);

        ExtractedDocument doc = new DocxTextExtractor()
                .extract(new ByteArrayInputStream(docx), "lease.docx");

        assertThat(doc.pageCount()).isEqualTo(2);
        assertThat(doc.pages().get(0).text()).contains(big1);
        assertThat(doc.pages().get(1).text()).contains(big2);
    }

    @Test
    void blank_paragraphs_are_skipped() throws Exception {
        byte[] docx = docxWithParagraphs("", "   ", "Real content here.");
        ExtractedDocument doc = new DocxTextExtractor()
                .extract(new ByteArrayInputStream(docx), "lease.docx");

        assertThat(doc.pageCount()).isEqualTo(1);
        assertThat(doc.pages().get(0).text()).isEqualTo("Real content here.");
    }

    @Test
    void malformed_docx_bytes_raise_parse_failed_not_a_generic_exception() {
        byte[] garbage = "not a docx file".getBytes();
        assertThatThrownBy(() -> new DocxTextExtractor().extract(new ByteArrayInputStream(garbage), "bad.docx"))
                .isInstanceOf(DocumentExtractionException.class)
                .satisfies(e -> assertThat(((DocumentExtractionException) e).reason())
                        .isEqualTo(DocumentExtractionException.Reason.PARSE_FAILED));
    }
}
