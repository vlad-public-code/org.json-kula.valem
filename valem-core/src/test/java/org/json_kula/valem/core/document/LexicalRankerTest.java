package org.json_kula.valem.core.document;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LexicalRankerTest {

    private static DocumentChunk chunk(int id, String text) {
        return new DocumentChunk(id, id + 1, id + 1, text);
    }

    @Test
    void ranks_chunk_containing_query_terms_above_one_that_does_not() {
        List<DocumentChunk> chunks = List.of(
                chunk(0, "Termination fee is $50 if you cancel within 30 days."),
                chunk(1, "This document describes the vehicle's paint color options."));

        List<ScoredChunk> ranked = LexicalRanker.rank(chunks, "what is the early termination fee?", 2);

        assertThat(ranked).hasSize(2);
        assertThat(ranked.get(0).chunk().chunkId()).isEqualTo(0);
        assertThat(ranked.get(0).score()).isGreaterThan(ranked.get(1).score());
    }

    @Test
    void exact_phrase_match_gets_a_bonus_over_partial_overlap() {
        List<DocumentChunk> chunks = List.of(
                chunk(0, "The early termination fee is calculated as follows."),
                chunk(1, "Fees may apply. Termination of service occurs on notice. Early bird gets none."));

        List<ScoredChunk> ranked = LexicalRanker.rank(chunks, "early termination fee", 2);

        assertThat(ranked.get(0).chunk().chunkId()).isEqualTo(0);
    }

    @Test
    void topK_limits_result_size() {
        List<DocumentChunk> chunks = List.of(chunk(0, "fee fee fee"), chunk(1, "fee"), chunk(2, "fee fee"));
        assertThat(LexicalRanker.rank(chunks, "fee", 1)).hasSize(1);
    }

    @Test
    void topK_larger_than_chunk_count_returns_all() {
        List<DocumentChunk> chunks = List.of(chunk(0, "fee"));
        assertThat(LexicalRanker.rank(chunks, "fee", 50)).hasSize(1);
    }

    @Test
    void empty_chunk_list_returns_empty_result() {
        assertThat(LexicalRanker.rank(List.of(), "anything", 5)).isEmpty();
    }

    @Test
    void blank_query_scores_everything_zero_but_still_returns_topK_deterministically() {
        List<DocumentChunk> chunks = List.of(chunk(0, "alpha"), chunk(1, "beta"), chunk(2, "gamma"));
        List<ScoredChunk> ranked = LexicalRanker.rank(chunks, "   ", 2);
        assertThat(ranked).hasSize(2);
        assertThat(ranked).allSatisfy(sc -> assertThat(sc.score()).isZero());
        // ties broken by chunk id order -> deterministic
        assertThat(ranked.get(0).chunk().chunkId()).isEqualTo(0);
        assertThat(ranked.get(1).chunk().chunkId()).isEqualTo(1);
    }

    @Test
    void stopwords_alone_do_not_inflate_score() {
        List<DocumentChunk> chunks = List.of(chunk(0, "the the the the the"));
        List<ScoredChunk> ranked = LexicalRanker.rank(chunks, "what is the", 1);
        assertThat(ranked.get(0).score()).isZero();
    }

    @Test
    void rejects_non_positive_topK() {
        assertThatThrownBy(() -> LexicalRanker.rank(List.of(), "x", 0))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
