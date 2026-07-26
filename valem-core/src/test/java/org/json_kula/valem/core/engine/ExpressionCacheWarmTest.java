package org.json_kula.valem.core.engine;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** {@link ExpressionCache#warm} batch-compiles expressions up front; failures fall back to lazy compile. */
class ExpressionCacheWarmTest {

    @Test
    void warm_compilesEveryExpression_upFront() {
        ExpressionCache cache = new ExpressionCache();
        assertThat(cache.isCompiled("a + b")).isFalse();

        cache.warm(List.of("a + b", "$sum(items.price)", "status = \"active\""));

        assertThat(cache.isCompiled("a + b")).isTrue();
        assertThat(cache.isCompiled("$sum(items.price)")).isTrue();
        assertThat(cache.isCompiled("status = \"active\"")).isTrue();
        assertThat(cache.size()).isEqualTo(3);
    }

    @Test
    void warm_isIdempotent_andSkipsAlreadyCachedAndBlankEntries() {
        ExpressionCache cache = new ExpressionCache();
        cache.get("a + b");                       // compile one directly first
        cache.warm(List.of("a + b", "  ", "c * d")); // blank skipped, "a + b" already present

        assertThat(cache.isCompiled("c * d")).isTrue();
        assertThat(cache.size()).isEqualTo(2);    // a+b and c*d only

        cache.warm(List.of("a + b", "c * d"));    // fully cached → no-op
        assertThat(cache.size()).isEqualTo(2);
    }

    @Test
    void warm_withInvalidExpression_isBestEffort_andLeavesLazyCompileToReportTheError() {
        ExpressionCache cache = new ExpressionCache();

        // The batch aborts on the invalid entry, so warm installs nothing and never throws…
        cache.warm(List.of("a + b", "1 +", "c * d"));
        assertThat(cache.isCompiled("a + b")).isFalse();

        // …a valid expression still compiles lazily on first get…
        assertThat(cache.get("a + b")).isNotNull();
        assertThat(cache.isCompiled("a + b")).isTrue();

        // …and the invalid one surfaces its compilation error at first use.
        assertThatThrownBy(() -> cache.get("1 +"))
                .isInstanceOf(ExpressionCache.CompilationException.class);
    }

    @Test
    void warm_ignoresNullAndEmptyInput() {
        ExpressionCache cache = new ExpressionCache();
        cache.warm(null);
        cache.warm(List.of());
        assertThat(cache.size()).isZero();
    }
}
