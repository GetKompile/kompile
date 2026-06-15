/*
 *   Copyright 2025 Kompile Inc.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *   distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 * limitations under the License.
 */

package ai.kompile.staging.archive;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;
import lombok.EqualsAndHashCode;
import lombok.Getter;

import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Represents a semantic version (major.minor.patch) with optional pre-release and build metadata.
 * Supports parsing, comparison, and compatibility checking for Kompile archives.
 *
 * <p>Examples of valid versions:
 * <ul>
 *   <li>1.0.0</li>
 *   <li>2.1.3</li>
 *   <li>1.0.0-beta.1</li>
 *   <li>1.0.0-alpha+build.123</li>
 *   <li>1.0.0+20250115</li>
 * </ul>
 */
@Getter
@EqualsAndHashCode
public class ArchiveVersion implements Comparable<ArchiveVersion> {

    /**
     * Regex pattern for semantic versioning.
     * Groups: 1=major, 2=minor, 3=patch, 4=prerelease (optional), 5=buildmeta (optional)
     */
    private static final Pattern SEMVER_PATTERN = Pattern.compile(
            "^(0|[1-9]\\d*)\\.(0|[1-9]\\d*)\\.(0|[1-9]\\d*)" +
            "(?:-((?:0|[1-9]\\d*|\\d*[a-zA-Z-][0-9a-zA-Z-]*)(?:\\.(?:0|[1-9]\\d*|\\d*[a-zA-Z-][0-9a-zA-Z-]*))*))?" +
            "(?:\\+([0-9a-zA-Z-]+(?:\\.[0-9a-zA-Z-]+)*))?$"
    );

    /**
     * Initial version for new archives.
     */
    public static final ArchiveVersion INITIAL = new ArchiveVersion(1, 0, 0, null, null);

    /**
     * Major version number. Incremented for breaking changes.
     */
    private final int major;

    /**
     * Minor version number. Incremented for backward-compatible features.
     */
    private final int minor;

    /**
     * Patch version number. Incremented for backward-compatible bug fixes.
     */
    private final int patch;

    /**
     * Pre-release identifier (e.g., "alpha", "beta.1", "rc.2").
     * Null for release versions.
     */
    private final String preRelease;

    /**
     * Build metadata (e.g., "build.123", "20250115").
     * Ignored in version comparison per semver spec.
     */
    private final String buildMetadata;

    /**
     * Creates a new version with the specified components.
     */
    public ArchiveVersion(int major, int minor, int patch, String preRelease, String buildMetadata) {
        if (major < 0 || minor < 0 || patch < 0) {
            throw new IllegalArgumentException("Version components cannot be negative");
        }
        this.major = major;
        this.minor = minor;
        this.patch = patch;
        this.preRelease = preRelease != null && !preRelease.isEmpty() ? preRelease : null;
        this.buildMetadata = buildMetadata != null && !buildMetadata.isEmpty() ? buildMetadata : null;
    }

    /**
     * Creates a release version without pre-release or build metadata.
     */
    public ArchiveVersion(int major, int minor, int patch) {
        this(major, minor, patch, null, null);
    }

    /**
     * Parses a version string into an ArchiveVersion.
     *
     * @param version Version string (e.g., "1.2.3", "1.0.0-beta.1")
     * @return Parsed ArchiveVersion
     * @throws IllegalArgumentException if the version string is invalid
     */
    @JsonCreator
    public static ArchiveVersion parse(String version) {
        if (version == null || version.isEmpty()) {
            throw new IllegalArgumentException("Version string cannot be null or empty");
        }

        // Strip leading 'v' if present (common in tags like v1.0.0)
        String normalized = version.startsWith("v") || version.startsWith("V")
                ? version.substring(1)
                : version;

        Matcher matcher = SEMVER_PATTERN.matcher(normalized);
        if (!matcher.matches()) {
            throw new IllegalArgumentException("Invalid semantic version: " + version);
        }

        int major = Integer.parseInt(matcher.group(1));
        int minor = Integer.parseInt(matcher.group(2));
        int patch = Integer.parseInt(matcher.group(3));
        String preRelease = matcher.group(4);
        String buildMetadata = matcher.group(5);

        return new ArchiveVersion(major, minor, patch, preRelease, buildMetadata);
    }

    /**
     * Attempts to parse a version string, returning null if invalid.
     */
    public static ArchiveVersion tryParse(String version) {
        try {
            return parse(version);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /**
     * Creates a version from components.
     */
    public static ArchiveVersion of(int major, int minor, int patch) {
        return new ArchiveVersion(major, minor, patch);
    }

    /**
     * Creates a version with pre-release identifier.
     */
    public static ArchiveVersion of(int major, int minor, int patch, String preRelease) {
        return new ArchiveVersion(major, minor, patch, preRelease, null);
    }

    /**
     * Returns true if this is a pre-release version.
     */
    public boolean isPreRelease() {
        return preRelease != null;
    }

    /**
     * Returns true if this is a stable release (not pre-release).
     */
    public boolean isStable() {
        return preRelease == null;
    }

    /**
     * Returns true if this version is newer than the other version.
     */
    public boolean isNewerThan(ArchiveVersion other) {
        return this.compareTo(other) > 0;
    }

    /**
     * Returns true if this version is older than the other version.
     */
    public boolean isOlderThan(ArchiveVersion other) {
        return this.compareTo(other) < 0;
    }

    /**
     * Returns true if this version equals the other version (ignoring build metadata).
     */
    public boolean isSameAs(ArchiveVersion other) {
        return this.compareTo(other) == 0;
    }

    /**
     * Returns true if this version is compatible with the other version.
     * Compatible means same major version and this version >= other version.
     */
    public boolean isCompatibleWith(ArchiveVersion other) {
        if (other == null) {
            return true;
        }
        return this.major == other.major && this.compareTo(other) >= 0;
    }

    /**
     * Returns the type of upgrade from the other version to this version.
     */
    public VersionDiff diffFrom(ArchiveVersion other) {
        if (other == null) {
            return VersionDiff.MAJOR_UPGRADE;
        }

        int cmp = this.compareTo(other);
        if (cmp == 0) {
            return VersionDiff.SAME;
        } else if (cmp < 0) {
            return VersionDiff.DOWNGRADE;
        } else if (this.major > other.major) {
            return VersionDiff.MAJOR_UPGRADE;
        } else if (this.minor > other.minor) {
            return VersionDiff.MINOR_UPGRADE;
        } else {
            return VersionDiff.PATCH_UPGRADE;
        }
    }

    /**
     * Returns a new version with major incremented and minor/patch reset.
     */
    public ArchiveVersion nextMajor() {
        return new ArchiveVersion(major + 1, 0, 0);
    }

    /**
     * Returns a new version with minor incremented and patch reset.
     */
    public ArchiveVersion nextMinor() {
        return new ArchiveVersion(major, minor + 1, 0);
    }

    /**
     * Returns a new version with patch incremented.
     */
    public ArchiveVersion nextPatch() {
        return new ArchiveVersion(major, minor, patch + 1);
    }

    /**
     * Returns a pre-release version based on this version.
     */
    public ArchiveVersion withPreRelease(String preRelease) {
        return new ArchiveVersion(major, minor, patch, preRelease, buildMetadata);
    }

    /**
     * Returns this version with build metadata.
     */
    public ArchiveVersion withBuildMetadata(String buildMetadata) {
        return new ArchiveVersion(major, minor, patch, preRelease, buildMetadata);
    }

    /**
     * Returns this version as a stable release (removes pre-release).
     */
    public ArchiveVersion toStable() {
        return new ArchiveVersion(major, minor, patch, null, buildMetadata);
    }

    @Override
    public int compareTo(ArchiveVersion other) {
        if (other == null) {
            return 1;
        }

        // Compare major.minor.patch
        int result = Integer.compare(this.major, other.major);
        if (result != 0) return result;

        result = Integer.compare(this.minor, other.minor);
        if (result != 0) return result;

        result = Integer.compare(this.patch, other.patch);
        if (result != 0) return result;

        // Pre-release versions have lower precedence than release versions
        if (this.preRelease == null && other.preRelease == null) {
            return 0;
        } else if (this.preRelease == null) {
            return 1; // this is release, other is pre-release
        } else if (other.preRelease == null) {
            return -1; // this is pre-release, other is release
        }

        // Compare pre-release identifiers
        return comparePreRelease(this.preRelease, other.preRelease);
    }

    /**
     * Compares pre-release identifiers according to semver spec.
     */
    private int comparePreRelease(String a, String b) {
        String[] partsA = a.split("\\.");
        String[] partsB = b.split("\\.");

        int minLength = Math.min(partsA.length, partsB.length);
        for (int i = 0; i < minLength; i++) {
            String partA = partsA[i];
            String partB = partsB[i];

            boolean aNumeric = isNumeric(partA);
            boolean bNumeric = isNumeric(partB);

            if (aNumeric && bNumeric) {
                // Both numeric: compare as integers
                int cmp = Integer.compare(Integer.parseInt(partA), Integer.parseInt(partB));
                if (cmp != 0) return cmp;
            } else if (aNumeric) {
                // Numeric has lower precedence than alphanumeric
                return -1;
            } else if (bNumeric) {
                return 1;
            } else {
                // Both alphanumeric: compare as strings
                int cmp = partA.compareTo(partB);
                if (cmp != 0) return cmp;
            }
        }

        // Larger set of pre-release fields has higher precedence
        return Integer.compare(partsA.length, partsB.length);
    }

    private boolean isNumeric(String s) {
        return s.matches("\\d+");
    }

    /**
     * Returns the version string (e.g., "1.2.3" or "1.0.0-beta.1+build.123").
     */
    @Override
    @JsonValue
    public String toString() {
        StringBuilder sb = new StringBuilder();
        sb.append(major).append('.').append(minor).append('.').append(patch);
        if (preRelease != null) {
            sb.append('-').append(preRelease);
        }
        if (buildMetadata != null) {
            sb.append('+').append(buildMetadata);
        }
        return sb.toString();
    }

    /**
     * Returns the core version string without pre-release or build metadata.
     */
    public String toShortString() {
        return major + "." + minor + "." + patch;
    }

    /**
     * Represents the type of difference between two versions.
     */
    public enum VersionDiff {
        /**
         * Major version upgrade (breaking changes possible).
         */
        MAJOR_UPGRADE,

        /**
         * Minor version upgrade (new features, backward compatible).
         */
        MINOR_UPGRADE,

        /**
         * Patch version upgrade (bug fixes only).
         */
        PATCH_UPGRADE,

        /**
         * Same version.
         */
        SAME,

        /**
         * Downgrade to older version.
         */
        DOWNGRADE;

        /**
         * Returns true if this represents an upgrade.
         */
        public boolean isUpgrade() {
            return this == MAJOR_UPGRADE || this == MINOR_UPGRADE || this == PATCH_UPGRADE;
        }

        /**
         * Returns true if this upgrade may have breaking changes.
         */
        public boolean mayHaveBreakingChanges() {
            return this == MAJOR_UPGRADE;
        }
    }
}
