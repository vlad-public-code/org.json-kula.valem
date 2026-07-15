package org.json_kula.valem.persistence.spi;

import org.json_kula.valem.core.blob.BlobStore;
import org.json_kula.valem.persistence.SpecStore;
import org.json_kula.valem.persistence.StateStore;
import org.json_kula.valem.persistence.audit.AuditStore;

import java.io.Closeable;
import java.util.Set;

/**
 * SPI for a persistence backend. One implementation per adapter jar
 * ({@code valem-persistence-postgres}, {@code -mongo}, {@code -s3}, …), registered via
 * {@code META-INF/services/org.json_kula.valem.persistence.spi.PersistenceProvider} and
 * discovered by {@link java.util.ServiceLoader}.
 *
 * <p>The resolver ({@code StorageConfig} in {@code valem-api}) indexes providers by
 * {@link #type()} and asks each for the concerns the operator selected. Because discovery is
 * classpath-driven, {@code valem-api} no longer compile-depends on every adapter — a
 * backend is present iff its jar is on the classpath, and a selection with no matching provider
 * fails with a clear "add the jar" message rather than a wiring error.
 *
 * <p>A provider that owns shared, expensive clients (a JDBC pool, a Mongo client, a Redis
 * connection, an S3 client) should build them lazily, cache them across the store getters, and
 * release them in {@link #close()}. Store getters may be called more than once per concern and
 * should return a store bound to the same shared client.
 */
public interface PersistenceProvider extends Closeable {

    /**
     * The configuration token this provider answers to, e.g. {@code "postgres"}, {@code "mongodb"},
     * {@code "s3"}. Matched against the normalised {@code valem.storage.*-type} value.
     * Aliases (e.g. {@code postgresql}, {@code mongo}) are normalised by the resolver before lookup.
     */
    String type();

    /** The concerns this provider can serve. */
    Set<Concern> concerns();

    /**
     * Builds (or returns a cached) {@link SpecStore}.
     *
     * @throws UnsupportedConcernException if this provider does not serve {@link Concern#SPEC}
     */
    default SpecStore specStore(ProviderContext ctx) {
        throw new UnsupportedConcernException(type(), Concern.SPEC);
    }

    /**
     * Builds (or returns a cached) {@link StateStore}.
     *
     * @throws UnsupportedConcernException if this provider does not serve {@link Concern#STATE}
     */
    default StateStore stateStore(ProviderContext ctx) {
        throw new UnsupportedConcernException(type(), Concern.STATE);
    }

    /**
     * Builds (or returns a cached) {@link BlobStore}.
     *
     * @throws UnsupportedConcernException if this provider does not serve {@link Concern#BLOB}
     */
    default BlobStore blobStore(ProviderContext ctx) {
        throw new UnsupportedConcernException(type(), Concern.BLOB);
    }

    /**
     * Builds (or returns a cached) {@link AuditStore}.
     *
     * @throws UnsupportedConcernException if this provider does not serve {@link Concern#AUDIT}
     */
    default AuditStore auditStore(ProviderContext ctx) {
        throw new UnsupportedConcernException(type(), Concern.AUDIT);
    }

    /**
     * Releases any shared clients this provider owns (connection pools, driver clients). No-op by
     * default for providers that hold no resources (in-memory, filesystem). Called by the resolver
     * on shutdown.
     */
    @Override
    default void close() {
        // no resources to release by default
    }
}
