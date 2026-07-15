package org.json_kula.valem.service;

/** Thrown when a JSON Patch (RFC 6902) document cannot be applied to the current base document. */
public class InvalidPatchException extends RuntimeException {
    public InvalidPatchException(String message, Throwable cause) {
        super(message, cause);
    }

    public InvalidPatchException(String message) {
        super(message);
    }
}
