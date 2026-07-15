package org.json_kula.valem.console;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration tests for ConsoleApp that exercise the full stdin → stdout JSON-line protocol.
 */
class ConsoleAppIntegrationTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private InputStream  savedIn;
    private PrintStream  savedOut;
    private ByteArrayOutputStream capturedOut;

    @BeforeEach
    void captureStreams() {
        savedIn  = System.in;
        savedOut = System.out;
        capturedOut = new ByteArrayOutputStream();
        System.setOut(new PrintStream(capturedOut, true, StandardCharsets.UTF_8));
    }

    @AfterEach
    void restoreStreams() {
        System.setIn(savedIn);
        System.setOut(savedOut);
    }

    @Test
    void eof_on_empty_input_produces_no_output() throws Exception {
        feed("");
        ConsoleApp.main(new String[0]);
        assertThat(output()).isEmpty();
    }

    @Test
    void blank_lines_are_silently_skipped() throws Exception {
        feed("\n\n\n");
        ConsoleApp.main(new String[0]);
        assertThat(output()).isEmpty();
    }

    @Test
    void exit_command_returns_ok_true_with_bye() throws Exception {
        feed("{\"cmd\":\"exit\"}\n");
        ConsoleApp.main(new String[0]);

        String[] lines = lines();
        assertThat(lines).hasSize(1);
        JsonNode r = MAPPER.readTree(lines[0]);
        assertThat(r.path("ok").asBoolean()).isTrue();
        assertThat(r.path("result").asText()).isEqualTo("bye");
    }

    @Test
    void invalid_json_returns_ok_false_with_error() throws Exception {
        feed("not-json-at-all\n");
        ConsoleApp.main(new String[0]);

        String[] lines = lines();
        assertThat(lines).hasSize(1);
        JsonNode r = MAPPER.readTree(lines[0]);
        assertThat(r.path("ok").asBoolean()).isFalse();
        assertThat(r.path("error").asText()).contains("Invalid JSON");
    }

    @Test
    void unknown_command_returns_ok_false_with_error() throws Exception {
        feed("{\"cmd\":\"no-such-cmd\"}\n");
        ConsoleApp.main(new String[0]);

        String[] lines = lines();
        assertThat(lines).hasSize(1);
        JsonNode r = MAPPER.readTree(lines[0]);
        assertThat(r.path("ok").asBoolean()).isFalse();
        assertThat(r.path("error").asText()).contains("Unknown command");
    }

    @Test
    void list_models_returns_empty_array_initially() throws Exception {
        feed("{\"cmd\":\"list-models\"}\n");
        ConsoleApp.main(new String[0]);

        JsonNode r = MAPPER.readTree(lines()[0]);
        assertThat(r.path("ok").asBoolean()).isTrue();
        assertThat(r.path("result").isArray()).isTrue();
        assertThat(r.path("result").size()).isEqualTo(0);
    }

    @Test
    void full_create_mutate_get_state_cycle() throws Exception {
        String spec = """
                {"id":"io-order","version":"1.0.0","schema":{},
                 "derivations":[{"path":"$.total","expr":"price * qty"}],
                 "constraints":[],"actions":[],"metaDerivations":[],"tests":[]}
                """.replace('\n', ' ');

        String input = String.join("\n",
                "{\"cmd\":\"create-model\",\"spec\":" + spec + "}",
                "{\"cmd\":\"mutate\",\"id\":\"io-order\",\"mutations\":{\"$.price\":5,\"$.qty\":3}}",
                "{\"cmd\":\"get-state\",\"id\":\"io-order\"}",
                "{\"cmd\":\"exit\"}"
        ) + "\n";

        feed(input);
        ConsoleApp.main(new String[0]);

        String[] lines = lines();
        assertThat(lines).hasSize(4);

        // create-model
        JsonNode create = MAPPER.readTree(lines[0]);
        assertThat(create.path("ok").asBoolean()).isTrue();
        assertThat(create.path("result").path("status").asText()).isEqualTo("created");

        // mutate
        JsonNode mutate = MAPPER.readTree(lines[1]);
        assertThat(mutate.path("ok").asBoolean()).isTrue();
        assertThat(mutate.path("result").path("success").asBoolean()).isTrue();

        // get-state
        JsonNode state = MAPPER.readTree(lines[2]);
        assertThat(state.path("ok").asBoolean()).isTrue();
        assertThat(state.path("result").path("total").asDouble()).isEqualTo(15.0);

        // exit
        JsonNode exit = MAPPER.readTree(lines[3]);
        assertThat(exit.path("ok").asBoolean()).isTrue();
    }

    @Test
    void lines_after_exit_are_not_processed() throws Exception {
        feed("{\"cmd\":\"exit\"}\n{\"cmd\":\"list-models\"}\n");
        ConsoleApp.main(new String[0]);
        // Only the exit response should appear, not list-models
        assertThat(lines()).hasSize(1);
    }

    // ── helpers ────────────────────────────────────────────────────────────────

    private void feed(String input) {
        System.setIn(new ByteArrayInputStream(input.getBytes(StandardCharsets.UTF_8)));
    }

    private String output() {
        return capturedOut.toString(StandardCharsets.UTF_8).strip();
    }

    private String[] lines() {
        String out = output();
        if (out.isEmpty()) return new String[0];
        return out.split("\n");
    }
}
