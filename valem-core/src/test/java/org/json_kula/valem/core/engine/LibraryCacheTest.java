package org.json_kula.valem.core.engine;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.json_kula.valem.core.model.LibraryLayer;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The content-addressed library cache.
 *
 * <p>Kept deliberately small in distinct definitions: each one is a javac invocation and a generated
 * class, and a fork that accumulates thousands of them leaks (see the JSonata2Java javac threshold).
 */
class LibraryCacheTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final JsonNodeFactory F = JsonNodeFactory.instance;

    private static final String DOUBLE = "( $double := function($n){ $n * 2 }; [\"double\"] )";
    private static final String MAX_RATE = "( $maxRate := $max($const.brackets.rate); [\"maxRate\"] )";

    @Test
    void the_same_definition_and_constants_compile_once() {
        ObjectNode constants = F.objectNode();
        LibraryCache.compile(layers(DOUBLE), constants);        // prime

        int before = LibraryCache.compileCount();
        LibraryCache.Compiled again = LibraryCache.compile(layers(DOUBLE), constants);

        assertThat(LibraryCache.compileCount()).as("cache hit performs no javac work").isEqualTo(before);
        assertThat(again.functions()).containsKey("double");
    }

    @Test
    void differing_constants_produce_different_exported_values() throws Exception {
        // The reason constants are part of the cache key: an exported *value* is evaluated once, at
        // definition time, so two models sharing a definition must not share one another's frozen value.
        ObjectNode low  = (ObjectNode) MAPPER.readTree("{\"brackets\":[{\"rate\":0.1},{\"rate\":0.4}]}");
        ObjectNode high = (ObjectNode) MAPPER.readTree("{\"brackets\":[{\"rate\":0.1},{\"rate\":0.9}]}");

        assertThat(LibraryCache.compile(layers(MAX_RATE), low).constants().get("maxRate").asDouble())
                .isEqualTo(0.4);
        assertThat(LibraryCache.compile(layers(MAX_RATE), high).constants().get("maxRate").asDouble())
                .isEqualTo(0.9);
    }

    @Test
    void constants_key_is_order_insensitive() throws Exception {
        ObjectNode a = (ObjectNode) MAPPER.readTree("{\"x\":1,\"y\":2}");
        ObjectNode b = (ObjectNode) MAPPER.readTree("{\"y\":2,\"x\":1}");
        LibraryCache.compile(layers(DOUBLE), a);

        int before = LibraryCache.compileCount();
        LibraryCache.compile(layers(DOUBLE), b);

        assertThat(LibraryCache.compileCount())
                .as("same constants in a different key order is the same library").isEqualTo(before);
    }

    @Test
    void later_layers_win_a_name_collision_and_may_call_earlier_ones() {
        LibraryCache.Compiled compiled = LibraryCache.compile(List.of(
                LibraryLayer.own("( $base := function($n){ $n * 2 }; $keep := function($n){ $n + 1 }; [\"base\", \"keep\"] )", null),
                LibraryLayer.own("( $base := function($n){ $n * 10 }; [\"base\"] )", null)),
                F.objectNode());

        assertThat(compiled.names()).contains("base", "keep");
        assertThat(compiled.functions()).containsKeys("base", "keep");
        // The override is what is bound; proving which one runs is LibraryTest's job (it needs a runtime).
        assertThat(compiled.layers()).hasSize(2);
    }

    @Test
    void a_later_layer_can_call_an_export_of_an_earlier_layer() {
        LibraryCache.Compiled compiled = LibraryCache.compile(List.of(
                LibraryLayer.own("( $half := function($n){ $n / 2 }; [\"half\"] )", null),
                // Self-containment would reject $half if the earlier layer's exports were not bound
                // while this layer is defined.
                LibraryLayer.own("( $quarter := function($n){ $half($half($n)) }; [\"quarter\"] )", null)),
                F.objectNode());

        assertThat(compiled.names()).contains("half", "quarter");
    }

    @Test
    void an_exported_value_may_be_computed_from_an_earlier_layers_function() {
        LibraryCache.Compiled compiled = LibraryCache.compile(List.of(
                LibraryLayer.own("( $twice := function($n){ $n * 2 }; [\"twice\"] )", null),
                LibraryLayer.own("( $six := $twice(3); [\"six\"] )", null)),
                F.objectNode());

        assertThat(compiled.constants().get("six").asInt()).isEqualTo(6);
    }

    @Test
    void a_signature_override_coerces_at_the_call_boundary() {
        LibraryCache.Compiled coerced = LibraryCache.compile(
                List.of(LibraryLayer.own(DOUBLE, java.util.Map.of("double", "<n:n>"))), F.objectNode());

        assertThat(coerced.functions().get("double").getFunctionSignature()).isEqualTo("<n:n>");
    }

    @Test
    void a_definition_that_does_not_compile_reports_the_offending_layer() {
        LibraryLayer bad = LibraryLayer.own("( $f := function($x { $x }; [\"f\"] )", null);

        assertThatThrownBy(() -> LibraryCache.compile(List.of(bad), F.objectNode()))
                .isInstanceOf(LibraryCache.LibraryCompilationException.class)
                .satisfies(e -> assertThat(
                        ((LibraryCache.LibraryCompilationException) e).layer()).isSameAs(bad));
    }

    @Test
    void an_empty_layer_list_yields_the_empty_library() {
        assertThat(LibraryCache.compile(List.of(), F.objectNode()).isEmpty()).isTrue();
        assertThat(LibraryCache.compile(null, F.objectNode()).names()).isEmpty();
    }

    @Test
    void concurrent_compilation_of_one_definition_yields_one_shared_instance() throws Exception {
        // A definition no other test uses, so the first call really is a miss.
        String define = "( $conc := function($n){ $n + 41 }; [\"conc\"] )";
        ObjectNode constants = F.objectNode();

        ExecutorService pool = Executors.newFixedThreadPool(8);
        try {
            List<Callable<LibraryCache.Compiled>> tasks = IntStream.range(0, 8)
                    .<Callable<LibraryCache.Compiled>>mapToObj(
                            i -> () -> LibraryCache.compile(layers(define), constants))
                    .toList();
            List<Future<LibraryCache.Compiled>> results = pool.invokeAll(tasks);

            Object first = results.getFirst().get().layers().getFirst();
            for (Future<LibraryCache.Compiled> r : results) {
                assertThat(r.get().layers().getFirst())
                        .as("every caller shares one compiled library").isSameAs(first);
            }
        } finally {
            pool.shutdownNow();
        }
    }

    private static List<LibraryLayer> layers(String define) {
        return List.of(LibraryLayer.own(define, null));
    }
}
