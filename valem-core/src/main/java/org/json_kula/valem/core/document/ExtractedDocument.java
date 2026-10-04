package org.json_kula.valem.core.document;

import java.util.List;

/**
 * The result of extracting text from an uploaded PDF/DOCX: the original filename plus one
 * {@link DocumentPage} per page (PDF) or pseudo-page (DOCX — see {@code DocxTextExtractor}).
 *
 * <p>{@code sourceKind} distinguishes a real page-numbered source from an approximated one so callers
 * (the scan prompt, the REST/MCP response shapes) never present a DOCX section as if it were a citable
 * PDF page number — see the v1 design spec §4.2 / AC-10.
 */
public record ExtractedDocument(String sourceFilename, SourceKind sourceKind, List<DocumentPage> pages) {

    public enum SourceKind { PDF, DOCX }

    public ExtractedDocument {
        pages = pages == null ? List.of() : List.copyOf(pages);
    }

    /** Human-facing label for a location within this document ("page" for PDF, "section" for DOCX). */
    public String locationLabel() {
        return sourceKind == SourceKind.PDF ? "page" : "section";
    }

    public int pageCount() {
        return pages.size();
    }

    public int charCount() {
        return pages.stream().mapToInt(p -> p.text().length()).sum();
    }

    public String fullText() {
        StringBuilder sb = new StringBuilder(charCount() + pages.size() * 8);
        for (DocumentPage p : pages) {
            if (!sb.isEmpty()) sb.append("\n\n");
            sb.append(p.text());
        }
        return sb.toString();
    }

    /**
     * True when there is no usable text — either no pages at all, or every page is blank. Checked
     * against {@link String#isBlank()} per page, not {@link #charCount()}: a scanned-image PDF's pages
     * are typically not zero-length after extraction — PDFBox's stripper still emits trailing
     * whitespace/newlines per page — so a raw char-count check would silently miss the "scanned
     * document, no real text" case this method exists to catch (vision doc AC-1).
     */
    public boolean isEmpty() {
        return pages.isEmpty() || pages.stream().allMatch(p -> p.text().isBlank());
    }
}
