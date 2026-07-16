package org.json_kula.valem.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.json_kula.valem.core.blob.InMemoryBlobStore;
import org.json_kula.valem.service.ModelOperations;
import org.json_kula.valem.service.ModelRegistry;
import org.json_kula.valem.service.ModelService;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code pair_browser} must appear only when the active {@link ModelOperations} facade implements
 * {@link BrowserPairable} (remote_with_browser mode) — never for embedded or plain {@code --url} mode —
 * and must call through to the facade's {@link BrowserPairable#pairBrowser()}.
 */
class ToolRegistryBrowserPairingTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** A minimal ModelOperations that ALSO implements BrowserPairable, standing in for the real facade. */
    private static final class FakePairableOperations extends ModelService implements BrowserPairable {
        private PairResult next = PairResult.pending("http://x/?pair=CODE", "WXYZ-1234", 600);

        FakePairableOperations() { super(new ModelRegistry(), new InMemoryBlobStore()); }

        @Override public PairResult pairBrowser() { return next; }
    }

    @Test
    void embeddedMode_hasNoPairBrowserTool() {
        ModelService service = new ModelService(new ModelRegistry(), new InMemoryBlobStore());
        ToolRegistry registry = new ToolRegistry(service, MAPPER);
        assertThat(registry.toolNames()).doesNotContain("pair_browser");
    }

    @Test
    void remoteWithBrowserMode_exposesPairBrowserTool() {
        ToolRegistry registry = new ToolRegistry(new FakePairableOperations(), MAPPER);
        assertThat(registry.toolNames()).contains("pair_browser");
    }

    @Test
    void pairBrowserTool_returnsThePendingResultStructurally() {
        ToolRegistry registry = new ToolRegistry(new FakePairableOperations(), MAPPER);

        ObjectNode result = registry.call("pair_browser", MAPPER.createObjectNode());

        assertThat(result.get("isError").asBoolean()).isFalse();
        JsonNode structured = result.get("structuredContent");
        assertThat(structured.path("status").asText()).isEqualTo("pending");
        assertThat(structured.path("verificationUri").asText()).contains("CODE");
        assertThat(structured.path("userCode").asText()).isEqualTo("WXYZ-1234");
    }
}
