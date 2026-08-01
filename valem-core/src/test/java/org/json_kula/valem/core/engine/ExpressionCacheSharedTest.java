package org.json_kula.valem.core.engine;

import org.json_kula.jsonata_jvm.JsonataExpression;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The process-wide shared compiled-expression cache (the javac-per-fork leak fix): distinct
 * {@link ExpressionCache} instances must reuse one compiled instance per expression, while each
 * instance keeps its own cold-start / size semantics.
 */
class ExpressionCacheSharedTest {

    @Test
    void two_caches_share_one_compiled_instance() {
        ExpressionCache a = new ExpressionCache();
        ExpressionCache b = new ExpressionCache();

        JsonataExpression fromA = a.get("shared_expr_alpha + shared_expr_beta");
        JsonataExpression fromB = b.get("shared_expr_alpha + shared_expr_beta");

        assertThat(fromB)
                .as("a second runtime reuses the first runtime's compiled class, not a fresh compile")
                .isSameAs(fromA);
        // The generated class is the same too — the whole point (one class in metaspace, not two).
        assertThat(fromB.getClass()).isSameAs(fromA.getClass());
    }

    @Test
    void a_fresh_cache_reports_cold_even_when_another_cache_already_compiled_it() {
        ExpressionCache first = new ExpressionCache();
        first.get("cold_start_probe_x * 2");
        assertThat(first.isCompiled("cold_start_probe_x * 2")).isTrue();

        // A brand-new cache has an empty *local* view — isCompiled/size are per-instance — even though
        // the expression is warm in the shared cache. get() then reuses the shared instance.
        ExpressionCache fresh = new ExpressionCache();
        assertThat(fresh.isCompiled("cold_start_probe_x * 2")).isFalse();
        assertThat(fresh.size()).isZero();
        assertThat(fresh.get("cold_start_probe_x * 2"))
                .isSameAs(first.get("cold_start_probe_x * 2"));
        assertThat(fresh.isCompiled("cold_start_probe_x * 2")).isTrue();
        assertThat(fresh.size()).isEqualTo(1);
    }
}
