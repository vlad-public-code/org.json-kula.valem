package org.json_kula.valem.core.llm;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * The set of {@link DomainGuidanceTopic}s the {@code get_domain_guidance} tool offers — the
 * language-agnostic replacement for the old keyword-regex shape dispatch.
 *
 * <p><b>Extensible.</b> Builtin topics load from a JSON classpath resource
 * ({@value #BUILTIN_RESOURCE}); an operator can layer additional topics — or override a builtin one by
 * reusing its id — by pointing config at another JSON document ({@link #withOverridesJson}). Merge is
 * by id: an override with an existing id replaces it in place; a new id is appended. Both documents are
 * a JSON array of {@code {id, description, instructions}} (or {@code instructionLines}).
 */
public final class DomainGuidanceCatalog {

    private static final Logger log = LoggerFactory.getLogger(DomainGuidanceCatalog.class);

    /** The classpath resource holding the builtin topics. */
    public static final String BUILTIN_RESOURCE = "/valem/domain-guidance-topics.json";

    private static final TypeReference<List<DomainGuidanceTopic>> LIST = new TypeReference<>() {};

    // Insertion-ordered, keyed by id, so declaration order and overrides both behave predictably.
    private final Map<String, DomainGuidanceTopic> byId;

    private DomainGuidanceCatalog(Map<String, DomainGuidanceTopic> byId) {
        this.byId = byId;
    }

    /** The builtin catalog loaded from {@value #BUILTIN_RESOURCE}. Empty if the resource is missing. */
    public static DomainGuidanceCatalog builtin(ObjectMapper mapper) {
        Map<String, DomainGuidanceTopic> map = new LinkedHashMap<>();
        merge(map, loadResource(BUILTIN_RESOURCE, mapper), "builtin");
        log.info("Loaded {} builtin domain-guidance topic(s).", map.size());
        return new DomainGuidanceCatalog(map);
    }

    /** Convenience {@link #builtin(ObjectMapper)} with a default mapper. */
    public static DomainGuidanceCatalog builtin() {
        return builtin(new ObjectMapper());
    }

    /**
     * A copy of this catalog with the topics from {@code json} (a JSON array) merged in — same id
     * overrides in place, new id appended. A blank/malformed document is ignored (guidance is an
     * enhancement, never a hard dependency). Use this to apply an operator's config-supplied topics.
     */
    public DomainGuidanceCatalog withOverridesJson(String json, ObjectMapper mapper) {
        if (json == null || json.isBlank()) return this;
        List<DomainGuidanceTopic> extra;
        try {
            extra = mapper.reader().without(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                    .forType(LIST).readValue(json);
        } catch (Exception e) {
            log.warn("Ignoring domain-guidance overrides — could not parse: {}", e.getMessage());
            return this;
        }
        Map<String, DomainGuidanceTopic> map = new LinkedHashMap<>(byId);
        int before = map.size();
        merge(map, extra, "override");
        log.info("Applied domain-guidance overrides: {} topic(s) now (was {}).", map.size(), before);
        return new DomainGuidanceCatalog(map);
    }

    // ── Resolution used by the tool ───────────────────────────────────────────────

    /** All topic ids, in catalog order. */
    public List<String> ids() {
        return new ArrayList<>(byId.keySet());
    }

    /** The topic for {@code id} (case-insensitive), if any. */
    public Optional<DomainGuidanceTopic> byId(String id) {
        if (id == null) return Optional.empty();
        return Optional.ofNullable(byId.get(id.trim().toLowerCase(Locale.ROOT)));
    }

    /** A {@code "- id — description"} menu of every topic, for the tool's description text. */
    public String menu() {
        StringBuilder sb = new StringBuilder();
        for (DomainGuidanceTopic t : byId.values()) {
            sb.append("  - ").append(t.id()).append(" — ").append(t.description()).append('\n');
        }
        return sb.toString();
    }

    /**
     * The concatenated instructions for the requested ids, in catalog order, de-duplicated. Unknown
     * ids are ignored; {@code ""} when nothing resolves.
     */
    public String instructionsFor(Collection<String> requestedIds) {
        if (requestedIds == null || requestedIds.isEmpty()) return "";
        Set<String> wanted = new LinkedHashSet<>();
        for (String id : requestedIds) if (id != null) wanted.add(id.trim().toLowerCase(Locale.ROOT));
        StringBuilder sb = new StringBuilder();
        for (DomainGuidanceTopic t : byId.values()) {   // catalog order, each at most once
            if (wanted.contains(t.id())) sb.append(t.instructions());
        }
        return sb.toString();
    }

    /** Topic count (diagnostics / tests). */
    public int size() { return byId.size(); }

    // ── internals ─────────────────────────────────────────────────────────────────

    private static void merge(Map<String, DomainGuidanceTopic> into,
                              List<DomainGuidanceTopic> topics, String source) {
        if (topics == null) return;
        for (DomainGuidanceTopic t : topics) {
            if (t == null || !t.isValid()) {
                log.warn("Skipping invalid {} domain-guidance topic (missing id/instructions): {}",
                        source, t == null ? "null" : t.id());
                continue;
            }
            // Canonicalise the id to lower-case in the STORED topic too (not just the map key), so
            // ids()/menu()/instructionsFor() (which read topic.id()) all agree with byId()'s lookup —
            // otherwise a mixed-case operator-supplied id resolves via byId() but never via
            // instructionsFor(), which compares a lower-cased request against the original-case id.
            String key = t.id().toLowerCase(Locale.ROOT);
            into.put(key, new DomainGuidanceTopic(key, t.description(), t.instructions()));   // overrides in place
        }
    }

    private static List<DomainGuidanceTopic> loadResource(String resource, ObjectMapper mapper) {
        try (InputStream in = DomainGuidanceCatalog.class.getResourceAsStream(resource)) {
            if (in == null) {
                log.warn("Domain-guidance resource '{}' not found — no builtin topics.", resource);
                return List.of();
            }
            return mapper.reader().without(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                    .forType(LIST).readValue(in);
        } catch (Exception e) {
            log.warn("Could not load domain-guidance resource '{}': {}", resource, e.getMessage());
            return List.of();
        }
    }
}
