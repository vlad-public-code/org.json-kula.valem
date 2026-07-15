package org.json_kula.valem.api.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.ArrayList;
import java.util.List;

/**
 * Binds {@code valem.composition.repositories} — the priority-ordered repository chain (references
 * design §4). The {@code local} repository is always prepended (checked first, doubles as the cache),
 * so only the additional {@code http}/{@code filesystem} repos are configured here.
 */
@ConfigurationProperties("valem.composition")
public class CompositionProperties {

    private List<RepositoryConfig> repositories = new ArrayList<>();

    public List<RepositoryConfig> getRepositories() { return repositories; }
    public void setRepositories(List<RepositoryConfig> repositories) { this.repositories = repositories; }

    /** One configured repository. Only {@code http} is wired in M6; {@code local} is implicit. */
    public static class RepositoryConfig {
        private String id;
        private String transport;    // http | mcp | filesystem | local — how it is reached
        private String repoClass;    // local | web — its class (bound from `repo-class`); inferred per transport when unset
        private String locator;      // base URL (http) / directory (filesystem)
        private String credential;   // optional bearer token for a private http repo
        private String egressProfile;
        private boolean trusted = true;

        public String getId() { return id; }
        public void setId(String id) { this.id = id; }
        public String getTransport() { return transport; }
        public void setTransport(String transport) { this.transport = transport; }
        public String getRepoClass() { return repoClass; }
        public void setRepoClass(String repoClass) { this.repoClass = repoClass; }
        public String getLocator() { return locator; }
        public void setLocator(String locator) { this.locator = locator; }
        public String getCredential() { return credential; }
        public void setCredential(String credential) { this.credential = credential; }
        public String getEgressProfile() { return egressProfile; }
        public void setEgressProfile(String egressProfile) { this.egressProfile = egressProfile; }
        public boolean isTrusted() { return trusted; }
        public void setTrusted(boolean trusted) { this.trusted = trusted; }
    }
}
