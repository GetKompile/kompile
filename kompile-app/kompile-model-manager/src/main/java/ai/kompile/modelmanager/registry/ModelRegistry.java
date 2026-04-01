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

package ai.kompile.modelmanager.registry;

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

    @JsonProperty("version")
    @Builder.Default
    private String version = "1.0";

    @JsonProperty("updated_at")
    private String updatedAt;

    @JsonProperty("models")
    @Builder.Default
    private Map<String, ModelEntry> models = new HashMap<>();

    @JsonProperty("installed_archives")
    @Builder.Default
    private Map<String, ArchiveInstallInfo> installedArchives = new HashMap<>();

    public static ModelRegistry empty() {
        return ModelRegistry.builder()
                .version("1.0")
                .updatedAt(Instant.now().toString())
                .models(new HashMap<>())
                .installedArchives(new HashMap<>())
                .build();
    }

    public ModelEntry getModel(String modelId) {
        return models.get(modelId);
    }

    public Optional<ModelEntry> findModel(String modelId) {
        return Optional.ofNullable(models.get(modelId));
    }

    public void putModel(ModelEntry entry) {
        models.put(entry.getModelId(), entry);
        updatedAt = Instant.now().toString();
    }

    public ModelEntry removeModel(String modelId) {
        ModelEntry removed = models.remove(modelId);
        if (removed != null) {
            updatedAt = Instant.now().toString();
        }
        return removed;
    }

    public List<ModelEntry> getModelsByType(ModelType type) {
        return models.values().stream()
                .filter(e -> e.getType() == type)
                .collect(Collectors.toList());
    }

    public List<String> getModelIdsByType(String typeName) {
        ModelType type = ModelType.fromValue(typeName);
        return models.values().stream()
                .filter(e -> e.getType() == type)
                .map(ModelEntry::getModelId)
                .collect(Collectors.toList());
    }

    @JsonIgnore
    public List<ModelEntry> getActiveModels() {
        return models.values().stream()
                .filter(ModelEntry::isActive)
                .collect(Collectors.toList());
    }

    @JsonIgnore
    public List<ModelEntry> getActiveEncoders() {
        return models.values().stream()
                .filter(e -> e.getType() == ModelType.ENCODER && e.isActive())
                .collect(Collectors.toList());
    }

    @JsonIgnore
    public List<ModelEntry> getActiveCrossEncoders() {
        return models.values().stream()
                .filter(e -> e.getType() == ModelType.CROSS_ENCODER && e.isActive())
                .collect(Collectors.toList());
    }

    @JsonIgnore
    public int getTotalModelCount() {
        return models.size();
    }

    @JsonIgnore
    public int getActiveModelCount() {
        return (int) models.values().stream()
                .filter(ModelEntry::isActive)
                .count();
    }

    public boolean hasModel(String modelId) {
        return models.containsKey(modelId);
    }

    @JsonIgnore
    public Map<String, ModelEntry> getAllModels() {
        return Collections.unmodifiableMap(models);
    }

    /**
     * Set a model as the active model (deactivates others of same type).
     */
    public void setActiveModel(String modelId) {
        ModelEntry target = models.get(modelId);
        if (target == null) return;
        ModelType type = target.getType();
        // Deactivate other models of the same type
        models.values().stream()
                .filter(e -> e.getType() == type && e.isActive() && !e.getModelId().equals(modelId))
                .forEach(e -> e.setStatus(ModelStatus.STAGED));
        target.setStatus(ModelStatus.ACTIVE);
        updatedAt = java.time.Instant.now().toString();
    }

    @JsonIgnore
    public List<ModelEntry> getActiveOcrModels() {
        return models.values().stream()
                .filter(e -> e.getType().isOcr() && e.isActive())
                .collect(Collectors.toList());
    }

    @JsonIgnore
    public List<ModelEntry> getAllVlmModels() {
        return models.values().stream()
                .filter(e -> {
                    if (e.getMetadata() == null) return false;
                    List<String> components = e.getMetadata().getComponents();
                    return components != null && !components.isEmpty();
                })
                .collect(Collectors.toList());
    }

    // ==================== Archive Management ====================

    public Optional<ArchiveInstallInfo> getInstalledArchive(String archiveId) {
        if (installedArchives == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(installedArchives.get(archiveId));
    }

    public boolean hasInstalledArchive(String archiveId) {
        return installedArchives != null && installedArchives.containsKey(archiveId);
    }

    public void putInstalledArchive(ArchiveInstallInfo info) {
        if (installedArchives == null) {
            installedArchives = new HashMap<>();
        }
        installedArchives.put(info.getArchiveId(), info);
        updatedAt = Instant.now().toString();
    }

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

    @JsonIgnore
    public List<ArchiveInstallInfo> getInstalledArchives() {
        if (installedArchives == null) {
            return List.of();
        }
        return new ArrayList<>(installedArchives.values());
    }

    @JsonIgnore
    public int getInstalledArchiveCount() {
        return installedArchives != null ? installedArchives.size() : 0;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class ArchiveInstallInfo {
        @JsonProperty("archive_id")
        private String archiveId;

        @JsonProperty("archive_name")
        private String archiveName;

        @JsonProperty("version")
        private String version;

        @JsonProperty("installed_at")
        private String installedAt;

        @JsonProperty("source_url")
        private String sourceUrl;

        @JsonProperty("checksum")
        private String checksum;

        @JsonProperty("model_ids")
        @Builder.Default
        private List<String> modelIds = new ArrayList<>();

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
