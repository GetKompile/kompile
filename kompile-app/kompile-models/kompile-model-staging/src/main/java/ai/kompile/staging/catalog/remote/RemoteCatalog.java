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

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Represents a remote catalog of available Kompile archives.
 * Used for discovering and downloading archives from remote sources.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class RemoteCatalog {

    /**
     * Catalog schema version.
     */
    @JsonProperty("catalog_version")
    @Builder.Default
    private String catalogVersion = "1.0";

    /**
     * When the catalog was last updated (ISO 8601).
     */
    @JsonProperty("last_updated")
    private String lastUpdated;

    /**
     * Base URL for archive downloads.
     * Archive URLs are relative to this base.
     */
    @JsonProperty("base_url")
    private String baseUrl;

    /**
     * Catalog source URL (where this catalog was fetched from).
     */
    @JsonProperty("source_url")
    private String sourceUrl;

    /**
     * Catalog name.
     */
    @JsonProperty("name")
    private String name;

    /**
     * Catalog description.
     */
    @JsonProperty("description")
    private String description;

    /**
     * List of available archives.
     */
    @JsonProperty("archives")
    @Builder.Default
    private List<RemoteCatalogEntry> archives = new ArrayList<>();

    /**
     * Find an archive by ID.
     */
    public Optional<RemoteCatalogEntry> findArchive(String archiveId) {
        if (archives == null) {
            return Optional.empty();
        }
        return archives.stream()
                .filter(a -> a.getArchiveId().equals(archiveId))
                .findFirst();
    }

    /**
     * Get full download URL for an archive.
     */
    public String getDownloadUrl(RemoteCatalogEntry entry, String version) {
        String downloadUrl = entry.getDownloadUrl(version);
        if (downloadUrl == null) {
            return null;
        }

        // If already absolute URL, return as-is
        if (downloadUrl.startsWith("http://") || downloadUrl.startsWith("https://")) {
            return downloadUrl;
        }

        // Combine with base URL
        if (baseUrl != null && !baseUrl.isEmpty()) {
            String base = baseUrl.endsWith("/") ? baseUrl : baseUrl + "/";
            return base + downloadUrl;
        }

        return downloadUrl;
    }

    /**
     * Get the number of available archives.
     */
    public int getArchiveCount() {
        return archives != null ? archives.size() : 0;
    }

    /**
     * Create an empty catalog.
     */
    public static RemoteCatalog empty() {
        return RemoteCatalog.builder()
                .catalogVersion("1.0")
                .archives(new ArrayList<>())
                .build();
    }
}
