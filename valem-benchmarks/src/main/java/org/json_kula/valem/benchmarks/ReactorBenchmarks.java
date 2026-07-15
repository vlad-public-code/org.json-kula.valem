package org.json_kula.valem.benchmarks;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import org.json_kula.valem.core.blob.InMemoryBlobStore;
import org.json_kula.valem.core.graph.CompiledModel;
import org.json_kula.valem.core.graph.DependencyGraph;
import org.json_kula.valem.core.graph.ModelSpecCompiler;
import org.json_kula.valem.core.model.ModelSpec;
import org.json_kula.valem.core.state.DirtyPropagator;
import org.json_kula.valem.core.state.ModelState;
import org.json_kula.valem.core.state.PathConverter;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Warmup;
import org.openjdk.jmh.infra.Blackhole;

import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/**
 * JMH micro-benchmarks for the reactor hot paths flagged in the CPU audit (T0.2). These give a
 * baseline to validate CPU-1 ({@code toSegments} parsing), CPU-2 ({@code DirtyPropagator.propagate})
 * and CPU-3 ({@code mergedDocument} deep copy) against.
 *
 * <p>Run: {@code mvn -Pbenchmarks install} then {@code java -jar valem-benchmarks/target/benchmarks.jar}.
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Warmup(iterations = 5, time = 1)
@Measurement(iterations = 10, time = 1)
@Fork(2)
@State(Scope.Thread)
public class ReactorBenchmarks {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private String samplePath;
    private DependencyGraph graph;
    private Set<String> mutatedPaths;
    private ModelState smallState;
    private ModelState largeState;

    @Setup
    @SuppressWarnings("deprecation") // core InMemoryBlobStore — throwaway, never persisted
    public void setup() throws Exception {
        samplePath = "$.order.items[7].components[3].lineTotal";

        // ── Dependency graph: ~200 concrete derivations + 20 wildcard derivations ──
        DependencyGraph.Builder b = DependencyGraph.builder();
        for (int i = 0; i < 200; i++) {
            b.addEdge("$.f" + i, "$.d" + i);              // d_i depends on f_i
        }
        for (int i = 0; i < 20; i++) {
            b.addEdge("$.items[*].p" + i, "$.wsum" + i);  // wildcard input
        }
        graph = b.build();
        mutatedPaths = Set.of("$.f42", "$.items[3].p5");

        // ── Model state at two sizes for mergedDocument() ──
        ModelSpec spec = MAPPER.readValue(
                "{\"id\":\"bench\",\"schema\":{},\"derivations\":[]}", ModelSpec.class);
        CompiledModel model = ModelSpecCompiler.compile(spec);

        smallState = new ModelState(model, new InMemoryBlobStore());
        populate(smallState, 20, 3);

        largeState = new ModelState(model, new InMemoryBlobStore());
        populate(largeState, 500, 5);
    }

    private static void populate(ModelState state, int fields, int arrayItems) {
        for (int i = 0; i < fields; i++) {
            state.setValue("$.field" + i, JsonNodeFactory.instance.numberNode(i));
        }
        for (int i = 0; i < arrayItems; i++) {
            state.setValue("$.items[" + i + "].qty", JsonNodeFactory.instance.numberNode(i));
            state.setValue("$.items[" + i + "].price", JsonNodeFactory.instance.numberNode(i * 2));
            state.setDerived("$.items[" + i + "].lineTotal",
                    JsonNodeFactory.instance.numberNode(i * i));
        }
    }

    @Benchmark
    public List<String> toSegments() {
        return PathConverter.toSegments(samplePath);
    }

    @Benchmark
    public Set<String> propagate() {
        return DirtyPropagator.propagate(graph, mutatedPaths);
    }

    @Benchmark
    public void mergedDocumentSmall(Blackhole bh) {
        bh.consume(smallState.mergedDocument());
    }

    @Benchmark
    public void mergedDocumentLarge(Blackhole bh) {
        bh.consume(largeState.mergedDocument());
    }
}
