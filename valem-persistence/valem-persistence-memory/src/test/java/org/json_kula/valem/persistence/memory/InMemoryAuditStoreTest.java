package org.json_kula.valem.persistence.memory;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import org.json_kula.valem.core.engine.DerivationTrace;
import org.json_kula.valem.persistence.audit.AuditHashing;
import org.json_kula.valem.persistence.audit.AuditQuery;
import org.json_kula.valem.persistence.audit.AuditRecord;
import org.json_kula.valem.persistence.audit.AuditVerification;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class InMemoryAuditStoreTest {

    private static final JsonNodeFactory NF = JsonNodeFactory.instance;

    private AuditRecord rec(String modelId, String path, Instant ts) {
        return AuditRecord.of(modelId, ts, "1.0.0", "client",
                Map.of(path, NF.numberNode(1)),
                List.of("$.total"), List.of(), List.of(),
                List.of(DerivationTrace.ofDerivation("$.total", "a+b", List.of(path), NF.numberNode(3))));
    }

    @Test
    void is_enabled_is_true() {
        assertThat(new InMemoryAuditStore().isEnabled()).isTrue();
    }

    @Test
    void append_and_query_round_trip_newest_first() {
        InMemoryAuditStore s = new InMemoryAuditStore();
        s.append(rec("m", "$.a", Instant.parse("2026-01-01T00:00:00Z")));
        s.append(rec("m", "$.b", Instant.parse("2026-01-02T00:00:00Z")));
        List<AuditRecord> all = s.query(AuditQuery.all("m"));
        assertThat(all).hasSize(2);
        assertThat(all.get(0).sequence()).isEqualTo(1);
        assertThat(all.get(0).mutations()).containsKey("$.b");
    }

    @Test
    void trails_are_isolated_per_model() {
        InMemoryAuditStore s = new InMemoryAuditStore();
        s.append(rec("m1", "$.a", Instant.now()));
        s.append(rec("m2", "$.a", Instant.now()));
        assertThat(s.count("m1")).isEqualTo(1);
        assertThat(s.count("m2")).isEqualTo(1);
        assertThat(s.query(AuditQuery.all("m1"))).hasSize(1);
    }

    @Test
    void path_prefix_filter_applies() {
        InMemoryAuditStore s = new InMemoryAuditStore();
        s.append(rec("m", "$.order.customer.name", Instant.now()));
        s.append(rec("m", "$.order.items", Instant.now()));
        assertThat(s.query(new AuditQuery("m", "$.order.customer", null, null, 100))).hasSize(1);
    }

    @Test
    void deleteAudit_clears_model() {
        InMemoryAuditStore s = new InMemoryAuditStore();
        s.append(rec("m", "$.a", Instant.now()));
        s.deleteAudit("m");
        assertThat(s.count("m")).isZero();
    }

    @Test
    void query_unknown_model_is_empty() {
        assertThat(new InMemoryAuditStore().query(AuditQuery.all("nope"))).isEmpty();
    }

    @Test
    void appended_records_are_hash_chained_and_verify_intact() {
        InMemoryAuditStore s = new InMemoryAuditStore();
        AuditRecord a = s.append(rec("m", "$.a", Instant.parse("2026-01-01T00:00:00Z")));
        AuditRecord b = s.append(rec("m", "$.b", Instant.parse("2026-01-02T00:00:00Z")));
        assertThat(a.prevHash()).isEqualTo(AuditHashing.GENESIS);
        assertThat(b.prevHash()).isEqualTo(a.hash());
        AuditVerification v = s.verify("m");
        assertThat(v.valid()).isTrue();
        assertThat(v.recordsChecked()).isEqualTo(2);
    }

    @Test
    void verify_empty_or_unknown_trail_is_valid() {
        assertThat(new InMemoryAuditStore().verify("none").valid()).isTrue();
    }
}
