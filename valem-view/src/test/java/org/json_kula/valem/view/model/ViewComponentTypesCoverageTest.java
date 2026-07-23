package org.json_kula.valem.view.model;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.json_kula.valem.core.model.ViewComponentTypes;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Pins the three places a component {@code type} is enumerated to each other.
 *
 * <p>The vocabulary is written down once per language boundary and there is no shared source:
 * {@code ViewComponentTypes} in core gates it at write time, {@code ComponentSpec}'s
 * {@code @JsonSubTypes} binds it to a record, and {@code KNOWN_COMPONENT_TYPES} in the React
 * library dispatches it. Each drifts independently and each drifts silently — a type missing from
 * core is rejected at create time even though the evaluator handles it; a type missing from
 * {@code @JsonSubTypes} passes validation and then binds to {@link UnknownComponentSpec}, losing
 * its typed fields; a type missing from the TypeScript list validates, evaluates, and renders as
 * an orange "Unknown component type" box. This test is the thing that makes adding a type a
 * three-file change by failing until it is.
 */
class ViewComponentTypesCoverageTest {

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);

    /** Where the TypeScript mirror lives, relative to this module. */
    private static final Path TS_TYPES = Path.of("..", "valem-view-react", "src", "types.ts");

    @Test
    void every_core_vocabulary_type_binds_to_a_typed_record() {
        Set<String> declared = subTypeNames();
        assertThat(declared)
                .as("@JsonSubTypes on ComponentSpec vs ViewComponentTypes.ALL")
                .containsExactlyInAnyOrderElementsOf(ViewComponentTypes.ALL);
    }

    @Test
    void no_known_type_falls_through_to_the_unknown_record() throws Exception {
        for (String type : ViewComponentTypes.ALL) {
            ComponentSpec parsed = MAPPER.readValue(
                    "{ \"id\": \"c\", \"type\": \"" + type + "\" }", ComponentSpec.class);
            assertThat(parsed)
                    .as("type '%s' should bind to a typed record, not UnknownComponentSpec", type)
                    .isNotInstanceOf(UnknownComponentSpec.class);
            assertThat(parsed.type())
                    .as("type '%s' must survive onto the record so grouped types stay distinct", type)
                    .isEqualTo(type);
        }
    }

    @Test
    void the_typescript_mirror_lists_exactly_the_same_types() throws Exception {
        // Skipped when valem-view is built outside the repo (no sibling npm package to read).
        assumeTrue(Files.exists(TS_TYPES), "valem-view-react/src/types.ts not present");

        Set<String> fromTs = knownComponentTypesFromTypeScript(Files.readString(TS_TYPES));
        assertThat(fromTs)
                .as("KNOWN_COMPONENT_TYPES in valem-view-react vs ViewComponentTypes.ALL")
                .containsExactlyInAnyOrderElementsOf(ViewComponentTypes.ALL);
    }

    @Test
    void the_vocabulary_is_partitioned_not_overlapping() {
        // A type in two buckets would be counted twice and, worse, means two catalog rows
        // disagreeing about what it is.
        int summed = ViewComponentTypes.FIELDS.size() + ViewComponentTypes.OUTPUT.size()
                + ViewComponentTypes.AGGREGATES.size() + ViewComponentTypes.ACTIONS.size();
        assertThat(ViewComponentTypes.ALL).hasSize(summed);
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    /** Both spellings Jackson accepts: {@code name = "x"} and {@code names = {...}}. */
    private static Set<String> subTypeNames() {
        JsonSubTypes ann = ComponentSpec.class.getAnnotation(JsonSubTypes.class);
        assertThat(ann).as("ComponentSpec must declare @JsonSubTypes").isNotNull();
        return Arrays.stream(ann.value())
                .flatMap(t -> Stream.concat(Stream.of(t.name()), Arrays.stream(t.names())))
                .filter(s -> !s.isBlank())
                .collect(Collectors.toCollection(TreeSet::new));
    }

    private static Set<String> knownComponentTypesFromTypeScript(String source) {
        Matcher block = Pattern
                .compile("KNOWN_COMPONENT_TYPES\\s*=\\s*\\[(.*?)]\\s*as const", Pattern.DOTALL)
                .matcher(source);
        assertThat(block.find()).as("KNOWN_COMPONENT_TYPES array not found in types.ts").isTrue();

        Set<String> names = new LinkedHashSet<>();
        Matcher entry = Pattern.compile("'([^']+)'").matcher(block.group(1));
        while (entry.find()) names.add(entry.group(1));
        return names;
    }
}
