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

package ai.kompile.staging.config;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * UI-configurable settings for the staging service.
 * These settings are persisted to a JSON file and can be modified through the UI.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class StagingSettings {

    /**
     * URL of the kompile-app-main service to notify after model changes.
     * When set, the staging service will call the reload endpoint after optimization/restore.
     * Example: http://localhost:8080
     */
    @JsonProperty("callback_url")
    private String callbackUrl;

    /**
     * Whether to automatically notify kompile-app-main after model changes.
     */
    @JsonProperty("auto_reload_enabled")
    @Builder.Default
    private boolean autoReloadEnabled = true;

    /**
     * Timeout in milliseconds for the reload callback.
     */
    @JsonProperty("callback_timeout_ms")
    @Builder.Default
    private int callbackTimeoutMs = 30000;

    // ==================== Optimizer Settings ====================

    /**
     * Whether to enable FP16 pre-casting during graph optimization.
     * When enabled, the optimizer will insert FP16 cast operations
     * before compute-heavy ops like matmul for faster inference on supported hardware.
     */
    @JsonProperty("optimizer_fp16_enabled")
    @Builder.Default
    private boolean optimizerFp16Enabled = true;

    /**
     * Whether the graph optimizer is enabled at all.
     */
    @JsonProperty("optimizer_enabled")
    @Builder.Default
    private boolean optimizerEnabled = true;

    /**
     * Maximum number of optimization iterations (passes over the graph).
     */
    @JsonProperty("optimizer_max_iterations")
    @Builder.Default
    private int optimizerMaxIterations = 3;

    /**
     * Whether to log which optimizations were applied.
     */
    @JsonProperty("optimizer_log_applied")
    @Builder.Default
    private boolean optimizerLogApplied = false;

    /**
     * Default optimization profile to use when no profile is specified.
     * Values: "default", "transformer", "minimal", "aggressive", "size_reduction"
     */
    @JsonProperty("default_optimization_profile")
    @Builder.Default
    private String defaultOptimizationProfile = "default";

    /**
     * Default performance profile for Triton/CUDA/DSP settings.
     * Values: "DEBUG_FAST", "BALANCED", "MAX_PERF", "OPTIMAL"
     */
    @JsonProperty("default_performance_profile")
    @Builder.Default
    private String defaultPerformanceProfile = "BALANCED";

    /**
     * Create default settings.
     */
    public static StagingSettings defaults() {
        return StagingSettings.builder()
                .callbackUrl(null)
                .autoReloadEnabled(true)
                .callbackTimeoutMs(30000)
                .optimizerFp16Enabled(true)
                .optimizerEnabled(true)
                .optimizerMaxIterations(3)
                .optimizerLogApplied(false)
                .defaultOptimizationProfile("default")
                .defaultPerformanceProfile("BALANCED")
                .build();
    }
}
