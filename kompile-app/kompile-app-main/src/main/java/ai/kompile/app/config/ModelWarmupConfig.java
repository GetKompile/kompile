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

package ai.kompile.app.config;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Configuration for model warmup before serving.
 * Persisted to ~/.kompile/config/model-warmup-config.json.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class ModelWarmupConfig {

    @JsonProperty("enabled")
    @Builder.Default
    private boolean enabled = true;  // enabled by default

    @JsonProperty("iterations")
    @Builder.Default
    private int iterations = 3;

    @JsonProperty("timeout_seconds")
    @Builder.Default
    private int timeoutSeconds = 60;

    @JsonProperty("warmup_text")
    @Builder.Default
    private String warmupText = "The quick brown fox jumps over the lazy dog.";

    @JsonProperty("fail_fast")
    @Builder.Default
    private boolean failFast = false;

    public static ModelWarmupConfig defaults() {
        return ModelWarmupConfig.builder().build();
    }
}
