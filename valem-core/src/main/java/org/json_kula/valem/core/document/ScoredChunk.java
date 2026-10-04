package org.json_kula.valem.core.document;

/** A {@link DocumentChunk} paired with its relevance score from {@link LexicalRanker}. */
public record ScoredChunk(DocumentChunk chunk, double score) {}
