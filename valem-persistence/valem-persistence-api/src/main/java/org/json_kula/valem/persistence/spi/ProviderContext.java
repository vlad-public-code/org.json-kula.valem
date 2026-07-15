package org.json_kula.valem.persistence.spi;

import com.fasterxml.jackson.databind.ObjectMapper;

import javax.sql.DataSource;
import java.util.function.UnaryOperator;

/**
 * Configuration + shared dependencies handed to a {@link PersistenceProvider} when it builds a
 * store. Keeps providers free of any Spring dependency: the resolver ({@code StorageConfig})
 * supplies a plain property lookup, the shared {@link ObjectMapper}, and an optional externally
 * managed {@link DataSource}.
 *
 * <p>Property keys are the same {@code valem.storage.*} keys used in application config
 * (e.g. {@code valem.storage.s3.bucket}). Providers read only the keys they need.
 */
public final class ProviderContext {

    private final UnaryOperator<String> lookup;
    private final ObjectMapper mapper;
    private final DataSource dataSource;

    /**
     * @param lookup     resolves a property key to its value, or {@code null} if unset
     * @param mapper     shared Jackson mapper (spec/state/audit stores serialise through it)
     * @param dataSource externally managed JDBC {@link DataSource}, or {@code null} to let a
     *                   JDBC-backed provider synthesise and own its own connection pool
     */
    public ProviderContext(UnaryOperator<String> lookup, ObjectMapper mapper, DataSource dataSource) {
        this.lookup = lookup;
        this.mapper = mapper;
        this.dataSource = dataSource;
    }

    /** Returns the raw property value for {@code key}, or {@code null} if unset. */
    public String get(String key) {
        return lookup.apply(key);
    }

    /** Returns the property value for {@code key}, or {@code defaultValue} if unset/blank. */
    public String get(String key, String defaultValue) {
        String v = lookup.apply(key);
        return (v == null || v.isBlank()) ? defaultValue : v;
    }

    /**
     * Returns the property value for {@code key}, throwing {@link IllegalStateException} if unset
     * or blank. Use for keys without a sensible default (e.g. an S3 bucket name).
     */
    public String require(String key) {
        String v = lookup.apply(key);
        if (v == null || v.isBlank()) {
            throw new IllegalStateException("Missing required storage property: " + key);
        }
        return v;
    }

    /** Returns {@code key} parsed as an int, or {@code defaultValue} if unset/blank/unparsable. */
    public int intProp(String key, int defaultValue) {
        String v = lookup.apply(key);
        if (v == null || v.isBlank()) return defaultValue;
        try {
            return Integer.parseInt(v.trim());
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    /** Returns {@code key} parsed as a long, or {@code defaultValue} if unset/blank/unparsable. */
    public long longProp(String key, long defaultValue) {
        String v = lookup.apply(key);
        if (v == null || v.isBlank()) return defaultValue;
        try {
            return Long.parseLong(v.trim());
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    /** The shared Jackson {@link ObjectMapper}. */
    public ObjectMapper mapper() {
        return mapper;
    }

    /**
     * The externally managed {@link DataSource}, or {@code null} when none was supplied. A
     * JDBC-backed provider should prefer this when present (the host owns its lifecycle) and
     * otherwise synthesise its own pool, closing it in {@link PersistenceProvider#close()}.
     */
    public DataSource dataSource() {
        return dataSource;
    }
}
