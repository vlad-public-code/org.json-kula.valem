package org.json_kula.valem.core.graph;

import org.json_kula.valem.core.engine.ExpressionCache;
import org.json_kula.valem.core.engine.TestCaseRunner;
import org.json_kula.valem.core.engine.spi.EffectKind;
import org.json_kula.valem.core.engine.spi.EffectKindRegistry;
import org.json_kula.valem.core.engine.spi.EffectValidationContext;
import org.json_kula.valem.core.model.ConstraintSpec;
import org.json_kula.valem.core.model.DefaultValueSpec;
import org.json_kula.valem.core.model.DerivationSpec;
import org.json_kula.valem.core.model.EffectSpec;
import org.json_kula.valem.core.model.LineageEntry;
import org.json_kula.valem.core.model.MetaDerivationSpec;
import org.json_kula.valem.core.model.ModelCoordinate;
import org.json_kula.valem.core.model.ModelSpec;
import org.json_kula.valem.core.model.TargetSpec;
import org.json_kula.valem.core.state.PathConverter;
import org.json_kula.valem.core.util.SemVer;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Pre-compilation validator for {@link ModelSpec}.
 *
 * <p>Checks are ordered so that structural problems (blank ids, missing exprs) are
 * reported before the more expensive expression-compilation and cycle-detection passes.
 * Expression validation re-uses {@link ExpressionCache} so each expression is compiled
 * at most once per call to {@link #validate}.
 */
public final class ModelSpecValidator {

    public enum Severity { ERROR, WARNING }

    /** A single validation finding. */
    public record ValidationError(String location, String message, Severity severity) {}

    /** Aggregated result of a validation pass. */
    public record ValidationResult(List<ValidationError> findings) {

        /** Returns {@code true} only when no ERROR-level findings exist. */
        public boolean isValid() {
            return findings.stream().noneMatch(f -> f.severity() == Severity.ERROR);
        }

        public List<ValidationError> errors() {
            return findings.stream().filter(f -> f.severity() == Severity.ERROR).toList();
        }

        public List<ValidationError> warnings() {
            return findings.stream().filter(f -> f.severity() == Severity.WARNING).toList();
        }
    }

    private ModelSpecValidator() {}

    /**
     * Validates the given spec and returns all findings.
     *
     * <p>Never throws — all problems are returned as {@link ValidationError} entries.
     */
    public static ValidationResult validate(ModelSpec spec) {
        List<ValidationError> findings = new ArrayList<>();
        ExpressionCache cache = new ExpressionCache();

        checkSpec(spec, findings);
        checkSchema(spec, findings);
        checkViewDefinition(spec, findings);
        checkDerivations(spec, cache, findings);
        checkMetaDerivations(spec, cache, findings);
        checkConstraints(spec, cache, findings);
        checkEffects(spec, cache, findings);
        checkDefaultValues(spec, cache, findings);
        checkConstants(spec, findings);
        checkTemplateAndLineage(spec, findings);

        // Cycle detection — skip if expression errors were found to avoid cascading noise
        boolean hasErrors = findings.stream().anyMatch(f -> f.severity() == Severity.ERROR);
        if (!hasErrors) {
            checkCycles(spec, findings);
            // Run embedded test cases only when the spec compiles cleanly
            boolean cycleErrorAdded = findings.stream().anyMatch(f -> f.severity() == Severity.ERROR);
            if (!cycleErrorAdded) {
                checkTests(spec, findings);
            }
        }

        return new ValidationResult(List.copyOf(findings));
    }

    // ── Per-section checks ────────────────────────────────────────────────────

    private static void checkSpec(ModelSpec spec, List<ValidationError> out) {
        if (isBlank(spec.id())) {
            out.add(error("id", "Model id is required and must not be blank"));
        } else if (!ModelCoordinate.isValid(spec.id()) || spec.id().indexOf('@') >= 0) {
            out.add(error("id", "Model id '" + spec.id() + "' is not a valid coordinate identity — "
                    + "it must be '[namespace/]name' where each segment starts with a letter "
                    + "(letters, digits, '-', '_'); no version/'@'."));
        }
        // Version must be semver (references design §3.2). A legacy non-semver version is rejected with
        // a migration pointer, consistent with how removed-shape specs fail loudly.
        if (!isBlank(spec.version()) && !SemVer.isValid(spec.version())) {
            out.add(error("version", "Model version '" + spec.version() + "' is not semver — "
                    + "edit it to MAJOR.MINOR.PATCH (e.g. '1.0.0'). A coordinate's version must be "
                    + "semver so ranges and digests resolve reproducibly."));
        }
        if (spec.schema() == null || spec.schema().isNull() || spec.schema().isMissingNode()) {
            out.add(error("schema", "Model schema is required"));
        }
    }

    /**
     * Shape checks for {@code template} (authored) and {@code lineage} (materializer-written): the
     * coordinate must be well-formed and the ancestor chain must be acyclic (no repeated coordinate
     * identity). Existence / resolution / true DAG-across-repos is the api materializer's job.
     */
    private static void checkTemplateAndLineage(ModelSpec spec, List<ValidationError> out) {
        if (spec.template() != null) {
            String ref = spec.template().ref();
            if (isBlank(ref)) {
                out.add(error("template.ref", "template requires a ref coordinate"));
            } else if (!ModelCoordinate.isValid(ref)) {
                out.add(error("template.ref", "invalid template coordinate: '" + ref + "'"));
            }
        }
        Set<String> seenAncestors = new LinkedHashSet<>();
        int i = 0;
        for (LineageEntry entry : spec.lineage()) {
            String loc = "lineage[" + i++ + "]";
            if (isBlank(entry.ref())) {
                out.add(error(loc + ".ref", "lineage entry requires a ref"));
                continue;
            }
            if (!ModelCoordinate.isValid(entry.ref())) {
                out.add(error(loc + ".ref", "invalid lineage coordinate: '" + entry.ref() + "'"));
            }
            String identity = ModelCoordinate.isValid(entry.ref())
                    ? ModelCoordinate.parse(entry.ref()).identity() : entry.ref();
            if (!seenAncestors.add(identity)) {
                out.add(error(loc + ".ref", "lineage is not acyclic — repeated ancestor: " + identity));
            }
        }
    }

    /**
     * Validates JSON Schema {@code $ref}/{@code $defs} usage. Only local definition refs
     * ({@code #/$defs/<Name>}) are supported; everything else is rejected loudly so a spec
     * cannot silently lose validation behind an unresolved ref.
     */
    private static void checkSchema(ModelSpec spec, List<ValidationError> out) {
        com.fasterxml.jackson.databind.JsonNode schema = spec.schema();
        if (schema == null || !schema.isObject()) return;  // presence handled by checkSpec

        com.fasterxml.jackson.databind.JsonNode defs = schema.path("$defs");
        Set<String> defNames = new LinkedHashSet<>();
        if (defs.isObject()) defs.fieldNames().forEachRemaining(defNames::add);

        Set<String> referenced = new LinkedHashSet<>();
        walkSchemaRefs(schema, "schema", defNames, referenced, out);

        for (String name : defNames) {
            if (!referenced.contains(name)) {
                out.add(warn("schema.$defs." + name,
                        "definition '" + name + "' is declared but never referenced by any $ref"));
            }
        }
    }

    /** Recursively visits every object node, checking each {@code $ref} it carries. */
    private static void walkSchemaRefs(
            com.fasterxml.jackson.databind.JsonNode node,
            String loc,
            Set<String> defNames,
            Set<String> referenced,
            List<ValidationError> out) {
        if (node == null) return;
        if (node.isArray()) {
            int i = 0;
            for (com.fasterxml.jackson.databind.JsonNode child : node) {
                walkSchemaRefs(child, loc + "[" + i++ + "]", defNames, referenced, out);
            }
            return;
        }
        if (!node.isObject()) return;

        com.fasterxml.jackson.databind.JsonNode ref = node.get("$ref");
        if (ref != null) {
            String name = SchemaPaths.localDefName(ref);
            if (name == null) {
                out.add(error(loc + ".$ref",
                        "unsupported $ref '" + (ref.isTextual() ? ref.asText() : ref)
                        + "' — only local definition refs of the form '#/$defs/<Name>' are supported"));
            } else {
                referenced.add(name);
                if (!defNames.contains(name)) {
                    out.add(error(loc + ".$ref",
                            "$ref targets '#/$defs/" + name + "' but no such definition exists in $defs"));
                }
                if (node.size() > 1) {
                    out.add(warn(loc,
                            "keywords alongside $ref are ignored (only the $ref is resolved)"));
                }
            }
        }

        // Reject a malformed `pattern` regex here (422 at create/evolve) rather than letting it slip to
        // runtime, where an uncompilable pattern would leave the field silently unvalidated.
        com.fasterxml.jackson.databind.JsonNode pattern = node.get("pattern");
        if (pattern != null && pattern.isTextual()) {
            try {
                java.util.regex.Pattern.compile(pattern.asText());
            } catch (java.util.regex.PatternSyntaxException e) {
                out.add(error(loc + ".pattern",
                        "invalid regular expression '" + pattern.asText() + "': " + e.getDescription()));
            }
        }

        node.fields().forEachRemaining(e ->
                walkSchemaRefs(e.getValue(), loc + "." + e.getKey(), defNames, referenced, out));
    }

    /**
     * Structural checks over the raw {@code viewDefinition} JSON (no {@code valem-view}
     * dependency): view-id and per-view component-id uniqueness, and non-dangling
     * {@code defaultView} / {@code sectionList.itemView} references. Closes the render-time-500
     * gap for the structural class of errors; full typed-parse validation is the service layer's.
     */
    private static void checkViewDefinition(ModelSpec spec, List<ValidationError> out) {
        com.fasterxml.jackson.databind.JsonNode vd = spec.viewDefinition();
        if (vd == null || !vd.isObject()) return;
        com.fasterxml.jackson.databind.JsonNode views = vd.get("views");
        if (views == null || !views.isArray()) return;

        Set<String> viewIds = new LinkedHashSet<>();
        for (com.fasterxml.jackson.databind.JsonNode view : views) {
            String vid = textField(view, "id");
            if (vid == null) {
                out.add(error("viewDefinition.views", "a view is missing its 'id'"));
                continue;
            }
            if (!viewIds.add(vid)) {
                out.add(error("viewDefinition.views", "Duplicate view id: " + vid));
            }
            Set<String> componentIds = new LinkedHashSet<>();
            collectComponentIds(view.get("components"), vid, componentIds, out);
        }

        String defaultView = textField(vd, "defaultView");
        if (defaultView != null && !viewIds.contains(defaultView)) {
            out.add(error("viewDefinition.defaultView",
                    "defaultView '" + defaultView + "' names no view"));
        }
        for (com.fasterxml.jackson.databind.JsonNode view : views) {
            checkItemViews(view.get("components"), viewIds, out);
        }
    }

    private static void collectComponentIds(
            com.fasterxml.jackson.databind.JsonNode components, String viewId,
            Set<String> ids, List<ValidationError> out) {
        if (components == null || !components.isArray()) return;
        for (com.fasterxml.jackson.databind.JsonNode c : components) {
            String id = textField(c, "id");
            if (id != null && !ids.add(id)) {
                out.add(error("viewDefinition.views." + viewId,
                        "Duplicate component id in view '" + viewId + "': " + id));
            }
            collectComponentIds(c.get("components"), viewId, ids, out);
        }
    }

    private static void checkItemViews(
            com.fasterxml.jackson.databind.JsonNode components, Set<String> viewIds,
            List<ValidationError> out) {
        if (components == null || !components.isArray()) return;
        for (com.fasterxml.jackson.databind.JsonNode c : components) {
            String itemView = textField(c, "itemView");
            if (itemView != null && !viewIds.contains(itemView)) {
                out.add(error("viewDefinition", "itemView '" + itemView + "' names no view"));
            }
            checkItemViews(c.get("components"), viewIds, out);
        }
    }

    private static String textField(com.fasterxml.jackson.databind.JsonNode node, String field) {
        if (node == null) return null;
        com.fasterxml.jackson.databind.JsonNode v = node.get(field);
        return v != null && v.isTextual() && !v.asText().isBlank() ? v.asText() : null;
    }

    private static void checkConstants(ModelSpec spec, List<ValidationError> out) {
        for (Map.Entry<String, com.fasterxml.jackson.databind.JsonNode> e : spec.constants().entrySet()) {
            String name = e.getKey();
            if (isBlank(name)) {
                out.add(error("constants", "constant name must not be blank"));
            } else if (!name.matches("[A-Za-z_][A-Za-z0-9_]*")) {
                // $const.<name> navigation needs a simple identifier; warn rather than reject so an
                // author can still reference an unusual key via $const."odd-name".
                out.add(warn("constants." + name,
                        "constant name '" + name + "' is not a simple identifier; reference it as "
                        + "$const.\"" + name + "\" rather than $const." + name));
            }
        }
    }

    private static void checkDefaultValues(ModelSpec spec, ExpressionCache cache, List<ValidationError> out) {
        Set<String> seen = new LinkedHashSet<>();
        int i = 0;
        for (DefaultValueSpec d : spec.defaultValues()) {
            String loc = "defaultValues[" + i++ + "]";
            if (isBlank(d.path())) {
                out.add(error(loc, "path is required"));
            } else if (!seen.add(d.path())) {
                out.add(error(loc + ".path", "Duplicate defaultValues path: " + d.path()));
            } else {
                // A container address: the root "$", an object path, or an array-element pattern
                // ($.items[*]). checkAddress enforces the canonical dialect; any form is accepted.
                checkAddress(d.path(), loc + ".path", out);
            }
            if (isBlank(d.expr())) {
                out.add(error(loc, "expr is required"));
            } else {
                validateExpr(d.expr(), loc + ".expr", cache, out);
            }
        }
    }

    private static void checkDerivations(ModelSpec spec, ExpressionCache cache, List<ValidationError> out) {
        Set<String> seen = new LinkedHashSet<>();
        int i = 0;
        for (DerivationSpec d : spec.derivations()) {
            String loc = "derivations[" + i++ + "]";
            if (isBlank(d.path())) {
                out.add(error(loc, "path is required"));
            } else if (!seen.add(d.path())) {
                out.add(error(loc + ".path", "Duplicate derivation path: " + d.path()));
            } else {
                checkAddress(d.path(), loc + ".path", out);
            }
            if (isBlank(d.expr())) {
                out.add(error(loc, "expr is required"));
            } else {
                validateExpr(d.expr(), loc + ".expr", cache, out);
            }
        }
    }

    private static void checkMetaDerivations(ModelSpec spec, ExpressionCache cache, List<ValidationError> out) {
        Set<String> seen = new LinkedHashSet<>();
        int i = 0;
        for (MetaDerivationSpec md : spec.metaDerivations()) {
            String loc = "metaDerivations[" + i++ + "]";
            if (isBlank(md.path())) {
                out.add(error(loc, "path is required"));
            } else {
                checkAddress(md.path(), loc + ".path", out);
            }
            if (md.property() == null) {
                out.add(error(loc, "property is required"));
            }
            if (!isBlank(md.path()) && md.property() != null) {
                String nodeKey = md.nodeKey();
                if (!seen.add(nodeKey)) {
                    out.add(error(loc, "Duplicate meta-derivation node key: " + nodeKey));
                }
            }
            if (isBlank(md.expr())) {
                out.add(error(loc, "expr is required"));
            } else {
                validateExpr(md.expr(), loc + ".expr", cache, out);
            }
        }
    }

    private static void checkConstraints(ModelSpec spec, ExpressionCache cache, List<ValidationError> out) {
        Set<String> seen = new LinkedHashSet<>();
        int i = 0;
        for (ConstraintSpec c : spec.constraints()) {
            String loc = "constraints[" + i++ + "]";
            if (isBlank(c.id())) {
                out.add(error(loc, "id is required"));
            } else if (!seen.add(c.id())) {
                out.add(error(loc + ".id", "Duplicate constraint id: " + c.id()));
            }
            if (isBlank(c.expr())) {
                out.add(error(loc, "expr is required"));
            } else {
                validateExpr(c.expr(), loc + ".expr", cache, out);
            }
            if (c.policy() == null) {
                out.add(error(loc, "policy is required (rollback | flag | warn)"));
            }
            if (isBlank(c.message())) {
                out.add(warn(loc, "message is empty; consider providing a human-readable violation message"));
            }
        }
    }

    private static final Set<String> ALLOWED_EFFECT_METHODS =
            Set.of("GET", "POST", "PUT", "PATCH", "DELETE");

    private static void checkEffects(ModelSpec spec, ExpressionCache cache, List<ValidationError> out) {
        Set<String> seen = new LinkedHashSet<>();
        int i = 0;
        for (EffectSpec e : spec.effects()) {
            String loc = "effects[" + i++ + "]";
            if (isBlank(e.id())) {
                out.add(error(loc, "id is required"));
            } else if (!seen.add(e.id())) {
                out.add(error(loc + ".id", "Duplicate effect id: " + e.id()));
            }
            if (isBlank(e.trigger())) {
                out.add(error(loc, "trigger is required"));
            } else {
                validateExpr(e.trigger(), loc + ".trigger", cache, out);
            }
            if (!isBlank(e.dedupeKey())) {
                validateExpr(e.dedupeKey(), loc + ".dedupeKey", cache, out);
            }
            if (!isBlank(e.statusPath())) {
                checkAddress(e.statusPath(), loc + ".statusPath", out);
            }

            String kind = !isBlank(e.executor()) ? e.executor() : EffectKindRegistry.SERVER;
            EffectKindRegistry registry = EffectKindRegistry.get();
            if (!registry.isEnabled(kind)) {
                out.add(error(loc + ".executor", "unknown or disabled effect executor kind: '" + kind + "'"));
            } else switch (kind) {
                case EffectKindRegistry.CALLER -> {
                    if (isBlank(e.emit())) {
                        out.add(error(loc, "caller-executor effect requires an emit event name"));
                    }
                    // payload values reference model state only — safe to compile.
                    for (Map.Entry<String, String> pe : e.payload().entrySet()) {
                        validateExpr(pe.getValue(), loc + ".payload." + pe.getKey(), cache, out);
                    }
                }
                case EffectKindRegistry.SERVER -> {
                    checkServerEffect(e, loc, cache, out);
                    checkResponseSetTargets(e, loc, out);
                }
                case EffectKindRegistry.LLM -> {
                    if (isBlank(e.prompt())) {
                        out.add(error(loc + ".prompt", "llm-executor effect requires a prompt expression"));
                    } else {
                        validateExpr(e.prompt(), loc + ".prompt", cache, out);
                    }
                    checkResponseSetTargets(e, loc, out);
                }
                case EffectKindRegistry.TIMER -> {
                    boolean hasAt = !isBlank(e.at());
                    boolean hasAfter = !isBlank(e.afterMs());
                    if (!hasAt && !hasAfter) {
                        out.add(error(loc, "timer-executor effect requires 'at' (absolute) or 'afterMs' (delay)"));
                    }
                    if (hasAt)    validateExpr(e.at(), loc + ".at", cache, out);
                    if (hasAfter) validateExpr(e.afterMs(), loc + ".afterMs", cache, out);
                    checkResponseSetTargets(e, loc, out);
                }
                default -> {
                    // Plugin kind: isEnabled(kind) guarantees a discovered EffectKind. Delegate the
                    // kind-specific shape check; the shared fold-back targets are checked here.
                    EffectKind plugin = registry.plugin(kind);
                    plugin.validate(e, loc, effectValidationCtx(cache, out));
                    checkResponseSetTargets(e, loc, out);
                }
            }
        }
    }

    /** Wraps the static validator primitives as an {@link EffectValidationContext} for a plugin kind. */
    private static EffectValidationContext effectValidationCtx(ExpressionCache cache, List<ValidationError> out) {
        return new EffectValidationContext() {
            @Override public void error(String location, String message) {
                out.add(ModelSpecValidator.error(location, message));
            }
            @Override public void validateExpr(String expr, String location) {
                ModelSpecValidator.validateExpr(expr, location, cache, out);
            }
            @Override public void checkAddress(String address, String location) {
                ModelSpecValidator.checkAddress(address, location, out);
            }
        };
    }

    /**
     * A {@code server} effect must have <b>exactly one</b> locator: {@code request.url}, a
     * {@code requests} fan-out, a write-{@code target} ({@code ref} + {@code path}), or a
     * read-{@code target} ({@code ref} + {@code read}). Reference <em>existence</em> and locality are
     * validated in the api {@code CompositionValidator}; here only shape.
     */
    private static void checkServerEffect(EffectSpec e, String loc, ExpressionCache cache,
                                          List<ValidationError> out) {
        boolean hasUrl      = e.request() != null && !isBlank(e.request().url());
        boolean hasRequests = !isBlank(e.requests());
        boolean hasTarget   = e.target() != null && !isBlank(e.target().ref());

        int locators = (hasUrl ? 1 : 0) + (hasRequests ? 1 : 0) + (hasTarget ? 1 : 0);
        if (locators == 0) {
            out.add(error(loc, "server-executor effect requires exactly one of: request.url, a "
                    + "requests fan-out, or a target link (target.ref)"));
            return;
        }
        if (locators > 1) {
            out.add(error(loc, "server-executor effect has more than one locator — request.url, "
                    + "requests, and target are mutually exclusive"));
        }

        if (hasRequests) {
            validateExpr(e.requests(), loc + ".requests", cache, out);
        }
        if (hasUrl) {
            String method = e.request().method();
            if (method != null && !ALLOWED_EFFECT_METHODS.contains(method.toUpperCase())) {
                out.add(error(loc + ".request.method", "unsupported HTTP method: " + method));
            }
            if (!isBlank(e.request().body())) {
                validateExpr(e.request().body(), loc + ".request.body", cache, out);
            }
        }
        if (hasTarget) {
            checkTarget(e, loc, cache, out);
        } else if (!isBlank(e.body())) {
            out.add(error(loc + ".body", "top-level 'body' is only valid on a target write-link"));
        }
    }

    /** Validates a composition {@code target} block (coordinate shape + write/read exclusivity). */
    private static void checkTarget(EffectSpec e, String loc, ExpressionCache cache,
                                    List<ValidationError> out) {
        TargetSpec t = e.target();
        if (!ModelCoordinate.isValid(t.ref())) {
            out.add(error(loc + ".target.ref", "invalid model coordinate: '" + t.ref() + "'"));
        }
        boolean hasPath = !isBlank(t.path());
        boolean hasRead = !isBlank(t.read());
        if (hasPath == hasRead) {
            out.add(error(loc + ".target", "target must have exactly one of 'path' (write-link) or "
                    + "'read' (read-link)"));
        }
        if (hasRead) {
            checkAddress(t.read(), loc + ".target.read", out);
            if (!isBlank(e.body())) {
                out.add(error(loc + ".body", "a read-link (target.read) must not carry a body"));
            }
        }
        if (hasPath) {
            checkAddress(t.path(), loc + ".target.path", out);
            if (!isBlank(e.body())) {
                validateExpr(e.body(), loc + ".body", cache, out);
            }
        }
        if (t.binding() != null && isBlank(t.binding().transport())) {
            out.add(error(loc + ".target.binding.transport", "binding requires a transport"));
        }
    }

    /** response.set targets must be canonical writable addresses. Value expressions reference the
     *  runtime-only {@code $response} binding, so they are resolved by the executor, not compiled here. */
    private static void checkResponseSetTargets(EffectSpec e, String loc, List<ValidationError> out) {
        for (String target : e.responseSet().keySet()) {
            checkAddress(target, loc + ".response.set['" + target + "']", out);
        }
    }

    private static void checkTests(ModelSpec spec, List<ValidationError> out) {
        if (spec.tests().isEmpty()) return;
        for (TestCaseRunner.TestResult result : TestCaseRunner.run(spec, spec.tests())) {
            if (result.failed()) {
                String label = result.description() != null
                        ? "'" + result.description() + "'" : "(unnamed)";
                for (TestCaseRunner.FieldFailure f : result.failures()) {
                    out.add(warn("tests", "Test " + label + " failed: " + f.message()));
                }
            }
        }
    }

    private static void checkCycles(ModelSpec spec, List<ValidationError> out) {
        try {
            ModelSpecCompiler.compile(spec);
        } catch (DependencyGraph.CyclicDependencyException cde) {
            out.add(error("graph",
                    "Cyclic dependency detected among nodes: " + cde.involvedNodes()));
        } catch (Exception e) {
            // Any other compilation problem not already caught by expression checks
            out.add(error("graph", "Compilation failed: " + e.getMessage()));
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private static void validateExpr(String expr, String location,
                                     ExpressionCache cache, List<ValidationError> out) {
        try {
            cache.get(expr);
        } catch (ExpressionCache.CompilationException ce) {
            out.add(error(location, "Invalid JSONata expression — " + ce.getMessage()));
        }
        warnOnDynamicNavigation(expr, location, out);
    }

    /**
     * Best-effort soundness check (see architecture/reactive-engine.md "Soundness invariant"):
     * reactive correctness requires every input to be a <em>statically extractable</em> path so the
     * dependency graph has an edge for it. Constructs whose effective path is computed at runtime —
     * {@code $lookup(obj, dynamicKey)} and {@code $eval(...)} — are invisible to
     * {@link ExpressionPathExtractor}, so a change to the field they read will not mark this node
     * dirty and its value can go silently stale. Surface a warning rather than failing, since the
     * author may know the referenced field never changes.
     */
    private static void warnOnDynamicNavigation(String expr, String location, List<ValidationError> out) {
        if (expr == null) return;
        if (expr.contains("$lookup(")) {
            out.add(warn(location, "expression uses $lookup() — dynamic key lookup is not seen by "
                    + "static dependency extraction; the result may go stale when the looked-up "
                    + "field changes. Prefer a static path."));
        }
        if (expr.contains("$eval(")) {
            out.add(warn(location, "expression uses $eval() — runtime-evaluated JSONata defeats "
                    + "static dependency extraction; dependencies cannot be tracked and the value "
                    + "may go stale. Avoid $eval in reactive expressions."));
        }
    }

    /**
     * Validates a Valem <b>address</b> (a path used as data — not a JSONata expression body)
     * against the DEC-6 dialect: JSON Path, {@code $.}-rooted, with bracket array indices. The
     * legacy dot-index form ({@code $.items.0.x}) and unrooted forms ({@code items[0].x}) are
     * rejected as ERRORs. Expression bodies are never passed here — they may use any JSONata-valid
     * navigation.
     */
    private static void checkAddress(String address, String location, List<ValidationError> out) {
        if (isBlank(address)) return; // required-ness is checked by the caller
        if (!PathConverter.isCanonicalAddress(address)) {
            out.add(error(location, "Non-canonical address '" + address + "' — addresses must be JSON "
                    + "Path with a $.-root and bracket array indices, e.g. '"
                    + PathConverter.toCanonicalAddress(address) + "'."));
        }
    }

    private static boolean isBlank(String s) { return s == null || s.isBlank(); }

    private static ValidationError error(String location, String message) {
        return new ValidationError(location, message, Severity.ERROR);
    }

    private static ValidationError warn(String location, String message) {
        return new ValidationError(location, message, Severity.WARNING);
    }
}
