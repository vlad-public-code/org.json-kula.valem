package org.json_kula.valem.core.document;

/**
 * One page (PDF) or pseudo-page (DOCX, where no reliable page model exists) of extracted document
 * text. {@code pageNumber} is 1-based.
 */
public record DocumentPage(int pageNumber, String text) {

    public DocumentPage {
        if (pageNumber < 1) throw new IllegalArgumentException("pageNumber must be >= 1: " + pageNumber);
        text = text == null ? "" : text;
    }
}
