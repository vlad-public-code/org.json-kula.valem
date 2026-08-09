package org.json_kula.valem.core.engine;

import org.junit.jupiter.api.BeforeAll;
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
 * constraint. Hence a modest default — configured in valem-api's {@code application.yml} and reaching
 * this Spring-less module as a system property — plus an environment variable, so a deployment can
 * tune it without rebuilding an image.
 */
class ExpressionCacheSizeTest {

    @BeforeAll
    static void loadTheClassBeforeAnyTestInstallsAnOverride() {
        // DEFAULT_MAX_SIZE is resolved once, in a static initialiser, on first touch of the class.
        // Touching it here — before the override tests below run — keeps what it captured independent
        // of JUnit's method execution order.
        assertThat(ExpressionCache.DEFAULT_MAX_SIZE).isGreaterThanOrEqualTo(64);
    }

    @Test
    void fallsBackToTheLibraryDefaultWhenNothingOverridesIt() {
        // The deployable's default is configured in valem-api's application.yml and reaches this
        // module as the system property; this fallback only covers an embedding that sets neither.
        assertThat(System.getProperty("valem.limits.expression-cache-size")).isNull();
        assertThat(System.getenv("VALEM_LIMITS_EXPRESSION_CACHE_SIZE")).isNull();
        assertThat(ExpressionCache.resolveMaxSize()).isEqualTo(500);
        assertThat(ExpressionCache.FALLBACK_MAX_SIZE).isEqualTo(500);
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
            assertThat(ExpressionCache.resolveMaxSize()).isEqualTo(500);
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
