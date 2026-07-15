package org.json_kula.valem.api.blob;

import org.json_kula.valem.core.model.BlobRef;
import org.json_kula.valem.core.model.ModelSpec;
import org.json_kula.valem.persistence.memory.InMemoryBlobStore;
import org.json_kula.valem.service.ModelRegistry;
import org.json_kula.valem.service.ModelService;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

class BlobGarbageCollectorTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void sweep_deletes_only_unreferenced_blobs() throws Exception {
        InMemoryBlobStore store = new InMemoryBlobStore();
        ModelService service = new ModelService(new ModelRegistry(), store);

        // Two blobs; only one is referenced by a model.
        BlobRef referenced = store.store(bytes("keep-me"), "text/plain");
        BlobRef orphan     = store.store(bytes("delete-me"), "text/plain");

        ModelSpec spec = MAPPER.readValue("{\"id\":\"m\",\"schema\":{}}", ModelSpec.class);
        service.createModel(spec);
        service.mutate("m", java.util.Map.of("$.attachment", referenced.toJsonNode()));

        BlobGarbageCollector gc = new BlobGarbageCollector(service, store);

        // Dry run: reports the orphan without deleting.
        BlobGarbageCollector.GcReport dryRun = gc.sweep(false);
        assertThat(dryRun.supported()).isTrue();
        assertThat(dryRun.scanned()).isEqualTo(2);
        assertThat(dryRun.referenced()).isEqualTo(1);
        assertThat(dryRun.orphanedIds()).containsExactly(orphan.blobId());
        assertThat(dryRun.deleted()).isZero();
        assertThat(store.exists(orphan.blobId())).isTrue(); // not yet deleted

        // Apply: the orphan is deleted, the referenced blob survives.
        BlobGarbageCollector.GcReport applied = gc.sweep(true);
        assertThat(applied.deleted()).isEqualTo(1);
        assertThat(store.exists(orphan.blobId())).isFalse();
        assertThat(store.exists(referenced.blobId())).isTrue();
    }

    private static ByteArrayInputStream bytes(String s) {
        return new ByteArrayInputStream(s.getBytes(StandardCharsets.UTF_8));
    }
}
