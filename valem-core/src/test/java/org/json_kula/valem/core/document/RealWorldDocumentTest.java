package org.json_kula.valem.core.document;

import org.junit.jupiter.api.Test;

import java.io.FileInputStream;
import java.io.InputStream;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Runs the deterministic half of the document-to-spec pipeline (extraction, chunking, lexical
 * ranking — everything short of the LLM call) against a real, publicly published government PDF,
 * not a synthetic PDFBox-generated fixture: IRS Revenue Procedure 2025-32, the official 2026 annual
 * inflation adjustments (tax brackets, phase-outs, penalty amounts). Downloaded 2026-09-28 from
 * https://www.irs.gov/pub/irs-drop/rp-25-32.pdf (public, unauthenticated, in the public domain as a
 * US government work) and committed as a small (~260 KB) fixture.
 *
 * <p>Synthetic fixtures elsewhere in this package prove the algorithms are correct in isolation; this
 * proves they still work against real-world PDF quirks a generated fixture can't reproduce (embedded
 * fonts, page headers/footers, multi-column-ish tables, non-ASCII punctuation from the source
 * document's typesetting).
 */
class RealWorldDocumentTest {

    private static final String FIXTURE = "src/test/resources/fixtures/irs-rp-2025-32.pdf";

    private static ExtractedDocument load() throws Exception {
        try (InputStream in = new FileInputStream(FIXTURE)) {
            return new PdfTextExtractor().extract(in, "irs-rp-2025-32.pdf");
        }
    }

    @Test
    void extracts_every_page_of_a_real_36_page_government_pdf() throws Exception {
        ExtractedDocument doc = load();

        assertThat(doc.pageCount()).isEqualTo(36);
        assertThat(doc.isEmpty()).isFalse();
        assertThat(doc.locationLabel()).isEqualTo("page");
        // Spot-check real content survived extraction intact, including a dollar figure.
        assertThat(doc.fullText()).contains("2026").contains("$403,500");
    }

    @Test
    void the_target_chunk_survives_lexical_prefiltering_into_the_topK_candidates() throws Exception {
        ExtractedDocument doc = load();
        List<DocumentChunk> chunks = DocumentChunker.chunk(doc, 4000); // production default

        // Section .26 "Qualified Business Income" (page 21) introduces the §199A threshold/phase-in
        // table whose figures land on page 22: "Married Individuals Filing Joint Returns $403,500
        // $553,500".
        //
        // Honest finding from writing this test: this query does NOT rank that chunk #1 — a
        // different real table (Earned Income Credit, pages 14-15) scores higher, because it happens
        // to repeat "income"/"amount"/"married"/"joint" many times over, and the no-stemming
        // tokenizer treats the table's "Returns" (plural) as a different token from the query's
        // "return" (singular). That is a real, expected limitation of simple term-overlap ranking on
        // a real document with several similarly-worded tables — exactly what the vision doc's Open
        // Question #1 means by "start lexical, measure, upgrade if misses are common," and exactly
        // why the product design sends the LLM several ranked candidates (AC-2) rather than only the
        // #1 lexical match: a semantic reader can still pick the right one out of a few, even when the
        // cheap prefilter's top rank is wrong. This test asserts the guarantee the prefilter actually
        // needs to hold — the correct chunk is somewhere IN the candidate set the LLM will see, at
        // the production topK — not that lexical ranking alone gets first place right.
        List<ScoredChunk> ranked = LexicalRanker.rank(chunks,
                "What is the qualified business income threshold amount for married individuals "
                + "filing a joint return?", 6);

        assertThat(ranked)
                .as("the QBI table must be among the top-6 candidates sent to the LLM, even if not ranked #1")
                .anySatisfy(sc -> assertThat(sc.chunk().text())
                        .contains("$403,500")
                        .contains("Married Individuals Filing Joint Returns"));
    }

    @Test
    void chunking_stays_bounded_on_a_real_document_not_just_synthetic_fixtures() throws Exception {
        ExtractedDocument doc = load();
        List<DocumentChunk> chunks = DocumentChunker.chunk(doc, 4000);

        assertThat(chunks).isNotEmpty();
        for (DocumentChunk chunk : chunks) {
            assertThat(chunk.text().length()).isLessThanOrEqualTo(4000);
        }
    }
}
