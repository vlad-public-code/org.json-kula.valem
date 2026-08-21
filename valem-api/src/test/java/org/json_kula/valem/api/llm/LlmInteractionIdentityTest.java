package org.json_kula.valem.api.llm;

import org.json_kula.valem.core.llm.LlmClient;
import org.json_kula.valem.core.llm.LlmDescriptor;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * That the interaction log records which LLM answered each call.
 *
 * <p>Per record rather than once globally: a routing deployment can answer two calls of one
 * generation from two different providers, so "the configured model" is not an answer.
 */
class LlmInteractionIdentityTest {

    @Test
    void a_recorded_call_names_the_provider_and_model_underneath() {
        LlmInteractionLog log = new LlmInteractionLog(true);
        LlmClient identified = new StubClient(new LlmDescriptor("groq", "openai/gpt-oss-120b"), false);

        new RecordingLlmClient(identified, log).complete("give me a spec");

        assertThat(log.getAll()).singleElement().satisfies(r -> {
            assertThat(r.provider()).isEqualTo("groq");
            assertThat(r.model()).isEqualTo("openai/gpt-oss-120b");
        });
    }

    @Test
    void a_failed_call_still_names_the_model_that_failed() {
        // The case the field is most wanted for: the error row is useless without it.
        LlmInteractionLog log = new LlmInteractionLog(true);
        LlmClient failing = new StubClient(new LlmDescriptor("groq", "openai/gpt-oss-120b"), true);

        assertThatThrownBy(() -> new RecordingLlmClient(failing, log).complete("boom"))
                .isInstanceOf(LlmClient.LlmException.class);

        assertThat(log.getAll()).singleElement().satisfies(r -> {
            assertThat(r.errorMessage()).isNotNull();
            assertThat(r.provider()).isEqualTo("groq");
            assertThat(r.model()).isEqualTo("openai/gpt-oss-120b");
        });
    }

    @Test
    void the_identity_is_read_before_the_call_not_after() {
        // A routing delegate answers describe() with the provider the NEXT call would use. Reading
        // it after a failure would therefore name the provider that takes over, mislabelling the row
        // that just failed as belonging to a provider which never ran.
        LlmInteractionLog log = new LlmInteractionLog(true);
        LlmClient rerouting = new ReroutingClient();

        assertThatThrownBy(() -> new RecordingLlmClient(rerouting, log).complete("boom"))
                .isInstanceOf(LlmClient.LlmException.class);

        assertThat(log.getAll()).singleElement().satisfies(r ->
                assertThat(r.provider())
                        .as("the provider that actually ran, not its replacement")
                        .isEqualTo("primary"));
    }

    @Test
    void an_unidentified_client_records_nulls_rather_than_a_guess() {
        LlmInteractionLog log = new LlmInteractionLog(true);

        new RecordingLlmClient(prompt -> "ok", log).complete("give me a spec");

        assertThat(log.getAll()).singleElement().satisfies(r -> {
            assertThat(r.provider()).isNull();
            assertThat(r.model()).isNull();
        });
    }

    @Test
    void the_decorators_pass_the_identity_through() {
        // A decorator that forgets to delegate describe() erases the identity for everything above
        // it — and the wrapping order (recording outside, limiting inside) means every layer counts.
        LlmClient identified = new StubClient(new LlmDescriptor("groq", "openai/gpt-oss-120b"), false);
        LlmClient wrapped = new RecordingLlmClient(
                new ConcurrencyLimitingLlmClient(identified, 1), new LlmInteractionLog(true));

        assertThat(wrapped.describe()).isEqualTo(new LlmDescriptor("groq", "openai/gpt-oss-120b"));
    }

    @Test
    void redaction_keeps_the_model_metadata_it_drops_the_content_for() {
        // capture-content=false exists to drop prompt/response text, not the operational trail. A
        // provider name and a public model id carry no domain data, and "a call failed" is not much
        // of a trail without them.
        LlmInteractionLog redacting = new LlmInteractionLog(false);
        LlmClient identified = new StubClient(new LlmDescriptor("groq", "openai/gpt-oss-120b"), false);

        new RecordingLlmClient(identified, redacting).complete("a prompt full of domain data");

        assertThat(redacting.getAll()).singleElement().satisfies(r -> {
            assertThat(r.prompt()).isEqualTo("[redacted]");
            assertThat(r.response()).isEqualTo("[redacted]");
            assertThat(r.provider()).isEqualTo("groq");
            assertThat(r.model()).isEqualTo("openai/gpt-oss-120b");
        });
    }

    @Test
    void the_pre_identity_constructors_still_compile_to_unknown() {
        // Callers that predate the fields keep working and simply record no identity.
        LlmInteractionRecord legacy = new LlmInteractionRecord(
                java.time.Instant.now(), "p", "r", null, 1L, List.of());

        assertThat(legacy.provider()).isNull();
        assertThat(legacy.model()).isNull();
    }

    // ── Fixtures ──────────────────────────────────────────────────────────────

    private record StubClient(LlmDescriptor descriptor, boolean fail) implements LlmClient {
        @Override
        public String complete(String prompt) {
            if (fail) throw new LlmException("provider exploded");
            return "{}";
        }

        @Override
        public LlmDescriptor describe() {
            return descriptor;
        }
    }

    /** Fails, and afterwards reports a different provider — a circuit opening under the recorder. */
    private static final class ReroutingClient implements LlmClient {
        private boolean failed = false;

        @Override
        public String complete(String prompt) {
            failed = true;
            throw new LlmException("provider exploded");
        }

        @Override
        public LlmDescriptor describe() {
            return failed ? new LlmDescriptor("backup", "model-b")
                          : new LlmDescriptor("primary", "model-a");
        }
    }
}
