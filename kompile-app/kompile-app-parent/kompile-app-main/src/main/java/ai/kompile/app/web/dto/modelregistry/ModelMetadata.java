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

@JsonIgnoreProperties(ignoreUnknown = true)
public class ModelMetadata {
    @JsonProperty("embedding_dim")
    public Integer embeddingDim;

    @JsonProperty("hidden_size")
    public Integer hiddenSize;

    @JsonProperty("num_layers")
    public Integer numLayers;

    @JsonProperty("max_sequence_length")
    public int maxSequenceLength = 512;

    @JsonProperty("model_type")
    public String modelType;

    @JsonProperty("encoder_type")
    public String encoderType;

    @JsonProperty("rag_role")
    public String ragRole;

    @JsonProperty("framework")
    public String framework = "samediff";

    @JsonProperty("training_data")
    public String trainingData;

    @JsonProperty("source_origin")
    public String sourceOrigin;

    @JsonProperty("source_repository")
    public String sourceRepository;

    @JsonProperty("original_format")
    public String originalFormat;

    @JsonProperty("conversion_date")
    public String conversionDate;

    @JsonProperty("description")
    public String description;

    // === Provenance fields for version tracking ===

    @JsonProperty("version")
    public String version;

    @JsonProperty("staging_registry_version")
    public String stagingRegistryVersion;

    @JsonProperty("source_archive_id")
    public String sourceArchiveId;

    @JsonProperty("source_archive_version")
    public String sourceArchiveVersion;

    @JsonProperty("installed_from")
    public String installedFrom;

    @JsonProperty("installed_at")
    public String installedAt;
}
