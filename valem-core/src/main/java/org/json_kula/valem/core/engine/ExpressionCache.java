package org.json_kula.valem.core.engine;

import org.json_kula.jsonata_jvm.JsonataCompilationException;
import org.json_kula.jsonata_jvm.JsonataExpression;
import org.json_kula.jsonata_jvm.JsonataExpressionFactory;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Thread-safe, bounded cache of compiled {@link JsonataExpression} objects.
 *
 * <p>Compilation (parse → translate → javac → load) is expensive. One
 * {@link ExpressionCache} per runtime instance amortises that cost so that
 * each distinct expression string is compiled at most once.
 *
 * <p>The cache is bounded (LRU, 500 entries by default — see {@link #resolveMaxSize}) so it cannot
 * grow without limit (audit MEM-2). This matters for a frequently-evolved model — whose runtimes seed
 * forward every expression they have ever had — and for the long-lived, server-lifetime shell caches.
 * Eviction is always safe: an evicted expression is simply recompiled on next use.
 */
public final class ExpressionCache {

    /**
     * Last-resort bound when nothing configures one; mirrors the default in {@code application.yml}.
     * Declared before {@link #DEFAULT_MAX_SIZE} so it is assigned before the initialiser that reads it.
     */
    static final int FALLBACK_MAX_SIZE = 500;

    static final int DEFAULT_MAX_SIZE = resolveMaxSize();

    /**
     * How many compiled expressions to retain, from (in precedence order) the
     * {@code valem.limits.expression-cache-size} system property, the
     * {@code VALEM_LIMITS_EXPRESSION_CACHE_SIZE} environment variable, or the fallback below.
     *
     * <p><b>The configured value lives in {@code application.yml}, not here.</b> A server deployment
     * sets {@code valem.limits.expression-cache-size} (defaulted there to
     * {@code ${VALEM_LIMITS_EXPRESSION_CACHE_SIZE:500}}) alongside every other {@code valem.*}
     * setting, and {@code CoreLimitsEnvironmentPostProcessor} publishes it as the system property read
     * here before any core class loads. This module has no Spring, so it cannot read that file itself
     * and cannot lean on relaxed binding — hence the direct {@link System#getenv} branch, which is
     * also what configures the Spring-less embeddings (the MCP server, the console) and what lets a
     * platform tune a container without rebuilding its image. The constant below is only the
     * last-resort library fallback for an embedding that configures nothing at all; keep it in step
     * with the value in {@code application.yml}.
     *
     * <p><b>Why a few hundred and not the 10,000 this used to default to.</b> Each entry pins a
     * compiled Java class and its classloader in Metaspace, which is native memory and is not covered
     * by the heap cap a container sets. A memory-constrained host running an agent that authors specs
     * over MCP mints novel expressions continuously, so the live set climbed toward the bound and the
     * platform OOM-killed the container — a silent restart, with no Java error, because the heap was
     * never the constraint. A smaller bound trades CPU for memory: an evicted expression is recompiled
     * on next use, but the footprint stays flat because the dropped classloader becomes unreachable
     * and can be unloaded. Raise it on a host with memory to spare; the floor of 64 keeps a
     * pathologically small value from thrashing the compiler.
     */
    static int resolveMaxSize() {
        Integer fromProperty = Integer.getInteger("valem.limits.expression-cache-size");
        if (fromProperty != null) return Math.max(64, fromProperty);

        String fromEnv = System.getenv("VALEM_LIMITS_EXPRESSION_CACHE_SIZE");
        if (fromEnv != null && !fromEnv.isBlank()) {
            try {
                return Math.max(64, Integer.parseInt(fromEnv.trim()));
            } catch (NumberFormatException ignored) {
                // A malformed override must not stop the engine starting — fall through to the default.
            }
        }
        return FALLBACK_MAX_SIZE;
    }

    /** Last-resort evaluation timeout when nothing configures one; mirrors {@code application.yml}. */
    static final int FALLBACK_TIMEOUT_MS = 2_000;

    /**
     * Wall-clock budget for a single expression evaluation, from (in precedence order) the
     * {@code valem.limits.expression-timeout-ms} system property, the
     * {@code VALEM_LIMITS_EXPRESSION_TIMEOUT_MS} environment variable, or
     * {@link #FALLBACK_TIMEOUT_MS}. {@code 0} disables the timeout entirely.
     *
     * <p><b>Why there is a timeout at all.</b> Derivations, constraints and effect triggers are
     * evaluated <b>inside the model lock</b> ({@code ModelService} synchronizes on the runtime), so a
     * single non-terminating evaluation does not just fail one request — it holds the lock, and on a
     * host where Loom gives parallelism 1 it can wedge the whole service. Expression bodies are
     * LLM-authored; a {@code library} makes deep, self-recursive computation easy to write for the
     * first time. A bounded evaluation turns that from an outage into one failed field: the runtime
     * already maps an evaluation error to a {@code null} value plus a {@code DerivationTrace}.
     *
     * <p><b>Why it is wall-clock, and what that costs.</b> The deadline is checked against
     * {@code System.currentTimeMillis()}, which keeps advancing while the thread is descheduled, so
     * on a pathologically loaded host a normally-fast expression can be timed out. The bound is
     * therefore generous relative to real expressions (single-digit milliseconds) and configurable;
     * set it to {@code 0} where evaluation time is genuinely unbounded and the lock contention is
     * acceptable.
     *
     * <p>Enabling it also flips a process-wide flag in the JSONata runtime
     * ({@code JsonataRuntime.notifyTimeoutInUse}) that installs a deadline check at higher-order
     * call sites. That check is one thread-local read per 64 callback invocations — negligible, but
     * it is not free, which is the other reason the value is configurable.
     */
    static int resolveTimeoutMs() {
        Integer fromProperty = Integer.getInteger("valem.limits.expression-timeout-ms");
        if (fromProperty != null) return Math.max(0, fromProperty);

        String fromEnv = System.getenv("VALEM_LIMITS_EXPRESSION_TIMEOUT_MS");
        if (fromEnv != null && !fromEnv.isBlank()) {
            try {
                return Math.max(0, Integer.parseInt(fromEnv.trim()));
            } catch (NumberFormatException ignored) {
                // A malformed override must not stop the engine starting — fall through to the default.
            }
        }
        return FALLBACK_TIMEOUT_MS;
    }

    /** Unchecked wrapper thrown when a JSONata expression fails to compile. */
    public static final class CompilationException extends RuntimeException {
        private final String expression;

        CompilationException(String expression, JsonataCompilationException cause) {
            super("Cannot compile JSONata expression: " + expression + " — " + cause.getMessage(), cause);
            this.expression = expression;
        }

        public String expression() { return expression; }
    }

    private final JsonataExpressionFactory factory = new JsonataExpressionFactory();

    /**
     * Process-wide compiled-expression cache shared by <b>every</b> {@link ExpressionCache} instance
     * (audit MEM-2 / the javac-per-fork leak). Each JSONata expression compiles to a distinct Java
     * class that occupies metaspace for as long as it is reachable; a per-runtime cache recompiled the
     * same expression — a fresh class + classloader — once per model, so a long-lived JVM (a server,
     * or a single test fork creating many models) accumulated classes without bound. Compiled
     * expressions are immutable and stateless (bindings are passed per-evaluate), so one instance is
     * safely reused across all runtimes: keying compilation on the shared map means each distinct
     * expression compiles <b>once per JVM</b>, and the metaspace footprint is bounded by this LRU.
     */
    private static final Map<String, JsonataExpression> SHARED = Collections.synchronizedMap(
            new LinkedHashMap<>(256, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, JsonataExpression> eldest) {
                    return size() > DEFAULT_MAX_SIZE;
                }
            });

    // Per-instance access-ordered LRU. It preserves the per-runtime API (cold start, size, isCompiled)
    // and holds only references to instances the shared cache already owns — so it adds no metaspace.
    private final Map<String, JsonataExpression> cache = Collections.synchronizedMap(
            new LinkedHashMap<>(256, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, JsonataExpression> eldest) {
                    return size() > DEFAULT_MAX_SIZE;
                }
            });

    /**
     * Returns the process-wide compiled instance for {@code expr}, compiling only if no instance
     * (in this cache or any other) exists yet. The first writer wins, so all callers share one object.
     */
    private JsonataExpression canonical(String expr) {
        JsonataExpression shared = SHARED.get(expr);
        if (shared != null) return shared;
        JsonataExpression compiled = compile(expr);          // outside any lock
        JsonataExpression existing = SHARED.putIfAbsent(expr, compiled);
        return existing != null ? existing : compiled;       // a raced duplicate is discarded
    }

    /**
     * Returns the compiled expression for {@code expr}, compiling it on first access.
     *
     * <p>Compilation happens <b>outside</b> the cache lock: a cache miss compiles into a local, then a
     * short critical section installs it. This keeps the expensive javac round-trip off the shared
     * mutex, so one thread compiling a new expression never blocks another thread's cache hit on the
     * same (per-runtime or shared shell) cache. A rare race just compiles a duplicate — the result is
     * immutable, and the first installed instance wins so all callers still share one object.
     *
     * @throws CompilationException if the expression is syntactically invalid
     */
    public JsonataExpression get(String expr) {
        JsonataExpression cached = cache.get(expr);
        if (cached != null) return cached;
        // Miss on the per-instance map: get the process-wide canonical instance (compiling only if no
        // runtime has compiled this expression yet), then record it locally so this cache's own
        // cold-start / size / isCompiled semantics still reflect what it has actually used.
        JsonataExpression canonical = canonical(expr);
        cache.put(expr, canonical);
        return canonical;
    }

    private JsonataExpression compile(String expr) {
        try {
            return withTimeout(factory.compile(expr));
        } catch (JsonataCompilationException e) {
            throw new CompilationException(expr, e);
        }
    }

    /**
     * Applies the configured evaluation timeout to a freshly compiled expression.
     *
     * <p>Applied here, at compile time, rather than per evaluation: the deadline itself is computed
     * when an evaluation begins, so the budget is a property of the compiled instance and is safe to
     * set before the instance is published to the shared cache and used concurrently.
     *
     * <p>Resolved per compilation rather than captured in a static initialiser like
     * {@link #DEFAULT_MAX_SIZE}. The two settings are not alike: the cache bound sizes a structure
     * allocated once, while the budget is stamped onto each compiled expression, and reading a system
     * property is nothing beside the javac round-trip that just happened. The payoff is that changing
     * the setting takes effect for expressions compiled afterwards, instead of requiring a restart.
     * (An already-compiled expression keeps the budget it was given — the shared cache hands out one
     * instance per expression for the life of the process.)
     */
    private static JsonataExpression withTimeout(JsonataExpression compiled) {
        int timeoutMs = resolveTimeoutMs();
        if (timeoutMs > 0) compiled.setTimeout(timeoutMs);
        return compiled;
    }

    /**
     * Pre-compiles every not-yet-cached expression in {@code expressions} in a <b>single</b> javac
     * invocation via {@link JsonataExpressionFactory#compileAll}, rather than one invocation per
     * expression. Each javac invocation carries a large fixed cost (compiler bootstrap, platform
     * symbol loading, classpath indexing), so batching a model's expressions up front is markedly
     * faster than compiling them lazily one at a time on first access.
     *
     * <p>Best-effort by design: if the batch contains a syntactically invalid expression, {@code
     * compileAll} aborts and nothing is installed — the expressions are then compiled lazily on first
     * {@link #get}, which surfaces the precise error (e.g. during validation). Warming never throws;
     * a failure only forgoes the batch speedup. Blank/duplicate/already-cached entries are skipped.
     */
    public void warm(Collection<String> expressions) {
        if (expressions == null || expressions.isEmpty()) return;
        List<String> candidates = expressions.stream()
                .filter(Objects::nonNull)
                .filter(e -> !e.isBlank())
                .distinct()
                .filter(e -> !cache.containsKey(e))
                .toList();
        if (candidates.isEmpty()) return;

        // Anything another runtime already compiled is pulled from the shared cache (no javac); only
        // the genuinely-never-seen expressions are batch-compiled once and shared.
        List<String> toCompile = new ArrayList<>();
        for (String e : candidates) {
            JsonataExpression shared = SHARED.get(e);
            if (shared != null) cache.putIfAbsent(e, shared);
            else toCompile.add(e);
        }
        if (toCompile.isEmpty()) return;
        try {
            List<JsonataExpression> compiled = factory.compileAll(toCompile);
            for (int i = 0; i < toCompile.size(); i++) {
                String e = toCompile.get(i);
                JsonataExpression fresh = withTimeout(compiled.get(i));
                JsonataExpression existing = SHARED.putIfAbsent(e, fresh);
                cache.putIfAbsent(e, existing != null ? existing : fresh);
            }
        } catch (JsonataCompilationException e) {
            // Best-effort: leave the expressions for lazy per-expression compilation via get(),
            // which reports the precise CompilationException at first use.
        }
    }

    /**
     * Copies all already-compiled entries from {@code other} into this cache. Compiled
     * expressions are immutable, so sharing them across runtimes is safe; entries whose
     * expression text is no longer used are simply never read. Used on spec evolution so the
     * new runtime does not re-run the expensive javac round-trip for unchanged expressions.
     */
    public void seedFrom(ExpressionCache other) {
        if (other != null && other != this) {
            synchronized (other.cache) {
                this.cache.putAll(other.cache);
            }
        }
    }

    /** True when {@code expr} is already compiled and cached (does not trigger compilation). */
    public boolean isCompiled(String expr) {
        return cache.containsKey(expr);
    }

    /** Current number of cached compiled expressions (for observability gauges). */
    public int size() {
        return cache.size();
    }

    /**
     * Drops this cache's per-instance compiled-expression references. The process-wide shared cache is
     * untouched, so a shared expression keeps its single class; but a class that the shared cache has
     * already LRU-evicted can now be reclaimed once no other runtime pins it. Called when a model that
     * <b>owns</b> this cache is disposed. Safe to call more than once.
     */
    public void clear() {
        cache.clear();
    }
}
