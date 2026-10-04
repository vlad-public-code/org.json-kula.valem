package org.json_kula.valem.core.document;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Deterministic, LLM-free relevance ranking of {@link DocumentChunk}s against a free-text query —
 * term-frequency overlap plus an exact-phrase bonus. No embedding model, no external service: this is
 * what keeps a scan/search step's cost bounded by top-K rather than by document length, on both the
 * REST scan phase and the MCP {@code search_document} tool (see v1 design spec §3, §5.2's "start
 * lexical" recommendation).
 */
public final class LexicalRanker {

    private LexicalRanker() {}

    private static final Pattern TOKEN = Pattern.compile("[\\p{L}\\p{N}]+");

    private static final Set<String> STOPWORDS = Set.of(
            "the", "a", "an", "of", "to", "in", "is", "are", "for", "and", "or", "on", "what",
            "how", "my", "does", "do", "i", "it", "this", "that", "with", "as", "at", "by", "be",
            "was", "were", "if", "when", "me", "will", "would", "can", "could");

    /**
     * Ranks {@code chunks} against {@code query}, returning at most {@code topK} entries, highest
     * score first (ties broken by chunk order, so results are deterministic).
     */
    public static List<ScoredChunk> rank(List<DocumentChunk> chunks, String query, int topK) {
        if (topK <= 0) throw new IllegalArgumentException("topK must be > 0: " + topK);

        List<String> queryTerms = tokenize(query).stream()
                .filter(t -> !STOPWORDS.contains(t))
                .distinct()
                .toList();
        String normalizedQuery = query == null ? "" : query.strip().toLowerCase(Locale.ROOT);

        List<ScoredChunk> scored = new ArrayList<>(chunks.size());
        for (DocumentChunk chunk : chunks) {
            scored.add(new ScoredChunk(chunk, score(chunk, queryTerms, normalizedQuery)));
        }
        return scored.stream()
                .sorted(Comparator.comparingDouble(ScoredChunk::score).reversed()
                        .thenComparingInt(sc -> sc.chunk().chunkId()))
                .limit(topK)
                .toList();
    }

    private static double score(DocumentChunk chunk, List<String> queryTerms, String normalizedQuery) {
        if (queryTerms.isEmpty()) return 0.0;
        String lowerText = chunk.text().toLowerCase(Locale.ROOT);
        Map<String, Long> freq = tokenize(lowerText).stream()
                .collect(Collectors.groupingBy(t -> t, Collectors.counting()));

        double score = 0.0;
        for (String term : queryTerms) {
            long count = freq.getOrDefault(term, 0L);
            if (count > 0) score += 1.0 + Math.log(count);
        }
        if (!normalizedQuery.isBlank() && lowerText.contains(normalizedQuery)) score += 5.0;
        return score;
    }

    private static List<String> tokenize(String s) {
        if (s == null || s.isBlank()) return List.of();
        List<String> out = new ArrayList<>();
        Matcher m = TOKEN.matcher(s.toLowerCase(Locale.ROOT));
        while (m.find()) out.add(m.group());
        return out;
    }
}
