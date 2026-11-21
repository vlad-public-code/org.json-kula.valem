package org.json_kula.valem.mcp;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.json_kula.valem.core.blob.InMemoryBlobStore;
import org.json_kula.valem.service.ModelRegistry;
import org.json_kula.valem.service.ModelService;
import org.junit.jupiter.api.Test;

import java.io.File;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The walkthrough in docs/usage-scenarios/agent-state.md ("Walkthrough: an agent under guardrails"),
 * replayed through the MCP tools an agent calls. The agent never computes cost, budget or mode: it
 * records a step and reads back what the model derived. A person steers through the same writes a
 * card makes. Keeps the documented numbers honest.
 */
class AgentGuardrailsWalkthroughTest {

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
            .configure(SerializationFeature.FAIL_ON_EMPTY_BEANS, false);

    private final ToolRegistry registry =
            new ToolRegistry(new ModelService(new ModelRegistry(), new InMemoryBlobStore()), MAPPER);
    private String id;

    @Test
    void an_agent_runs_under_guardrails_and_a_person_steers() throws Exception {
        ObjectNode spec = (ObjectNode) MAPPER.readTree(resolve("agent-run-guardrails.json"));
        spec.remove("_name");
        spec.remove("_description");
        ObjectNode create = MAPPER.createObjectNode();
        create.set("spec", spec);
        ObjectNode created = registry.call("create_model", create);
        assertThat(created.path("isError").asBoolean()).isFalse();
        id = created.path("structuredContent").path("id").asText();

        mutate("{\"$.run.task\": \"Summarise the Q3 incidents for the ops review\"}");
        assertUsage(0, 0, "ok");

        assertThat(recordStep(llm("small", 20000, 2000)).path("isError").asBoolean()).isFalse();
        recordStep(tool("search"));
        recordStep(tool("fetch"));
        assertUsage(0.05, 3, "ok");
        recordStep(llm("large", 40000, 8000));
        assertUsage(1.25, 4, "ok");

        ObjectNode draft = recordStep(llm("large", 20000, 4000));
        assertUsage(1.85, 5, "warn");
        assertThat(draft.path("structuredContent").path("flaggedConstraints").toString()).contains("near-budget");

        // One more large pass would cost $0.60 and break the $2 budget: refused, nothing recorded.
        ObjectNode refused = recordStep(llm("large", 20000, 4000));
        assertThat(refused.path("isError").asBoolean()).isTrue();
        assertThat(refused.path("structuredContent").path("violations").get(0).path("constraintId").asText())
                .isEqualTo("hard-budget");
        assertUsage(1.85, 5, "warn");

        // The agent falls back to the small model.
        recordStep(llm("small", 20000, 4000));
        assertUsage(1.89, 6, "warn");

        // A person caps the run at the steps used so far: the model halts it and tells the caller why.
        ObjectNode cap = mutate("{\"$.run.maxSteps\": 6}");
        assertUsage(1.89, 6, "halt");
        JsonNode effect = cap.path("structuredContent").path("dispatchedEffects").get(0);
        assertThat(effect.path("emit").asText()).isEqualTo("run.halt");
        assertThat(effect.path("payload").path("reason").asText()).isEqualTo("step limit");

        // The person approves more room; the run is back to ok.
        mutate("{\"$.run.maxCostUsd\": 3, \"$.run.maxSteps\": 10}");
        assertUsage(1.89, 6, "ok");

        recordStep(llm("large", 20000, 4000));
        assertUsage(2.49, 7, "warn");
        mutate("{\"$.run.state\": \"finished\"}");
        assertUsage(2.49, 7, "finished");
    }

    // ── helpers ────────────────────────────────────────────────────────────────

    private ObjectNode recordStep(ObjectNode step) {
        ObjectNode args = MAPPER.createObjectNode();
        args.put("id", id);
        ArrayNode patch = args.putArray("patch");
        patch.addObject().put("op", "add").put("path", "/steps/-").set("value", step);
        return registry.call("patch_model", args);
    }

    private ObjectNode mutate(String mutations) throws Exception {
        ObjectNode args = MAPPER.createObjectNode();
        args.put("id", id);
        args.set("mutations", MAPPER.readTree(mutations));
        ObjectNode result = registry.call("mutate", args);
        assertThat(result.path("isError").asBoolean()).isFalse();
        return result;
    }

    private void assertUsage(double spent, int steps, String mode) {
        ObjectNode args = MAPPER.createObjectNode();
        args.put("id", id);
        JsonNode state = registry.call("get_state", args).path("structuredContent");
        assertThat(state.path("usage").path("costUsd").asDouble()).isEqualTo(spent);
        assertThat(state.path("usage").path("steps").asInt()).isEqualTo(steps);
        assertThat(state.path("run").path("mode").asText()).isEqualTo(mode);
    }

    private static ObjectNode llm(String model, int in, int out) {
        ObjectNode s = MAPPER.createObjectNode();
        s.put("kind", "llm").put("model", model).put("inputTokens", in).put("outputTokens", out);
        return s;
    }

    private static ObjectNode tool(String name) {
        ObjectNode s = MAPPER.createObjectNode();
        s.put("kind", "tool").put("tool", name);
        return s;
    }

    private static File resolve(String name) {
        for (String dir : new String[]{"../valem-ui/src/examples/", "valem-ui/src/examples/"}) {
            File f = new File(dir + name);
            if (f.exists()) return f;
        }
        throw new IllegalStateException(name + " not found relative to working dir");
    }
}
