package org.json_kula.valem.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.json_kula.valem.core.llm.SpecGenerationPrompt;
import org.json_kula.valem.core.llm.SpecGenerationSchema;

import java.io.InputStream;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.stream.Stream;

/**
 * The Valem MCP <b>resources</b> surface: readable reference material a connected agent can pull
 * in while authoring a spec — the same substrate the runtime's own {@code SpecGenerator} feeds its LLM,
 * but handed to the (stronger) agent model instead. Exposes:
 *
 * <ul>
 *   <li>{@code valem://guide/model-spec-format} — the ModelSpec authoring guide (rules + JSONata
 *       gotchas) from {@link SpecGenerationPrompt#SYSTEM_CONTEXT}.</li>
 *   <li>{@code valem://schema/model-spec} and {@code .../spec-evolution} — the JSON Schemas from
 *       {@link SpecGenerationSchema}.</li>
 *   <li>{@code valem://examples/<name>} — the bundled example specs (working models to learn from).</li>
 * </ul>
 *
 * <p>Transport-agnostic, mirroring {@link ToolRegistry}: {@link #listNode()} builds the
 * {@code resources/list} array; {@link #read(String)} returns a {@code resources/read} result, or
 * {@code null} when the uri is unknown.
 */
class ResourceRegistry {

    private static final String EXAMPLES_DIR    = "examples";
    private static final String EXAMPLE_PREFIX  = "valem://examples/";
    /** The uriTemplate advertised by {@code resources/templates/list} for the bundled examples. */
    private static final String EXAMPLE_TEMPLATE = "valem://examples/{name}";

    private record Resource(String uri, String name, String title, String description,
                            String mimeType, Supplier<String> body) {}

    private final ObjectMapper mapper;
    private final Map<String, Resource> fixed = new LinkedHashMap<>();

    ResourceRegistry(ObjectMapper mapper) {
        this.mapper = mapper;
        register();
    }

    private void register() {
        add("valem://guide/model-spec-format", "model-spec-format",
            "ModelSpec authoring guide",
            "How to write a Valem ModelSpec: sections, path notation, JSONata rules and common "
            + "mistakes. Read this before authoring a spec.",
            "text/markdown",
            () -> SpecGenerationPrompt.SYSTEM_CONTEXT);

        add("valem://schema/model-spec", "model-spec-schema",
            "ModelSpec JSON Schema",
            "The JSON Schema for a full ModelSpec (the same schema embedded in the create_model tool).",
            "application/json",
            () -> pretty(SpecGenerationSchema.modelSpec(mapper)));

        add("valem://schema/spec-evolution", "spec-evolution-schema",
            "SpecEvolution JSON Schema",
            "The JSON Schema for a SpecEvolution diff (used by the evolve_spec tool).",
            "application/json",
            () -> pretty(SpecGenerationSchema.specEvolution(mapper)));

        add("valem://guide/jsonata-gotchas", "jsonata-gotchas",
            "JSONata gotchas cheatsheet",
            "The JSONata pitfalls that most often break Valem expressions (bindings, singleton arrays, "
            + "path dialect). Skim before writing a derivation/constraint expr.",
            "text/markdown",
            () -> JSONATA_GOTCHAS);

        add("valem://guide/spec-evolution", "spec-evolution",
            "Spec-evolution authoring guide",
            "How to author a SpecEvolution diff: targeted upserts vs wholesale sections, expectedVersion, "
            + "and state-preserving schema changes. Read before calling evolve_spec.",
            "text/markdown",
            () -> SPEC_EVOLUTION_GUIDE);

        add("valem://guide/view-system", "view-system",
            "View-system reference",
            "How an embedded viewDefinition maps model state to a component tree, the full catalog of "
            + "legal component types, and the shape get_view returns. Read before authoring a view.",
            "text/markdown",
            () -> VIEW_SYSTEM_GUIDE);
    }

    // ── Transport-facing operations ───────────────────────────────────────────────

    /** Builds the {@code resources} array for a {@code resources/list} response. */
    ArrayNode listNode() {
        ArrayNode arr = mapper.createArrayNode();
        for (Resource r : fixed.values()) {
            arr.add(descriptor(r.uri(), r.name(), r.title(), r.description(), r.mimeType()));
        }
        for (String name : exampleNames()) {
            arr.add(descriptor(EXAMPLE_PREFIX + name, name,
                    "Example spec: " + name,
                    "A ready-to-run example ModelSpec (" + name + ").",
                    "application/json"));
        }
        return arr;
    }

    /**
     * Builds the {@code resourceTemplates} array for a {@code resources/templates/list} response. The
     * bundled examples are exposed as a single {@code valem://examples/{name}} uriTemplate (§3.4) —
     * cheaper than enumerating each, and the natural pairing for a client's completions capability.
     */
    ArrayNode templatesNode() {
        ArrayNode arr = mapper.createArrayNode();
        ObjectNode ex = arr.addObject();
        ex.put("uriTemplate", EXAMPLE_TEMPLATE);
        ex.put("name", "example-spec");
        ex.put("title", "Example spec by name");
        ex.put("description", "A bundled example ModelSpec by base name (see resources/list for names).");
        ex.put("mimeType", "application/json");
        // A model's live merged state, readable and subscribable (§4.2): valem://state/<modelId>.
        ObjectNode st = arr.addObject();
        st.put("uriTemplate", "valem://state/{modelId}");
        st.put("name", "model-state");
        st.put("title", "Model state by id");
        st.put("description", "A model's live merged state; subscribe to get notifications/resources/"
                + "updated when it changes (e.g. the browser's edits in a paired session).");
        st.put("mimeType", "application/json");
        return arr;
    }

    /**
     * Returns a {@code resources/read} result ({@code {contents:[{uri,mimeType,text}]}}) for the given
     * uri, or {@code null} if no such resource exists (the caller maps that to a JSON-RPC error).
     */
    ObjectNode read(String uri) {
        Resource r = fixed.get(uri);
        if (r != null) {
            return contents(uri, r.mimeType(), r.body().get());
        }
        if (uri != null && uri.startsWith(EXAMPLE_PREFIX)) {
            String name = uri.substring(EXAMPLE_PREFIX.length());
            String body = readExample(name);
            if (body != null) {
                return contents(uri, "application/json", body);
            }
        }
        return null;
    }

    // ── Builders ──────────────────────────────────────────────────────────────────

    private ObjectNode descriptor(String uri, String name, String title, String description, String mimeType) {
        ObjectNode node = mapper.createObjectNode();
        node.put("uri", uri);
        node.put("name", name);
        node.put("title", title);
        node.put("description", description);
        node.put("mimeType", mimeType);
        return node;
    }

    private ObjectNode contents(String uri, String mimeType, String text) {
        ObjectNode result = mapper.createObjectNode();
        ObjectNode item = result.putArray("contents").addObject();
        item.put("uri", uri);
        item.put("mimeType", mimeType);
        item.put("text", text);
        return result;
    }

    private void add(String uri, String name, String title, String description,
                     String mimeType, Supplier<String> body) {
        fixed.put(uri, new Resource(uri, name, title, description, mimeType, body));
    }

    private String pretty(JsonNode node) {
        try {
            return mapper.writerWithDefaultPrettyPrinter().writeValueAsString(node);
        } catch (Exception e) {
            return String.valueOf(node);
        }
    }

    // ── Example specs on the classpath ─────────────────────────────────────────────

    /** Reads a bundled example spec's JSON text, or {@code null} if there is no such example. */
    private String readExample(String name) {
        // Reject any path trickery — examples are flat, name-only.
        if (name == null || name.isEmpty() || name.contains("/") || name.contains("..")) return null;
        try (InputStream in = getClass().getResourceAsStream("/" + EXAMPLES_DIR + "/" + name + ".json")) {
            if (in == null) return null;
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Lists the base names (no {@code .json}) of the bundled example specs. Works whether the module
     * runs from an exploded {@code target/classes} directory (dev/tests) or a packaged jar.
     */
    List<String> exampleNames() {
        try {
            URL loc = ResourceRegistry.class.getProtectionDomain().getCodeSource().getLocation();
            if (loc == null) return List.of();
            Path root = Paths.get(loc.toURI());
            List<String> names = Files.isDirectory(root) ? listFromDirectory(root) : listFromJar(root);
            Collections.sort(names);
            return names;
        } catch (Exception e) {
            return List.of();
        }
    }

    private static List<String> listFromDirectory(Path root) throws Exception {
        Path dir = root.resolve(EXAMPLES_DIR);
        if (!Files.isDirectory(dir)) return new ArrayList<>();
        try (Stream<Path> s = Files.list(dir)) {
            List<String> names = new ArrayList<>();
            s.map(p -> p.getFileName().toString())
             .filter(n -> n.endsWith(".json"))
             .forEach(n -> names.add(stripJson(n)));
            return names;
        }
    }

    private static List<String> listFromJar(Path jarPath) throws Exception {
        List<String> names = new ArrayList<>();
        try (JarFile jar = new JarFile(jarPath.toFile())) {
            Enumeration<JarEntry> entries = jar.entries();
            String prefix = EXAMPLES_DIR + "/";
            while (entries.hasMoreElements()) {
                String n = entries.nextElement().getName();
                if (n.startsWith(prefix) && n.endsWith(".json")
                        && n.indexOf('/', prefix.length()) < 0) {
                    names.add(stripJson(n.substring(prefix.length())));
                }
            }
        }
        return names;
    }

    private static String stripJson(String fileName) {
        return fileName.substring(0, fileName.length() - ".json".length());
    }

    // ── Bundled guide bodies (§3.4) ─────────────────────────────────────────────────

    private static final String JSONATA_GOTCHAS = """
            # JSONata gotchas in Valem

            Expressions (`expr` / `trigger` / meta `expr`) are JSONata, evaluated against the merged
            document. Addresses (spec `path`, mutation keys, view `bind`) are NOT JSONata — they are
            canonical JSON Path (`$.a.b[0]`). Do not mix the two.

            - **No leading `$` in expressions.** Write `order.total`, not `$.order.total`. The `$.`
              form is an address; inside an `expr` it means "the whole document, then field total".
            - **Bindings available in every expression:** `$const.<name>` (named constants),
              and in a wildcard (`$.items[*].x`) derivation `$parent` = the current array element.
              In `defaultValues`, `$parent` = the new container's JSON parent and `$self` = its
              caller-provided fields.
            - **A derivation reading only `$const` never recomputes** (constants create no dependency
              edge). Reference an input alongside it if it must react.
            - **Singleton sequences flatten.** A path/predicate that matches exactly one node yields
              that node, not a one-element array. Force an array with `[ ... ]` or `$append([], x)`
              when a downstream step needs one.
            - **Missing vs null.** A path with no match evaluates to JSONata *undefined* (nothing),
              which drops the field — it is not `null`. Guard with `field ? a : b` or `$exists(field)`.
            - **Same-level derivations can't see each other.** Derivations evaluate in topological
              level order against prior levels only; two fields in the same level cannot reference one
              another. Split into dependent levels by referencing the other field.
            - **Constraints/effects see derived fields.** They evaluate against the merged document,
              so `total` (a derived field) is visible in a constraint `expr`.
            - **Verify before committing.** Use `eval_expression` (same compiler the runtime validates
              against) for a single expr, and `test_spec` / `dry_run` for the whole reactive cascade.
            """;

    private static final String SPEC_EVOLUTION_GUIDE = """
            # Authoring a SpecEvolution

            `evolve_spec` applies an incremental diff and preserves live state — prefer it over
            delete+recreate. Set `newVersion`; optionally set `expectedVersion` to the version you read
            (the call fails with a Version conflict `{expected, actual}` if the model moved under you —
            re-read and retry).

            **Prefer targeted diffs over wholesale replacement** (they are mutually exclusive per
            section within one evolution):

            - **Derivations / constraints / effects / metaDerivations:** `upsert<Section>` (by path/id)
              and `remove<Section>` lists.
            - **Schema:** `upsertSchemaDefs` / `removeSchemaDefs` (by `$defs` name) or
              `upsertSchemaNodes` / `removeSchemaNodes` (by canonical data path, `required` tri-state) —
              or `newSchema` wholesale. A schema change that would strand existing state is rejected
              (422-equivalent); `dry_run` the evolved spec against current inputs first.
            - **View:** `upsertViews` / `removeViews` / `newDefaultView`, or `upsertComponents` /
              `removeComponents` (by id, with `parentId` / `beforeId` placement) — or `newViewDefinition`.
            - **Constants:** `upsertConstants` / `removeConstants` (removal blocked while `$const.<name>`
              is still referenced) — or `newConstants`.

            Unchanged expressions are carried forward and not recompiled. Additive evolutions backfill
            new fields where absent without overwriting existing values.
            """;

    private static final String VIEW_SYSTEM_GUIDE = """
            # View system

            A spec may embed a `viewDefinition`: a tree of components describing a UI over the model.
            `get_view` evaluates it against the current merged document and returns a renderer-agnostic
            `EvaluatedView` (a resolved component tree), not raw view spec.

            - **Components** have a `type` from the closed catalog below, an optional `bind` (a canonical
              address into the model, e.g. `$.order.total`) whose value is resolved at evaluation time,
              and children.
            - **Named views.** A definition may hold several views; `get_view` takes an optional `viewId`
              and falls back to the default view. Ids must be unique; a dangling `defaultView`/`itemView`
              is rejected at write time.
            - **Lists** render a child `itemView` per element of a bound array.
            - Evolve a view with targeted `upsertComponents` / `removeComponents` (see the spec-evolution
              guide) rather than resending the whole `viewDefinition`.

            ## Component catalog (closed set — `validate_spec` rejects anything else)

            Types are **camelCase** and exact. There is no `text`, `number-input`, `input`, `container`
            or `list` type; the near-miss names are called out below.

            **Fields** (need `bind`; `label`, `placeholder`, `helperText`, `tooltip`, `onChange` apply
            to all of them):

            | `type` | Extra fields |
            |---|---|
            | `textField` | — |
            | `textAreaField` | `rows` |
            | `numericField` | — (this is the number input) |
            | `passwordField` | — |
            | `emailField` | — |
            | `phoneNumberField` | — |
            | `checkboxField` | — (boolean) |
            | `toggleField` | — (boolean switch) |
            | `selectField` | `options`, `optionsExpr` |
            | `radioField` | `options`, `optionsExpr` |
            | `multiSelectField` | `options`, `optionsExpr` |
            | `dateField` / `dateTimeField` / `timeField` | — |
            | `sliderField` | `min`, `max`, `step` |
            | `fileUploadField` | `accept`, `multiple`, `minFiles`/`maxFiles`, `minSize`/`maxSize` |
            | `countrySelector` | — |
            | `countryRegionSelector` | `dependsOn` (bind path of a `countrySelector`) |

            **Output** (read-only):

            | `type` | Extra fields |
            |---|---|
            | `label` | `text`, or `bind` to show a value |
            | `staticText` | `text` (this is the literal-text component) |
            | `badge` | `text`, `variant` |
            | `separatorLine` | — |
            | `dataTable` | `bind` (array), `tableColumns`, `pageSize` |
            | `dataChart` | `bind` (array), `chartType`, `chartX`, `chartSeries` |
            | `progressBar` | `bind`, `min`, `max`, `showValue`, `format` |

            **Containers and actions:**

            | `type` | Key fields |
            |---|---|
            | `group` | `layout`, `columns`, `components` (this is the generic container) |
            | `fieldSet` | `legend`, `components` |
            | `sectionList` | `bind` (array), `itemView`, `canAdd`, `canRemove` (this is the list) |
            | `sectionItem` | `bind`, `components` |
            | `button` | `label`, `variant`, `icon`, `onClick` |
            | `menu` | `menuItems`, `orientation` |

            Run `validate_spec` before `create_model` — an unknown `type` is reported there with the
            closest legal spelling, whereas an unvalidated one renders as an error box in the UI.

            The `EvaluatedView` contract and the per-component evaluation rules live in the published
            view-system reference.
            """;
}
