package org.json_kula.valem.core.document;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.json_kula.valem.core.document.ExtractedDocument;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PdfTextExtractorTest {

    private static byte[] onePagePdf(String text) throws IOException {
        try (PDDocument doc = new PDDocument(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            PDPage page = new PDPage(PDRectangle.A4);
            doc.addPage(page);
            try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                cs.beginText();
                cs.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
                cs.newLineAtOffset(50, 700);
                cs.showText(text);
                cs.endText();
            }
            doc.save(out);
            return out.toByteArray();
        }
    }

    private static byte[] twoPagePdf(String page1Text, String page2Text) throws IOException {
        try (PDDocument doc = new PDDocument(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            for (String text : new String[]{page1Text, page2Text}) {
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

    @Test
    void supports_pdf_extension_and_content_type() {
        PdfTextExtractor extractor = new PdfTextExtractor();
        assertThat(extractor.supports("policy.pdf", "application/octet-stream")).isTrue();
        assertThat(extractor.supports("policy.PDF", null)).isTrue();
        assertThat(extractor.supports("weird-name", "application/pdf")).isTrue();
        assertThat(extractor.supports("policy.docx", "application/vnd.openxmlformats")).isFalse();
    }

    @Test
    void extracts_one_page_with_its_text() throws Exception {
        byte[] pdf = onePagePdf("Coinsurance is 20 percent of the allowed amount.");
        ExtractedDocument doc = new PdfTextExtractor()
                .extract(new ByteArrayInputStream(pdf), "policy.pdf");

        assertThat(doc.sourceKind()).isEqualTo(ExtractedDocument.SourceKind.PDF);
        assertThat(doc.locationLabel()).isEqualTo("page");
        assertThat(doc.pageCount()).isEqualTo(1);
        assertThat(doc.pages().get(0).pageNumber()).isEqualTo(1);
        assertThat(doc.pages().get(0).text()).contains("Coinsurance is 20 percent of the allowed amount.");
    }

    @Test
    void extracts_multiple_pages_in_order_with_correct_page_numbers() throws Exception {
        byte[] pdf = twoPagePdf("First page clause.", "Second page clause.");
        ExtractedDocument doc = new PdfTextExtractor()
                .extract(new ByteArrayInputStream(pdf), "contract.pdf");

        assertThat(doc.pageCount()).isEqualTo(2);
        assertThat(doc.pages().get(0).pageNumber()).isEqualTo(1);
        assertThat(doc.pages().get(0).text()).contains("First page clause.");
        assertThat(doc.pages().get(1).pageNumber()).isEqualTo(2);
        assertThat(doc.pages().get(1).text()).contains("Second page clause.");
    }

    @Test
    void malformed_pdf_bytes_raise_parse_failed_not_a_generic_exception() {
        byte[] garbage = "this is not a pdf".getBytes();
        assertThatThrownBy(() -> new PdfTextExtractor().extract(new ByteArrayInputStream(garbage), "bad.pdf"))
                .isInstanceOf(DocumentExtractionException.class)
                .satisfies(e -> assertThat(((DocumentExtractionException) e).reason())
                        .isEqualTo(DocumentExtractionException.Reason.PARSE_FAILED));
    }
}
