package org.json_kula.valem.core.blob;

/**
 * Thrown by a {@link BlobStore} when storing a blob would exceed the store's configured capacity.
 *
 * <p>Used by the in-memory store to reject (rather than evict) once a total-bytes budget is reached:
 * content-addressed references must not vanish, so eviction is not an option. Unchecked so it does
 * not widen the {@link BlobStore#store} signature; callers that care (e.g. the REST layer) catch it
 * and map it to a clear client error.
 */
public class BlobStoreCapacityException extends RuntimeException {
    public BlobStoreCapacityException(String message) {
        super(message);
    }
}
