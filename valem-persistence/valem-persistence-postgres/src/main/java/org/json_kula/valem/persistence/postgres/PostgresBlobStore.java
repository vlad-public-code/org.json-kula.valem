package org.json_kula.valem.persistence.postgres;

import org.json_kula.valem.core.blob.BlobStore;
import org.json_kula.valem.core.blob.NoSuchBlobException;
import org.json_kula.valem.core.model.BlobRef;
import org.json_kula.valem.persistence.BlobSpooler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;

/**
 * Content-addressed {@link BlobStore} backed by the PostgreSQL {@code ss_blobs} table.
 * Blob bytes are stored inline as {@code BYTEA}.
 *
 * <p><b>Heap note (F-T6):</b> the write path spools the upload to a temp file while hashing and then
 * hands a bounded {@code setBinaryStream} to the driver, so this class never holds the whole blob as
 * a single {@code byte[]}. Inline {@code BYTEA} is, however, materialised by the JDBC driver/server
 * as one value on read — there is no row-level streaming for {@code BYTEA} (true streaming needs the
 * PostgreSQL Large Object API). Inline storage is therefore an explicit, bounded exception to
 * FR-BLOB-2: keep blobs under the {@code valem.blob.max-bytes} cap (default 50 MB) and use the
 * S3 or filesystem backend for large objects.
 */
public final class PostgresBlobStore implements BlobStore {

    private static final Logger log = LoggerFactory.getLogger(PostgresBlobStore.class);

    private final JdbcTemplate jdbc;

    public PostgresBlobStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public BlobRef store(InputStream data, String mediaType) throws IOException {
        // Spool to a temp file while hashing (bounded heap), then stream the file to the driver via
        // setBinaryStream rather than buffering the whole blob as a byte[] (F-T6).
        try (BlobSpooler spooled = BlobSpooler.spool(data)) {
            String blobId = spooled.blobId();
            long   size   = spooled.byteCount();

            try (InputStream in = spooled.openStream()) {
                jdbc.update(con -> {
                    var ps = con.prepareStatement("""
                            INSERT INTO ss_blobs(blob_id, media_type, byte_count, data)
                            VALUES(?, ?, ?, ?)
                            ON CONFLICT (blob_id) DO NOTHING
                            """);
                    ps.setString(1, blobId);
                    ps.setString(2, mediaType);
                    ps.setLong(3, size);
                    ps.setBinaryStream(4, in, size);
                    return ps;
                });
            }
            log.debug("Stored blob {} ({} bytes)", blobId, size);
            return new BlobRef(blobId, mediaType, size);
        }
    }

    @Override
    public InputStream load(String blobId) throws IOException {
        try {
            byte[] data = jdbc.queryForObject(
                    "SELECT data FROM ss_blobs WHERE blob_id = ?",
                    byte[].class, blobId);
            if (data == null) throw new NoSuchBlobException(blobId);
            return new ByteArrayInputStream(data);
        } catch (EmptyResultDataAccessException e) {
            throw new NoSuchBlobException(blobId);
        }
    }

    @Override
    public boolean exists(String blobId) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM ss_blobs WHERE blob_id = ?",
                Integer.class, blobId);
        return count != null && count > 0;
    }

    @Override
    public void delete(String blobId) {
        jdbc.update("DELETE FROM ss_blobs WHERE blob_id = ?", blobId);
        log.debug("Deleted blob {}", blobId);
    }
}
