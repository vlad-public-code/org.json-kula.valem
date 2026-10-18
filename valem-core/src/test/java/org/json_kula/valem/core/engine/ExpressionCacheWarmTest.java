package org.json_kula.valem.core.engine;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link ExpressionCache#warm} batch-compiles expressions up front; failures fall back to lazy compile.
 *
 * <p>Expression literals here are namespaced ({@code wcwt*}) rather than the more obvious {@code "a +
 * b"}: {@link ExpressionCache}'s {@code SHARED} map is process-wide by design (the javac-per-fork
 * Metaspace fix), so a common literal already compiled by another test class in the same surefire fork
 * (e.g. {@code SpecVerifierTest}, {@code VerificationClassifierTest}) would make a fresh {@link
 * ExpressionCache} appear to have it pre-warmed, breaking these assertions depending on run order.
 */
class ExpressionCacheWarmTest {

    @Test
    void warm_compilesEveryExpression_upFront() {
        ExpressionCache cache = new ExpressionCache();
        assertThat(cache.isCompiled("wcwtA + wcwtB")).isFalse();

        cache.warm(List.of("wcwtA + wcwtB", "$sum(wcwtItems.wcwtPrice)", "wcwtStatus = \"active\""));

        assertThat(cache.isCompiled("wcwtA + wcwtB")).isTrue();
        assertThat(cache.isCompiled("$sum(wcwtItems.wcwtPrice)")).isTrue();
        assertThat(cache.isCompiled("wcwtStatus = \"active\"")).isTrue();
        assertThat(cache.size()).isEqualTo(3);
    }

    @Test
    void warm_isIdempotent_andSkipsAlreadyCachedAndBlankEntries() {
        ExpressionCache cache = new ExpressionCache();
        cache.get("wcwtC + wcwtD");                       // compile one directly first
        cache.warm(List.of("wcwtC + wcwtD", "  ", "wcwtE * wcwtF")); // blank skipped, already present

        assertThat(cache.isCompiled("wcwtE * wcwtF")).isTrue();
        assertThat(cache.size()).isEqualTo(2);    // wcwtC+wcwtD and wcwtE*wcwtF only

        cache.warm(List.of("wcwtC + wcwtD", "wcwtE * wcwtF"));    // fully cached → no-op
        assertThat(cache.size()).isEqualTo(2);
    }

    @Test
    void warm_withInvalidExpression_skipsOnlyThatOne_andLeavesLazyCompileToReportTheError() {
        ExpressionCache cache = new ExpressionCache();

        // The invalid entry is dropped before the batch, so warm never throws and the valid
        // neighbours are still batch-compiled (one bad view label must not cost a model its warm-up)…
        cache.warm(List.of("wcwtG + wcwtH", "1 +", "wcwtI * wcwtJ", "wcwtTotal: $"));
        assertThat(cache.isCompiled("wcwtG + wcwtH")).isTrue();
        assertThat(cache.isCompiled("wcwtI * wcwtJ")).isTrue();
        assertThat(cache.isCompiled("1 +")).isFalse();
        assertThat(cache.isCompiled("wcwtTotal: $")).isFalse();

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
