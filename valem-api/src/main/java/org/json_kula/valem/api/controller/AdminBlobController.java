package org.json_kula.valem.api.controller;

import org.json_kula.valem.api.blob.BlobGarbageCollector;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;

/**
 * Administrative blob maintenance endpoints (behind the API key like every other route).
 *
 * <p>{@code POST /admin/blobs/gc} runs a mark-and-sweep of unreferenced blobs (audit MEM-6).
 * Defaults to a dry run; pass {@code ?apply=true} to actually delete the reported orphans.
 *
 * <p>Destructive {@code apply=true} additionally requires a configured {@code valem.api.key}:
 * in open/development mode (no key) the whole API is unauthenticated, so an anonymous, irreversible
 * bulk delete of content-addressed storage is refused — dry-run inspection stays available.
 */
@RestController
@RequestMapping("/admin/blobs")
public class AdminBlobController {

    private final BlobGarbageCollector gc;
    private final boolean apiKeyConfigured;

    public AdminBlobController(BlobGarbageCollector gc,
                               @Value("${valem.api.key:}") String apiKey) {
        this.gc = gc;
        this.apiKeyConfigured = apiKey != null && !apiKey.isBlank();
    }

    @PostMapping("/gc")
    public ResponseEntity<BlobGarbageCollector.GcReport> gc(
            @RequestParam(value = "apply", defaultValue = "false") boolean apply) throws IOException {
        if (apply && !apiKeyConfigured) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "blob gc apply=true requires a configured valem.api.key; the API is in open "
                    + "mode. Run without apply for a dry run, or configure an API key to delete.");
        }
        return ResponseEntity.ok(gc.sweep(apply));
    }
}
