package org.json_kula.valem.core;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.json_kula.valem.core.model.*;

import static org.assertj.core.api.Assertions.assertThat;

class ModelSpecDeserializationTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void deserialise_minimal_spec() throws Exception {
        String json = """
            {
              "id": "order-model",
              "schema": { "$schema": "https://json-schema.org/draft/2020-12/schema", "type": "object" },
              "derivations": [
                { "path": "$.order.total", "expr": "order.subtotal + order.tax" }
              ],
              "metaDerivations": [
                { "path": "$.order.downPayment", "property": "minimum", "expr": "order.total * 0.2" }
              ],
              "constraints": [
                { "id": "credit-check", "expr": "order.total <= customer.creditLimit", "message": "Over limit", "policy": "rollback" }
              ]
            }
            """;

        ModelSpec spec = mapper.readValue(json, ModelSpec.class);

        assertThat(spec.id()).isEqualTo("order-model");
        assertThat(spec.version()).isEqualTo("1.0.0");
        assertThat(spec.derivations()).hasSize(1);
        assertThat(spec.derivations().getFirst().path()).isEqualTo("$.order.total");
        assertThat(spec.derivations().getFirst().evaluation()).isEqualTo(EvaluationMode.EAGER);
        assertThat(spec.metaDerivations()).hasSize(1);
        assertThat(spec.metaDerivations().getFirst().property()).isEqualTo(MetaProperty.MINIMUM);
        assertThat(spec.metaDerivations().getFirst().nodeKey()).isEqualTo("$.order.downPayment#minimum");
        assertThat(spec.constraints()).hasSize(1);
        assertThat(spec.constraints().getFirst().isGlobal()).isTrue();
        assertThat(spec.effects()).isEmpty();
        assertThat(spec.tests()).isEmpty();
    }

    @Test
    void deserialise_constraint_path_variants() throws Exception {
        String json = """
            {
              "id": "m",
              "schema": {},
              "constraints": [
                { "id": "a", "expr": "$ > 0", "message": "pos", "policy": "flag",
                  "path": "$.loan.amount" },
                { "id": "b", "expr": "$.qty > 0", "message": "qty", "policy": "rollback",
                  "path": "$.order.items[*]" },
                { "id": "c", "expr": "$ > 0", "message": "pos", "policy": "flag",
                  "path": ["$.loan.principal", "$.loan.fees"] }
              ]
            }
            """;

        ModelSpec spec = mapper.readValue(json, ModelSpec.class);
        assertThat(spec.constraints().get(0).isScalar()).isTrue();
        assertThat(spec.constraints().get(1).isArrayScoped()).isTrue();
        assertThat(spec.constraints().get(2).isMultiTarget()).isTrue();
    }

    @Test
    void blobRef_round_trip() {
        BlobRef ref = new BlobRef("sha256:abc123", "image/png", 4096L);
        assertThat(BlobRef.isBlobRef(ref.toJsonNode())).isTrue();
        BlobRef restored = BlobRef.fromJsonNode(ref.toJsonNode());
        assertThat(restored).isEqualTo(ref);
    }

    @Test
    void metaProperty_applicability() {
        assertThat(MetaProperty.MINIMUM.applicableTo()).containsOnly(FieldType.NUMBER);
        assertThat(MetaProperty.PATTERN.applicableTo()).containsOnly(FieldType.STRING);
        assertThat(MetaProperty.REQUIRED.applicableTo()).containsAll(java.util.List.of(FieldType.values()));
        assertThat(MetaProperty.RELEVANT.applicableTo()).containsAll(java.util.List.of(FieldType.values()));
    }

    @Test
    void fieldType_binary_detection() throws Exception {
        String binarySchema = """
            { "type": "object", "contentMediaType": "image/png", "x-valem-binary": true }
            """;
        var node = mapper.readTree(binarySchema);
        assertThat(FieldType.of(node)).isEqualTo(FieldType.BINARY);
        assertThat(FieldType.of(mapper.readTree("{\"type\":\"number\"}"))).isEqualTo(FieldType.NUMBER);
        assertThat(FieldType.of(mapper.readTree("{\"type\":\"string\"}"))).isEqualTo(FieldType.STRING);
    }
}

