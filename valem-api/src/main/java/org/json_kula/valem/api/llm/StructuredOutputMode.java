package org.json_kula.valem.api.llm;

/**
 * How much structured-output constraint to ask an OpenAI-compatible provider for.
 *
 * <p>Support is not uniform across the OpenAI-compatible ecosystem, and it is not a yes/no: there is
 * a ladder. Most hosted providers accept {@code {"type":"json_object"}}; fewer accept a full
 * {@code {"type":"json_schema", …}}; some local servers (llama.cpp, older vLLM/LM Studio builds)
 * reject {@code response_format} altogether. A provider that rejects what it is sent answers
 * <b>400</b>, which the sandbox's failure classifier reads as a configuration fault — so a perfectly
 * healthy provider looks like a dead one until this is turned down.
 *
 * <p>Turning it down is safe: {@code SpecGenerator.extractJson} already recovers a JSON document from
 * prose or a fenced block, so a model that merely <em>tends</em> to return JSON still works. The
 * schema was always guidance rather than a contract — {@code ModelSpecValidator} is the source of
 * truth either way — so the cost of a lower rung is a higher retry rate, not a broken pipeline.
 *
 * <p>Applies to OpenAI-compatible providers only. Anthropic has no {@code response_format}; the
 * setting is ignored there.
 */
public enum StructuredOutputMode {

    /**
     * Ask for the most the request supports: {@code json_schema} when a response schema is supplied,
     * {@code json_object} otherwise. The default, and what every provider got before this existed.
     */
    SCHEMA,

    /**
     * Always ask for {@code json_object}, never {@code json_schema}. The right rung for a provider
     * that has JSON mode but rejects or ignores schemas — the most common partial support.
     */
    JSON,

    /**
     * Send no {@code response_format} at all, for a provider that rejects the field outright. The
     * model is asked for JSON by the prompt alone and the response is recovered by extraction.
     */
    NONE;

    /** Parses a configured value; blank or unrecognised yields {@link #SCHEMA}. */
    public static StructuredOutputMode parse(String value) {
        if (value == null || value.isBlank()) return SCHEMA;
        return switch (value.trim().toLowerCase()) {
            case "json", "json_object", "object" -> JSON;
            case "none", "off", "false"          -> NONE;
            default                              -> SCHEMA;
        };
    }
}
