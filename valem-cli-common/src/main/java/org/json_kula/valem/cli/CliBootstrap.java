package org.json_kula.valem.cli;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.json_kula.valem.client.ValemClient;
import org.json_kula.valem.core.blob.InMemoryBlobStore;
import org.json_kula.valem.service.ModelOperations;
import org.json_kula.valem.service.ModelRegistry;
import org.json_kula.valem.service.ModelService;

import java.util.ArrayList;
import java.util.List;
import java.util.function.UnaryOperator;

/**
 * Shared launch plumbing for the Valem CLIs (MCP + console): parse the mode-selection arguments
 * and build the right {@link ModelOperations} — embedded (the zero-config, in-memory default) or
 * remote (against a running {@code valem-web}).
 *
 * <p>The embedded default is deliberately untouchable: with no {@code --url} (and no
 * {@code VALEM_URL}) each CLI behaves exactly as before — in-process, in-memory, offline-capable.
 * {@code --url} (or the env var) opts into remote mode; an API key may be supplied with
 * {@code --api-key} or {@code VALEM_API_KEY}. Flags take precedence over environment variables.
 *
 * <p>The API key is never logged, echoed, or placed in any diagnostic here (credential hygiene, R2.6).
 */
public final class CliBootstrap {

    private CliBootstrap() {}

    /** Parsed launch options. {@link #remote()} is true iff a non-blank base URL was supplied. */
    public record Options(String url, String apiKey, boolean help, boolean version, List<String> unknown) {
        public boolean remote() { return url != null && !url.isBlank(); }
    }

    /**
     * Parses CLI arguments, falling back to {@code VALEM_URL} / {@code VALEM_API_KEY} from
     * the supplied environment lookup. Recognises {@code --url}, {@code --api-key} (space- or
     * {@code =}-separated), {@code --help}/{@code -h}, {@code --version}/{@code -V}. Any other token is
     * collected in {@link Options#unknown()} for the caller to report.
     */
    public static Options parse(String[] args, UnaryOperator<String> env) {
        String url    = env.apply("VALEM_URL");
        String apiKey = env.apply("VALEM_API_KEY");
        boolean help = false;
        boolean version = false;
        List<String> unknown = new ArrayList<>();

        for (int i = 0; i < args.length; i++) {
            String a = args[i];
            switch (a) {
                case "--help", "-h"    -> help = true;
                case "--version", "-V" -> version = true;
                case "--url"           -> url    = requireValue(args, ++i, a);
                case "--api-key"       -> apiKey = requireValue(args, ++i, a);
                default -> {
                    if (a.startsWith("--url="))          url    = a.substring("--url=".length());
                    else if (a.startsWith("--api-key=")) apiKey = a.substring("--api-key=".length());
                    else unknown.add(a);
                }
            }
        }
        return new Options(blankToNull(url), blankToNull(apiKey), help, version, List.copyOf(unknown));
    }

    /**
     * Builds the {@link ModelOperations} for these options: a {@link RemoteModelOperations} over the
     * Java SDK when {@link Options#remote()}, otherwise an embedded in-memory {@link ModelService}
     * (today's default).
     */
    @SuppressWarnings("deprecation") // core InMemoryBlobStore: lean dep tree; embedded CLI state is ephemeral
    public static ModelOperations createModelOperations(Options opts, ObjectMapper mapper) {
        if (opts.remote()) {
            return new RemoteModelOperations(new ValemClient(opts.url(), opts.apiKey()), mapper);
        }
        return new ModelService(new ModelRegistry(), new InMemoryBlobStore());
    }

    private static String requireValue(String[] args, int i, String flag) {
        if (i >= args.length) throw new IllegalArgumentException("Missing value for " + flag);
        return args[i];
    }

    private static String blankToNull(String s) {
        return (s == null || s.isBlank()) ? null : s;
    }
}
