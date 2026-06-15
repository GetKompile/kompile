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
 *  limitations under the License.
 */

package ai.kompile.staging.config;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Pre-defined performance profiles that bundle Triton, CUDA, and DSP settings
 * into a single configuration that can be applied through the UI.
 */
public enum PerformanceProfile {

    DEBUG_FAST("Debug Fast", "Minimal compilation, no fusion. Fast startup for debugging.") {
        @Override
        public Map<String, Object> getRecommendedSettings() {
            Map<String, Object> settings = new LinkedHashMap<>();
            settings.put("tritonEnableFpFusion", false);
            settings.put("tritonAlwaysCompile", false);
            settings.put("tritonCacheEnabled", false);
            settings.put("tritonNumWarps", 1);
            settings.put("tritonNumStages", 1);
            settings.put("fp16Compute", false);
            settings.put("batchedGemm", false);
            settings.put("castElimination", false);
            settings.put("cublasTf32", false);
            settings.put("cudaTensorCoreEnabled", false);
            settings.put("cudaGraphOptimization", false);
            settings.put("cudaAsyncExecution", false);
            settings.put("verbose", true);
            settings.put("debug", true);
            return settings;
        }
    },

    BALANCED("Balanced", "Moderate fusion, standard tuning. Good for most workloads.") {
        @Override
        public Map<String, Object> getRecommendedSettings() {
            Map<String, Object> settings = new LinkedHashMap<>();
            settings.put("tritonEnableFpFusion", true);
            settings.put("tritonAlwaysCompile", false);
            settings.put("tritonCacheEnabled", true);
            settings.put("tritonNumWarps", 4);
            settings.put("tritonNumStages", 2);
            settings.put("fp16Compute", false);
            settings.put("batchedGemm", true);
            settings.put("castElimination", true);
            settings.put("cublasTf32", true);
            settings.put("cudaTensorCoreEnabled", true);
            settings.put("cudaGraphOptimization", false);
            settings.put("cudaAsyncExecution", true);
            settings.put("verbose", false);
            settings.put("debug", false);
            return settings;
        }
    },

    MAX_PERF("Max Performance", "Aggressive fusion, all optimizations enabled. Maximum throughput.") {
        @Override
        public Map<String, Object> getRecommendedSettings() {
            Map<String, Object> settings = new LinkedHashMap<>();
            settings.put("tritonEnableFpFusion", true);
            settings.put("tritonAlwaysCompile", true);
            settings.put("tritonCacheEnabled", true);
            settings.put("tritonNumWarps", 8);
            settings.put("tritonNumStages", 4);
            settings.put("fp16Compute", true);
            settings.put("batchedGemm", true);
            settings.put("castElimination", true);
            settings.put("cublasTf32", true);
            settings.put("cudaTensorCoreEnabled", true);
            settings.put("cudaGraphOptimization", true);
            settings.put("cudaAsyncExecution", true);
            settings.put("verbose", false);
            settings.put("debug", false);
            return settings;
        }
    },

    OPTIMAL("Optimal", "Best-known config for target hardware. Balances throughput and precision.") {
        @Override
        public Map<String, Object> getRecommendedSettings() {
            Map<String, Object> settings = new LinkedHashMap<>();
            settings.put("tritonEnableFpFusion", true);
            settings.put("tritonAlwaysCompile", false);
            settings.put("tritonCacheEnabled", true);
            settings.put("tritonNumWarps", 4);
            settings.put("tritonNumStages", 3);
            settings.put("fp16Compute", true);
            settings.put("batchedGemm", true);
            settings.put("castElimination", true);
            settings.put("castSinkMatmul", true);
            settings.put("cublasTf32", true);
            settings.put("cudaTensorCoreEnabled", true);
            settings.put("cudaGraphOptimization", true);
            settings.put("cudaAsyncExecution", true);
            settings.put("verbose", false);
            settings.put("debug", false);
            return settings;
        }
    },

    LLM_OPTIMAL("LLM Optimal", "Best-known LLM inference config (~86 tok/s on RTX 4090). Matches BenchmarkConfig.optimal().") {
        @Override
        public Map<String, Object> getRecommendedSettings() {
            Map<String, Object> settings = new LinkedHashMap<>();
            // Triton compiler settings
            settings.put("tritonCacheEnabled", true);
            settings.put("tritonAlwaysCompile", false);
            settings.put("tritonDisableLineInfo", true);
            settings.put("tritonBuildThreads", 4);
            settings.put("tritonEnableFpFusion", true);
            // Optimal overrides matching BenchmarkConfig.optimal()
            settings.put("tritonIncludeTypes", "CONST_GEN,GATHER,CONCAT,SPLIT,STACK,NORMALIZATION,ATTENTION");
            settings.put("tritonSectionFusion", true);
            settings.put("tritonCompileAll", true);
            settings.put("tritonGraphCapture", true);
            settings.put("tritonAllowFallbackCapture", true);
            settings.put("tritonConsolidatedArgTable", true);
            settings.put("tritonArgDirtyTracking", true);
            settings.put("tritonFusionScoring", false);
            settings.put("tritonNumWarps", 4);
            settings.put("tritonNumStages", 1);
            settings.put("tritonNumCTAs", 1);
            // DSP
            settings.put("batchedGemm", true);
            settings.put("cublasTf32", true);
            // Reset flags
            settings.put("tritonSkipKernels", false);
            settings.put("tritonVerifyKernels", false);
            settings.put("tritonVerifyFullSnapshot", false);
            settings.put("tritonForceRecapture", false);
            settings.put("tritonCooperativeLaunch", false);
            settings.put("tritonVerbose", false);
            settings.put("batchZero", false);
            settings.put("batchZeroKernel", false);
            settings.put("castSinkMatmul", false);
            settings.put("verbose", false);
            settings.put("debug", false);
            return settings;
        }
    },

    LLM_BASIC("LLM Basic", "Conservative LLM config for systems without Triton/CUDA graph support. cuBLAS TF32 + batched GEMM only.") {
        @Override
        public Map<String, Object> getRecommendedSettings() {
            Map<String, Object> settings = new LinkedHashMap<>();
            settings.put("cublasTf32", true);
            settings.put("batchedGemm", true);
            settings.put("castElimination", true);
            settings.put("verbose", false);
            settings.put("debug", false);
            return settings;
        }
    },

    LLM_DEBUG("LLM Debug", "Debug-friendly LLM config with verbose logging and kernel verification enabled.") {
        @Override
        public Map<String, Object> getRecommendedSettings() {
            // Start from LLM_OPTIMAL and add debug overrides
            Map<String, Object> settings = LLM_OPTIMAL.getRecommendedSettings();
            settings.put("tritonVerbose", true);
            settings.put("tritonDumpSections", true);
            settings.put("tritonVerifyKernels", true);
            settings.put("tritonBuildThreads", 1);
            settings.put("tritonNumWarps", 2);
            settings.put("tritonNumStages", 2);
            settings.put("verbose", true);
            settings.put("debug", false);
            return settings;
        }
    };

    private final String displayName;
    private final String description;

    PerformanceProfile(String displayName, String description) {
        this.displayName = displayName;
        this.description = description;
    }

    public String getDisplayName() {
        return displayName;
    }

    public String getDescription() {
        return description;
    }

    /**
     * Get the recommended settings map for this profile.
     * Keys are setting names, values are the recommended values.
     */
    public abstract Map<String, Object> getRecommendedSettings();
}
