package org.json_kula.valem.persistence.filesystem;

import org.json_kula.valem.core.blob.EnumerableBlobStore;
import org.json_kula.valem.core.blob.NoSuchBlobException;
import org.json_kula.valem.core.model.BlobRef;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.stream.Stream;

/**
 * {@link BlobStore} backed by the local filesystem.
 * Each blob is a flat file named {@code <sha256-hex>} under the configured directory.
 * Content-addressed: blobId is {@code "sha256:<hex>"}. Writes are atomic (temp + rename).
 */
public final class FilesystemBlobStore implements EnumerableBlobStore {

    private final Path rootDir;

    public FilesystemBlobStore(Path rootDir) {
        this.rootDir = rootDir;
    }

    @Override
    public BlobRef store(InputStream data, String mediaType) throws IOException {
        Files.createDirectories(rootDir);
        MessageDigest digest = newSha256();
        Path tmp = Files.createTempFile(rootDir, "blob-", ".tmp");
        long size;
        try (OutputStream out = Files.newOutputStream(tmp);
             DigestInputStream dis = new DigestInputStream(data, digest)) {
            size = dis.transferTo(out);
        }
        String hex    = HexFormat.of().formatHex(digest.digest());
        String blobId = "sha256:" + hex;
        Path   dest   = blobPath(hex);
        if (!Files.exists(dest)) {
            Files.move(tmp, dest);
        } else {
            Files.deleteIfExists(tmp);
        }
        return new BlobRef(blobId, mediaType, size);
    }

    @Override
    public InputStream load(String blobId) throws IOException {
        Path p = blobPath(hexOf(blobId));
        if (!Files.exists(p)) throw new NoSuchBlobException(blobId);
        return Files.newInputStream(p);
    }

    @Override
    public boolean exists(String blobId) {
        return Files.exists(blobPath(hexOf(blobId)));
    }

    @Override
    public void delete(String blobId) {
        try { Files.deleteIfExists(blobPath(hexOf(blobId))); }
        catch (IOException e) { throw new RuntimeException("Failed to delete blob " + blobId, e); }
    }

    @Override
    public Set<String> listBlobIds() throws IOException {
        if (!Files.exists(rootDir)) return Set.of();
        try (Stream<Path> files = Files.list(rootDir)) {
            Set<String> ids = new LinkedHashSet<>();
            files.filter(Files::isRegularFile)
                    .map(p -> p.getFileName().toString())
                    // Blob files are the 64-char sha256 hex; ignore any stray *.tmp or other files.
                    .filter(name -> name.length() == 64 && name.chars()
                            .allMatch(c -> (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f')))
                    .forEach(hex -> ids.add("sha256:" + hex));
            return ids;
        }
    }

    private Path blobPath(String hex) {
        Path resolved = rootDir.resolve(hex).normalize();
        if (!resolved.startsWith(rootDir.normalize()))
            throw new IllegalArgumentException("Invalid blob hex: " + hex);
        return resolved;
    }

    private static String hexOf(String blobId) {
        if (blobId != null && blobId.startsWith("sha256:")) {
            String hex = blobId.substring(7);
            if (hex.length() != 64 || !hex.matches("[0-9a-f]+"))
                throw new IllegalArgumentException("Invalid blobId: hex must be exactly 64 lowercase hex characters");
            return hex;
        }
        throw new IllegalArgumentException("Unsupported blobId format: " + blobId);
    }

    private static MessageDigest newSha256() {
        try { return MessageDigest.getInstance("SHA-256"); }
        catch (NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }
}
