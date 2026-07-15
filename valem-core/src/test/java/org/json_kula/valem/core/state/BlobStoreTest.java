package org.json_kula.valem.core.state;

import org.json_kula.valem.core.blob.FilesystemBlobStore;
import org.json_kula.valem.core.blob.InMemoryBlobStore;
import org.json_kula.valem.core.blob.NoSuchBlobException;
import org.json_kula.valem.core.model.BlobRef;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class BlobStoreTest {

    // ── InMemoryBlobStore ──────────────────────────────────────────────────────

    @Test
    void inMemory_store_and_load() throws IOException {
        var store = new InMemoryBlobStore();
        byte[] data = "hello blob".getBytes();
        BlobRef ref = store.store(new ByteArrayInputStream(data), "text/plain");

        assertThat(ref.blobId()).startsWith("sha256:");
        assertThat(ref.mediaType()).isEqualTo("text/plain");
        assertThat(ref.bytes()).isEqualTo(data.length);
        assertThat(store.exists(ref.blobId())).isTrue();

        byte[] loaded = store.load(ref.blobId()).readAllBytes();
        assertThat(loaded).isEqualTo(data);
    }

    @Test
    void inMemory_same_content_returns_same_id() throws IOException {
        var store = new InMemoryBlobStore();
        byte[] data = "identical content".getBytes();
        BlobRef a = store.store(new ByteArrayInputStream(data), "text/plain");
        BlobRef b = store.store(new ByteArrayInputStream(data), "text/plain");
        assertThat(a.blobId()).isEqualTo(b.blobId());
    }

    @Test
    void inMemory_different_content_returns_different_id() throws IOException {
        var store = new InMemoryBlobStore();
        BlobRef a = store.store(new ByteArrayInputStream("aaa".getBytes()), "text/plain");
        BlobRef b = store.store(new ByteArrayInputStream("bbb".getBytes()), "text/plain");
        assertThat(a.blobId()).isNotEqualTo(b.blobId());
    }

    @Test
    void inMemory_delete_removes_blob() throws IOException {
        var store = new InMemoryBlobStore();
        BlobRef ref = store.store(new ByteArrayInputStream("x".getBytes()), "text/plain");
        store.delete(ref.blobId());
        assertThat(store.exists(ref.blobId())).isFalse();
        assertThatThrownBy(() -> store.load(ref.blobId()))
                .isInstanceOf(NoSuchBlobException.class);
    }

    @Test
    void inMemory_load_unknown_id_throws() {
        var store = new InMemoryBlobStore();
        assertThatThrownBy(() -> store.load("sha256:nonexistent"))
                .isInstanceOf(NoSuchBlobException.class)
                .hasMessageContaining("sha256:nonexistent");
    }

    // ── FilesystemBlobStore ────────────────────────────────────────────────────

    @Test
    void filesystem_store_and_load(@TempDir Path dir) throws IOException {
        var store = new FilesystemBlobStore(dir);
        byte[] data = "filesystem blob".getBytes();
        BlobRef ref = store.store(new ByteArrayInputStream(data), "application/octet-stream");

        assertThat(ref.blobId()).startsWith("sha256:");
        assertThat(store.exists(ref.blobId())).isTrue();
        assertThat(store.load(ref.blobId()).readAllBytes()).isEqualTo(data);
    }

    @Test
    void filesystem_same_content_idempotent(@TempDir Path dir) throws IOException {
        var store = new FilesystemBlobStore(dir);
        byte[] data = "same".getBytes();
        BlobRef a = store.store(new ByteArrayInputStream(data), "text/plain");
        BlobRef b = store.store(new ByteArrayInputStream(data), "text/plain");
        assertThat(a.blobId()).isEqualTo(b.blobId());
    }

    @Test
    void filesystem_delete_removes_file(@TempDir Path dir) throws IOException {
        var store = new FilesystemBlobStore(dir);
        BlobRef ref = store.store(new ByteArrayInputStream("y".getBytes()), "text/plain");
        store.delete(ref.blobId());
        assertThat(store.exists(ref.blobId())).isFalse();
    }
}
