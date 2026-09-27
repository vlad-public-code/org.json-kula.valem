package org.json_kula.valem.core.document;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.pdfbox.text.TextPosition;

import java.io.IOException;
import java.io.InputStream;
import java.io.Writer;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Extracts one {@link DocumentPage} per real PDF page via Apache PDFBox, in a single pass. */
public final class PdfTextExtractor implements DocumentTextExtractor {

    @Override
    public boolean supports(String filename, String contentType) {
        String lower = filename == null ? "" : filename.toLowerCase(Locale.ROOT);
        return lower.endsWith(".pdf") || "application/pdf".equalsIgnoreCase(contentType);
    }

    @Override
    public ExtractedDocument extract(InputStream in, String filename)
            throws DocumentExtractionException, IOException {
        byte[] bytes = in.readAllBytes();
        try (PDDocument document = Loader.loadPDF(bytes)) {
            PerPageTextStripper stripper = new PerPageTextStripper();
            stripper.writeText(document, Writer.nullWriter());
            return new ExtractedDocument(filename, ExtractedDocument.SourceKind.PDF, stripper.pages());
        } catch (IOException e) {
            throw new DocumentExtractionException(DocumentExtractionException.Reason.PARSE_FAILED,
                    "Could not parse PDF: " + e.getMessage(), e);
        }
    }

    /**
     * Captures text per page during {@code PDFTextStripper}'s single walk of the document.
     *
     * <p>The straightforward alternative — call {@code getText(document)} once per page with a
     * narrowed {@code setStartPage}/{@code setEndPage} range — re-scans the <em>entire</em> page tree
     * on every call to find the requested range, making n calls do O(n²) traversal work for a document
     * of n pages. That cost grows precisely where this feature is designed to still perform well: a
     * document approaching the 300-page cap. Overriding these hooks instead captures per-page
     * boundaries during the one walk the stripper already does.
     */
    private static final class PerPageTextStripper extends PDFTextStripper {

        private final List<DocumentPage> pages = new ArrayList<>();
        private final StringBuilder current = new StringBuilder();

        PerPageTextStripper() throws IOException {
            super();
        }

        @Override
        protected void writeString(String text, List<TextPosition> textPositions) throws IOException {
            current.append(text);
        }

        @Override
        protected void writeLineSeparator() throws IOException {
            current.append('\n');
        }

        @Override
        protected void endPage(PDPage page) throws IOException {
            pages.add(new DocumentPage(pages.size() + 1, current.toString()));
            current.setLength(0);
            super.endPage(page);
        }

        List<DocumentPage> pages() {
            return pages;
        }
    }
}
