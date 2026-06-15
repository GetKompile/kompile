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
 * Configuration for the three-tier weight cache (GPU -> CPU -> Disk).
 * Persisted to ~/.kompile/config/model-weight-cache-config.json.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class ModelWeightCacheConfig {

    @JsonProperty("enabled")
    @Builder.Default
    private boolean enabled = true;

    @JsonProperty("gpu_pressure_threshold")
    @Builder.Default
    private double gpuPressureThreshold = 0.85;

    @JsonProperty("cpu_budget_bytes")
    @Builder.Default
    private long cpuBudgetBytes = 4L * 1024 * 1024 * 1024; // 4 GB

    @JsonProperty("disk_path")
    @Builder.Default
    private String diskPath = System.getProperty("user.home") + "/.kompile/weight-cache";

    @JsonProperty("enable_mmap")
    @Builder.Default
    private boolean enableMmap = true;

    @JsonProperty("pressure_check_interval_seconds")
    @Builder.Default
    private int pressureCheckIntervalSeconds = 30;

    public static ModelWeightCacheConfig defaults() {
        return ModelWeightCacheConfig.builder().build();
    }
}
