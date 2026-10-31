package org.json_kula.valem.core.graph;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.json_kula.valem.core.blob.InMemoryBlobStore;
import org.json_kula.valem.core.engine.ConstraintEvaluator;
import org.json_kula.valem.core.engine.EffectRequest;
import org.json_kula.valem.core.engine.ModelRuntime;
import org.json_kula.valem.core.model.ModelSpec;
import org.json_kula.valem.core.state.ModelState;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The agent-workflow examples (agent-report-pipeline, agent-task-board, agent-run-guardrails)
 * driven mutation by mutation. The embedded self-tests check the derived values for a given
 * state; these check what the self-tests cannot: which step the engine dispatches next, and
 * which writes the gates refuse (rolled back, state untouched).
 */
class AgentWorkflowExamplesTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    // ── agent-report-pipeline ────────────────────────────────────────────────

    @Test
    void report_pipeline_dispatches_each_step_from_state() throws Exception {
        ModelRuntime rt = load("agent-report-pipeline.json");

        var r = rt.mutate(writes("$.brief.topic", "Heat pumps", "$.brief.audience", "homeowners"));
        assertThat(effectIds(r)).containsExactly("do-research");

        // The research step folds back: the next step follows from state alone.
        r = rt.mutate(writes("$.research.notes", "COP 3-4", "$.research.fromBrief", "Heat pumps | for: homeowners"));
        assertThat(effectIds(r)).containsExactly("write-draft");

        r = rt.mutate(writes("$.draft.text", "Draft one", "$.draft.fromBrief", "Heat pumps | for: homeowners",
                "$.draft.version", 1));
        assertThat(effectIds(r)).containsExactly("review-draft");

        r = rt.mutate(writes("$.review.score", 9, "$.review.ofDraft", 1));
        assertThat(effectIds(r)).containsExactly("ask-for-approval");
        assertThat(rt.getValue("$.status.next").asText()).isEqualTo("approve");
    }

    @Test
    void report_pipeline_refuses_approval_without_a_passing_review() throws Exception {
        ModelRuntime rt = load("agent-report-pipeline.json");
        rt.mutate(writes("$.brief.topic", "Heat pumps", "$.brief.audience", "homeowners",
                "$.research.notes", "n", "$.research.fromBrief", "Heat pumps | for: homeowners",
                "$.draft.text", "d", "$.draft.fromBrief", "Heat pumps | for: homeowners", "$.draft.version", 1,
                "$.review.score", 5, "$.review.ofDraft", 1));

        assertThatThrownBy(() -> rt.mutate(writes("$.approval.approvedDraft", 1)))
                .isInstanceOf(ConstraintEvaluator.ConstraintViolationException.class);
        JsonNode approved = rt.getValue("$.approval.approvedDraft");
        assertThat(approved == null || approved.isNull() || approved.isMissingNode()).isTrue();
    }

    @Test
    void editing_the_brief_restarts_the_pipeline_without_any_event() throws Exception {
        ModelRuntime rt = load("agent-report-pipeline.json");
        rt.mutate(writes("$.brief.topic", "Heat pumps", "$.brief.audience", "homeowners",
                "$.research.notes", "n", "$.research.fromBrief", "Heat pumps | for: homeowners",
                "$.draft.text", "d", "$.draft.fromBrief", "Heat pumps | for: homeowners", "$.draft.version", 1,
                "$.review.score", 9, "$.review.ofDraft", 1, "$.approval.approvedDraft", 1));
        assertThat(rt.getValue("$.status.next").asText()).isEqualTo("done");

        var r = rt.mutate(writes("$.brief.audience", "installers"));
        assertThat(rt.getValue("$.status.approved").asBoolean()).isFalse();
        assertThat(effectIds(r)).containsExactly("do-research");
    }

    // ── agent-task-board ─────────────────────────────────────────────────────

    @Test
    void task_board_announces_newly_ready_work_as_dependencies_finish() throws Exception {
        ModelRuntime rt = load("agent-task-board.json");
        // Agents append a task by writing its fields; defaultValues fill status/dependsOn.
        var r = rt.mutate(writes(
                "$.tasks[0].id", "outline",
                "$.tasks[1].id", "data",
                "$.tasks[2].id", "write", "$.tasks[2].dependsOn", json("[\"outline\", \"data\"]")));
        assertThat(rt.getValue("$.tasks[0].status").asText()).isEqualTo("todo");
        assertThat(effectIds(r)).containsExactly("announce-ready-work");
        assertThat(rt.getValue("$.summary.readyIds")).isEqualTo(json("[\"outline\",\"data\"]"));

        rt.mutate(writes("$.tasks[0].status", "done", "$.tasks[0].owner", "planner", "$.tasks[0].output", "o"));
        r = rt.mutate(writes("$.tasks[1].status", "done", "$.tasks[1].owner", "analyst", "$.tasks[1].output", "d"));
        assertThat(rt.getValue("$.summary.readyIds")).isEqualTo(json("[\"write\"]"));
        assertThat(effectIds(r)).containsExactly("announce-ready-work");
    }

    @Test
    void task_board_refuses_claims_and_completions_the_plan_does_not_allow() throws Exception {
        ModelRuntime rt = load("agent-task-board.json");
        rt.mutate(writes(
                "$.tasks[0].id", "outline",
                "$.tasks[1].id", "write", "$.tasks[1].dependsOn", json("[\"outline\"]")));

        // write still waits on outline
        assertThatThrownBy(() -> rt.mutate(writes("$.tasks[1].status", "claimed", "$.tasks[1].owner", "writer")))
                .isInstanceOf(ConstraintEvaluator.ConstraintViolationException.class);
        // done without an output
        assertThatThrownBy(() -> rt.mutate(writes("$.tasks[0].status", "done")))
                .isInstanceOf(ConstraintEvaluator.ConstraintViolationException.class);
        // a dependency on a task that does not exist
        assertThatThrownBy(() -> rt.mutate(writes("$.tasks[1].dependsOn", json("[\"ghost\"]"))))
                .isInstanceOf(ConstraintEvaluator.ConstraintViolationException.class);

        assertThat(rt.getValue("$.tasks[1].status").asText()).isEqualTo("todo");
        assertThat(rt.getValue("$.tasks[0].status").asText()).isEqualTo("todo");
    }

    // ── agent-run-guardrails ─────────────────────────────────────────────────

    @Test
    void guardrails_refuse_a_step_that_would_break_the_budget() throws Exception {
        ModelRuntime rt = load("agent-run-guardrails.json");
        rt.mutate(Map.of("$.steps[0]", json("""
                { "kind": "llm", "model": "large", "inputTokens": 60000, "outputTokens": 10000 }""")));
        assertThat(rt.getValue("$.run.mode").asText()).isEqualTo("warn");

        assertThatThrownBy(() -> rt.mutate(Map.of("$.steps[1]", json("""
                { "kind": "llm", "model": "large", "inputTokens": 20000, "outputTokens": 2000 }"""))))
                .isInstanceOf(ConstraintEvaluator.ConstraintViolationException.class);
        assertThat(rt.getValue("$.usage.steps").asInt()).isEqualTo(1);
    }

    @Test
    void guardrails_time_out_freezes_the_step_budget() throws Exception {
        ModelRuntime rt = load("agent-run-guardrails.json");
        rt.mutate(Map.of("$.steps[0]", json("{ \"kind\": \"tool\", \"tool\": \"search\" }")));

        // What the time-limit timer folds back.
        var r = rt.mutate(writes("$.run.state", "timed_out", "$.run.maxSteps", 1));
        assertThat(rt.getValue("$.run.mode").asText()).isEqualTo("halt");
        assertThat(effectIds(r)).containsExactly("tell-agent-to-stop");

        assertThatThrownBy(() -> rt.mutate(Map.of("$.steps[1]", json("{ \"kind\": \"tool\", \"tool\": \"fetch\" }"))))
                .isInstanceOf(ConstraintEvaluator.ConstraintViolationException.class);
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private static ModelRuntime load(String name) throws Exception {
        ObjectNode raw = (ObjectNode) MAPPER.readTree(resolve(name));
        raw.remove("_name");
        raw.remove("_description");
        ModelSpec spec = MAPPER.treeToValue(raw, ModelSpec.class);
        CompiledModel cm = ModelSpecCompiler.compile(spec);
        ModelRuntime rt = new ModelRuntime(cm, new ModelState(cm, new InMemoryBlobStore()));
        rt.initialize();
        return rt;
    }

    private static List<String> effectIds(ModelRuntime.MutationResult r) {
        return r.dispatchedEffects().stream().map(EffectRequest::effectId).toList();
    }

    private static Map<String, JsonNode> writes(Object... kv) {
        Map<String, JsonNode> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            Object v = kv[i + 1];
            m.put((String) kv[i], v instanceof JsonNode n ? n : MAPPER.valueToTree(v));
        }
        return m;
    }

    private static JsonNode json(String s) {
        try { return MAPPER.readTree(s); } catch (Exception e) { throw new IllegalArgumentException(e); }
    }

    private static File resolve(String name) {
        for (String dir : new String[]{"../valem-ui/src/examples/", "valem-ui/src/examples/"}) {
            File f = new File(dir + name);
            if (f.exists()) return f;
        }
        throw new IllegalStateException(name + " not found relative to working dir");
    }
}
