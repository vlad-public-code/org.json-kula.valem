package org.json_kula.valem.core.graph;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.json_kula.valem.core.model.ModelSpec;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Projection of a {@link CompiledModel} into the {@link ModelGraph} lens the "Why is this number?"
 * surface consumes (docs/sandbox/why-this-number.md). Pins node kinds, expressions, edge direction,
 * levels, and the {@link GraphProjection#canonicalKey} contract.
 */
class GraphProjectionTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private ModelGraph project(String specJson) throws Exception {
        ModelSpec spec = MAPPER.readValue(specJson, ModelSpec.class);
        return GraphProjection.project(ModelSpecCompiler.compile(spec), "m1");
    }

    private static Function<String, ModelGraph.Node> byKey(ModelGraph g) {
        Map<String, ModelGraph.Node> index =
                g.nodes().stream().collect(java.util.stream.Collectors.toMap(ModelGraph.Node::key, n -> n));
        return index::get;
    }

    @Test
    void projectsBaseDerivedConstraintAndEffectNodesWithKindsAndExpressions() throws Exception {
        ModelGraph g = project("""
            { "id": "m", "schema": {},
              "derivations": [
                { "path": "$.total", "expr": "subtotal + tax" }
              ],
              "metaDerivations": [
                { "path": "$.tax", "property": "minimum", "expr": "0" }
              ],
              "constraints": [
                { "id": "cap", "expr": "total <= 100", "message": "Total over cap" }
              ],
              "effects": [
                { "id": "notify", "trigger": "total > 50" }
              ] }
            """);

        assertThat(g.modelId()).isEqualTo("m1");
        Function<String, ModelGraph.Node> node = byKey(g);

        // Derived node carries its expression.
        ModelGraph.Node total = node.apply("$.total");
        assertThat(total).isNotNull();
        assertThat(total.kind()).isEqualTo("DERIVED");
        assertThat(total.expression()).isEqualTo("subtotal + tax");
        assertThat(total.label()).isEqualTo("total");

        // Base inputs are BASE with no expression.
        assertThat(node.apply("$.subtotal").kind()).isEqualTo("BASE");
        assertThat(node.apply("$.subtotal").expression()).isNull();

        // Meta node keeps its #property key and expression.
        ModelGraph.Node metaMin = node.apply("$.tax#minimum");
        assertThat(metaMin).isNotNull();
        assertThat(metaMin.kind()).isEqualTo("META");
        assertThat(metaMin.expression()).isEqualTo("0");

        // Constraint split out of META by prefix; message used as label.
        ModelGraph.Node cap = node.apply("$constraint:cap");
        assertThat(cap).isNotNull();
        assertThat(cap.kind()).isEqualTo("CONSTRAINT");
        assertThat(cap.label()).isEqualTo("Total over cap");
        assertThat(cap.expression()).isEqualTo("total <= 100");

        // Effect split out of META by prefix; trigger is the expression.
        ModelGraph.Node notify = node.apply("$effect:notify");
        assertThat(notify).isNotNull();
        assertThat(notify.kind()).isEqualTo("EFFECT");
        assertThat(notify.expression()).isEqualTo("total > 50");
    }

    @Test
    void edgesRunFromDependencyToDependent() throws Exception {
        ModelGraph g = project("""
            { "id": "m", "schema": {},
              "derivations": [ { "path": "$.total", "expr": "subtotal + tax" } ] }
            """);

        // subtotal and tax feed total.
        assertThat(g.edges()).contains(
                new ModelGraph.Edge("$.subtotal", "$.total"),
                new ModelGraph.Edge("$.tax", "$.total"));
        // No edge in the wrong direction.
        assertThat(g.edges()).noneMatch(e -> e.from().equals("$.total"));
    }

    @Test
    void levelsPlaceInputsBeforeDerived() throws Exception {
        ModelGraph g = project("""
            { "id": "m", "schema": {},
              "derivations": [
                { "path": "$.total", "expr": "subtotal + tax" },
                { "path": "$.grand", "expr": "total * 2" }
              ] }
            """);

        // A chained derivation lands in a deeper level than its inputs.
        int totalLevel = levelOf(g, "$.total");
        int grandLevel = levelOf(g, "$.grand");
        int subtotalLevel = levelOf(g, "$.subtotal");
        assertThat(subtotalLevel).isLessThan(totalLevel);
        assertThat(totalLevel).isLessThan(grandLevel);
    }

    private static int levelOf(ModelGraph g, String key) {
        List<List<String>> levels = g.levels();
        for (int i = 0; i < levels.size(); i++) if (levels.get(i).contains(key)) return i;
        throw new AssertionError("key not in any level: " + key);
    }

    @Test
    void canonicalKeyNormalizesClientPaths() {
        assertThat(GraphProjection.canonicalKey("order.total")).isEqualTo("$.order.total");
        assertThat(GraphProjection.canonicalKey(".total")).isEqualTo("$.total");
        assertThat(GraphProjection.canonicalKey("$.total")).isEqualTo("$.total");     // already canonical
        assertThat(GraphProjection.canonicalKey("$constraint:cap")).isEqualTo("$constraint:cap");
        assertThat(GraphProjection.canonicalKey("$effect:notify")).isEqualTo("$effect:notify");
    }
}
