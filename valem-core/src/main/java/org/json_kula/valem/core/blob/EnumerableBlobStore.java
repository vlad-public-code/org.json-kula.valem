package org.json_kula.valem.core.blob;

import java.io.IOException;
import java.util.Set;

/**
 * Opt-in capability for a {@link BlobStore} that can enumerate every stored blob id. Required for
 * blob garbage collection (mark-and-sweep of unreferenced blobs, audit MEM-6). A backend that does
 * not implement this — e.g. streaming/object stores where a full key listing is expensive — is
 * simply skipped by the janitor rather than forcing a breaking change on the base SPI.
 */
public interface EnumerableBlobStore extends BlobStore {

    /** Returns the ids of all blobs currently stored. */
    Set<String> listBlobIds() throws IOException;
}
