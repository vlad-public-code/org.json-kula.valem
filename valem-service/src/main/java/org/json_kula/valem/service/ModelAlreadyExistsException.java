package org.json_kula.valem.service;

public class ModelAlreadyExistsException extends RuntimeException {
    public ModelAlreadyExistsException(String id) {
        super("Model with id '" + id + "' already exists");
    }
}
