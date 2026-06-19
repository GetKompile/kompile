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
public class ModelEntry {
    @JsonProperty("model_id")
    public String modelId;

    @JsonProperty("type")
    public String type;

    @JsonProperty("path")
    public String path;

    @JsonProperty("model_file")
    public String modelFile = "model.sdz";

    @JsonProperty("vocab_file")
    public String vocabFile = "vocab.txt";

    @JsonProperty("checksum")
    public String checksum;

    @JsonProperty("status")
    public String status = "active";

    @JsonProperty("promoted_at")
    public String promotedAt;

    @JsonProperty("version")
    public String version;

    @JsonProperty("metadata")
    public ModelMetadata metadata;

    @JsonProperty("tokenizer")
    public TokenizerConfig tokenizer;

    /**
     * Get the effective version, checking top-level first then metadata.
     */
    public String getEffectiveVersion() {
        if (version != null && !version.isEmpty()) {
            return version;
        }
        if (metadata != null && metadata.version != null) {
            return metadata.version;
        }
        return null;
    }
}
