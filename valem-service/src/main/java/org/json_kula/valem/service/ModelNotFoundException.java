package org.json_kula.valem.service;

public class ModelNotFoundException extends RuntimeException {
    public ModelNotFoundException(String id) {
        super("Model not found: " + id);
    }
}
