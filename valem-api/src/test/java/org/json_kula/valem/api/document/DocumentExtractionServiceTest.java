package org.json_kula.valem.api.document;

import org.json_kula.valem.core.document.DocumentExtractionException;
import org.json_kula.valem.core.document.DocumentPage;
import org.json_kula.valem.core.document.DocumentTextExtractor;
import org.json_kula.valem.core.document.ExtractedDocument;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DocumentExtractionServiceTest {

    private static DocumentTextExtractor fakeExtractor(boolean supports, ExtractedDocument result) {
        return new DocumentTextExtractor() {
            @Override public boolean supports(String filename, String contentType) { return supports; }
            @Override public ExtractedDocument extract(InputStream in, String filename) { return result; }
        };
    }

    private static ExtractedDocument docWithPages(int count) {
        List<DocumentPage> pages = new ArrayList<>();
        for (int i = 1; i <= count; i++) pages.add(new DocumentPage(i, "text " + i));
        return new ExtractedDocument("x.pdf", ExtractedDocument.SourceKind.PDF, pages);
    }

    @Test
    void rejects_empty_upload() {
        DocumentExtractionService service = new DocumentExtractionService(List.of(), 1000, 10, 1_000_000);
        MockMultipartFile file = new MockMultipartFile("file", "x.pdf", "application/pdf", new byte[0]);

        assertThatThrownBy(() -> service.extract(file))
                .isInstanceOf(DocumentExtractionException.class)
                .satisfies(e -> assertThat(((DocumentExtractionException) e).reason())
                        .isEqualTo(DocumentExtractionException.Reason.EMPTY_DOCUMENT));
    }

    @Test
    void rejects_oversized_file_before_ever_invoking_the_extractor() {
        // A fake extractor that throws if invoked proves the size cap is checked BEFORE parsing
        // (vision doc AC-1: rejected immediately, not after processing starts).
        DocumentTextExtractor neverCalled = new DocumentTextExtractor() {
            @Override public boolean supports(String f, String c) { return true; }
            @Override public ExtractedDocument extract(InputStream in, String filename) {
                throw new AssertionError("extractor must not be invoked when the size cap is exceeded");
            }
        };
        DocumentExtractionService service = new DocumentExtractionService(List.of(neverCalled), 10, 300, 1_000_000);
        MockMultipartFile file = new MockMultipartFile("file", "x.pdf", "application/pdf",
                "this content is longer than ten bytes".getBytes());

        assertThatThrownBy(() -> service.extract(file))
                .isInstanceOf(DocumentExtractionException.class)
                .satisfies(e -> assertThat(((DocumentExtractionException) e).reason())
                        .isEqualTo(DocumentExtractionException.Reason.FILE_TOO_LARGE));
    }

    @Test
    void rejects_unsupported_format() {
        DocumentExtractionService service = new DocumentExtractionService(
                List.of(fakeExtractor(false, docWithPages(1))), 1_000_000, 300, 1_000_000);
        MockMultipartFile file = new MockMultipartFile("file", "spreadsheet.xlsx",
                "application/vnd.ms-excel", "data".getBytes());

        assertThatThrownBy(() -> service.extract(file))
                .isInstanceOf(DocumentExtractionException.class)
                .satisfies(e -> assertThat(((DocumentExtractionException) e).reason())
                        .isEqualTo(DocumentExtractionException.Reason.UNSUPPORTED_FORMAT));
    }

    @Test
    void rejects_document_with_no_extractable_text() {
        ExtractedDocument empty = new ExtractedDocument("x.pdf", ExtractedDocument.SourceKind.PDF, List.of());
        DocumentExtractionService service = new DocumentExtractionService(
                List.of(fakeExtractor(true, empty)), 1_000_000, 300, 1_000_000);
        MockMultipartFile file = new MockMultipartFile("file", "scanned.pdf", "application/pdf", "data".getBytes());

        assertThatThrownBy(() -> service.extract(file))
                .isInstanceOf(DocumentExtractionException.class)
                .satisfies(e -> assertThat(((DocumentExtractionException) e).reason())
                        .isEqualTo(DocumentExtractionException.Reason.EMPTY_DOCUMENT));
    }

    @Test
    void rejects_document_exceeding_extracted_char_cap_even_within_the_page_cap() {
        // A single page can still carry an enormous amount of text (a decompression-ratio outlier,
        // or just a pathologically busy page) without ever tripping the page-count cap — this check
        // is independent of it (defense in depth alongside POI's own zip-bomb guard for DOCX).
        ExtractedDocument huge = new ExtractedDocument("x.pdf", ExtractedDocument.SourceKind.PDF,
                List.of(new DocumentPage(1, "x".repeat(2_000_000))));
        DocumentExtractionService service = new DocumentExtractionService(
                List.of(fakeExtractor(true, huge)), 10_000_000, 300, 1_000_000);
        MockMultipartFile file = new MockMultipartFile("file", "big.pdf", "application/pdf", "data".getBytes());

        assertThatThrownBy(() -> service.extract(file))
                .isInstanceOf(DocumentExtractionException.class)
                .satisfies(e -> assertThat(((DocumentExtractionException) e).reason())
                        .isEqualTo(DocumentExtractionException.Reason.CONTENT_TOO_LARGE));
    }

    @Test
    void rejects_document_exceeding_page_cap_after_extraction() {
        DocumentExtractionService service = new DocumentExtractionService(
                List.of(fakeExtractor(true, docWithPages(301))), 1_000_000, 300, 1_000_000);
        MockMultipartFile file = new MockMultipartFile("file", "big.pdf", "application/pdf", "data".getBytes());

        assertThatThrownBy(() -> service.extract(file))
                .isInstanceOf(DocumentExtractionException.class)
                .satisfies(e -> assertThat(((DocumentExtractionException) e).reason())
                        .isEqualTo(DocumentExtractionException.Reason.TOO_MANY_PAGES));
    }

    @Test
    void returns_extracted_document_within_limits() throws Exception {
        DocumentExtractionService service = new DocumentExtractionService(
                List.of(fakeExtractor(true, docWithPages(5))), 1_000_000, 300, 1_000_000);
        MockMultipartFile file = new MockMultipartFile("file", "ok.pdf", "application/pdf", "data".getBytes());

        ExtractedDocument result = service.extract(file);

        assertThat(result.pageCount()).isEqualTo(5);
    }

    @Test
    void picks_the_first_extractor_that_supports_the_file() throws Exception {
        DocumentTextExtractor doesNotSupport = fakeExtractor(false, docWithPages(1));
        DocumentTextExtractor supports = fakeExtractor(true, docWithPages(2));
        DocumentExtractionService service = new DocumentExtractionService(
                List.of(doesNotSupport, supports), 1_000_000, 300, 1_000_000);
        MockMultipartFile file = new MockMultipartFile("file", "ok.docx",
                "application/vnd.openxmlformats-officedocument.wordprocessingml.document", "data".getBytes());

        assertThat(service.extract(file).pageCount()).isEqualTo(2);
    }
}
