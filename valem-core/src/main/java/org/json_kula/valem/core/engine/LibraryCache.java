package org.json_kula.valem.core.engine;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.NullNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.json_kula.jsonata_jvm.JsonataBindings;
import org.json_kula.jsonata_jvm.JsonataBoundFunction;
import org.json_kula.jsonata_jvm.JsonataCompilationException;
import org.json_kula.jsonata_jvm.JsonataExpressionFactory;
import org.json_kula.jsonata_jvm.JsonataLibrary;
import org.json_kula.jsonata_jvm.JsonataLibraryOptions;
import org.json_kula.valem.core.model.LibraryLayer;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Process-wide, content-addressed cache of compiled {@link JsonataLibrary} objects.
 *
 * <p>Compiling a library is one javac invocation and one generated class, regardless of how many
 * functions it exports. That class is pinned in Metaspace — native memory, outside the container's
 * heap cap — for as long as it is reachable, which is the same accumulation
 * {@link ExpressionCache}'s shared cache exists to bound. So libraries get the same treatment: one
 * compilation per distinct definition per JVM, and a bounded LRU.
 *
 * <h2>The key</h2>
 * A SHA-256 over the definition, the model's constants, and the signature overrides — <b>all three</b>.
 * Constants are in the key because a definition may export a <em>computed value</em>
 * ({@code $maxRate := $max($const.brackets.rate)}), which is evaluated once, at compile time,
 * against the constants in force then. Keying on the definition text alone would hand a second model
 * the first model's frozen value. (Exported <em>functions</em> resolve {@code $const} late, against
 * the calling evaluation, so for those the constants would not matter — but a cheap hash removes the
 * whole class of cross-model contamination rather than half of it.)
 *
 * <h2>Lifetime</h2>
 * {@link JsonataLibrary#close()} is deliberately never called: a library may be shared by several
 * models through this cache, so no single model owns its lifetime. Dropping the last reference is
 * what releases it — the same discipline {@link ExpressionCache#clear()} follows for expressions.
 * Closing on model disposal would break every other model sharing the entry, with an error naming a
 * function that looks perfectly valid in the spec.
 */
public final class LibraryCache {

    /** Last-resort bound when nothing configures one; mirrors the default in {@code application.yml}. */
    static final int FALLBACK_MAX_SIZE = 64;

    static final int DEFAULT_MAX_SIZE = resolveMaxSize();

    /**
     * How many compiled libraries to retain, from (in precedence order) the
     * {@code valem.limits.library-cache-size} system property, the
     * {@code VALEM_LIMITS_LIBRARY_CACHE_SIZE} environment variable, or {@link #FALLBACK_MAX_SIZE}.
     * Mirrors {@link ExpressionCache#resolveMaxSize()} — this module has no Spring, so it reads the
     * property {@code CoreLimitsEnvironmentPostProcessor} publishes rather than {@code application.yml}.
     */
    static int resolveMaxSize() {
        Integer fromProperty = Integer.getInteger("valem.limits.library-cache-size");
        if (fromProperty != null) return Math.max(4, fromProperty);

        String fromEnv = System.getenv("VALEM_LIMITS_LIBRARY_CACHE_SIZE");
        if (fromEnv != null && !fromEnv.isBlank()) {
            try {
                return Math.max(4, Integer.parseInt(fromEnv.trim()));
            } catch (NumberFormatException ignored) {
                // A malformed override must not stop the engine starting — fall through to the default.
            }
        }
        return FALLBACK_MAX_SIZE;
    }

    /** Unchecked wrapper thrown when a library definition fails to compile. */
    public static final class LibraryCompilationException extends RuntimeException {
        private final transient LibraryLayer layer;

        LibraryCompilationException(LibraryLayer layer, JsonataCompilationException cause) {
            super(cause.getMessage(), cause);
            this.layer = layer;
        }

        public LibraryLayer layer() { return layer; }
    }

    private LibraryCache() {}

    private static final JsonataExpressionFactory FACTORY = new JsonataExpressionFactory();

    private static final Map<String, JsonataLibrary> SHARED = Collections.synchronizedMap(
            new LinkedHashMap<>(32, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, JsonataLibrary> eldest) {
                    return size() > DEFAULT_MAX_SIZE;
                }
            });

    /** Compilations actually performed, for tests that assert a cache hit performed no javac work. */
    private static volatile int compileCount;

    /**
     * The compiled result of one model's library: the layers in bind order, plus the merged export
     * maps a later layer's entry overrides an earlier one in.
     */
    public record Compiled(List<JsonataLibrary> layers,
                           Map<String, JsonataBoundFunction> functions,
                           Map<String, JsonNode> constants) {

        public static final Compiled EMPTY = new Compiled(List.of(), Map.of(), Map.of());

        /** Every exported name, functions and constants alike, without the leading {@code $}. */
        public java.util.Set<String> names() {
            java.util.Set<String> all = new java.util.LinkedHashSet<>(functions.keySet());
            all.addAll(constants.keySet());
            return all;
        }

        public boolean isEmpty() { return layers.isEmpty(); }
    }

    /**
     * Compiles {@code layers} in order and returns the merged result, reusing any layer another model
     * has already compiled with the same definition, constants and signatures.
     *
     * <p>Layer <i>k</i> is compiled with {@code $const} plus the exports of layers 0..<i>k</i>-1
     * bound, so a branch's function may call one it inherited (and so upstream's self-containment
     * check accepts the inherited name). At evaluation time the caller binds the merged maps, where a
     * later layer has already won any name collision.
     *
     * @param constants the model's constants object, bound as {@code $const} while each layer is defined
     * @throws LibraryCompilationException if any layer fails to compile
     */
    public static Compiled compile(List<LibraryLayer> layers, ObjectNode constants) {
        if (layers == null || layers.isEmpty()) return Compiled.EMPTY;

        List<JsonataLibrary> compiled = new java.util.ArrayList<>(layers.size());
        Map<String, JsonataBoundFunction> functions = new LinkedHashMap<>();
        Map<String, JsonNode> values = new LinkedHashMap<>();

        for (LibraryLayer layer : layers) {
            JsonataLibrary lib = compileLayer(layer, constants, functions, values);
            compiled.add(lib);
            // Later layers win: a branch may override an export it inherited. A constant and a
            // function of the same name cannot coexist, so a later kind displaces the earlier one.
            lib.getFunctions().forEach((name, fn) -> { values.remove(name); functions.put(name, fn); });
            lib.getConstants().forEach((name, v)  -> { functions.remove(name); values.put(name, v); });
        }
        return new Compiled(List.copyOf(compiled),
                Collections.unmodifiableMap(functions),
                Collections.unmodifiableMap(values));
    }

    private static JsonataLibrary compileLayer(LibraryLayer layer, ObjectNode constants,
                                               Map<String, JsonataBoundFunction> inheritedFns,
                                               Map<String, JsonNode> inheritedValues) {
        String key = cacheKey(layer, constants, inheritedFns.keySet(), inheritedValues);
        JsonataLibrary cached = SHARED.get(key);
        if (cached != null) return cached;

        JsonataLibraryOptions options = new JsonataLibraryOptions().input(NullNode.instance);
        JsonataBindings bindings = new JsonataBindings()
                .bindValue("const", constants != null ? constants : NullNode.instance)
                .bindFunctions(inheritedFns);
        inheritedValues.forEach(bindings::bindValue);
        options.bindings(bindings);
        layer.signatures().forEach(options::signature);

        JsonataLibrary built;
        try {
            built = FACTORY.compileLibrary(layer.define(), options);       // outside any lock
            compileCount++;
        } catch (JsonataCompilationException e) {
            throw new LibraryCompilationException(layer, e);
        }
        JsonataLibrary existing = SHARED.putIfAbsent(key, built);
        return existing != null ? existing : built;                        // a raced duplicate is discarded
    }

    /**
     * SHA-256 over everything that can change what a layer exports: its definition, the constants
     * bound while it is defined, its signature overrides, and the names it inherits from earlier
     * layers (an inherited name changes which binding a body resolves against).
     */
    private static String cacheKey(LibraryLayer layer, ObjectNode constants,
                                   java.util.Set<String> inheritedFnNames,
                                   Map<String, JsonNode> inheritedValues) {
        StringBuilder sb = new StringBuilder(layer.define());
        sb.append(' ').append(constants == null ? "" : canonical(constants));
        sb.append(' ');
        new java.util.TreeMap<>(layer.signatures())
                .forEach((n, s) -> sb.append(n).append('=').append(s).append(','));
        sb.append(' ');
        new java.util.TreeSet<>(inheritedFnNames).forEach(n -> sb.append(n).append(','));
        sb.append(' ');
        new java.util.TreeMap<>(inheritedValues)
                .forEach((n, v) -> sb.append(n).append('=').append(v).append(','));
        return sha256(sb.toString());
    }

    /** Key-sorted rendering so two equal constants objects hash identically whatever their order. */
    private static String canonical(JsonNode node) {
        if (node == null || node.isNull()) return "null";
        if (!node.isObject()) return node.toString();
        StringBuilder sb = new StringBuilder("{");
        new java.util.TreeMap<>(fieldsOf(node))
                .forEach((k, v) -> sb.append(k).append(':').append(canonical(v)).append(','));
        return sb.append('}').toString();
    }

    private static Map<String, JsonNode> fieldsOf(JsonNode node) {
        Map<String, JsonNode> out = new LinkedHashMap<>();
        node.fields().forEachRemaining(e -> out.put(e.getKey(), e.getValue()));
        return out;
    }

    private static String sha256(String text) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256")
                    .digest(text.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(hash.length * 2);
            for (byte b : hash) sb.append(Character.forDigit((b >> 4) & 0xF, 16))
                                  .append(Character.forDigit(b & 0xF, 16));
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);   // required of every JVM
        }
    }

    // ── Observability / test hooks ────────────────────────────────────────────────

    /** Compilations performed since JVM start; a cache hit does not increment it. */
    public static int compileCount() { return compileCount; }

    /** Current number of cached libraries. */
    public static int size() { return SHARED.size(); }

    /** Drops every cached library. For tests that need a cold cache; not used in production. */
    static void clear() { SHARED.clear(); }
}
