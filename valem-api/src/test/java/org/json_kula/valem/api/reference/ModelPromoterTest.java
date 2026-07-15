package org.json_kula.valem.api.reference;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.json_kula.valem.core.blob.InMemoryBlobStore;
import org.json_kula.valem.core.model.ModelSpec;
import org.json_kula.valem.service.ModelRegistry;
import org.json_kula.valem.service.ModelService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * M6 — mobility: promote a local model into a web ({@code http}) repository, gated by the
 * reference-locality closure. A local-only dependency blocks promotion; a demote (web→local) is refused.
 */
class ModelPromoterTest {

    private final ObjectMapper mapper = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    private HttpServer server;
    private ModelService web;     // the web repo's backing instance

    @AfterEach
    void tearDown() { if (server != null) server.stop(0); }

    private ModelSpec spec(String json) throws Exception {
        return mapper.readValue(json, ModelSpec.class);
    }

    private ModelPromoter promoterOver(ModelService local, String webBaseUrl) {
        ModelResolver resolver = new ModelResolver(List.of(
                new LocalModelRepository(local),
                new HttpModelRepository("web", webBaseUrl, null)));
        return new ModelPromoter(local, resolver);
    }

    @Test
    void promotesSelfContainedModelToWebRepo() throws Exception {
        web = new ModelService(new ModelRegistry(), new InMemoryBlobStore());
        String baseUrl = startWebFacade(web);

        ModelService local = new ModelService(new ModelRegistry(), new InMemoryBlobStore());
        local.createModel(spec("{\"id\":\"widget\",\"version\":\"1.0.0\",\"schema\":{}}"));

        promoterOver(local, baseUrl).promote("widget", "web");

        // Now resolvable on the web repo.
        assertThat(web.getSpec("widget").id()).isEqualTo("widget");
    }

    @Test
    void promoteBlockedByLocalOnlyDependencyThenSucceedsAfterPromotingIt() throws Exception {
        web = new ModelService(new ModelRegistry(), new InMemoryBlobStore());
        String baseUrl = startWebFacade(web);

        ModelService local = new ModelService(new ModelRegistry(), new InMemoryBlobStore());
        local.createModel(spec("{\"id\":\"helper\",\"version\":\"1.0.0\",\"schema\":{}}"));
        local.createModel(spec("""
            { "id": "widget", "version": "1.0.0", "schema": {},
              "effects": [ {
                "id": "call", "executor": "server", "trigger": "true", "dedupeKey": "n",
                "target": { "ref": "helper", "path": "$.in" }, "body": "n", "statusPath": "$.io.call"
              } ] }
            """));
        ModelPromoter promoter = promoterOver(local, baseUrl);

        // 'helper' is local-only → the closure check blocks promoting 'widget'.
        assertThatThrownBy(() -> promoter.promote("widget", "web"))
                .isInstanceOf(ReferenceException.PromotionClosureFailure.class)
                .hasMessageContaining("helper");

        // Promote the dependency first (bottom-up), then 'widget' succeeds.
        promoter.promote("helper", "web");
        assertThatCode(() -> promoter.promote("widget", "web")).doesNotThrowAnyException();
        assertThat(web.getSpec("widget").id()).isEqualTo("widget");
    }

    @Test
    void classIsIndependentOfTransport_httpRepoMarkedLocalIsRefusedAsPromoteTarget() throws Exception {
        // An http transport can be either class; when configured local-class it is not a web target,
        // so promoting into it is a refused demote — proving class ⟂ transport.
        ModelService local = new ModelService(new ModelRegistry(), new InMemoryBlobStore());
        local.createModel(spec("{\"id\":\"widget\",\"version\":\"1.0.0\",\"schema\":{}}"));
        ModelResolver resolver = new ModelResolver(List.of(
                new LocalModelRepository(local),
                new HttpModelRepository("dev-mirror", "http://127.0.0.1:1", null, RepositoryClass.LOCAL)));
        ModelPromoter promoter = new ModelPromoter(local, resolver);

        assertThatThrownBy(() -> promoter.promote("widget", "dev-mirror"))
                .isInstanceOf(ReferenceException.DemoteUnsupported.class);
    }

    @Test
    void demoteToLocalRepoIsRefused() throws Exception {
        ModelService local = new ModelService(new ModelRegistry(), new InMemoryBlobStore());
        local.createModel(spec("{\"id\":\"widget\",\"version\":\"1.0.0\",\"schema\":{}}"));
        ModelResolver resolver = new ModelResolver(List.of(new LocalModelRepository(local)));
        ModelPromoter promoter = new ModelPromoter(local, resolver);

        assertThatThrownBy(() -> promoter.promote("widget", "local"))
                .isInstanceOf(ReferenceException.DemoteUnsupported.class);
    }

    // ── minimal web facade: GET /models/{id}/spec + POST /models ──

    private String startWebFacade(ModelService svc) throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/models", exchange -> {
            try {
                String path = exchange.getRequestURI().getPath();
                String method = exchange.getRequestMethod();
                byte[] out = "{}".getBytes(StandardCharsets.UTF_8);
                int code = 200;
                if (method.equals("POST") && path.equals("/models")) {
                    ModelSpec s = mapper.readValue(exchange.getRequestBody().readAllBytes(), ModelSpec.class);
                    svc.createModel(s);
                    code = 201;
                } else if (method.equals("GET") && path.endsWith("/spec")) {
                    String id = path.split("/")[2];
                    try { out = mapper.writeValueAsBytes(svc.getSpec(id)); }
                    catch (RuntimeException notFound) { exchange.sendResponseHeaders(404, -1); exchange.close(); return; }
                } else {
                    exchange.sendResponseHeaders(404, -1); exchange.close(); return;
                }
                exchange.getResponseHeaders().add("Content-Type", "application/json");
                exchange.sendResponseHeaders(code, out.length);
                try (OutputStream os = exchange.getResponseBody()) { os.write(out); }
            } catch (Exception e) {
                byte[] err = e.toString().getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(500, err.length);
                try (OutputStream os = exchange.getResponseBody()) { os.write(err); }
            }
        });
        server.start();
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }
}
