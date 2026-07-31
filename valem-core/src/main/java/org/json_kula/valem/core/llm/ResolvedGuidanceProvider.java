package org.json_kula.valem.core.llm;

/**
 * Implemented by a {@link LlmClient.ToolExecutor} that can report the domain guidance the model
 * resolved via the {@code get_domain_guidance} tool during this generation session.
 *
 * <p>Tools run on the FIRST attempt only, so the instructions the model pulled would otherwise vanish
 * on every (fresh-conversation) repair attempt. {@link SpecGenerator} reads this after the initial
 * attempt and re-injects the resolved guidance into the repair prompts, so the LLM-selected guidance
 * persists across the whole retry loop — LLM-classified once, present on every attempt.
 */
public interface ResolvedGuidanceProvider {

    /** The concatenated guidance instructions resolved so far this session, or {@code ""} if none. */
    String resolvedGuidance();
}
