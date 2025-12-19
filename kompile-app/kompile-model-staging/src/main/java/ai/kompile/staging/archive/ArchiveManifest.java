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

import ai.kompile.staging.registry.ModelEntry;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Manifest for a Kompile Archive (.karch file).
 * Contains versioning, metadata, and model information for the archive.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class ArchiveManifest {

    /**
     * Current format version of the manifest schema.
     */
    public static final String CURRENT_FORMAT_VERSION = "1.0";

    /**
     * Archive format version (schema version).
     */
    @JsonProperty("format_version")
    @Builder.Default
    private String formatVersion = CURRENT_FORMAT_VERSION;

    /**
     * Unique identifier for this archive (e.g., "kompile-default-models").
     */
    @JsonProperty("archive_id")
    private String archiveId;

    /**
     * Human-readable name for the archive.
     */
    @JsonProperty("archive_name")
    private String archiveName;

    /**
     * Content version using semantic versioning.
     */
    @JsonProperty("content_version")
    private String contentVersion;

    /**
     * Previous version this archive replaces (for update tracking).
     */
    @JsonProperty("previous_version")
    private String previousVersion;

    /**
     * When the archive was created (ISO 8601).
     */
    @JsonProperty("created_at")
    private String createdAt;

    /**
     * Release date (ISO 8601, date only).
     */
    @JsonProperty("release_date")
    private String releaseDate;

    /**
     * Description of the archive contents.
     */
    @JsonProperty("description")
    private String description;

    /**
     * Publisher information.
     */
    @JsonProperty("publisher")
    private ArchivePublisher publisher;

    /**
     * Compatibility requirements.
     */
    @JsonProperty("compatibility")
    private ArchiveCompatibility compatibility;

    /**
     * List of models in the archive.
     */
    @JsonProperty("models")
    @Builder.Default
    private List<ArchiveModelEntry> models = new ArrayList<>();

    /**
     * Map of file paths to their SHA256 checksums.
     */
    @JsonProperty("checksums")
    @Builder.Default
    private Map<String, String> checksums = new HashMap<>();

    /**
     * Total size of all files in bytes.
     */
    @JsonProperty("total_size_bytes")
    private long totalSizeBytes;

    /**
     * Number of models in the archive.
     */
    @JsonProperty("model_count")
    private int modelCount;

    /**
     * Kompile version that created this archive.
     */
    @JsonProperty("kompile_version")
    private String kompileVersion;

    /**
     * Changelog or release notes for this version.
     */
    @JsonProperty("changelog")
    private String changelog;

    /**
     * Tags for categorization (e.g., ["embeddings", "reranking", "english"]).
     */
    @JsonProperty("tags")
    @Builder.Default
    private List<String> tags = new ArrayList<>();

    /**
     * Creates a new archive manifest with default values.
     */
    public static ArchiveManifest create(String archiveId, String contentVersion, String description) {
        return ArchiveManifest.builder()
                .formatVersion(CURRENT_FORMAT_VERSION)
                .archiveId(archiveId)
                .contentVersion(contentVersion)
                .createdAt(Instant.now().toString())
                .description(description)
                .models(new ArrayList<>())
                .checksums(new HashMap<>())
                .compatibility(ArchiveCompatibility.defaultCompatibility())
                .build();
    }

    /**
     * Creates an archive manifest for a new version based on a previous manifest.
     */
    public static ArchiveManifest createNextVersion(ArchiveManifest previous, String newVersion, String changelog) {
        return ArchiveManifest.builder()
                .formatVersion(CURRENT_FORMAT_VERSION)
                .archiveId(previous.getArchiveId())
                .archiveName(previous.getArchiveName())
                .contentVersion(newVersion)
                .previousVersion(previous.getContentVersion())
                .createdAt(Instant.now().toString())
                .description(previous.getDescription())
                .publisher(previous.getPublisher())
                .compatibility(previous.getCompatibility())
                .models(new ArrayList<>(previous.getModels()))
                .checksums(new HashMap<>())
                .tags(new ArrayList<>(previous.getTags()))
                .changelog(changelog)
                .build();
    }

    /**
     * Adds a model to the archive.
     */
    public void addModel(ArchiveModelEntry entry) {
        if (models == null) {
            models = new ArrayList<>();
        }
        models.add(entry);
        modelCount = models.size();
    }

    /**
     * Adds a model from a registry ModelEntry.
     */
    public void addModel(ModelEntry entry) {
        addModel(ArchiveModelEntry.fromModelEntry(entry));
    }

    /**
     * Adds a model from a registry ModelEntry with version.
     */
    public void addModel(ModelEntry entry, String version) {
        addModel(ArchiveModelEntry.fromModelEntry(entry, version));
    }

    /**
     * Adds a file checksum.
     */
    public void addChecksum(String path, String checksum) {
        if (checksums == null) {
            checksums = new HashMap<>();
        }
        checksums.put(path, checksum);
    }

    /**
     * Finds a model by ID.
     */
    public Optional<ArchiveModelEntry> findModel(String modelId) {
        if (models == null) {
            return Optional.empty();
        }
        return models.stream()
                .filter(m -> m.getModelId().equals(modelId))
                .findFirst();
    }

    /**
     * Returns the parsed content version or null if unparseable.
     */
    public ArchiveVersion getParsedContentVersion() {
        return ArchiveVersion.tryParse(contentVersion);
    }

    /**
     * Returns the parsed previous version or null if unparseable.
     */
    public ArchiveVersion getParsedPreviousVersion() {
        return ArchiveVersion.tryParse(previousVersion);
    }

    /**
     * Checks if this archive is compatible with the given Kompile version.
     */
    public boolean isCompatible(String kompileVersion) {
        if (compatibility == null) {
            return true;
        }
        return compatibility.isCompatible(kompileVersion);
    }

    /**
     * Checks if this archive is newer than another manifest.
     */
    public boolean isNewerThan(ArchiveManifest other) {
        if (other == null) {
            return true;
        }
        ArchiveVersion thisVersion = getParsedContentVersion();
        ArchiveVersion otherVersion = other.getParsedContentVersion();
        if (thisVersion == null || otherVersion == null) {
            return false;
        }
        return thisVersion.isNewerThan(otherVersion);
    }

    /**
     * Verifies that all checksums match the provided actual checksums.
     */
    public boolean verifyChecksums(Map<String, String> actualChecksums) {
        if (checksums == null || checksums.isEmpty()) {
            return true;
        }
        for (Map.Entry<String, String> entry : checksums.entrySet()) {
            String actual = actualChecksums.get(entry.getKey());
            if (actual == null || !actual.equals(entry.getValue())) {
                return false;
            }
        }
        return true;
    }

    /**
     * Returns the list of model IDs in this archive.
     */
    public List<String> getModelIds() {
        if (models == null) {
            return List.of();
        }
        return models.stream()
                .map(ArchiveModelEntry::getModelId)
                .toList();
    }

    /**
     * Converts all archive model entries to registry model entries.
     */
    public List<ModelEntry> toModelEntries() {
        if (models == null) {
            return List.of();
        }
        return models.stream()
                .map(ArchiveModelEntry::toModelEntry)
                .toList();
    }

    /**
     * Returns a short summary of the archive.
     */
    public String toSummary() {
        return String.format("%s v%s (%d models, %s)",
                archiveId != null ? archiveId : "unnamed",
                contentVersion != null ? contentVersion : "unknown",
                modelCount,
                formatBytes(totalSizeBytes));
    }

    private String formatBytes(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return String.format("%.1f KB", bytes / 1024.0);
        if (bytes < 1024 * 1024 * 1024) return String.format("%.1f MB", bytes / (1024.0 * 1024));
        return String.format("%.2f GB", bytes / (1024.0 * 1024 * 1024));
    }
}
