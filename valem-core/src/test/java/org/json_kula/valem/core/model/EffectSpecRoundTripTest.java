package org.json_kula.valem.core.model;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Regression: an effect's {@code response.set} must survive a full serialize→deserialize round-trip
 * (a spec store→reload cycle), not be silently dropped. Guards the fold-back mapping composition needs.
 */
class EffectSpecRoundTripTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void responseSetSurvivesRoundTrip() throws Exception {
        String json = "{\"id\":\"e\",\"executor\":\"server\",\"trigger\":\"true\","
                + "\"request\":{\"url\":\"https://x\"},"
                + "\"response\":{\"set\":{\"$.ack\":\"$response.ack\",\"$.n\":\"$response.n\"}}}";
        EffectSpec spec = mapper.readValue(json, EffectSpec.class);
        assertThat(spec.responseSet()).containsKeys("$.ack", "$.n");

        String serialized = mapper.writeValueAsString(spec);
        assertThat(serialized).contains("\"response\"").doesNotContain("responseSet");

        EffectSpec again = mapper.readValue(serialized, EffectSpec.class);
        assertThat(again.responseSet()).isEqualTo(spec.responseSet());
    }
}
