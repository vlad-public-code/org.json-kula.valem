package org.json_kula.valem.api.dto;

import org.json_kula.valem.core.graph.ModelSpecValidator;

import java.util.List;

public record ValidationErrorResponse(String message, List<ModelSpecValidator.ValidationError> errors) {
    public static ValidationErrorResponse of(ModelSpecValidator.ValidationResult result) {
        return new ValidationErrorResponse("Model spec validation failed", result.errors());
    }
}
