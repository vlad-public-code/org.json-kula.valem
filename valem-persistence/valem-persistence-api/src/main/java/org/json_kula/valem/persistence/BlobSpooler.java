package org.json_kula.valem.persistence;

import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * Spools an uploaded blob to a temporary file while computing its SHA-256, so a content-addressed
 * store can learn the blob id and byte count <b>without buffering the whole blob in heap</b>
 * (F-T6). The previous approach — {@code DigestInputStream.readAllBytes()} — held the entire blob
 * as a single {@code byte[]}; this streams through an 8 KiB buffer to disk instead.
 *
 * <p>Use in try-with-resources; {@link #close()} deletes the temp file. Call {@link #openStream()}
 * to read the spooled bytes back (e.g. to hand a bounded stream to a JDBC {@code setBinaryStream}
 * or a GridFS {@code uploadFromStream}). The blob id is {@code "sha256:" + hex(digest)}.
 */
public final class BlobSpooler implements Closeable {

    private final Path   tempFile;
    private final String blobId;
    private final long   byteCount;

    private BlobSpooler(Path tempFile, String blobId, long byteCount) {
        this.tempFile  = tempFile;
        this.blobId    = blobId;
        this.byteCount = byteCount;
    }

    /**
     * Drains {@code data} to a temp file, hashing as it goes. The caller's stream is fully read but
     * not closed (callers typically wrap a request stream they own).
     */
    public static BlobSpooler spool(InputStream data) throws IOException {
        MessageDigest digest = newSha256();
        Path tmp = Files.createTempFile("valem-blob-", ".tmp");
        long count = 0;
        try (DigestInputStream dis = new DigestInputStream(data, digest);
             OutputStream out = Files.newOutputStream(tmp)) {
            byte[] buf = new byte[8192];
            int n;
            while ((n = dis.read(buf)) != -1) {
                out.write(buf, 0, n);
                count += n;
            }
        } catch (IOException | RuntimeException e) {
            Files.deleteIfExists(tmp);
            throw e;
        }
        String blobId = "sha256:" + HexFormat.of().formatHex(digest.digest());
        return new BlobSpooler(tmp, blobId, count);
    }

    /** Opens a fresh stream over the spooled bytes. The caller must close it. */
    public InputStream openStream() throws IOException {
        return Files.newInputStream(tempFile);
    }

    public String blobId()    { return blobId; }
    public long   byteCount() { return byteCount; }

    @Override
    public void close() throws IOException {
        Files.deleteIfExists(tempFile);
    }

    private static MessageDigest newSha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }
}
