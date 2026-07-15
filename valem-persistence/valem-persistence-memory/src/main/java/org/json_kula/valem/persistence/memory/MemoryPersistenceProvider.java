package org.json_kula.valem.persistence.memory;

import org.json_kula.valem.core.blob.BlobStore;
import org.json_kula.valem.persistence.SpecStore;
import org.json_kula.valem.persistence.StateStore;
import org.json_kula.valem.persistence.audit.AuditStore;
import org.json_kula.valem.persistence.spi.Concern;
import org.json_kula.valem.persistence.spi.PersistenceProvider;
import org.json_kula.valem.persistence.spi.ProviderContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.EnumSet;
import java.util.Set;

/**
 * In-memory {@link PersistenceProvider} — the default, non-durable backend. Serves all four
 * concerns from heap-resident stores.
 *
 * <p>Spec and state share a single {@link InMemoryModelStore} instance so the resolver can back
 * both from one combined {@code ModelStore}.
 */
public final class MemoryPersistenceProvider implements PersistenceProvider {

    private static final Logger log = LoggerFactory.getLogger(MemoryPersistenceProvider.class);

    private InMemoryModelStore modelStore;

    @Override
    public String type() {
        return "memory";
    }

    @Override
    public Set<Concern> concerns() {
        return EnumSet.of(Concern.SPEC, Concern.STATE, Concern.BLOB, Concern.AUDIT);
    }

    @Override
    public SpecStore specStore(ProviderContext ctx) {
        return modelStore();
    }

    @Override
    public StateStore stateStore(ProviderContext ctx) {
        return modelStore();
    }

    @Override
    public BlobStore blobStore(ProviderContext ctx) {
        return new InMemoryBlobStore(maxTotalBlobBytes(ctx));
    }

    @Override
    public AuditStore auditStore(ProviderContext ctx) {
        return new InMemoryAuditStore();
    }

    private synchronized InMemoryModelStore modelStore() {
        if (modelStore == null) {
            modelStore = new InMemoryModelStore();
        }
        return modelStore;
    }

    private static long maxTotalBlobBytes(ProviderContext ctx) {
        long max = ctx.longProp("valem.blob.max-total-bytes", 536_870_912L);
        if (max <= 0) {
            log.warn("valem.blob.max-total-bytes is unbounded — the in-memory blob store can "
                    + "grow until the heap is exhausted; set a finite cap for production");
            return Long.MAX_VALUE;
        }
        return max;
    }
}
