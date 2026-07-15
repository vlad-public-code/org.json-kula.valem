package org.json_kula.valem.api.llm;

import java.util.List;

/**
 * Implemented by tool executors that accumulate {@link WebFetchFact}s during a generation session,
 * so {@link RecordingLlmClient} can surface them on the interaction record regardless of which
 * concrete executor (single fetch tool, or a composite of several web tools) was used.
 */
public interface FactProvider {
    List<WebFetchFact> facts();
}
