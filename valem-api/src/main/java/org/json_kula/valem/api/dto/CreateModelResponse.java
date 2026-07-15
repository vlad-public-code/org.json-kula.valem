package org.json_kula.valem.api.dto;

public record CreateModelResponse(String id, String status) {
    public static CreateModelResponse created(String id) {
        return new CreateModelResponse(id, "created");
    }
}
