package org.json_kula.valem.core.graph;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.json_kula.valem.core.engine.TestCaseRunner;
import org.json_kula.valem.core.model.ModelSpec;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
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
     * Every view {@code bind} addresses a property the schema actually declares.
     *
     * <p>This is the gap between "the model computes" and "the page works". A bind left pointing at
     * a renamed or deleted field passes {@link ModelSpecValidator} and passes every embedded
     * self-test — the arithmetic is untouched — and then renders as a dead control that silently
     * shows nothing. Nothing else in the suite reads the {@code viewDefinition} at all.
     */
    @ParameterizedTest(name = "{0}")
    @MethodSource("examples")
    void view_bindings_resolve_to_schema_properties(File specFile) throws Exception {
        JsonNode spec = strip(MAPPER.readTree(specFile));
        JsonNode schema = spec.path("schema");
        // An effect's status machine (pending/in_flight/applied/failed) is written at runtime under
        // its statusPath and is deliberately not declared in the schema, so an effectStatus bound
        // beneath one resolves by definition.
        List<String> statusPaths = new ArrayList<>();
        for (JsonNode effect : spec.path("effects")) {
            String sp = effect.path("statusPath").asText(null);
            if (sp != null) statusPaths.add(sp);
        }
        for (JsonNode view : spec.path("viewDefinition").path("views")) {
            assertBindingsResolve(specFile, schema, statusPaths, view.path("components"));
        }
    }

    private static void assertBindingsResolve(File specFile, JsonNode schema, List<String> statusPaths,
                                              JsonNode components) {
        for (JsonNode c : components) {
            assertBindingsResolve(specFile, schema, statusPaths, c.path("components"));
            checkBind(specFile, schema, statusPaths, c.path("bind").asText(null),
                    "component '" + c.path("id").asText() + "'");
            // keyValueList / summaryList rows carry their own binds.
            for (JsonNode item : c.path("items")) {
                checkBind(specFile, schema, statusPaths, item.path("bind").asText(null),
                        "item '" + item.path("label").asText() + "'");
            }
        }
    }

    private static void checkBind(File specFile, JsonNode schema, List<String> statusPaths,
                                  String bind, String what) {
        if (bind == null || !bind.startsWith("$.")) return;
        if (statusPaths.stream().anyMatch(sp -> bind.equals(sp) || bind.startsWith(sp + "."))) return;
        assertThat(resolves(schema, bind))
                .withFailMessage(() -> specFile.getName() + " — " + what + " binds " + bind
                        + ", which no schema property declares")
                .isTrue();
    }

    /**
     * Walks a canonical bind address ({@code $.quote.applicant.age}, {@code $.items[*].qty}) through
     * the JSON Schema: a name segment steps into {@code properties}, an index or wildcard segment
     * steps into {@code items}. Binding to the whole document ({@code $}) always resolves.
     */
    private static boolean resolves(JsonNode schema, String bind) {
        JsonNode node = schema;
        Matcher m = SEGMENT.matcher(bind.substring(2));
        int end = 0;
        while (m.find()) {
            if (m.start() != end) return false;   // unparseable address
            end = m.end();
            String name = m.group(1);
            if (name != null) {
                node = node.path("properties").path(name);
            } else {
                node = node.path("items");        // "[0]" or "[*]"
            }
            if (node.isMissingNode() || node.isNull()) return false;
        }
        return end == bind.length() - 2;
    }

    /** One address segment: a property name, or a bracketed index/wildcard. */
    private static final Pattern SEGMENT = Pattern.compile("\\.?([A-Za-z_$][A-Za-z0-9_$]*)|\\[(\\d+|\\*)]");

    /**
     * There is exactly one tracked copy of the example specs.
     *
     * <p>There used to be three — this directory, {@code valem-ui/public/examples} (served over HTTP)
     * and {@code valem-console/src/test/resources/examples} (the console integration test's fixtures)
     * — and they drifted: one served spec and five test fixtures disagreed with the originals, and
     * nothing failed, because no test read more than one copy. The other two are now build-time
     * copies (a vite plugin and a maven-resources execution). This test is what stops a third
     * reappearing: duplicating a spec is easy, noticing that the duplicate went stale is not.
     */
    @Test
    void example_specs_have_exactly_one_tracked_copy() throws Exception {
        Path canonical = resolveExamplesDir().toAbsolutePath().normalize();
        Path repoRoot = canonical.getParent().getParent().getParent();

        List<Path> duplicates;
        try (var paths = Files.walk(repoRoot)) {
            duplicates = paths
                    .filter(Files::isDirectory)
                    .filter(p -> p.getFileName().toString().equals("examples"))
                    .map(p -> p.toAbsolutePath().normalize())
                    .filter(p -> !p.equals(canonical))
                    .filter(BundledExamplesTest::isTracked)
                    .filter(BundledExamplesTest::holdsSpecs)
                    .toList();
        }

        assertThat(duplicates)
                .withFailMessage(() -> "example specs must live only in " + canonical
                        + ", but a second tracked copy exists at " + duplicates
                        + ". Copy them at build time instead (see valem-ui/build/copyExamples.mjs "
                        + "and valem-console/pom.xml).")
                .isEmpty();
    }

    /** Build output and vendored trees are copies by design, not tracked duplicates. */
    private static boolean isTracked(Path dir) {
        for (Path segment : dir) {
            String s = segment.toString();
            if (s.equals("target") || s.equals("dist") || s.equals("build")
                    || s.equals("node_modules") || s.equals(".git") || s.equals(".claude")) {
                return false;
            }
        }
        return true;
    }

    private static boolean holdsSpecs(Path dir) {
        try (var files = Files.list(dir)) {
            return files.anyMatch(p -> p.toString().endsWith(".json"));
        } catch (Exception e) {
            return false;
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
