package org.json_kula.valem.core.util;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;

/**
 * Deterministic, content-addressed JSON canonicalisation and SHA-256 hashing.
 *
 * <p>This is the single canonicaliser shared across the platform so that every content hash stays
 * mutually consistent:
 * <ul>
 *   <li><b>Audit chaining</b> — tamper-evident record hashes ({@code AuditHashing} delegates here).</li>
 *   <li><b>Spec digests</b> — a {@code sha256:} model coordinate and every {@code lineage} pin
 *       (references design §3.3).</li>
 *   <li><b>Effect definition hashes</b> — inherited-effect approval keys an approval to the effect's
 *       canonical bytes (multi-tenant-authorization §4.2).</li>
 * </ul>
 *
 * <p>Canonical form (RFC 8785 / JCS in spirit): object keys sorted (both POJO/record properties and
 * JSON map entries), insignificant whitespace removed, UTF-8. Numbers are serialised as Jackson
 * renders them; callers hashing across machines should feed already-normalised numeric values.
 *
 * <p>Pure — no I/O, no Spring, no other module dependency. Lives in {@code valem-core} so both
 * {@code valem-api} (reference digests, effect hashes) and {@code valem-persistence-api}
 * (audit) can reuse it.
 */
public final class CanonicalJson {

    private CanonicalJson() {}

    /** prevHash / genesis sentinel: 64 hex zeros (a SHA-256-width all-zero value). */
    public static final String GENESIS = "0".repeat(64);

    // Sorted properties + sorted map keys handle POJO/record fields; the recursive tree rewrite below
    // additionally sorts embedded JsonNode object keys, which those features do not touch.
    private static final ObjectMapper CANONICAL = JsonMapper.builder()
            .configure(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY, true)
            .configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true)
            .build();

    /**
     * Canonical string form of any value (POJO, record, or {@link JsonNode}): the value is projected
     * to a JSON tree, object keys are sorted recursively, and the tree is serialised compactly. Two
     * structurally-equal inputs that differ only in key order or whitespace canonicalise identically.
     */
    public static String canonicalize(Object value) {
        try {
            JsonNode tree = (value instanceof JsonNode n) ? n : CANONICAL.valueToTree(value);
            return CANONICAL.writeValueAsString(sort(tree));
        } catch (Exception e) {
            throw new IllegalStateException("Failed to canonicalise value for hashing", e);
        }
    }

    /** Returns a copy of {@code node} with every object's fields ordered lexicographically by key. */
    private static JsonNode sort(JsonNode node) {
        if (node instanceof ObjectNode obj) {
            List<String> names = new ArrayList<>();
            obj.fieldNames().forEachRemaining(names::add);
            names.sort(null);
            ObjectNode out = CANONICAL.createObjectNode();
            for (String n : names) {
                out.set(n, sort(obj.get(n)));
            }
            return out;
        }
        if (node instanceof ArrayNode arr) {
            ArrayNode out = CANONICAL.createArrayNode();
            for (JsonNode child : arr) {
                out.add(sort(child));
            }
            return out;
        }
        return node;
    }

    /** SHA-256 hex digest of {@code input}'s UTF-8 bytes. */
    public static String sha256(String input) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(input.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e); // never on a compliant JRE
        }
    }

    /**
     * SHA-256 hex digest over the canonical form of {@code value}. Two structurally-equal values that
     * differ only in key order or whitespace hash identically.
     */
    public static String digest(Object value) {
        return sha256(canonicalize(value));
    }

    /** {@code "sha256:" + digest(value)} — the digest form used by a pinned coordinate / lineage. */
    public static String prefixedDigest(Object value) {
        return "sha256:" + digest(value);
    }
}
