package org.json_kula.valem.core.model;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;

import java.util.Map;

/**
 * A declarative effect — the pure core emits an <em>effect request</em> as data; a shell (selected by
 * {@link Executor}) executes it. Unifies the old {@code actions} feature ({@code executor: caller})
 * with I/O-capable external effects ({@code executor: server}). The core never performs I/O.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record EffectSpec(
        String id,
        String executor,                   // "server" (default) | "caller" | "llm" | "timer" | any plugin kind
        String trigger,                    // JSONata boolean over the merged document
        String dedupeKey,                  // JSONata; edge key — re-fire only when it transitions
        RequestSpec request,               // server executor: the outbound HTTP request template
        // Serialized/read via the authored `response: { set }` wrapper (see responseWrapper()), so a
        // stored spec round-trips symmetrically; @JsonIgnore suppresses the raw component name.
        @JsonIgnore Map<String, String> responseSet,   // server executor: JSON Path target -> JSONata over $response
        String statusPath,                 // address of the $io sub-doc holding {phase, key, at, error}
        PolicySpec policy,                 // server executor: timeout/retries/egressProfile
        String emit,                       // caller executor: event name surfaced to the client
        Map<String, String> payload,       // caller executor: JSON Path -> JSONata payload map
        String requests,                   // server executor: JSONata -> array of request descriptors (fan-out)
        String prompt,                     // llm executor: JSONata -> the prompt text
        JsonNode responseSchema,           // llm executor: optional JSON Schema for structured output
        String at,                         // timer executor: JSONata -> absolute fire time (epoch millis or ISO-8601)
        String afterMs,                    // timer executor: JSONata -> relative delay in milliseconds
        TargetSpec target,                 // server executor: composition link to another model (alt. to request.url)
        String body,                       // server executor (target write-link): JSONata -> value written at target.path
        Origin origin                      // read-only: which ancestor contributed this effect (set by TemplateMaterializer)
) {
    @JsonCreator
    public static EffectSpec of(
            @JsonProperty(value = "id", required = true) String id,
            @JsonProperty("executor")                     String executor,
            @JsonProperty(value = "trigger", required = true) String trigger,
            @JsonProperty("dedupeKey")                    String dedupeKey,
            @JsonProperty("request")                      RequestSpec request,
            @JsonProperty("response")                     Response response,
            @JsonProperty("statusPath")                   String statusPath,
            @JsonProperty("policy")                       PolicySpec policy,
            @JsonProperty("emit")                         String emit,
            @JsonProperty("payload")                      Map<String, String> payload,
            @JsonProperty("requests")                     String requests,
            @JsonProperty("prompt")                       String prompt,
            @JsonProperty("responseSchema")               JsonNode responseSchema,
            @JsonProperty("at")                           String at,
            @JsonProperty("afterMs")                      String afterMs,
            @JsonProperty("target")                       TargetSpec target,
            @JsonProperty("body")                         String body,
            @JsonProperty("origin")                       Origin origin
    ) {
        return new EffectSpec(
                id,
                executor != null && !executor.isBlank() ? executor : "server",
                trigger,
                dedupeKey,
                request,
                response != null && response.set() != null ? Map.copyOf(response.set()) : Map.of(),
                statusPath,
                policy,
                emit,
                payload != null ? Map.copyOf(payload) : Map.of(),
                requests,
                prompt,
                responseSchema,
                at,
                afterMs,
                target,
                body,
                origin);
    }

    /** Returns a copy of this effect stamped with {@code origin} (used by the TemplateMaterializer). */
    public EffectSpec withOrigin(Origin origin) {
        return new EffectSpec(id, executor, trigger, dedupeKey, request, responseSet, statusPath,
                policy, emit, payload, requests, prompt, responseSchema, at, afterMs, target, body, origin);
    }

    /**
     * Provenance of an inherited effect: the ancestor coordinate it came from and that ancestor's
     * owner (multi-tenant-authorization §4.2). {@code null} for a branch-authored effect. When
     * {@code fromOwner} differs from the model's owner, the effect is inherited across an ownership
     * boundary and must be approved before it may run.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Origin(String fromRef, String fromOwner) {
        @JsonCreator
        public static Origin of(
                @JsonProperty("fromRef")   String fromRef,
                @JsonProperty("fromOwner") String fromOwner) {
            return new Origin(fromRef, fromOwner);
        }
    }

    /**
     * Serializes {@code responseSet} back to the authored {@code "response": { "set": {...} }} shape so
     * a stored spec round-trips (the raw component is {@code @JsonIgnore}d). Absent when empty.
     */
    @JsonProperty("response")
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public Response responseWrapper() {
        return responseSet == null || responseSet.isEmpty() ? null : new Response(responseSet);
    }

    /** Wrapper matching the {@code "response": { "set": { ... } }} JSON shape. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Response(Map<String, String> set) {
        @JsonCreator
        public static Response of(@JsonProperty("set") Map<String, String> set) {
            return new Response(set != null ? Map.copyOf(set) : Map.of());
        }
    }

    /** Outbound request template — authored in the spec (URL and parameters are model-specific, not
     *  backend config). {@code url} is an absolute URL; {@code url}/{@code headers} interpolate
     *  {@code { expr }} segments; {@code body} is a whole JSONata expression producing JSON. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record RequestSpec(String method, String url, Map<String, String> headers, String body) {
        @JsonCreator
        public static RequestSpec of(
                @JsonProperty("method")  String method,
                @JsonProperty("url")     String url,
                @JsonProperty("headers") Map<String, String> headers,
                @JsonProperty("body")    String body) {
            return new RequestSpec(
                    method != null ? method : "GET",
                    url,
                    headers != null ? Map.copyOf(headers) : Map.of(),
                    body);
        }
    }

    /** Execution policy for a server effect. {@code egressProfile} is OPTIONAL: an indirection for
     *  attaching server-held credentials or enabling a production allowlist lockdown. It is absent in
     *  the sandbox, where the spec provides the URL and SSRF is handled by generic runtime guards. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record PolicySpec(String egressProfile, int timeoutMs, int retries, String backoff,
                             String model, Double temperature) {
        @JsonCreator
        public static PolicySpec of(
                @JsonProperty("egressProfile") String  egressProfile,
                @JsonProperty("timeoutMs")     Integer timeoutMs,
                @JsonProperty("retries")       Integer retries,
                @JsonProperty("backoff")       String  backoff,
                @JsonProperty("model")         String  model,
                @JsonProperty("temperature")   Double  temperature) {
            return new PolicySpec(
                    egressProfile,
                    timeoutMs != null ? timeoutMs : 5000,
                    retries   != null ? retries   : 0,
                    backoff,
                    model,
                    temperature);
        }
    }
}
