package org.json_kula.valem.core.blob;

import org.json_kula.valem.core.model.BlobRef;

import java.io.IOException;
import java.io.InputStream;

/**
 * Pluggable external storage for binary field values.
 *
 * <p>Binary fields in the model state are stored as {@link BlobRef} objects
 * (a lightweight JSON record holding the id, media type, and byte count).
 * The actual bytes live here, keyed by the content-addressed {@code blobId}.
 */
public interface BlobStore {

    /**
     * Stores the bytes from {@code data} and returns a {@link BlobRef} that can
     * be persisted in the model state. Implementations must be content-addressed:
     * uploading the same bytes twice must return the same {@code blobId}.
     *
     * @param data      the binary content (fully read; caller closes the stream)
     * @param mediaType MIME type, e.g. {@code "image/png"}
     * @return a {@link BlobRef} pointing to the stored blob
     */
    BlobRef store(InputStream data, String mediaType) throws IOException;

    /**
     * Opens a stream to read blob bytes.
     *
     * @throws NoSuchBlobException if {@code blobId} is unknown
     */
    InputStream load(String blobId) throws IOException;

    /** Returns {@code true} if a blob with this id exists. */
    boolean exists(String blobId);

    /**
     * Deletes a blob. No-op if the id is unknown.
     * Called during blob lifecycle management (e.g. when a binary field is overwritten).
     */
    void delete(String blobId);
}
