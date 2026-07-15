package org.json_kula.valem.core.state;

import com.fasterxml.jackson.core.JsonPointer;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class PathConverterTest {

    // ── toJsonPointer ─────────────────────────────────────────────────────────

    @Test
    void simple_path_to_pointer() {
        assertThat(PathConverter.toJsonPointer("$.order.total").toString())
                .isEqualTo("/order/total");
    }

    @Test
    void deep_path_to_pointer() {
        assertThat(PathConverter.toJsonPointer("$.order.customer.name").toString())
                .isEqualTo("/order/customer/name");
    }

    @Test
    void bracket_index_to_pointer() {
        assertThat(PathConverter.toJsonPointer("$.order.items[0].qty").toString())
                .isEqualTo("/order/items/0/qty");
    }

    @Test
    void root_only_returns_empty_pointer() {
        assertThat(PathConverter.toJsonPointer("$")).isEqualTo(JsonPointer.empty());
        assertThat(PathConverter.toJsonPointer(null)).isEqualTo(JsonPointer.empty());
    }

    // ── toSegments ────────────────────────────────────────────────────────────

    @Test
    void segments_simple() {
        assertThat(PathConverter.toSegments("$.a.b.c")).isEqualTo(List.of("a", "b", "c"));
    }

    @Test
    void segments_wildcard() {
        assertThat(PathConverter.toSegments("$.order.items[*].qty"))
                .isEqualTo(List.of("order", "items", "[*]", "qty"));
    }

    @Test
    void segments_bracket_index() {
        assertThat(PathConverter.toSegments("$.order.items[0].qty"))
                .isEqualTo(List.of("order", "items", "0", "qty"));
    }

    @Test
    void segments_consecutive_indices() {
        assertThat(PathConverter.toSegments("$.grid[2][3]"))
                .isEqualTo(List.of("grid", "2", "3"));
    }

    @Test
    void segments_index_followed_by_identifier_preserves_old_semantics() {
        // A malformed non-canonical key where a bracket index is glued to trailing identifier chars:
        // the single-pass scanner must reproduce the old replaceAll/split result ([0] → ".0", then the
        // trailing "b" continues that segment → "0b"), not split it into a separate segment.
        assertThat(PathConverter.toSegments("$.a[0]b")).isEqualTo(List.of("a", "0b"));
        assertThat(PathConverter.toSegments("$.a[*]b")).isEqualTo(List.of("a", "[*]b"));
    }

    // ── fromJsonPointer ───────────────────────────────────────────────────────

    @Test
    void pointer_to_jsonpath_simple() {
        assertThat(PathConverter.fromJsonPointer(JsonPointer.compile("/order/total")))
                .isEqualTo("$.order.total");
    }

    @Test
    void pointer_to_jsonpath_with_index() {
        assertThat(PathConverter.fromJsonPointer(JsonPointer.compile("/order/items/0/qty")))
                .isEqualTo("$.order.items[0].qty");
    }

    @Test
    void pointer_to_jsonpath_empty() {
        assertThat(PathConverter.fromJsonPointer(JsonPointer.empty())).isEqualTo("$");
    }

    @Test
    void round_trip_simple_path() {
        String original = "$.order.customer.name";
        JsonPointer ptr = PathConverter.toJsonPointer(original);
        assertThat(PathConverter.fromJsonPointer(ptr)).isEqualTo(original);
    }

    @Test
    void round_trip_indexed_path() {
        String original = "$.order.items[0].qty";
        JsonPointer ptr = PathConverter.toJsonPointer(original);
        assertThat(PathConverter.fromJsonPointer(ptr)).isEqualTo(original);
    }

    // ── segmentsFromPointer (incremental-log replay) ────────────────────────────

    @Test
    void segments_from_pointer_splits_and_unescapes() {
        assertThat(PathConverter.segmentsFromPointer("/order/items/0/qty"))
                .containsExactly("order", "items", "0", "qty");
        assertThat(PathConverter.segmentsFromPointer("/a~1b/c~0d"))
                .containsExactly("a/b", "c~d");
    }

    @Test
    void segments_from_pointer_empty_for_root() {
        assertThat(PathConverter.segmentsFromPointer("")).isEmpty();
        assertThat(PathConverter.segmentsFromPointer(null)).isEmpty();
    }

    // ── Address canonicalisation (DEC-6 / E-T1) ─────────────────────────────────

    @Test
    void canonical_address_is_unchanged_for_already_canonical_input() {
        assertThat(PathConverter.toCanonicalAddress("$.order.items[0].qty"))
                .isEqualTo("$.order.items[0].qty");
        assertThat(PathConverter.toCanonicalAddress("$.order.items[*].qty"))
                .isEqualTo("$.order.items[*].qty");
        assertThat(PathConverter.toCanonicalAddress("$")).isEqualTo("$");
    }

    @Test
    void canonical_address_normalises_legacy_dot_index_and_adds_root() {
        assertThat(PathConverter.toCanonicalAddress("$.order.items.0.qty"))
                .isEqualTo("$.order.items[0].qty");
        assertThat(PathConverter.toCanonicalAddress("order.items.0.qty"))
                .isEqualTo("$.order.items[0].qty");
    }

    @Test
    void bracket_and_legacy_dot_index_parse_to_the_same_segments() {
        assertThat(PathConverter.toSegments("$.items.0.name"))
                .isEqualTo(PathConverter.toSegments("$.items[0].name"));
    }

    @Test
    void is_canonical_address_distinguishes_canonical_from_legacy() {
        assertThat(PathConverter.isCanonicalAddress("$.items[0].name")).isTrue();
        assertThat(PathConverter.isCanonicalAddress("$.a.b")).isTrue();
        assertThat(PathConverter.isCanonicalAddress("$.items.0.name")).isFalse(); // dot-index
        assertThat(PathConverter.isCanonicalAddress("items[0].name")).isFalse();  // unrooted
        assertThat(PathConverter.isCanonicalAddress("")).isFalse();
    }
}
