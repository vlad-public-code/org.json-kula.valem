package org.json_kula.valem.persistence.filesystem;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import org.json_kula.valem.core.engine.DerivationTrace;
import org.json_kula.valem.persistence.audit.AuditHashing;
import org.json_kula.valem.persistence.audit.AuditQuery;
import org.json_kula.valem.persistence.audit.AuditRecord;
import org.json_kula.valem.persistence.audit.AuditVerification;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class FilesystemAuditStoreTest {

    private static final ObjectMapper    MAPPER = new ObjectMapper();
    private static final JsonNodeFactory NF     = JsonNodeFactory.instance;

    @TempDir Path tempDir;

    private FilesystemAuditStore store() {
        return new FilesystemAuditStore(tempDir, MAPPER);
    }

    private AuditRecord rec(String path, Instant ts, String source) {
        return AuditRecord.of("order", ts, "1.0.0", source,
                Map.of(path, NF.numberNode(1)),
                List.of("$.order.total"),
                List.of(), List.of(),
                List.of(DerivationTrace.ofDerivation("$.order.total", "sum(items.price)",
                        List.of(path), NF.numberNode(42))));
    }

    @Test
    void is_enabled_returns_true() {
        assertThat(store().isEnabled()).isTrue();
    }

    @Test
    void append_assigns_monotonic_per_model_sequence() throws Exception {
        FilesystemAuditStore s = store();
        AuditRecord a = s.append(rec("$.order.a", Instant.parse("2026-01-01T00:00:00Z"), "client"));
        AuditRecord b = s.append(rec("$.order.b", Instant.parse("2026-01-02T00:00:00Z"), "client"));
        assertThat(a.sequence()).isZero();
        assertThat(b.sequence()).isEqualTo(1);
        assertThat(s.count("order")).isEqualTo(2);
    }

    @Test
    void append_survives_reopen_and_query_is_most_recent_first() throws Exception {
        store().append(rec("$.order.a", Instant.parse("2026-01-01T00:00:00Z"), "client"));
        store().append(rec("$.order.b", Instant.parse("2026-01-02T00:00:00Z"), "client"));
        // Fresh store instance over the same directory (simulates a restart).
        List<AuditRecord> all = store().query(AuditQuery.all("order"));
        assertThat(all).hasSize(2);
        assertThat(all.get(0).sequence()).isEqualTo(1);   // newest first
        assertThat(all.get(1).sequence()).isZero();
    }

    @Test
    void query_filters_by_path_prefix() throws Exception {
        FilesystemAuditStore s = store();
        s.append(rec("$.order.customer.name", Instant.parse("2026-01-01T00:00:00Z"), "client"));
        s.append(rec("$.order.items", Instant.parse("2026-01-02T00:00:00Z"), "client"));
        List<AuditRecord> onlyCustomer = s.query(
                new AuditQuery("order", "$.order.customer", null, null, 100));
        assertThat(onlyCustomer).hasSize(1);
        assertThat(onlyCustomer.get(0).mutations()).containsKey("$.order.customer.name");
    }

    @Test
    void query_filters_by_time_window() throws Exception {
        FilesystemAuditStore s = store();
        s.append(rec("$.order.a", Instant.parse("2026-01-01T00:00:00Z"), "client"));
        s.append(rec("$.order.b", Instant.parse("2026-03-01T00:00:00Z"), "client"));
        List<AuditRecord> feb = s.query(new AuditQuery("order", null,
                Instant.parse("2026-02-01T00:00:00Z"), null, 100));
        assertThat(feb).hasSize(1);
        assertThat(feb.get(0).mutations()).containsKey("$.order.b");
    }

    @Test
    void query_respects_limit_keeping_newest() throws Exception {
        FilesystemAuditStore s = store();
        for (int i = 0; i < 5; i++) {
            s.append(rec("$.order.x" + i, Instant.parse("2026-01-0" + (i + 1) + "T00:00:00Z"), "client"));
        }
        List<AuditRecord> two = s.query(new AuditQuery("order", null, null, null, 2));
        assertThat(two).hasSize(2);
        assertThat(two.get(0).sequence()).isEqualTo(4);
        assertThat(two.get(1).sequence()).isEqualTo(3);
    }

    @Test
    void deleteAudit_removes_trail() throws Exception {
        FilesystemAuditStore s = store();
        s.append(rec("$.order.a", Instant.now(), "client"));
        s.deleteAudit("order");
        assertThat(s.count("order")).isZero();
        assertThat(s.query(AuditQuery.all("order"))).isEmpty();
    }

    @Test
    void records_are_hash_chained_and_verify_intact() throws Exception {
        FilesystemAuditStore s = store();
        AuditRecord a = s.append(rec("$.order.a", Instant.parse("2026-01-01T00:00:00Z"), "client"));
        AuditRecord b = s.append(rec("$.order.b", Instant.parse("2026-01-02T00:00:00Z"), "client"));
        assertThat(a.prevHash()).isEqualTo(AuditHashing.GENESIS);
        assertThat(a.hash()).isNotBlank();
        assertThat(b.prevHash()).isEqualTo(a.hash());   // chain linkage

        AuditVerification v = s.verify("order");
        assertThat(v.valid()).isTrue();
        assertThat(v.recordsChecked()).isEqualTo(2);
    }

    @Test
    void verify_detects_a_tampered_record() throws Exception {
        FilesystemAuditStore s = store();
        s.append(rec("$.order.a", Instant.parse("2026-01-01T00:00:00Z"), "client"));
        s.append(rec("$.order.b", Instant.parse("2026-01-02T00:00:00Z"), "client"));

        // Tamper: rewrite the first line's payload while leaving its stored hash unchanged.
        Path file = tempDir.resolve("order").resolve("audit.jsonl");
        List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
        lines.set(0, lines.get(0).replace("\"source\":\"client\"", "\"source\":\"forged\""));
        Files.writeString(file, String.join("\n", lines) + "\n", StandardCharsets.UTF_8);

        AuditVerification v = s.verify("order");
        assertThat(v.valid()).isFalse();
        assertThat(v.firstBrokenSequence()).isZero();
        assertThat(v.detail()).contains("altered");
    }

    @Test
    void verify_detects_a_deleted_record() throws Exception {
        FilesystemAuditStore s = store();
        s.append(rec("$.order.a", Instant.parse("2026-01-01T00:00:00Z"), "client"));
        s.append(rec("$.order.b", Instant.parse("2026-01-02T00:00:00Z"), "client"));
        s.append(rec("$.order.c", Instant.parse("2026-01-03T00:00:00Z"), "client"));

        // Delete the middle record — the chain from the next record no longer links.
        Path file = tempDir.resolve("order").resolve("audit.jsonl");
        List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
        lines.remove(1);
        Files.writeString(file, String.join("\n", lines) + "\n", StandardCharsets.UTF_8);

        AuditVerification v = s.verify("order");
        assertThat(v.valid()).isFalse();
        assertThat(v.detail()).contains("reorder or deletion");
    }

    @Test
    void verify_empty_trail_is_valid() throws Exception {
        assertThat(store().verify("no-such-model").valid()).isTrue();
    }

    @Test
    void trace_survives_round_trip() throws Exception {
        FilesystemAuditStore s = store();
        s.append(rec("$.order.a", Instant.parse("2026-01-01T00:00:00Z"), "foldback"));
        AuditRecord loaded = s.query(AuditQuery.all("order")).get(0);
        assertThat(loaded.source()).isEqualTo("foldback");
        assertThat(loaded.traces()).hasSize(1);
        assertThat(loaded.traces().get(0).targetPath()).isEqualTo("$.order.total");
        assertThat(loaded.traces().get(0).result().intValue()).isEqualTo(42);
    }
}
