package org.json_kula.valem.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.NullNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.json_kula.jsonata_jvm.JsonataExpression;
import org.json_kula.valem.core.engine.ConstraintEvaluator;
import org.json_kula.valem.core.engine.ExpressionCache;
import org.json_kula.valem.core.engine.ModelRuntime;
import org.json_kula.valem.core.engine.TestCaseRunner;
import org.json_kula.valem.core.graph.ModelSpecValidator;
import org.json_kula.valem.core.graph.SpecEvolution;
import org.json_kula.valem.core.llm.SpecGenerationSchema;
import org.json_kula.valem.core.model.ModelSpec;
import org.json_kula.valem.core.model.TestCase;
import org.json_kula.valem.service.ModelOperations;
import org.json_kula.valem.service.ModelRegistry;
import org.json_kula.valem.service.ModelService;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The Valem MCP tool surface: maps Model Context Protocol {@code tools/call} invocations to
 * {@link ModelService} operations, and describes each tool for {@code tools/list}.
 *
 * <p>This class is deliberately transport-agnostic — it knows nothing about JSON-RPC or stdio. It
 * exposes two operations that {@link McpServer} drives: {@link #listNode()} (the {@code tools} array
 * for a {@code tools/list} response) and {@link #call(String, JsonNode)} (execute one tool, returning
 * the MCP {@code tools/call} result object).
 *
 * <h2>Result shape</h2>
 * <p>Every result carries a {@code text} content block (the JSON result, pretty-printed) for backward
 * compatibility; when the result is a JSON object it is <b>also</b> returned as {@code structuredContent}
 * so a client can consume it without re-parsing the text.
 *
 * <p>Tool-execution failures return a result with {@code isError:true} — <b>not</b> a JSON-RPC protocol
 * error — so the calling model sees the failure and can react (per the MCP tool-error convention). A
 * ROLLBACK constraint violation is surfaced <b>structurally</b> (its {@code constraintId}/{@code policy}/
 * {@code message} list), not just as a flattened message string.
 */
class ToolRegistry {

    /** Executes one tool's logic against the service; may throw, which becomes an {@code isError} result. */
    @FunctionalInterface
    private interface Handler {
        Object handle(JsonNode args) throws Exception;
    }

    private record Tool(String name, String title, String description,
                        ObjectNode inputSchema, ObjectNode annotations, Handler handler) {}

    private final ModelOperations service;
    private final ObjectMapper  mapper;
    private final Map<String, Tool> tools = new LinkedHashMap<>();

    ToolRegistry(ModelOperations service, ObjectMapper mapper) {
        this.service = service;
        this.mapper  = mapper;
        register();
    }

    // ── Tool definitions ────────────────────────────────────────────────────────

    private void register() {
        add("list_models", "List models",
            "List the ids of all currently registered Valem models (alphabetical).",
            objectSchema(),
            annotations(true, false, true),
            args -> service.listModels());

        add("create_model", "Create model",
            "Create a new model from a declarative ModelSpec. The spec carries the JSON schema plus "
            + "derivations (computed fields), constraints (invariants), and optional effects. Returns "
            + "the created id. Fails (isError) on an invalid spec or a duplicate id.",
            objectSchema(schema -> {
                ObjectNode props = schema.putObject("properties");
                props.set("spec", describedSchema(SpecGenerationSchema.modelSpec(mapper),
                        "A full ModelSpec document (id, schema, derivations, constraints, effects, "
                        + "defaultValues, views)."));
                schema.putArray("required").add("spec");
            }),
            annotations(false, false, false),
            args -> {
                ModelSpec spec = mapper.treeToValue(required(args, "spec"), ModelSpec.class);
                service.createModel(spec);
                return service.getInfo(spec.id());   // richer than {id,status}: version + counts
            });

        add("get_model_info", "Get model info",
            "Get summary info for a model: id, version, and derivation/meta/constraint/effect counts.",
            idSchema(),
            annotations(true, false, true),
            args -> service.getInfo(requiredText(args, "id")));

        add("get_spec", "Get model spec",
            "Get the full stored ModelSpec JSON for a model.",
            idSchema(),
            annotations(true, false, true),
            args -> service.getSpec(requiredText(args, "id")));

        add("get_state", "Get state",
            "Get a model's merged state (base fields plus all computed derived fields). Pass an "
            + "optional ISO-8601 'at' timestamp for a point-in-time read from mutation history.",
            objectSchema(schema -> {
                ObjectNode props = schema.putObject("properties");
                stringProp(props, "id", "The model id.");
                stringProp(props, "at", "Optional ISO-8601 instant (e.g. 2026-07-03T12:00:00Z) "
                        + "for a point-in-time read; omit for current state.");
                schema.putArray("required").add("id");
            }),
            annotations(true, false, true),
            args -> {
                Instant at = args.hasNonNull("at") ? Instant.parse(args.get("at").asText()) : null;
                return service.getState(requiredText(args, "id"), at);
            });

        add("get_field", "Get field value",
            "Get the value of a single field by its JSON Path address (e.g. \"$.order.total\"). "
            + "Evaluates a LAZY derivation on demand.",
            pathSchema(),
            annotations(true, false, true),
            args -> {
                JsonNode value = service.getFieldValue(requiredText(args, "id"), requiredText(args, "path"));
                return (value == null || value.isMissingNode()) ? null : value;
            });

        add("mutate", "Apply mutations",
            "Apply field mutations to a model and run the reactive pipeline (derivations recompute, "
            + "constraints enforce, effects dispatch). 'mutations' is a flat map keyed by canonical "
            + "JSON Path address, e.g. {\"$.order.qty\": 3}. A ROLLBACK constraint violation returns "
            + "isError with the structured list of violated constraints.",
            objectSchema(schema -> {
                ObjectNode props = schema.putObject("properties");
                stringProp(props, "id", "The model id.");
                ObjectNode muts = props.putObject("mutations");
                muts.put("type", "object");
                muts.put("description", "Map of canonical JSON Path address to new value, "
                        + "e.g. {\"$.order.qty\": 3, \"$.order.discount\": 0.1}.");
                muts.put("additionalProperties", true);
                schema.putArray("required").add("id").add("mutations");
            }),
            annotations(false, false, false),
            args -> {
                ModelService.MutationOutcome outcome =
                        service.mutate(requiredText(args, "id"), parseMutations(required(args, "mutations")));
                return mutationMap(outcome.result());
            });

        add("explain", "Explain field",
            "Explain why a field is what it is: returns the recent derivation/constraint trace records "
            + "for a path from the in-memory ring buffer (inputs, expression, result). For a constraint "
            + "use the synthetic path \"$constraint:<id>\".",
            pathSchema(),
            annotations(true, false, true),
            args -> service.explain(requiredText(args, "id"), requiredText(args, "path")));

        add("get_history", "Get history",
            "List the ISO-8601 timestamps of a model's committed mutations (most recent 100).",
            idSchema(),
            annotations(true, false, true),
            args -> service.getHistory(requiredText(args, "id")));

        add("evolve_spec", "Evolve spec",
            "Apply an incremental SpecEvolution diff to a model, preserving live state. Returns the new "
            + "version. Fails (isError) if the evolved spec is invalid, if expectedVersion no longer "
            + "matches, or if a schema change would strand existing state. Prefer targeted diffs "
            + "(upsertSchemaNodes/upsertSchemaDefs, upsertComponents, upsertConstants) over resending a "
            + "whole section (newSchema/newViewDefinition/newConstants).",
            objectSchema(schema -> {
                ObjectNode props = schema.putObject("properties");
                stringProp(props, "id", "The model id.");
                props.set("evolution", describedSchema(SpecGenerationSchema.specEvolution(mapper),
                        "A SpecEvolution document: newVersion/expectedVersion plus per-section upsert/remove "
                        + "lists. Schema tiers: upsertSchemaDefs/removeSchemaDefs (by $defs name), "
                        + "upsertSchemaNodes/removeSchemaNodes (by canonical data path), or newSchema "
                        + "(wholesale). View tiers: upsertViews/removeViews/newDefaultView, "
                        + "upsertComponents/removeComponents, or newViewDefinition. Constants: "
                        + "upsertConstants/removeConstants or newConstants."));
                schema.putArray("required").add("id").add("evolution");
            }),
            annotations(false, true, false),   // can remove derivations/constraints/effects → destructive
            args -> {
                SpecEvolution evolution = mapper.treeToValue(required(args, "evolution"), SpecEvolution.class);
                ModelSpec evolved = service.evolveSpec(requiredText(args, "id"), evolution);
                return Map.of("id", evolved.id(), "version", evolved.version());
            });

        add("delete_model", "Delete model",
            "Remove a model from the registry. Fails (isError) if the model does not exist.",
            idSchema(),
            annotations(false, true, false),
            args -> {
                service.deleteModel(requiredText(args, "id"));
                return Map.of("deleted", true);
            });

        add("get_view", "Get view",
            "Evaluate a model's embedded view definition against current state and return the resolved "
            + "component tree. Pass an optional 'viewId' for a named view; omit for the default view.",
            objectSchema(schema -> {
                ObjectNode props = schema.putObject("properties");
                stringProp(props, "id", "The model id.");
                stringProp(props, "viewId", "Optional named view id; omit for the default view.");
                schema.putArray("required").add("id");
            }),
            annotations(true, false, true),
            args -> service.getView(
                    requiredText(args, "id"),
                    args.hasNonNull("viewId") ? args.get("viewId").asText() : null));

        // ── Authoring / verification tools (the agent generates; Valem verifies) ──

        add("validate_spec", "Validate spec",
            "Validate a ModelSpec WITHOUT creating it: returns a 'valid' flag plus structured findings "
            + "(errors + warnings, each with a location and message). Use this to iterate on a draft — "
            + "fix the reported errors, re-validate — before committing with create_model.",
            objectSchema(schema -> {
                ObjectNode props = schema.putObject("properties");
                props.set("spec", describedSchema(SpecGenerationSchema.modelSpec(mapper),
                        "The ModelSpec to validate."));
                schema.putArray("required").add("spec");
            }),
            annotations(true, false, true),
            args -> {
                ModelSpec spec = mapper.treeToValue(required(args, "spec"), ModelSpec.class);
                return validationMap(ModelSpecValidator.validate(spec));
            });

        add("eval_expression", "Evaluate expression",
            "Evaluate a single JSONata expression against a sample input document and return the "
            + "computed value, or the exact compile/eval error. Write the expr exactly as in a "
            + "derivation/constraint 'expr': bare dot-paths, no leading $ (e.g. "
            + "\"loan.amount * loan.annualRate / 1200\"). Use this to verify an expression before "
            + "putting it in a spec — it uses the same compiler the runtime validates against.",
            objectSchema(schema -> {
                ObjectNode props = schema.putObject("properties");
                stringProp(props, "expr", "A single JSONata expression (bare dot-paths, no leading $).");
                ObjectNode input = props.putObject("input");
                input.put("type", "object");
                input.put("description", "Sample document the expression runs against (full nested "
                        + "shape, e.g. {\"loan\": {\"amount\": 20000}}). Optional; defaults to {}.");
                input.put("additionalProperties", true);
                schema.putArray("required").add("expr");
            }),
            annotations(true, false, true),
            args -> evalExpression(requiredText(args, "expr"),
                    args.hasNonNull("input") ? args.get("input") : mapper.createObjectNode()));

        add("test_spec", "Test spec",
            "Run a spec's embedded test cases (or ad-hoc given->expect cases) through the real reactive "
            + "pipeline in a throwaway runtime, returning pass/fail plus per-field failures (path, "
            + "expected, actual). Use this to certify domain behavior before create_model / promotion.",
            objectSchema(schema -> {
                ObjectNode props = schema.putObject("properties");
                props.set("spec", describedSchema(SpecGenerationSchema.modelSpec(mapper),
                        "The ModelSpec whose tests to run."));
                ObjectNode tests = props.putObject("tests");
                tests.put("type", "array");
                tests.put("description", "Optional list of test cases (each with 'given' inputs and "
                        + "'expect' outputs); omit to run the spec's own embedded 'tests'.");
                schema.putArray("required").add("spec");
            }),
            annotations(true, false, true),
            args -> {
                ModelSpec spec = mapper.treeToValue(required(args, "spec"), ModelSpec.class);
                List<TestCase> tests = args.hasNonNull("tests")
                        ? List.of(mapper.treeToValue(args.get("tests"), TestCase[].class))
                        : spec.tests();
                return testMap(TestCaseRunner.run(spec, tests));
            });

        add("dry_run", "Dry run",
            "Compile a candidate ModelSpec in an ISOLATED throwaway runtime, apply optional sample "
            + "mutations, and return the resulting merged state (base + derived) — WITHOUT registering "
            + "it in the live registry. Use this to preview the full reactive cascade of a draft spec.",
            objectSchema(schema -> {
                ObjectNode props = schema.putObject("properties");
                props.set("spec", describedSchema(SpecGenerationSchema.modelSpec(mapper),
                        "The candidate ModelSpec."));
                ObjectNode muts = props.putObject("mutations");
                muts.put("type", "object");
                muts.put("description", "Optional field mutations to apply, keyed by canonical JSON "
                        + "Path (e.g. {\"$.price\": 10, \"$.qty\": 3}).");
                muts.put("additionalProperties", true);
                schema.putArray("required").add("spec");
            }),
            annotations(true, false, true),
            args -> {
                ModelSpec spec = mapper.treeToValue(required(args, "spec"), ModelSpec.class);
                ModelService throwaway = newThrowawayService();
                throwaway.createModel(spec);
                if (args.hasNonNull("mutations")) {
                    Map<String, JsonNode> muts = parseMutations(args.get("mutations"));
                    if (!muts.isEmpty()) throwaway.mutate(spec.id(), muts);
                }
                return throwaway.getState(spec.id(), null);
            });
    }

    // ── Transport-facing operations ───────────────────────────────────────────────

    /** Builds the {@code tools} array for a {@code tools/list} response. */
    ArrayNode listNode() {
        ArrayNode arr = mapper.createArrayNode();
        for (Tool t : tools.values()) {
            ObjectNode node = arr.addObject();
            node.put("name", t.name());
            node.put("title", t.title());
            node.put("description", t.description());
            node.set("inputSchema", t.inputSchema());
            node.set("annotations", t.annotations());
        }
        return arr;
    }

    /**
     * Executes the named tool and returns the MCP {@code tools/call} result object
     * ({@code {content:[{type:"text",text:...}], structuredContent?, isError}}).
     */
    ObjectNode call(String name, JsonNode arguments) {
        Tool tool = tools.get(name);
        if (tool == null) {
            return plainError("Unknown tool: '" + name + "'");
        }
        JsonNode args = (arguments == null || arguments.isNull()) ? mapper.createObjectNode() : arguments;
        try {
            return successResult(tool.handler().handle(args));
        } catch (Exception e) {
            return errorResult(e);
        }
    }

    // ── Result builders ───────────────────────────────────────────────────────────

    /**
     * A success result: a pretty-printed text block always, plus {@code structuredContent} when the
     * payload is a JSON object (MCP structured content is defined as an object; arrays/scalars are
     * conveyed via the text block only).
     */
    private ObjectNode successResult(Object payload) {
        ObjectNode result = mapper.createObjectNode();
        ObjectNode block = result.putArray("content").addObject();
        block.put("type", "text");
        result.put("isError", false);
        if (payload == null) {
            block.put("text", "null");
            return result;
        }
        JsonNode node = mapper.valueToTree(payload);
        block.put("text", pretty(node));
        if (node.isObject()) {
            result.set("structuredContent", node);
        }
        return result;
    }

    /**
     * An error result. A ROLLBACK constraint violation is surfaced structurally (its violations list);
     * anything else falls back to the exception message. Always {@code isError:true}.
     */
    private ObjectNode errorResult(Exception e) {
        JsonNode structured = structuredError(e);
        if (structured != null) {
            ObjectNode result = mapper.createObjectNode();
            ObjectNode block = result.putArray("content").addObject();
            block.put("type", "text");
            block.put("text", pretty(structured));
            result.set("structuredContent", structured);
            result.put("isError", true);
            return result;
        }
        return plainError(messageOf(e));
    }

    /** A plain text-only error result (no structured content). */
    private ObjectNode plainError(String message) {
        ObjectNode result = mapper.createObjectNode();
        result.putArray("content").addObject().put("type", "text").put("text", message);
        result.put("isError", true);
        return result;
    }

    /** Structured JSON for a recognised exception, or {@code null} to fall back to a plain message. */
    private JsonNode structuredError(Throwable e) {
        if (e instanceof ConstraintEvaluator.ConstraintViolationException cve) {
            ObjectNode err = mapper.createObjectNode();
            err.put("error", "Constraint violation");
            ArrayNode arr = err.putArray("violations");
            for (ConstraintEvaluator.Violation v : cve.violations()) {
                ObjectNode vn = arr.addObject();
                vn.put("constraintId", v.constraintId());
                vn.put("message", v.message());
                vn.set("policy", mapper.valueToTree(v.policy()));
            }
            return err;
        }
        return null;
    }

    private static String messageOf(Throwable e) {
        return e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
    }

    private String pretty(JsonNode node) {
        try {
            return mapper.writerWithDefaultPrettyPrinter().writeValueAsString(node);
        } catch (Exception e) {
            return String.valueOf(node);
        }
    }

    // ── Schema / annotation helpers ────────────────────────────────────────────────

    /** An input schema for a tool that takes no arguments. */
    private ObjectNode objectSchema() {
        return objectSchema(schema -> schema.putObject("properties"));
    }

    /** An input schema built by the given customiser (which sets properties/required). */
    private ObjectNode objectSchema(java.util.function.Consumer<ObjectNode> customiser) {
        ObjectNode schema = mapper.createObjectNode();
        schema.put("type", "object");
        customiser.accept(schema);
        return schema;
    }

    /** The common {@code {id}} single-required-string schema. */
    private ObjectNode idSchema() {
        return objectSchema(schema -> {
            ObjectNode props = schema.putObject("properties");
            stringProp(props, "id", "The model id.");
            schema.putArray("required").add("id");
        });
    }

    /** The common {@code {id, path}} schema. */
    private ObjectNode pathSchema() {
        return objectSchema(schema -> {
            ObjectNode props = schema.putObject("properties");
            stringProp(props, "id", "The model id.");
            stringProp(props, "path", "A canonical JSON Path address, e.g. \"$.order.total\".");
            schema.putArray("required").add("id").add("path");
        });
    }

    private static void stringProp(ObjectNode props, String name, String description) {
        ObjectNode p = props.putObject(name);
        p.put("type", "string");
        p.put("description", description);
    }

    /** Copies a reusable JSON Schema and overlays a description (so it reads well in the tool surface). */
    private ObjectNode describedSchema(JsonNode schema, String description) {
        ObjectNode copy = schema.deepCopy();
        copy.put("description", description);
        return copy;
    }

    /**
     * Builds an MCP tool-annotations object. {@code openWorldHint} is always false — every tool operates
     * on this server's in-memory model registry, not an external/open world.
     */
    private ObjectNode annotations(boolean readOnly, boolean destructive, boolean idempotent) {
        ObjectNode a = mapper.createObjectNode();
        a.put("readOnlyHint", readOnly);
        a.put("destructiveHint", destructive);
        a.put("idempotentHint", idempotent);
        a.put("openWorldHint", false);
        return a;
    }

    private void add(String name, String title, String description,
                     ObjectNode inputSchema, ObjectNode annotations, Handler handler) {
        tools.put(name, new Tool(name, title, description, inputSchema, annotations, handler));
    }

    // ── Argument helpers (shared shape with the console CommandDispatcher) ─────────

    private static JsonNode required(JsonNode args, String field) {
        JsonNode node = args.get(field);
        if (node == null || node.isNull()) {
            throw new IllegalArgumentException("Missing required argument: '" + field + "'");
        }
        return node;
    }

    private static String requiredText(JsonNode args, String field) {
        return required(args, field).asText();
    }

    private static Map<String, JsonNode> parseMutations(JsonNode node) {
        if (!node.isObject()) {
            throw new IllegalArgumentException("'mutations' must be a JSON object");
        }
        Map<String, JsonNode> map = new LinkedHashMap<>();
        node.properties().forEach(e -> map.put(e.getKey(), e.getValue()));
        return map;
    }

    private static Map<String, Object> mutationMap(ModelRuntime.MutationResult r) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("success",            r.success());
        map.put("mutatedPaths",       r.mutatedPaths());
        map.put("derivedUpdated",     r.derivedUpdated());
        map.put("metaUpdated",        r.metaUpdated());
        map.put("flaggedConstraints", r.flaggedConstraints());
        map.put("dispatchedEffects",  r.dispatchedEffects());
        map.put("traces",             r.traces());
        return map;
    }

    private static Map<String, Object> validationMap(ModelSpecValidator.ValidationResult vr) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("valid",    vr.isValid());
        map.put("errors",   vr.errors().stream().map(ToolRegistry::findingMap).toList());
        map.put("warnings", vr.warnings().stream().map(ToolRegistry::findingMap).toList());
        return map;
    }

    private static Map<String, Object> findingMap(ModelSpecValidator.ValidationError f) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("location", f.location());
        m.put("message",  f.message());
        return m;
    }

    /** Compiles and evaluates a JSONata expression against a sample input, returning a structured result. */
    private Object evalExpression(String expr, JsonNode input) {
        JsonataExpression compiled;
        try {
            compiled = new ExpressionCache().get(expr.strip());
        } catch (ExpressionCache.CompilationException ce) {
            return Map.of("ok", false, "error", "compile", "message", messageOf(ce));
        }
        try {
            JsonNode result = compiled.evaluate(input);
            boolean undefined = result == null || result.isMissingNode();
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("ok", true);
            m.put("undefined", undefined);
            m.put("result", undefined ? NullNode.getInstance() : result);
            return m;
        } catch (Exception e) {
            return Map.of("ok", false, "error", "evaluation", "message", messageOf(e));
        }
    }

    private static Map<String, Object> testMap(List<TestCaseRunner.TestResult> results) {
        long passed = results.stream().filter(TestCaseRunner.TestResult::passed).count();
        List<Object> details = new ArrayList<>();
        for (TestCaseRunner.TestResult r : results) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("description", r.description());
            m.put("passed", r.passed());
            if (!r.passed()) {
                List<Object> fails = new ArrayList<>();
                for (TestCaseRunner.FieldFailure f : r.failures()) {
                    Map<String, Object> fm = new LinkedHashMap<>();
                    fm.put("path", f.path());
                    fm.put("expected", f.expected());
                    fm.put("actual", f.actual());
                    fm.put("message", f.message());
                    fails.add(fm);
                }
                m.put("failures", fails);
            }
            details.add(m);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("total",   results.size());
        out.put("passed",  (int) passed);
        out.put("failed",  results.size() - (int) passed);
        out.put("results", details);
        return out;
    }

    /** A fresh, fully isolated in-memory service for dry-runs; never touches the live registry. */
    @SuppressWarnings("deprecation") // core InMemoryBlobStore: lean dep tree; throwaway, never persisted
    private static ModelService newThrowawayService() {
        return new ModelService(new ModelRegistry(),
                new org.json_kula.valem.core.blob.InMemoryBlobStore());
    }

    /** Tool names, exposed for tests. */
    List<String> toolNames() {
        return List.copyOf(tools.keySet());
    }
}
