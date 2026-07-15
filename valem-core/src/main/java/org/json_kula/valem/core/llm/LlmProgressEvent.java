package org.json_kula.valem.core.llm;

import java.util.List;

/** Progress event emitted during a multi-step LLM generation session. */
public sealed interface LlmProgressEvent
        permits LlmProgressEvent.LlmRequesting, LlmProgressEvent.ToolCalling,
                LlmProgressEvent.ToolCompleted, LlmProgressEvent.Validating,
                LlmProgressEvent.ValidationFailed, LlmProgressEvent.TestRunning,
                LlmProgressEvent.TestFailed, LlmProgressEvent.Retrying {

    /** A new HTTP round-trip to the LLM is starting. */
    record LlmRequesting(int attempt) implements LlmProgressEvent {}

    /** The LLM requested a tool call. {@code detail} is the query/URL/expression preview. */
    record ToolCalling(String tool, String detail) implements LlmProgressEvent {}

    /** A tool call completed. {@code resultSummary} is a brief description of the result. */
    record ToolCompleted(String tool, String resultSummary) implements LlmProgressEvent {}

    /** Structural validation of the generated spec is about to run. */
    record Validating(int attempt) implements LlmProgressEvent {}

    /** Structural validation failed; the loop will retry. */
    record ValidationFailed(int attempt, List<String> errors) implements LlmProgressEvent {}

    /** Embedded self-tests are about to run. */
    record TestRunning(int attempt) implements LlmProgressEvent {}

    /** Some embedded self-tests failed; the loop may retry. */
    record TestFailed(int attempt, int failCount) implements LlmProgressEvent {}

    /** A repair iteration is starting (attempt > 1). */
    record Retrying(int attempt, int maxAttempts) implements LlmProgressEvent {}
}
