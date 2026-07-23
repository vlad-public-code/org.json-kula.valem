package org.json_kula.valem.core.graph;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.json_kula.valem.core.engine.TestCaseRunner;
import org.json_kula.valem.core.model.ModelSpec;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Guards every bundled example spec.
 *
 * <p>The examples are not decoration: {@code valem-ui}'s CreatePanel globs this directory and
 * {@code valem-mcp} copies it into its jar as the {@code valem://examples/{name}} resources, so a
 * broken one is what an agent reads as the reference for how a spec is written. They are also the
 * only place the component vocabulary is exercised in a whole, real spec rather than a fixture —
 * which makes this the test that catches a component type wired into the evaluator but missing
 * from {@code ViewComponentTypes}, or a view whose ids collide.
 */
class BundledExamplesTest {

    /**
     * Lenient, like every real reader of a spec. {@code EffectSpec} has no {@code description}
     * field but several bundled examples carry one as documentation, and the service parses with
     * {@code FAIL_ON_UNKNOWN_PROPERTIES} disabled — a strict mapper here would reject specs the
     * product itself accepts.
     */
    private static final ObjectMapper MAPPER = new ObjectMapper()
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);

    static Stream<File> examples() {
        Path dir = resolveExamplesDir();
        try (var files = Files.list(dir)) {
            List<File> found = files
                    .filter(p -> p.toString().endsWith(".json"))
                    .map(Path::toFile)
                    .sorted(Comparator.comparing(File::getName))
                    .toList();
            assertThat(found).as("bundled examples in %s", dir).isNotEmpty();
            return found.stream();
        } catch (Exception e) {
            throw new IllegalStateException("could not list examples in " + dir, e);
        }
    }

    /** Every example parses and validates — schema, expressions, effects, and the view tree. */
    @ParameterizedTest(name = "{0}")
    @MethodSource("examples")
    void example_is_structurally_valid(File specFile) throws Exception {
        ModelSpec spec = MAPPER.treeToValue(strip(MAPPER.readTree(specFile)), ModelSpec.class);

        ModelSpecValidator.ValidationResult validation = ModelSpecValidator.validate(spec);
        assertThat(validation.isValid())
                .withFailMessage(() -> specFile.getName() + " validation errors: " + validation.errors())
                .isTrue();
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("examples")
    void example_embedded_self_tests_pass(File specFile) throws Exception {
        ModelSpec spec = MAPPER.treeToValue(strip(MAPPER.readTree(specFile)), ModelSpec.class);

        for (TestCaseRunner.TestResult r : TestCaseRunner.run(spec, spec.tests())) {
            assertThat(r.passed())
                    .withFailMessage(() -> specFile.getName() + " — " + r.description()
                            + " failed: " + r.failures())
                    .isTrue();
        }
    }

    /**
     * Bundled examples carry documentation-only {@code _name}/{@code _description} fields that
     * every real client strips before POSTing; {@code ModelSpec} has no such fields.
     */
    private static ObjectNode strip(JsonNode raw) {
        ObjectNode clean = raw.deepCopy();
        clean.remove("_name");
        clean.remove("_description");
        return clean;
    }

    static Path resolveExamplesDir() {
        // Surefire's working directory is the module basedir, but the whole-reactor and
        // single-module invocations differ in how deep that is.
        for (String candidate : new String[]{
                "../valem-ui/src/examples",
                "valem-ui/src/examples",
        }) {
            Path p = Path.of(candidate);
            if (Files.isDirectory(p)) return p;
        }
        throw new IllegalStateException(
                "valem-ui/src/examples not found relative to " + Path.of("").toAbsolutePath());
    }
}
