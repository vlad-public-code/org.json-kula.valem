package org.json_kula.valem.persistence.spi;

/**
 * Thrown when a {@link PersistenceProvider} is asked for a {@link Concern} it does not serve
 * (e.g. requesting a blob store from the Redis provider, or a spec store from S3).
 *
 * <p>The resolver ({@code StorageConfig}) treats this as a configuration error: the operator
 * selected a backend for a concern that backend cannot fulfil.
 */
public class UnsupportedConcernException extends RuntimeException {

    private final String providerType;
    private final Concern concern;

    public UnsupportedConcernException(String providerType, Concern concern) {
        super("Persistence backend '" + providerType + "' does not support the "
                + concern.name().toLowerCase() + " concern");
        this.providerType = providerType;
        this.concern = concern;
    }

    /** The {@link PersistenceProvider#type()} that rejected the request. */
    public String providerType() {
        return providerType;
    }

    /** The concern that was requested but not served. */
    public Concern concern() {
        return concern;
    }
}
