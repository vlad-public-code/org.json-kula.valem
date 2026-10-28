package org.json_kula.valem.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.IntNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class StatelessRunnerTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** A cleaning quote: price from rooms, a FLAG minimum and a ROLLBACK cap, one passing self-test. */
    private static final String QUOTE = """
            {
              "id": "cleaning-quote",
              "version": "1.0.0",
              "schema": {
                "type": "object",
                "properties": {
                  "rooms": {"type": "integer", "minimum": 0, "default": 2},
                  "price": {"type": "number", "readOnly": true}
                }
              },
              "derivations": [{"path": "$.price", "expr": "rooms * 30"}],
              "constraints": [
                {"id": "min-order", "path": "$.price", "expr": "$ >= 80", "message": "Below the minimum order of 80", "policy": "flag"},
                {"id": "max-rooms", "expr": "rooms <= 20", "message": "At most 20 rooms", "policy": "rollback"}
              ],
              "tests": [{"description": "four rooms", "given": {"$.rooms": 4}, "expect": {"$.price": 120}}],
              "viewDefinition": {"defaultView": "main", "views": [{"id": "main", "label": "Quote",
                "components": [{"id": "rooms", "type": "numericField", "label": "Rooms", "bind": "$.rooms"}]}]}
            }
            """;

    private final StatelessRunner runner = new StatelessRunner();

    @Test
    void prepares_a_valid_spec_and_runs_it_from_the_cache() throws Exception {
        StatelessRunner.Prepared p = runner.prepare(MAPPER.readTree(QUOTE));

        assertThat(p.ok()).isTrue();
        assertThat(p.specHash()).startsWith("sha256:");
        assertThat(runner.isCached(p.specHash())).isTrue();

        StatelessRunner.Outcome out = runner.run(p.specHash(), Map.of("$.rooms", IntNode.valueOf(4)));
        assertThat(out).isInstanceOf(StatelessRunner.Computed.class);
        StatelessRunner.Computed c = (StatelessRunner.Computed) out;
        assertThat(c.state().path("rooms").asInt()).isEqualTo(4);
        assertThat(c.state().path("price").asInt()).isEqualTo(120);
        assertThat(c.flags()).isEmpty();
    }

    @Test
    void schema_defaults_apply_when_no_inputs_are_sent_and_flags_report_the_final_state() throws Exception {
        String hash = runner.prepare(MAPPER.readTree(QUOTE)).specHash();

        StatelessRunner.Computed c = (StatelessRunner.Computed) runner.run(hash, Map.of());

        assertThat(c.state().path("price").asInt()).isEqualTo(60);
        assertThat(c.flags()).extracting(StatelessRunner.Issue::id).containsExactly("min-order");
        assertThat(c.flags().getFirst().paths()).containsExactly("$.price");
    }

    @Test
    void a_flag_the_inputs_fix_is_not_reported() throws Exception {
        String hash = runner.prepare(MAPPER.readTree(QUOTE)).specHash();

        StatelessRunner.Computed c = (StatelessRunner.Computed) runner.run(hash, Map.of("$.rooms", IntNode.valueOf(3)));

        assertThat(c.flags()).isEmpty();
    }

    @Test
    void a_rollback_rule_rejects_the_inputs_with_its_message() throws Exception {
        String hash = runner.prepare(MAPPER.readTree(QUOTE)).specHash();

        StatelessRunner.Outcome out = runner.run(hash, Map.of("$.rooms", IntNode.valueOf(25)));

        assertThat(out).isInstanceOf(StatelessRunner.Rejected.class);
        StatelessRunner.Rejected r = (StatelessRunner.Rejected) out;
        assertThat(r.reason()).isEqualTo("constraint");
        assertThat(r.issues()).extracting(StatelessRunner.Issue::message).containsExactly("At most 20 rooms");
    }

    @Test
    void a_schema_violation_rejects_the_inputs() throws Exception {
        String hash = runner.prepare(MAPPER.readTree(QUOTE)).specHash();

        StatelessRunner.Outcome out = runner.run(hash, Map.of("$.rooms", IntNode.valueOf(-1)));

        assertThat(out).isInstanceOf(StatelessRunner.Rejected.class);
        assertThat(((StatelessRunner.Rejected) out).reason()).isEqualTo("schema");
    }

    @Test
    void a_failing_self_test_makes_the_spec_invalid_and_nothing_is_cached() throws Exception {
        ObjectNode spec = (ObjectNode) MAPPER.readTree(QUOTE);
        ((ObjectNode) spec.withArray("tests").get(0).get("expect")).put("$.price", 999);

        StatelessRunner.Prepared p = runner.prepare(spec);

        assertThat(p.ok()).isFalse();
        assertThat(p.errors()).isNotEmpty();
        assertThat(runner.isCached(p.specHash())).isFalse();
    }

    @Test
    void an_unparseable_spec_is_reported_not_thrown() throws Exception {
        ObjectNode spec = (ObjectNode) MAPPER.readTree(QUOTE);
        spec.put("derivatons", "typo");

        StatelessRunner.Prepared p = runner.prepare(spec);

        assertThat(p.ok()).isFalse();
        assertThat(p.errors().getFirst().message()).contains("derivatons");
    }

    @Test
    void an_unknown_hash_asks_for_the_spec() {
        assertThatThrownBy(() -> runner.run("sha256:00", Map.of()))
                .isInstanceOf(StatelessRunner.SpecUnknownException.class);
    }

    @Test
    void an_evicted_spec_is_unknown_again() throws Exception {
        StatelessRunner tiny = new StatelessRunner(1, Duration.ofHours(1));
        String first = tiny.prepare(MAPPER.readTree(QUOTE)).specHash();
        ObjectNode other = (ObjectNode) MAPPER.readTree(QUOTE);
        other.put("id", "other-quote");
        tiny.prepare(other);

        // Caffeine evicts asynchronously; a run on the cache drains pending maintenance.
        tiny.isCached(first);
        assertThatThrownBy(() -> {
            for (int i = 0; i < 50 && tiny.isCached(first); i++) Thread.sleep(10);
            tiny.run(first, Map.of());
        }).isInstanceOf(StatelessRunner.SpecUnknownException.class);
    }

    @Test
    void the_hash_ignores_key_order_and_formatting() throws Exception {
        JsonNode a = MAPPER.readTree("{\"b\": 1, \"a\": {\"y\": [1, 2], \"x\": true}}");
        JsonNode b = MAPPER.readTree("{\"a\":{\"x\":true,\"y\":[1,2]},\"b\":1}");

        assertThat(StatelessRunner.specHash(a)).isEqualTo(StatelessRunner.specHash(b));
        assertThat(StatelessRunner.specHash(a))
                .isNotEqualTo(StatelessRunner.specHash(MAPPER.readTree("{\"a\":{\"x\":true,\"y\":[2,1]},\"b\":1}")));
    }
}
