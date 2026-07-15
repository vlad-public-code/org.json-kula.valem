package org.json_kula.valem.api.controller;

import org.json_kula.valem.service.ModelService;
import org.json_kula.valem.view.engine.EvaluatedView;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * REST endpoints for rendering model view definitions.
 */
@RestController
@RequestMapping("/models")
public class ViewController {

    private final ModelService service;

    public ViewController(ModelService service) {
        this.service = service;
    }

    @GetMapping("/{id}/view")
    public ResponseEntity<EvaluatedView> getDefaultView(@PathVariable("id") String id) {
        return ResponseEntity.ok(service.getEvaluatedView(id, null));
    }

    @GetMapping("/{id}/view/{viewId}")
    public ResponseEntity<EvaluatedView> getNamedView(
            @PathVariable("id") String id,
            @PathVariable("viewId") String viewId) {
        return ResponseEntity.ok(service.getEvaluatedView(id, viewId));
    }
}
