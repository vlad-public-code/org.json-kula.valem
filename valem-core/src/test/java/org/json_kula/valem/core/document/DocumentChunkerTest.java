package org.json_kula.valem.core.document;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DocumentChunkerTest {

    private static ExtractedDocument doc(String... pageTexts) {
        List<DocumentPage> pages = new java.util.ArrayList<>();
        for (int i = 0; i < pageTexts.length; i++) pages.add(new DocumentPage(i + 1, pageTexts[i]));
        return new ExtractedDocument("test.pdf", ExtractedDocument.SourceKind.PDF, pages);
    }

    @Test
    void empty_document_produces_no_chunks() {
        assertThat(DocumentChunker.chunk(doc(), 1000)).isEmpty();
    }

    @Test
    void blank_pages_are_skipped() {
        assertThat(DocumentChunker.chunk(doc("", "   ", "\n"), 1000)).isEmpty();
    }

    @Test
    void small_pages_are_grouped_into_one_chunk() {
        List<DocumentChunk> chunks = DocumentChunker.chunk(doc("page one", "page two", "page three"), 1000);
        assertThat(chunks).hasSize(1);
        DocumentChunk chunk = chunks.get(0);
        assertThat(chunk.startPage()).isEqualTo(1);
        assertThat(chunk.endPage()).isEqualTo(3);
        assertThat(chunk.text()).contains("page one", "page two", "page three");
    }

    @Test
    void pages_split_into_separate_chunks_once_cap_exceeded() {
        // Each page is 10 chars; cap of 15 allows only one page per chunk (two pages = 22 with separator).
        List<DocumentChunk> chunks = DocumentChunker.chunk(doc("0123456789", "abcdefghij", "ABCDEFGHIJ"), 15);
        assertThat(chunks).hasSize(3);
        assertThat(chunks.get(0).startPage()).isEqualTo(1);
        assertThat(chunks.get(0).endPage()).isEqualTo(1);
        assertThat(chunks.get(1).startPage()).isEqualTo(2);
        assertThat(chunks.get(2).startPage()).isEqualTo(3);
    }

    @Test
    void oversized_single_page_is_split_sequentially_never_dropped() {
        String longText = "x".repeat(25); // cap 10 -> 3 chunks: 10,10,5
        List<DocumentChunk> chunks = DocumentChunker.chunk(doc(longText), 10);
        assertThat(chunks).hasSize(3);
        assertThat(chunks).allSatisfy(c -> {
            assertThat(c.startPage()).isEqualTo(1);
            assertThat(c.endPage()).isEqualTo(1);
        });
        String reassembled = chunks.stream().map(DocumentChunk::text).reduce("", String::concat);
        assertThat(reassembled).isEqualTo(longText);
    }

    @Test
    void oversized_page_flushes_pending_grouped_chunk_first() {
        List<DocumentChunk> chunks = DocumentChunker.chunk(doc("short", "y".repeat(20)), 10);
        assertThat(chunks).hasSize(3); // [short] flushed alone, then the oversized page split into 2
        assertThat(chunks.get(0).text()).isEqualTo("short");
        assertThat(chunks.get(0).endPage()).isEqualTo(1);
        assertThat(chunks.get(1).startPage()).isEqualTo(2);
        assertThat(chunks.get(2).startPage()).isEqualTo(2);
    }

    @Test
    void page_label_shows_range_only_when_pages_differ() {
        DocumentChunk single = new DocumentChunk(0, 3, 3, "x");
        DocumentChunk range = new DocumentChunk(1, 3, 5, "x");
        assertThat(single.pageLabel()).isEqualTo("3");
        assertThat(range.pageLabel()).isEqualTo("3-5");
    }

    @Test
    void rejects_non_positive_cap() {
        assertThatThrownBy(() -> DocumentChunker.chunk(doc("a"), 0))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
