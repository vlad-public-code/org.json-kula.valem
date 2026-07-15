package org.json_kula.valem.api.controller;

import org.json_kula.valem.api.composition.CompositionGraph;
import org.json_kula.valem.api.composition.CompositionGraphService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Cross-model composition observability (composition architecture §6.2). The live link/lineage
 * topology, computed on demand — never authoritative state.
 */
@RestController
public class CompositionController {

    private final CompositionGraphService graphService;

    public CompositionController(CompositionGraphService graphService) {
        this.graphService = graphService;
    }

    @GetMapping("/composition/graph")
    public ResponseEntity<CompositionGraph> graph() {
        return ResponseEntity.ok(graphService.build());
    }
}
