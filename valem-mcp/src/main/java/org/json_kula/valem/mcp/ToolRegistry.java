package org.json_kula.valem.mcp;

import com.fasterxml.jackson.core.JsonPointer;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.NullNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.json_kula.jsonata_jvm.JsonataExpression;
import org.json_kula.valem.core.engine.ConstraintEvaluator;
import org.json_kula.valem.core.engine.ExpressionCache;
import org.json_kula.valem.core.engine.ModelRuntime;
import org.json_kula.valem.core.engine.SchemaViolationException;
import org.json_kula.valem.core.engine.TestCaseRunner;
import org.json_kula.valem.core.graph.ModelSpecValidator;
import org.json_kula.valem.core.graph.SpecEvolution;
import org.json_kula.valem.core.llm.SpecGenerationSchema;
import org.json_kula.valem.core.model.BlobRef;
import org.json_kula.valem.core.model.ModelSpec;
import org.json_kula.valem.core.model.TestCase;
import org.json_kula.valem.core.state.PathConverter;
import org.json_kula.valem.core.state.Snapshot;
import org.json_kula.valem.service.ModelOperations;
import org.json_kula.valem.service.ModelRegistry;
import org.json_kula.valem.service.ModelService;
import org.json_kula.valem.service.SpecVersionConflictException;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
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
                        ObjectNode inputSchema, ObjectNode outputSchema,
                        ObjectNode annotations, Handler handler) {}

    /**
     * Above this pretty-printed size (chars ≈ bytes for the ASCII-dominant JSON here) a tool result is
     * replaced with a compact note telling the agent how to narrow it (§1.3 result-size guard). Keeps one
     * oversized read (a whole big {@code get_state}, a bulky {@code explain}) from wrecking the session.
     */
    private static final int MAX_RESULT_CHARS = 50_000;

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
            modelInfoOutputSchema(),
            annotations(false, false, false),
            args -> {
                ModelSpec spec = mapper.treeToValue(required(args, "spec"), ModelSpec.class);
                service.createModel(spec);
                return service.getInfo(spec.id());   // richer than {id,status}: version + counts
            });

        add("get_model_info", "Get model info",
            "Get summary info for a model: id, version, and derivation/meta/constraint/effect counts.",
            idSchema(),
            modelInfoOutputSchema(),
            annotations(true, false, true),
            args -> service.getInfo(requiredText(args, "id")));

        add("get_spec", "Get model spec",
            "Get the full stored ModelSpec JSON for a model.",
            idSchema(),
            objectSchema(),   // a full ModelSpec document
            annotations(true, false, true),
            args -> service.getSpec(requiredText(args, "id")));

        add("get_state", "Get state",
            "Get a model's merged state (base fields plus all computed derived fields). On a large model "
            + "this is the biggest context cost — narrow it: pass 'paths' to project only specific "
            + "subtrees (canonical addresses, each spliced back into a pruned document at its address), "
            + "and/or 'depth' to cap nesting (deeper containers collapse to a '<object: N fields>' / "
            + "'<array: N items>' marker). Pass an optional ISO-8601 'at' timestamp for a point-in-time "
            + "read from mutation history.",
            objectSchema(schema -> {
                ObjectNode props = schema.putObject("properties");
                stringProp(props, "id", "The model id.");
                stringProp(props, "at", "Optional ISO-8601 instant (e.g. 2026-07-03T12:00:00Z) "
                        + "for a point-in-time read; omit for current state.");
                ObjectNode paths = props.putObject("paths");
                paths.put("type", "array");
                paths.put("description", "Optional canonical addresses to project, e.g. "
                        + "[\"$.order\", \"$.totals\"]. Only these subtrees are returned, spliced back "
                        + "into a pruned document at their addresses; absent addresses are skipped.");
                paths.putObject("items").put("type", "string");
                ObjectNode depth = props.putObject("depth");
                depth.put("type", "integer");
                depth.put("minimum", 0);
                depth.put("description", "Optional max nesting depth; containers deeper than this "
                        + "collapse to a size marker. Applied after 'paths'.");
                schema.putArray("required").add("id");
            }),
            objectSchema(),   // freeform merged document
            annotations(true, false, true),
            args -> {
                Instant at = args.hasNonNull("at") ? Instant.parse(args.get("at").asText()) : null;
                ObjectNode state = service.getState(requiredText(args, "id"), at);
                return projectState(state, args.get("paths"), args.get("depth"));
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
            + "isError with the structured list of violated constraints. Returns the actionable summary "
            + "(derivedUpdated / flaggedConstraints / dispatchedEffects); pass includeTraces:true for the "
            + "full derivation/constraint trace (the same payload 'explain' serves — omit it and call "
            + "explain only when a value looks wrong).",
            objectSchema(schema -> {
                ObjectNode props = schema.putObject("properties");
                stringProp(props, "id", "The model id.");
                ObjectNode muts = props.putObject("mutations");
                muts.put("type", "object");
                muts.put("description", "Map of canonical JSON Path address to new value, "
                        + "e.g. {\"$.order.qty\": 3, \"$.order.discount\": 0.1}.");
                muts.put("additionalProperties", true);
                boolProp(props, "includeTraces", "Include the full derivation/constraint trace "
                        + "in the result (default false).");
                schema.putArray("required").add("id").add("mutations");
            }),
            mutationOutputSchema(),
            annotations(false, false, false),
            args -> {
                ModelService.MutationOutcome outcome =
                        service.mutate(requiredText(args, "id"), parseMutations(required(args, "mutations")));
                return mutationMap(outcome.result(), boolArg(args, "includeTraces", false));
            });

        add("patch_model", "Patch model",
            "Apply an RFC 6902 JSON Patch document to a model and run the reactive pipeline. Unlike "
            + "'mutate' (a flat address→value map), a patch expresses array insert/remove/move and "
            + "test/copy ops, e.g. [{\"op\":\"add\",\"path\":\"/order/items/-\",\"value\":{...}}, "
            + "{\"op\":\"remove\",\"path\":\"/order/items/0\"}]. 'path' fields use RFC 6901 JSON Pointer "
            + "(slash-separated, '-' for array append), NOT the $.-rooted address form. Same result shape "
            + "and ROLLBACK/schema error handling as 'mutate'.",
            objectSchema(schema -> {
                ObjectNode props = schema.putObject("properties");
                stringProp(props, "id", "The model id.");
                ObjectNode patch = props.putObject("patch");
                patch.put("type", "array");
                patch.put("description", "An RFC 6902 JSON Patch: an array of {op, path, value?, from?} "
                        + "operations applied in order.");
                patch.putObject("items").put("type", "object");
                boolProp(props, "includeTraces", "Include the full derivation/constraint trace "
                        + "in the result (default false).");
                schema.putArray("required").add("id").add("patch");
            }),
            mutationOutputSchema(),
            annotations(false, false, false),
            args -> {
                ModelService.MutationOutcome outcome =
                        service.patchMutate(requiredText(args, "id"), required(args, "patch"));
                return mutationMap(outcome.result(), boolArg(args, "includeTraces", false));
            });

        add("get_effective_schema", "Get effective schema",
            "Get the effective JSON Schema for a field: the static schema overlaid with LIVE meta-derived "
            + "constraints (current min/max/required/…). Check this BEFORE writing a value to learn what "
            + "the reactive pipeline will accept, instead of discovering an invalid mutation only by "
            + "trying it and getting a schema-violation error.",
            pathSchema(),
            objectSchema(),   // a JSON Schema fragment for the field
            annotations(true, false, true),
            args -> service.getEffectiveSchema(requiredText(args, "id"), requiredText(args, "path")));

        add("snapshot", "Snapshot state",
            "Capture an immutable point-in-time snapshot of a model's state (base document + derived/meta "
            + "caches). A natural safety step before a risky evolve_spec: keep the returned snapshot and, "
            + "if the change goes wrong, hand it back to 'restore' to roll the state back.",
            idSchema(),
            objectSchema(),
            annotations(true, false, true),
            args -> service.snapshot(requiredText(args, "id")));

        add("restore", "Restore state",
            "Restore a model's state from a snapshot previously returned by 'snapshot' (pass it back "
            + "verbatim as 'snapshot'). Overwrites the model's current base state. The snapshot must be "
            + "for the same model.",
            objectSchema(schema -> {
                ObjectNode props = schema.putObject("properties");
                stringProp(props, "id", "The model id.");
                ObjectNode snap = props.putObject("snapshot");
                snap.put("type", "object");
                snap.put("description", "A snapshot object as returned by the 'snapshot' tool.");
                snap.put("additionalProperties", true);
                schema.putArray("required").add("id").add("snapshot");
            }),
            annotations(false, true, true),
            args -> {
                Snapshot snapshot = mapper.treeToValue(required(args, "snapshot"), Snapshot.class);
                service.restore(requiredText(args, "id"), snapshot);
                return Map.of("restored", true);
            });

        add("explain", "Explain field",
            "Explain why a field is what it is: returns the recent derivation/constraint trace records "
            + "for a path from the in-memory ring buffer (inputs, expression, result). For a constraint "
            + "use the synthetic path \"$constraint:<id>\". Trace records can be bulky — pass 'limit' to "
            + "return only the most recent N.",
            objectSchema(schema -> {
                ObjectNode props = schema.putObject("properties");
                stringProp(props, "id", "The model id.");
                stringProp(props, "path", "A canonical JSON Path address, e.g. \"$.order.total\".");
                ObjectNode limit = props.putObject("limit");
                limit.put("type", "integer");
                limit.put("minimum", 1);
                limit.put("description", "Optional cap; return only the most recent N trace records.");
                schema.putArray("required").add("id").add("path");
            }),
            annotations(true, false, true),
            args -> {
                List<?> traces = service.explain(requiredText(args, "id"), requiredText(args, "path"));
                return tailLimit(traces, args.get("limit"));
            });

        add("get_history", "Get history",
            "List the ISO-8601 timestamps of a model's committed mutations (most recent 100).",
            idSchema(),
            annotations(true, false, true),
            args -> service.getHistory(requiredText(args, "id")));

        add("get_audit", "Get audit trail",
            "Query a model's durable, append-only audit trail (newest-first): one record per committed "
            + "reactive cycle (mutations, derivedUpdated, traces, flaggedConstraints, dispatchedEffects, "
            + "source, sequence). This is the queryable superset of get_history/explain — it survives the "
            + "in-memory ring buffer rolling over. Filter with an optional 'pathPrefix' (canonical "
            + "address), an ISO-8601 'from'/'to' window, and 'limit'. (Embedded mode keeps this in memory "
            + "for the session; remote/paired mode reads the server's durable store.)",
            objectSchema(schema -> {
                ObjectNode props = schema.putObject("properties");
                stringProp(props, "id", "The model id.");
                stringProp(props, "pathPrefix", "Optional canonical address prefix; keep only records "
                        + "that touched a matching field/derivation/constraint.");
                stringProp(props, "from", "Optional ISO-8601 lower bound (inclusive).");
                stringProp(props, "to", "Optional ISO-8601 upper bound (exclusive).");
                ObjectNode limit = props.putObject("limit");
                limit.put("type", "integer");
                limit.put("minimum", 1);
                limit.put("description", "Optional max records (default 100, newest-first).");
                schema.putArray("required").add("id");
            }),
            annotations(true, false, true),
            args -> service.getAudit(
                    requiredText(args, "id"),
                    args.hasNonNull("pathPrefix") ? args.get("pathPrefix").asText() : null,
                    args.hasNonNull("from") ? Instant.parse(args.get("from").asText()) : null,
                    args.hasNonNull("to")   ? Instant.parse(args.get("to").asText())   : null,
                    args.hasNonNull("limit") ? args.get("limit").asInt() : 0));

        add("verify_audit", "Verify audit trail",
            "Verify the tamper-evidence hash chain of a model's durable audit trail. Returns "
            + "{valid, recordsChecked, firstBrokenSequence, detail}; a false 'valid' points at the first "
            + "altered/reordered/deleted record. (Embedded mode has no hash chain and reports valid.)",
            idSchema(),
            objectSchema(schema -> {
                ObjectNode props = schema.putObject("properties");
                boolProp(props, "valid", "True when the whole chain is intact.");
                intProp(props, "recordsChecked", "Number of records examined.");
                intProp(props, "firstBrokenSequence", "Sequence of the first broken record, or null.");
                stringProp(props, "detail", "Human-readable explanation ('ok' when valid).");
            }),
            annotations(true, false, true),
            args -> service.verifyAudit(requiredText(args, "id")));

        // ── Blobs (niche: only when specs reference binary fields) ────────────────

        add("upload_blob", "Upload blob",
            "Store binary content (base64-encoded in 'data') in the content-addressed blob store and get "
            + "back a BlobRef {$blobId, $mediaType, $bytes} to embed in a model's binary field. Storage is "
            + "content-addressed (SHA-256), so uploading identical bytes returns the same $blobId.",
            objectSchema(schema -> {
                ObjectNode props = schema.putObject("properties");
                stringProp(props, "data", "The blob content, base64-encoded.");
                stringProp(props, "mediaType", "Optional media type (default application/octet-stream).");
                schema.putArray("required").add("data");
            }),
            objectSchema(schema -> {
                ObjectNode props = schema.putObject("properties");
                stringProp(props, "$blobId", "Content-addressed id (sha256:...).");
                stringProp(props, "$mediaType", "The stored media type.");
                intProp(props, "$bytes", "Size in bytes.");
            }),
            annotations(false, false, true),
            args -> {
                byte[] bytes = decodeBase64(requiredText(args, "data"));
                String mediaType = args.hasNonNull("mediaType")
                        ? args.get("mediaType").asText() : "application/octet-stream";
                try (InputStream in = new ByteArrayInputStream(bytes)) {
                    return service.uploadBlob(in, mediaType).toJsonNode();
                }
            });

        add("download_blob", "Download blob",
            "Fetch a blob's bytes by id, base64-encoded in the result. Pass 'modelId' to fetch a blob "
            + "referenced by a specific model (access-scoped); omit it for a direct store fetch. Large "
            + "blobs may exceed the result-size limit — this channel suits small binaries only.",
            objectSchema(schema -> {
                ObjectNode props = schema.putObject("properties");
                stringProp(props, "blobId", "The blob id (sha256:...).");
                stringProp(props, "modelId", "Optional model id to scope the fetch to a referencing model.");
                schema.putArray("required").add("blobId");
            }),
            objectSchema(schema -> {
                ObjectNode props = schema.putObject("properties");
                stringProp(props, "blobId", "The requested blob id.");
                intProp(props, "bytes", "Size in bytes.");
                stringProp(props, "data", "The blob content, base64-encoded.");
            }),
            annotations(true, false, true),
            args -> {
                String blobId = requiredText(args, "blobId");
                byte[] bytes;
                try (InputStream in = args.hasNonNull("modelId")
                        ? service.getBlobForModel(args.get("modelId").asText(), blobId)
                        : service.downloadBlob(blobId)) {
                    bytes = in.readAllBytes();
                }
                Map<String, Object> out = new LinkedHashMap<>();
                out.put("blobId", blobId);
                out.put("bytes", bytes.length);
                out.put("data", Base64.getEncoder().encodeToString(bytes));
                return out;
            });

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
            objectSchema(schema -> {
                ObjectNode props = schema.putObject("properties");
                stringProp(props, "id", "The evolved model id.");
                stringProp(props, "version", "The model's new version after evolution.");
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
            objectSchema(schema -> boolProp(schema.putObject("properties"), "deleted",
                    "True when the model was removed.")),
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
            objectSchema(),   // an EvaluatedView component tree
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
            objectSchema(schema -> {
                ObjectNode props = schema.putObject("properties");
                boolProp(props, "valid", "True when the spec has no errors.");
                arrayProp(props, "errors", "Blocking findings (each with location + message).");
                arrayProp(props, "warnings", "Non-blocking findings.");
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
            objectSchema(schema -> {
                ObjectNode props = schema.putObject("properties");
                boolProp(props, "ok", "True when the expression compiled and evaluated.");
                boolProp(props, "undefined", "True when the result is JSONata 'undefined' (no match).");
                stringProp(props, "error", "'compile' or 'evaluation' when ok is false.");
                stringProp(props, "message", "The compile/eval error message when ok is false.");
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
            objectSchema(schema -> {
                ObjectNode props = schema.putObject("properties");
                intProp(props, "total", "Total test cases run.");
                intProp(props, "passed", "Cases that passed.");
                intProp(props, "failed", "Cases that failed.");
                arrayProp(props, "results", "Per-case results (with per-field failures on failure).");
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
            objectSchema(),   // freeform merged state of the throwaway model
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

        // ── remote_with_browser mode only: the device-flow pairing entry point ──

        if (service instanceof BrowserPairable pairable) {
            add("pair_browser", "Pair with browser",
                "Pair this MCP session with a browser tab on the hosted Valem sandbox so both drive the "
                + "same live model session. Mints a pairing on first call (or resumes an existing "
                + "not-yet-approved one) and waits up to a minute for the developer to approve it. Returns "
                + "{status:\"paired\"|\"already_paired\", namespaceId} once done, or "
                + "{status:\"pending\", verificationUri, userCode, expiresInSec} if the developer hasn't "
                + "approved yet — tell them to open verificationUri and TYPE userCode into the approve "
                + "screen (the page never shows it; only you and they know it), then "
                + "call this tool again (it resumes the SAME pairing, it does not mint a new one). Every "
                + "other model tool (create_model, mutate, evolve_spec, get_state, explain, ...) fails "
                + "with a clear error until pairing succeeds.",
                objectSchema(),
                annotations(false, false, false),
                args -> pairable.pairBrowser(callProgress.get()));
        }
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
            if (t.outputSchema() != null) {
                node.set("outputSchema", t.outputSchema());
            }
            node.set("annotations", t.annotations());
        }
        return arr;
    }

    /** Per-call progress/cancellation channel (set by {@link #call(String, JsonNode, ProgressHandle)}). */
    private final ThreadLocal<ProgressHandle> callProgress = ThreadLocal.withInitial(() -> ProgressHandle.NONE);

    /**
     * Executes the named tool and returns the MCP {@code tools/call} result object
     * ({@code {content:[{type:"text",text:...}], structuredContent?, isError}}).
     */
    ObjectNode call(String name, JsonNode arguments) {
        return call(name, arguments, ProgressHandle.NONE);
    }

    /**
     * As {@link #call(String, JsonNode)} but with a {@link ProgressHandle} a long-running tool
     * ({@code pair_browser}) can use to emit progress and observe cancellation (§3.2).
     */
    ObjectNode call(String name, JsonNode arguments, ProgressHandle progress) {
        Tool tool = tools.get(name);
        if (tool == null) {
            return plainError("Unknown tool: '" + name + "'");
        }
        JsonNode args = (arguments == null || arguments.isNull()) ? mapper.createObjectNode() : arguments;
        callProgress.set(progress);
        try {
            return successResult(tool.handler().handle(args));
        } catch (Exception e) {
            return errorResult(e);
        } finally {
            callProgress.remove();
        }
    }

    /** The tool names the server should run on a background thread (long-poll tools). */
    boolean isLongRunning(String name) {
        return "pair_browser".equals(name);
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
        String text = pretty(node);
        // §1.3 result-size guard: a single oversized read must not wreck the session. Replace the payload
        // with a compact note telling the agent how to narrow it, and drop structuredContent (it would
        // carry the very bytes we are refusing to return).
        if (text.length() > MAX_RESULT_CHARS) {
            block.put("text", pretty(oversizeNote(text.length())));
            return result;
        }
        block.put("text", text);
        if (node.isObject()) {
            result.set("structuredContent", node);
        }
        return result;
    }

    /** The compact stand-in returned when a result exceeds {@link #MAX_RESULT_CHARS}. */
    private ObjectNode oversizeNote(int actualChars) {
        ObjectNode note = mapper.createObjectNode();
        note.put("error", "Result too large");
        note.put("resultChars", actualChars);
        note.put("limitChars", MAX_RESULT_CHARS);
        note.put("hint", "The result exceeded the size limit and was withheld. Narrow the query: for "
                + "get_state pass paths=[...] to project subtrees or depth=N to cap nesting; use get_field "
                + "for a single value; pass limit=N to explain to cap trace records.");
        return note;
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
        // §3.3: per-field schema violations — the agent can fix the offending path/keyword and retry.
        if (e instanceof SchemaViolationException sve) {
            ObjectNode err = mapper.createObjectNode();
            err.put("error", "Schema violation");
            ArrayNode arr = err.putArray("violations");
            for (SchemaViolationException.Violation v : sve.violations()) {
                ObjectNode vn = arr.addObject();
                vn.put("path", v.path());
                vn.put("keyword", v.keyword());
                vn.put("message", v.message());
            }
            return err;
        }
        // §3.3: optimistic-concurrency conflict on evolve_spec — surface {expected, actual} so the agent
        // can re-read the current version and retry instead of guessing.
        if (e instanceof SpecVersionConflictException vce) {
            ObjectNode err = mapper.createObjectNode();
            err.put("error", "Version conflict");
            err.put("expected", vce.expected());
            err.put("actual", vce.actual());
            err.put("message", messageOf(vce));
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
        typedProp(props, name, "string", description);
    }

    private static void boolProp(ObjectNode props, String name, String description) {
        typedProp(props, name, "boolean", description);
    }

    private static void intProp(ObjectNode props, String name, String description) {
        typedProp(props, name, "integer", description);
    }

    private static void arrayProp(ObjectNode props, String name, String description) {
        typedProp(props, name, "array", description);
    }

    private static void typedProp(ObjectNode props, String name, String type, String description) {
        ObjectNode p = props.putObject(name);
        p.put("type", type);
        p.put("description", description);
    }

    /** The output schema shared by {@code create_model} / {@code get_model_info} (a {@code ModelInfo}). */
    private ObjectNode modelInfoOutputSchema() {
        return objectSchema(schema -> {
            ObjectNode props = schema.putObject("properties");
            stringProp(props, "id", "The model id.");
            stringProp(props, "version", "The spec version.");
            intProp(props, "derivationCount", "Number of derivations.");
            intProp(props, "metaDerivationCount", "Number of meta-derivations.");
            intProp(props, "constraintCount", "Number of constraints.");
            intProp(props, "effectCount", "Number of effects.");
        });
    }

    /** The output schema shared by {@code mutate} / {@code patch_model} (a {@code MutationResult}). */
    private ObjectNode mutationOutputSchema() {
        return objectSchema(schema -> {
            ObjectNode props = schema.putObject("properties");
            boolProp(props, "success", "True when the mutation committed.");
            arrayProp(props, "mutatedPaths", "Base paths written.");
            arrayProp(props, "derivedUpdated", "Derived paths recomputed.");
            arrayProp(props, "metaUpdated", "Meta paths recomputed.");
            arrayProp(props, "flaggedConstraints", "Ids of FLAG-policy constraints that fired.");
            arrayProp(props, "dispatchedEffects", "Ids of effects dispatched.");
            arrayProp(props, "traces", "Full derivation/constraint traces (only when includeTraces:true).");
        });
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

    /** Registers a tool with no declared {@code outputSchema} (result shape is freeform / non-object). */
    private void add(String name, String title, String description,
                     ObjectNode inputSchema, ObjectNode annotations, Handler handler) {
        add(name, title, description, inputSchema, null, annotations, handler);
    }

    /** Registers a tool, optionally declaring the {@code outputSchema} of its {@code structuredContent}. */
    private void add(String name, String title, String description,
                     ObjectNode inputSchema, ObjectNode outputSchema, ObjectNode annotations, Handler handler) {
        tools.put(name, new Tool(name, title, description, inputSchema, outputSchema, annotations, handler));
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

    private static byte[] decodeBase64(String data) {
        try {
            return Base64.getDecoder().decode(data);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("'data' must be valid base64: " + e.getMessage());
        }
    }

    private static boolean boolArg(JsonNode args, String field, boolean dflt) {
        JsonNode n = args.get(field);
        return (n == null || n.isNull()) ? dflt : n.asBoolean(dflt);
    }

    /**
     * Caps a list to its most recent {@code limit} elements (the tail), leaving it untouched when
     * {@code limitArg} is absent or non-positive. Used by {@code explain} (§1.3).
     */
    private static List<?> tailLimit(List<?> list, JsonNode limitArg) {
        if (limitArg == null || !limitArg.isInt() || limitArg.asInt() <= 0) return list;
        int limit = limitArg.asInt();
        return list.size() <= limit ? list : list.subList(list.size() - limit, list.size());
    }

    // ── get_state projection (§1.1) ────────────────────────────────────────────────

    /**
     * Applies the optional {@code paths} projection and {@code depth} cap to a merged-state document.
     * With neither, returns the state unchanged. {@code paths} keeps only the named subtrees (spliced
     * back into a pruned document at their canonical addresses); {@code depth} then collapses containers
     * nested deeper than the limit to a compact size marker.
     */
    private JsonNode projectState(ObjectNode state, JsonNode pathsArg, JsonNode depthArg) {
        JsonNode result = state;
        if (pathsArg != null && pathsArg.isArray() && !pathsArg.isEmpty()) {
            ObjectNode projected = mapper.createObjectNode();
            for (JsonNode p : pathsArg) {
                String path = p.asText();
                JsonNode value = state.at(PathConverter.toJsonPointer(path));
                if (value != null && !value.isMissingNode()) {
                    spliceInto(projected, PathConverter.toSegments(path), value.deepCopy());
                }
            }
            result = projected;
        }
        if (depthArg != null && depthArg.isInt() && depthArg.asInt() >= 0) {
            result = trimDepth(result, depthArg.asInt());
        }
        return result;
    }

    /**
     * Splices {@code value} into {@code root} at {@code segments}, creating intermediate objects/arrays
     * as needed (the next segment being numeric selects an array). Concrete (non-wildcard) addresses only.
     */
    private void spliceInto(ObjectNode root, List<String> segments, JsonNode value) {
        if (segments.isEmpty()) return;
        JsonNode cursor = root;
        for (int i = 0; i < segments.size() - 1; i++) {
            String seg = segments.get(i);
            boolean nextIsIndex = isIndex(segments.get(i + 1));
            cursor = descend(cursor, seg, nextIsIndex);
        }
        put(cursor, segments.get(segments.size() - 1), value);
    }

    /** Returns (creating if absent) the child container of {@code parent} at {@code seg}. */
    private JsonNode descend(JsonNode parent, String seg, boolean childIsArray) {
        if (parent instanceof ArrayNode arr) {
            int idx = Integer.parseInt(seg);
            while (arr.size() <= idx) arr.addNull();
            JsonNode existing = arr.get(idx);
            if (existing == null || existing.isNull() || !existing.isContainerNode()) {
                JsonNode created = childIsArray ? mapper.createArrayNode() : mapper.createObjectNode();
                arr.set(idx, created);
                return created;
            }
            return existing;
        }
        ObjectNode obj = (ObjectNode) parent;
        JsonNode existing = obj.get(seg);
        if (existing == null || !existing.isContainerNode()) {
            JsonNode created = childIsArray ? mapper.createArrayNode() : mapper.createObjectNode();
            obj.set(seg, created);
            return created;
        }
        return existing;
    }

    private void put(JsonNode parent, String seg, JsonNode value) {
        if (parent instanceof ArrayNode arr) {
            int idx = Integer.parseInt(seg);
            while (arr.size() <= idx) arr.addNull();
            arr.set(idx, value);
        } else {
            ((ObjectNode) parent).set(seg, value);
        }
    }

    private static boolean isIndex(String seg) {
        if (seg.isEmpty()) return false;
        for (int i = 0; i < seg.length(); i++) {
            if (seg.charAt(i) < '0' || seg.charAt(i) > '9') return false;
        }
        return true;
    }

    /**
     * Returns a copy of {@code node} with objects/arrays nested deeper than {@code depth} replaced by a
     * compact size marker ({@code "<object: N fields>"} / {@code "<array: N items>"}). Scalars pass
     * through; {@code depth == 0} collapses every container.
     */
    private JsonNode trimDepth(JsonNode node, int depth) {
        if (!node.isContainerNode()) return node;
        if (depth <= 0) {
            return mapper.getNodeFactory().textNode(node.isArray()
                    ? "<array: " + node.size() + " items>"
                    : "<object: " + node.size() + " fields>");
        }
        if (node.isArray()) {
            ArrayNode out = mapper.createArrayNode();
            for (JsonNode e : node) out.add(trimDepth(e, depth - 1));
            return out;
        }
        ObjectNode out = mapper.createObjectNode();
        node.properties().forEach(e -> out.set(e.getKey(), trimDepth(e.getValue(), depth - 1)));
        return out;
    }

    /**
     * The actionable mutation summary. {@code traces} (the full inputs/expression/result records, the
     * same payload {@code explain} serves) is included only when {@code includeTraces} — repeating it on
     * every write is expensive noise, and the designed flow is to call {@code explain} when a value looks
     * wrong (§1.2).
     */
    private static Map<String, Object> mutationMap(ModelRuntime.MutationResult r, boolean includeTraces) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("success",            r.success());
        map.put("mutatedPaths",       r.mutatedPaths());
        map.put("derivedUpdated",     r.derivedUpdated());
        map.put("metaUpdated",        r.metaUpdated());
        map.put("flaggedConstraints", r.flaggedConstraints());
        map.put("dispatchedEffects",  r.dispatchedEffects());
        if (includeTraces) {
            map.put("traces",         r.traces());
        }
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
