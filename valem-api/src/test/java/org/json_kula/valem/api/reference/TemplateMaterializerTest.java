package org.json_kula.valem.api.reference;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.IntNode;
import org.json_kula.valem.core.blob.InMemoryBlobStore;
import org.json_kula.valem.core.model.ModelSpec;
import org.json_kula.valem.service.ModelRegistry;
import org.json_kula.valem.service.ModelService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * M4 — a branch inherits a template's reactive logic, overrides part of it, materializes into a
 * self-contained inlined spec with pinned lineage, and no longer depends on the template.
 */
class TemplateMaterializerTest {

    private final ObjectMapper mapper = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    private ModelService service;
    private TemplateMaterializer materializer;

    @BeforeEach
    void setUp() {
        service = new ModelService(new ModelRegistry(), new InMemoryBlobStore());
        ModelResolver resolver = new ModelResolver(List.of(new LocalModelRepository(service)));
        materializer = new TemplateMaterializer(resolver);
    }

    private ModelSpec spec(String json) throws Exception {
        return mapper.readValue(json, ModelSpec.class);
    }

    private static final String TEMPLATE = """
            {
              "id": "base", "version": "1.0.0",
              "schema": {"type": "object", "properties": {"n": {"type": "number"}}},
              "derivations": [ {"path": "$.doubled", "expr": "n * 2"} ]
            }
            """;

    private static final String BRANCH = """
            {
              "id": "branch", "version": "2.0.0", "schema": {},
              "template": { "ref": "base" },
              "derivations": [
                {"path": "$.doubled", "expr": "n * 20"},
                {"path": "$.tripled", "expr": "n * 3"}
              ]
            }
            """;

    @Test
    void branchInheritsOverridesAndMaterializesSelfContained() throws Exception {
        service.createModel(spec(TEMPLATE));

        ModelSpec effective = materializer.materialize(spec(BRANCH));

        // Identity + inlining
        assertThat(effective.id()).isEqualTo("branch");
        assertThat(effective.version()).isEqualTo("2.0.0");
        assertThat(effective.template()).isNull();            // template resolved away
        assertThat(effective.schema()).isEqualTo(spec(TEMPLATE).schema());   // inherited via empty {}

        // Lineage pinned to the resolved ancestor
        assertThat(effective.lineage()).singleElement().satisfies(l -> {
            assertThat(l.ref()).isEqualTo("base");
            assertThat(l.version()).isEqualTo("1.0.0");
            assertThat(l.repo()).isEqualTo("local");
            assertThat(l.digest()).startsWith("sha256:");
        });

        // Merged derivations: inherited path overridden, new path added
        assertThat(effective.derivations()).extracting("path")
                .containsExactlyInAnyOrder("$.doubled", "$.tripled");

        // Runs, and is self-contained — deleting the template does not break the branch
        service.createModel(effective);
        service.deleteModel("base");
        service.mutate("branch", Map.of("$.n", IntNode.valueOf(5)));
        assertThat(service.getFieldValue("branch", "$.doubled").asInt()).isEqualTo(100);  // 20*5 override
        assertThat(service.getFieldValue("branch", "$.tripled").asInt()).isEqualTo(15);   // 3*5 inherited-add
    }

    @Test
    void unresolvedTemplateIsRejected() throws Exception {
        ModelSpec branch = spec("""
            {"id": "orphan", "version": "1.0.0", "schema": {}, "template": {"ref": "ghost"}}
            """);
        assertThatThrownBy(() -> materializer.materialize(branch))
                .isInstanceOf(ReferenceException.UnresolvedReference.class);
    }

    @Test
    void nonBranchSpecPassesThroughUnchanged() throws Exception {
        ModelSpec plain = spec(TEMPLATE);
        assertThat(materializer.materialize(plain)).isSameAs(plain);
    }
}
