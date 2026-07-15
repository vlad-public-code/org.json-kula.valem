package org.json_kula.valem.api.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class ViewControllerTest {

    @Autowired MockMvc mvc;

    // ── GET /models/{id}/view — default view ──────────────────────────────────

    @Test
    void get_default_view_returns_200_with_evaluated_components() throws Exception {
        createModel("vc-default-view", """
                {
                  "id": "vc-default-view", "schema": {},
                  "defaultValues": [{"path": "$", "expr": "{ \\"score\\": 10 }"}],
                  "viewDefinition": {
                    "views": [
                      {
                        "id": "main",
                        "label": "Main View",
                        "layout": "vertical",
                        "components": [
                          {"id": "scoreField", "type": "numericField", "label": "Score", "bind": "$.score"}
                        ]
                      }
                    ],
                    "defaultView": "main"
                  }
                }
                """);

        mvc.perform(get("/models/vc-default-view/view"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.modelId",  is("vc-default-view")))
                .andExpect(jsonPath("$.viewId",   is("main")))
                .andExpect(jsonPath("$.title",    is("Main View")))
                .andExpect(jsonPath("$.layout",   is("vertical")))
                .andExpect(jsonPath("$.components", hasSize(1)))
                .andExpect(jsonPath("$.components[0].id",       is("scoreField")))
                .andExpect(jsonPath("$.components[0].value",    is(10)))
                .andExpect(jsonPath("$.components[0].visible").doesNotExist())
                .andExpect(jsonPath("$.components[0].readOnly").doesNotExist());
    }

    // ── GET /models/{id}/view/{viewId} — named view ───────────────────────────

    @Test
    void get_named_view_returns_200_with_correct_view() throws Exception {
        createModel("vc-named-view", """
                {
                  "id": "vc-named-view", "schema": {},
                  "viewDefinition": {
                    "views": [
                      {
                        "id": "summary",
                        "label": "Summary",
                        "layout": "vertical",
                        "components": [
                          {"id": "sumField", "type": "label", "label": "Summary label"}
                        ]
                      },
                      {
                        "id": "detail",
                        "label": "Detail",
                        "layout": "horizontal",
                        "components": [
                          {"id": "detField", "type": "textField", "label": "Detail field"}
                        ]
                      }
                    ],
                    "defaultView": "summary"
                  }
                }
                """);

        mvc.perform(get("/models/vc-named-view/view/detail"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.viewId",  is("detail")))
                .andExpect(jsonPath("$.layout",  is("horizontal")))
                .andExpect(jsonPath("$.components", hasSize(1)))
                .andExpect(jsonPath("$.components[0].id", is("detField")));
    }

    @Test
    void get_named_view_with_bad_viewId_returns_404() throws Exception {
        createModel("vc-bad-viewid", """
                {
                  "id": "vc-bad-viewid", "schema": {},
                  "viewDefinition": {
                    "views": [ { "id": "main", "label": "Main", "components": [] } ],
                    "defaultView": "main"
                  }
                }
                """);

        mvc.perform(get("/models/vc-bad-viewid/view/no-such-view"))
                .andExpect(status().isNotFound());
    }

    // ── 404 cases ─────────────────────────────────────────────────────────────

    @Test
    void get_view_on_model_without_viewDefinition_returns_404() throws Exception {
        createModel("vc-no-view", """
                { "id": "vc-no-view", "schema": {} }
                """);

        mvc.perform(get("/models/vc-no-view/view"))
                .andExpect(status().isNotFound());
    }

    @Test
    void get_view_on_nonexistent_model_returns_404() throws Exception {
        mvc.perform(get("/models/vc-does-not-exist/view"))
                .andExpect(status().isNotFound());
    }

    // ── Text expression evaluation ────────────────────────────────────────────

    @Test
    void get_view_evaluates_jsonata_text_expression() throws Exception {
        createModel("vc-text-expr", """
                {
                  "id": "vc-text-expr", "schema": {},
                  "defaultValues": [{"path": "$", "expr": "{ \\"qty\\": 7 }"}],
                  "viewDefinition": {
                    "views": [
                      {
                        "id": "main", "label": "Main", "layout": "vertical",
                        "components": [
                          {"id": "lbl", "type": "staticText", "text": "$string(qty)"}
                        ]
                      }
                    ],
                    "defaultView": "main"
                  }
                }
                """);

        mvc.perform(get("/models/vc-text-expr/view"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.components[0].text", is("7")));
    }

    // ── daily-wellness example: all four new component types ─────────────────

    /** Returns the daily-wellness spec with the given model id embedded. */
    private static String dailyWellnessSpec(String id) {
        return DAILY_WELLNESS_SPEC_TEMPLATE.replace("DW_ID_PLACEHOLDER", id);
    }

    private static final String DAILY_WELLNESS_SPEC_TEMPLATE = """
            {
              "id": "DW_ID_PLACEHOLDER", "version": "1.0.0",
              "schema": { "type": "object",
                "properties": {
                  "energyLevel":          { "type": "integer" },
                  "moodLevel":            { "type": "integer" },
                  "checkInTime":          { "type": ["string","null"] },
                  "photoRef":             { "type": ["array","null"], "items": {"type":"object"}, "maxItems": 3 },
                  "weeklyStepGoal":       { "type": "integer" },
                  "weeklyStepsCompleted": { "type": "integer" },
                  "wellnessScore":        { "type": "integer", "readOnly": true },
                  "weeklyProgress":       { "type": "integer", "readOnly": true },
                  "statusLabel":          { "type": "string",  "readOnly": true }
                }
              },
              "derivations": [
                { "path": "$.wellnessScore",
                  "expr": "(energyLevel + moodLevel) * 5" },
                { "path": "$.weeklyProgress",
                  "expr": "$min([$round(weeklyStepsCompleted / weeklyStepGoal * 100), 100])" },
                { "path": "$.statusLabel",
                  "expr": "wellnessScore >= 80 ? \\"Excellent\\" : wellnessScore >= 60 ? \\"Good\\" : wellnessScore >= 40 ? \\"Fair\\" : \\"Low\\"" }
              ],
              "constraints": [], "actions": [], "metaDerivations": [], "tests": [],
              "defaultValues": [ { "path": "$", "expr": "{ \\"energyLevel\\": 7, \\"moodLevel\\": 7, \\"checkInTime\\": \\"09:00\\", \\"weeklyStepGoal\\": 70000, \\"weeklyStepsCompleted\\": 35000 }" } ],
              "viewDefinition": {
                "renderer": "builtin", "defaultView": "main",
                "views": [{
                  "id": "main", "label": "Daily Wellness Check-in", "layout": "vertical",
                  "components": [
                    { "id": "energySlider",      "type": "sliderField",     "label": "Energy Level",
                      "bind": "$.energyLevel",   "min": 1, "max": 10, "step": 1 },
                    { "id": "moodSlider",         "type": "sliderField",     "label": "Mood Level",
                      "bind": "$.moodLevel",     "min": 1, "max": 10, "step": 1 },
                    { "id": "checkInTimeField",   "type": "timeField",       "label": "Check-in Time",
                      "bind": "$.checkInTime" },
                    { "id": "photoUpload",        "type": "fileUploadField", "label": "Wellness Photos",
                      "bind": "$.photoRef",      "accept": "image/*", "multiple": true, "maxFiles": 3 },
                    { "id": "weeklyProgressBar",  "type": "progressBar",     "label": "Weekly Step Goal",
                      "bind": "$.weeklyProgress", "min": 0, "max": 100,
                      "showValue": true, "format": "percent" },
                    { "id": "wellnessLabel",      "type": "label",
                      "label": "Wellness Score",  "bind": "$.wellnessScore" }
                  ]
                }]
              }
            }
            """;

    @Test
    void daily_wellness_slider_fields_have_min_max_step_and_bound_values() throws Exception {
        createModel("vc-dw-sliders", dailyWellnessSpec("vc-dw-sliders"));

        mvc.perform(get("/models/vc-dw-sliders/view"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.viewId",   is("main")))
                .andExpect(jsonPath("$.title",    is("Daily Wellness Check-in")))
                .andExpect(jsonPath("$.components", hasSize(6)))
                // energySlider
                .andExpect(jsonPath("$.components[0].id",    is("energySlider")))
                .andExpect(jsonPath("$.components[0].type",  is("sliderField")))
                .andExpect(jsonPath("$.components[0].min",   is(1.0)))
                .andExpect(jsonPath("$.components[0].max",   is(10.0)))
                .andExpect(jsonPath("$.components[0].step",  is(1.0)))
                .andExpect(jsonPath("$.components[0].value", is(7)))
                // moodSlider
                .andExpect(jsonPath("$.components[1].id",    is("moodSlider")))
                .andExpect(jsonPath("$.components[1].type",  is("sliderField")))
                .andExpect(jsonPath("$.components[1].min",   is(1.0)))
                .andExpect(jsonPath("$.components[1].max",   is(10.0)))
                .andExpect(jsonPath("$.components[1].value", is(7)));
    }

    @Test
    void daily_wellness_time_field_returns_initial_check_in_time() throws Exception {
        createModel("vc-dw-time", dailyWellnessSpec("vc-dw-time"));

        mvc.perform(get("/models/vc-dw-time/view"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.components[2].id",      is("checkInTimeField")))
                .andExpect(jsonPath("$.components[2].type",    is("timeField")))
                .andExpect(jsonPath("$.components[2].value",   is("09:00")))
                .andExpect(jsonPath("$.components[2].visible").doesNotExist());
    }

    @Test
    void daily_wellness_file_upload_field_has_accept_and_no_initial_value() throws Exception {
        createModel("vc-dw-upload", dailyWellnessSpec("vc-dw-upload"));

        mvc.perform(get("/models/vc-dw-upload/view"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.components[3].id",       is("photoUpload")))
                .andExpect(jsonPath("$.components[3].type",     is("fileUploadField")))
                .andExpect(jsonPath("$.components[3].accept",   is("image/*")))
                .andExpect(jsonPath("$.components[3].multiple", is(true)))
                .andExpect(jsonPath("$.components[3].maxFiles", is(3)))
                .andExpect(jsonPath("$.components[3].value").doesNotExist());
    }

    @Test
    void daily_wellness_progress_bar_reflects_derived_weekly_progress() throws Exception {
        createModel("vc-dw-progress", dailyWellnessSpec("vc-dw-progress"));

        // Initial: weeklyStepsCompleted=35000, weeklyStepGoal=70000 → progress=50
        mvc.perform(get("/models/vc-dw-progress/view"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.components[4].id",        is("weeklyProgressBar")))
                .andExpect(jsonPath("$.components[4].type",      is("progressBar")))
                .andExpect(jsonPath("$.components[4].min",       is(0.0)))
                .andExpect(jsonPath("$.components[4].max",       is(100.0)))
                .andExpect(jsonPath("$.components[4].showValue", is(true)))
                .andExpect(jsonPath("$.components[4].format",    is("percent")))
                .andExpect(jsonPath("$.components[4].value",     is(50)));
    }

    @Test
    void daily_wellness_view_reflects_derived_wellness_score() throws Exception {
        createModel("vc-dw-score", dailyWellnessSpec("vc-dw-score"));

        // Initial energy=7, mood=7 → wellnessScore=70
        mvc.perform(get("/models/vc-dw-score/view"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.components[5].id",    is("wellnessLabel")))
                .andExpect(jsonPath("$.components[5].type",  is("label")))
                .andExpect(jsonPath("$.components[5].value", is(70)));
    }

    @Test
    void daily_wellness_wellness_score_updates_after_mutation() throws Exception {
        createModel("vc-dw-mutate", dailyWellnessSpec("vc-dw-mutate"));

        // Mutate to max energy and mood
        mvc.perform(post("/models/vc-dw-mutate/mutations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"$.energyLevel\": 10, \"$.moodLevel\": 10}"))
                .andExpect(status().isOk());

        // wellnessScore = (10+10)*5 = 100
        mvc.perform(get("/models/vc-dw-mutate/view"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.components[5].value", is(100)));
    }

    @Test
    void daily_wellness_progress_bar_caps_at_100_after_goal_exceeded() throws Exception {
        createModel("vc-dw-cap", dailyWellnessSpec("vc-dw-cap"));

        // Exceed the weekly goal
        mvc.perform(post("/models/vc-dw-cap/mutations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"$.weeklyStepsCompleted\": 90000}"))
                .andExpect(status().isOk());

        mvc.perform(get("/models/vc-dw-cap/view"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.components[4].value", is(100)));
    }

    @Test
    void daily_wellness_time_field_updates_after_check_in() throws Exception {
        createModel("vc-dw-time-upd", dailyWellnessSpec("vc-dw-time-upd"));

        mvc.perform(post("/models/vc-dw-time-upd/mutations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"$.checkInTime\": \"14:30\"}"))
                .andExpect(status().isOk());

        mvc.perform(get("/models/vc-dw-time-upd/view"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.components[2].value", is("14:30")));
    }

    // ── new component types ───────────────────────────────────────────────────

    @Test
    void slider_field_exposes_min_max_step_and_bound_value() throws Exception {
        createModel("vc-slider", """
                {
                  "id": "vc-slider", "schema": {},
                  "defaultValues": [{"path": "$", "expr": "{ \\"volume\\": 75 }"}],
                  "viewDefinition": {
                    "views": [{
                      "id": "main", "label": "Main", "layout": "vertical",
                      "components": [
                        {"id": "vol", "type": "sliderField", "label": "Volume",
                         "bind": "$.volume", "min": 0, "max": 100, "step": 5}
                      ]
                    }],
                    "defaultView": "main"
                  }
                }
                """);

        mvc.perform(get("/models/vc-slider/view"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.components[0].id",      is("vol")))
                .andExpect(jsonPath("$.components[0].type",    is("sliderField")))
                .andExpect(jsonPath("$.components[0].value",   is(75)))
                .andExpect(jsonPath("$.components[0].min",     is(0.0)))
                .andExpect(jsonPath("$.components[0].max",     is(100.0)))
                .andExpect(jsonPath("$.components[0].step",    is(5.0)));
    }

    @Test
    void slider_field_null_min_max_step_when_not_specified() throws Exception {
        createModel("vc-slider-norange", """
                {
                  "id": "vc-slider-norange", "schema": {},
                  "defaultValues": [{"path": "$", "expr": "{ \\"val\\": 50 }"}],
                  "viewDefinition": {
                    "views": [{
                      "id": "main", "label": "Main", "layout": "vertical",
                      "components": [
                        {"id": "s1", "type": "sliderField", "bind": "$.val"}
                      ]
                    }],
                    "defaultView": "main"
                  }
                }
                """);

        mvc.perform(get("/models/vc-slider-norange/view"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.components[0].min").doesNotExist())
                .andExpect(jsonPath("$.components[0].max").doesNotExist())
                .andExpect(jsonPath("$.components[0].step").doesNotExist());
    }

    @Test
    void time_field_returns_bound_time_value() throws Exception {
        createModel("vc-time", """
                {
                  "id": "vc-time", "schema": {},
                  "defaultValues": [{"path": "$", "expr": "{ \\"startTime\\": \\"14:30\\" }"}],
                  "viewDefinition": {
                    "views": [{
                      "id": "main", "label": "Main", "layout": "vertical",
                      "components": [
                        {"id": "start", "type": "timeField", "label": "Start Time",
                         "bind": "$.startTime"}
                      ]
                    }],
                    "defaultView": "main"
                  }
                }
                """);

        mvc.perform(get("/models/vc-time/view"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.components[0].id",    is("start")))
                .andExpect(jsonPath("$.components[0].type",  is("timeField")))
                .andExpect(jsonPath("$.components[0].value", is("14:30")))
                .andExpect(jsonPath("$.components[0].visible").doesNotExist())
                .andExpect(jsonPath("$.components[0].readOnly").doesNotExist());
    }

    @Test
    void time_field_with_no_initial_value_has_null_value() throws Exception {
        createModel("vc-time-empty", """
                {
                  "id": "vc-time-empty", "schema": {},
                  "viewDefinition": {
                    "views": [{
                      "id": "main", "label": "Main", "layout": "vertical",
                      "components": [
                        {"id": "t1", "type": "timeField", "bind": "$.startTime"}
                      ]
                    }],
                    "defaultView": "main"
                  }
                }
                """);

        mvc.perform(get("/models/vc-time-empty/view"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.components[0].value").doesNotExist());
    }

    @Test
    void file_upload_field_exposes_accept_attribute() throws Exception {
        createModel("vc-fileupload", """
                {
                  "id": "vc-fileupload", "schema": {},
                  "viewDefinition": {
                    "views": [{
                      "id": "main", "label": "Main", "layout": "vertical",
                      "components": [
                        {"id": "avatar", "type": "fileUploadField", "label": "Profile Photo",
                         "bind": "$.avatarBlob", "accept": "image/*"}
                      ]
                    }],
                    "defaultView": "main"
                  }
                }
                """);

        mvc.perform(get("/models/vc-fileupload/view"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.components[0].id",      is("avatar")))
                .andExpect(jsonPath("$.components[0].type",    is("fileUploadField")))
                .andExpect(jsonPath("$.components[0].accept",  is("image/*")))
                .andExpect(jsonPath("$.components[0].visible").doesNotExist());
    }

    @Test
    void file_upload_field_without_accept_has_no_accept_in_response() throws Exception {
        createModel("vc-fileupload-noaccept", """
                {
                  "id": "vc-fileupload-noaccept", "schema": {},
                  "viewDefinition": {
                    "views": [{
                      "id": "main", "label": "Main", "layout": "vertical",
                      "components": [
                        {"id": "f1", "type": "fileUploadField", "bind": "$.doc"}
                      ]
                    }],
                    "defaultView": "main"
                  }
                }
                """);

        mvc.perform(get("/models/vc-fileupload-noaccept/view"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.components[0].accept").doesNotExist());
    }

    @Test
    void progress_bar_exposes_display_fields_and_bound_value() throws Exception {
        createModel("vc-progress", """
                {
                  "id": "vc-progress", "schema": {},
                  "defaultValues": [{"path": "$", "expr": "{ \\"completionPct\\": 65 }"}],
                  "viewDefinition": {
                    "views": [{
                      "id": "main", "label": "Main", "layout": "vertical",
                      "components": [
                        {"id": "prog", "type": "progressBar", "label": "Completion",
                         "bind": "$.completionPct",
                         "min": 0, "max": 100, "showValue": true, "format": "percent"}
                      ]
                    }],
                    "defaultView": "main"
                  }
                }
                """);

        mvc.perform(get("/models/vc-progress/view"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.components[0].id",        is("prog")))
                .andExpect(jsonPath("$.components[0].type",      is("progressBar")))
                .andExpect(jsonPath("$.components[0].value",     is(65)))
                .andExpect(jsonPath("$.components[0].min",       is(0.0)))
                .andExpect(jsonPath("$.components[0].max",       is(100.0)))
                .andExpect(jsonPath("$.components[0].showValue", is(true)))
                .andExpect(jsonPath("$.components[0].format",    is("percent")));
    }

    @Test
    void progress_bar_value_format_variant() throws Exception {
        createModel("vc-progress-value", """
                {
                  "id": "vc-progress-value", "schema": {},
                  "defaultValues": [{"path": "$", "expr": "{ \\"done\\": 3 }"}],
                  "viewDefinition": {
                    "views": [{
                      "id": "main", "label": "Main", "layout": "vertical",
                      "components": [
                        {"id": "pb", "type": "progressBar", "bind": "$.done",
                         "min": 0, "max": 10, "showValue": false, "format": "value"}
                      ]
                    }],
                    "defaultView": "main"
                  }
                }
                """);

        mvc.perform(get("/models/vc-progress-value/view"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.components[0].value",     is(3)))
                .andExpect(jsonPath("$.components[0].max",       is(10.0)))
                .andExpect(jsonPath("$.components[0].showValue", is(false)))
                .andExpect(jsonPath("$.components[0].format",    is("value")));
    }

    // ── Traffic efficiency: large model + large view ──────────────────────────

    /**
     * Creates a model whose state exceeds 3 MB and a view whose full response exceeds 1 MB.
     * After mutating a single field with the X-View header, asserts that:
     * - viewDelta contains exactly the one changed component
     * - viewDelta byte length is less than 1% of the full view byte length
     */
    @Test
    void mutation_delta_is_small_fraction_of_full_view_for_large_model() throws Exception {
        final int FIELD_COUNT = 100;
        final String PAD = "X".repeat(32_000); // 32 KB per field → ~3.2 MB state + ~3.2 MB view

        // Build spec as a JSON string programmatically
        StringBuilder schemaProps  = new StringBuilder();
        StringBuilder seedMutation = new StringBuilder(); // seed via a bulk mutation, not a "$" rule
        StringBuilder components   = new StringBuilder();

        for (int i = 0; i < FIELD_COUNT; i++) {
            if (i > 0) { schemaProps.append(','); seedMutation.append(','); components.append(','); }
            schemaProps.append("\"f").append(i).append("\":{\"type\":\"string\"}");
            seedMutation.append("\"$.f").append(i).append("\":\"").append(PAD).append("\"");
            components.append("{\"id\":\"c").append(i)
                      .append("\",\"type\":\"textField\",\"label\":\"F\",\"bind\":\"$.f").append(i)
                      .append("\"}");
        }

        String specJson = "{\"id\":\"vc-large-delta\",\"schema\":{\"type\":\"object\",\"properties\":{"
                + schemaProps + "}},"
                + "\"derivations\":[],\"constraints\":[],\"actions\":[],\"metaDerivations\":[],\"tests\":[],"
                + "\"viewDefinition\":{\"defaultView\":\"main\","
                + "\"views\":[{\"id\":\"main\",\"label\":\"Main\",\"layout\":\"vertical\","
                + "\"components\":[" + components + "]}]}}";

        createModel("vc-large-delta", specJson);

        // Seed the large state via a bulk mutation. A 3.2 MB JSONata object literal would exceed the
        // compiled-expression size limit, so the seed cannot be a "$" defaultValues rule here.
        mvc.perform(post("/models/vc-large-delta/mutations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{" + seedMutation + "}"))
                .andExpect(status().isOk());

        // Capture full view size (should be >> 1 MB because each component carries the 32 KB value)
        String fullView = mvc.perform(get("/models/vc-large-delta/view"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(fullView.length())
                .as("full view must exceed 1 MB")
                .isGreaterThan(1_000_000);

        // Mutate exactly one field; X-View triggers delta computation
        String mutationBody = mvc.perform(post("/models/vc-large-delta/mutations")
                        .header("X-View", "main")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"$.f0\":\"changed\"}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        ObjectMapper om = new ObjectMapper();
        JsonNode delta = om.readTree(mutationBody).path("viewDelta");

        assertThat(delta.isMissingNode())
                .as("viewDelta must be present when X-View header is sent")
                .isFalse();
        assertThat(delta.size())
                .as("viewDelta must contain exactly the 1 changed component")
                .isEqualTo(1);
        assertThat(delta.has("c0"))
                .as("viewDelta must contain component c0 (bound to the mutated field)")
                .isTrue();
        assertThat(delta.path("c0").path("value").asText())
                .as("updated component must carry the new value")
                .isEqualTo("changed");

        // The mutation response (including delta) must be << full view
        assertThat(mutationBody.length())
                .as("mutation response with viewDelta must be less than 1%% of full view")
                .isLessThan(fullView.length() / 100);
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private void createModel(String id, String specJson) throws Exception {
        mvc.perform(post("/models")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(specJson))
                .andExpect(status().isCreated());
    }
}
