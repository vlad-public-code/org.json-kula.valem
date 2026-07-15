package org.json_kula.valem.core.blob;

public class NoSuchBlobException extends RuntimeException {
    private final String blobId;

    public NoSuchBlobException(String blobId) {
        super("Blob not found: " + blobId);
        this.blobId = blobId;
    }

    public String blobId() { return blobId; }
}
