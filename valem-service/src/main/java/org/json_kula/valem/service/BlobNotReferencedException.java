package org.json_kula.valem.service;

public class BlobNotReferencedException extends RuntimeException {
    public BlobNotReferencedException(String modelId, String blobId) {
        super("Blob '" + blobId + "' is not referenced by model '" + modelId + "'");
    }
}
