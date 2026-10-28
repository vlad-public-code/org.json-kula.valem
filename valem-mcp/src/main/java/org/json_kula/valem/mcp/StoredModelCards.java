package org.json_kula.valem.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.json_kula.valem.core.engine.ConstraintEvaluator;
import org.json_kula.valem.core.engine.SchemaViolationException;
import org.json_kula.valem.core.graph.ModelGraph;
import org.json_kula.valem.core.model.DerivationSpec;
import org.json_kula.valem.core.model.ModelSpec;
import org.json_kula.valem.core.state.PathConverter;
import org.json_kula.valem.service.ModelNotFoundException;
import org.json_kula.valem.service.ModelOperations;
import org.json_kula.valem.service.StatelessRunner;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The model card for a model the server keeps: the stdio server's in-memory models, {@code valem-web}'s
 * stored ones, or a paired sandbox session's. Where {@link ModelCardTools} recomputes a spec from scratch
 * on every edit, this card writes each edit to the model with {@code mutate}, so the assistant's
 * {@code get_state} and {@code explain} see what the user typed, and the user sees what the assistant
 * set. It is the same card ({@value ModelCardTools#CARD_URI}); its payload carries the model id, which
 * tells the card to send edits to {@value #EDIT} instead of {@code valem_recompute}.
 *
 * <p>{@value #SHOW} is listed only to clients that render the card: without one, {@code get_state}
 * already answers the same question.
 */
public final class StoredModelCards {

    public static final String SHOW = "show_model";
    public static final String EDIT = "valem_card_edit";

    /** How many output values the text answer lists before it stops. */
    private static final int MAX_LISTED_OUTPUTS = 20;

    private final ModelOperations service;
    private final ModelCardTools cards;
    private final ObjectMapper mapper;

    public StoredModelCards(ModelOperations service, ModelCardTools cards, ObjectMapper mapper) {
        this.service = service;
        this.cards = cards;
        this.mapper = mapper;
    }

    /** The names {@link #call} answers. */
    public Set<String> toolNames() {
        return Set.of(SHOW, EDIT);
    }

    /** The tool definitions, or none when the client can't show the card. */
    public ArrayNode toolDefinitions(boolean withUi) {
        ArrayNode tools = mapper.createArrayNode();
        if (!withUi || !cards.hasCard()) return tools;

        ObjectNode show = tools.addObject();
        show.put("name", SHOW);
        show.put("title", "Show a stored Valem model");
        show.put("description", "Show the user one of this server's models (created with create_model) "
                + "as an interactive card in the chat. Every edit the user makes in the card is a mutate on "
                + "the model, so get_state and explain see it. Use it after create_model or evolve_spec "
                + "when the user should see or try the model. The model needs a viewDefinition. To show a "
                + "spec without creating a model, use " + ModelCardTools.RENDER_MODEL + " instead.");
        ObjectNode schema = show.putObject("inputSchema");
        schema.put("type", "object");
        schema.putObject("properties").putObject("id").put("type", "string")
                .put("description", "The model id.");
        schema.putArray("required").add("id");
        show.set("annotations", annotations(true));
        show.set("_meta", cards.cardToolMeta());

        ObjectNode edit = tools.addObject();
        edit.put("name", EDIT);
        edit.put("title", "Apply a Valem card edit");
        edit.put("description", "Called by the Valem card when the user edits a stored model. Not for "
                + "direct use: call mutate instead.");
        ObjectNode es = edit.putObject("inputSchema");
        es.put("type", "object");
        ObjectNode ep = es.putObject("properties");
        ep.putObject("id").put("type", "string");
        ep.putObject("specHash").put("type", "string");
        ep.putObject("mutations").put("type", "object").put("additionalProperties", true);
        es.putArray("required").add("id");
        edit.set("annotations", annotations(false));
        ObjectNode meta = edit.putObject("_meta");
        meta.putObject("ui").put("resourceUri", ModelCardTools.CARD_URI).putArray("visibility").add("app");
        meta.put("ui/resourceUri", ModelCardTools.CARD_URI);
        meta.put("openai/outputTemplate", ModelCardTools.CARD_URI);
        meta.put("openai/widgetAccessible", true);
        return tools;
    }

    private ObjectNode annotations(boolean readOnly) {
        ObjectNode a = mapper.createObjectNode();
        a.put("readOnlyHint", readOnly);
        a.put("destructiveHint", false);
        a.put("idempotentHint", readOnly);
        a.put("openWorldHint", false);
        return a;
    }

    // ── tools/call ───────────────────────────────────────────────────────────────

    /** Runs one tool; every failure, a model the server doesn't have included, is an {@code isError} result. */
    public ObjectNode call(String name, JsonNode arguments) {
        JsonNode args = arguments == null || arguments.isNull() ? mapper.createObjectNode() : arguments;
        String id = args.path("id").asText("").trim();
        if (id.isEmpty()) return text("Missing required argument 'id' (the model id).", null, true);
        try {
            return switch (name) {
                case SHOW -> show(id);
                case EDIT -> edit(id, args);
                default -> text("Unknown tool: '" + name + "'", null, true);
            };
        } catch (ModelNotFoundException e) {
            return text("No model '" + id + "' on this server. list_models shows the ones it has.", null, true);
        } catch (RuntimeException e) {
            return text(e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName(), null, true);
        }
    }

    private ObjectNode show(String id) {
        ModelSpec spec = service.getSpec(id);
        if (spec.viewDefinition() == null || spec.viewDefinition().isNull()) {
            return text("Model '" + id + "' has no viewDefinition, so there is nothing to show. Add one with "
                    + "evolve_spec (newViewDefinition), then call " + SHOW + " again.", null, true);
        }
        JsonNode specJson = mapper.valueToTree(spec);
        ObjectNode state = service.getState(id, null);
        List<StatelessRunner.Issue> flags = StatelessRunner.issues(spec, service.currentFlags(id));

        ObjectNode structured = mapper.createObjectNode();
        structured.put("modelId", id);
        structured.put("version", spec.version());
        ObjectNode outputs = structured.putObject("outputs");
        for (DerivationSpec d : spec.derivations()) {
            if (d.path().contains("[*]")) continue;
            outputs.set(d.path(), valueAt(state, d.path()));
        }
        structured.set("flags", issuesNode(flags));

        StringBuilder sb = new StringBuilder("Showing model ").append(id).append(' ').append(spec.version())
                .append(" to the user as an editable card. Current values:\n");
        int n = 0;
        for (Map.Entry<String, JsonNode> e : outputs.properties()) {
            if (n++ == MAX_LISTED_OUTPUTS) {
                sb.append("- … and ").append(outputs.size() - MAX_LISTED_OUTPUTS).append(" more\n");
                break;
            }
            sb.append("- ").append(e.getKey()).append(" = ").append(e.getValue()).append('\n');
        }
        if (!flags.isEmpty()) sb.append("Flagged rules: ").append(messages(flags)).append(".\n");
        sb.append("Edits in the card are saved to the model; read them back with get_state.");

        ObjectNode result = text(sb.toString(), structured, false);
        ObjectNode card = result.putObject("_meta").putObject("valem/card");
        card.put("modelId", id);
        card.set("spec", specJson);
        card.put("specHash", StatelessRunner.specHash(specJson));
        card.putObject("inputs");
        card.set("state", state);
        card.set("meta", meta(id, spec));
        card.set("flags", issuesNode(flags));
        card.set("graph", graphNode(service.graph(id)));
        return result;
    }

    /**
     * Applies the card's edits (none: just read the current state back). The answer carries the spec
     * again when it has changed since the card's {@code specHash}, so a card the assistant evolved
     * under redraws with the new view.
     */
    private ObjectNode edit(String id, JsonNode args) {
        Map<String, JsonNode> mutations = mutations(args.get("mutations"));
        ModelSpec spec = service.getSpec(id);
        if (!mutations.isEmpty()) {
            try {
                service.mutate(id, mutations);
            } catch (ConstraintEvaluator.ConstraintViolationException e) {
                return rejected(id, "constraint", StatelessRunner.issues(spec, e.violations()));
            } catch (SchemaViolationException e) {
                List<StatelessRunner.Issue> issues = e.violations().stream()
                        .map(v -> new StatelessRunner.Issue(v.keyword(), v.message(),
                                v.path() == null ? List.of() : List.of(v.path())))
                        .toList();
                return rejected(id, "schema", issues);
            }
        }
        // Read after the write: another client may have evolved the model in between.
        spec = service.getSpec(id);
        JsonNode specJson = mapper.valueToTree(spec);
        String hash = StatelessRunner.specHash(specJson);
        ObjectNode structured = mapper.createObjectNode();
        structured.put("specHash", hash);
        structured.set("state", service.getState(id, null));
        structured.set("meta", meta(id, spec));
        structured.set("flags", issuesNode(StatelessRunner.issues(spec, service.currentFlags(id))));
        if (!hash.equals(args.path("specHash").asText(null))) {
            structured.set("spec", specJson);
            structured.set("graph", graphNode(service.graph(id)));
        }
        return text(mutations.isEmpty() ? "Current state." : "Saved.", structured, false);
    }

    private ObjectNode rejected(String id, String reason, List<StatelessRunner.Issue> issues) {
        ObjectNode structured = mapper.createObjectNode();
        structured.put("specHash", StatelessRunner.specHash(mapper.valueToTree(service.getSpec(id))));
        structured.put("rejected", reason);
        structured.set("issues", issuesNode(issues));
        return text("Not applied: " + messages(issues), structured, false);
    }

    // ── result building ──────────────────────────────────────────────────────────

    /** {@code <bind>#readOnly|visible|required} across every view; empty without meta-derivations. */
    private ObjectNode meta(String id, ModelSpec spec) {
        ObjectNode meta = mapper.createObjectNode();
        if (spec.metaDerivations().isEmpty()) return meta;
        for (JsonNode view : spec.viewDefinition().path("views")) {
            String viewId = view.path("id").asText(null);
            if (viewId == null) continue;
            JsonNode evaluated = service.getView(id, viewId);
            if (evaluated != null) StatelessRunner.collectMeta(evaluated.path("components"), meta);
        }
        return meta;
    }

    private ObjectNode graphNode(ModelGraph graph) {
        ObjectNode g = mapper.createObjectNode();
        ArrayNode nodes = g.putArray("nodes");
        for (ModelGraph.Node n : graph.nodes()) {
            ObjectNode o = nodes.addObject().put("key", n.key()).put("kind", n.kind()).put("label", n.label());
            if (n.expression() == null) o.putNull("expression"); else o.put("expression", n.expression());
        }
        ArrayNode edges = g.putArray("edges");
        for (ModelGraph.Edge e : graph.edges()) edges.addObject().put("from", e.from()).put("to", e.to());
        return g;
    }

    private JsonNode valueAt(JsonNode state, String path) {
        JsonNode v = state.at(PathConverter.toJsonPointer(path));
        return v.isMissingNode() ? mapper.nullNode() : v;
    }

    private ArrayNode issuesNode(List<StatelessRunner.Issue> issues) {
        ArrayNode arr = mapper.createArrayNode();
        for (StatelessRunner.Issue i : issues) {
            ObjectNode o = arr.addObject();
            o.put("id", i.id());
            o.put("message", i.message());
            ArrayNode paths = o.putArray("paths");
            i.paths().forEach(paths::add);
        }
        return arr;
    }

    private static String messages(List<StatelessRunner.Issue> issues) {
        return String.join("; ", issues.stream()
                .map(i -> i.message() == null || i.message().isBlank() ? i.id() : i.message()).toList());
    }

    private ObjectNode text(String text, ObjectNode structured, boolean isError) {
        ObjectNode result = mapper.createObjectNode();
        result.putArray("content").addObject().put("type", "text").put("text", text);
        if (structured != null) result.set("structuredContent", structured);
        result.put("isError", isError);
        return result;
    }

    private static Map<String, JsonNode> mutations(JsonNode node) {
        Map<String, JsonNode> map = new LinkedHashMap<>();
        if (node == null || node.isNull()) return map;
        if (!node.isObject()) {
            throw new IllegalArgumentException("'mutations' must be an object keyed by canonical path.");
        }
        for (Map.Entry<String, JsonNode> e : node.properties()) {
            if (!PathConverter.isCanonicalAddress(e.getKey())) {
                throw new IllegalArgumentException("Mutation key '" + e.getKey() + "' is not a canonical path.");
            }
            map.put(e.getKey(), e.getValue());
        }
        return map;
    }
}
