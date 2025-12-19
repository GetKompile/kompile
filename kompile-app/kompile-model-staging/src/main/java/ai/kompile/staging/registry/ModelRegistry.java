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

package ai.kompile.staging.registry;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * The root model registry structure.
 * Contains all models indexed by their model ID.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class ModelRegistry {

    /**
     * Registry format version.
     */
    @JsonProperty("version")
    @Builder.Default
    private String version = "1.0";

    /**
     * ISO 8601 timestamp when the registry was last updated.
     */
    @JsonProperty("updated_at")
    private String updatedAt;

    /**
     * Map of model ID to model entry.
     */
    @JsonProperty("models")
    @Builder.Default
    private Map<String, ModelEntry> models = new HashMap<>();

    /**
     * Map of installed archive ID to installation info.
     * Tracks which archives have been imported and their versions.
     */
    @JsonProperty("installed_archives")
    @Builder.Default
    private Map<String, ArchiveInstallInfo> installedArchives = new HashMap<>();

    /**
     * Create an empty registry.
     */
    public static ModelRegistry empty() {
        return ModelRegistry.builder()
                .version("1.0")
                .updatedAt(Instant.now().toString())
                .models(new HashMap<>())
                .installedArchives(new HashMap<>())
                .build();
    }

    /**
     * Get a model by ID.
     */
    public ModelEntry getModel(String modelId) {
        return models.get(modelId);
    }

    /**
     * Get a model by ID as Optional.
     */
    public Optional<ModelEntry> findModel(String modelId) {
        return Optional.ofNullable(models.get(modelId));
    }

    /**
     * Add or update a model in the registry.
     */
    public void putModel(ModelEntry entry) {
        models.put(entry.getModelId(), entry);
        updatedAt = Instant.now().toString();
    }

    /**
     * Remove a model from the registry.
     */
    public ModelEntry removeModel(String modelId) {
        ModelEntry removed = models.remove(modelId);
        if (removed != null) {
            updatedAt = Instant.now().toString();
        }
        return removed;
    }

    /**
     * Get all models of a specific type.
     */
    public List<ModelEntry> getModelsByType(ModelType type) {
        return models.values().stream()
                .filter(e -> e.getType() == type)
                .collect(Collectors.toList());
    }

    /**
     * Get all model IDs of a specific type.
     */
    public List<String> getModelIdsByType(String typeName) {
        ModelType type = ModelType.fromValue(typeName);
        return models.values().stream()
                .filter(e -> e.getType() == type)
                .map(ModelEntry::getModelId)
                .collect(Collectors.toList());
    }

    /**
     * Get all active models.
     */
    @JsonIgnore
    public List<ModelEntry> getActiveModels() {
        return models.values().stream()
                .filter(ModelEntry::isActive)
                .collect(Collectors.toList());
    }

    /**
     * Get all active encoder models.
     */
    @JsonIgnore
    public List<ModelEntry> getActiveEncoders() {
        return models.values().stream()
                .filter(e -> e.getType() == ModelType.ENCODER && e.isActive())
                .collect(Collectors.toList());
    }

    /**
     * Get all active cross-encoder models.
     */
    @JsonIgnore
    public List<ModelEntry> getActiveCrossEncoders() {
        return models.values().stream()
                .filter(e -> e.getType() == ModelType.CROSS_ENCODER && e.isActive())
                .collect(Collectors.toList());
    }

    /**
     * Get the total number of models in the registry.
     */
    @JsonIgnore
    public int getTotalModelCount() {
        return models.size();
    }

    /**
     * Get the number of active models.
     */
    @JsonIgnore
    public int getActiveModelCount() {
        return (int) models.values().stream()
                .filter(ModelEntry::isActive)
                .count();
    }

    /**
     * Check if a model exists in the registry.
     */
    public boolean hasModel(String modelId) {
        return models.containsKey(modelId);
    }

    /**
     * Get an unmodifiable view of all models.
     */
    @JsonIgnore
    public Map<String, ModelEntry> getAllModels() {
        return Collections.unmodifiableMap(models);
    }

    // ==================== Archive Management ====================

    /**
     * Get installed archive info by archive ID.
     */
    public Optional<ArchiveInstallInfo> getInstalledArchive(String archiveId) {
        if (installedArchives == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(installedArchives.get(archiveId));
    }

    /**
     * Check if an archive is installed.
     */
    public boolean hasInstalledArchive(String archiveId) {
        return installedArchives != null && installedArchives.containsKey(archiveId);
    }

    /**
     * Add or update installed archive info.
     */
    public void putInstalledArchive(ArchiveInstallInfo info) {
        if (installedArchives == null) {
            installedArchives = new HashMap<>();
        }
        installedArchives.put(info.getArchiveId(), info);
        updatedAt = Instant.now().toString();
    }

    /**
     * Remove installed archive info.
     */
    public ArchiveInstallInfo removeInstalledArchive(String archiveId) {
        if (installedArchives == null) {
            return null;
        }
        ArchiveInstallInfo removed = installedArchives.remove(archiveId);
        if (removed != null) {
            updatedAt = Instant.now().toString();
        }
        return removed;
    }

    /**
     * Get all installed archives.
     */
    @JsonIgnore
    public List<ArchiveInstallInfo> getInstalledArchives() {
        if (installedArchives == null) {
            return List.of();
        }
        return new ArrayList<>(installedArchives.values());
    }

    /**
     * Get the number of installed archives.
     */
    @JsonIgnore
    public int getInstalledArchiveCount() {
        return installedArchives != null ? installedArchives.size() : 0;
    }

    /**
     * Information about an installed archive.
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class ArchiveInstallInfo {
        /**
         * Archive identifier.
         */
        @JsonProperty("archive_id")
        private String archiveId;

        /**
         * Archive name.
         */
        @JsonProperty("archive_name")
        private String archiveName;

        /**
         * Installed version.
         */
        @JsonProperty("version")
        private String version;

        /**
         * When the archive was installed (ISO 8601).
         */
        @JsonProperty("installed_at")
        private String installedAt;

        /**
         * Source URL if downloaded from remote.
         */
        @JsonProperty("source_url")
        private String sourceUrl;

        /**
         * Archive checksum at installation time.
         */
        @JsonProperty("checksum")
        private String checksum;

        /**
         * List of model IDs from this archive.
         */
        @JsonProperty("model_ids")
        @Builder.Default
        private List<String> modelIds = new ArrayList<>();

        /**
         * Create install info for a new archive installation.
         */
        public static ArchiveInstallInfo create(String archiveId, String version, List<String> modelIds) {
            return ArchiveInstallInfo.builder()
                    .archiveId(archiveId)
                    .version(version)
                    .installedAt(Instant.now().toString())
                    .modelIds(modelIds != null ? new ArrayList<>(modelIds) : new ArrayList<>())
                    .build();
        }
    }
}
