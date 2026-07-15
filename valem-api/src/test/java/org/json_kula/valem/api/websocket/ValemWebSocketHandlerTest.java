package org.json_kula.valem.api.websocket;

import org.json_kula.valem.api.dto.DispatchedEffect;
import org.json_kula.valem.core.engine.ConstraintEvaluator;
import org.json_kula.valem.core.model.ConstraintPolicy;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class ValemWebSocketHandlerTest {

    // ── eventMatchesFilter ─────────────────────────────────────────────────────

    @Test
    void null_filter_always_matches() {
        ChangeEvent event = event(List.of("$.order.total"), List.of());
        assertThat(ValemWebSocketHandler.eventMatchesFilter(event, null)).isTrue();
    }

    @Test
    void mutated_path_starting_with_filter_prefix_matches() {
        ChangeEvent event = event(List.of("$.order.total"), List.of());
        assertThat(ValemWebSocketHandler.eventMatchesFilter(event, Set.of("$.order"))).isTrue();
    }

    @Test
    void derived_path_starting_with_filter_prefix_matches() {
        ChangeEvent event = event(List.of("$.subtotal"), List.of("$.order.total"));
        // filter watches $.order — only derivedUpdated matches
        assertThat(ValemWebSocketHandler.eventMatchesFilter(event, Set.of("$.order"))).isTrue();
    }

    @Test
    void exact_path_match_passes_filter() {
        ChangeEvent event = event(List.of("$.order.total"), List.of());
        assertThat(ValemWebSocketHandler.eventMatchesFilter(event, Set.of("$.order.total"))).isTrue();
    }

    @Test
    void unrelated_path_does_not_match_filter() {
        ChangeEvent event = event(List.of("$.order.total"), List.of("$.order.subtotal"));
        assertThat(ValemWebSocketHandler.eventMatchesFilter(event, Set.of("$.customer"))).isFalse();
    }

    @Test
    void empty_event_paths_do_not_match_path_filter() {
        ChangeEvent event = event(List.of(), List.of());
        assertThat(ValemWebSocketHandler.eventMatchesFilter(event, Set.of("$.order"))).isFalse();
    }

    @Test
    void event_with_flagged_constraints_always_passes_filter() {
        ConstraintEvaluator.Violation violation =
                new ConstraintEvaluator.Violation("c1", "violation message", ConstraintPolicy.FLAG);
        ChangeEvent event = new ChangeEvent("m", List.of(), List.of(), List.of(violation), List.of());
        // Filter targets $.order but violation is on unrelated.field — still passes
        assertThat(ValemWebSocketHandler.eventMatchesFilter(event, Set.of("$.order"))).isTrue();
    }

    @Test
    void event_with_dispatched_effects_always_passes_filter() {
        DispatchedEffect effect = new DispatchedEffect("e1", "alert", null);
        ChangeEvent event = new ChangeEvent("m", List.of(), List.of(), List.of(), List.of(effect));
        assertThat(ValemWebSocketHandler.eventMatchesFilter(event, Set.of("$.order"))).isTrue();
    }

    @Test
    void multiple_filter_paths_any_match_suffices() {
        ChangeEvent event = event(List.of("$.customer.name"), List.of());
        // Two filter paths; only $.customer matches
        assertThat(ValemWebSocketHandler.eventMatchesFilter(
                event, Set.of("$.order", "$.customer"))).isTrue();
    }

    // ── Helpers ────────────────────────────────────────────────────────────────

    private static ChangeEvent event(List<String> mutated, List<String> derived) {
        return new ChangeEvent("m", mutated, derived, List.of(), List.of());
    }
}
