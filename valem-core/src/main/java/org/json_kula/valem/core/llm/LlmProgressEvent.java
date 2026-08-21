package org.json_kula.valem.core.llm;

import java.util.List;

/** Progress event emitted during a multi-step LLM generation session. */
public sealed interface LlmProgressEvent
        permits LlmProgressEvent.LlmRequesting, LlmProgressEvent.ToolCalling,
                LlmProgressEvent.ToolCompleted, LlmProgressEvent.Validating,
                LlmProgressEvent.ValidationFailed, LlmProgressEvent.TestRunning,
                LlmProgressEvent.TestFailed, LlmProgressEvent.Retrying {

    /**
     * A new HTTP round-trip to the LLM is starting, naming the model it goes to.
     *
     * <p>Carried per event rather than announced once per session because a routing deployment can
     * send attempt 2 to a different provider than attempt 1 — which is exactly when a reader needs
     * to know. {@code provider}/{@code model} are {@code null} when the client cannot identify
     * itself.
     */
    record LlmRequesting(int attempt, String provider, String model) implements LlmProgressEvent {

        /** For a client that does not identify itself. */
        public LlmRequesting(int attempt) {
            this(attempt, null, null);
        }

        /** From a {@link LlmDescriptor}, which may be {@code null}. */
        public static LlmRequesting of(int attempt, LlmDescriptor descriptor) {
            return descriptor == null
                    ? new LlmRequesting(attempt)
                    : new LlmRequesting(attempt, descriptor.provider(), descriptor.model());
        }
    }

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
