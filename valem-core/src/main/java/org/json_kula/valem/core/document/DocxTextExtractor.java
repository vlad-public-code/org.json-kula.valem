package org.json_kula.valem.core.document;

import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * DOCX has no reliable page-break model in the OOXML alone (page breaks are a rendering-time
 * concern, not stored per-paragraph), so this extractor approximates "pages" as fixed-size
 * pseudo-pages — consecutive paragraphs grouped up to a character budget. Callers must label these
 * "section N", never "page N" — see {@link ExtractedDocument#locationLabel()} and the v1 design spec
 * §4.2 / AC-10.
 */
public final class DocxTextExtractor implements DocumentTextExtractor {

    /** Matches the default scan chunk size so a pseudo-page rarely spans a chunk boundary. */
    private static final int PSEUDO_PAGE_CHARS = 4000;

    @Override
    public boolean supports(String filename, String contentType) {
        String lower = filename == null ? "" : filename.toLowerCase(Locale.ROOT);
        return lower.endsWith(".docx");
    }

    @Override
    public ExtractedDocument extract(InputStream in, String filename)
            throws DocumentExtractionException, IOException {
        try (XWPFDocument document = new XWPFDocument(in)) {
            List<DocumentPage> pages = new ArrayList<>();
            StringBuilder current = new StringBuilder();
            int pageNumber = 1;

            for (XWPFParagraph paragraph : document.getParagraphs()) {
                String text = paragraph.getText();
                if (text == null || text.isBlank()) continue;

                if (!current.isEmpty() && current.length() + text.length() > PSEUDO_PAGE_CHARS) {
                    pages.add(new DocumentPage(pageNumber++, current.toString()));
                    current.setLength(0);
                }
                if (!current.isEmpty()) current.append('\n');
                current.append(text);
            }
            if (!current.isEmpty()) pages.add(new DocumentPage(pageNumber, current.toString()));

            return new ExtractedDocument(filename, ExtractedDocument.SourceKind.DOCX, pages);
        } catch (IOException e) {
            throw new DocumentExtractionException(DocumentExtractionException.Reason.PARSE_FAILED,
                    "Could not parse DOCX: " + e.getMessage(), e);
        } catch (RuntimeException e) {
            // POI throws unchecked exceptions (e.g. POIXMLException/OLE2NotOfficeXmlFileException)
            // on a corrupt or non-OOXML file — normalize to the same named failure as a checked one.
            throw new DocumentExtractionException(DocumentExtractionException.Reason.PARSE_FAILED,
                    "Could not parse DOCX: " + e.getMessage(), e);
        }
    }
}
