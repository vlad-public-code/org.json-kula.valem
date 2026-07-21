package org.json_kula.valem.core.graph;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.json_kula.valem.core.model.ModelSpec;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SpecEvolutionViewTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private ModelSpec base(String json) throws Exception {
        return MAPPER.readValue(json, ModelSpec.class);
    }

    private SpecEvolution diff(String json) throws Exception {
        return MAPPER.readValue(json, SpecEvolution.class);
    }

    private static final String VIEW_SPEC = """
            {
              "id": "m",
              "version": "1.0.0",
              "schema": {},
              "viewDefinition": {
                "renderer": "builtin",
                "defaultView": "main",
                "views": [
                  { "id": "main", "label": "Main", "components": [
                    { "id": "title",  "type": "label", "text": "Hello" },
                    { "id": "section", "type": "fieldSet", "legend": "S", "components": [
                      { "id": "qty", "type": "numericField", "bind": "$.qty", "label": "Qty" }
                    ]},
                    { "id": "footer", "type": "label", "text": "Bye" }
                  ]}
                ]
              }
            }
            """;

    private static JsonNode mainComponents(ModelSpec spec) {
        return spec.viewDefinition().path("views").path(0).path("components");
    }

    private static List<String> ids(JsonNode components) {
        List<String> out = new ArrayList<>();
        components.forEach(c -> out.add(c.path("id").asText()));
        return out;
    }

    // ── Replace in place ────────────────────────────────────────────────────────

    @Test
    void component_upsert_replaces_in_place_leaving_siblings_and_order_intact() throws Exception {
        ModelSpec evolved = diff("""
                { "upsertComponents": [
                    { "viewId": "main", "component": { "id": "title", "type": "label", "text": "Hi" } }
                ]}
                """).applyTo(base(VIEW_SPEC));

        JsonNode comps = mainComponents(evolved);
        assertThat(ids(comps)).containsExactly("title", "section", "footer");
        assertThat(comps.path(0).path("text").asText()).isEqualTo("Hi");
        // section subtree untouched
        assertThat(comps.path(1).path("components").path(0).path("id").asText()).isEqualTo("qty");
    }

    // ── Insert / append / move ──────────────────────────────────────────────────

    @Test
    void insert_before_anchor_at_root() throws Exception {
        ModelSpec evolved = diff("""
                { "upsertComponents": [
                    { "viewId": "main", "beforeId": "footer",
                      "component": { "id": "banner", "type": "label", "text": "X" } }
                ]}
                """).applyTo(base(VIEW_SPEC));
        assertThat(ids(mainComponents(evolved))).containsExactly("title", "section", "banner", "footer");
    }

    @Test
    void append_at_root_when_no_anchor() throws Exception {
        ModelSpec evolved = diff("""
                { "upsertComponents": [
                    { "viewId": "main", "component": { "id": "extra", "type": "label", "text": "X" } }
                ]}
                """).applyTo(base(VIEW_SPEC));
        assertThat(ids(mainComponents(evolved))).containsExactly("title", "section", "footer", "extra");
    }

    @Test
    void insert_under_a_parent_container() throws Exception {
        ModelSpec evolved = diff("""
                { "upsertComponents": [
                    { "viewId": "main", "parentId": "section",
                      "component": { "id": "note", "type": "label", "text": "N" } }
                ]}
                """).applyTo(base(VIEW_SPEC));
        JsonNode section = mainComponents(evolved).path(1);
        assertThat(ids(section.path("components"))).containsExactly("qty", "note");
    }

    @Test
    void move_existing_component_to_root_via_beforeId() throws Exception {
        ModelSpec evolved = diff("""
                { "upsertComponents": [
                    { "viewId": "main", "beforeId": "footer",
                      "component": { "id": "qty", "type": "numericField", "bind": "$.qty", "label": "Quantity" } }
                ]}
                """).applyTo(base(VIEW_SPEC));
        JsonNode comps = mainComponents(evolved);
        assertThat(ids(comps)).containsExactly("title", "section", "qty", "footer");
        // detached from its old parent
        assertThat(ids(comps.path(1).path("components"))).isEmpty();
        assertThat(comps.path(2).path("label").asText()).isEqualTo("Quantity");
    }

    // ── Removal ─────────────────────────────────────────────────────────────────

    @Test
    void remove_container_removes_its_subtree() throws Exception {
        ModelSpec evolved = diff("""
                { "removeComponents": [ { "viewId": "main", "componentId": "section" } ] }
                """).applyTo(base(VIEW_SPEC));
        assertThat(ids(mainComponents(evolved))).containsExactly("title", "footer");
    }

    // ── View tier ───────────────────────────────────────────────────────────────

    @Test
    void component_upsert_can_target_a_view_added_in_the_same_evolution() throws Exception {
        ModelSpec evolved = diff("""
                {
                  "upsertViews": [ { "id": "summary", "label": "Summary", "components": [] } ],
                  "upsertComponents": [
                    { "viewId": "summary", "component": { "id": "total", "type": "label", "text": "T" } }
                  ]
                }
                """).applyTo(base(VIEW_SPEC));
        JsonNode summary = evolved.viewDefinition().path("views").path(1);
        assertThat(summary.path("id").asText()).isEqualTo("summary");
        assertThat(ids(summary.path("components"))).containsExactly("total");
    }

    @Test
    void removing_a_view_and_repointing_default_view() throws Exception {
        ModelSpec evolved = diff("""
                {
                  "upsertViews": [ { "id": "alt", "label": "Alt", "components": [] } ],
                  "newDefaultView": "alt",
                  "removeViews": ["main"]
                }
                """).applyTo(base(VIEW_SPEC));
        assertThat(evolved.viewDefinition().path("defaultView").asText()).isEqualTo("alt");
        assertThat(evolved.viewDefinition().path("views")).hasSize(1);
    }

    // ── Rules ───────────────────────────────────────────────────────────────────

    @Test
    void exclusive_wholesale_and_diff_is_rejected() throws Exception {
        assertThatThrownBy(() -> diff("""
                {
                  "newViewDefinition": { "views": [] },
                  "newDefaultView": "main"
                }
                """).applyTo(base(VIEW_SPEC)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("newViewDefinition cannot be combined");
    }

    @Test
    void component_op_on_unknown_view_is_rejected() throws Exception {
        assertThatThrownBy(() -> diff("""
                { "removeComponents": [ { "viewId": "ghost", "componentId": "x" } ] }
                """).applyTo(base(VIEW_SPEC)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("view 'ghost' which does not exist");
    }

    @Test
    void removing_absent_component_is_rejected() throws Exception {
        assertThatThrownBy(() -> diff("""
                { "removeComponents": [ { "viewId": "main", "componentId": "nope" } ] }
                """).applyTo(base(VIEW_SPEC)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("is absent from view 'main'");
    }

    @Test
    void unknown_beforeId_is_rejected() throws Exception {
        assertThatThrownBy(() -> diff("""
                { "upsertComponents": [
                    { "viewId": "main", "beforeId": "ghost",
                      "component": { "id": "x", "type": "label", "text": "X" } } ]}
                """).applyTo(base(VIEW_SPEC)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("beforeId 'ghost'");
    }

    @Test
    void moving_a_container_into_its_own_subtree_is_rejected() throws Exception {
        assertThatThrownBy(() -> diff("""
                { "upsertComponents": [
                    { "viewId": "main", "parentId": "qty",
                      "component": { "id": "section", "type": "fieldSet", "legend": "S", "components": [] } } ]}
                """).applyTo(base(VIEW_SPEC)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("inside its own subtree");
    }

    @Test
    void duplicate_component_id_in_result_is_caught_by_validation() throws Exception {
        // Insert a NEW container whose subtree carries an id that already exists elsewhere
        // in the view ("title"). (A top-level id collision would be treated as a move instead.)
        assertThatThrownBy(() -> diff("""
                { "upsertComponents": [
                    { "viewId": "main", "parentId": "section",
                      "component": { "id": "group", "type": "fieldSet", "legend": "G", "components": [
                        { "id": "title", "type": "label", "text": "dup" }
                      ]} } ]}
                """).applyTo(base(VIEW_SPEC)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Evolved spec failed validation");
    }

    @Test
    void dangling_default_view_after_removal_is_caught_by_validation() throws Exception {
        assertThatThrownBy(() -> diff("""
                { "removeViews": ["main"] }
                """).applyTo(base(VIEW_SPEC)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Evolved spec failed validation");
    }
}
