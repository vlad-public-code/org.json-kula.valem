package org.json_kula.valem.core.engine;

import org.json_kula.jsonata_jvm.JsonataCompilationException;
import org.json_kula.jsonata_jvm.JsonataExpression;
import org.json_kula.jsonata_jvm.JsonataExpressionFactory;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Thread-safe, bounded cache of compiled {@link JsonataExpression} objects.
 *
 * <p>Compilation (parse → translate → javac → load) is expensive. One
 * {@link ExpressionCache} per runtime instance amortises that cost so that
 * each distinct expression string is compiled at most once.
 *
 * <p>The cache is bounded (LRU, {@value #DEFAULT_MAX_SIZE} entries by default, overridable via the
 * {@code valem.limits.expression-cache-size} system property) so it cannot grow without limit
 * (audit MEM-2). This matters for a frequently-evolved model — whose runtimes seed forward every
 * expression they have ever had — and for the long-lived, server-lifetime shell caches. Eviction is
 * always safe: an evicted expression is simply recompiled on next use.
 */
public final class ExpressionCache {

    static final int DEFAULT_MAX_SIZE =
            Math.max(64, Integer.getInteger("valem.limits.expression-cache-size", 10_000));

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

    // Access-ordered LRU: the eldest (least-recently-used) entry is evicted past the size bound.
    // Wrapped with synchronizedMap so computeIfAbsent/putAll are atomic across threads.
    private final Map<String, JsonataExpression> cache = Collections.synchronizedMap(
            new LinkedHashMap<>(256, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, JsonataExpression> eldest) {
                    return size() > DEFAULT_MAX_SIZE;
                }
            });

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
        JsonataExpression compiled = compile(expr);
        // synchronizedMap uses the wrapper (== cache) as its mutex, so this block is atomic w.r.t. the
        // map's own get/put and never runs a compile while held.
        synchronized (cache) {
            JsonataExpression raced = cache.get(expr);
            if (raced != null) return raced;
            cache.put(expr, compiled);
            return compiled;
        }
    }

    private JsonataExpression compile(String expr) {
        try {
            return factory.compile(expr);
        } catch (JsonataCompilationException e) {
            throw new CompilationException(expr, e);
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
}
