package org.json_kula.valem.view.engine;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ViewExpressionsTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void collects_evaluated_strings_across_nested_containers_and_table_cells() throws Exception {
        var view = MAPPER.readTree("""
                { "renderer": "builtin", "defaultView": "main", "views": [ { "id": "main", "components": [
                    { "id": "s", "type": "staticText", "text": "$.a & \\"!\\"", "visible": "showA" },
                    { "id": "lit", "type": "staticText", "text": "Just words" },
                    { "id": "g", "type": "group", "collapsed": "$.c", "components": [
                        { "id": "b", "type": "button", "text": "$.b", "enabled": "$.ok",
                          "mutations": { "$.x": "1" } } ] },
                    { "id": "t", "type": "editableTable", "bind": "$.items", "canAdd": "$count($.items) < 5",
                      "gridColumns": [ { "id": "c1", "header": "Total", "components": [
                          { "id": "tot", "type": "label", "bind": "$.items[*].total", "text": "$.items[*].total" } ] } ] },
                    { "id": "dup", "type": "staticText", "text": "$.a & \\"!\\"" }
                ] } ] }
                """);

        assertThat(ViewExpressions.collect(view)).containsExactly(
                "$.a & \"!\"", "showA", "$.c", "$.b", "$.ok", "$count($.items) < 5", "$.items[*].total");
    }

    @Test
    void null_or_missing_view_collects_nothing() {
        assertThat(ViewExpressions.collect(null)).isEmpty();
        assertThat(ViewExpressions.collect(MAPPER.nullNode())).isEmpty();
    }
}
