package org.json_kula.valem.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ResourceRegistryTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private ResourceRegistry resources;

    @BeforeEach
    void setUp() {
        resources = new ResourceRegistry(MAPPER);
    }

    @Test
    void lists_the_fixed_guide_and_schema_resources() {
        List<String> uris = new ArrayList<>();
        resources.listNode().forEach(r -> uris.add(r.path("uri").asText()));
        assertThat(uris).contains(
                "valem://guide/model-spec-format",
                "valem://schema/model-spec",
                "valem://schema/spec-evolution");
    }

    @Test
    void lists_the_bundled_example_specs() {
        // The examples are copied into the classpath by the build (maven-resources-plugin).
        assertThat(resources.exampleNames())
                .contains("insurance-quote", "car-loan-calculator", "order-items-price-total");

        List<String> uris = new ArrayList<>();
        resources.listNode().forEach(r -> uris.add(r.path("uri").asText()));
        assertThat(uris).contains("valem://examples/insurance-quote");
    }

    @Test
    void every_listed_resource_declares_name_and_mimeType() {
        resources.listNode().forEach(r -> {
            assertThat(r.path("uri").asText()).isNotBlank();
            assertThat(r.path("name").asText()).isNotBlank();
            assertThat(r.path("mimeType").asText()).isNotBlank();
        });
    }

    @Test
    void reads_the_authoring_guide_as_markdown() {
        JsonNode item = resources.read("valem://guide/model-spec-format").path("contents").get(0);
        assertThat(item.path("mimeType").asText()).isEqualTo("text/markdown");
        assertThat(item.path("text").asText()).contains("Valem");
    }

    @Test
    void reads_the_model_spec_schema_as_json() throws Exception {
        JsonNode item = resources.read("valem://schema/model-spec").path("contents").get(0);
        assertThat(item.path("mimeType").asText()).isEqualTo("application/json");
        JsonNode schema = MAPPER.readTree(item.path("text").asText());
        assertThat(schema.path("type").asText()).isEqualTo("object");
        assertThat(schema.path("properties").has("derivations")).isTrue();
    }

    @Test
    void reads_an_example_spec() throws Exception {
        JsonNode item = resources.read("valem://examples/insurance-quote").path("contents").get(0);
        assertThat(item.path("mimeType").asText()).isEqualTo("application/json");
        JsonNode spec = MAPPER.readTree(item.path("text").asText());
        assertThat(spec.path("id").asText()).isNotBlank();
    }

    @Test
    void read_of_unknown_resource_returns_null() {
        assertThat(resources.read("valem://nope/nothing")).isNull();
        assertThat(resources.read("valem://examples/does-not-exist")).isNull();
    }

    @Test
    void read_rejects_example_path_traversal() {
        assertThat(resources.read("valem://examples/../McpServer")).isNull();
        assertThat(resources.read("valem://examples/sub/dir")).isNull();
    }
}
