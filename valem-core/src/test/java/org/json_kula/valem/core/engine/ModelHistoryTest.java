package org.json_kula.valem.core.engine;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.json_kula.valem.core.state.Snapshot;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Collections;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ModelHistoryTest {

    private static Snapshot stub(String label) {
        ObjectNode doc = JsonNodeFactory.instance.objectNode();
        doc.put("_label", label);
        return new Snapshot("m", "1.0.0", doc, Collections.emptyMap(), Collections.emptyMap());
    }

    // ── Basic record / retrieve ───────────────────────────────────────────────

    @Test
    void empty_history_returns_empty_for_any_time() {
        ModelHistory h = new ModelHistory();
        assertThat(h.findAt(Instant.now())).isEmpty();
        assertThat(h.timestamps()).isEmpty();
        assertThat(h.size()).isZero();
    }

    @Test
    void single_entry_returned_for_exact_timestamp() {
        ModelHistory h = new ModelHistory();
        Instant t = Instant.parse("2026-06-08T10:00:00Z");
        Snapshot snap = stub("a");
        h.record(t, snap);

        assertThat(h.findAt(t)).contains(snap);
        assertThat(h.size()).isEqualTo(1);
    }

    @Test
    void single_entry_returned_for_timestamp_after_it() {
        ModelHistory h = new ModelHistory();
        Instant t = Instant.parse("2026-06-08T10:00:00Z");
        h.record(t, stub("a"));

        assertThat(h.findAt(t.plusSeconds(3600))).isPresent();
    }

    @Test
    void single_entry_not_returned_for_timestamp_before_it() {
        ModelHistory h = new ModelHistory();
        Instant t = Instant.parse("2026-06-08T10:00:00Z");
        h.record(t, stub("a"));

        assertThat(h.findAt(t.minusSeconds(1))).isEmpty();
    }

    // ── Multiple entries ──────────────────────────────────────────────────────

    @Test
    void find_returns_latest_at_or_before_query_time() {
        ModelHistory h = new ModelHistory();
        Instant t1 = Instant.parse("2026-06-08T10:00:00Z");
        Instant t2 = Instant.parse("2026-06-08T10:01:00Z");
        Instant t3 = Instant.parse("2026-06-08T10:02:00Z");
        Snapshot s1 = stub("s1");
        Snapshot s2 = stub("s2");
        Snapshot s3 = stub("s3");
        h.record(t1, s1);
        h.record(t2, s2);
        h.record(t3, s3);

        // Query between t2 and t3 → should return s2
        assertThat(h.findAt(t2.plusSeconds(30))).contains(s2);
        // Query at exactly t3 → should return s3
        assertThat(h.findAt(t3)).contains(s3);
        // Query before all → empty
        assertThat(h.findAt(t1.minusSeconds(1))).isEmpty();
        // Query after all → s3
        assertThat(h.findAt(t3.plusSeconds(3600))).contains(s3);
    }

    @Test
    void timestamps_returned_in_chronological_order() {
        ModelHistory h = new ModelHistory();
        Instant t1 = Instant.parse("2026-06-08T10:00:00Z");
        Instant t2 = Instant.parse("2026-06-08T10:01:00Z");
        h.record(t1, stub("a"));
        h.record(t2, stub("b"));

        assertThat(h.timestamps()).containsExactly(t1, t2);
    }

    // ── Bounded ring buffer ───────────────────────────────────────────────────

    @Test
    void oldest_entry_evicted_when_buffer_full() {
        ModelHistory h = new ModelHistory(3);
        Instant t1 = Instant.parse("2026-06-08T10:00:00Z");
        Instant t2 = t1.plusSeconds(1);
        Instant t3 = t2.plusSeconds(1);
        Instant t4 = t3.plusSeconds(1);
        Snapshot s1 = stub("s1");
        h.record(t1, s1);
        h.record(t2, stub("s2"));
        h.record(t3, stub("s3"));
        h.record(t4, stub("s4")); // evicts s1

        assertThat(h.size()).isEqualTo(3);
        assertThat(h.findAt(t1)).isEmpty(); // s1 was evicted
        assertThat(h.findAt(t2)).isPresent();
        assertThat(h.findAt(t4)).isPresent();
    }

    @Test
    void invalid_max_size_throws() {
        assertThatThrownBy(() -> new ModelHistory(-1))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void zero_max_size_disables_history() {
        ModelHistory h = new ModelHistory(0);
        h.record(Instant.now(), stub("a"));
        h.record(Instant.now(), stub("b"));
        assertThat(h.size()).isZero();
        assertThat(h.findAt(Instant.now())).isEmpty();
    }

    // ── Clear ─────────────────────────────────────────────────────────────────

    @Test
    void clear_removes_all_entries() {
        ModelHistory h = new ModelHistory();
        h.record(Instant.now(), stub("a"));
        h.record(Instant.now(), stub("b"));

        h.clear();

        assertThat(h.size()).isZero();
        assertThat(h.findAt(Instant.now().plusSeconds(3600))).isEmpty();
    }
}
