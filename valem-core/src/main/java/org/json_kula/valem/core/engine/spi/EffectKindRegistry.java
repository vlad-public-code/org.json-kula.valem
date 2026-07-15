package org.json_kula.valem.core.engine.spi;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.ServiceLoader;
import java.util.Set;

/**
 * Process-wide registry of effect kinds. The four <b>built-in</b> kinds ({@code server}, {@code caller},
 * {@code llm}, {@code timer}) are always known and handled directly by the engine; additional
 * <b>plugin</b> kinds are discovered via {@link ServiceLoader} of {@link EffectKind} from the classpath.
 * A jar dropped on the classpath that ships a {@code META-INF/services} entry therefore adds a kind with
 * no core edits.
 *
 * <p>An optional <b>enable-list</b> gates which kinds are active: when configured non-empty, only the
 * named kinds (built-in or plugin) are usable and a spec selecting any other kind is rejected at
 * validation. When unset/empty, every known kind is enabled. The imperative shell calls
 * {@link #configure} once at startup (before any model loads) with
 * {@code valem.effects.kinds.enabled}; core/console/mcp callers that never configure it get the
 * all-enabled default lazily.
 *
 * <p>This mirrors the existing process-wide, static {@code ModelSpecValidator.validate} design: a single
 * global instance keeps the SPI reachable from the static validation path without threading a registry
 * through every call site.
 */
public final class EffectKindRegistry {

    public static final String SERVER = "server";
    public static final String CALLER = "caller";
    public static final String LLM    = "llm";
    public static final String TIMER  = "timer";

    /** The kinds the engine handles directly (not via the SPI). */
    public static final Set<String> BUILTINS = Set.of(SERVER, CALLER, LLM, TIMER);

    private static volatile EffectKindRegistry instance;

    private final Map<String, EffectKind> plugins;   // enabled, discovered plugin kinds (kind -> impl)
    private final Set<String> enabled;               // every enabled kind name (built-in + plugin)

    private EffectKindRegistry(Map<String, EffectKind> plugins, Set<String> enabled) {
        this.plugins = Map.copyOf(plugins);
        this.enabled = Set.copyOf(enabled);
    }

    /** The current registry, lazily built with the all-enabled default if never {@link #configure}d. */
    public static EffectKindRegistry get() {
        EffectKindRegistry r = instance;
        if (r == null) {
            synchronized (EffectKindRegistry.class) {
                r = instance;
                if (r == null) {
                    r = build(null, null);
                    instance = r;
                }
            }
        }
        return r;
    }

    /**
     * Rebuilds and installs the registry with the given enable-list (null/empty = enable everything
     * discovered). Discovery uses the current thread's context classloader. Idempotent; call once at
     * startup before any validation or model load.
     */
    public static synchronized void configure(Collection<String> enabledKinds) {
        instance = build(enabledKinds, null);
    }

    /** Test/embedding hook: rebuild against a specific classloader (e.g. one carrying a fixture plugin). */
    public static synchronized void configure(Collection<String> enabledKinds, ClassLoader loader) {
        instance = build(enabledKinds, loader);
    }

    static EffectKindRegistry build(Collection<String> enabledKinds, ClassLoader loader) {
        Map<String, EffectKind> discovered = new LinkedHashMap<>();
        ServiceLoader<EffectKind> sl = loader == null
                ? ServiceLoader.load(EffectKind.class)
                : ServiceLoader.load(EffectKind.class, loader);
        for (EffectKind k : sl) {
            if (k.kind() == null || k.kind().isBlank()) continue;
            if (BUILTINS.contains(k.kind())) continue;   // built-in names are reserved for the engine
            discovered.putIfAbsent(k.kind(), k);
        }

        Set<String> known = new LinkedHashSet<>(BUILTINS);
        known.addAll(discovered.keySet());

        Set<String> enabledSet;
        boolean unrestricted = enabledKinds == null || enabledKinds.isEmpty();
        if (unrestricted) {
            enabledSet = known;
        } else {
            enabledSet = new LinkedHashSet<>();
            for (String e : enabledKinds) {
                if (e != null && !e.isBlank()) enabledSet.add(e.trim());
            }
        }

        Map<String, EffectKind> enabledPlugins = new LinkedHashMap<>();
        for (Map.Entry<String, EffectKind> en : discovered.entrySet()) {
            if (enabledSet.contains(en.getKey())) enabledPlugins.put(en.getKey(), en.getValue());
        }
        return new EffectKindRegistry(enabledPlugins, enabledSet);
    }

    /** True for one of the four engine-handled built-in kinds. */
    public boolean isBuiltin(String kind) {
        return BUILTINS.contains(kind);
    }

    /**
     * True when {@code kind} may be used by a spec: it is enabled <em>and</em> actually available (a
     * built-in, or a discovered plugin). An enable-list entry naming a kind no plugin provides is not
     * usable.
     */
    public boolean isEnabled(String kind) {
        return enabled.contains(kind) && (isBuiltin(kind) || plugins.containsKey(kind));
    }

    /** The plugin {@link EffectKind} for {@code kind}, or {@code null} if it is a built-in or unknown. */
    public EffectKind plugin(String kind) {
        return plugins.get(kind);
    }

    /**
     * Whether {@code kind} has durable, foldable in-flight state (participates in reconcile / superseded
     * re-fire). Built-ins: everything except {@code caller}. Plugins: their declared {@link
     * EffectKind#durable()} (unknown kinds default to durable — validation rejects them earlier anyway).
     */
    public boolean durable(String kind) {
        EffectKind k = plugins.get(kind);
        if (k != null) return k.durable();
        return !CALLER.equals(kind);
    }
}
