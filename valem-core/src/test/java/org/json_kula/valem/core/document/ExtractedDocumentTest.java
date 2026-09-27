package org.json_kula.valem.core.document;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ExtractedDocumentTest {

    @Test
    void empty_page_list_is_empty() {
        var doc = new ExtractedDocument("x.pdf", ExtractedDocument.SourceKind.PDF, List.of());
        assertThat(doc.isEmpty()).isTrue();
    }

    @Test
    void whitespace_only_pages_are_empty_even_with_nonzero_char_count() {
        // Regression: PDFBox's PDFTextStripper still emits trailing whitespace/newlines for a
        // page with no real text (e.g. a scanned image), so charCount() alone is not zero — isEmpty()
        // must check per-page blankness, not raw char count (vision doc AC-1).
        var doc = new ExtractedDocument("scanned.pdf", ExtractedDocument.SourceKind.PDF,
                List.of(new DocumentPage(1, "\n"), new DocumentPage(2, "   \n\n")));

        assertThat(doc.charCount()).isGreaterThan(0);
        assertThat(doc.isEmpty()).isTrue();
    }

    @Test
    void a_page_with_real_text_is_not_empty() {
        var doc = new ExtractedDocument("x.pdf", ExtractedDocument.SourceKind.PDF,
                List.of(new DocumentPage(1, "\n"), new DocumentPage(2, "Real content.")));
        assertThat(doc.isEmpty()).isFalse();
    }

    @Test
    void location_label_is_page_for_pdf_and_section_for_docx() {
        var pdf = new ExtractedDocument("x.pdf", ExtractedDocument.SourceKind.PDF,
                List.of(new DocumentPage(1, "x")));
        var docx = new ExtractedDocument("x.docx", ExtractedDocument.SourceKind.DOCX,
                List.of(new DocumentPage(1, "x")));
        assertThat(pdf.locationLabel()).isEqualTo("page");
        assertThat(docx.locationLabel()).isEqualTo("section");
    }
}
