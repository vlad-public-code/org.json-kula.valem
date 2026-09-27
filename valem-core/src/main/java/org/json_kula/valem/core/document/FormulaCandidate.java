package org.json_kula.valem.core.document;

/**
 * One formula/rule the scan phase located inside an uploaded document, always carrying a verbatim
 * quote and a location so the user can verify it before compiling (vision doc AC-2/AC-5).
 *
 * @param quote       verbatim excerpt — never a paraphrase presented as a quote
 * @param page        1-based page/section number the quote came from
 * @param sectionHint a short human label the model inferred (e.g. "§4.2 Coinsurance"), may be blank
 * @param confidence  clamped to [0,1]
 * @param rationale   one sentence on why this excerpt answers the user's ask
 */
public record FormulaCandidate(String quote, int page, String sectionHint, double confidence,
                               String rationale) {

    public FormulaCandidate {
        if (quote == null || quote.isBlank())
            throw new IllegalArgumentException("quote must not be blank");
        if (page < 1) throw new IllegalArgumentException("page must be >= 1: " + page);
        confidence = Math.max(0.0, Math.min(1.0, confidence));
        sectionHint = sectionHint == null ? "" : sectionHint;
        rationale = rationale == null ? "" : rationale;
    }
}
