package org.json_kula.valem.persistence.audit;

import java.time.Instant;

/**
 * Filter for {@link AuditStore#query(AuditQuery)}.
 *
 * <p>All filters are AND-combined; a {@code null} filter is "unbounded" on that axis:
 * <ul>
 *   <li>{@link #modelId} — required; scopes the query to one model's audit trail.</li>
 *   <li>{@link #pathPrefix} — keep only records that touched a field/derivation/constraint whose
 *       path starts with this prefix (see {@link AuditRecord#touchesPath}). {@code null} = any.</li>
 *   <li>{@link #from} / {@link #to} — inclusive lower / exclusive upper time bounds. {@code null} = open.</li>
 *   <li>{@link #limit} — max records returned, most-recent-first; {@code <= 0} applies {@link #DEFAULT_LIMIT}.</li>
 * </ul>
 */
public record AuditQuery(
        String modelId,
        String pathPrefix,
        Instant from,
        Instant to,
        int limit
) {
    public static final int DEFAULT_LIMIT = 100;
    public static final int MAX_LIMIT     = 10_000;

    public AuditQuery {
        if (modelId == null || modelId.isBlank()) {
            throw new IllegalArgumentException("AuditQuery.modelId must not be blank");
        }
    }

    /** A query for one model with no path/time filters and the default limit. */
    public static AuditQuery all(String modelId) {
        return new AuditQuery(modelId, null, null, null, DEFAULT_LIMIT);
    }

    /** The effective limit, clamped to {@code [1, MAX_LIMIT]} with the default applied for {@code <= 0}. */
    public int effectiveLimit() {
        if (limit <= 0)         return DEFAULT_LIMIT;
        if (limit > MAX_LIMIT)  return MAX_LIMIT;
        return limit;
    }

    /** {@code true} if {@code ts} falls within the {@link #from}/{@link #to} window. */
    public boolean inWindow(Instant ts) {
        if (ts == null) return false;
        if (from != null && ts.isBefore(from)) return false;
        return to == null || ts.isBefore(to);
    }
}
