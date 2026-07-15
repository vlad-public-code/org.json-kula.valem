package org.json_kula.valem.api.reference.mcp;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.json_kula.valem.api.reference.ModelLink;
import org.json_kula.valem.api.reference.ModelRepository;
import org.json_kula.valem.api.reference.RepositoryClass;
import org.json_kula.valem.api.reference.ResolvedSpec;
import org.json_kula.valem.core.model.ModelCoordinate;
import org.json_kula.valem.core.model.ModelSpec;
import org.json_kula.valem.core.util.CanonicalJson;
import org.json_kula.valem.core.util.SemVer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.List;
import java.util.Optional;

/**
 * The {@code mcp} transport (references design §4.1): resolves a coordinate against a model exposed by
 * an MCP server, via its {@code get_spec} / {@code create_model} tools ({@code resolveLink} handles are
 * driven by {@link McpModelLink}). Transport is orthogonal to <b>class</b>: an MCP server driven by one
 * local agent is {@code local}-class, one fronting a shared registry is {@code web}-class — the class is
 * supplied at construction, not inferred from the fact that it is MCP.
 */
public class McpModelRepository implements ModelRepository {

    private static final Logger log = LoggerFactory.getLogger(McpModelRepository.class);

    private final String id;
    private final RepositoryClass repositoryClass;
    private final McpTools tools;
    private final ObjectMapper mapper;

    public McpModelRepository(String id, McpTransport transport, RepositoryClass repositoryClass) {
        this.id = id;
        this.repositoryClass = repositoryClass;
        this.mapper = new ObjectMapper().configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
        this.tools = new McpTools(transport, mapper);
    }

    /**
     * Launches an MCP server as a child process (its stdin/stdout are the transport) from a
     * whitespace-separated command, e.g. {@code "npx valem-registry-mcp"} or
     * {@code "java -jar valem-mcp.jar"}.
     */
    public static McpModelRepository launch(String id, String command, RepositoryClass repositoryClass)
            throws IOException {
        ProcessBuilder pb = new ProcessBuilder(command.trim().split("\\s+"));
        pb.redirectError(ProcessBuilder.Redirect.INHERIT);   // server diagnostics go to our stderr
        Process process = pb.start();
        ObjectMapper mapper = new ObjectMapper();
        McpTransport transport = new StdioMcpTransport(
                process.getInputStream(), process.getOutputStream(), mapper, process);
        return new McpModelRepository(id, transport, repositoryClass);
    }

    @Override
    public String id() {
        return id;
    }

    @Override
    public RepositoryClass repositoryClass() {
        return repositoryClass;
    }

    private Optional<ModelSpec> fetchSpec(ModelCoordinate coord) {
        try {
            ObjectNode args = mapper.createObjectNode();
            args.put("id", coord.identity());
            JsonNode spec = tools.callStructured("get_spec", args);
            if (spec == null) return Optional.empty();
            return Optional.of(mapper.treeToValue(spec, ModelSpec.class));
        } catch (McpTransport.McpException e) {
            return Optional.empty();   // not found / server error → fall through the chain
        } catch (Exception e) {
            log.warn("mcp repo '{}' failed resolving '{}': {}", id, coord.identity(), e.toString());
            return Optional.empty();
        }
    }

    private static boolean versionMatches(ModelCoordinate coord, ModelSpec spec) {
        if (!SemVer.isValid(spec.version())) return false;
        return coord.version() instanceof ModelCoordinate.Digest
                || coord.satisfiedBy(SemVer.parse(spec.version()));
    }

    @Override
    public List<SemVer> listVersions(ModelCoordinate nameOnly) {
        return fetchSpec(nameOnly)
                .filter(s -> SemVer.isValid(s.version()))
                .map(s -> List.of(SemVer.parse(s.version())))
                .orElse(List.of());
    }

    @Override
    public Optional<ResolvedSpec> resolveSpec(ModelCoordinate coord) {
        return fetchSpec(coord)
                .filter(spec -> versionMatches(coord, spec))
                .map(spec -> new ResolvedSpec(spec,
                        coord.withExactVersion(SemVer.parse(spec.version())),
                        CanonicalJson.prefixedDigest(spec), id));
    }

    @Override
    public Optional<ModelLink> resolveLink(ModelCoordinate coord) {
        return fetchSpec(coord)
                .filter(spec -> versionMatches(coord, spec))
                .map(spec -> new McpModelLink(tools, coord.identity(), coord));
    }

    @Override
    public boolean canPublish() {
        return true;   // the create_model tool
    }

    @Override
    public void publish(ModelSpec materialized) {
        ObjectNode args = mapper.createObjectNode();
        args.set("spec", mapper.valueToTree(materialized));
        tools.callStructured("create_model", args);   // throws McpException on invalid/duplicate
    }
}
