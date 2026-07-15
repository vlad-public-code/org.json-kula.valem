package org.json_kula.valem.core.util;

import java.util.Objects;

/**
 * A minimal semantic version — {@code MAJOR.MINOR.PATCH} with an optional {@code -prerelease} tag
 * (references design §3.1 grammar). Comparable by precedence (major, then minor, then patch; a
 * prerelease sorts <em>before</em> its corresponding release). Pure value type, no dependency.
 *
 * <p>Only the subset Valem coordinates need is modelled: build metadata ({@code +build}) is not
 * accepted, and prerelease comparison is a simple lexical fallback (sufficient for range selection).
 */
public record SemVer(int major, int minor, int patch, String prerelease) implements Comparable<SemVer> {

    public SemVer {
        if (major < 0 || minor < 0 || patch < 0) {
            throw new IllegalArgumentException("semver components must be non-negative");
        }
        if (prerelease != null && prerelease.isBlank()) {
            prerelease = null;
        }
    }

    /** Parses {@code 1.2.3} or {@code 1.2.3-rc.1}; throws {@link IllegalArgumentException} otherwise. */
    public static SemVer parse(String s) {
        if (s == null || s.isBlank()) {
            throw new IllegalArgumentException("version must not be blank");
        }
        String core = s;
        String pre = null;
        int dash = s.indexOf('-');
        if (dash >= 0) {
            core = s.substring(0, dash);
            pre = s.substring(dash + 1);
            if (pre.isBlank()) {
                throw new IllegalArgumentException("empty prerelease in version: " + s);
            }
        }
        if (core.indexOf('+') >= 0) {
            throw new IllegalArgumentException("build metadata is not supported in version: " + s);
        }
        String[] parts = core.split("\\.", -1);
        if (parts.length != 3) {
            throw new IllegalArgumentException(
                    "version must be MAJOR.MINOR.PATCH (semver): " + s);
        }
        try {
            return new SemVer(nonNeg(parts[0], s), nonNeg(parts[1], s), nonNeg(parts[2], s), pre);
        } catch (NumberFormatException nfe) {
            throw new IllegalArgumentException("version component is not a number: " + s, nfe);
        }
    }

    /** {@code true} if {@code s} is a well-formed semver. */
    public static boolean isValid(String s) {
        try {
            parse(s);
            return true;
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    private static int nonNeg(String part, String whole) {
        if (part.length() > 1 && part.charAt(0) == '0') {
            throw new IllegalArgumentException("version component has a leading zero: " + whole);
        }
        int v = Integer.parseInt(part);
        if (v < 0) throw new IllegalArgumentException("negative version component: " + whole);
        return v;
    }

    @Override
    public int compareTo(SemVer o) {
        int c = Integer.compare(major, o.major);
        if (c != 0) return c;
        c = Integer.compare(minor, o.minor);
        if (c != 0) return c;
        c = Integer.compare(patch, o.patch);
        if (c != 0) return c;
        // A prerelease has lower precedence than the associated normal release.
        if (Objects.equals(prerelease, o.prerelease)) return 0;
        if (prerelease == null) return 1;   // this is a release, o is a prerelease → this is greater
        if (o.prerelease == null) return -1;
        return prerelease.compareTo(o.prerelease);
    }

    @Override
    public String toString() {
        String core = major + "." + minor + "." + patch;
        return prerelease == null ? core : core + "-" + prerelease;
    }
}
