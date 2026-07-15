package org.json_kula.valem.api.llm;

import org.json_kula.valem.core.llm.LlmClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class MockLlmClient implements LlmClient {

    private static final Logger log = LoggerFactory.getLogger(MockLlmClient.class);

    @Override
    public String complete(String prompt) {
        log.info("MockLlmClient: returning canned response (promptLen={})", prompt.length());
        if (prompt.contains("Apply the following changes")) {
            return "{}";
        }
        return buildSpec(extractModelId(prompt));
    }

    private String extractModelId(String prompt) {
        for (String line : prompt.split("\n")) {
            String trimmed = line.trim();
            if (trimmed.startsWith("Model ID:")) {
                return trimmed.substring("Model ID:".length()).trim();
            }
        }
        return "mock-model";
    }

    private String buildSpec(String modelId) {
        return """
                {
                  "id": "%s",
                  "version": "1.0.0",
                  "schema": {
                    "type": "object",
                    "properties": {
                      "value": { "type": "number" }
                    }
                  },
                  "derivations": [
                    { "path": "$.doubled", "expr": "value * 2", "evaluation": "eager" }
                  ],
                  "constraints": [],
                  "actions": [],
                  "metaDerivations": [],
                  "tests": []
                }
                """.formatted(modelId);
    }
}
