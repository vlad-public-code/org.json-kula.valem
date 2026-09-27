package org.json_kula.valem.core.document;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Extracts one {@link DocumentPage} per real PDF page via Apache PDFBox. */
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
            int pageCount = document.getNumberOfPages();
            List<DocumentPage> pages = new ArrayList<>(pageCount);
            PDFTextStripper stripper = new PDFTextStripper();
            for (int pageNumber = 1; pageNumber <= pageCount; pageNumber++) {
                stripper.setStartPage(pageNumber);
                stripper.setEndPage(pageNumber);
                pages.add(new DocumentPage(pageNumber, stripper.getText(document)));
            }
            return new ExtractedDocument(filename, ExtractedDocument.SourceKind.PDF, pages);
        } catch (IOException e) {
            throw new DocumentExtractionException(DocumentExtractionException.Reason.PARSE_FAILED,
                    "Could not parse PDF: " + e.getMessage(), e);
        }
    }
}
