package org.json_kula.valem.core.graph;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.json_kula.valem.core.model.ModelSpec;

import static org.assertj.core.api.Assertions.assertThat;

class SpecExpressionsTest {

    private final ObjectMapper mapper = new ObjectMapper();

    private ModelSpec parse(String json) throws Exception {
        return mapper.readValue(json, ModelSpec.class);
    }

    @Test
    void collect_gathersDerivationMetaConstraintDefaultAndEffectExpressions() throws Exception {
        ModelSpec spec = parse("""
            {
              "id": "m", "schema": {},
              "derivations":   [ { "path": "$.total", "expr": "subtotal + tax" } ],
              "metaDerivations": [ { "path": "$.total", "property": "readOnly", "expr": "locked = true" } ],
              "constraints":   [ { "id": "c1", "expr": "total >= 0", "policy": "flag" } ],
              "defaultValues": [ { "path": "$", "expr": "{ 'subtotal': 0 }" } ],
              "effects": [
                { "id": "e1", "executor": "timer", "trigger": "total > 100",
                  "dedupeKey": "total", "afterMs": "1000" }
              ]
            }
            """);

        assertThat(SpecExpressions.collect(spec))
                .contains("subtotal + tax", "locked = true", "total >= 0",
                          "{ 'subtotal': 0 }", "total > 100", "total", "1000");
    }

    @Test
    void collect_skipsBlankExpressions_andEmptySpecYieldsEmptyList() throws Exception {
        assertThat(SpecExpressions.collect(parse("""
                { "id": "m", "schema": {} }
                """))).isEmpty();
    }
}
