package org.json_kula.valem.core.document;

import java.util.List;

/**
 * Outcome of a document scan — mirrors {@code SpecGenerator.GenerationResult}'s
 * success/failure shape by convention. {@code Found} never carries zero candidates; "nothing found"
 * is always a {@code NotFound} with a reason, never an empty success (vision doc AC-2).
 */
public sealed interface DocumentScanResult permits DocumentScanResult.Found, DocumentScanResult.NotFound {

    record Found(List<FormulaCandidate> candidates) implements DocumentScanResult {
        public Found {
            if (candidates == null || candidates.isEmpty())
                throw new IllegalArgumentException("Found must carry at least one candidate");
            candidates = List.copyOf(candidates);
        }
    }

    record NotFound(String reason) implements DocumentScanResult {
        public NotFound {
            reason = (reason == null || reason.isBlank()) ? "No formula found" : reason;
        }
    }
}
