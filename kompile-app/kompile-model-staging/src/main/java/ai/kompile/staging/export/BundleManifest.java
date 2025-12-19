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

package ai.kompile.staging.export;

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

/**
 * Manifest for an exported model bundle.
 * Contains metadata about all models in the bundle for air-gap transfer.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class BundleManifest {

    /**
     * Bundle format version.
     */
    @JsonProperty("version")
    @Builder.Default
    private String version = "1.0";

    /**
     * When the bundle was created.
     */
    @JsonProperty("created_at")
    private String createdAt;

    /**
     * Description of the bundle.
     */
    @JsonProperty("description")
    private String description;

    /**
     * List of models in the bundle.
     */
    @JsonProperty("models")
    @Builder.Default
    private List<ModelEntry> models = new ArrayList<>();

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
     * Number of models in the bundle.
     */
    @JsonProperty("model_count")
    private int modelCount;

    /**
     * Kompile version that created this bundle.
     */
    @JsonProperty("kompile_version")
    private String kompileVersion;

    /**
     * Create a new bundle manifest.
     */
    public static BundleManifest create(String description) {
        return BundleManifest.builder()
                .version("1.0")
                .createdAt(Instant.now().toString())
                .description(description)
                .models(new ArrayList<>())
                .checksums(new HashMap<>())
                .build();
    }

    /**
     * Add a model to the bundle.
     */
    public void addModel(ModelEntry entry) {
        models.add(entry);
        modelCount = models.size();
    }

    /**
     * Add a file checksum.
     */
    public void addChecksum(String path, String checksum) {
        checksums.put(path, checksum);
    }

    /**
     * Verify that all checksums match.
     */
    public boolean verifyChecksums(Map<String, String> actualChecksums) {
        for (Map.Entry<String, String> entry : checksums.entrySet()) {
            String actual = actualChecksums.get(entry.getKey());
            if (actual == null || !actual.equals(entry.getValue())) {
                return false;
            }
        }
        return true;
    }
}
