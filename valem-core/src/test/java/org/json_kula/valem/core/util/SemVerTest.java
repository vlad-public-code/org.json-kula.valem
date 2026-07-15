package org.json_kula.valem.core.util;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SemVerTest {

    @Test
    void parsesAndRoundTrips() {
        assertThat(SemVer.parse("1.2.3")).isEqualTo(new SemVer(1, 2, 3, null));
        assertThat(SemVer.parse("1.2.3").toString()).isEqualTo("1.2.3");
        assertThat(SemVer.parse("2.0.0-rc.1").toString()).isEqualTo("2.0.0-rc.1");
    }

    @Test
    void rejectsNonSemver() {
        for (String bad : new String[]{"1.0", "1", "1.0.0.0", "x.y.z", "01.0.0", "1.0.0+build", ""}) {
            assertThat(SemVer.isValid(bad)).as(bad).isFalse();
            assertThatThrownBy(() -> SemVer.parse(bad)).isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test
    void ordersByPrecedence() {
        assertThat(SemVer.parse("1.0.0")).isLessThan(SemVer.parse("2.0.0"));
        assertThat(SemVer.parse("1.2.0")).isLessThan(SemVer.parse("1.10.0"));
        assertThat(SemVer.parse("1.0.1")).isGreaterThan(SemVer.parse("1.0.0"));
        // a prerelease has lower precedence than its release
        assertThat(SemVer.parse("1.0.0-rc.1")).isLessThan(SemVer.parse("1.0.0"));
    }
}
