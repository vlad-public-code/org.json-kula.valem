package org.json_kula.valem.service;

import java.time.Instant;

public class HistoryNotFoundException extends RuntimeException {
    public HistoryNotFoundException(String modelId, Instant at) {
        super("No history entry found at or before " + at + " for model '" + modelId + "'");
    }
}
