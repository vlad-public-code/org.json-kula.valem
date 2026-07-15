package org.json_kula.valem.api.reference;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.json_kula.valem.core.model.ModelCoordinate;
import org.json_kula.valem.core.model.ModelSpec;
import org.json_kula.valem.core.util.CanonicalJson;
import org.json_kula.valem.core.util.SemVer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Optional;

/**
 * The {@code http} (web-class) transport (references design §4.1): resolves a coordinate against a
 * remote Valem instance's REST API. {@code resolveSpec} fetches {@code GET /models/{id}/spec};
 * {@code resolveLink} returns an {@link HttpModelLink} driving the remote model. The locator is a
 * server-configured, trusted endpoint (with an optional credential), distinct from the spec-provided-URL
 * effect egress guard.
 */
public class HttpModelRepository implements ModelRepository {

    private static final Logger log = LoggerFactory.getLogger(HttpModelRepository.class);

    private final String id;
    private final String baseUrl;   // e.g. https://models.acme.internal
    private final String apiKey;    // optional bearer credential for a private repo
    private final RepositoryClass repositoryClass;
    private final HttpClient http;
    private final ObjectMapper mapper;

    /** Defaults to {@link RepositoryClass#WEB} (a remote instance is shared/addressable). */
    public HttpModelRepository(String id, String baseUrl, String apiKey) {
        this(id, baseUrl, apiKey, RepositoryClass.WEB);
    }

    public HttpModelRepository(String id, String baseUrl, String apiKey, RepositoryClass repositoryClass) {
        this.id = id;
        this.baseUrl = stripTrailingSlash(baseUrl);
        this.apiKey = apiKey;
        this.repositoryClass = repositoryClass;
        this.http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
        this.mapper = new ObjectMapper().configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
    }

    @Override
    public String id() {
        return id;
    }

    @Override
    public RepositoryClass repositoryClass() {
        return repositoryClass;
    }

    /** Fetches the remote spec for {@code coord}'s identity, or empty on 404 / unreachable. */
    private Optional<ModelSpec> fetchSpec(ModelCoordinate coord) {
        try {
            HttpRequest.Builder rb = HttpRequest.newBuilder(
                    URI.create(baseUrl + "/models/" + coord.identity() + "/spec"))
                    .timeout(Duration.ofSeconds(10)).GET();
            if (apiKey != null && !apiKey.isBlank()) rb.header("Authorization", "Bearer " + apiKey);
            HttpResponse<byte[]> resp = http.send(rb.build(), HttpResponse.BodyHandlers.ofByteArray());
            if (resp.statusCode() == 404) return Optional.empty();
            if (resp.statusCode() >= 300) {
                log.warn("repo '{}' returned HTTP {} for spec '{}'", id, resp.statusCode(), coord.identity());
                return Optional.empty();
            }
            return Optional.of(mapper.readValue(resp.body(), ModelSpec.class));
        } catch (Exception e) {
            log.warn("repo '{}' unreachable resolving '{}': {}", id, coord.identity(), e.toString());
            return Optional.empty();
        }
    }

    private static boolean versionMatches(ModelCoordinate coord, ModelSpec spec) {
        if (!SemVer.isValid(spec.version())) return false;
        return coord.version() instanceof ModelCoordinate.Digest
                || coord.satisfiedBy(SemVer.parse(spec.version()));
    }

    @Override
    public List<SemVer> listVersions(ModelCoordinate nameOnly) {
        return fetchSpec(nameOnly)
                .filter(s -> SemVer.isValid(s.version()))
                .map(s -> List.of(SemVer.parse(s.version())))
                .orElse(List.of());
    }

    @Override
    public Optional<ResolvedSpec> resolveSpec(ModelCoordinate coord) {
        return fetchSpec(coord)
                .filter(spec -> versionMatches(coord, spec))
                .map(spec -> {
                    String digest = CanonicalJson.prefixedDigest(spec);
                    if (coord.version() instanceof ModelCoordinate.Digest pinned
                            && !pinned.sha256().equals(digest)) {
                        throw new ReferenceException.DigestMismatch(
                                "repo '" + id + "' served '" + coord.identity() + "' with digest " + digest
                                + " != pinned " + pinned.sha256());
                    }
                    return new ResolvedSpec(spec, coord.withExactVersion(SemVer.parse(spec.version())),
                            digest, id);
                });
    }

    @Override
    public Optional<ModelLink> resolveLink(ModelCoordinate coord) {
        return fetchSpec(coord)
                .filter(spec -> versionMatches(coord, spec))
                .map(spec -> new HttpModelLink(http, mapper, baseUrl, apiKey, coord.identity(), coord));
    }

    @Override
    public boolean canPublish() {
        return true;   // a remote Valem accepts POST /models
    }

    @Override
    public void publish(ModelSpec materialized) {
        try {
            HttpRequest.Builder rb = HttpRequest.newBuilder(URI.create(baseUrl + "/models"))
                    .timeout(Duration.ofSeconds(10))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofByteArray(mapper.writeValueAsBytes(materialized)));
            if (apiKey != null && !apiKey.isBlank()) rb.header("Authorization", "Bearer " + apiKey);
            HttpResponse<byte[]> resp = http.send(rb.build(), HttpResponse.BodyHandlers.ofByteArray());
            if (resp.statusCode() >= 300) {
                throw new IllegalStateException("publish to repo '" + id + "' failed: HTTP "
                        + resp.statusCode() + " " + new String(resp.body(), java.nio.charset.StandardCharsets.UTF_8));
            }
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("publish to repo '" + id + "' failed: " + e, e);
        }
    }

    private static String stripTrailingSlash(String s) {
        return (s != null && s.endsWith("/")) ? s.substring(0, s.length() - 1) : s;
    }
}
