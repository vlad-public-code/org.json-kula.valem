package org.json_kula.valem.core.blob;

import org.json_kula.valem.core.model.BlobRef;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.concurrent.ConcurrentHashMap;

/**
 * @deprecated Moved to {@code valem-persistence-memory} module as
 *             {@code org.json_kula.valem.persistence.memory.InMemoryBlobStore}.
 *             This copy is retained in {@code valem-core} for backward compatibility
 *             only and will be removed in a future release.
 */
@Deprecated
public class InMemoryBlobStore implements BlobStore {

    private final ConcurrentHashMap<String, byte[]> store = new ConcurrentHashMap<>();

    @Override
    public BlobRef store(InputStream data, String mediaType) throws IOException {
        byte[] bytes = data.readAllBytes();
        String blobId = sha256Id(bytes);
        store.put(blobId, bytes);
        return new BlobRef(blobId, mediaType, bytes.length);
    }

    @Override
    public InputStream load(String blobId) {
        byte[] bytes = store.get(blobId);
        if (bytes == null) throw new NoSuchBlobException(blobId);
        return new ByteArrayInputStream(bytes);
    }

    @Override
    public boolean exists(String blobId) {
        return store.containsKey(blobId);
    }

    @Override
    public void delete(String blobId) {
        store.remove(blobId);
    }

    private static String sha256Id(byte[] bytes) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return "sha256:" + HexFormat.of().formatHex(md.digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }
}
