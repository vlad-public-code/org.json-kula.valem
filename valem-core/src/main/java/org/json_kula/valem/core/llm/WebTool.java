package org.json_kula.valem.core.llm;

import java.util.List;

/**
 * Provides one or more tool definitions and a fresh per-generation executor for web access.
 *
 * <p>A single tool (e.g. {@code web_fetch}) returns a one-element list; a composite that bundles
 * several tools (e.g. {@code web_search} + {@code web_fetch}) returns all of them. The single
 * {@link #newExecutor()} routes each invocation to the right handler by {@code ToolCall.name()}.
 *
 * <p>Each call to {@link #newExecutor()} returns a new executor that tracks its own
 * call limits independently, so different {@code generate()} sessions don't share quota.
 */
public interface WebTool {

    /** The tool definitions sent to the LLM so it knows which tools exist. */
    List<LlmClient.ToolDefinition> definitions();

    /**
     * The subset of {@link #definitions()} safe to offer on a REPAIR attempt (not just the first) —
     * local, side-effect-free tools like {@code eval_jsonata} that let the model re-test a corrected
     * expression. Network tools ({@code web_fetch}/{@code web_search}) are deliberately excluded: their
     * budget is session-scoped and largely spent up front, and re-offering them on a repair is where an
     * off-topic call can leak in (provider prompt-prefix cache bleed). Default: none.
     */
    default List<LlmClient.ToolDefinition> repairDefinitions() { return List.of(); }

    /**
     * Creates a fresh executor for one generation session.
     * The executor enforces per-session call limits internally and routes by tool name.
     */
    LlmClient.ToolExecutor newExecutor();
}
