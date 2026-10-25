package org.json_kula.valem.service;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import org.json_kula.valem.core.blob.InMemoryBlobStore;
import org.json_kula.valem.core.engine.ConstraintEvaluator;
import org.json_kula.valem.core.engine.ExpressionCache;
import org.json_kula.valem.core.engine.ModelRuntime;
import org.json_kula.valem.core.engine.SchemaViolationException;
import org.json_kula.valem.core.graph.CompiledModel;
import org.json_kula.valem.core.graph.ModelSpecCompiler;
import org.json_kula.valem.core.graph.ModelSpecValidator;
import org.json_kula.valem.core.model.ConstraintSpec;
import org.json_kula.valem.core.model.ModelSpec;
import org.json_kula.valem.core.state.ModelState;
import org.json_kula.valem.view.engine.ViewEvaluator;
import org.json_kula.valem.view.model.ViewDefinition;
import org.json_kula.valem.view.model.ViewSpec;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;

/**
 * Runs a spec on a set of inputs with no model registered anywhere: compile once, then every call is a
 * fresh throw-away {@link ModelRuntime} that applies the inputs and reads the merged state back.
 *
 * <p>This is what a chat card needs. The host keeps the conversation, the card keeps the inputs, and the
 * server keeps nothing but a short-term cache of compiled models keyed by the spec's hash, so any replica
 * can answer any call and a redeploy loses nothing a caller can't resend. A cache miss is reported as
 * {@link SpecUnknownException}; the caller resends the spec and {@link #prepare} compiles it again.
 *
 * <p>Compilation is the expensive part (each JSONata expression is a javac round-trip, pinned in
 * Metaspace), so both caches are bounded: the compiled models by count and idle time, the expressions by
 * {@link ExpressionCache}'s own LRU. An evicted entry is simply recompiled on its next use.
 *
 * <p>Thread-safe: the caches are concurrent and each run owns its runtime.
 */
public final class StatelessRunner {

    /** Strict on purpose: a misspelled spec field is reported to the author instead of silently dropped. */
    private static final ObjectMapper SPEC_MAPPER = new ObjectMapper();
    private static final ObjectMapper VIEW_MAPPER = new ObjectMapper()
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
    private static final JsonNodeFactory F = JsonNodeFactory.instance;

    /** Default bound on cached compiled models. */
    public static final int DEFAULT_MAX_SPECS = 200;
    /** Default idle time after which a cached compiled model is dropped. */
    public static final Duration DEFAULT_IDLE = Duration.ofHours(1);

    /** The validator's location for a failing embedded test. */
    private static final String TESTS_LOCATION = "tests";

    private final ExpressionCache expressions = new ExpressionCache();
    private final Cache<String, CompiledModel> compiled;

    /** A spec parsed, validated (self-tests included) and, when valid, compiled and cached. */
    public record Prepared(String specHash, ModelSpec spec, List<ModelSpecValidator.ValidationError> errors,
                           List<ModelSpecValidator.ValidationError> warnings) {
        public boolean ok() { return errors.isEmpty(); }
    }

    /** One constraint or schema finding, with the field paths it belongs to (empty for a global rule). */
    public record Issue(String id, String message, List<String> paths) {}

    /** The result of a run: the new state, or the reason the inputs were refused. */
    public sealed interface Outcome permits Computed, Rejected {}

    /**
     * The inputs were accepted.
     *
     * @param state merged document (base plus derived), as {@code GET /models/{id}/state} returns it
     * @param meta  per-field view metadata keyed {@code <bind>#readOnly|visible|required}; empty when the
     *              spec has no meta-derivations
     * @param flags FLAG-policy constraints that currently fail
     */
    public record Computed(ObjectNode state, ObjectNode meta, List<Issue> flags) implements Outcome {}

    /**
     * The inputs were refused and nothing was applied.
     *
     * @param reason {@code "constraint"} for a ROLLBACK rule, {@code "schema"} for a schema violation
     */
    public record Rejected(String reason, List<Issue> issues) implements Outcome {}

    /** The hash names no cached compiled model: the caller should resend the spec. */
    public static final class SpecUnknownException extends RuntimeException {
        public SpecUnknownException(String specHash) {
            super("No compiled model for " + specHash + "; resend the spec.");
        }
    }

    public StatelessRunner() {
        this(DEFAULT_MAX_SPECS, DEFAULT_IDLE);
    }

    public StatelessRunner(int maxSpecs, Duration idle) {
        this.compiled = Caffeine.newBuilder()
                .maximumSize(maxSpecs)
                .expireAfterAccess(idle)
                .build();
    }

    /**
     * Parses and validates {@code specJson} (running its embedded tests), and compiles and caches it
     * when it is valid. Never throws for a bad spec: parse errors come back as findings too.
     */
    public Prepared prepare(JsonNode specJson) {
        String hash = specHash(specJson);
        ModelSpec spec;
        try {
            spec = SPEC_MAPPER.treeToValue(specJson, ModelSpec.class);
        } catch (Exception e) {
            String msg = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            int cut = msg.indexOf(" (through reference chain");
            var error = new ModelSpecValidator.ValidationError("spec",
                    "Not a valid ModelSpec: " + (cut > 0 ? msg.substring(0, cut) : msg),
                    ModelSpecValidator.Severity.ERROR);
            return new Prepared(hash, null, List.of(error), List.of());
        }
        if (compiled.getIfPresent(hash) != null) {
            return new Prepared(hash, spec, List.of(), List.of());
        }
        ModelSpecValidator.ValidationResult vr = ModelSpecValidator.validate(spec, expressions, true);
        // The validator reports a failing self-test as a warning, since a stored model may legitimately
        // carry one. A spec run here was just written to be shown, so its own tests must pass.
        List<ModelSpecValidator.ValidationError> errors = new ArrayList<>(vr.errors());
        List<ModelSpecValidator.ValidationError> warnings = new ArrayList<>();
        for (ModelSpecValidator.ValidationError w : vr.warnings()) {
            (TESTS_LOCATION.equals(w.location()) ? errors : warnings).add(w);
        }
        if (errors.isEmpty()) {
            compiled.put(hash, ModelSpecCompiler.compile(spec));
        }
        return new Prepared(hash, spec, List.copyOf(errors), List.copyOf(warnings));
    }

    /** True when a compiled model for {@code specHash} is cached right now. */
    public boolean isCached(String specHash) {
        return compiled.getIfPresent(specHash) != null;
    }

    /** The spec behind a cached hash, if it is still cached. */
    public Optional<ModelSpec> cachedSpec(String specHash) {
        return Optional.ofNullable(compiled.getIfPresent(specHash)).map(CompiledModel::spec);
    }

    /**
     * Runs the cached spec on {@code inputs} (canonical paths to values, all applied in one cycle on top
     * of the spec's defaults).
     *
     * @throws SpecUnknownException when the hash is not cached
     */
    public Outcome run(String specHash, Map<String, JsonNode> inputs) {
        CompiledModel model = compiled.getIfPresent(specHash);
        if (model == null) throw new SpecUnknownException(specHash);
        return run(model, inputs);
    }

    private Outcome run(CompiledModel model, Map<String, JsonNode> inputs) {
        ModelRuntime rt = ModelRuntime.withSharedCache(model, new ModelState(model, new InMemoryBlobStore()), expressions);
        try {
            try {
                rt.initialize();
                if (inputs != null && !inputs.isEmpty()) rt.mutate(inputs);
            } catch (ConstraintEvaluator.ConstraintViolationException e) {
                return new Rejected("constraint", issues(model.spec(), e.violations()));
            } catch (SchemaViolationException e) {
                List<Issue> issues = e.violations().stream()
                        .map(v -> new Issue(v.keyword(), v.message(), v.path() == null ? List.of() : List.of(v.path())))
                        .toList();
                return new Rejected("schema", issues);
            }
            ObjectNode state = rt.fullState();
            return new Computed(state, meta(model, rt, state), currentFlags(model, rt, state));
        } finally {
            rt.dispose();
        }
    }

    /**
     * Every FLAG rule that fails on the final state. A mutation result only reports the rules its cycle
     * re-evaluated, so after a separate initialize and mutate a rule the inputs fixed could linger; this
     * evaluates them all once against the finished document instead.
     */
    private List<Issue> currentFlags(CompiledModel model, ModelRuntime rt, ObjectNode merged) {
        Set<String> all = new LinkedHashSet<>();
        for (ConstraintSpec c : model.constraints()) all.add("$constraint:" + c.id());
        if (all.isEmpty()) return List.of();
        try {
            return issues(model.spec(),
                    new ConstraintEvaluator(expressions).evaluate(model, rt.stateView(), all, null, merged));
        } catch (ConstraintEvaluator.ConstraintViolationException e) {
            // Cannot happen after a committed cycle; report rather than hide it if it ever does.
            return issues(model.spec(), e.violations());
        }
    }

    private static List<Issue> issues(ModelSpec spec, List<ConstraintEvaluator.Violation> violations) {
        List<Issue> out = new ArrayList<>();
        for (ConstraintEvaluator.Violation v : violations) {
            List<String> paths = List.of();
            for (ConstraintSpec c : spec.constraints()) {
                if (c.id().equals(v.constraintId()) && !c.isGlobal()) {
                    paths = c.path().stream().filter(p -> !p.contains("[*]")).toList();
                }
            }
            out.add(new Issue(v.constraintId(), v.message(), paths));
        }
        return out;
    }

    /**
     * The per-field metadata a renderer needs ({@code <bind>#readOnly|visible|required}), collected
     * across every view. Only meta-derivations make it differ from the defaults, so a spec without any
     * skips evaluating views entirely.
     */
    private ObjectNode meta(CompiledModel model, ModelRuntime rt, ObjectNode state) {
        ObjectNode meta = F.objectNode();
        JsonNode rawView = model.spec().viewDefinition();
        if (model.spec().metaDerivations().isEmpty() || rawView == null || rawView.isNull()) return meta;
        ViewDefinition vd;
        try {
            vd = VIEW_MAPPER.treeToValue(rawView, ViewDefinition.class);
        } catch (Exception e) {
            return meta;
        }
        for (ViewSpec view : vd.views()) {
            JsonNode evaluated = VIEW_MAPPER.valueToTree(ViewEvaluator.evaluate(
                    model.spec().id(), view, state, rt.stateView().metaCache(), expressions,
                    model.constantsNode(), model.libraryFunctions(), model.libraryConstants()));
            collectMeta(evaluated.path("components"), meta);
        }
        return meta;
    }

    private static void collectMeta(JsonNode components, ObjectNode meta) {
        for (JsonNode c : components) {
            String bind = c.path("bind").asText(null);
            if (bind != null) {
                for (String key : List.of("readOnly", "visible", "required")) {
                    if (c.has(key)) meta.set(bind + "#" + key, c.get(key));
                }
            }
            collectMeta(c.path("components"), meta);
        }
    }

    /**
     * {@code sha256:<hex>} over the spec's canonical JSON (object keys sorted, no whitespace), so two
     * copies of one spec that differ only in key order or formatting share a cache entry.
     */
    public static String specHash(JsonNode spec) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(canonical(spec).getBytes(StandardCharsets.UTF_8));
            return "sha256:" + HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    static String canonical(JsonNode node) {
        return sorted(node).toString();
    }

    private static JsonNode sorted(JsonNode node) {
        if (node.isObject()) {
            TreeMap<String, JsonNode> fields = new TreeMap<>();
            node.properties().forEach(e -> fields.put(e.getKey(), sorted(e.getValue())));
            ObjectNode out = F.objectNode();
            fields.forEach(out::set);
            return out;
        }
        if (node.isArray()) {
            ArrayNode out = F.arrayNode();
            node.forEach(el -> out.add(sorted(el)));
            return out;
        }
        return node;
    }
}
