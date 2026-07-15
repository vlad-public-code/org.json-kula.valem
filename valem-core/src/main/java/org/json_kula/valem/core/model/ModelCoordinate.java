package org.json_kula.valem.core.model;

import org.json_kula.valem.core.util.SemVer;

import java.util.regex.Pattern;

/**
 * A location-independent model identity — {@code [namespace/]name[@version-spec]}, shaped like a
 * Maven / npm coordinate (references design §3). Names <em>which</em> model, never <em>where</em>;
 * location is supplied by the repository chain. Pure {@code valem-core} value type used by both a
 * link {@code target.ref} and a {@code template.ref}. It never carries a location and cannot resolve
 * itself.
 *
 * <p>Grammar (§3.1): {@code namespace} is dotted segments (a Maven groupId); {@code name} is one
 * segment; each segment starts with a letter. {@code version-spec} is an exact semver, a range
 * ({@code ^ ~ >= > <= < =}), or a {@code sha256:} digest. Coordinates are case-sensitive.
 */
public record ModelCoordinate(String namespace, String name, VersionSpec version) {

    private static final Pattern SEGMENT = Pattern.compile("[A-Za-z][A-Za-z0-9_-]*");
    private static final Pattern DIGEST  = Pattern.compile("sha256:[0-9a-f]{64}");

    public ModelCoordinate {
        if (name == null || !SEGMENT.matcher(name).matches()) {
            throw new IllegalArgumentException("invalid coordinate name: " + name);
        }
        if (namespace != null) {
            if (namespace.isEmpty()) {
                namespace = null;
            } else {
                for (String seg : namespace.split("\\.", -1)) {
                    if (!SEGMENT.matcher(seg).matches()) {
                        throw new IllegalArgumentException("invalid coordinate namespace: " + namespace);
                    }
                }
            }
        }
        if (version == null) version = Unversioned.INSTANCE;
    }

    /** The version-spec portion of a coordinate. */
    public sealed interface VersionSpec permits Exact, Range, Digest, Unversioned {}

    /** An exact pin: {@code @2.1.0}. */
    public record Exact(SemVer version) implements VersionSpec {
        @Override public String toString() { return version.toString(); }
    }

    /** A semver range: {@code @^1.4.0}, {@code @>=2.0.0}, … */
    public record Range(Op op, SemVer version) implements VersionSpec {
        @Override public String toString() { return op.token + version; }
    }

    /** A content pin: {@code @sha256:9f2c…} (64 lowercase hex). */
    public record Digest(String sha256) implements VersionSpec {
        public Digest {
            if (sha256 == null || !DIGEST.matcher(sha256).matches()) {
                throw new IllegalArgumentException("invalid digest: " + sha256);
            }
        }
        @Override public String toString() { return sha256; }
    }

    /** No version constraint — matches the running instance / any version. */
    public enum Unversioned implements VersionSpec {
        INSTANCE;
        @Override public String toString() { return ""; }
    }

    /** Range operators, longest-token-first so {@code >=} is matched before {@code >}. */
    public enum Op {
        CARET("^"), TILDE("~"), GTE(">="), LTE("<="), GT(">"), LT("<"), EQ("=");
        final String token;
        Op(String token) { this.token = token; }
    }

    /** Parses a coordinate string per §3.1; throws {@link IllegalArgumentException} on any violation. */
    public static ModelCoordinate parse(String s) {
        if (s == null || s.isBlank()) {
            throw new IllegalArgumentException("coordinate must not be blank");
        }
        if (s.chars().anyMatch(Character::isWhitespace)) {
            throw new IllegalArgumentException("coordinate must not contain whitespace: " + s);
        }
        String identity = s;
        VersionSpec version = Unversioned.INSTANCE;
        int at = s.indexOf('@');
        if (at >= 0) {
            if (s.indexOf('@', at + 1) >= 0) {
                throw new IllegalArgumentException("coordinate has more than one '@': " + s);
            }
            identity = s.substring(0, at);
            version = parseVersion(s.substring(at + 1), s);
        }
        String namespace = null;
        String name = identity;
        int slash = identity.indexOf('/');
        if (slash >= 0) {
            if (identity.indexOf('/', slash + 1) >= 0) {
                throw new IllegalArgumentException("coordinate has more than one '/': " + s);
            }
            namespace = identity.substring(0, slash);
            name = identity.substring(slash + 1);
        }
        return new ModelCoordinate(namespace, name, version);
    }

    /** {@code true} if {@code s} is a well-formed coordinate. */
    public static boolean isValid(String s) {
        try {
            parse(s);
            return true;
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    private static VersionSpec parseVersion(String v, String whole) {
        if (v.isBlank()) {
            throw new IllegalArgumentException("empty version-spec in coordinate: " + whole);
        }
        if (v.startsWith("sha256:")) {
            return new Digest(v);
        }
        for (Op op : Op.values()) {
            if (v.startsWith(op.token)) {
                return new Range(op, SemVer.parse(v.substring(op.token.length())));
            }
        }
        // No operator → exact.
        return new Exact(SemVer.parse(v));
    }

    /** The version-less identity {@code [namespace/]name}. */
    public String identity() {
        return namespace == null ? name : namespace + "/" + name;
    }

    /** {@code true} iff this coordinate is pinned to an exact version or a digest. */
    public boolean isExact() {
        return version instanceof Exact || version instanceof Digest;
    }

    /** A copy pinned to an exact {@code version} (used after range resolution). */
    public ModelCoordinate withExactVersion(SemVer version) {
        return new ModelCoordinate(namespace, name, new Exact(version));
    }

    /**
     * Whether a concrete released {@code candidate} version satisfies this coordinate's version-spec.
     * A digest pin is not answerable here (it is a content check, handled by the resolver) and returns
     * {@code false}; {@link Unversioned} matches anything.
     */
    public boolean satisfiedBy(SemVer candidate) {
        return switch (version) {
            case Unversioned u -> true;
            case Exact e -> e.version().compareTo(candidate) == 0;
            case Digest d -> false;
            case Range r -> satisfiesRange(r, candidate);
        };
    }

    private static boolean satisfiesRange(Range r, SemVer c) {
        SemVer base = r.version();
        return switch (r.op()) {
            case EQ  -> c.compareTo(base) == 0;
            case GT  -> c.compareTo(base) > 0;
            case GTE -> c.compareTo(base) >= 0;
            case LT  -> c.compareTo(base) < 0;
            case LTE -> c.compareTo(base) <= 0;
            case CARET -> c.compareTo(base) >= 0 && c.major() == base.major();
            case TILDE -> c.compareTo(base) >= 0
                    && c.major() == base.major() && c.minor() == base.minor();
        };
    }

    /** Canonical render {@code namespace/name@version}, namespace and version omitted when empty. */
    @Override
    public String toString() {
        String v = version.toString();
        return v.isEmpty() ? identity() : identity() + "@" + v;
    }
}
