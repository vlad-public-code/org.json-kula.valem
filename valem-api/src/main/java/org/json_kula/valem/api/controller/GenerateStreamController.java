package org.json_kula.valem.api.controller;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.json_kula.valem.core.llm.LlmClient;
import org.json_kula.valem.core.llm.LlmProgressEvent;
import org.json_kula.valem.core.llm.SpecGenerator;
import org.json_kula.valem.core.model.ModelSpec;
import org.json_kula.valem.service.ModelService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * SSE endpoints for long-running LLM generation flows.
 *
 * <p>Streams {@link LlmProgressEvent}s as {@code event: progress} SSE frames while the
 * {@link SpecGenerator} retry loop runs, then sends a terminal {@code event: done} frame
 * containing the result. The long-running work runs on a virtual thread so the HTTP
 * thread is immediately released after returning the {@link SseEmitter}.
 */
@RestController
public class GenerateStreamController {

    private static final Logger log = LoggerFactory.getLogger(GenerateStreamController.class);

    private static final long SSE_TIMEOUT_MS = 600_000L; // 10 min

    private static final int MAX_DESCRIPTION_LENGTH = 5_000;

    private final Optional<SpecGenerator> specGenerator;
    private final ModelService modelService;
    private final ObjectMapper mapper;

    public GenerateStreamController(Optional<SpecGenerator> specGenerator,
                                    ModelService modelService,
                                    ObjectMapper mapper) {
        this.specGenerator = specGenerator;
        this.modelService  = modelService;
        this.mapper        = mapper;
    }

    // ── Request records ───────────────────────────────────────────────────────

    record GenerateStreamRequest(String modelId, String domainDescription, boolean includeView) {}

    /**
     * {@code includeView} is nullable: {@code null} (default) auto-enables view evolution when the
     * current spec already has a {@code viewDefinition}; {@code true}/{@code false} force it.
     */
    record EvolveAiStreamRequest(String description, Boolean includeView) {}

    // ── Generate ──────────────────────────────────────────────────────────────

    /**
     * Runs the full {@link SpecGenerator#generate} loop and streams progress events.
     *
     * <p>On success sends {@code event: done} with {@code {"valid":true,"spec":{...}}};
     * on failure with {@code {"valid":false,"errors":[...],"rawResponse":"..."}}.
     */
    @PostMapping(path = "/models/generate/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter generateStream(@RequestBody GenerateStreamRequest req) {
        if (specGenerator.isEmpty()) {
            log.warn("Generate stream requested but LLM is not configured");
            SseEmitter emitter = new SseEmitter(0L);
            sendError(emitter, "LLM not configured — set valem.llm.api-key");
            return emitter;
        }
        if (req.domainDescription() != null && req.domainDescription().length() > MAX_DESCRIPTION_LENGTH) {
            SseEmitter emitter = new SseEmitter(0L);
            sendError(emitter, "domainDescription exceeds maximum length of " + MAX_DESCRIPTION_LENGTH);
            return emitter;
        }

        log.info("Generate stream: modelId={} descriptionLen={} includeView={}",
                req.modelId(), req.domainDescription() == null ? 0 : req.domainDescription().length(),
                req.includeView());

        SseEmitter emitter = new SseEmitter(SSE_TIMEOUT_MS);
        Thread.ofVirtual().name("llm-generate-stream").start(() -> {
            try {
                SpecGenerator.GenerationResult result = specGenerator.get().generate(
                        req.modelId(), req.domainDescription(), req.includeView(),
                        event -> sendProgress(emitter, event));

                Map<String, Object> done;
                if (result instanceof SpecGenerator.GenerationResult.Success s) {
                    done = new LinkedHashMap<>();
                    done.put("valid", true);
                    done.put("spec", s.spec());
                } else if (result instanceof SpecGenerator.GenerationResult.Failure f) {
                    done = new LinkedHashMap<>();
                    done.put("valid", false);
                    done.put("errors", f.lastErrors().stream()
                            .map(e -> Map.of("location", e.location(), "message", e.message()))
                            .toList());
                    done.put("rawResponse", f.lastRawResponse());
                } else {
                    done = Map.of("valid", false, "errors", List.of());
                }
                sendDone(emitter, done);
                emitter.complete();
            } catch (LlmClient.LlmException e) {
                log.error("Generate stream: LLM failed for modelId={}: {}", req.modelId(), e.getMessage());
                sendError(emitter, e.getMessage());
            } catch (Exception e) {
                log.error("Generate stream: unexpected error for modelId={}", req.modelId(), e);
                sendError(emitter, "Unexpected error: " + e.getMessage());
            }
        });
        return emitter;
    }

    // ── AI Evolve ─────────────────────────────────────────────────────────────

    /**
     * Runs the full {@link SpecGenerator#generateEvolution} loop and streams progress events.
     *
     * <p>On success sends {@code event: done} with {@code {"version":"...","spec":{...}}};
     * on failure with {@code {"error":"..."}}.
     */
    @PostMapping(path = "/models/{id}/spec/evolve/ai/stream",
                 produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter evolveAiStream(@PathVariable("id") String id,
                                     @RequestBody EvolveAiStreamRequest req) {
        if (specGenerator.isEmpty()) {
            log.warn("AI evolve stream requested for model={} but LLM is not configured", id);
            SseEmitter emitter = new SseEmitter(0L);
            sendError(emitter, "LLM not configured — set valem.llm.api-key");
            return emitter;
        }
        if (req.description() == null || req.description().isBlank()) {
            SseEmitter emitter = new SseEmitter(0L);
            sendError(emitter, "description is required");
            return emitter;
        }
        if (req.description().length() > MAX_DESCRIPTION_LENGTH) {
            SseEmitter emitter = new SseEmitter(0L);
            sendError(emitter, "description exceeds maximum length of " + MAX_DESCRIPTION_LENGTH);
            return emitter;
        }

        log.info("AI evolve stream: modelId={} descriptionLen={}", id, req.description().length());

        SseEmitter emitter = new SseEmitter(SSE_TIMEOUT_MS);
        Thread.ofVirtual().name("llm-evolve-stream").start(() -> {
            try {
                ModelSpec currentSpec = modelService.getSpec(id);
                boolean includeView = req.includeView() != null
                        ? req.includeView() : currentSpec.viewDefinition() != null;
                var evolution = specGenerator.get().generateEvolution(
                        currentSpec, req.description(), includeView,
                        event -> sendProgress(emitter, event));

                ModelSpec evolved = modelService.evolveSpec(id, evolution);
                log.info("AI evolve stream succeeded: modelId={} newVersion={}", id, evolved.version());
                sendDone(emitter, Map.of("version", evolved.version(), "spec", evolved));
                emitter.complete();
            } catch (LlmClient.LlmException e) {
                log.warn("AI evolve stream: LLM failed for modelId={}: {}", id, e.getMessage());
                sendError(emitter, e.getMessage());
            } catch (IllegalArgumentException e) {
                log.warn("AI evolve stream: evolved spec invalid for modelId={}: {}", id, e.getMessage());
                sendError(emitter, e.getMessage());
            } catch (Exception e) {
                log.error("AI evolve stream: unexpected error for modelId={}", id, e);
                sendError(emitter, "Unexpected error: " + e.getMessage());
            }
        });
        return emitter;
    }

    // ── SSE helpers ───────────────────────────────────────────────────────────

    private void sendProgress(SseEmitter emitter, LlmProgressEvent event) {
        try {
            emitter.send(SseEmitter.event()
                    .name("progress")
                    .data(toJson(progressToMap(event)), MediaType.APPLICATION_JSON));
        } catch (IOException ignored) {
            // Client disconnected — the virtual thread will finish naturally or catch on the next send
        }
    }

    private void sendDone(SseEmitter emitter, Object data) {
        try {
            emitter.send(SseEmitter.event()
                    .name("done")
                    .data(toJson(data), MediaType.APPLICATION_JSON));
        } catch (IOException ignored) {}
    }

    private void sendError(SseEmitter emitter, String message) {
        try {
            emitter.send(SseEmitter.event()
                    .name("error")
                    .data(toJson(Map.of("message", message)), MediaType.APPLICATION_JSON));
        } catch (IOException ignored) {}
        emitter.completeWithError(new RuntimeException(message));
    }

    private String toJson(Object obj) {
        try {
            return mapper.writeValueAsString(obj);
        } catch (JsonProcessingException e) {
            return "{}";
        }
    }

    private static Map<String, Object> progressToMap(LlmProgressEvent event) {
        return switch (event) {
            case LlmProgressEvent.LlmRequesting e ->
                    Map.of("type", "llm_requesting", "attempt", e.attempt());
            case LlmProgressEvent.ToolCalling e ->
                    Map.of("type", "tool_calling", "tool", e.tool(), "detail", e.detail());
            case LlmProgressEvent.ToolCompleted e ->
                    Map.of("type", "tool_completed", "tool", e.tool(), "resultSummary", e.resultSummary());
            case LlmProgressEvent.Validating e ->
                    Map.of("type", "validating", "attempt", e.attempt());
            case LlmProgressEvent.ValidationFailed e -> {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("type", "validation_failed");
                m.put("attempt", e.attempt());
                m.put("errors", e.errors());
                yield m;
            }
            case LlmProgressEvent.TestRunning e ->
                    Map.of("type", "test_running", "attempt", e.attempt());
            case LlmProgressEvent.TestFailed e ->
                    Map.of("type", "test_failed", "attempt", e.attempt(), "failCount", e.failCount());
            case LlmProgressEvent.Retrying e ->
                    Map.of("type", "retrying", "attempt", e.attempt(), "maxAttempts", e.maxAttempts());
        };
    }
}
