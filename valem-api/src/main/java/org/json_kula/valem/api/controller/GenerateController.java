package org.json_kula.valem.api.controller;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.json_kula.valem.core.graph.ModelSpecValidator;
import org.json_kula.valem.core.graph.SpecEvolution;
import org.json_kula.valem.core.llm.LlmClient;
import org.json_kula.valem.core.llm.SpecGenerationPrompt;
import org.json_kula.valem.core.llm.SpecGenerator;
import org.json_kula.valem.core.model.ModelSpec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import java.util.Optional;

@RestController
public class GenerateController {

    private static final Logger log = LoggerFactory.getLogger(GenerateController.class);

    private final Optional<LlmClient> llmClient;
    private final ObjectMapper mapper;

    public GenerateController(Optional<LlmClient> llmClient, ObjectMapper mapper) {
        this.llmClient = llmClient;
        this.mapper = mapper;
    }

    private static final int MAX_PROMPT_LENGTH = 100_000;
    private static final int MAX_DESCRIPTION_LENGTH = 5_000;

    record PreviewRequest(String modelId, String domainDescription, boolean includeView) {}

    /**
     * Either a ready-made {@code prompt} (built/edited by the caller — e.g. the DevTools UI), or a
     * {@code domainDescription} from which the prompt is built server-side and never returned to the
     * client (used by the sandbox so the raw prompt stays out of the browser — it appears only in the
     * LLM interaction log). {@code prompt} wins when both are present.
     */
    record GenerateRequest(String modelId, String prompt, String domainDescription, boolean includeView) {}
    record EvolutionPreviewRequest(String modelId, JsonNode currentSpec, String evolutionRequest, boolean includeView) {}
    record EvolutionGenerateRequest(String modelId, String prompt) {}

    /** Returns the full prompt string without calling the LLM — lets the UI show and edit it first. */
    @PostMapping("/models/generate/preview")
    ResponseEntity<Map<String, Object>> preview(@RequestBody PreviewRequest req) {
        if (req.domainDescription() != null && req.domainDescription().length() > MAX_DESCRIPTION_LENGTH) {
            return ResponseEntity.badRequest().body(Map.of(
                    "error", "domainDescription exceeds maximum length of " + MAX_DESCRIPTION_LENGTH + " characters"));
        }
        log.debug("Preview prompt: modelId={} includeView={}", req.modelId(), req.includeView());
        String prompt = SpecGenerationPrompt.initialPrompt(req.modelId(), req.domainDescription(), req.includeView());
        return ResponseEntity.ok(Map.of("prompt", prompt));
    }

    /** Returns the evolution prompt without calling the LLM. */
    @PostMapping("/models/generate/evolution/preview")
    ResponseEntity<Map<String, Object>> evolutionPreview(@RequestBody EvolutionPreviewRequest req) {
        log.debug("Evolution preview: modelId={} includeView={}", req.modelId(), req.includeView());
        String specJson;
        try {
            specJson = mapper.writeValueAsString(req.currentSpec());
        } catch (JsonProcessingException e) {
            return ResponseEntity.badRequest().body(Map.of("error", "Could not serialize currentSpec: " + e.getOriginalMessage()));
        }
        String prompt = SpecGenerationPrompt.evolutionPrompt(
                req.modelId(), specJson, req.evolutionRequest(), req.includeView());
        return ResponseEntity.ok(Map.of("prompt", prompt));
    }

    /** Sends the evolution prompt to Claude and returns the parsed SpecEvolution. */
    @PostMapping("/models/generate/evolution")
    ResponseEntity<Map<String, Object>> generateEvolution(@RequestBody EvolutionGenerateRequest req) {
        if (llmClient.isEmpty()) {
            log.warn("Generate evolution requested but LLM is not configured");
            return ResponseEntity.status(503)
                    .body(Map.of("error", "LLM not configured — set valem.llm.api-key"));
        }
        if (req.prompt() != null && req.prompt().length() > MAX_PROMPT_LENGTH) {
            return ResponseEntity.badRequest().body(Map.of(
                    "error", "prompt exceeds maximum length of " + MAX_PROMPT_LENGTH + " characters"));
        }
        log.info("Generate evolution: modelId={} promptLen={}", req.modelId(),
                req.prompt() == null ? 0 : req.prompt().length());
        try {
            String rawResponse = llmClient.get().complete(req.prompt());
            String repairedJson = SpecGenerator.repairConstraintPolicy(
                    SpecGenerator.fixExpressions(
                            SpecGenerator.repairJson(
                                    SpecGenerator.collapseStringNewlines(
                                            SpecGenerator.extractJson(rawResponse)))), mapper);

            SpecEvolution evolution;
            try {
                evolution = mapper.readValue(repairedJson, SpecEvolution.class);
            } catch (JsonProcessingException e) {
                log.warn("Generate evolution: LLM returned invalid JSON for modelId={}: {}", req.modelId(), e.getOriginalMessage());
                return ResponseEntity.unprocessableEntity().body(Map.of(
                        "valid", false,
                        "error", "LLM response is not valid SpecEvolution JSON: " + e.getOriginalMessage(),
                        "rawResponse", rawResponse));
            }

            log.info("Generate evolution succeeded: modelId={}", req.modelId());
            return ResponseEntity.ok(Map.of("valid", true, "evolution", evolution));
        } catch (LlmClient.LlmException e) {
            log.error("Generate evolution: LLM call failed for modelId={}", req.modelId(), e);
            return ResponseEntity.status(502).body(Map.of("error", e.getMessage()));
        }
    }

    /**
     * Generates a spec. Accepts either a ready-made {@code prompt} (built/edited by the caller) or a
     * {@code domainDescription} that is expanded into the prompt server-side — so a caller that wants
     * to keep the raw prompt off the wire (the sandbox) can send just the description.
     */
    @PostMapping("/models/generate")
    ResponseEntity<Map<String, Object>> generate(@RequestBody GenerateRequest req) {
        if (llmClient.isEmpty()) {
            log.warn("Generate requested but LLM is not configured (set valem.llm.api-key)");
            return ResponseEntity.status(503)
                    .body(Map.of("error", "LLM not configured — set valem.llm.api-key"));
        }

        String prompt = req.prompt();
        if (prompt == null || prompt.isBlank()) {
            // No prompt supplied — build it from the description (kept server-side).
            if (req.domainDescription() == null || req.domainDescription().isBlank()) {
                return ResponseEntity.badRequest().body(Map.of(
                        "error", "provide either a prompt or a domainDescription"));
            }
            if (req.domainDescription().length() > MAX_DESCRIPTION_LENGTH) {
                return ResponseEntity.badRequest().body(Map.of(
                        "error", "domainDescription exceeds maximum length of " + MAX_DESCRIPTION_LENGTH + " characters"));
            }
            prompt = SpecGenerationPrompt.initialPrompt(req.modelId(), req.domainDescription(), req.includeView());
        } else if (prompt.length() > MAX_PROMPT_LENGTH) {
            return ResponseEntity.badRequest().body(Map.of(
                    "error", "prompt exceeds maximum length of " + MAX_PROMPT_LENGTH + " characters"));
        }
        log.info("Generate request: modelId={} promptLen={}", req.modelId(), prompt.length());
        try {
            String rawResponse = llmClient.get().complete(prompt);
            String repairedJson = SpecGenerator.repairConstraintPolicy(
                    SpecGenerator.fixExpressions(
                            SpecGenerator.repairJson(
                                    SpecGenerator.collapseStringNewlines(
                                            SpecGenerator.extractJson(rawResponse)))), mapper);

            ModelSpec spec;
            try {
                spec = mapper.readValue(repairedJson, ModelSpec.class);
            } catch (JsonProcessingException e) {
                log.warn("Generate: LLM returned invalid JSON for modelId={}: {}", req.modelId(), e.getOriginalMessage());
                return ResponseEntity.unprocessableEntity().body(Map.of(
                        "valid", false,
                        "errors", java.util.List.of(Map.of(
                                "location", "root",
                                "message", "LLM response is not valid JSON: " + e.getOriginalMessage())),
                        "rawResponse", rawResponse));
            }

            ModelSpecValidator.ValidationResult validation = ModelSpecValidator.validate(spec);
            if (validation.isValid()) {
                // Keep the schema consistent with the derivations: a derived field must be readOnly
                // so clients/tools don't try to write it (the SpecGenerator loop does this too).
                spec = SpecGenerator.markDerivedFieldsReadOnly(spec);
                log.info("Generate succeeded: modelId={}", req.modelId());
                return ResponseEntity.ok(Map.of(
                        "valid", true,
                        "spec", spec));
            } else {
                log.warn("Generate: spec failed validation for modelId={} ({} errors)",
                        req.modelId(), validation.errors().size());
                return ResponseEntity.unprocessableEntity().body(Map.of(
                        "valid", false,
                        "errors", validation.errors(),
                        "rawResponse", rawResponse));
            }
        } catch (LlmClient.LlmException e) {
            log.error("Generate: LLM call failed for modelId={}", req.modelId(), e);
            return ResponseEntity.status(502).body(Map.of("error", e.getMessage()));
        }
    }
}
