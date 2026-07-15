package org.json_kula.valem.api.blob;

import org.json_kula.valem.core.blob.BlobStore;
import org.json_kula.valem.core.blob.EnumerableBlobStore;
import org.json_kula.valem.service.ModelService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.List;
import java.util.Set;

/**
 * Mark-and-sweep garbage collector for unreferenced blobs (audit MEM-6). Blobs become unreachable
 * when an upload is never referenced, when a model is deleted, or when a {@code BlobRef} field is
 * overwritten/removed by a plain mutation — none of which reclaims storage today.
 *
 * <p>The <b>mark</b> set is every blob id referenced by any registered model's current base document
 * ({@link ModelService#referencedBlobIds()}); the <b>sweep</b> deletes stored blobs not in that set.
 * Only backends implementing {@link EnumerableBlobStore} can be swept (others report unsupported).
 *
 * <p>Runs on demand (admin-triggered) and defaults to a dry run so an operator can inspect the
 * orphan set before deleting — this also sidesteps the race with an in-flight upload that has not yet
 * been referenced by a model.
 */
@Component
public class BlobGarbageCollector {

    private static final Logger log = LoggerFactory.getLogger(BlobGarbageCollector.class);

    private final ModelService service;
    private final BlobStore blobStore;

    public BlobGarbageCollector(ModelService service, BlobStore blobStore) {
        this.service   = service;
        this.blobStore = blobStore;
    }

    /** Cap on how many orphan ids the report echoes back, so a store with millions of orphans cannot
     *  produce a multi-hundred-MB response. {@code orphaned} always carries the true total. */
    private static final int MAX_REPORTED_ORPHANS = 1_000;

    /**
     * Result of a sweep. {@code supported=false} means the active blob backend cannot enumerate its
     * contents, so nothing was scanned. When {@code apply} was false, {@code deleted} is 0 and
     * {@code orphanedIds} samples what <em>would</em> be deleted — at most {@link #MAX_REPORTED_ORPHANS}
     * ids; {@code orphaned} is the untruncated count.
     */
    public record GcReport(boolean supported, int scanned, int referenced, int orphaned,
                           int deleted, List<String> orphanedIds) {}

    /** Runs a sweep. When {@code apply} is false, reports orphans without deleting (dry run). */
    public GcReport sweep(boolean apply) throws IOException {
        if (!(blobStore instanceof EnumerableBlobStore enumerable)) {
            log.info("blob gc: backend {} does not support enumeration — skipped",
                    blobStore.getClass().getSimpleName());
            return new GcReport(false, 0, 0, 0, 0, List.of());
        }

        Set<String> all        = enumerable.listBlobIds();
        Set<String> referenced = service.referencedBlobIds();
        List<String> orphaned  = all.stream()
                .filter(id -> !referenced.contains(id))
                .sorted()
                .toList();

        int deleted = 0;
        if (apply) {
            // Re-read the mark set immediately before deleting so a blob that a mutation referenced
            // between the first mark and now is not swept (shrinks the TOCTOU window to the delete
            // loop). A blob uploaded but not yet referenced by any model is still an orphan here — the
            // dry-run default exists precisely so an operator inspects the set first, and apply should
            // run when uploads are quiesced.
            Set<String> stillReferenced = service.referencedBlobIds();
            for (String id : orphaned) {
                if (stillReferenced.contains(id)) continue;
                try {
                    blobStore.delete(id);
                    deleted++;
                } catch (RuntimeException e) {
                    log.warn("blob gc: failed to delete {}: {}", id, e.toString());
                }
            }
        }

        log.info("blob gc: scanned={} referenced={} orphaned={} deleted={} (apply={})",
                all.size(), referenced.size(), orphaned.size(), deleted, apply);
        List<String> sample = orphaned.size() > MAX_REPORTED_ORPHANS
                ? orphaned.subList(0, MAX_REPORTED_ORPHANS)
                : orphaned;
        return new GcReport(true, all.size(), referenced.size(), orphaned.size(), deleted, sample);
    }
}
