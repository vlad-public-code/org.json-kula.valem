package org.json_kula.valem.mcp;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.json_kula.valem.cli.RemoteModelOperations;
import org.json_kula.valem.client.ValemClient;
import org.json_kula.valem.service.ModelOperations;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 2.3 gate: the pure authoring/verify tools must run against local core even in remote mode,
 * with no server reachable. Here the facade is a {@link RemoteModelOperations} pointed at an
 * unreachable URL — {@code validate_spec} and {@code dry_run} must still succeed, proving they never
 * touch the remote transport.
 */
class RemoteModeAuthoringTest {

    private static final String UNREACHABLE = "http://127.0.0.1:1"; // nothing listens here

    private final ObjectMapper mapper = new ObjectMapper();

    private ToolRegistry remoteTools() {
        ModelOperations remote = new RemoteModelOperations(new ValemClient(UNREACHABLE), mapper);
        return new ToolRegistry(remote, mapper);
    }

    @Test
    void validateSpecRunsLocallyWithServerUnreachable() throws Exception {
        ObjectNode args = mapper.createObjectNode();
        args.set("spec", mapper.readTree(
                "{\"id\":\"x\",\"version\":\"1.0.0\",\"schema\":{\"type\":\"object\"}}"));

        ObjectNode result = remoteTools().call("validate_spec", args);

        assertThat(result.get("isError").asBoolean()).isFalse();
        assertThat(result.get("structuredContent").get("valid").asBoolean()).isTrue();
    }

    @Test
    void dryRunRunsLocallyWithServerUnreachable() throws Exception {
        ObjectNode args = mapper.createObjectNode();
        args.set("spec", mapper.readTree(
                "{\"id\":\"x\",\"version\":\"1.0.0\",\"schema\":{\"type\":\"object\","
                + "\"properties\":{\"n\":{\"type\":\"number\"}}}}"));

        ObjectNode result = remoteTools().call("dry_run", args);

        assertThat(result.get("isError").asBoolean()).isFalse();
    }
}
