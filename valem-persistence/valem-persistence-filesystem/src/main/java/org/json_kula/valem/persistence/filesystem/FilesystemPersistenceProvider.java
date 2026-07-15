package org.json_kula.valem.persistence.filesystem;

import org.json_kula.valem.core.blob.BlobStore;
import org.json_kula.valem.persistence.SpecStore;
import org.json_kula.valem.persistence.StateStore;
import org.json_kula.valem.persistence.audit.AuditStore;
import org.json_kula.valem.persistence.spi.Concern;
import org.json_kula.valem.persistence.spi.PersistenceProvider;
import org.json_kula.valem.persistence.spi.ProviderContext;

import java.nio.file.Path;
import java.util.EnumSet;
import java.util.Set;

/**
 * Filesystem-backed {@link PersistenceProvider}. Spec + state + audit live under
 * {@code valem.persistence-dir}; blobs live under {@code valem.blob-store-path}. Serves
 * all four concerns.
 *
 * <p>Spec and state share a single {@link FilesystemModelStore} instance so the resolver can back
 * both from one combined {@code ModelStore}.
 */
public final class FilesystemPersistenceProvider implements PersistenceProvider {

    private FilesystemModelStore modelStore;

    @Override
    public String type() {
        return "filesystem";
    }

    @Override
    public Set<Concern> concerns() {
        return EnumSet.of(Concern.SPEC, Concern.STATE, Concern.BLOB, Concern.AUDIT);
    }

    @Override
    public SpecStore specStore(ProviderContext ctx) {
        return modelStore(ctx);
    }

    @Override
    public StateStore stateStore(ProviderContext ctx) {
        return modelStore(ctx);
    }

    @Override
    public BlobStore blobStore(ProviderContext ctx) {
        return new FilesystemBlobStore(Path.of(ctx.require("valem.blob-store-path")));
    }

    @Override
    public AuditStore auditStore(ProviderContext ctx) {
        return new FilesystemAuditStore(persistenceDir(ctx), ctx.mapper());
    }

    private synchronized FilesystemModelStore modelStore(ProviderContext ctx) {
        if (modelStore == null) {
            modelStore = new FilesystemModelStore(persistenceDir(ctx), ctx.mapper(),
                    ctx.intProp("valem.storage.compaction-threshold", 100));
        }
        return modelStore;
    }

    private static Path persistenceDir(ProviderContext ctx) {
        return Path.of(ctx.require("valem.persistence-dir"));
    }
}
