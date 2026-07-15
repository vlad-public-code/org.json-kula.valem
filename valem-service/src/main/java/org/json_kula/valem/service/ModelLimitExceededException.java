package org.json_kula.valem.service;

public class ModelLimitExceededException extends RuntimeException {

    private final int limit;

    public ModelLimitExceededException(int limit) {
        super("Model registry is full: maximum " + limit + " models allowed");
        this.limit = limit;
    }

    public int limit() { return limit; }
}
