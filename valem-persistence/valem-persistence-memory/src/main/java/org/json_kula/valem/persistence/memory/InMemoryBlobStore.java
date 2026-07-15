package org.json_kula.valem.persistence.memory;

import org.json_kula.valem.core.blob.BlobStoreCapacityException;
import org.json_kula.valem.core.blob.EnumerableBlobStore;
import org.json_kula.valem.core.blob.NoSuchBlobException;
import org.json_kula.valem.core.model.BlobRef;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * {@link BlobStore} backed by a {@link ConcurrentHashMap}.
 * Content-addressed: blobId is {@code "sha256:<hex>"}. Not durable — data lives in heap only.
 *
 * <p>An optional total-bytes ceiling bounds heap usage: once the stored bytes would exceed the
 * budget, a new (not-already-present) blob is <b>rejected</b> with {@link BlobStoreCapacityException}
 * rather than evicting existing content (content-addressed references must not vanish).
 */
public final class InMemoryBlobStore implements EnumerableBlobStore {

    private final ConcurrentHashMap<String, byte[]> store = new ConcurrentHashMap<>();
    private final long      maxTotalBytes;
    private final AtomicLong totalBytes = new AtomicLong(0);

    /** Unbounded store (no total-bytes ceiling). */
    public InMemoryBlobStore() {
        this(Long.MAX_VALUE);
    }

    /** @param maxTotalBytes total-bytes ceiling; use {@link Long#MAX_VALUE} for unbounded. */
    public InMemoryBlobStore(long maxTotalBytes) {
        this.maxTotalBytes = maxTotalBytes <= 0 ? Long.MAX_VALUE : maxTotalBytes;
    }

    @Override
    public BlobRef store(InputStream data, String mediaType) throws IOException {
        byte[] bytes = data.readAllBytes();
        String blobId = sha256Id(bytes);
        // Content-addressed: an identical blob already present costs no additional budget.
        if (!store.containsKey(blobId)) {
            long projected = totalBytes.addAndGet(bytes.length);
            if (projected > maxTotalBytes) {
                totalBytes.addAndGet(-bytes.length);
                throw new BlobStoreCapacityException(
                        "In-memory blob store capacity exceeded ("
                        + maxTotalBytes + " bytes); rejecting " + bytes.length + "-byte blob");
            }
            // putIfAbsent guards a concurrent identical upload so we don't double-count.
            if (store.putIfAbsent(blobId, bytes) != null) {
                totalBytes.addAndGet(-bytes.length);
            }
        }
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
        byte[] removed = store.remove(blobId);
        if (removed != null) {
            totalBytes.addAndGet(-removed.length);
        }
    }

    @Override
    public Set<String> listBlobIds() {
        return Set.copyOf(store.keySet());
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
