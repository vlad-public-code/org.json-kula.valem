package org.json_kula.valem.core.engine;

import com.fasterxml.jackson.annotation.JsonValue;
import com.fasterxml.jackson.databind.JsonNode;

import java.util.List;
import java.util.Map;

/**
 * The trust-layer verification report for a model — the data behind the "built &amp; checked against N
 * cases" badge (docs/sandbox/trust-layer.md). Produced on demand by {@link SpecVerifier}; a pure
 * function of the spec and its embedded test cases. No state, no persistence.
 *
 * <p><b>Honesty contract.</b> The report claims only that the model's own logic is internally
 * consistent and held for example inputs — never that any real-world figure it cites is factually
 * correct (the self-tests assert against the model's own constants). {@link #checkedCount} counts only
 * <em>verifiable</em> cases (see {@link VerificationClassifier}); un-verifiable ones are surfaced via
 * {@link #unverifiableCount}, never silently passed.
 *
 * @param modelId           the model this report is for
 * @param specVersion       the spec version it was computed against (the cache key alongside modelId)
 * @param state             {@code green} (all verifiable pass) / {@code amber} (some fail) / {@code neutral} (none verifiable)
 * @param checkedCount      number of verifiable (auto-checkable) cases — the honest denominator
 * @param passedCount       verifiable cases with no verifiable failures
 * @param unverifiableCount cases excluded from the count because nothing in them is auto-checkable
 * @param cases             per-case detail for the click-through checks panel
 */
public record VerificationReport(
        String modelId,
        String specVersion,
        State state,
        int checkedCount,
        int passedCount,
        int unverifiableCount,
        List<CaseReport> cases) {

    /** Badge state, serialised lowercase. */
    public enum State {
        GREEN, AMBER, NEUTRAL;

        @JsonValue
        public String jsonValue() { return name().toLowerCase(); }
    }

    /**
     * One test case's outcome, rendered as human rows by the checks panel.
     *
     * @param description the case description
     * @param verifiable  whether this case has any auto-checkable assertion (counts toward the badge)
     * @param passed      {@code true}/{@code false} for a verifiable case; {@code null} when not verifiable
     * @param given       the mutation inputs the case applied
     * @param expect      the asserted expected values
     * @param actual      the observed values for assertions that failed (empty when the case passed)
     * @param reason      why an un-verifiable case is excluded; {@code null} for verifiable cases
     */
    public record CaseReport(
            String description,
            boolean verifiable,
            Boolean passed,
            Map<String, JsonNode> given,
            Map<String, JsonNode> expect,
            Map<String, JsonNode> actual,
            String reason) {}
}
