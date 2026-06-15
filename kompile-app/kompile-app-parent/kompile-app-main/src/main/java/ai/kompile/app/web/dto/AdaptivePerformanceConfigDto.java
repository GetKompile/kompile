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

package ai.kompile.app.web.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * DTO for adaptive performance configuration.
 * Allows the frontend to configure real-time batch size adjustments
 * based on memory pressure and throughput metrics.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record AdaptivePerformanceConfigDto(
        // Enable/disable adaptive mode
        boolean enabled,

        // Memory thresholds (percentage)
        int targetMemoryPercent,
        int criticalMemoryPercent,

        // Embedding batch size limits
        int minEmbeddingBatch,
        int maxEmbeddingBatch,

        // Index batch size limits
        int minIndexBatch,
        int maxIndexBatch,

        // Timing settings
        int checkIntervalMs,
        int adjustmentCooldownMs,

        // Preset name (optional)
        String preset
) {
    /**
     * Creates a default configuration.
     */
    public static AdaptivePerformanceConfigDto defaultConfig() {
        return new AdaptivePerformanceConfigDto(
                false,       // disabled by default
                75,          // target: 75%
                90,          // critical: 90%
                8,           // min embedding batch
                64,          // max embedding batch
                25,          // min index batch
                200,         // max index batch
                2000,        // check every 2s
                5000,        // cooldown 5s
                "balanced"
        );
    }

    /**
     * Creates a conservative configuration (safer for low-memory systems).
     */
    public static AdaptivePerformanceConfigDto conservative() {
        return new AdaptivePerformanceConfigDto(
                true,
                65,          // target: 65%
                80,          // critical: 80%
                4,           // min embedding batch
                32,          // max embedding batch
                10,          // min index batch
                100,         // max index batch
                1500,        // check every 1.5s
                3000,        // cooldown 3s
                "conservative"
        );
    }

    /**
     * Creates a balanced configuration (default when enabled).
     */
    public static AdaptivePerformanceConfigDto balanced() {
        return new AdaptivePerformanceConfigDto(
                true,
                75,          // target: 75%
                90,          // critical: 90%
                8,           // min embedding batch
                64,          // max embedding batch
                25,          // min index batch
                200,         // max index batch
                2000,        // check every 2s
                5000,        // cooldown 5s
                "balanced"
        );
    }

    /**
     * Creates an aggressive configuration (maximizes throughput).
     */
    public static AdaptivePerformanceConfigDto aggressive() {
        return new AdaptivePerformanceConfigDto(
                true,
                85,          // target: 85%
                95,          // critical: 95%
                16,          // min embedding batch
                128,         // max embedding batch
                50,          // min index batch
                500,         // max index batch
                3000,        // check every 3s
                8000,        // cooldown 8s
                "aggressive"
        );
    }

    /**
     * Creates a configuration based on the given preset name.
     */
    public static AdaptivePerformanceConfigDto fromPreset(String preset) {
        return switch (preset != null ? preset.toLowerCase() : "balanced") {
            case "conservative" -> conservative();
            case "aggressive" -> aggressive();
            default -> balanced();
        };
    }
}
