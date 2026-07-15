package org.json_kula.valem.core.graph;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.List;

/**
 * Structural, id-addressed splice of a {@code viewDefinition} JSON tree — the view-tier
 * counterpart to the schema splice in {@link SpecEvolution}. Operates entirely on raw
 * {@link JsonNode}s so {@code valem-core} keeps no dependency on {@code valem-view};
 * the evolved tree is parsed into typed records (and fully validated) downstream.
 *
 * <p>A view definition is {@code { renderer, views: [ ViewSpec... ], defaultView }} where each
 * {@code ViewSpec} has an {@code id} and a recursive {@code components} tree, each component
 * carrying its own {@code id}.
 *
 * <p>Operations that cannot be applied (unknown view/component/anchor id, or a move into a
 * component's own subtree) throw {@link IllegalArgumentException}. Result-level invariants
 * (id uniqueness, dangling {@code defaultView}/{@code itemView}) are enforced afterwards by
 * {@link ModelSpecValidator}.
 */
public final class ViewDefinitionSplice {

    private static final JsonNodeFactory NF = JsonNodeFactory.instance;

    private ViewDefinitionSplice() {}

    public static JsonNode apply(
            JsonNode base,
            List<JsonNode> upsertViews,
            List<String> removeViews,
            List<SpecEvolution.ComponentUpsert> upsertComponents,
            List<SpecEvolution.ComponentRemove> removeComponents,
            String newDefaultView) {

        ObjectNode root = base != null && base.isObject() ? base.deepCopy() : NF.objectNode();
        ArrayNode views = ensureViews(root);

        // 1. View removals
        for (String viewId : removeViews) {
            removeView(views, viewId);
        }

        // 2. View upserts (replace by id, else append)
        for (JsonNode payload : upsertViews) {
            if (payload == null || !payload.isObject() || !payload.hasNonNull("id")) {
                throw new IllegalArgumentException(
                        "SpecEvolution: upsertViews entries must be view objects carrying an 'id'");
            }
            upsertView(views, (ObjectNode) payload);
        }

        // 3. Component removals
        for (SpecEvolution.ComponentRemove rm : removeComponents) {
            ObjectNode view = requireView(views, rm.viewId(), "removeComponents");
            if (removeComponent(componentsOf(view, false), rm.componentId()) == null) {
                throw new IllegalArgumentException(
                        "SpecEvolution: removeComponents component '" + rm.componentId()
                        + "' is absent from view '" + rm.viewId() + "'");
            }
        }

        // 4. Component upserts
        for (SpecEvolution.ComponentUpsert up : upsertComponents) {
            if (up.component() == null || !up.component().isObject() || !up.component().hasNonNull("id")) {
                throw new IllegalArgumentException(
                        "SpecEvolution: upsertComponents.component must be an object carrying an 'id'");
            }
            ObjectNode view = requireView(views, up.viewId(), "upsertComponents");
            upsertComponent(view, up);
        }

        // 5. defaultView
        if (newDefaultView != null) {
            root.put("defaultView", newDefaultView);
        }

        return root;
    }

    // ── Views ─────────────────────────────────────────────────────────────────

    private static ArrayNode ensureViews(ObjectNode root) {
        JsonNode v = root.get("views");
        if (v != null && v.isArray()) return (ArrayNode) v;
        ArrayNode arr = NF.arrayNode();
        root.set("views", arr);
        return arr;
    }

    private static void removeView(ArrayNode views, String viewId) {
        for (int i = views.size() - 1; i >= 0; i--) {
            if (idEquals(views.get(i), viewId)) views.remove(i);
        }
    }

    private static void upsertView(ArrayNode views, ObjectNode payload) {
        String id = payload.get("id").asText();
        for (int i = 0; i < views.size(); i++) {
            if (idEquals(views.get(i), id)) { views.set(i, payload); return; }
        }
        views.add(payload);
    }

    private static ObjectNode requireView(ArrayNode views, String viewId, String field) {
        for (JsonNode v : views) {
            if (idEquals(v, viewId)) return (ObjectNode) v;
        }
        throw new IllegalArgumentException(
                "SpecEvolution: " + field + " targets view '" + viewId + "' which does not exist");
    }

    // ── Components ──────────────────────────────────────────────────────────────

    private static void upsertComponent(ObjectNode view, SpecEvolution.ComponentUpsert up) {
        ObjectNode component = ((ObjectNode) up.component());
        String id = component.get("id").asText();
        Locator existing = locate(componentsOf(view, false), id);

        if (existing != null && up.parentId() == null && up.beforeId() == null) {
            // Replace in place (position preserved).
            existing.container().set(existing.index(), component);
            return;
        }
        if (existing != null) {
            // Move/reposition: guard against relocating a component into its own subtree (checked
            // against the live node being moved, not the replacement payload), then detach.
            JsonNode liveNode = existing.container().get(existing.index());
            if (up.parentId() != null
                    && (id.equals(up.parentId()) || findComponent(liveNode, up.parentId()) != null)) {
                throw new IllegalArgumentException(
                        "SpecEvolution: upsertComponents cannot move '" + id
                        + "' under '" + up.parentId() + "' — the target is inside its own subtree");
            }
            existing.container().remove(existing.index());
        }
        insert(view, up.parentId(), up.beforeId(), component);
    }

    private static void insert(ObjectNode view, String parentId, String beforeId, ObjectNode component) {
        ArrayNode target;
        if (parentId == null) {
            target = componentsOf(view, true);
        } else {
            ObjectNode parent = findComponent(view, parentId);
            if (parent == null) {
                throw new IllegalArgumentException(
                        "SpecEvolution: upsertComponents parentId '" + parentId + "' does not exist in the view");
            }
            target = componentsOf(parent, true);
        }
        if (beforeId != null) {
            for (int i = 0; i < target.size(); i++) {
                if (idEquals(target.get(i), beforeId)) { target.insert(i, component); return; }
            }
            throw new IllegalArgumentException(
                    "SpecEvolution: upsertComponents beforeId '" + beforeId + "' does not exist in the target container");
        }
        target.add(component);
    }

    /** Removes the first component with {@code id} from the tree rooted at {@code components}. */
    private static ObjectNode removeComponent(ArrayNode components, String id) {
        if (components == null) return null;
        for (int i = 0; i < components.size(); i++) {
            JsonNode child = components.get(i);
            if (idEquals(child, id)) {
                components.remove(i);
                return (ObjectNode) child;
            }
            ObjectNode found = removeComponent(childComponents(child), id);
            if (found != null) return found;
        }
        return null;
    }

    /** Finds a component by id anywhere under {@code node}'s component tree (excludes {@code node} itself). */
    private static ObjectNode findComponent(JsonNode node, String id) {
        ArrayNode components = childComponents(node);
        if (components == null) return null;
        for (JsonNode child : components) {
            if (idEquals(child, id)) return (ObjectNode) child;
            ObjectNode found = findComponent(child, id);
            if (found != null) return found;
        }
        return null;
    }

    /** Locates the (container array, index) of component {@code id} within the tree. */
    private static Locator locate(ArrayNode components, String id) {
        if (components == null) return null;
        for (int i = 0; i < components.size(); i++) {
            JsonNode child = components.get(i);
            if (idEquals(child, id)) return new Locator(components, i);
            Locator deeper = locate(childComponents(child), id);
            if (deeper != null) return deeper;
        }
        return null;
    }

    private static ArrayNode componentsOf(ObjectNode holder, boolean create) {
        JsonNode c = holder.get("components");
        if (c != null && c.isArray()) return (ArrayNode) c;
        if (!create) return null;
        ArrayNode arr = NF.arrayNode();
        holder.set("components", arr);
        return arr;
    }

    private static ArrayNode childComponents(JsonNode node) {
        if (node == null || !node.isObject()) return null;
        JsonNode c = node.get("components");
        return c != null && c.isArray() ? (ArrayNode) c : null;
    }

    private static boolean idEquals(JsonNode node, String id) {
        return node != null && node.isObject()
                && node.hasNonNull("id") && node.get("id").asText().equals(id);
    }

    private record Locator(ArrayNode container, int index) {}
}
