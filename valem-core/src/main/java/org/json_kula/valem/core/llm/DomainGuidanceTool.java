package org.json_kula.valem.core.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.json_kula.valem.core.llm.LlmClient.ToolCall;
import org.json_kula.valem.core.llm.LlmClient.ToolDefinition;
import org.json_kula.valem.core.llm.LlmClient.ToolExecutor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * A {@link WebTool} that returns vetted, copy-this instructions for a configurable set of
 * {@link DomainGuidanceCatalog} topics. The model reads the domain description — in ANY language —
 * decides which topics apply, and calls this tool with their ids; the tool returns the concatenated
 * instructions. This replaces the old English-keyword regex dispatch: the LLM (a far better semantic
 * classifier than a keyword regex) selects the topics, so it works regardless of the input language,
 * and the topic set is extensible via the catalog (builtin JSON + operator overrides).
 *
 * <p>Local and side-effect-free (no network). The executor {@linkplain ResolvedGuidanceProvider
 * remembers} everything it returned this session so {@link SpecGenerator} can re-inject that guidance
 * into repair prompts (tools run on the first attempt only).
 */
public final class DomainGuidanceTool implements WebTool {

    private static final Logger log = LoggerFactory.getLogger(DomainGuidanceTool.class);

    static final String TOOL_NAME = "get_domain_guidance";

    private final DomainGuidanceCatalog catalog;

    public DomainGuidanceTool(DomainGuidanceCatalog catalog) {
        this.catalog = catalog;
    }

    @Override
    public List<ToolDefinition> definitions() {
        return List.of(definition());
    }

    ToolDefinition definition() {
        ObjectNode schema = JsonNodeFactory.instance.objectNode();
        schema.put("type", "object");
        ObjectNode props = schema.putObject("properties");
        ObjectNode topics = props.putObject("topics");
        topics.put("type", "array");
        topics.put("description",
                "The ids of the guidance topics this domain matches (pass every one that plausibly "
                + "applies; usually 0-2). Choose from the fixed list in the tool description.");
        ObjectNode items = topics.putObject("items");
        items.put("type", "string");
        ArrayNode en = items.putArray("enum");
        catalog.ids().forEach(en::add);
        schema.putArray("required").add("topics");
        return new ToolDefinition(
                TOOL_NAME,
                "Get vetted instructions for the hard modelling shapes this domain involves. Read the "
                + "domain description (any language), pick the matching topic ids, and call this FIRST — "
                + "then follow what it returns. Available topics:\n" + catalog.menu(),
                schema);
    }

    @Override
    public GuidanceExecutor newExecutor() {
        return new GuidanceExecutor();
    }

    /** Resolves topic ids to instructions and accumulates them for later re-injection into repairs. */
    final class GuidanceExecutor implements ToolExecutor, ResolvedGuidanceProvider {

        // Topics resolved across all calls this session, in first-seen order (deduped).
        private final Set<String> resolvedTopics = new LinkedHashSet<>();

        @Override
        public String execute(ToolCall call) {
            List<String> requested = parseTopics(call.arguments());
            if (requested.isEmpty()) {
                return "[get_domain_guidance: pass a non-empty \"topics\" array chosen from: "
                        + String.join(", ", catalog.ids()) + "]";
            }
            List<String> matched = new ArrayList<>();
            List<String> unknown = new ArrayList<>();
            for (String id : requested) {
                if (catalog.byId(id).isPresent()) matched.add(id);
                else unknown.add(id);
            }
            resolvedTopics.addAll(matched);
            log.info("DomainGuidanceTool: resolved topics {}{}", matched,
                    unknown.isEmpty() ? "" : " (ignored unknown: " + unknown + ")");

            String instructions = catalog.instructionsFor(matched);
            if (instructions.isBlank()) {
                return "[get_domain_guidance: none of " + requested + " is a known topic. Valid topics: "
                        + String.join(", ", catalog.ids()) + "]";
            }
            String note = unknown.isEmpty() ? ""
                    : "\n(Note: ignored unknown topic(s) " + unknown + ".)";
            return "GUIDANCE for " + matched + ":" + instructions + note;
        }

        @Override
        public String resolvedGuidance() {
            return catalog.instructionsFor(resolvedTopics);
        }

        private static List<String> parseTopics(JsonNode args) {
            List<String> out = new ArrayList<>();
            JsonNode topics = args == null ? null : args.get("topics");
            if (topics != null && topics.isArray()) {
                for (JsonNode t : topics) if (t.isTextual() && !t.asText().isBlank()) out.add(t.asText());
            } else if (topics != null && topics.isTextual() && !topics.asText().isBlank()) {
                out.add(topics.asText());   // tolerate a single-string arg
            }
            return out;
        }
    }
}
