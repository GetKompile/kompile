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

import java.time.Instant;
import java.util.List;

/**
 * JSON-persistable benchmark configuration for SameDiff inference tuning.
 * Independent DTO — no DL4J dependency. Settings are applied via Nd4j.getEnvironment() setters.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record SamediffBenchmarkConfig(
        // Metadata
        @JsonProperty("name") String name,
        @JsonProperty("isActive") Boolean isActive,
        @JsonProperty("createdAt") String createdAt,
        @JsonProperty("lastUsedAt") String lastUsedAt,

        // Triton compiler settings
        @JsonProperty("tritonBuildThreads") Integer tritonBuildThreads,
        @JsonProperty("tritonCacheEnabled") Boolean tritonCacheEnabled,
        @JsonProperty("tritonVerbose") Boolean tritonVerbose,
        @JsonProperty("tritonAlwaysCompile") Boolean tritonAlwaysCompile,
        @JsonProperty("tritonNumWarps") Integer tritonNumWarps,
        @JsonProperty("tritonNumStages") Integer tritonNumStages,
        @JsonProperty("tritonNumCTAs") Integer tritonNumCTAs,
        @JsonProperty("tritonEnableFpFusion") Boolean tritonEnableFpFusion,
        @JsonProperty("tritonCacheDir") String tritonCacheDir,
        @JsonProperty("tritonDumpDir") String tritonDumpDir,
        @JsonProperty("tritonOverrideArch") String tritonOverrideArch,

        // CUDA performance settings
        @JsonProperty("cudaTensorCoreEnabled") Boolean cudaTensorCoreEnabled,
        @JsonProperty("cudaGraphOptimization") Boolean cudaGraphOptimization,

        // Generation parameters
        @JsonProperty("maxTokens") Integer maxTokens,
        @JsonProperty("captureMinExec") Integer captureMinExec,

        // Validation criteria
        @JsonProperty("minDiversityPct") Double minDiversityPct,
        @JsonProperty("expectedSubstrings") List<String> expectedSubstrings,
        @JsonProperty("expectStructuralTags") Boolean expectStructuralTags
) {

    /**
     * Creates an optimal benchmark configuration matching best-known Triton settings
     * for high-throughput GPU inference.
     */
    public static SamediffBenchmarkConfig optimal() {
        return new SamediffBenchmarkConfig(
                "optimal",                          // name
                true,                               // isActive
                Instant.now().toString(),            // createdAt
                null,                               // lastUsedAt
                // Triton — optimal settings
                Runtime.getRuntime().availableProcessors(), // tritonBuildThreads
                true,                               // tritonCacheEnabled
                false,                              // tritonVerbose
                false,                              // tritonAlwaysCompile
                8,                                  // tritonNumWarps
                3,                                  // tritonNumStages
                1,                                  // tritonNumCTAs
                true,                               // tritonEnableFpFusion
                null,                               // tritonCacheDir (use default)
                null,                               // tritonDumpDir
                null,                               // tritonOverrideArch
                // CUDA
                true,                               // cudaTensorCoreEnabled
                true,                               // cudaGraphOptimization
                // Generation
                256,                                // maxTokens
                3,                                  // captureMinExec
                // Validation
                0.3,                                // minDiversityPct
                null,                               // expectedSubstrings
                false                               // expectStructuralTags
        );
    }

    /**
     * Converts this benchmark config to an Nd4jEnvironmentConfig partial update
     * containing only the Triton/CUDA fields.
     */
    public Nd4jEnvironmentConfig toNd4jEnvironmentConfig() {
        return Nd4jEnvironmentConfig.builder()
                .tritonBuildThreads(tritonBuildThreads)
                .tritonCacheEnabled(tritonCacheEnabled)
                .tritonVerbose(tritonVerbose)
                .tritonAlwaysCompile(tritonAlwaysCompile)
                .tritonNumWarps(tritonNumWarps)
                .tritonNumStages(tritonNumStages)
                .tritonNumCTAs(tritonNumCTAs)
                .tritonEnableFpFusion(tritonEnableFpFusion)
                .tritonCacheDir(tritonCacheDir)
                .tritonDumpDir(tritonDumpDir)
                .tritonOverrideArch(tritonOverrideArch)
                .cudaTensorCoreEnabled(cudaTensorCoreEnabled)
                .cudaGraphOptimization(cudaGraphOptimization)
                .build();
    }
}
