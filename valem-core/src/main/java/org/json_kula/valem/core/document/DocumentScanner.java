package org.json_kula.valem.core.document;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.json_kula.valem.core.llm.LlmClient;
import org.json_kula.valem.core.llm.SpecGenerationPrompt;
import org.json_kula.valem.core.llm.SpecGenerator;

import java.util.ArrayList;
import java.util.List;

/**
 * Locates a candidate formula/rule inside an {@link ExtractedDocument} for a user's one-line ask.
 *
 * <p>Cost/latency are bounded by {@code topK} × {@code maxCharsPerChunk} (capped again by
 * {@code maxTotalChars}), not by document length — the whole point of the lexical-prefilter design
 * (v1 design spec §2, §4.3): {@link DocumentChunker} splits the document, {@link LexicalRanker} scores
 * chunks against the ask with no LLM/network call, and only the top few are ever sent to the model, in
 * a single structured-output turn.
 *
 * <p>Pure with respect to I/O beyond the injected {@link LlmClient} — no Spring, mirroring
 * {@code SpecGenerator}'s placement in {@code valem-core}.
 */
public final class DocumentScanner {

    private final LlmClient    llm;
    private final ObjectMapper mapper;
    private final int          topK;
    private final int          maxCharsPerChunk;
    private final int          maxTotalChars;

    public DocumentScanner(LlmClient llm, ObjectMapper mapper, int topK, int maxCharsPerChunk,
                           int maxTotalChars) {
        if (topK <= 0) throw new IllegalArgumentException("topK must be > 0: " + topK);
        if (maxCharsPerChunk <= 0)
            throw new IllegalArgumentException("maxCharsPerChunk must be > 0: " + maxCharsPerChunk);
        if (maxTotalChars < maxCharsPerChunk)
            throw new IllegalArgumentException("maxTotalChars must be >= maxCharsPerChunk");
        this.llm              = llm;
        this.mapper           = mapper;
        this.topK             = topK;
        this.maxCharsPerChunk = maxCharsPerChunk;
        this.maxTotalChars    = maxTotalChars;
    }

    public DocumentScanResult scan(ExtractedDocument doc, String userAsk) {
        if (doc == null || doc.isEmpty())
            return new DocumentScanResult.NotFound("Document has no extractable text");
        if (userAsk == null || userAsk.isBlank())
            return new DocumentScanResult.NotFound("No request given to search the document for");

        List<DocumentChunk> chunks = DocumentChunker.chunk(doc, maxCharsPerChunk);
        if (chunks.isEmpty())
            return new DocumentScanResult.NotFound("Document has no extractable text");

        List<ScoredChunk> top = capTotalChars(LexicalRanker.rank(chunks, userAsk, topK));

        SpecGenerationPrompt.PromptParts prompt =
                DocumentScanPrompt.build(userAsk, top, doc.locationLabel());
        JsonNode schema = DocumentScanPrompt.responseSchema(mapper);

        String raw;
        try {
            raw = llm.complete(prompt, new LlmClient.CompletionOptions(0.0, schema));
        } catch (LlmClient.LlmException e) {
            return new DocumentScanResult.NotFound("LLM call failed: " + e.getMessage());
        }
        return parse(raw);
    }

    /**
     * Trims the ranked list to {@code maxTotalChars} total, as a safety net for the case where the
     * top-K chunks are individually near {@code maxCharsPerChunk} and would together still be too
     * large. Always keeps at least the single top-ranked chunk, even if it alone exceeds the cap
     * (better to send one over-cap chunk than none at all).
     */
    private List<ScoredChunk> capTotalChars(List<ScoredChunk> ranked) {
        if (ranked.isEmpty()) return ranked;
        List<ScoredChunk> out = new ArrayList<>();
        int total = 0;
        for (ScoredChunk sc : ranked) {
            int len = sc.chunk().text().length();
            if (!out.isEmpty() && total + len > maxTotalChars) break;
            out.add(sc);
            total += len;
        }
        return out;
    }

    private DocumentScanResult parse(String raw) {
        JsonNode node;
        try {
            node = mapper.readTree(SpecGenerator.extractJson(raw));
        } catch (Exception e) {
            return new DocumentScanResult.NotFound("Malformed scan response: " + e.getMessage());
        }

        JsonNode candidatesNode = node.path("candidates");
        List<FormulaCandidate> candidates = new ArrayList<>();
        if (candidatesNode.isArray()) {
            for (JsonNode c : candidatesNode) {
                String quote = c.path("quote").asText("");
                int    page  = c.path("page").asInt(0);
                // Skip malformed individual entries rather than failing the whole scan over one bad
                // element — a partial, honest candidate list beats an all-or-nothing failure here.
                if (quote.isBlank() || page < 1) continue;
                candidates.add(new FormulaCandidate(
                        quote,
                        page,
                        c.path("sectionHint").asText(""),
                        c.path("confidence").asDouble(0.5),
                        c.path("rationale").asText("")));
            }
        }

        boolean found = node.path("found").asBoolean(false);
        if (!found || candidates.isEmpty()) {
            String reason = node.path("reason").asText("No formula found in the searched excerpts");
            return new DocumentScanResult.NotFound(reason);
        }
        return new DocumentScanResult.Found(candidates);
    }
}
