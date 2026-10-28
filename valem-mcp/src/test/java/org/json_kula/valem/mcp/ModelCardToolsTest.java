package org.json_kula.valem.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.json_kula.valem.core.blob.InMemoryBlobStore;
import org.json_kula.valem.service.ModelRegistry;
import org.json_kula.valem.service.ModelService;
import org.json_kula.valem.service.StatelessRunner;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ModelCardToolsTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final String QUOTE = """
            {
              "id": "cleaning-quote",
              "version": "1.0.0",
              "schema": {
                "type": "object",
                "properties": {
                  "rooms": {"type": "integer", "minimum": 0, "default": 2},
                  "price": {"type": "number", "readOnly": true}
                }
              },
              "derivations": [{"path": "$.price", "expr": "rooms * 30"}],
              "constraints": [
                {"id": "min-order", "expr": "price >= 80", "message": "Below the minimum order of 80", "policy": "flag"},
                {"id": "max-rooms", "expr": "rooms <= 20", "message": "At most 20 rooms", "policy": "rollback"}
              ],
              "tests": [{"description": "four rooms", "given": {"$.rooms": 4}, "expect": {"$.price": 120}}],
              "viewDefinition": {"defaultView": "main", "views": [{"id": "main", "label": "Quote",
                "components": [{"id": "rooms", "type": "numericField", "label": "Rooms", "bind": "$.rooms"}]}]}
            }
            """;

    private final ModelCardTools tools =
            new ModelCardTools(new StatelessRunner(), MAPPER, ModelCardTools.Limits.DEFAULT, "<html>card</html>");

    @Test
    void render_model_shows_a_valid_spec_with_its_values_and_the_card_payload() throws Exception {
        ObjectNode args = MAPPER.createObjectNode();
        args.set("spec", MAPPER.readTree(QUOTE));
        args.putObject("inputs").put("$.rooms", 4);

        JsonNode result = tools.call(ModelCardTools.RENDER_MODEL, args);

        assertThat(result.path("isError").asBoolean()).isFalse();
        assertThat(result.path("content").get(0).path("text").asText()).contains("$.price = 120");
        JsonNode structured = result.path("structuredContent");
        assertThat(structured.path("outputs").path("$.price").asInt()).isEqualTo(120);
        assertThat(structured.path("flags")).isEmpty();
        JsonNode card = result.path("_meta").path("valem/card");
        assertThat(card.path("spec").path("id").asText()).isEqualTo("cleaning-quote");
        assertThat(card.path("state").path("price").asInt()).isEqualTo(120);
        assertThat(card.path("specHash").asText()).isEqualTo(structured.path("specHash").asText());
    }

    private static final ModelCardTools.ShareLinks LINKS = (spec, inputs) -> new ModelCardTools.ShareLinks.Links(
            "https://valem.run/#share=" + spec.path("id").asText() + "/" + inputs.size(),
            "https://valem.run/embed.html#e=" + spec.path("id").asText());

    @Test
    void render_model_returns_the_servers_share_and_embed_links() throws Exception {
        ObjectNode args = MAPPER.createObjectNode();
        args.set("spec", MAPPER.readTree(QUOTE));
        args.putObject("inputs").put("$.rooms", 4);

        JsonNode result = tools.call(ModelCardTools.RENDER_MODEL, args, LINKS);

        JsonNode structured = result.path("structuredContent");
        assertThat(structured.path("shareUrl").asText()).isEqualTo("https://valem.run/#share=cleaning-quote/1");
        assertThat(structured.path("embedUrl").asText()).isEqualTo("https://valem.run/embed.html#e=cleaning-quote");
        assertThat(result.path("content").get(0).path("text").asText())
                .contains("https://valem.run/#share=cleaning-quote/1").contains("Embed URL");
        assertThat(result.path("_meta").path("valem/card").path("shareUrl").asText())
                .isEqualTo("https://valem.run/#share=cleaning-quote/1");
    }

    @Test
    void recompute_returns_links_for_the_current_inputs_only_when_the_spec_is_sent() throws Exception {
        String hash = tools.call(ModelCardTools.RENDER_MODEL, MAPPER.createObjectNode().set("spec", MAPPER.readTree(QUOTE)))
                .path("structuredContent").path("specHash").asText();
        ObjectNode args = MAPPER.createObjectNode().put("specHash", hash);
        args.putObject("inputs").put("$.rooms", 5);

        assertThat(tools.call(ModelCardTools.RECOMPUTE, args, LINKS).path("structuredContent").has("shareUrl")).isFalse();

        args.set("spec", MAPPER.readTree(QUOTE));
        assertThat(tools.call(ModelCardTools.RECOMPUTE, args, LINKS).path("structuredContent").path("shareUrl").asText())
                .isEqualTo("https://valem.run/#share=cleaning-quote/1");
    }

    @Test
    void a_link_too_long_for_a_chat_is_dropped() throws Exception {
        ModelCardTools.ShareLinks huge = (spec, inputs) ->
                new ModelCardTools.ShareLinks.Links("https://x/#" + "a".repeat(ModelCardTools.MAX_LINK_CHARS), null);
        JsonNode result = tools.call(ModelCardTools.RENDER_MODEL,
                MAPPER.createObjectNode().set("spec", MAPPER.readTree(QUOTE)), huge);

        assertThat(result.path("structuredContent").has("shareUrl")).isFalse();
        assertThat(result.path("content").get(0).path("text").asText()).contains("too large for a share link");
    }

    @Test
    void without_a_linker_there_is_no_link() throws Exception {
        ObjectNode args = MAPPER.createObjectNode();
        args.set("spec", MAPPER.readTree(QUOTE));

        JsonNode result = tools.call(ModelCardTools.RENDER_MODEL, args);

        assertThat(result.path("structuredContent").has("shareUrl")).isFalse();
        assertThat(result.path("_meta").path("valem/card").has("shareUrl")).isFalse();
    }

    @Test
    void render_model_returns_fixable_errors_when_a_self_test_fails() throws Exception {
        ObjectNode spec = (ObjectNode) MAPPER.readTree(QUOTE);
        ((ObjectNode) spec.withArray("tests").get(0).get("expect")).put("$.price", 999);
        ObjectNode args = MAPPER.createObjectNode();
        args.set("spec", spec);

        JsonNode result = tools.call(ModelCardTools.RENDER_MODEL, args);

        assertThat(result.path("isError").asBoolean()).isTrue();
        assertThat(result.path("content").get(0).path("text").asText()).contains("four rooms");
        assertThat(result.has("_meta")).isFalse();
    }

    @Test
    void render_model_falls_back_to_defaults_when_starting_inputs_break_a_rule() throws Exception {
        ObjectNode args = MAPPER.createObjectNode();
        args.set("spec", MAPPER.readTree(QUOTE));
        args.putObject("inputs").put("$.rooms", 50);

        JsonNode result = tools.call(ModelCardTools.RENDER_MODEL, args);

        assertThat(result.path("isError").asBoolean()).isFalse();
        assertThat(result.path("structuredContent").path("rejectedInputs").get(0).path("id").asText())
                .isEqualTo("max-rooms");
        assertThat(result.path("structuredContent").path("outputs").path("$.price").asInt()).isEqualTo(60);
        assertThat(result.path("structuredContent").path("flags").get(0).path("id").asText())
                .isEqualTo("min-order");
    }

    @Test
    void render_model_refuses_a_spec_without_a_view() throws Exception {
        ObjectNode spec = (ObjectNode) MAPPER.readTree(QUOTE);
        spec.remove("viewDefinition");
        ObjectNode args = MAPPER.createObjectNode();
        args.set("spec", spec);

        JsonNode result = tools.call(ModelCardTools.RENDER_MODEL, args);

        assertThat(result.path("isError").asBoolean()).isTrue();
        assertThat(result.path("content").get(0).path("text").asText()).contains("viewDefinition");
    }

    @Test
    void render_model_enforces_the_size_limit() throws Exception {
        ModelCardTools small = new ModelCardTools(new StatelessRunner(), MAPPER,
                new ModelCardTools.Limits(100, 200), "<html></html>");
        ObjectNode args = MAPPER.createObjectNode();
        args.set("spec", MAPPER.readTree(QUOTE));

        JsonNode result = small.call(ModelCardTools.RENDER_MODEL, args);

        assertThat(result.path("isError").asBoolean()).isTrue();
        assertThat(result.path("content").get(0).path("text").asText()).contains("limit");
    }

    @Test
    void recompute_applies_the_inputs_and_reports_a_rejected_edit() throws Exception {
        ObjectNode render = MAPPER.createObjectNode();
        render.set("spec", MAPPER.readTree(QUOTE));
        String hash = tools.call(ModelCardTools.RENDER_MODEL, render).path("structuredContent").path("specHash").asText();

        ObjectNode ok = MAPPER.createObjectNode().put("specHash", hash);
        ok.putObject("inputs").put("$.rooms", 5);
        JsonNode okResult = tools.call(ModelCardTools.RECOMPUTE, ok);
        assertThat(okResult.path("structuredContent").path("state").path("price").asInt()).isEqualTo(150);

        ObjectNode bad = MAPPER.createObjectNode().put("specHash", hash);
        bad.putObject("inputs").put("$.rooms", 25);
        JsonNode badResult = tools.call(ModelCardTools.RECOMPUTE, bad);
        assertThat(badResult.path("structuredContent").path("rejected").asText()).isEqualTo("constraint");
        assertThat(badResult.path("structuredContent").path("issues").get(0).path("message").asText())
                .isEqualTo("At most 20 rooms");
    }

    @Test
    void recompute_asks_for_the_spec_on_a_cache_miss_and_accepts_it_when_resent() throws Exception {
        ObjectNode miss = MAPPER.createObjectNode().put("specHash", "sha256:unknown");
        miss.putObject("inputs");
        JsonNode missResult = tools.call(ModelCardTools.RECOMPUTE, miss);
        assertThat(missResult.path("structuredContent").path("error").asText()).isEqualTo("spec_unknown");

        ObjectNode resend = miss.deepCopy();
        resend.set("spec", MAPPER.readTree(QUOTE));
        ((ObjectNode) resend.get("inputs")).put("$.rooms", 3);
        JsonNode resent = tools.call(ModelCardTools.RECOMPUTE, resend);
        assertThat(resent.path("isError").asBoolean()).isFalse();
        assertThat(resent.path("structuredContent").path("state").path("price").asInt()).isEqualTo(90);
        assertThat(resent.path("structuredContent").path("specHash").asText()).startsWith("sha256:");
    }

    @Test
    void non_canonical_input_keys_are_refused() throws Exception {
        ObjectNode args = MAPPER.createObjectNode();
        args.set("spec", MAPPER.readTree(QUOTE));
        args.putObject("inputs").put("rooms", 4);

        JsonNode result = tools.call(ModelCardTools.RENDER_MODEL, args);

        assertThat(result.path("isError").asBoolean()).isTrue();
        assertThat(result.path("content").get(0).path("text").asText()).contains("canonical");
    }

    @Test
    void the_guide_covers_the_spec_format_and_card_notes() {
        String text = tools.call(ModelCardTools.SPEC_GUIDE, null).path("content").get(0).path("text").asText();
        assertThat(text).contains("render_model").contains("viewDefinition");
    }

    @Test
    void tool_definitions_carry_the_card_link_only_with_ui() {
        JsonNode withUi = tools.toolDefinitions(true);
        assertThat(names(withUi)).containsExactly(ModelCardTools.SPEC_GUIDE, ModelCardTools.RENDER_MODEL,
                ModelCardTools.RECOMPUTE);
        assertThat(withUi.get(1).path("_meta").path("ui").path("resourceUri").asText())
                .isEqualTo(ModelCardTools.CARD_URI);
        assertThat(withUi.get(2).path("_meta").path("ui").path("visibility").get(0).asText()).isEqualTo("app");

        JsonNode withoutUi = tools.toolDefinitions(false);
        assertThat(names(withoutUi)).containsExactly(ModelCardTools.SPEC_GUIDE, ModelCardTools.RENDER_MODEL);
        assertThat(withoutUi.get(1).has("_meta")).isFalse();
    }

    @Test
    void a_build_without_the_card_offers_no_ui() {
        ModelCardTools noCard = new ModelCardTools(new StatelessRunner(), MAPPER, ModelCardTools.Limits.DEFAULT, null);
        assertThat(names(noCard.toolDefinitions(true))).doesNotContain(ModelCardTools.RECOMPUTE);
        assertThat(noCard.resourceDescriptor()).isNull();
        assertThat(noCard.readResource(ModelCardTools.CARD_URI)).isNull();
    }

    @Test
    void the_card_resource_is_served_with_the_mcp_app_mime_type_and_no_network() {
        JsonNode read = tools.readResource(ModelCardTools.CARD_URI);
        JsonNode item = read.path("contents").get(0);
        assertThat(item.path("mimeType").asText()).isEqualTo("text/html;profile=mcp-app");
        assertThat(item.path("text").asText()).isEqualTo("<html>card</html>");
        assertThat(item.path("_meta").path("ui").path("csp").path("connectDomains")).isEmpty();
        assertThat(tools.readResource("ui://other")).isNull();
    }

    @Test
    void ui_support_follows_the_client_extensions() throws Exception {
        assertThat(ModelCardTools.clientSupportsUi(MAPPER.readTree("{\"capabilities\": {}}"))).isTrue();
        assertThat(ModelCardTools.clientSupportsUi(MAPPER.readTree(
                "{\"capabilities\": {\"extensions\": {}}}"))).isFalse();
        assertThat(ModelCardTools.clientSupportsUi(MAPPER.readTree(
                "{\"capabilities\": {\"extensions\": {\"io.modelcontextprotocol/ui\": "
                        + "{\"mimeTypes\": [\"text/html;profile=mcp-app\"]}}}}"))).isTrue();
        assertThat(ModelCardTools.clientSupportsUi(MAPPER.readTree(
                "{\"capabilities\": {\"extensions\": {\"io.modelcontextprotocol/ui\": "
                        + "{\"mimeTypes\": [\"text/other\"]}}}}"))).isFalse();
    }

    @Test
    void mcp_server_lists_and_dispatches_the_card_tools() throws Exception {
        McpServer server = new McpServer(new ModelService(new ModelRegistry(), new InMemoryBlobStore()), MAPPER);

        ObjectNode init = MAPPER.createObjectNode().put("jsonrpc", "2.0").put("id", 1).put("method", "initialize");
        init.putObject("params").put("protocolVersion", "2025-11-25").putObject("capabilities").putObject("extensions");
        server.handleMessage(init);
        JsonNode list = server.handleMessage(MAPPER.createObjectNode().put("jsonrpc", "2.0").put("id", 2)
                .put("method", "tools/list"));
        List<String> listed = names(list.path("result").path("tools"));
        assertThat(listed).contains("create_model", ModelCardTools.RENDER_MODEL, ModelCardTools.SPEC_GUIDE)
                .doesNotContain(ModelCardTools.RECOMPUTE);

        ObjectNode call = MAPPER.createObjectNode().put("jsonrpc", "2.0").put("id", 3).put("method", "tools/call");
        ObjectNode params = call.putObject("params").put("name", ModelCardTools.RENDER_MODEL);
        params.putObject("arguments").set("spec", MAPPER.readTree(QUOTE));
        JsonNode called = server.handleMessage(call);
        assertThat(called.path("result").path("structuredContent").path("outputs").path("$.price").asInt())
                .isEqualTo(60);
    }

    private static List<String> names(JsonNode tools) {
        List<String> names = new ArrayList<>();
        tools.forEach(t -> names.add(t.path("name").asText()));
        return names;
    }
}
