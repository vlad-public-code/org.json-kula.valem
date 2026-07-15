package org.json_kula.valem.service;

import org.json_kula.valem.core.graph.ModelSpecValidator;

public class ModelValidationException extends RuntimeException {

    private final ModelSpecValidator.ValidationResult validationResult;

    public ModelValidationException(ModelSpecValidator.ValidationResult validationResult) {
        super("Model spec validation failed");
        this.validationResult = validationResult;
    }

    public ModelSpecValidator.ValidationResult validationResult() {
        return validationResult;
    }
}
