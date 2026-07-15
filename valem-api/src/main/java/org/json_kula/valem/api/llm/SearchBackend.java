package org.json_kula.valem.api.llm;

/**
 * A pluggable web-search backend for {@link WebSearchTool}. Implementations perform the actual
 * network call and return a compact, model-friendly result list (or a bracketed
 * {@code [web_search: ...]} status string on failure/no-results) — the same contract regardless
 * of which underlying search provider is selected via {@code valem.llm.web-search.provider}.
 */
public interface SearchBackend {

    /**
     * Performs a search and returns a formatted result string ready to hand back to the LLM as a
     * tool result, or a {@code [web_search: ...]} status string if the search failed or found
     * nothing.
     */
    String search(String query, int maxResults);
}
