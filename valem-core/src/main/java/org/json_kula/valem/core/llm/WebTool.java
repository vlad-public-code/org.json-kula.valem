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
     * Creates a fresh executor for one generation session.
     * The executor enforces per-session call limits internally and routes by tool name.
     */
    LlmClient.ToolExecutor newExecutor();
}
