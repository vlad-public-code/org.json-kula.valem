package org.json_kula.valem.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.json_kula.valem.core.graph.ModelSpecValidator;
import org.json_kula.valem.core.llm.SpecGenerationPrompt;
import org.json_kula.valem.core.model.DerivationSpec;
import org.json_kula.valem.core.model.ModelSpec;
import org.json_kula.valem.core.state.PathConverter;
import org.json_kula.valem.service.StatelessRunner;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Valem models as interactive cards in a chat app, over the MCP Apps extension
 * ({@code io.modelcontextprotocol/ui}, spec 2026-01-26), which Claude, ChatGPT and VS Code render.
 *
 * <p>The assistant writes a spec and calls {@code render_model}; Valem validates it and runs its
 * self-tests, and the host shows the result in the card ({@value #CARD_URI}). Every edit in the card
 * calls {@code valem_recompute} through the host. Nothing is kept per conversation: the card holds the
 * inputs, the tool result carries the spec in {@code _meta}, and a {@link StatelessRunner} keeps compiled
 * models in a short-term cache keyed by spec hash, so a miss is answered with {@code spec_unknown} and
 * the card resends the spec.
 *
 * <p>Transport-agnostic, like {@link ToolRegistry}: {@link McpServer} (stdio and {@code valem-web}'s
 * {@code /mcp}) and the hosted stateless endpoint both drive it through {@link #toolDefinitions},
 * {@link #call}, {@link #resourceDescriptor} and {@link #readResource}.
 *
 * <p>The card itself is the {@code valem-mcp-app} build, packaged on the classpath at
 * {@value #CARD_RESOURCE}. A build without it still serves the tools, with no UI metadata, so a spec
 * can be checked and computed as text.
 *
 * <p>A server that can open a model from a link passes {@link ShareLinks} to
 * {@link #call(String, JsonNode, ShareLinks)}: {@code render_model} and {@code valem_recompute} then
 * return links to a live copy of the model with the current inputs, which the assistant can hand out
 * (a published artifact cannot run the model itself) and the card's "Open in browser" button follows.
 */
public final class ModelCardTools {

    /** The MCP Apps extension id a host advertises under {@code capabilities.extensions}. */
    public static final String EXTENSION_ID = "io.modelcontextprotocol/ui";
    /** The MIME type of an MCP App resource. */
    public static final String MIME_TYPE = "text/html;profile=mcp-app";
    /** The card's resource URI. */
    public static final String CARD_URI = "ui://valem/model-card.html";
    /** Where the built card sits on the classpath. */
    public static final String CARD_RESOURCE = "/valem-mcp-app/model-card.html";
    /** Longer links get cut off by chat apps and mail clients, so none is offered. */
    public static final int MAX_LINK_CHARS = 8000;

    /**
     * Links that open a live copy of a model outside the chat, supplied by the server that hosts such
     * a page; the format of the link is that server's business.
     */
    @FunctionalInterface
    public interface ShareLinks {
        /**
         * Links for this model with these inputs; either may be null.
         *
         * @param shareUrl a page anyone can open in a browser
         * @param embedUrl a URL for an {@code <iframe>} on another web page
         */
        record Links(String shareUrl, String embedUrl) {}

        Links links(JsonNode spec, Map<String, JsonNode> inputs);
    }

    public static final String RENDER_MODEL = "render_model";
    public static final String RECOMPUTE = "valem_recompute";
    public static final String SPEC_GUIDE = "valem_spec_guide";

    /** How many output values the text answer lists before it stops. */
    private static final int MAX_LISTED_OUTPUTS = 20;

    /** Limits on a spec sent to be rendered; compiling is the expensive part. */
    public record Limits(int maxSpecBytes, int maxExpressions) {
        public static final Limits DEFAULT = new Limits(64 * 1024, 200);
    }

    private final StatelessRunner runner;
    private final ObjectMapper mapper;
    private final Limits limits;
    private final String cardHtml;

    public ModelCardTools(StatelessRunner runner, ObjectMapper mapper) {
        this(runner, mapper, Limits.DEFAULT, loadCard());
    }

    /** {@code cardHtml} may be null: the tools then carry no UI metadata. */
    public ModelCardTools(StatelessRunner runner, ObjectMapper mapper, Limits limits, String cardHtml) {
        this.runner = runner;
        this.mapper = mapper;
        this.limits = limits;
        this.cardHtml = cardHtml;
    }

    /** The built card from the classpath, or null when this build has none. */
    public static String loadCard() {
        try (InputStream in = ModelCardTools.class.getResourceAsStream(CARD_RESOURCE)) {
            return in == null ? null : new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            return null;
        }
    }

    /** True when the card HTML is available to serve. */
    public boolean hasCard() {
        return cardHtml != null;
    }

    /**
     * Whether to offer the card to a client, from its {@code initialize} params. A client that sends an
     * {@code extensions} map without the UI extension has said it can't render one; a client that sends
     * no map at all predates extension negotiation, and the UI metadata is harmless to it.
     */
    public static boolean clientSupportsUi(JsonNode initializeParams) {
        if (initializeParams == null) return true;
        JsonNode extensions = initializeParams.path("capabilities").path("extensions");
        if (!extensions.isObject()) return true;
        JsonNode ui = extensions.get(EXTENSION_ID);
        if (ui == null || !ui.isObject()) return false;
        JsonNode mimeTypes = ui.get("mimeTypes");
        if (mimeTypes == null || !mimeTypes.isArray()) return true;
        for (JsonNode m : mimeTypes) if (MIME_TYPE.equals(m.asText())) return true;
        return false;
    }

    /** The names {@link #call} answers. */
    public Set<String> toolNames() {
        return Set.of(RENDER_MODEL, RECOMPUTE, SPEC_GUIDE);
    }

    // ── tools/list ───────────────────────────────────────────────────────────────

    /** The tool definitions; {@code withUi} adds the card metadata and the card-only recompute tool. */
    public ArrayNode toolDefinitions(boolean withUi) {
        boolean ui = withUi && hasCard();
        ArrayNode tools = mapper.createArrayNode();

        ObjectNode guide = tools.addObject();
        guide.put("name", SPEC_GUIDE);
        guide.put("title", "Valem spec guide");
        guide.put("description", "How to write a Valem ModelSpec that render_model accepts: sections, "
                + "path notation, JSONata rules, view components and common mistakes. Call this once "
                + "before writing your first spec in a conversation.");
        guide.putObject("inputSchema").put("type", "object").putObject("properties");
        guide.set("annotations", annotations());

        ObjectNode render = tools.addObject();
        render.put("name", RENDER_MODEL);
        render.put("title", "Show a Valem model");
        render.put("description", "Show the user a Valem model as an interactive card they can edit in the "
                + "chat: a form whose computed fields update on every change, checked by a deterministic "
                + "engine. Use it when the user asks for a calculator, quote, budget, planner, pricing or "
                + "any model with inputs and computed results. Write a complete ModelSpec (call "
                + SPEC_GUIDE + " first): a schema, derivations for every computed field, a viewDefinition "
                + "that binds the inputs and shows the results, and tests with given/expect cases. Valem "
                + "validates the spec and runs its tests; if anything fails the result lists the errors "
                + "and no card is shown, so fix them and call again. To change a model, call again with "
                + "the whole revised spec and the current inputs from the card. Quote numbers from the "
                + "result, never compute them yourself.");
        ObjectNode schema = render.putObject("inputSchema");
        schema.put("type", "object");
        ObjectNode props = schema.putObject("properties");
        ObjectNode spec = props.putObject("spec");
        spec.put("type", "object");
        spec.put("description", "The complete ModelSpec (id, version, schema, derivations, constraints, "
                + "viewDefinition, tests).");
        ObjectNode inputs = props.putObject("inputs");
        inputs.put("type", "object");
        inputs.put("description", "Optional starting values keyed by canonical path, e.g. "
                + "{\"$.rooms\": 4}. Omit to use the spec's defaults.");
        inputs.put("additionalProperties", true);
        schema.putArray("required").add("spec");
        render.set("annotations", annotations());
        if (ui) render.set("_meta", toolUiMeta(false));

        if (ui) {
            ObjectNode recompute = tools.addObject();
            recompute.put("name", RECOMPUTE);
            recompute.put("title", "Recompute a Valem card");
            recompute.put("description", "Called by the Valem card when the user edits an input. Not for "
                    + "direct use: call " + RENDER_MODEL + " instead.");
            ObjectNode rs = recompute.putObject("inputSchema");
            rs.put("type", "object");
            ObjectNode rp = rs.putObject("properties");
            rp.putObject("specHash").put("type", "string");
            rp.putObject("inputs").put("type", "object").put("additionalProperties", true);
            rp.putObject("spec").put("type", "object");
            rs.putArray("required").add("specHash").add("inputs");
            recompute.set("annotations", annotations());
            recompute.set("_meta", toolUiMeta(true));
        }
        return tools;
    }

    private ObjectNode annotations() {
        ObjectNode a = mapper.createObjectNode();
        a.put("readOnlyHint", true);
        a.put("destructiveHint", false);
        a.put("idempotentHint", true);
        a.put("openWorldHint", false);
        return a;
    }

    /**
     * The tool's UI link, in the standard form plus the aliases older hosts read: the flat
     * {@code ui/resourceUri} key the ext-apps SDK still writes, and ChatGPT's {@code openai/*} keys.
     */
    private ObjectNode toolUiMeta(boolean appOnly) {
        ObjectNode meta = mapper.createObjectNode();
        ObjectNode ui = meta.putObject("ui");
        ui.put("resourceUri", CARD_URI);
        if (appOnly) ui.putArray("visibility").add("app");
        meta.put("ui/resourceUri", CARD_URI);
        meta.put("openai/outputTemplate", CARD_URI);
        if (appOnly) {
            meta.put("openai/widgetAccessible", true);
        } else {
            meta.put("openai/toolInvocation/invoking", "Checking the model…");
            meta.put("openai/toolInvocation/invoked", "Model ready");
        }
        return meta;
    }

    // ── resources ────────────────────────────────────────────────────────────────

    /** The card's {@code resources/list} entry, or null when this build has no card. */
    public ObjectNode resourceDescriptor() {
        if (!hasCard()) return null;
        ObjectNode r = mapper.createObjectNode();
        r.put("uri", CARD_URI);
        r.put("name", "valem-model-card");
        r.put("title", "Valem model card");
        r.put("description", "The interactive card render_model results are shown in.");
        r.put("mimeType", MIME_TYPE);
        r.set("_meta", resourceUiMeta());
        return r;
    }

    /** A {@code resources/read} result for the card, or null when {@code uri} is not the card's. */
    public ObjectNode readResource(String uri) {
        if (!CARD_URI.equals(uri) || !hasCard()) return null;
        ObjectNode result = mapper.createObjectNode();
        ObjectNode item = result.putArray("contents").addObject();
        item.put("uri", CARD_URI);
        item.put("mimeType", MIME_TYPE);
        item.put("text", cardHtml);
        item.set("_meta", resourceUiMeta());
        return result;
    }

    /** No network for the card: every call goes through the host, so the CSP lists stay empty. */
    private ObjectNode resourceUiMeta() {
        ObjectNode meta = mapper.createObjectNode();
        ObjectNode ui = meta.putObject("ui");
        ui.put("prefersBorder", true);
        ObjectNode csp = ui.putObject("csp");
        csp.putArray("connectDomains");
        csp.putArray("resourceDomains");
        meta.put("openai/widgetPrefersBorder", true);
        meta.put("openai/widgetDescription", "An editable Valem model: inputs on the left or top, "
                + "computed results that update as the user edits.");
        return meta;
    }

    // ── tools/call ───────────────────────────────────────────────────────────────

    /** Runs one tool, returning an MCP {@code tools/call} result; failures are {@code isError} results. */
    public ObjectNode call(String name, JsonNode arguments) {
        return call(name, arguments, null);
    }

    /** As {@link #call(String, JsonNode)}, on a server that can open a model from a link (null: none). */
    public ObjectNode call(String name, JsonNode arguments, ShareLinks shareLinks) {
        JsonNode args = arguments == null || arguments.isNull() ? mapper.createObjectNode() : arguments;
        try {
            return switch (name) {
                case SPEC_GUIDE -> text(specGuide(), null, false);
                case RENDER_MODEL -> render(args, shareLinks);
                case RECOMPUTE -> recompute(args, shareLinks);
                default -> text("Unknown tool: '" + name + "'", null, true);
            };
        } catch (IllegalArgumentException e) {
            return text(e.getMessage(), null, true);
        }
    }

    private ObjectNode render(JsonNode args, ShareLinks shareLinks) {
        JsonNode specJson = args.get("spec");
        if (specJson == null || !specJson.isObject()) {
            throw new IllegalArgumentException("Missing required argument 'spec' (a ModelSpec object).");
        }
        checkLimits(specJson);
        StatelessRunner.Prepared prepared = runner.prepare(specJson);
        if (!prepared.ok()) {
            ObjectNode structured = mapper.createObjectNode();
            structured.put("valid", false);
            ArrayNode errors = structured.putArray("errors");
            StringBuilder sb = new StringBuilder("The spec was not shown: fix these and call ")
                    .append(RENDER_MODEL).append(" again.\n");
            for (ModelSpecValidator.ValidationError e : prepared.errors()) {
                errors.addObject().put("location", e.location()).put("message", e.message());
                sb.append("- ").append(e.location()).append(": ").append(e.message()).append('\n');
            }
            return text(sb.toString().trim(), structured, true);
        }
        ModelSpec spec = prepared.spec();
        if (spec.viewDefinition() == null || spec.viewDefinition().isNull()) {
            throw new IllegalArgumentException("The spec has no viewDefinition, so there is nothing to show. "
                    + "Add one that binds the inputs and displays the computed fields (see "
                    + SPEC_GUIDE + "), then call again.");
        }

        Map<String, JsonNode> inputs = inputs(args.get("inputs"));
        StatelessRunner.Outcome outcome = runner.run(prepared.specHash(), inputs);
        List<StatelessRunner.Issue> rejected = List.of();
        if (outcome instanceof StatelessRunner.Rejected r) {
            // The starting values break a rule: show the defaults and say why the values were dropped.
            rejected = r.issues();
            inputs = Map.of();
            outcome = runner.run(prepared.specHash(), inputs);
            if (outcome instanceof StatelessRunner.Rejected again) {
                throw new IllegalArgumentException("The spec's own defaults break a rule: "
                        + messages(again.issues()) + ". Fix the defaults or the rule and call again.");
            }
        }
        StatelessRunner.Computed computed = (StatelessRunner.Computed) outcome;

        ObjectNode structured = summary(spec, prepared.specHash(), inputs, computed);
        if (!rejected.isEmpty()) structured.set("rejectedInputs", issuesNode(rejected));
        if (!prepared.warnings().isEmpty()) {
            ArrayNode w = structured.putArray("warnings");
            prepared.warnings().forEach(f -> w.add(f.location() + ": " + f.message()));
        }

        StringBuilder sb = new StringBuilder("Showing ").append(spec.id()).append(' ').append(spec.version())
                .append(" to the user as an editable card. Current values:\n");
        appendValues(sb, structured.path("outputs"));
        appendFlags(sb, computed.flags());
        if (!rejected.isEmpty()) {
            sb.append("The starting inputs were not applied: ").append(messages(rejected)).append(".\n");
        }
        sb.append("The user may now edit inputs in the card; the card reports changes back to you.");
        ShareLinks.Links links = links(shareLinks, specJson, inputs);
        if (links != null) {
            putLinks(structured, links);
            if (links.shareUrl() != null) {
                sb.append("\nShare link (a live copy of this model anyone can open in a browser; give it "
                        + "unchanged, it carries the whole model): ").append(links.shareUrl());
            }
            if (links.embedUrl() != null) {
                sb.append("\nEmbed URL (for an <iframe> on a web page): ").append(links.embedUrl());
            }
            if (links.shareUrl() == null && links.embedUrl() == null) {
                sb.append("\nThe model is too large for a share link.");
            }
        }

        ObjectNode result = text(sb.toString(), structured, false);
        ObjectNode card = result.putObject("_meta").putObject("valem/card");
        card.set("spec", specJson);
        card.put("specHash", prepared.specHash());
        card.set("inputs", mapper.valueToTree(inputs));
        if (links != null && links.shareUrl() != null) card.put("shareUrl", links.shareUrl());
        cardState(card, computed);
        return result;
    }

    private ObjectNode recompute(JsonNode args, ShareLinks shareLinks) {
        String hash = args.path("specHash").asText(null);
        if (hash == null) throw new IllegalArgumentException("Missing required argument 'specHash'.");
        JsonNode specJson = args.get("spec");
        if (specJson != null && specJson.isObject() && !runner.isCached(hash)) {
            checkLimits(specJson);
            StatelessRunner.Prepared prepared = runner.prepare(specJson);
            if (!prepared.ok()) throw new IllegalArgumentException("The resent spec is not valid.");
            hash = prepared.specHash();
        }
        Map<String, JsonNode> inputs = inputs(args.get("inputs"));
        StatelessRunner.Outcome outcome;
        try {
            outcome = runner.run(hash, inputs);
        } catch (StatelessRunner.SpecUnknownException e) {
            ObjectNode structured = mapper.createObjectNode().put("error", "spec_unknown");
            return text("spec_unknown: resend the spec with the inputs.", structured, true);
        }
        ObjectNode structured = mapper.createObjectNode();
        structured.put("specHash", hash);
        if (outcome instanceof StatelessRunner.Rejected r) {
            structured.put("rejected", r.reason());
            structured.set("issues", issuesNode(r.issues()));
            return text("Not applied: " + messages(r.issues()), structured, false);
        }
        StatelessRunner.Computed computed = (StatelessRunner.Computed) outcome;
        cardState(structured, computed);
        // Links need the spec itself, so they come only with a call that sends it (the card's
        // "Open in browser" does); an ordinary edit sends just the hash.
        ShareLinks.Links links = specJson != null && specJson.isObject() ? links(shareLinks, specJson, inputs) : null;
        if (links != null) putLinks(structured, links);
        return text("Recomputed.", structured, false);
    }

    // ── result building ──────────────────────────────────────────────────────────

    /** What the model and the card both read: identity, inputs, computed outputs and flags. */
    private ObjectNode summary(ModelSpec spec, String hash, Map<String, JsonNode> inputs,
                               StatelessRunner.Computed computed) {
        ObjectNode s = mapper.createObjectNode();
        s.put("modelId", spec.id());
        s.put("version", spec.version());
        s.put("specHash", hash);
        s.set("inputs", mapper.valueToTree(inputs));
        ObjectNode outputs = s.putObject("outputs");
        for (DerivationSpec d : spec.derivations()) {
            if (d.path().contains("[*]")) continue;
            JsonNode v = computed.state().at(PathConverter.toJsonPointer(d.path()));
            outputs.set(d.path(), v.isMissingNode() ? mapper.nullNode() : v);
        }
        s.set("flags", issuesNode(computed.flags()));
        return s;
    }

    /** The links for this model, with any too long for a chat or mail client dropped; null without a linker. */
    private static ShareLinks.Links links(ShareLinks shareLinks, JsonNode spec, Map<String, JsonNode> inputs) {
        if (shareLinks == null || spec == null) return null;
        ShareLinks.Links l = shareLinks.links(spec, inputs);
        if (l == null) return null;
        return new ShareLinks.Links(fits(l.shareUrl()), fits(l.embedUrl()));
    }

    private static String fits(String url) {
        return url != null && url.length() <= MAX_LINK_CHARS ? url : null;
    }

    private static void putLinks(ObjectNode target, ShareLinks.Links links) {
        if (links.shareUrl() != null) target.put("shareUrl", links.shareUrl());
        if (links.embedUrl() != null) target.put("embedUrl", links.embedUrl());
    }

    /** The full state the card renders from. */
    private void cardState(ObjectNode target, StatelessRunner.Computed computed) {
        target.set("state", computed.state());
        target.set("meta", computed.meta());
        target.set("flags", issuesNode(computed.flags()));
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

    private static void appendValues(StringBuilder sb, JsonNode outputs) {
        int n = 0;
        for (Map.Entry<String, JsonNode> e : outputs.properties()) {
            if (n++ == MAX_LISTED_OUTPUTS) {
                sb.append("- … and ").append(outputs.size() - MAX_LISTED_OUTPUTS).append(" more\n");
                break;
            }
            sb.append("- ").append(e.getKey()).append(" = ").append(e.getValue()).append('\n');
        }
    }

    private static void appendFlags(StringBuilder sb, List<StatelessRunner.Issue> flags) {
        if (!flags.isEmpty()) sb.append("Flagged rules: ").append(messages(flags)).append(".\n");
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

    // ── argument checks ──────────────────────────────────────────────────────────

    private void checkLimits(JsonNode specJson) {
        int bytes = specJson.toString().getBytes(StandardCharsets.UTF_8).length;
        if (bytes > limits.maxSpecBytes()) {
            throw new IllegalArgumentException("The spec is " + bytes / 1024 + " KB; the limit is "
                    + limits.maxSpecBytes() / 1024 + " KB. Simplify the model.");
        }
        int expressions = 0;
        for (String section : List.of("derivations", "metaDerivations", "constraints", "defaultValues", "effects")) {
            JsonNode s = specJson.get(section);
            if (s != null && s.isArray()) expressions += s.size();
        }
        if (expressions > limits.maxExpressions()) {
            throw new IllegalArgumentException("The spec has " + expressions + " expressions; the limit is "
                    + limits.maxExpressions() + ". Simplify the model.");
        }
    }

    private static Map<String, JsonNode> inputs(JsonNode node) {
        Map<String, JsonNode> map = new LinkedHashMap<>();
        if (node == null || node.isNull()) return map;
        if (!node.isObject()) {
            throw new IllegalArgumentException("'inputs' must be an object keyed by canonical path, "
                    + "e.g. {\"$.rooms\": 4}.");
        }
        for (Map.Entry<String, JsonNode> e : node.properties()) {
            String path = e.getKey();
            if (!PathConverter.isCanonicalAddress(path)) {
                throw new IllegalArgumentException("Input key '" + path + "' is not a canonical path; use "
                        + "the form \"$.field\" or \"$.items[0].qty\".");
            }
            map.put(path, e.getValue());
        }
        return map;
    }

    private static String specGuide() {
        return SpecGenerationPrompt.SYSTEM_CONTEXT + SpecGenerationPrompt.SYSTEM_CONTEXT_VIEW
                + "\n\n" + ResourceRegistry.JSONATA_GOTCHAS + CARD_NOTES;
    }

    /** What matters for a spec shown as a card, beyond the general authoring guide above. */
    private static final String CARD_NOTES = """


            # Specs shown with render_model

            - Always include a `viewDefinition`: it is what the user sees. Bind every input field and show
              every result the user asked about.
            - Give inputs sensible defaults (JSON Schema `default`), so the card shows real numbers before
              the user edits anything.
            - Include `tests` with at least one given/expect case per important result. render_model runs
              them and refuses the spec if any fail, so a wrong formula is caught before the user sees it.
            - Use `"policy": "flag"` for advice ("below the minimum order") and `"policy": "rollback"` only
              for values that must never be accepted; a rollback rejects the user's edit.
            - To revise a model, send the whole revised spec to render_model with the inputs the user last
              had; a new card appears with them carried over.
            - When render_model returns a share link and the user wants the model outside this chat (an
              artifact, a page to send someone, a bookmark), give them that link unchanged: it opens a live
              copy computed by Valem in any browser. For a web page, put the embed URL in an <iframe>. A
              published artifact or document cannot run the model itself, so link to it rather than
              rebuilding the calculation in code.
            """;
}
