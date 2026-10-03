package org.json_kula.valem.api.controller;

import org.json_kula.valem.api.spreadsheet.SpreadsheetCompileService;
import org.json_kula.valem.core.engine.SpecVerifier;
import org.json_kula.valem.core.spreadsheet.SpreadsheetCompiler;
import org.json_kula.valem.core.spreadsheet.SpreadsheetExtractionException;
import org.json_kula.valem.core.spreadsheet.UnsupportedFormulaException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.Map;

/**
 * Excel → spec (excel-to-spec-v1-design.md §7): one deterministic call, no scan/compile split —
 * there is nothing to search for, the formula is already machine-readable. No LLM is involved
 * anywhere in this feature, unlike the sibling document-to-spec (PDF/DOC) controller.
 */
@RestController
public class SpreadsheetGenerateController {

    private static final Logger log = LoggerFactory.getLogger(SpreadsheetGenerateController.class);

    private final SpreadsheetCompileService service;

    public SpreadsheetGenerateController(SpreadsheetCompileService service) {
        this.service = service;
    }

    @PostMapping(path = "/models/generate/spreadsheet/compile", consumes = "multipart/form-data")
    ResponseEntity<Map<String, Object>> compile(
            @RequestParam("file") MultipartFile file,
            @RequestParam("modelId") String modelId) {

        if (modelId == null || modelId.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "modelId must not be blank"));
        }

        SpreadsheetCompiler.CompileResult result;
        try {
            result = service.compile(file, modelId);
        } catch (SpreadsheetExtractionException e) {
            log.warn("Spreadsheet compile: extraction failed ({}): {}", e.reason(), e.getMessage());
            return ResponseEntity.badRequest().body(Map.of(
                    "error", e.getMessage(), "reason", e.reason().name()));
        } catch (UnsupportedFormulaException e) {
            // Thrown only when EVERY column was rejected -- nothing survived to compile.
            log.warn("Spreadsheet compile: nothing translatable ({}): {}", e.reason(), e.getMessage());
            return ResponseEntity.unprocessableEntity().body(Map.of(
                    "error", e.getMessage(), "reason", e.reason().name()));
        }

        log.info("Spreadsheet compile succeeded: modelId={} rejectedColumns={}",
                modelId, result.rejectedColumns().size());

        List<Map<String, String>> rejected = result.rejectedColumns().stream()
                .map(r -> Map.of("header", r.header(), "reason", r.reason().name(), "detail", r.detail()))
                .toList();

        return ResponseEntity.ok(Map.of(
                "valid", true,
                "spec", result.spec(),
                "verification", SpecVerifier.verify(result.spec()),
                "rejectedColumns", rejected));
    }
}
