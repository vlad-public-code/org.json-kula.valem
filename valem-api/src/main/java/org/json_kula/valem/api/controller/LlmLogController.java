package org.json_kula.valem.api.controller;

import org.json_kula.valem.api.llm.LlmInteractionLog;
import org.json_kula.valem.api.llm.LlmInteractionRecord;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/llm")
public class LlmLogController {

    private final LlmInteractionLog log;

    public LlmLogController(LlmInteractionLog log) {
        this.log = log;
    }

    @GetMapping("/interactions")
    public List<LlmInteractionRecord> getInteractions() {
        return log.getAll();
    }
}
