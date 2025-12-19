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

package ai.kompile.staging.catalog.remote;

import ai.kompile.staging.archive.ArchiveCompatibility;
import ai.kompile.staging.archive.ArchiveVersion;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * Entry for a single archive in the remote catalog.
 * Contains version history and download information.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class RemoteCatalogEntry {

    /**
     * Archive identifier.
     */
    @JsonProperty("archive_id")
    private String archiveId;

    /**
     * Human-readable name.
     */
    @JsonProperty("name")
    private String name;

    /**
     * Description of the archive.
     */
    @JsonProperty("description")
    private String description;

    /**
     * Latest available version.
     */
    @JsonProperty("latest_version")
    private String latestVersion;

    /**
     * Available versions (newest first).
     */
    @JsonProperty("versions")
    @Builder.Default
    private List<VersionInfo> versions = new ArrayList<>();

    /**
     * Tags for categorization.
     */
    @JsonProperty("tags")
    @Builder.Default
    private List<String> tags = new ArrayList<>();

    /**
     * Number of models in the archive.
     */
    @JsonProperty("model_count")
    private int modelCount;

    /**
     * Get the latest version info.
     */
    public Optional<VersionInfo> getLatestVersionInfo() {
        if (latestVersion != null) {
            return findVersion(latestVersion);
        }
        if (versions != null && !versions.isEmpty()) {
            return Optional.of(versions.get(0));
        }
        return Optional.empty();
    }

    /**
     * Find a specific version.
     */
    public Optional<VersionInfo> findVersion(String version) {
        if (versions == null) {
            return Optional.empty();
        }
        return versions.stream()
                .filter(v -> v.getVersion().equals(version))
                .findFirst();
    }

    /**
     * Get download URL for a specific version.
     */
    public String getDownloadUrl(String version) {
        return findVersion(version)
                .map(VersionInfo::getDownloadUrl)
                .orElse(null);
    }

    /**
     * Get download URL for latest version.
     */
    public String getLatestDownloadUrl() {
        return getLatestVersionInfo()
                .map(VersionInfo::getDownloadUrl)
                .orElse(null);
    }

    /**
     * Check if a newer version is available than the given version.
     */
    public boolean hasNewerVersion(String currentVersion) {
        if (latestVersion == null) {
            return false;
        }
        ArchiveVersion current = ArchiveVersion.tryParse(currentVersion);
        ArchiveVersion latest = ArchiveVersion.tryParse(latestVersion);
        if (current == null || latest == null) {
            return false;
        }
        return latest.isNewerThan(current);
    }

    /**
     * Get the version difference from current to latest.
     */
    public ArchiveVersion.VersionDiff getVersionDiff(String currentVersion) {
        ArchiveVersion current = ArchiveVersion.tryParse(currentVersion);
        ArchiveVersion latest = ArchiveVersion.tryParse(latestVersion);
        if (current == null || latest == null) {
            return ArchiveVersion.VersionDiff.SAME;
        }
        return latest.diffFrom(current);
    }

    /**
     * Information about a specific version.
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class VersionInfo {

        /**
         * Version string.
         */
        @JsonProperty("version")
        private String version;

        /**
         * Release date (ISO 8601 date).
         */
        @JsonProperty("release_date")
        private String releaseDate;

        /**
         * Download URL (relative or absolute).
         */
        @JsonProperty("download_url")
        private String downloadUrl;

        /**
         * SHA256 checksum of the archive.
         */
        @JsonProperty("checksum")
        private String checksum;

        /**
         * Size in bytes.
         */
        @JsonProperty("size_bytes")
        private long sizeBytes;

        /**
         * Changelog or release notes.
         */
        @JsonProperty("changelog")
        private String changelog;

        /**
         * Compatibility requirements.
         */
        @JsonProperty("compatibility")
        private ArchiveCompatibility compatibility;

        /**
         * Whether this is a pre-release version.
         */
        @JsonProperty("prerelease")
        @Builder.Default
        private boolean prerelease = false;

        /**
         * Get parsed version.
         */
        public ArchiveVersion getParsedVersion() {
            return ArchiveVersion.tryParse(version);
        }

        /**
         * Get formatted size.
         */
        public String getFormattedSize() {
            if (sizeBytes < 1024) return sizeBytes + " B";
            if (sizeBytes < 1024 * 1024) return String.format("%.1f KB", sizeBytes / 1024.0);
            if (sizeBytes < 1024 * 1024 * 1024) return String.format("%.1f MB", sizeBytes / (1024.0 * 1024));
            return String.format("%.2f GB", sizeBytes / (1024.0 * 1024 * 1024));
        }
    }
}
