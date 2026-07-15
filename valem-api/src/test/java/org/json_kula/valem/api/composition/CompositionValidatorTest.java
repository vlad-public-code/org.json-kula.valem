package org.json_kula.valem.api.composition;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.json_kula.valem.core.model.ModelSpec;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CompositionValidatorTest {

    private final ObjectMapper mapper = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    private ModelSpec spec(String json) throws Exception {
        return mapper.readValue(json, ModelSpec.class);
    }

    /** A server link to {@code target}, optionally guarded (statusPath + dedupeKey). */
    private String linkModel(String id, String target, boolean guarded) {
        String guard = guarded ? ",\"dedupeKey\":\"n\",\"statusPath\":\"$.io.x\"" : "";
        return "{\"id\":\"" + id + "\",\"version\":\"1.0.0\",\"schema\":{},"
                + "\"effects\":[{\"id\":\"lnk\",\"executor\":\"server\",\"trigger\":\"true\""
                + guard + ",\"target\":{\"ref\":\"" + target + "\",\"path\":\"$.in\"},\"body\":\"n\"}]}";
    }

    @Test
    void guardedTwoCycleIsAccepted() throws Exception {
        CompositionValidator v = new CompositionValidator(true);
        ModelSpec a = spec(linkModel("a", "b", true));
        ModelSpec b = spec(linkModel("b", "a", true));
        assertThatCode(() -> v.validate(b, List.of(a))).doesNotThrowAnyException();
    }

    @Test
    void unguardedTwoCycleIsRejected() throws Exception {
        CompositionValidator v = new CompositionValidator(true);
        ModelSpec a = spec(linkModel("a", "b", false));
        ModelSpec b = spec(linkModel("b", "a", true));   // b guarded, a not → a's edge on a cycle
        assertThatThrownBy(() -> v.validate(b, List.of(a)))
                .isInstanceOf(CompositionException.UnguardedCycle.class);
    }

    @Test
    void acyclicUnguardedLinkIsFine() throws Exception {
        CompositionValidator v = new CompositionValidator(true);
        ModelSpec a = spec(linkModel("a", "b", false));   // a -> b, no cycle
        ModelSpec b = spec("{\"id\":\"b\",\"version\":\"1.0.0\",\"schema\":{}}");
        assertThatCode(() -> v.validate(a, List.of(b))).doesNotThrowAnyException();
    }

    @Test
    void unresolvedTargetRejectedWhenNotLazy() throws Exception {
        CompositionValidator strict = new CompositionValidator(false);
        ModelSpec a = spec(linkModel("a", "ghost", true));
        assertThatThrownBy(() -> strict.validate(a, List.of()))
                .isInstanceOf(CompositionException.UnresolvedLinkTarget.class);

        CompositionValidator lazy = new CompositionValidator(true);
        assertThatCode(() -> lazy.validate(a, List.of())).doesNotThrowAnyException();
    }

    @Test
    void extractsLinkEdges() throws Exception {
        List<CompositionValidator.LinkEdge> edges =
                CompositionValidator.linkEdges(spec(linkModel("a", "acme/b@^1.0.0", true)));
        assertThat(edges).singleElement().satisfies(e -> {
            assertThat(e.from()).isEqualTo("a");
            assertThat(e.to()).isEqualTo("acme/b");
            assertThat(e.guarded()).isTrue();
        });
    }
}
