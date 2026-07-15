package org.json_kula.valem.persistence;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;

import static org.assertj.core.api.Assertions.assertThat;

class BlobSpoolerTest {

    @Test
    void computes_sha256_blob_id_and_byte_count() throws Exception {
        byte[] content = "hello valem".getBytes(StandardCharsets.UTF_8);
        String expectedHex = HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256").digest(content));

        try (BlobSpooler spooled = BlobSpooler.spool(new ByteArrayInputStream(content))) {
            assertThat(spooled.blobId()).isEqualTo("sha256:" + expectedHex);
            assertThat(spooled.byteCount()).isEqualTo(content.length);
        }
    }

    @Test
    void spooled_stream_reproduces_the_original_bytes() throws Exception {
        // Larger than the 8 KiB spool buffer to exercise the loop and multi-read playback.
        byte[] content = new byte[100_000];
        for (int i = 0; i < content.length; i++) content[i] = (byte) (i % 251);

        try (BlobSpooler spooled = BlobSpooler.spool(new ByteArrayInputStream(content))) {
            assertThat(spooled.byteCount()).isEqualTo(content.length);
            try (InputStream in = spooled.openStream()) {
                assertThat(in.readAllBytes()).isEqualTo(content);
            }
            // A second open yields the same bytes (temp file persists until close()).
            try (InputStream in = spooled.openStream()) {
                assertThat(in.readAllBytes()).isEqualTo(content);
            }
        }
    }

    @Test
    void identical_content_yields_identical_blob_id() throws Exception {
        byte[] content = "content-addressed".getBytes(StandardCharsets.UTF_8);
        String a;
        String b;
        try (BlobSpooler s = BlobSpooler.spool(new ByteArrayInputStream(content))) { a = s.blobId(); }
        try (BlobSpooler s = BlobSpooler.spool(new ByteArrayInputStream(content))) { b = s.blobId(); }
        assertThat(a).isEqualTo(b);
    }
}
