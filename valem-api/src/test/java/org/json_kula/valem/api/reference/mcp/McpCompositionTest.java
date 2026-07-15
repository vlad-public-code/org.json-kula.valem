package org.json_kula.valem.api.reference.mcp;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.IntNode;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.json_kula.valem.api.authz.EffectApprovalRegistry;
import org.json_kula.valem.api.effects.CompositeEffectExecutor;
import org.json_kula.valem.api.effects.EffectMetrics;
import org.json_kula.valem.api.effects.EgressGuard;
import org.json_kula.valem.api.effects.HttpEffectExecutor;
import org.json_kula.valem.api.effects.LinkEffectExecutor;
import org.json_kula.valem.api.effects.LlmEffectExecutor;
import org.json_kula.valem.api.effects.TimerEffectExecutor;
import org.json_kula.valem.api.reference.ModelLink;
import org.json_kula.valem.api.reference.ModelResolver;
import org.json_kula.valem.api.reference.RepositoryClass;
import org.json_kula.valem.core.blob.InMemoryBlobStore;
import org.json_kula.valem.core.model.ModelCoordinate;
import org.json_kula.valem.core.model.ModelSpec;
import org.json_kula.valem.mcp.McpServer;
import org.json_kula.valem.service.ModelRegistry;
import org.json_kula.valem.service.ModelService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.function.BooleanSupplier;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * M6 — the {@code mcp} transport, exercised end-to-end against the <b>real</b> {@link McpServer} run over
 * an in-process pipe (real newline-delimited JSON-RPC framing, no subprocess). Proves the transport is
 * orthogonal to class (an mcp repo can be web-class) and that a cross-instance link resolves + fires over
 * MCP, folding the reply back.
 */
class McpCompositionTest {

    private final ObjectMapper mapper = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    private ModelService remote;             // instance B, exposed via the MCP server
    private McpTransport transport;
    private McpModelRepository mcpRepo;
    private Thread serverThread;

    @BeforeEach
    void startRealMcpServer() throws Exception {
        remote = new ModelService(new ModelRegistry(), new InMemoryBlobStore());
        remote.createModel(spec("""
            { "id": "agg", "version": "1.0.0", "schema": {},
              "derivations": [ {"path": "$.doubled", "expr": "in * 2"} ] }
            """));

        ObjectMapper serverMapper = new ObjectMapper()
                .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
                .configure(SerializationFeature.FAIL_ON_EMPTY_BEANS, false);
        McpServer server = new McpServer(remote, serverMapper);

        PipedInputStream serverIn = new PipedInputStream();
        PipedOutputStream clientOut = new PipedOutputStream(serverIn);
        PipedInputStream clientIn = new PipedInputStream();
        PipedOutputStream serverOut = new PipedOutputStream(clientIn);
        serverThread = new Thread(() -> { try { server.run(serverIn, serverOut); } catch (Exception ignored) { } });
        serverThread.setDaemon(true);
        serverThread.start();

        transport = new StdioMcpTransport(clientIn, clientOut, new ObjectMapper(), null);
        mcpRepo = new McpModelRepository("registry", transport, RepositoryClass.WEB);
    }

    @AfterEach
    void stop() {
        if (transport != null) transport.close();
        if (serverThread != null) serverThread.interrupt();
    }

    private ModelSpec spec(String json) throws Exception {
        return mapper.readValue(json, ModelSpec.class);
    }

    @Test
    void resolvesSpecAndReadsFieldOverMcp() {
        var coord = ModelCoordinate.parse("agg");
        assertThat(mcpRepo.resolveSpec(coord)).isPresent()
                .get().satisfies(rs -> assertThat(rs.spec().id()).isEqualTo("agg"));
        assertThat(mcpRepo.isLocalClass()).isFalse();   // web-class over an mcp transport

        remote.mutate("agg", Map.of("$.in", IntNode.valueOf(21)));
        ModelLink link = mcpRepo.resolveLink(coord).orElseThrow();
        assertThat(link.getField("$.doubled").asInt()).isEqualTo(42);   // read the derived value over MCP
    }

    @Test
    void unknownModelResolvesToEmpty() {
        // get_spec on an unknown id returns an MCP isError result → surfaced as "not resolvable here".
        assertThat(mcpRepo.resolveSpec(ModelCoordinate.parse("ghost"))).isEmpty();
        assertThat(mcpRepo.resolveLink(ModelCoordinate.parse("ghost"))).isEmpty();
    }

    @Test
    void toolErrorBecomesMcpException() {
        // A rollback/invalid mutation surfaces as an isError tool result → McpException on the link.
        remote.createModel(catchThrow(() -> spec("""
            { "id": "capped", "version": "1.0.0", "schema": {},
              "constraints": [ {"id": "cap", "expr": "$not($exists(v)) or v <= 10", "policy": "rollback"} ] }
            """)));
        ModelLink link = mcpRepo.resolveLink(ModelCoordinate.parse("capped")).orElseThrow();
        org.assertj.core.api.Assertions.assertThatThrownBy(
                () -> link.mutate(Map.of("$.v", IntNode.valueOf(99))))
                .isInstanceOf(McpTransport.McpException.class);
    }

    private static <T> T catchThrow(java.util.concurrent.Callable<T> c) {
        try { return c.call(); } catch (Exception e) { throw new RuntimeException(e); }
    }

    @Test
    void writeLinkFiresAcrossMcpTransport() throws Exception {
        // Instance A whose only repository is the MCP-hosted B.
        ModelService local = new ModelService(new ModelRegistry(), new InMemoryBlobStore());
        EffectMetrics metrics = new EffectMetrics(new SimpleMeterRegistry());
        ModelResolver resolver = new ModelResolver(List.of(mcpRepo));
        HttpEffectExecutor http = new HttpEffectExecutor(local, new EgressGuard(true, 1_048_576), metrics);
        LinkEffectExecutor link = new LinkEffectExecutor(local, resolver, metrics);
        LlmEffectExecutor llm = new LlmEffectExecutor(local, null, metrics);
        TimerEffectExecutor timer = new TimerEffectExecutor(local, metrics);
        var approvals = new EffectApprovalRegistry(EffectApprovalRegistry.Mode.APPROVE, local);
        local.setEffectExecutor(new CompositeEffectExecutor(http, link, llm, timer, approvals, local));

        local.createModel(spec("""
            { "id": "leaf", "version": "1.0.0", "schema": {},
              "effects": [ {
                "id": "push", "executor": "server",
                "trigger": "subtotal >= 0", "dedupeKey": "subtotal",
                "target": { "ref": "agg", "path": "$.in" }, "body": "subtotal",
                "response": { "set": { "$.ack": "$response.doubled" } },
                "statusPath": "$.io.push"
              } ] }
            """));

        local.mutate("leaf", Map.of("$.subtotal", IntNode.valueOf(6)));

        await(() -> remoteInt("agg", "$.in") == 6);
        await(() -> localInt(local, "leaf", "$.ack") == 12);
        assertThat(remoteInt("agg", "$.doubled")).isEqualTo(12);
    }

    private int remoteInt(String id, String path) { return asInt(remote.getFieldValue(id, path)); }
    private static int localInt(ModelService svc, String id, String path) { return asInt(svc.getFieldValue(id, path)); }
    private static int asInt(JsonNode v) {
        return v == null || v.isNull() || v.isMissingNode() ? Integer.MIN_VALUE : v.asInt();
    }

    private static void await(BooleanSupplier cond) {
        Instant deadline = Instant.now().plus(Duration.ofSeconds(5));
        while (Instant.now().isBefore(deadline)) {
            if (cond.getAsBoolean()) return;
            try { Thread.sleep(20); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        }
        throw new AssertionError("condition not met within timeout");
    }
}
