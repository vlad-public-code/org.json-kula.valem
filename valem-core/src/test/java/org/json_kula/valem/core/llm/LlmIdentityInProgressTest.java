package org.json_kula.valem.core.llm;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * That a generation log can say <em>which</em> LLM answered.
 *
 * <p>The identity has to travel from the client, through {@link SpecGenerator}, onto every
 * {@code LlmRequesting} event — per attempt rather than once per session, because a routing
 * deployment can answer attempt 2 from a different provider than attempt 1.
 */
class LlmIdentityInProgressTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final String VALID_SPEC = """
            {"id":"m","schema":{"type":"object","properties":{"a":{"type":"number"}}}}""";

    @Test
    void the_requesting_event_names_the_provider_and_model_the_client_reports() {
        LlmClient stub = new StubClient(new LlmDescriptor("groq", "openai/gpt-oss-120b"));

        List<LlmProgressEvent> events = generateCollectingProgress(stub);

        assertThat(requestingEvents(events))
                .isNotEmpty()
                .allSatisfy(e -> {
                    assertThat(e.provider()).isEqualTo("groq");
                    assertThat(e.model()).isEqualTo("openai/gpt-oss-120b");
                });
    }

    @Test
    void a_client_that_cannot_identify_itself_leaves_the_fields_null() {
        // The LlmClient default: a lambda or a test stub stays a valid client, and the log simply
        // says "LLM" rather than inventing a name.
        LlmClient bare = prompt -> VALID_SPEC;

        assertThat(bare.describe()).isNull();
        assertThat(requestingEvents(generateCollectingProgress(bare)))
                .isNotEmpty()
                .allSatisfy(e -> {
                    assertThat(e.provider()).isNull();
                    assertThat(e.model()).isNull();
                });
    }

    @Test
    void each_attempt_re_reads_the_identity_so_a_mid_generation_switch_is_visible() {
        // The reason this is per-event and not announced once: under provider routing, the repair
        // attempt can land on a different model than the first attempt did.
        SwitchingClient switching = new SwitchingClient();

        List<LlmProgressEvent.LlmRequesting> requesting =
                requestingEvents(generateCollectingProgress(switching));

        assertThat(requesting).hasSizeGreaterThan(1);
        assertThat(requesting.getFirst().provider()).isEqualTo("primary");
        assertThat(requesting.get(1).provider())
                .as("the second attempt reports the provider that actually took over")
                .isEqualTo("backup");
    }

    @Test
    void label_joins_what_is_known_and_is_null_when_nothing_is() {
        assertThat(new LlmDescriptor("groq", "gpt-oss").label()).isEqualTo("groq · gpt-oss");
        assertThat(new LlmDescriptor("groq", null).label()).isEqualTo("groq");
        assertThat(new LlmDescriptor(null, "gpt-oss").label()).isEqualTo("gpt-oss");
        assertThat(new LlmDescriptor(null, null).label()).isNull();
        assertThat(new LlmDescriptor("  ", "  ").label()).isNull();
    }

    // ── Fixtures ──────────────────────────────────────────────────────────────

    private static List<LlmProgressEvent> generateCollectingProgress(LlmClient client) {
        List<LlmProgressEvent> events = new ArrayList<>();
        new SpecGenerator(client, MAPPER, 2).generate("m", "desc", false, events::add);
        return events;
    }

    private static List<LlmProgressEvent.LlmRequesting> requestingEvents(List<LlmProgressEvent> events) {
        return events.stream()
                .filter(LlmProgressEvent.LlmRequesting.class::isInstance)
                .map(LlmProgressEvent.LlmRequesting.class::cast)
                .toList();
    }

    private record StubClient(LlmDescriptor descriptor) implements LlmClient {
        @Override
        public String complete(String prompt) {
            return VALID_SPEC;
        }

        @Override
        public LlmDescriptor describe() {
            return descriptor;
        }
    }

    /** Fails the first attempt, then answers as a different provider — a failover in miniature. */
    private static final class SwitchingClient implements LlmClient {
        private int calls = 0;

        @Override
        public String complete(String prompt) {
            return ++calls == 1 ? "not a spec at all" : VALID_SPEC;
        }

        @Override
        public LlmDescriptor describe() {
            return calls == 0
                    ? new LlmDescriptor("primary", "model-a")
                    : new LlmDescriptor("backup", "model-b");
        }
    }
}
