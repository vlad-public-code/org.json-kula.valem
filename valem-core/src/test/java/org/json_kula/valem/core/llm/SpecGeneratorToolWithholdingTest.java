package org.json_kula.valem.core.llm;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Web tools (web_search / web_fetch) are for the research phase on the INITIAL generation attempt.
 * Repair attempts must fix the spec from what was already gathered, not re-invoke the tools — the
 * budget is largely spent, and (because each attempt is a fresh conversation) re-offering tools on a
 * repair is where an off-topic call appeared: a repair turn re-issued a web search that belonged to a
 * previously generated model, via provider prompt-prefix cache bleed on the identical system prefix.
 *
 * <p>These tests pin the contract that {@code SpecGenerator} offers tools on attempt 0 only and
 * withholds them on every repair, for both the generation and the evolution loops.
 */
class SpecGeneratorToolWithholdingTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** Records, in order, whether tools were offered on each LLM attempt. */
    private final List<Boolean> toolsPerAttempt = new ArrayList<>();

    /** A WebTool with a definition but a no-op executor — its executor is never actually invoked. */
    private WebTool noopWebTool() {
        LlmClient.ToolDefinition def =
                new LlmClient.ToolDefinition("web_search", "search the web", MAPPER.createObjectNode());
        return new WebTool() {
            @Override public List<LlmClient.ToolDefinition> definitions() { return List.of(def); }
            @Override public LlmClient.ToolExecutor newExecutor() { return call -> "unused"; }
        };
    }

    /** LlmClient that records tools-vs-no-tools per attempt and returns responses in sequence. */
    private LlmClient recordingClient(List<String> responses) {
        AtomicInteger attempt = new AtomicInteger();
        return new LlmClient() {
            @Override public String complete(String prompt) {
                throw new AssertionError("string complete() should not be reached");
            }
            @Override public String completeWithTools(
                    SpecGenerationPrompt.PromptParts parts, List<LlmClient.ToolDefinition> tools,
                    LlmClient.ToolExecutor executor, LlmClient.CompletionOptions options,
                    Consumer<LlmProgressEvent> onProgress) {
                toolsPerAttempt.add(true);
                return responses.get(Math.min(attempt.getAndIncrement(), responses.size() - 1));
            }
            @Override public String complete(
                    SpecGenerationPrompt.PromptParts parts, LlmClient.CompletionOptions options) {
                toolsPerAttempt.add(false);
                return responses.get(Math.min(attempt.getAndIncrement(), responses.size() - 1));
            }
        };
    }

    private static final String INVALID = "{ \"id\": \"\", \"schema\": {} }";       // blank id → repair
    private static final String VALID =
            "{ \"id\": \"m\", \"schema\": { \"type\": \"object\", "
            + "\"properties\": { \"a\": { \"type\": \"number\" } } } }";

    @Test
    void tools_offered_on_initial_attempt_then_withheld_on_repair() {
        LlmClient stub = recordingClient(List.of(INVALID, VALID));
        var result = new SpecGenerator(stub, MAPPER, 3, noopWebTool()).generate("m", "desc");

        assertThat(result).isInstanceOf(SpecGenerator.GenerationResult.Success.class);
        // attempt 0 = tools (research); attempt 1 = repair, no tools
        assertThat(toolsPerAttempt).containsExactly(true, false);
    }

    @Test
    void every_repair_attempt_withholds_tools() {
        // Two invalids then a valid → attempts 0,1,2: only attempt 0 gets tools.
        LlmClient stub = recordingClient(List.of(INVALID, INVALID, VALID));
        var result = new SpecGenerator(stub, MAPPER, 4, noopWebTool()).generate("m", "desc");

        assertThat(result).isInstanceOf(SpecGenerator.GenerationResult.Success.class);
        assertThat(toolsPerAttempt).containsExactly(true, false, false);
    }

    @Test
    void no_webtool_means_no_tools_on_any_attempt() {
        LlmClient stub = recordingClient(List.of(INVALID, VALID));
        // No WebTool configured → the initial attempt also goes through the plain complete() path.
        var result = new SpecGenerator(stub, MAPPER, 3).generate("m", "desc");

        assertThat(result).isInstanceOf(SpecGenerator.GenerationResult.Success.class);
        assertThat(toolsPerAttempt).containsExactly(false, false);
    }
}
