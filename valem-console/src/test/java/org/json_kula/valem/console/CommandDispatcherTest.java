package org.json_kula.valem.console;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.json_kula.valem.core.blob.InMemoryBlobStore;
import org.json_kula.valem.core.state.Snapshot;
import org.json_kula.valem.service.ModelRegistry;
import org.json_kula.valem.service.ModelService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CommandDispatcherTest {

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
            .configure(DeserializationFeature.READ_ENUMS_USING_TO_STRING, false)
            .configure(SerializationFeature.FAIL_ON_EMPTY_BEANS, false);

    private static final String SIMPLE_SPEC = """
            {
              "id": "cmd-test",
              "version": "1.0.0",
              "schema": {},
              "derivations": [
                {"path": "$.total", "expr": "price * qty"}
              ],
              "constraints": [], "actions": [], "metaDerivations": [], "tests": []
            }
            """;

    private CommandDispatcher dispatcher;

    @BeforeEach
    void setUp() {
        ModelService service = new ModelService(new ModelRegistry(), new InMemoryBlobStore());
        dispatcher = new CommandDispatcher(service, MAPPER);
    }

    // ── meta commands ──────────────────────────────────────────────────────────

    @Test
    void help_returns_command_list() throws Exception {
        Object result = dispatcher.dispatch(cmd("{\"cmd\":\"help\"}"));
        assertThat(result).isInstanceOf(List.class);
        assertThat((List<?>) result).anyMatch(s -> s.toString().contains("list-models"));
    }

    @Test
    void unknown_command_throws_IllegalArgumentException() {
        assertThatThrownBy(() -> dispatcher.dispatch(cmd("{\"cmd\":\"no-such-command\"}")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Unknown command");
    }

    @Test
    void missing_required_field_throws_IllegalArgumentException() {
        // 'create-model' requires 'spec' field
        assertThatThrownBy(() -> dispatcher.dispatch(cmd("{\"cmd\":\"create-model\"}")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("spec");
    }

    // ── list-models ────────────────────────────────────────────────────────────

    @Test
    void list_models_initially_empty() throws Exception {
        Object result = dispatcher.dispatch(cmd("{\"cmd\":\"list-models\"}"));
        assertThat(result).isInstanceOf(List.class);
        assertThat((List<?>) result).isEmpty();
    }

    // ── create-model / get-info / get-spec ────────────────────────────────────

    @Test
    void create_model_returns_id_and_created_status() throws Exception {
        Object result = dispatcher.dispatch(createCmd(SIMPLE_SPEC));
        assertThat(result).isInstanceOf(java.util.Map.class);
        @SuppressWarnings("unchecked")
        java.util.Map<String, Object> map = (java.util.Map<String, Object>) result;
        assertThat(map.get("id")).isEqualTo("cmd-test");
        assertThat(map.get("status")).isEqualTo("created");
    }

    @Test
    void create_model_duplicate_throws() throws Exception {
        dispatcher.dispatch(createCmd(SIMPLE_SPEC));
        assertThatThrownBy(() -> dispatcher.dispatch(createCmd(SIMPLE_SPEC)))
                .isInstanceOf(org.json_kula.valem.service.ModelAlreadyExistsException.class);
    }

    @Test
    void get_info_returns_derivation_and_constraint_counts() throws Exception {
        dispatcher.dispatch(createCmd(SIMPLE_SPEC));
        Object result = dispatcher.dispatch(cmd("{\"cmd\":\"get-info\",\"id\":\"cmd-test\"}"));
        JsonNode info = MAPPER.valueToTree(result);
        assertThat(info.path("id").asText()).isEqualTo("cmd-test");
        assertThat(info.path("derivationCount").asInt()).isEqualTo(1);
        assertThat(info.path("constraintCount").asInt()).isEqualTo(0);
    }

    @Test
    void get_spec_returns_spec_with_id() throws Exception {
        dispatcher.dispatch(createCmd(SIMPLE_SPEC));
        Object result = dispatcher.dispatch(cmd("{\"cmd\":\"get-spec\",\"id\":\"cmd-test\"}"));
        JsonNode spec = MAPPER.valueToTree(result);
        assertThat(spec.path("id").asText()).isEqualTo("cmd-test");
    }

    // ── mutate / get-state / get-field ────────────────────────────────────────

    @Test
    void mutate_triggers_derivation_and_returns_success() throws Exception {
        dispatcher.dispatch(createCmd(SIMPLE_SPEC));

        ObjectNode mutateCmd = MAPPER.createObjectNode();
        mutateCmd.put("cmd", "mutate");
        mutateCmd.put("id", "cmd-test");
        ObjectNode mutations = mutateCmd.putObject("mutations");
        mutations.put("$.price", 10.0);
        mutations.put("$.qty", 3);

        Object result = dispatcher.dispatch(mutateCmd);
        JsonNode r = MAPPER.valueToTree(result);
        assertThat(r.path("success").asBoolean()).isTrue();
        assertThat(r.path("derivedUpdated").isArray()).isTrue();
    }

    @Test
    void get_state_reflects_base_and_derived_fields() throws Exception {
        dispatcher.dispatch(createCmd(SIMPLE_SPEC));

        ObjectNode mutateCmd = MAPPER.createObjectNode();
        mutateCmd.put("cmd", "mutate");
        mutateCmd.put("id", "cmd-test");
        ObjectNode mutations = mutateCmd.putObject("mutations");
        mutations.put("$.price", 5.0);
        mutations.put("$.qty", 4);
        dispatcher.dispatch(mutateCmd);

        Object state = dispatcher.dispatch(cmd("{\"cmd\":\"get-state\",\"id\":\"cmd-test\"}"));
        JsonNode s = MAPPER.valueToTree(state);
        assertThat(s.path("price").asDouble()).isEqualTo(5.0);
        assertThat(s.path("qty").asInt()).isEqualTo(4);
        assertThat(s.path("total").asDouble()).isEqualTo(20.0);
    }

    @Test
    void get_field_returns_derived_value() throws Exception {
        dispatcher.dispatch(createCmd(SIMPLE_SPEC));

        ObjectNode mutateCmd = MAPPER.createObjectNode();
        mutateCmd.put("cmd", "mutate");
        mutateCmd.put("id", "cmd-test");
        mutateCmd.putObject("mutations").put("$.price", 7.0).put("$.qty", 2);
        dispatcher.dispatch(mutateCmd);

        Object val = dispatcher.dispatch(cmd("{\"cmd\":\"get-field\",\"id\":\"cmd-test\",\"path\":\"$.total\"}"));
        assertThat(MAPPER.valueToTree(val).asDouble()).isEqualTo(14.0);
    }

    // ── get-history ────────────────────────────────────────────────────────────

    @Test
    void get_history_returns_timestamp_after_mutation() throws Exception {
        dispatcher.dispatch(createCmd(SIMPLE_SPEC));

        ObjectNode mutateCmd = MAPPER.createObjectNode();
        mutateCmd.put("cmd", "mutate");
        mutateCmd.put("id", "cmd-test");
        mutateCmd.putObject("mutations").put("$.price", 1.0);
        dispatcher.dispatch(mutateCmd);

        Object history = dispatcher.dispatch(cmd("{\"cmd\":\"get-history\",\"id\":\"cmd-test\"}"));
        assertThat((List<?>) history).hasSize(1);
    }

    // ── patch-mutate ───────────────────────────────────────────────────────────

    @Test
    void patch_mutate_applies_rfc6902_replace_operation() throws Exception {
        dispatcher.dispatch(createCmd(SIMPLE_SPEC));

        // seed a value first
        ObjectNode mutateCmd = MAPPER.createObjectNode();
        mutateCmd.put("cmd", "mutate");
        mutateCmd.put("id", "cmd-test");
        mutateCmd.putObject("mutations").put("$.price", 5.0);
        dispatcher.dispatch(mutateCmd);

        ObjectNode patchCmd = MAPPER.createObjectNode();
        patchCmd.put("cmd", "patch-mutate");
        patchCmd.put("id", "cmd-test");
        patchCmd.putArray("patch").addObject()
                .put("op", "replace").put("path", "/price").put("value", 99.0);
        dispatcher.dispatch(patchCmd);

        Object state = dispatcher.dispatch(cmd("{\"cmd\":\"get-state\",\"id\":\"cmd-test\"}"));
        assertThat(MAPPER.valueToTree(state).path("price").asDouble()).isEqualTo(99.0);
    }

    // ── snapshot / restore ─────────────────────────────────────────────────────

    @Test
    void snapshot_and_restore_via_commands() throws Exception {
        dispatcher.dispatch(createCmd(SIMPLE_SPEC));

        // Set price=10
        ObjectNode m1 = MAPPER.createObjectNode();
        m1.put("cmd", "mutate"); m1.put("id", "cmd-test");
        m1.putObject("mutations").put("$.price", 10.0);
        dispatcher.dispatch(m1);

        // Take snapshot
        Snapshot snap = (Snapshot) dispatcher.dispatch(cmd("{\"cmd\":\"snapshot\",\"id\":\"cmd-test\"}"));
        assertThat(snap).isNotNull();

        // Change price to 99
        ObjectNode m2 = MAPPER.createObjectNode();
        m2.put("cmd", "mutate"); m2.put("id", "cmd-test");
        m2.putObject("mutations").put("$.price", 99.0);
        dispatcher.dispatch(m2);

        // Restore
        ObjectNode restoreCmd = MAPPER.createObjectNode();
        restoreCmd.put("cmd", "restore");
        restoreCmd.put("id", "cmd-test");
        restoreCmd.set("snapshot", MAPPER.valueToTree(snap));
        dispatcher.dispatch(restoreCmd);

        // Price should be back to 10
        Object state = dispatcher.dispatch(cmd("{\"cmd\":\"get-state\",\"id\":\"cmd-test\"}"));
        assertThat(MAPPER.valueToTree(state).path("price").asDouble()).isEqualTo(10.0);
    }

    // ── evolve-spec ────────────────────────────────────────────────────────────

    @Test
    void evolve_spec_adds_derivation() throws Exception {
        dispatcher.dispatch(createCmd(SIMPLE_SPEC));

        ObjectNode evolveCmd = MAPPER.createObjectNode();
        evolveCmd.put("cmd", "evolve-spec");
        evolveCmd.put("id", "cmd-test");
        evolveCmd.putObject("evolution")
                .putArray("upsertDerivations")
                .addObject()
                .put("path", "$.doubleTotal")
                .put("expr", "total * 2");
        dispatcher.dispatch(evolveCmd);

        Object info = dispatcher.dispatch(cmd("{\"cmd\":\"get-info\",\"id\":\"cmd-test\"}"));
        assertThat(MAPPER.valueToTree(info).path("derivationCount").asInt()).isEqualTo(2);
    }

    // ── delete-model ───────────────────────────────────────────────────────────

    @Test
    void delete_model_removes_it_from_list() throws Exception {
        dispatcher.dispatch(createCmd(SIMPLE_SPEC));
        assertThat((List<?>) dispatcher.dispatch(cmd("{\"cmd\":\"list-models\"}"))).hasSize(1);

        Object del = dispatcher.dispatch(cmd("{\"cmd\":\"delete-model\",\"id\":\"cmd-test\"}"));
        assertThat(MAPPER.valueToTree(del).path("deleted").asBoolean()).isTrue();
        assertThat((List<?>) dispatcher.dispatch(cmd("{\"cmd\":\"list-models\"}"))).isEmpty();
    }

    // ── blob ───────────────────────────────────────────────────────────────────

    @Test
    void upload_and_download_blob_round_trip() throws Exception {
        byte[] data = "blob content".getBytes(StandardCharsets.UTF_8);
        String encoded = Base64.getEncoder().encodeToString(data);

        ObjectNode uploadCmd = MAPPER.createObjectNode();
        uploadCmd.put("cmd", "upload-blob");
        uploadCmd.put("data", encoded);
        uploadCmd.put("mediaType", "text/plain");
        Object uploaded = dispatcher.dispatch(uploadCmd);
        String blobId = MAPPER.valueToTree(uploaded).path("$blobId").asText();
        assertThat(blobId).startsWith("sha256:");

        ObjectNode downloadCmd = MAPPER.createObjectNode();
        downloadCmd.put("cmd", "get-blob");
        downloadCmd.put("blobId", blobId);
        Object downloaded = dispatcher.dispatch(downloadCmd);
        String returnedData = MAPPER.valueToTree(downloaded).path("data").asText();
        assertThat(Base64.getDecoder().decode(returnedData)).isEqualTo(data);
    }

    // ── get-view ───────────────────────────────────────────────────────────────

    private static final String SPEC_WITH_VIEW = """
            {
              "id": "view-cmd-test",
              "version": "1.0.0",
              "schema": {},
              "derivations": [],
              "constraints": [], "actions": [], "metaDerivations": [], "tests": [],
              "defaultValues": [{"path": "$", "expr": "{ \\"qty\\": 5 }"}],
              "viewDefinition": {
                "views": [
                  {
                    "id": "main",
                    "label": "Main",
                    "layout": "vertical",
                    "components": [
                      {"id": "qtyField", "type": "numericField", "label": "Quantity", "bind": "$.qty"}
                    ]
                  }
                ],
                "defaultView": "main"
              }
            }
            """;

    @Test
    void get_view_returns_evaluated_view_with_bound_value() throws Exception {
        dispatcher.dispatch(createCmd(SPEC_WITH_VIEW));

        Object result = dispatcher.dispatch(cmd("{\"cmd\":\"get-view\",\"id\":\"view-cmd-test\"}"));
        JsonNode view = MAPPER.valueToTree(result);

        assertThat(view.path("viewId").asText()).isEqualTo("main");
        assertThat(view.path("modelId").asText()).isEqualTo("view-cmd-test");
        assertThat(view.path("components").isArray()).isTrue();
        assertThat(view.path("components").size()).isEqualTo(1);

        JsonNode qtyField = view.path("components").get(0);
        assertThat(qtyField.path("id").asText()).isEqualTo("qtyField");
        assertThat(qtyField.path("value").asInt()).isEqualTo(5);
        assertThat(qtyField.path("visible").isMissingNode()).isTrue();   // visible=true is omitted (default)
        assertThat(qtyField.path("readOnly").isMissingNode()).isTrue();  // readOnly=false is omitted (default)
    }

    @Test
    void get_view_with_named_view_id() throws Exception {
        dispatcher.dispatch(createCmd(SPEC_WITH_VIEW));
        Object result = dispatcher.dispatch(cmd("{\"cmd\":\"get-view\",\"id\":\"view-cmd-test\",\"viewId\":\"main\"}"));
        JsonNode view = MAPPER.valueToTree(result);
        assertThat(view.path("viewId").asText()).isEqualTo("main");
    }

    @Test
    void get_view_on_model_without_view_definition_throws() throws Exception {
        dispatcher.dispatch(createCmd(SIMPLE_SPEC));
        assertThatThrownBy(() -> dispatcher.dispatch(cmd("{\"cmd\":\"get-view\",\"id\":\"cmd-test\"}")))
                .isInstanceOf(Exception.class);
    }

    // ── get-view: daily-wellness new component types ───────────────────────────

    private static final String DAILY_WELLNESS_SPEC = """
            {
              "id": "dw-cmd-test",
              "version": "1.0.0",
              "schema": { "type": "object",
                "properties": {
                  "energyLevel":          { "type": "integer" },
                  "moodLevel":            { "type": "integer" },
                  "checkInTime":          { "type": ["string","null"] },
                  "photoRef":             { "type": ["array","null"], "items": {"type":"object"}, "maxItems": 3 },
                  "weeklyStepsCompleted": { "type": "integer" },
                  "weeklyStepGoal":       { "type": "integer" },
                  "wellnessScore":        { "type": "integer", "readOnly": true },
                  "weeklyProgress":       { "type": "integer", "readOnly": true }
                }
              },
              "derivations": [
                { "path": "$.wellnessScore", "expr": "(energyLevel + moodLevel) * 5" },
                { "path": "$.weeklyProgress",
                  "expr": "$min([$round(weeklyStepsCompleted / weeklyStepGoal * 100), 100])" }
              ],
              "constraints": [], "actions": [], "metaDerivations": [], "tests": [],
              "defaultValues": [ { "path": "$", "expr": "{ \\"energyLevel\\": 7, \\"moodLevel\\": 7, \\"checkInTime\\": \\"08:30\\", \\"weeklyStepGoal\\": 70000, \\"weeklyStepsCompleted\\": 35000 }" } ],
              "viewDefinition": {
                "renderer": "builtin",
                "defaultView": "main",
                "views": [{
                  "id": "main", "label": "Daily Wellness", "layout": "vertical",
                  "components": [
                    { "id": "energySlider",   "type": "sliderField",    "label": "Energy",
                      "bind": "$.energyLevel", "min": 1, "max": 10, "step": 1 },
                    { "id": "moodSlider",     "type": "sliderField",    "label": "Mood",
                      "bind": "$.moodLevel",   "min": 1, "max": 10, "step": 1 },
                    { "id": "checkInTime",    "type": "timeField",      "label": "Check-in Time",
                      "bind": "$.checkInTime" },
                    { "id": "photoUpload",    "type": "fileUploadField", "label": "Photos",
                      "bind": "$.photoRef", "accept": "image/*", "multiple": true, "maxFiles": 3 },
                    { "id": "progressBar",    "type": "progressBar",    "label": "Weekly Steps",
                      "bind": "$.weeklyProgress", "min": 0, "max": 100,
                      "showValue": true, "format": "percent" }
                  ]
                }]
              }
            }
            """;

    @Test
    void get_view_daily_wellness_slider_fields_have_min_max_step() throws Exception {
        dispatcher.dispatch(createCmd(DAILY_WELLNESS_SPEC));

        Object result = dispatcher.dispatch(cmd("{\"cmd\":\"get-view\",\"id\":\"dw-cmd-test\"}"));
        JsonNode view = MAPPER.valueToTree(result);

        JsonNode comps = view.path("components");
        assertThat(view.path("viewId").asText()).isEqualTo("main");

        JsonNode energySlider = comps.get(0);
        assertThat(energySlider.path("id").asText()).isEqualTo("energySlider");
        assertThat(energySlider.path("type").asText()).isEqualTo("sliderField");
        assertThat(energySlider.path("min").asDouble()).isEqualTo(1.0);
        assertThat(energySlider.path("max").asDouble()).isEqualTo(10.0);
        assertThat(energySlider.path("step").asDouble()).isEqualTo(1.0);
        assertThat(energySlider.path("value").asInt()).isEqualTo(7);

        JsonNode moodSlider = comps.get(1);
        assertThat(moodSlider.path("type").asText()).isEqualTo("sliderField");
        assertThat(moodSlider.path("min").asDouble()).isEqualTo(1.0);
        assertThat(moodSlider.path("max").asDouble()).isEqualTo(10.0);
    }

    @Test
    void get_view_daily_wellness_time_field_returns_bound_value() throws Exception {
        dispatcher.dispatch(createCmd(DAILY_WELLNESS_SPEC));

        Object result = dispatcher.dispatch(cmd("{\"cmd\":\"get-view\",\"id\":\"dw-cmd-test\"}"));
        JsonNode view = MAPPER.valueToTree(result);

        JsonNode timeField = view.path("components").get(2);
        assertThat(timeField.path("id").asText()).isEqualTo("checkInTime");
        assertThat(timeField.path("type").asText()).isEqualTo("timeField");
        assertThat(timeField.path("value").asText()).isEqualTo("08:30");
        assertThat(timeField.path("visible").isMissingNode()).isTrue();   // visible=true is omitted (default)
        assertThat(timeField.path("readOnly").isMissingNode()).isTrue();  // readOnly=false is omitted (default)
    }

    @Test
    void get_view_daily_wellness_file_upload_field_has_accept() throws Exception {
        dispatcher.dispatch(createCmd(DAILY_WELLNESS_SPEC));

        Object result = dispatcher.dispatch(cmd("{\"cmd\":\"get-view\",\"id\":\"dw-cmd-test\"}"));
        JsonNode view = MAPPER.valueToTree(result);

        JsonNode fileField = view.path("components").get(3);
        assertThat(fileField.path("id").asText()).isEqualTo("photoUpload");
        assertThat(fileField.path("type").asText()).isEqualTo("fileUploadField");
        assertThat(fileField.path("accept").asText()).isEqualTo("image/*");
        assertThat(fileField.path("multiple").asBoolean()).isTrue();
        assertThat(fileField.path("maxFiles").asInt()).isEqualTo(3);
    }

    @Test
    void get_view_daily_wellness_progress_bar_has_display_fields_and_derived_value() throws Exception {
        dispatcher.dispatch(createCmd(DAILY_WELLNESS_SPEC));

        Object result = dispatcher.dispatch(cmd("{\"cmd\":\"get-view\",\"id\":\"dw-cmd-test\"}"));
        JsonNode view = MAPPER.valueToTree(result);

        JsonNode progressBar = view.path("components").get(4);
        assertThat(progressBar.path("id").asText()).isEqualTo("progressBar");
        assertThat(progressBar.path("type").asText()).isEqualTo("progressBar");
        assertThat(progressBar.path("min").asDouble()).isEqualTo(0.0);
        assertThat(progressBar.path("max").asDouble()).isEqualTo(100.0);
        assertThat(progressBar.path("showValue").asBoolean()).isTrue();
        assertThat(progressBar.path("format").asText()).isEqualTo("percent");
        // weeklyProgress = $min([$round(35000 / 70000 * 100), 100]) = 50
        assertThat(progressBar.path("value").asInt()).isEqualTo(50);
    }

    @Test
    void get_view_daily_wellness_all_five_components_present() throws Exception {
        dispatcher.dispatch(createCmd(DAILY_WELLNESS_SPEC));

        Object result = dispatcher.dispatch(cmd("{\"cmd\":\"get-view\",\"id\":\"dw-cmd-test\"}"));
        JsonNode view = MAPPER.valueToTree(result);
        assertThat(view.path("components").size()).isEqualTo(5);
    }

    @Test
    void get_view_bad_viewId_throws() throws Exception {
        dispatcher.dispatch(createCmd(SPEC_WITH_VIEW));
        assertThatThrownBy(() ->
                dispatcher.dispatch(cmd("{\"cmd\":\"get-view\",\"id\":\"view-cmd-test\",\"viewId\":\"nonexistent\"}")))
                .isInstanceOf(Exception.class);
    }

    // ── helpers ────────────────────────────────────────────────────────────────

    private ObjectNode cmd(String json) throws Exception {
        return (ObjectNode) MAPPER.readTree(json);
    }

    private ObjectNode createCmd(String specJson) throws Exception {
        ObjectNode cmd = MAPPER.createObjectNode();
        cmd.put("cmd", "create-model");
        cmd.set("spec", MAPPER.readTree(specJson));
        return cmd;
    }
}
