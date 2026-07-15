package org.json_kula.valem.api.reference;

import java.util.Locale;

/**
 * A repository's <b>class</b> — its trust/reachability property, orthogonal to its <em>transport</em>
 * (references design §4.1). This is the dimension that drives reference-locality (§6) and mobility (§7),
 * <em>not</em> how the repository is reached:
 *
 * <ul>
 *   <li>{@link #LOCAL} — a private, process-scoped space (also the resolution cache). The in-process
 *       transport is always local; a dev-only filesystem dir or a single-agent MCP server is local too.</li>
 *   <li>{@link #WEB} — a shared, addressable-by-others store. {@code http} is web; an {@code mcp} or
 *       {@code filesystem} repository fronting a shared store is web.</li>
 * </ul>
 *
 * A transport is therefore not a class: {@code mcp} (and {@code filesystem}) can serve <b>either</b>
 * class depending on how it is used, so class is configured per repository (inferred per transport when
 * unset).
 */
public enum RepositoryClass {
    LOCAL,
    WEB;

    /** Parses a configured class, or returns {@code fallback} when blank/unrecognised. */
    public static RepositoryClass parse(String value, RepositoryClass fallback) {
        if (value == null || value.isBlank()) return fallback;
        return switch (value.toLowerCase(Locale.ROOT)) {
            case "local" -> LOCAL;
            case "web" -> WEB;
            default -> fallback;
        };
    }

    /** The default class for a transport when none is configured (references §13 open-decision-1). */
    public static RepositoryClass defaultFor(String transport) {
        if (transport == null) return WEB;
        return switch (transport.toLowerCase(Locale.ROOT)) {
            case "local", "filesystem" -> LOCAL;
            case "http" -> WEB;
            // mcp is ambiguous (single-agent vs shared) — default web, but it SHOULD be set explicitly.
            default -> WEB;
        };
    }
}
