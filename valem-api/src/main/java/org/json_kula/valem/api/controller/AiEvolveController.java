package org.json_kula.valem.api.controller;

import org.json_kula.valem.core.llm.LlmClient;
import org.json_kula.valem.core.llm.SpecGenerator;
import org.json_kula.valem.core.model.ModelSpec;
import org.json_kula.valem.service.ModelService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import java.util.Optional;

@RestController
public class AiEvolveController {

    private static final Logger log = LoggerFactory.getLogger(AiEvolveController.class);

    private final Optional<SpecGenerator> specGenerator;
    private final ModelService service;

    public AiEvolveController(Optional<SpecGenerator> specGenerator, ModelService service) {
        this.specGenerator = specGenerator;
        this.service = service;
    }

    private static final int MAX_DESCRIPTION_LENGTH = 5_000;

    /**
     * {@code includeView} is a nullable tri-state: {@code null} (the default when omitted) means
     * "auto" — evolve the view when the current spec already has a {@code viewDefinition}, so a spec
     * with a UI keeps it in sync; {@code true}/{@code false} force it on/off.
     */
    record AiEvolveRequest(String description, Boolean includeView) {}

    @PostMapping("/models/{id}/spec/evolve/ai")
    ResponseEntity<?> evolveWithAi(
            @PathVariable("id") String id,
            @RequestBody AiEvolveRequest req) {

        if (specGenerator.isEmpty()) {
            log.warn("AI evolve requested for model={} but LLM is not configured", id);
            return ResponseEntity.status(503)
                    .body(Map.of("error", "LLM not configured — set valem.llm.api-key"));
        }

        if (req.description() == null || req.description().isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "description is required"));
        }
        if (req.description().length() > MAX_DESCRIPTION_LENGTH) {
            return ResponseEntity.badRequest().body(
                    Map.of("error", "description exceeds maximum length of " + MAX_DESCRIPTION_LENGTH + " characters"));
        }

        log.info("AI evolve: modelId={} descriptionLen={}", id, req.description().length());

        ModelSpec currentSpec = service.getSpec(id);
        boolean includeView = req.includeView() != null
                ? req.includeView() : currentSpec.viewDefinition() != null;

        try {
            var evolution = specGenerator.get()
                    .generateEvolution(currentSpec, req.description(), includeView, e -> {});
            ModelSpec evolved = service.evolveSpec(id, evolution);
            log.info("AI evolve succeeded: modelId={} newVersion={}", id, evolved.version());
            return ResponseEntity.ok(Map.of("version", evolved.version(), "spec", evolved));
        } catch (LlmClient.LlmException e) {
            log.warn("AI evolve: LLM failed for modelId={}: {}", id, e.getMessage());
            return ResponseEntity.status(502)
                    .body(Map.of("error", e.getMessage()));
        } catch (IllegalArgumentException e) {
            log.warn("AI evolve: evolved spec invalid for modelId={}: {}", id, e.getMessage());
            return ResponseEntity.unprocessableEntity()
                    .body(Map.of("valid", false, "error", e.getMessage()));
        }
    }
}
