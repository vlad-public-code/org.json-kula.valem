package org.json_kula.valem.core.util;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class CanonicalJsonTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void keyOrderAndWhitespaceDoNotAffectDigest() throws Exception {
        var a = mapper.readTree("{\"b\":1,\"a\":{\"y\":2,\"x\":3}}");
        var b = mapper.readTree("{ \"a\" : { \"x\" : 3, \"y\" : 2 }, \"b\" : 1 }");
        assertThat(CanonicalJson.digest(a)).isEqualTo(CanonicalJson.digest(b));
    }

    @Test
    void differentContentDiffersInDigest() throws Exception {
        var a = mapper.readTree("{\"a\":1}");
        var b = mapper.readTree("{\"a\":2}");
        assertThat(CanonicalJson.digest(a)).isNotEqualTo(CanonicalJson.digest(b));
    }

    @Test
    void digestIsSha256HexAndPrefixedFormAddsScheme() throws Exception {
        var node = mapper.readTree("{\"a\":1}");
        String digest = CanonicalJson.digest(node);
        assertThat(digest).matches("[0-9a-f]{64}");
        assertThat(CanonicalJson.prefixedDigest(node)).isEqualTo("sha256:" + digest);
    }

    @Test
    void genesisIs64HexZeros() {
        assertThat(CanonicalJson.GENESIS).isEqualTo("0".repeat(64));
    }
}
