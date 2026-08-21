package org.json_kula.valem.core.llm;

/**
 * Which model actually answers a call: the provider name and the model id.
 *
 * <p>Exists so a generation log can say <em>which</em> LLM produced a spec. That is not cosmetic —
 * a deployment can route across several providers, and "the output got worse today" is
 * unanswerable without knowing which model served the request. It is also the first thing to check
 * when a spec fails validation in a way the usual model does not.
 *
 * <p>Both fields are display metadata, never secrets: a provider name and a public model id, never
 * a key or an endpoint. That is what lets them survive
 * {@code valem.llm.log.capture-content=false}, which strips the prompt and response text.
 *
 * @param provider provider name as configured ({@code anthropic}, {@code groq}, …); may be
 *                 {@code null} when a client does not know its own provider label
 * @param model    model id sent to the provider; may be {@code null} for a stub
 */
public record LlmDescriptor(String provider, String model) {

    /**
     * A single-line label for a log or a UI row: {@code "groq · openai/gpt-oss-120b"}, degrading to
     * whichever half is known, or {@code null} when neither is.
     */
    public String label() {
        boolean hasProvider = provider != null && !provider.isBlank();
        boolean hasModel    = model    != null && !model.isBlank();
        if (hasProvider && hasModel) return provider + " · " + model;
        if (hasProvider)             return provider;
        return hasModel ? model : null;
    }
}
