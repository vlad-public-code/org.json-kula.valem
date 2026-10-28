package org.json_kula.valem.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.json_kula.valem.core.blob.InMemoryBlobStore;
import org.json_kula.valem.core.model.ModelSpec;
import org.json_kula.valem.service.ModelRegistry;
import org.json_kula.valem.service.ModelService;
import org.json_kula.valem.service.StatelessRunner;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class StoredModelCardsTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final String QUOTE = """
            {
              "id": "q1",
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
              "viewDefinition": {"defaultView": "main", "views": [{"id": "main", "label": "Quote",
                "components": [{"id": "rooms", "type": "numericField", "label": "Rooms", "bind": "$.rooms"}]}]}
            }
            """;

    private ModelService service;
    private StoredModelCards cards;

    @BeforeEach
    void setUp() throws Exception {
        service = new ModelService(new ModelRegistry(), new InMemoryBlobStore());
        service.createModel(MAPPER.readValue(QUOTE, ModelSpec.class));
        ModelCardTools tools = new ModelCardTools(new StatelessRunner(), MAPPER, ModelCardTools.Limits.DEFAULT, "<html>card</html>");
        cards = new StoredModelCards(service, tools, MAPPER);
    }

    private JsonNode call(String name, String argsJson) throws Exception {
        return cards.call(name, MAPPER.readTree(argsJson));
    }

    @Test
    void show_model_carries_the_model_id_its_state_and_the_rules_that_fail_now() throws Exception {
        JsonNode result = call(StoredModelCards.SHOW, "{\"id\":\"q1\"}");

        assertThat(result.path("isError").asBoolean()).isFalse();
        assertThat(result.path("content").get(0).path("text").asText())
                .contains("$.price = 60").contains("Below the minimum order of 80");
        JsonNode card = result.path("_meta").path("valem/card");
        assertThat(card.path("modelId").asText()).isEqualTo("q1");
        assertThat(card.path("state").path("price").asInt()).isEqualTo(60);
        assertThat(card.path("spec").path("id").asText()).isEqualTo("q1");
        assertThat(card.path("flags").get(0).path("id").asText()).isEqualTo("min-order");
        assertThat(card.path("graph").path("nodes").findValuesAsText("key")).contains("$.price", "$.rooms");
    }

    @Test
    void an_edit_is_a_mutate_on_the_model_so_get_state_sees_it() throws Exception {
        String hash = call(StoredModelCards.SHOW, "{\"id\":\"q1\"}").path("_meta").path("valem/card").path("specHash").asText();

        JsonNode out = call(StoredModelCards.EDIT,
                "{\"id\":\"q1\",\"specHash\":\"" + hash + "\",\"mutations\":{\"$.rooms\":4}}").path("structuredContent");

        assertThat(out.path("state").path("price").asInt()).isEqualTo(120);
        assertThat(out.path("flags")).as("the minimum is met now").isEmpty();
        assertThat(out.has("spec")).as("unchanged spec is not resent").isFalse();
        assertThat(service.getState("q1", null).path("rooms").asInt()).isEqualTo(4);
    }

    @Test
    void a_rollback_rule_rejects_the_edit_and_leaves_the_model_as_it_was() throws Exception {
        JsonNode out = call(StoredModelCards.EDIT, "{\"id\":\"q1\",\"mutations\":{\"$.rooms\":25}}").path("structuredContent");

        assertThat(out.path("rejected").asText()).isEqualTo("constraint");
        assertThat(out.path("issues").get(0).path("message").asText()).isEqualTo("At most 20 rooms");
        assertThat(service.getState("q1", null).path("rooms").asInt()).isEqualTo(2);
    }

    @Test
    void an_empty_edit_reads_back_changes_made_elsewhere_with_the_new_spec_when_it_changed() throws Exception {
        String hash = call(StoredModelCards.SHOW, "{\"id\":\"q1\"}").path("_meta").path("valem/card").path("specHash").asText();
        service.mutate("q1", Map.of("$.rooms", MAPPER.getNodeFactory().numberNode(3)));

        JsonNode same = call(StoredModelCards.EDIT, "{\"id\":\"q1\",\"specHash\":\"" + hash + "\"}").path("structuredContent");
        assertThat(same.path("state").path("price").asInt()).isEqualTo(90);
        assertThat(same.has("spec")).isFalse();

        JsonNode stale = call(StoredModelCards.EDIT, "{\"id\":\"q1\",\"specHash\":\"sha256:old\"}").path("structuredContent");
        assertThat(stale.path("spec").path("id").asText()).isEqualTo("q1");
        assertThat(stale.has("graph")).isTrue();
    }

    @Test
    void an_unknown_model_or_a_model_without_a_view_is_a_tool_error() throws Exception {
        assertThat(call(StoredModelCards.SHOW, "{\"id\":\"nope\"}").path("content").get(0).path("text").asText())
                .contains("No model 'nope'");

        ObjectNode bare = (ObjectNode) MAPPER.readTree(QUOTE);
        bare.put("id", "bare");
        bare.remove("viewDefinition");
        service.createModel(MAPPER.treeToValue(bare, ModelSpec.class));
        JsonNode r = call(StoredModelCards.SHOW, "{\"id\":\"bare\"}");
        assertThat(r.path("isError").asBoolean()).isTrue();
        assertThat(r.path("content").get(0).path("text").asText()).contains("no viewDefinition");
    }

    @Test
    void the_server_offers_show_model_to_a_ui_client_and_routes_its_calls() throws Exception {
        assumeTrue(ModelCardTools.loadCard() != null, "the card is built into this jar");
        McpServer server = new McpServer(service, MAPPER);
        server.handle(MAPPER.readTree("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{"
                + "\"protocolVersion\":\"2025-11-25\",\"capabilities\":{\"extensions\":{\"io.modelcontextprotocol/ui\":"
                + "{\"mimeTypes\":[\"text/html;profile=mcp-app\"]}}}}}"));
        JsonNode tools = server.handle(MAPPER.readTree("{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/list\"}"))
                .path("result").path("tools");
        assertThat(tools.findValuesAsText("name")).contains(StoredModelCards.SHOW, StoredModelCards.EDIT);

        JsonNode shown = server.handle(MAPPER.readTree("{\"jsonrpc\":\"2.0\",\"id\":3,\"method\":\"tools/call\","
                + "\"params\":{\"name\":\"show_model\",\"arguments\":{\"id\":\"q1\"}}}")).path("result");
        assertThat(shown.path("_meta").path("valem/card").path("modelId").asText()).isEqualTo("q1");
    }

    @Test
    void the_tools_are_listed_only_to_a_client_that_shows_the_card() {
        assertThat(cards.toolDefinitions(false)).isEmpty();
        JsonNode defs = cards.toolDefinitions(true);
        assertThat(defs.findValuesAsText("name")).containsExactly(StoredModelCards.SHOW, StoredModelCards.EDIT);
        assertThat(defs.get(0).path("_meta").path("ui").path("resourceUri").asText()).isEqualTo(ModelCardTools.CARD_URI);
        assertThat(defs.get(1).path("_meta").path("ui").path("visibility").get(0).asText()).isEqualTo("app");
    }
}
