package org.json_kula.valem.persistence.memory;

import org.json_kula.valem.core.blob.BlobStoreCapacityException;
import org.json_kula.valem.core.model.BlobRef;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class InMemoryBlobStoreTest {

    private static ByteArrayInputStream bytes(int n) {
        byte[] b = new byte[n];
        return new ByteArrayInputStream(b);
    }

    @Test
    void stores_and_loads_round_trip() throws Exception {
        var store = new InMemoryBlobStore();
        byte[] data = "hello".getBytes(StandardCharsets.UTF_8);
        BlobRef ref = store.store(new ByteArrayInputStream(data), "text/plain");
        assertThat(store.exists(ref.blobId())).isTrue();
        assertThat(store.load(ref.blobId()).readAllBytes()).isEqualTo(data);
    }

    @Test
    void rejects_blob_that_would_exceed_total_capacity() throws Exception {
        var store = new InMemoryBlobStore(100); // 100-byte ceiling
        store.store(bytes(60), "application/octet-stream"); // ok → 60 used
        assertThatThrownBy(() -> store.store(bytes(50), "application/octet-stream"))
                .isInstanceOf(BlobStoreCapacityException.class)
                .hasMessageContaining("capacity exceeded");
    }

    @Test
    void identical_blob_does_not_double_count_budget() throws Exception {
        var store = new InMemoryBlobStore(100);
        byte[] data = new byte[60];
        store.store(new ByteArrayInputStream(data), "application/octet-stream");
        // Storing the same content again is content-addressed — no additional budget consumed.
        store.store(new ByteArrayInputStream(data), "application/octet-stream");
        // A distinct 30-byte blob still fits (60 used, 30 more ≤ 100).
        store.store(bytes(30), "application/octet-stream");
        assertThat(store).isNotNull();
    }

    @Test
    void delete_frees_capacity() throws Exception {
        var store = new InMemoryBlobStore(100);
        BlobRef ref = store.store(bytes(90), "application/octet-stream");
        store.delete(ref.blobId());
        // After delete, a new 90-byte blob fits again.
        store.store(bytes(90), "application/octet-stream");
        assertThat(store).isNotNull();
    }

    @Test
    void unbounded_store_accepts_large_blobs() throws Exception {
        var store = new InMemoryBlobStore(); // unbounded
        store.store(bytes(10_000_000), "application/octet-stream");
        assertThat(store).isNotNull();
    }
}
