package org.json_kula.valem.core.graph;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.json_kula.valem.core.model.LibraryLayer;
import org.json_kula.valem.core.model.LibrarySpec;
import org.json_kula.valem.core.model.ModelSpec;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Evolving a model's {@code library} — its own layer only, with a drop guard. */
class SpecEvolutionLibraryTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final String BASE = """
        { "id": "m", "schema": {},
          "library": "( $double := function($n){ $n * 2 }; [\\"double\\"] )",
          "derivations": [ { "path": "$.d", "expr": "$double(base)" } ] }
        """;

    @Test
    void an_absent_newLibrary_keeps_the_existing_one() throws Exception {
        ModelSpec evolved = evolve(BASE, "{ \"newVersion\": \"1.1.0\" }");

        assertThat(evolved.library()).isNotNull();
        assertThat(evolved.library().ownLayer().define()).contains("$double");
    }

    @Test
    void newLibrary_replaces_the_own_layer() throws Exception {
        ModelSpec evolved = evolve(BASE, """
            { "newLibrary": { "define": "( $double := function($n){ $n * 3 }; [\\"double\\"] )" } }
            """);

        assertThat(evolved.library().ownLayer().define()).contains("$n * 3");
        assertThat(evolved.library().layers()).hasSize(1);
    }

    @Test
    void newLibrary_accepts_the_bare_string_shorthand() throws Exception {
        ModelSpec evolved = evolve(BASE,
                "{ \"newLibrary\": \"( $double := function($n){ $n * 4 }; [\\\"double\\\"] )\" }");

        assertThat(evolved.library().ownLayer().define()).contains("$n * 4");
    }

    @Test
    void a_library_can_be_added_to_a_model_that_had_none() throws Exception {
        ModelSpec evolved = evolve("""
            { "id": "m", "schema": {},
              "derivations": [ { "path": "$.d", "expr": "base * 2" } ] }
            """, """
            { "newLibrary": { "define": "( $двa := function($n){ $n * 2 }; [\\"двa\\"] )" } }
            """.replace("двa", "twice"));

        assertThat(evolved.library().ownLayer().define()).contains("$twice");
    }

    @Test
    void dropping_an_export_that_is_still_called_is_rejected_by_name_and_location() {
        assertThatThrownBy(() -> evolve(BASE, """
            { "newLibrary": { "define": "( $other := function($n){ $n }; [\\"other\\"] )" } }
            """))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("newLibrary drops $double")
                .hasMessageContaining("derivations[0] ($.d)");
    }

    @Test
    void clearing_a_library_that_is_still_called_is_rejected() {
        assertThatThrownBy(() -> evolve(BASE, "{ \"newLibrary\": null }"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("$double");
    }

    @Test
    void clearing_is_allowed_once_the_calls_are_removed_in_the_same_evolution() throws Exception {
        ModelSpec evolved = evolve(BASE, """
            { "newLibrary": null,
              "upsertDerivations": [ { "path": "$.d", "expr": "base * 2" } ] }
            """);

        assertThat(evolved.library()).isNull();
        assertThat(evolved.derivations().getFirst().expr()).isEqualTo("base * 2");
    }

    @Test
    void an_evolution_that_leaves_the_library_untouched_recompiles_nothing() throws Exception {
        ModelSpec base = MAPPER.readValue(BASE, ModelSpec.class);
        ModelSpecCompiler.compile(base);                        // prime the shared cache

        int before = org.json_kula.valem.core.engine.LibraryCache.compileCount();
        ModelSpec evolved = evolve(BASE, "{ \"newVersion\": \"1.2.0\" }");
        ModelSpecCompiler.compile(evolved);

        assertThat(org.json_kula.valem.core.engine.LibraryCache.compileCount())
                .as("content-addressed: an unchanged definition is never recompiled").isEqualTo(before);
    }

    @Test
    void inherited_layers_are_carried_forward_and_only_the_own_layer_is_replaced() throws Exception {
        ModelSpec base = MAPPER.readValue(BASE, ModelSpec.class);
        LibraryLayer inherited = LibraryLayer.of(
                "( $shared := function($n){ $n + 1 }; [\"shared\"] )", null, null,
                "acme/shared", "1.0.0", "sha256:abc", "team", "globex");
        ModelSpec withInherited = base.withLibrary(new LibrarySpec(
                List.of(inherited, base.library().ownLayer()), List.of(), null));

        SpecEvolution evolution = MAPPER.readValue("""
            { "newLibrary": { "define": "( $double := function($n){ $n * 5 }; [\\"double\\"] )" } }
            """, SpecEvolution.class);
        ModelSpec evolved = evolution.applyTo(withInherited);

        assertThat(evolved.library().layers()).hasSize(2);
        assertThat(evolved.library().layers().getFirst().ref()).isEqualTo("acme/shared");
        assertThat(evolved.library().layers().getFirst().digest()).isEqualTo("sha256:abc");
        assertThat(evolved.library().ownLayer().define()).contains("$n * 5");
    }

    @Test
    void a_newLibrary_naming_only_inherited_layers_is_rejected() {
        // Jackson wraps the creator's rejection; the message is what the author needs to see.
        assertThatThrownBy(() -> evolve(BASE, """
            { "newLibrary": { "layers": [ { "define": "( $x := 1; [\\"x\\"] )", "ref": "a/b" } ] } }
            """))
                .hasMessageContaining("inherited layers are changed by re-materializing");
    }

    private ModelSpec evolve(String specJson, String evolutionJson) throws Exception {
        ModelSpec base = MAPPER.readValue(specJson, ModelSpec.class);
        SpecEvolution evolution = MAPPER.readValue(evolutionJson, SpecEvolution.class);
        return evolution.applyTo(base);
    }
}
