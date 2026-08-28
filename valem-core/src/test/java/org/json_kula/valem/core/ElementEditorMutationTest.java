package org.json_kula.valem.core;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.json_kula.valem.core.blob.InMemoryBlobStore;
import org.json_kula.valem.core.engine.ModelRuntime;
import org.json_kula.valem.core.graph.CompiledModel;
import org.json_kula.valem.core.graph.ModelSpecCompiler;
import org.json_kula.valem.core.model.ModelSpec;
import org.json_kula.valem.core.state.ModelState;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The engine contract a {@code sectionList}'s element editor stands on.
 *
 * <p>A list of items is authored once against the array pattern ({@code $.items[*].qty}) and bound
 * per row by the renderer, which substitutes the row's index and mutates that concrete path. Two
 * things have to hold for a list the user is still filling in to behave, and neither was pinned
 * here: a write scoped to one element must land on that element alone, and a half-finished row must
 * not destroy the totals — which turns on the difference between an absent field and a null one.
 */
class ElementEditorMutationTest {

    private static final ObjectMapper M = new ObjectMapper();

    private static final String SPEC = """
        { "id": "order", "version": "1.0.0",
          "schema": { "type": "object", "properties": {
            "items": { "type": "array", "items": { "type": "object", "properties": {
              "name": {"type": "string"}, "qty": {"type": "number"}, "price": {"type": "number"},
              "lineTotal": {"type": "number", "readOnly": true} } } },
            "grandTotal": { "type": "number", "readOnly": true } } },
          "derivations": [
            { "path": "$.items[*].lineTotal", "expr": "$parent.qty * $parent.price" },
            { "path": "$.grandTotal", "expr": "$sum(items.(qty * price))" } ] }
        """;
    // grandTotal reads the element's BASE fields rather than its derived lineTotal: a dotted path
    // into a per-item derived field ($sum(items.lineTotal)) resolves to nothing at all — not 0, but
    // a blank field — because a wildcard derivation's per-element results are not visible to a
    // sibling derivation that way. It is a silent authoring bug, which is what the editable_items
    // domain guidance warns about.

    @Test
    void a_write_scoped_to_one_element_lands_on_that_element_only() throws Exception {
        ModelRuntime rt = runtime();
        rt.mutate(mutation("$.items", """
                [ { "name": "Widget", "qty": 2, "price": 5 }, { "name": "Gizmo", "qty": 1, "price": 3 } ]"""));

        // The bracket-index path the renderer produces from `$.items[*].qty` on row 1.
        rt.mutate(mutation("$.items[1].qty", "4"));

        JsonNode doc = rt.stateView().mergedDocument();
        assertThat(doc.get("items").get(0).get("qty").asInt()).as("row 0 untouched").isEqualTo(2);
        assertThat(doc.get("items").get(1).get("qty").asInt()).isEqualTo(4);
        // Both the element's own derivation and the aggregate over the array recompute.
        assertThat(doc.get("items").get(1).get("lineTotal").asDouble()).isEqualTo(12.0);
        assertThat(doc.get("grandTotal").asDouble()).isEqualTo(22.0);
    }

    @Test
    void a_row_added_with_no_fields_yet_leaves_the_totals_alone() throws Exception {
        // What Add sends: a new element with no keys at all. The user has typed nothing into it, so
        // it must contribute nothing — and the total for the rows that ARE filled in must stand.
        ModelRuntime rt = runtime();
        rt.mutate(mutation("$.items", """
                [ { "name": "Widget", "qty": 2, "price": 5 }, {} ]"""));

        JsonNode doc = rt.stateView().mergedDocument();
        assertThat(doc.get("items")).hasSize(2);
        assertThat(doc.get("grandTotal").asDouble()).isEqualTo(10.0);
    }

    @Test
    void one_null_field_in_one_row_nulls_the_whole_aggregate() throws Exception {
        /*
         * Why Add must not seed the element with null-valued keys, and the reason this test exists
         * at all: JSONata propagates a null out through arithmetic and out of $sum, so a SINGLE
         * null in a single row takes the total with it — the user adds a row and every headline
         * number on the screen goes blank until they finish typing. An absent field (the test
         * above) is skipped instead. The two shapes look equally empty in the UI and are not.
         */
        ModelRuntime rt = runtime();
        rt.mutate(mutation("$.items", """
                [ { "name": "Widget", "qty": 2, "price": 5 }, { "qty": null, "price": null } ]"""));

        JsonNode grandTotal = rt.stateView().mergedDocument().get("grandTotal");
        assertThat(grandTotal.isNull()).as("null row poisons $sum — 10.0 is NOT what comes back").isTrue();
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private static ModelRuntime runtime() throws Exception {
        ModelSpec spec = M.readValue(SPEC, ModelSpec.class);
        CompiledModel model = ModelSpecCompiler.compile(spec);
        return new ModelRuntime(model, new ModelState(model, new InMemoryBlobStore()));
    }

    private static Map<String, JsonNode> mutation(String path, String json) throws Exception {
        Map<String, JsonNode> m = new LinkedHashMap<>();
        m.put(path, M.readTree(json));
        return m;
    }
}
