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
 * Configuration for model admission control.
 * Persisted to ~/.kompile/config/model-admission-config.json.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class ModelAdmissionConfig {

    @JsonProperty("enabled")
    @Builder.Default
    private boolean enabled = true;

    @JsonProperty("max_loaded_models")
    @Builder.Default
    private int maxLoadedModels = 10;

    @JsonProperty("max_concurrent_loads")
    @Builder.Default
    private int maxConcurrentLoads = 2;

    /** Memory to keep free on GPU in bytes (default 512MB). */
    @JsonProperty("memory_reserve_bytes")
    @Builder.Default
    private long memoryReserveBytes = 512L * 1024 * 1024;

    @JsonProperty("background_load_threads")
    @Builder.Default
    private int backgroundLoadThreads = 2;

    @JsonProperty("warmup_after_load")
    @Builder.Default
    private boolean warmupAfterLoad = true;

    /** Default memory estimate when no metadata available (2GB). */
    @JsonProperty("default_memory_estimate_bytes")
    @Builder.Default
    private long defaultMemoryEstimateBytes = 2L * 1024 * 1024 * 1024;

    public static ModelAdmissionConfig defaults() {
        return ModelAdmissionConfig.builder().build();
    }
}
