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

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Represents compatibility requirements for a Kompile archive.
 * Specifies which versions of Kompile can use this archive.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class ArchiveCompatibility {

    /**
     * Minimum required Kompile version (inclusive).
     * Format: semantic version (e.g., "1.0.0")
     */
    @JsonProperty("min_kompile_version")
    private String minKompileVersion;

    /**
     * Maximum supported Kompile version (inclusive).
     * Can use wildcards like "2.x" to indicate any 2.x version.
     * Null means no maximum.
     */
    @JsonProperty("max_kompile_version")
    private String maxKompileVersion;

    /**
     * Required features that must be present in the Kompile installation.
     */
    @JsonProperty("required_features")
    private String[] requiredFeatures;

    /**
     * Required Java version (e.g., "17", "21").
     */
    @JsonProperty("min_java_version")
    private String minJavaVersion;

    /**
     * Creates a compatibility with only minimum version requirement.
     */
    public static ArchiveCompatibility minVersion(String minVersion) {
        return ArchiveCompatibility.builder()
                .minKompileVersion(minVersion)
                .build();
    }

    /**
     * Creates a compatibility with minimum and maximum versions.
     */
    public static ArchiveCompatibility range(String minVersion, String maxVersion) {
        return ArchiveCompatibility.builder()
                .minKompileVersion(minVersion)
                .maxKompileVersion(maxVersion)
                .build();
    }

    /**
     * Creates a compatibility requiring a specific major version.
     */
    public static ArchiveCompatibility majorVersion(int major) {
        return ArchiveCompatibility.builder()
                .minKompileVersion(major + ".0.0")
                .maxKompileVersion(major + ".x")
                .build();
    }

    /**
     * Default compatibility (any 1.x version).
     */
    public static ArchiveCompatibility defaultCompatibility() {
        return ArchiveCompatibility.builder()
                .minKompileVersion("1.0.0")
                .build();
    }

    /**
     * Checks if a given Kompile version is compatible with this archive.
     *
     * @param kompileVersion The Kompile version to check
     * @return true if compatible, false otherwise
     */
    public boolean isCompatible(String kompileVersion) {
        if (kompileVersion == null || kompileVersion.isEmpty()) {
            return true; // Assume compatible if version unknown
        }

        ArchiveVersion version = ArchiveVersion.tryParse(kompileVersion);
        if (version == null) {
            return true; // Assume compatible if version unparseable
        }

        // Check minimum version
        if (minKompileVersion != null) {
            ArchiveVersion minVersion = ArchiveVersion.tryParse(minKompileVersion);
            if (minVersion != null && version.isOlderThan(minVersion)) {
                return false;
            }
        }

        // Check maximum version
        if (maxKompileVersion != null) {
            if (maxKompileVersion.endsWith(".x")) {
                // Wildcard: allow any version with matching major
                String majorStr = maxKompileVersion.substring(0, maxKompileVersion.indexOf(".x"));
                try {
                    int maxMajor = Integer.parseInt(majorStr);
                    if (version.getMajor() > maxMajor) {
                        return false;
                    }
                } catch (NumberFormatException e) {
                    // Ignore malformed wildcard
                }
            } else {
                ArchiveVersion maxVersion = ArchiveVersion.tryParse(maxKompileVersion);
                if (maxVersion != null && version.isNewerThan(maxVersion)) {
                    return false;
                }
            }
        }

        return true;
    }

    /**
     * Checks if the current Java version meets the requirement.
     */
    public boolean isJavaCompatible() {
        if (minJavaVersion == null) {
            return true;
        }

        try {
            int required = Integer.parseInt(minJavaVersion);
            String javaVersion = System.getProperty("java.version");
            int current = parseJavaVersion(javaVersion);
            return current >= required;
        } catch (NumberFormatException e) {
            return true; // Assume compatible if unparseable
        }
    }

    /**
     * Parses Java version string to major version number.
     */
    private int parseJavaVersion(String version) {
        if (version == null) {
            return 0;
        }
        // Handle both "1.8.0" style and "17.0.1" style versions
        if (version.startsWith("1.")) {
            version = version.substring(2);
        }
        int dotIndex = version.indexOf('.');
        if (dotIndex > 0) {
            version = version.substring(0, dotIndex);
        }
        int dashIndex = version.indexOf('-');
        if (dashIndex > 0) {
            version = version.substring(0, dashIndex);
        }
        try {
            return Integer.parseInt(version);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /**
     * Returns a human-readable compatibility string.
     */
    public String toDisplayString() {
        StringBuilder sb = new StringBuilder();
        if (minKompileVersion != null) {
            sb.append("Kompile >= ").append(minKompileVersion);
        }
        if (maxKompileVersion != null) {
            if (sb.length() > 0) {
                sb.append(", ");
            }
            sb.append("Kompile <= ").append(maxKompileVersion);
        }
        if (minJavaVersion != null) {
            if (sb.length() > 0) {
                sb.append(", ");
            }
            sb.append("Java >= ").append(minJavaVersion);
        }
        return sb.length() > 0 ? sb.toString() : "Any version";
    }
}
