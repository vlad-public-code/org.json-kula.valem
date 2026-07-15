package org.json_kula.valem.client;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.JsonNode;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Wire DTOs mirroring the Valem REST/WebSocket responses. Standalone (they do not reference the
 * engine module) and lenient on unknown properties so a newer server never breaks an older client.
 */
public final class ValemTypes {

    private ValemTypes() {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ModelInfo(
            String id, String version,
            int derivationCount, int metaDerivationCount,
            int constraintCount, int effectCount) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record CreateModelResponse(String id, String status) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ConstraintViolation(String constraintId, String message, String policy) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record DispatchedEffect(String effectId, String emit, JsonNode payload) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record DerivationTrace(
            String targetPath, String expression, List<String> inputPaths,
            JsonNode result, Boolean constraintPassed, String errorMessage) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record MutationResponse(
            boolean success,
            List<String> mutatedPaths,
            List<String> derivedUpdated,
            List<ConstraintViolation> flaggedConstraints,
            List<DispatchedEffect> dispatchedEffects,
            List<DerivationTrace> traces,
            Map<String, JsonNode> viewDelta) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record AuditRecord(
            String modelId, long sequence, String timestamp, String modelVersion, String source,
            Map<String, JsonNode> mutations, List<String> derivedUpdated,
            List<String> flaggedConstraints, List<String> dispatchedEffects,
            List<DerivationTrace> traces, String prevHash, String hash) {}

    /** Result of {@link ValemClient#verifyAudit}. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record AuditVerification(
            boolean valid, long recordsChecked, Long firstBrokenSequence, String detail) {}

    /** Filter for {@link ValemClient#audit}. Any field may be null (unbounded on that axis). */
    public record AuditQuery(String pathPrefix, Instant from, Instant to, Integer limit) {
        public static AuditQuery all() { return new AuditQuery(null, null, null, null); }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ChangeEvent(
            String modelId,
            List<String> mutatedPaths,
            List<String> derivedUpdated,
            List<ConstraintViolation> flaggedConstraints,
            List<DispatchedEffect> dispatchedEffects) {}
}
