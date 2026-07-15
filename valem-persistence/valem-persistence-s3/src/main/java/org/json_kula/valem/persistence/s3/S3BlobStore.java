package org.json_kula.valem.persistence.s3;

import org.json_kula.valem.core.blob.BlobStore;
import org.json_kula.valem.core.blob.NoSuchBlobException;
import org.json_kula.valem.core.model.BlobRef;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

import java.io.IOException;
import java.io.InputStream;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * Content-addressed {@link BlobStore} backed by S3-compatible object storage
 * (AWS S3, MinIO, Cloudflare R2, etc.).
 *
 * <p>Object keys are the SHA-256 hex digest of the content (without the {@code sha256:} prefix).
 * The {@link BlobRef#blobId()} is {@code "sha256:<hex>"} as with all other adapters.
 *
 * <p>Content-addressing: if an object with the computed key already exists (HEAD 200),
 * the upload is skipped and the existing {@code BlobRef} is returned.
 */
public final class S3BlobStore implements BlobStore {

    private static final Logger log = LoggerFactory.getLogger(S3BlobStore.class);

    private final S3Client s3;
    private final String   bucket;

    public S3BlobStore(S3Client s3, String bucket) {
        this.s3     = s3;
        this.bucket = bucket;
    }

    @Override
    public BlobRef store(InputStream data, String mediaType) throws IOException {
        MessageDigest digest = newSha256();
        byte[] bytes;
        long size;
        try (DigestInputStream dis = new DigestInputStream(data, digest)) {
            bytes = dis.readAllBytes();
            size  = bytes.length;
        }
        String hex    = HexFormat.of().formatHex(digest.digest());
        String blobId = "sha256:" + hex;

        if (!existsByHex(hex)) {
            s3.putObject(PutObjectRequest.builder()
                            .bucket(bucket)
                            .key(hex)
                            .contentType(mediaType)
                            .contentLength(size)
                            .build(),
                    RequestBody.fromBytes(bytes));
            log.debug("Uploaded blob {} ({} bytes) to S3 bucket '{}'", blobId, size, bucket);
        }
        return new BlobRef(blobId, mediaType, size);
    }

    @Override
    public InputStream load(String blobId) throws IOException {
        String hex = hexOf(blobId);
        if (!existsByHex(hex)) throw new NoSuchBlobException(blobId);
        return s3.getObject(GetObjectRequest.builder()
                .bucket(bucket)
                .key(hex)
                .build());
    }

    @Override
    public boolean exists(String blobId) {
        return existsByHex(hexOf(blobId));
    }

    @Override
    public void delete(String blobId) {
        s3.deleteObject(DeleteObjectRequest.builder()
                .bucket(bucket)
                .key(hexOf(blobId))
                .build());
        log.debug("Deleted blob {} from S3 bucket '{}'", blobId, bucket);
    }

    private boolean existsByHex(String hex) {
        try {
            s3.headObject(HeadObjectRequest.builder().bucket(bucket).key(hex).build());
            return true;
        } catch (NoSuchKeyException e) {
            return false;
        }
    }

    private static String hexOf(String blobId) {
        return blobId.startsWith("sha256:") ? blobId.substring(7) : blobId;
    }

    private static MessageDigest newSha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }
}
