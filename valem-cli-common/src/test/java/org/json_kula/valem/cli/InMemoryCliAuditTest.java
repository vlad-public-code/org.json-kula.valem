package org.json_kula.valem.cli;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.json_kula.valem.core.model.ModelSpec;
import org.json_kula.valem.service.ModelOperations;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The embedded CLI mode wires an {@link InMemoryCliAudit} so {@code get_audit}/{@code verify_audit} have
 * real data (via {@link CliBootstrap#createModelOperations}). This exercises the sink→reader round-trip
 * through the public {@link ModelOperations} facade.
 */
class InMemoryCliAuditTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final String SPEC = """
            { "id": "audited", "version": "1.0.0", "schema": {},
              "derivations": [ {"path": "$.total", "expr": "price * qty"} ],
              "constraints": [], "metaDerivations": [], "tests": [] }
            """;

    private static ModelOperations embedded() {
        return CliBootstrap.createModelOperations(
                new CliBootstrap.Options(null, null, false, false, false, java.util.List.of()), MAPPER);
    }

    @Test
    void embedded_mode_records_and_queries_audit() throws Exception {
        ModelOperations ops = embedded();
        ops.createModel(MAPPER.treeToValue(MAPPER.readTree(SPEC), ModelSpec.class));
        ops.mutate("audited", Map.of("$.price", MAPPER.getNodeFactory().numberNode(5),
                                     "$.qty",   MAPPER.getNodeFactory().numberNode(4)));

        JsonNode audit = ops.getAudit("audited", null, null, null, 0);
        assertThat(audit.isArray()).isTrue();
        assertThat(audit).isNotEmpty();
        JsonNode rec = audit.get(0);
        assertThat(rec.path("modelId").asText()).isEqualTo("audited");
        assertThat(rec.path("source").asText()).isEqualTo("client");
        assertThat(rec.path("derivedUpdated").toString()).contains("$.total");

        JsonNode verify = ops.verifyAudit("audited");
        assertThat(verify.path("valid").asBoolean()).isTrue();
        assertThat(verify.path("recordsChecked").asInt()).isGreaterThanOrEqualTo(1);
    }

    @Test
    void path_prefix_filters_audit_records() throws Exception {
        ModelOperations ops = embedded();
        ops.createModel(MAPPER.treeToValue(MAPPER.readTree(SPEC), ModelSpec.class));
        ops.mutate("audited", Map.of("$.price", MAPPER.getNodeFactory().numberNode(2),
                                     "$.qty",   MAPPER.getNodeFactory().numberNode(3)));

        // a prefix that no mutation/derivation/trace touched → no records
        assertThat(ops.getAudit("audited", "$.nonexistent", null, null, 0)).isEmpty();
        // the touched prefix → at least one
        assertThat(ops.getAudit("audited", "$.total", null, null, 0)).isNotEmpty();
    }
}
