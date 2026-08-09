package org.json_kula.valem.core.engine;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * How the compiled-expression bound is configured.
 *
 * <p>The bound is the one knob that decides this process's Metaspace footprint: every retained entry
 * pins a generated Java class and its classloader, and Metaspace is native memory that a container's
 * heap cap does not cover. A host running an agent that authors specs over MCP mints novel
 * expressions continuously, so an over-generous bound let the live set climb until the platform
 * OOM-killed the container — a silent restart with no Java error, because the heap was never the
 * constraint. Hence a modest default, and an environment variable so a deployment can tune it
 * without rebuilding an image.
 */
class ExpressionCacheSizeTest {

    @Test
    void defaultsTo250WhenNothingOverridesIt() {
        assertThat(System.getProperty("valem.limits.expression-cache-size")).isNull();
        assertThat(System.getenv("VALEM_LIMITS_EXPRESSION_CACHE_SIZE")).isNull();
        assertThat(ExpressionCache.resolveMaxSize()).isEqualTo(250);
    }

    @Test
    void aSystemPropertyOverridesTheDefault() {
        System.setProperty("valem.limits.expression-cache-size", "1200");
        try {
            assertThat(ExpressionCache.resolveMaxSize()).isEqualTo(1200);
        } finally {
            System.clearProperty("valem.limits.expression-cache-size");
        }
    }

    @Test
    void aPathologicallySmallValueIsFlooredRatherThanHonoured() {
        // Below the floor every model would evict its own expressions mid-evaluation and recompile
        // them, which trades the memory problem for a much worse CPU one.
        System.setProperty("valem.limits.expression-cache-size", "1");
        try {
            assertThat(ExpressionCache.resolveMaxSize()).isEqualTo(64);
        } finally {
            System.clearProperty("valem.limits.expression-cache-size");
        }
    }

    @Test
    void aMalformedOverrideFallsBackInsteadOfStoppingTheEngine() {
        // This is read in a static initialiser: throwing here would fail every model in the process.
        System.setProperty("valem.limits.expression-cache-size", "not-a-number");
        try {
            // Integer.getInteger yields null for an unparseable value, so resolution continues on to
            // the environment variable and then the default.
            assertThat(ExpressionCache.resolveMaxSize()).isEqualTo(250);
        } finally {
            System.clearProperty("valem.limits.expression-cache-size");
        }
    }

    @Test
    void theResolvedBoundIsWhatTheCacheActuallyUses() {
        assertThat(ExpressionCache.DEFAULT_MAX_SIZE).isEqualTo(ExpressionCache.resolveMaxSize());
        assertThat(ExpressionCache.DEFAULT_MAX_SIZE).isGreaterThanOrEqualTo(64);
    }
}
