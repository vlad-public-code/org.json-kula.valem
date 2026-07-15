package org.json_kula.valem.core.engine;

import org.json_kula.valem.core.state.ModelState;
import org.json_kula.valem.core.state.PathConverter;
import org.json_kula.tracked_json.json_node.TrackedJsonNode;
import org.json_kula.tracked_json.json_path.JsonPathSearch;

import java.util.List;

/**
 * Expands JsonPath expressions containing {@code [*]} wildcard segments into all
 * matching concrete paths given the current model state.
 *
 * <p>Example: for state {@code {order:{items:[{qty:1},{qty:2}]}}}, the pattern
 * {@code "$.order.items[*].qty"} expands to {@code ["$.order.items[0].qty", "$.order.items[1].qty"]}.
 *
 * <p>Delegates to {@link JsonPathSearch} from the {@code tracked-json} library.
 */
public final class ArrayPathExpander {

    private ArrayPathExpander() {}

    /**
     * Returns all concrete paths that {@code patternPath} matches in the current state.
     * For patterns without any {@code [*]}, returns a single-element list with the pattern itself.
     */
    public static List<String> expand(String patternPath, ModelState state) {
        if (!patternPath.contains("[*]")) return List.of(patternPath);
        TrackedJsonNode root = state.asRoot();
        return JsonPathSearch.find(root, patternPath).stream()
                .map(n -> PathConverter.fromJsonPointer(n.pointer()))
                .toList();
    }
}
