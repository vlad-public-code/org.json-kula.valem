package org.json_kula.valem.api.controller;

import org.json_kula.valem.core.blob.BlobStore;
import org.json_kula.valem.core.model.BlobRef;
import org.springframework.core.io.InputStreamResource;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;

import static org.springframework.http.HttpStatus.NOT_FOUND;

/**
 * REST controller for binary blob storage.
 *
 * <ul>
 *   <li>{@code POST /blobs} — uploads a binary file; returns its {@link BlobRef}.</li>
 *   <li>{@code GET /blobs/{blobId}} — streams the stored binary.</li>
 * </ul>
 *
 * <p>Blob IDs have the format {@code sha256:<hex>}. The colon must be URL-encoded
 * ({@code sha256%3A<hex>}) when used as a path variable.
 */
@RestController
@RequestMapping("/blobs")
public class BlobController {

    private final BlobStore blobStore;
    private final long maxBlobBytes;

    public BlobController(
            BlobStore blobStore,
            @org.springframework.beans.factory.annotation.Value(
                    "${valem.blob.max-bytes:52428800}") long maxBlobBytes) {
        this.blobStore    = blobStore;
        this.maxBlobBytes = maxBlobBytes;
    }

    /**
     * Uploads a binary file via multipart form upload.
     *
     * <p>Form field name: {@code file}. Optional {@code mediaType} field overrides content type.
     * Returns 201 Created with Location header pointing to the blob's URL.
     */
    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<BlobRef> upload(
            @RequestParam("file") MultipartFile file,
            @RequestParam(value = "mediaType", required = false) String mediaType) throws IOException {

        if (file.getSize() > maxBlobBytes) {
            throw new ResponseStatusException(
                    org.springframework.http.HttpStatus.PAYLOAD_TOO_LARGE,
                    "Blob exceeds maximum allowed size of " + maxBlobBytes + " bytes");
        }

        String mt = mediaType != null ? mediaType
                : (file.getContentType() != null ? file.getContentType() : "application/octet-stream");

        BlobRef ref;
        try (InputStream in = file.getInputStream()) {
            ref = blobStore.store(in, mt);
        } catch (org.json_kula.valem.core.blob.BlobStoreCapacityException e) {
            // In-memory store budget exhausted — reject with a clear 413 rather than OOM.
            throw new ResponseStatusException(
                    org.springframework.http.HttpStatus.PAYLOAD_TOO_LARGE, e.getMessage());
        }

        // URL-encode the blobId (sha256:<hex> → sha256%3A<hex>)
        String encodedId = ref.blobId().replace(":", "%3A");
        return ResponseEntity
                .created(URI.create("/blobs/" + encodedId))
                .body(ref);
    }

    /**
     * Streams the stored binary identified by {@code blobId}.
     *
     * <p>The {@code blobId} path variable should be URL-decoded by Spring automatically,
     * so clients can send either {@code sha256%3A<hex>} or (if supported) {@code sha256:<hex>}.
     */
    @GetMapping("/{blobId:.+}")
    public ResponseEntity<InputStreamResource> download(@PathVariable("blobId") String blobId) throws IOException {
        if (!blobStore.exists(blobId)) {
            throw new ResponseStatusException(NOT_FOUND, "Blob not found: " + blobId);
        }

        InputStream in = blobStore.load(blobId);
        HttpHeaders headers = new HttpHeaders();
        headers.setContentDisposition(ContentDisposition.inline().build());

        return ResponseEntity.ok()
                .headers(headers)
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .body(new InputStreamResource(in));
    }
}
