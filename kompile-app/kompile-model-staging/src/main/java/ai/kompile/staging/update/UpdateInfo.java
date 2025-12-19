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

package ai.kompile.staging.update;

import ai.kompile.staging.archive.ArchiveVersion;
import ai.kompile.staging.catalog.remote.RemoteCatalogEntry;
import lombok.Builder;
import lombok.Data;

/**
 * Information about an available update for an installed archive.
 */
@Data
@Builder
public class UpdateInfo {

    /**
     * Archive identifier.
     */
    private final String archiveId;

    /**
     * Archive name.
     */
    private final String archiveName;

    /**
     * Currently installed version.
     */
    private final String currentVersion;

    /**
     * Latest available version.
     */
    private final String latestVersion;

    /**
     * Type of version change.
     */
    private final ArchiveVersion.VersionDiff versionDiff;

    /**
     * Whether an update is available.
     */
    private final boolean updateAvailable;

    /**
     * Download URL for the update.
     */
    private final String downloadUrl;

    /**
     * Size of the update in bytes.
     */
    private final long sizeBytes;

    /**
     * Changelog for the new version.
     */
    private final String changelog;

    /**
     * Whether the update may have breaking changes.
     */
    private final boolean mayHaveBreakingChanges;

    /**
     * Create update info from installed version and remote catalog entry.
     */
    public static UpdateInfo fromCatalogEntry(String archiveId, String currentVersion,
                                               RemoteCatalogEntry remote, String downloadUrl) {
        ArchiveVersion current = ArchiveVersion.tryParse(currentVersion);
        ArchiveVersion latest = ArchiveVersion.tryParse(remote.getLatestVersion());

        boolean updateAvailable = latest != null && current != null && latest.isNewerThan(current);
        ArchiveVersion.VersionDiff diff = latest != null && current != null
                ? latest.diffFrom(current)
                : ArchiveVersion.VersionDiff.SAME;

        RemoteCatalogEntry.VersionInfo latestInfo = remote.getLatestVersionInfo().orElse(null);

        return UpdateInfo.builder()
                .archiveId(archiveId)
                .archiveName(remote.getName())
                .currentVersion(currentVersion)
                .latestVersion(remote.getLatestVersion())
                .versionDiff(diff)
                .updateAvailable(updateAvailable)
                .downloadUrl(downloadUrl)
                .sizeBytes(latestInfo != null ? latestInfo.getSizeBytes() : 0)
                .changelog(latestInfo != null ? latestInfo.getChangelog() : null)
                .mayHaveBreakingChanges(diff == ArchiveVersion.VersionDiff.MAJOR_UPGRADE)
                .build();
    }

    /**
     * Create info when no update is available.
     */
    public static UpdateInfo noUpdateAvailable(String archiveId, String currentVersion) {
        return UpdateInfo.builder()
                .archiveId(archiveId)
                .currentVersion(currentVersion)
                .latestVersion(currentVersion)
                .versionDiff(ArchiveVersion.VersionDiff.SAME)
                .updateAvailable(false)
                .build();
    }

    /**
     * Create info when archive is not found in catalog.
     */
    public static UpdateInfo notInCatalog(String archiveId, String currentVersion) {
        return UpdateInfo.builder()
                .archiveId(archiveId)
                .currentVersion(currentVersion)
                .updateAvailable(false)
                .build();
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

    /**
     * Get a summary string.
     */
    public String toSummary() {
        if (!updateAvailable) {
            return archiveId + ": up to date (" + currentVersion + ")";
        }
        return String.format("%s: %s -> %s (%s%s)",
                archiveId,
                currentVersion,
                latestVersion,
                versionDiff.name().toLowerCase().replace("_", " "),
                mayHaveBreakingChanges ? ", breaking changes possible" : "");
    }
}
