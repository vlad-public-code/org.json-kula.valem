package org.json_kula.valem.api.composition;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.json_kula.valem.api.reference.LocalModelRepository;
import org.json_kula.valem.api.reference.ModelResolver;
import org.json_kula.valem.api.reference.TemplateMaterializer;
import org.json_kula.valem.core.blob.InMemoryBlobStore;
import org.json_kula.valem.core.model.ModelSpec;
import org.json_kula.valem.service.ModelRegistry;
import org.json_kula.valem.service.ModelService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class CompositionGraphServiceTest {

    private final ObjectMapper mapper = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    private ModelService service;
    private CompositionGraphService graph;
    private TemplateMaterializer materializer;

    @BeforeEach
    void setUp() {
        service = new ModelService(new ModelRegistry(), new InMemoryBlobStore());
        graph = new CompositionGraphService(service);
        materializer = new TemplateMaterializer(
                new ModelResolver(List.of(new LocalModelRepository(service))));
    }

    private ModelSpec spec(String json) throws Exception {
        return mapper.readValue(json, ModelSpec.class);
    }

    @Test
    void reportsNodesLinkEdgesAndLineageEdges() throws Exception {
        service.createModel(spec("{\"id\":\"agg\",\"version\":\"1.0.0\",\"schema\":{}}"));
        service.createModel(spec("""
            {
              "id": "leaf", "version": "1.0.0", "schema": {},
              "effects": [ {
                "id": "push", "executor": "server", "trigger": "true", "dedupeKey": "n",
                "target": { "ref": "agg", "path": "$.in" }, "body": "n", "statusPath": "$.io.push"
              } ]
            }
            """));
        service.createModel(spec("{\"id\":\"base\",\"version\":\"1.0.0\",\"schema\":{}}"));
        service.createModel(materializer.materialize(spec(
                "{\"id\":\"child\",\"version\":\"1.0.0\",\"schema\":{},\"template\":{\"ref\":\"base\"}}")));

        CompositionGraph g = graph.build();

        assertThat(g.nodes()).extracting("ref")
                .containsExactlyInAnyOrder("agg", "leaf", "base", "child");

        assertThat(g.linkEdges()).singleElement().satisfies(e -> {
            assertThat(e.from()).isEqualTo("leaf");
            assertThat(e.to()).isEqualTo("agg");
            assertThat(e.effectId()).isEqualTo("push");
            assertThat(e.kind()).isEqualTo("write");
            assertThat(e.last()).isNull();   // never fired
        });

        assertThat(g.lineageEdges()).singleElement().satisfies(e -> {
            assertThat(e.branch()).isEqualTo("child");
            assertThat(e.template()).isEqualTo("base");
        });
    }
}
