package org.json_kula.valem.core.document;

/**
 * A contiguous group of pages (or a split of one over-long page) sized to fit one LLM turn.
 * {@code startPage}/{@code endPage} are inclusive, 1-based, and equal for a single-page (or
 * within-page) chunk.
 */
public record DocumentChunk(int chunkId, int startPage, int endPage, String text) {

    public DocumentChunk {
        if (startPage < 1 || endPage < startPage)
            throw new IllegalArgumentException("invalid page range: " + startPage + ".." + endPage);
        text = text == null ? "" : text;
    }

    /** e.g. {@code "3"} for a single page, {@code "3-5"} for a grouped range. */
    public String pageLabel() {
        return startPage == endPage ? String.valueOf(startPage) : startPage + "-" + endPage;
    }
}
