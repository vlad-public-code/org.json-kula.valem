package org.json_kula.valem.persistence.s3;

import com.adobe.testing.s3mock.junit5.S3MockExtension;
import org.json_kula.valem.core.model.BlobRef;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import software.amazon.awssdk.services.s3.S3Client;

import java.io.ByteArrayInputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class S3BlobStoreIT {

    private static final String BUCKET = "test-blobs";

    @RegisterExtension
    static final S3MockExtension S3_MOCK = S3MockExtension.builder()
            .silent()
            .withInitialBuckets(BUCKET)
            .build();

    private S3BlobStore blobStore;

    @BeforeEach
    void setUp() {
        blobStore = new S3BlobStore(S3_MOCK.createS3ClientV2(), BUCKET);
    }

    @Test
    void store_and_load_round_trip() throws Exception {
        byte[] data = "hello s3 blob".getBytes();
        BlobRef ref = blobStore.store(new ByteArrayInputStream(data), "text/plain");

        assertThat(ref.blobId()).startsWith("sha256:");
        assertThat(ref.bytes()).isEqualTo(data.length);
        assertThat(blobStore.exists(ref.blobId())).isTrue();

        byte[] loaded = blobStore.load(ref.blobId()).readAllBytes();
        assertThat(loaded).isEqualTo(data);
    }

    @Test
    void store_same_bytes_twice_returns_same_id() throws Exception {
        byte[] data = "duplicate blob".getBytes();
        BlobRef ref1 = blobStore.store(new ByteArrayInputStream(data), "text/plain");
        BlobRef ref2 = blobStore.store(new ByteArrayInputStream(data), "application/octet-stream");
        assertThat(ref1.blobId()).isEqualTo(ref2.blobId());
    }

    @Test
    void delete_removes_blob() throws Exception {
        byte[] data = "to be deleted".getBytes();
        BlobRef ref = blobStore.store(new ByteArrayInputStream(data), "text/plain");
        assertThat(blobStore.exists(ref.blobId())).isTrue();

        blobStore.delete(ref.blobId());
        assertThat(blobStore.exists(ref.blobId())).isFalse();
    }

    @Test
    void exists_returns_false_for_unknown() {
        assertThat(blobStore.exists("sha256:000000aabbcc")).isFalse();
    }

    @Test
    void load_unknown_blob_throws_NoSuchBlobException() {
        assertThatThrownBy(() -> blobStore.load("sha256:000000aabbcc"))
                .isInstanceOf(org.json_kula.valem.core.blob.NoSuchBlobException.class);
    }
}
