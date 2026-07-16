package org.json_kula.valem.cli;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class CliBootstrapTest {

    private static CliBootstrap.Options parse(String... args) {
        return CliBootstrap.parse(args, k -> Map.<String, String>of().get(k));
    }

    @Test
    void noArgs_isEmbedded() {
        CliBootstrap.Options opts = parse();
        assertThat(opts.remote()).isFalse();
        assertThat(opts.remoteWithBrowser()).isFalse();
    }

    @Test
    void urlAlone_isPlainRemote() {
        CliBootstrap.Options opts = parse("--url", "https://host");
        assertThat(opts.remote()).isTrue();
        assertThat(opts.remoteWithBrowser()).isFalse();
    }

    @Test
    void urlPlusBrowser_isRemoteWithBrowser() {
        CliBootstrap.Options opts = parse("--url", "https://host", "--browser");
        assertThat(opts.remote()).isTrue();
        assertThat(opts.remoteWithBrowser()).isTrue();
    }

    @Test
    void browserAlone_withoutUrl_isNotRemote() {
        CliBootstrap.Options opts = parse("--browser");
        assertThat(opts.remote()).isFalse();
        assertThat(opts.remoteWithBrowser()).isFalse();
    }

    @Test
    void urlEqualsForm_stillCombinesWithBrowser() {
        CliBootstrap.Options opts = parse("--url=https://host", "--browser");
        assertThat(opts.url()).isEqualTo("https://host");
        assertThat(opts.remoteWithBrowser()).isTrue();
    }
}
