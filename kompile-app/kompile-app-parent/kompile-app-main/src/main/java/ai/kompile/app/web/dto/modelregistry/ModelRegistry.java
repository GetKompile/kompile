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
package ai.kompile.app.web.dto.modelregistry;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@JsonIgnoreProperties(ignoreUnknown = true)
public class ModelRegistry {
    @JsonProperty("version")
    public String version = "1.0";

    @JsonProperty("updated_at")
    public String updatedAt;

    @JsonProperty("models")
    public Map<String, ModelEntry> models = new HashMap<>();

    @JsonProperty("installed_archives")
    public Map<String, ArchiveInstallInfo> installedArchives = new HashMap<>();

    public static ModelRegistry empty() {
        ModelRegistry registry = new ModelRegistry();
        registry.version = "1.0";
        registry.updatedAt = null;
        registry.models = new HashMap<>();
        registry.installedArchives = new HashMap<>();
        return registry;
    }

    public ModelEntry getModel(String modelId) {
        return models.get(modelId);
    }

    public List<ModelEntry> getModelsByType(String type) {
        return models.values().stream()
                .filter(e -> e.type != null && e.type.equalsIgnoreCase(type))
                .collect(Collectors.toList());
    }

    public List<ModelEntry> getActiveModels() {
        return models.values().stream()
                .filter(e -> "active".equalsIgnoreCase(e.status))
                .collect(Collectors.toList());
    }

    public List<ArchiveInstallInfo> getInstalledArchivesList() {
        return new ArrayList<>(installedArchives.values());
    }
}
