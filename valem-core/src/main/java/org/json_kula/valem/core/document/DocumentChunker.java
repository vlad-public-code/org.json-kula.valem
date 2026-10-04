package org.json_kula.valem.core.document;

import java.util.ArrayList;
import java.util.List;

/**
 * Splits an {@link ExtractedDocument} into {@link DocumentChunk}s no larger than a caller-supplied
 * character cap, so a scan/search step's cost is bounded per chunk regardless of overall document
 * length (see document-to-spec-v1-design.md §3).
 *
 * <p>Consecutive pages are grouped up to the cap; a single page that alone exceeds the cap is split
 * into sequential same-page chunks rather than silently truncated (never drop text — the caller
 * decides what to keep via top-K ranking, not this class).
 */
public final class DocumentChunker {

    private DocumentChunker() {}

    public static List<DocumentChunk> chunk(ExtractedDocument doc, int maxCharsPerChunk) {
        if (maxCharsPerChunk <= 0)
            throw new IllegalArgumentException("maxCharsPerChunk must be > 0: " + maxCharsPerChunk);

        List<DocumentChunk> out = new ArrayList<>();
        int chunkId = 0;
        int curStart = -1, curEnd = -1;
        StringBuilder cur = new StringBuilder();

        for (DocumentPage page : doc.pages()) {
            String text = page.text();
            if (text.isBlank()) continue;

            if (text.length() > maxCharsPerChunk) {
                if (!cur.isEmpty()) {
                    out.add(new DocumentChunk(chunkId++, curStart, curEnd, cur.toString()));
                    cur.setLength(0);
                    curStart = -1;
                    curEnd = -1;
                }
                for (int offset = 0; offset < text.length(); offset += maxCharsPerChunk) {
                    int end = Math.min(offset + maxCharsPerChunk, text.length());
                    out.add(new DocumentChunk(chunkId++, page.pageNumber(), page.pageNumber(),
                            text.substring(offset, end)));
                }
                continue;
            }

            int prospectiveLen = cur.isEmpty() ? text.length() : cur.length() + 2 + text.length();
            if (!cur.isEmpty() && prospectiveLen > maxCharsPerChunk) {
                out.add(new DocumentChunk(chunkId++, curStart, curEnd, cur.toString()));
                cur.setLength(0);
                curStart = -1;
                curEnd = -1;
            }
            if (cur.isEmpty()) {
                curStart = page.pageNumber();
            } else {
                cur.append("\n\n");
            }
            cur.append(text);
            curEnd = page.pageNumber();
        }

        if (!cur.isEmpty()) out.add(new DocumentChunk(chunkId, curStart, curEnd, cur.toString()));
        return out;
    }
}
