package org.json_kula.valem.core.model;

import org.json_kula.valem.core.model.ModelCoordinate.Digest;
import org.json_kula.valem.core.model.ModelCoordinate.Exact;
import org.json_kula.valem.core.model.ModelCoordinate.Range;
import org.json_kula.valem.core.model.ModelCoordinate.Unversioned;
import org.json_kula.valem.core.util.SemVer;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ModelCoordinateTest {

    @Test
    void parsesBareName() {
        ModelCoordinate c = ModelCoordinate.parse("order");
        assertThat(c.namespace()).isNull();
        assertThat(c.name()).isEqualTo("order");
        assertThat(c.version()).isEqualTo(Unversioned.INSTANCE);
        assertThat(c.toString()).isEqualTo("order");
    }

    @Test
    void parsesNamespaceNameAndExactVersion() {
        ModelCoordinate c = ModelCoordinate.parse("acme.finance/consolidation@2.1.0");
        assertThat(c.namespace()).isEqualTo("acme.finance");
        assertThat(c.name()).isEqualTo("consolidation");
        assertThat(c.identity()).isEqualTo("acme.finance/consolidation");
        assertThat(c.version()).isEqualTo(new Exact(new SemVer(2, 1, 0, null)));
        assertThat(c.isExact()).isTrue();
        assertThat(c.toString()).isEqualTo("acme.finance/consolidation@2.1.0");
    }

    @Test
    void parsesRanges() {
        assertThat(ModelCoordinate.parse("a/b@^1.4.0").version())
                .isEqualTo(new Range(ModelCoordinate.Op.CARET, new SemVer(1, 4, 0, null)));
        assertThat(ModelCoordinate.parse("a/b@>=2.0.0").version())
                .isEqualTo(new Range(ModelCoordinate.Op.GTE, new SemVer(2, 0, 0, null)));
        assertThat(ModelCoordinate.parse("a/b@~1.2.3").version())
                .isEqualTo(new Range(ModelCoordinate.Op.TILDE, new SemVer(1, 2, 3, null)));
    }

    @Test
    void parsesDigest() {
        String hex = "9f2c" + "0".repeat(60);
        ModelCoordinate c = ModelCoordinate.parse("a/b@sha256:" + hex);
        assertThat(c.version()).isEqualTo(new Digest("sha256:" + hex));
        assertThat(c.isExact()).isTrue();
        assertThat(c.toString()).isEqualTo("a/b@sha256:" + hex);
    }

    @Test
    void roundTripsCanonicalRender() {
        for (String s : new String[]{
                "order", "a/b", "a.b.c/name", "a/b@1.0.0", "a/b@^1.0.0", "a/b@>=1.2.3-rc.1"}) {
            assertThat(ModelCoordinate.parse(s).toString()).isEqualTo(s);
        }
    }

    @Test
    void rejectsMalformed() {
        for (String bad : new String[]{
                "", "  ", "1name", "a//b", "a/b/c", "a/b@1.0", "a/b@@1.0.0",
                "a/b@1.0.0.0", "a b", "-lead/name", "a/b@sha256:xyz", "a/b@notaversion"}) {
            assertThat(ModelCoordinate.isValid(bad)).as("should reject: '%s'", bad).isFalse();
            assertThatThrownBy(() -> ModelCoordinate.parse(bad))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test
    void satisfiedByRespectsRangeSemantics() {
        ModelCoordinate caret = ModelCoordinate.parse("a/b@^1.4.0");
        assertThat(caret.satisfiedBy(new SemVer(1, 4, 0, null))).isTrue();
        assertThat(caret.satisfiedBy(new SemVer(1, 9, 9, null))).isTrue();
        assertThat(caret.satisfiedBy(new SemVer(2, 0, 0, null))).isFalse();
        assertThat(caret.satisfiedBy(new SemVer(1, 3, 0, null))).isFalse();

        ModelCoordinate tilde = ModelCoordinate.parse("a/b@~1.2.3");
        assertThat(tilde.satisfiedBy(new SemVer(1, 2, 9, null))).isTrue();
        assertThat(tilde.satisfiedBy(new SemVer(1, 3, 0, null))).isFalse();

        ModelCoordinate exact = ModelCoordinate.parse("a/b@2.0.0");
        assertThat(exact.satisfiedBy(new SemVer(2, 0, 0, null))).isTrue();
        assertThat(exact.satisfiedBy(new SemVer(2, 0, 1, null))).isFalse();

        assertThat(ModelCoordinate.parse("a/b").satisfiedBy(new SemVer(9, 9, 9, null))).isTrue();
    }

    @Test
    void withExactVersionReplacesSpec() {
        ModelCoordinate resolved = ModelCoordinate.parse("a/b@^1.0.0")
                .withExactVersion(new SemVer(1, 5, 2, null));
        assertThat(resolved.toString()).isEqualTo("a/b@1.5.2");
        assertThat(resolved.isExact()).isTrue();
    }
}
