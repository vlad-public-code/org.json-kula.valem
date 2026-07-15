package org.json_kula.valem.core.model;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.json_kula.valem.core.graph.ModelSpecValidator;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * M1 — coordinate/link/template descriptors parse, validate, and round-trip. No execution.
 */
class CompositionDescriptorTest {

    // Matches how the app/console/MCP read specs (tolerant of unknown properties).
    private final ObjectMapper mapper = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    private String baseSchema() {
        return "\"schema\":{\"type\":\"object\"}";
    }

    private ModelSpec parse(String json) throws Exception {
        return mapper.readValue(json, ModelSpec.class);
    }

    private ModelSpecValidator.ValidationResult validate(String json) throws Exception {
        return ModelSpecValidator.validate(parse(json));
    }

    @Test
    void writeLinkParsesValidatesAndRoundTrips() throws Exception {
        String json = "{\"id\":\"a\",\"version\":\"1.0.0\"," + baseSchema() + ","
                + "\"effects\":[{\"id\":\"push\",\"executor\":\"server\",\"trigger\":\"x = true\","
                + "\"dedupeKey\":\"rev\",\"target\":{\"ref\":\"acme/consol@>=2.0.0\",\"path\":\"$.in\"},"
                + "\"body\":\"{ 'v': x }\","
                + "\"statusPath\":\"$.$io.push\"}]}";
        ModelSpec spec = parse(json);
        TargetSpec t = spec.effects().get(0).target();
        assertThat(t.ref()).isEqualTo("acme/consol@>=2.0.0");
        assertThat(t.isWrite()).isTrue();
        assertThat(spec.effects().get(0).body()).isEqualTo("{ 'v': x }");
        assertThat(validate(json).isValid()).isTrue();

        // round-trip the new target/body descriptor fields (component names match JSON keys)
        ModelSpec again = mapper.readValue(mapper.writeValueAsString(spec), ModelSpec.class);
        assertThat(again.effects().get(0).target().path()).isEqualTo("$.in");
        assertThat(again.effects().get(0).target().ref()).isEqualTo("acme/consol@>=2.0.0");
        assertThat(again.effects().get(0).body()).isEqualTo("{ 'v': x }");
    }

    @Test
    void readLinkParsesAndValidates() throws Exception {
        String json = "{\"id\":\"a\",\"version\":\"1.0.0\"," + baseSchema() + ","
                + "\"effects\":[{\"id\":\"pull\",\"executor\":\"server\",\"trigger\":\"c != null\","
                + "\"target\":{\"ref\":\"acme.fx/rates@^1.0.0\",\"read\":\"$.rates\"},"
                + "\"statusPath\":\"$.$io.fx\"}]}";
        ModelSpec spec = parse(json);
        assertThat(spec.effects().get(0).target().isRead()).isTrue();
        assertThat(validate(json).isValid()).isTrue();
    }

    @Test
    void rejectsBothUrlAndTarget() throws Exception {
        String json = "{\"id\":\"a\",\"version\":\"1.0.0\"," + baseSchema() + ","
                + "\"effects\":[{\"id\":\"e\",\"executor\":\"server\",\"trigger\":\"true\","
                + "\"request\":{\"url\":\"https://x\"},\"target\":{\"ref\":\"a/b\",\"path\":\"$.p\"}}]}";
        assertThat(validate(json).isValid()).isFalse();
    }

    @Test
    void rejectsTargetWithBothPathAndRead() throws Exception {
        String json = "{\"id\":\"a\",\"version\":\"1.0.0\"," + baseSchema() + ","
                + "\"effects\":[{\"id\":\"e\",\"executor\":\"server\",\"trigger\":\"true\","
                + "\"target\":{\"ref\":\"a/b\",\"path\":\"$.p\",\"read\":\"$.r\"}}]}";
        assertThat(validate(json).isValid()).isFalse();
    }

    @Test
    void rejectsInvalidTargetCoordinate() throws Exception {
        String json = "{\"id\":\"a\",\"version\":\"1.0.0\"," + baseSchema() + ","
                + "\"effects\":[{\"id\":\"e\",\"executor\":\"server\",\"trigger\":\"true\","
                + "\"target\":{\"ref\":\"1bad//coord\",\"path\":\"$.p\"}}]}";
        assertThat(validate(json).isValid()).isFalse();
    }

    @Test
    void readLinkWithBodyIsRejected() throws Exception {
        String json = "{\"id\":\"a\",\"version\":\"1.0.0\"," + baseSchema() + ","
                + "\"effects\":[{\"id\":\"e\",\"executor\":\"server\",\"trigger\":\"true\","
                + "\"target\":{\"ref\":\"a/b\",\"read\":\"$.r\"},\"body\":\"{ 'x': 1 }\"}]}";
        assertThat(validate(json).isValid()).isFalse();
    }

    @Test
    void templateAndLineageParseAndValidate() throws Exception {
        String json = "{\"id\":\"branch\",\"version\":\"1.0.0\"," + baseSchema() + ","
                + "\"template\":{\"ref\":\"acme/base@^1.0.0\"},"
                + "\"lineage\":[{\"ref\":\"acme/base\",\"version\":\"1.4.2\",\"digest\":\"sha256:x\","
                + "\"repo\":\"team\",\"owner\":\"acme\"}]}";
        ModelSpec spec = parse(json);
        assertThat(spec.template().ref()).isEqualTo("acme/base@^1.0.0");
        assertThat(spec.lineage()).hasSize(1);
        assertThat(spec.lineage().get(0).owner()).isEqualTo("acme");
        assertThat(validate(json).isValid()).isTrue();
    }

    @Test
    void cyclicLineageRejected() throws Exception {
        String json = "{\"id\":\"branch\",\"version\":\"1.0.0\"," + baseSchema() + ","
                + "\"lineage\":[{\"ref\":\"acme/base\",\"version\":\"1.0.0\"},"
                + "{\"ref\":\"acme/base\",\"version\":\"2.0.0\"}]}";
        assertThat(validate(json).isValid()).isFalse();
    }

    @Test
    void nonSemverVersionRejectedWithPointer() throws Exception {
        String json = "{\"id\":\"a\",\"version\":\"v1\"," + baseSchema() + "}";
        var result = validate(json);
        assertThat(result.isValid()).isFalse();
        assertThat(result.errors().stream().anyMatch(e -> e.location().equals("version"))).isTrue();
    }
}
