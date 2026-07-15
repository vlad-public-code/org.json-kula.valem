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

    private static final String EXAMPLES_DIR   = "examples";
    private static final String EXAMPLE_PREFIX = "valem://examples/";

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
}
