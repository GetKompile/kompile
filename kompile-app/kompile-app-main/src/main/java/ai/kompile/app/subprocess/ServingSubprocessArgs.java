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

package ai.kompile.app.subprocess;

import ai.kompile.app.config.KompileServerConstants;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.io.IOException;
import java.nio.file.Path;

/**
 * Arguments passed to the LLM serving subprocess via JSON file.
 *
 * <p>The serving subprocess starts a lightweight Spring Boot HTTP server exposing
 * LLM load/unload/generate/status endpoints. It runs as an independent process
 * with its own ND4J backend (CPU or CUDA) and can be deployed modularly.</p>
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ServingSubprocessArgs(
        // Server configuration
        int port,                           // HTTP server port (default 8091)
        String host,                        // Bind address (default 0.0.0.0)

        // Staging server URL for model resolution
        String stagingUrl,                  // e.g. http://localhost:8090

        // Optional: pre-load a model on startup
        String modelId,                     // Model ID to load on startup (null = start empty)
        String modelPath,                   // Path to SameDiff model file
        String tokenizerPath,               // Path to tokenizer.json

        // ND4J configuration
        String nd4jConfigJson,              // Full ND4J environment config from parent process

        // Memory watchdog thresholds
        int memoryThresholdPercent,
        int memoryCriticalPercent,
        int memoryKillThresholdPercent,
        long memoryCheckIntervalMs,
        int gpuMemoryThresholdPercent,
        int gpuMemoryCriticalPercent,
        int gpuMemoryKillThresholdPercent,
        int gpuSoftLimitPercent,        // GPU soft limit for CudaMemoryPool proactive failover
        int offHeapThresholdPercent,
        int offHeapCriticalPercent,
        int offHeapKillThresholdPercent,

        // LLM defaults
        int maxNewTokens,                   // Default max tokens for generation
        double temperature,                 // Default temperature
        int topK,                           // Default top-k sampling

        // DSP / optimizer flags
        Boolean dspEnabled,
        Boolean optimizerEnabled,
        Boolean optimizerFp16
) {
    public static ServingSubprocessArgs fromFile(Path path) throws IOException {
        return SubprocessArgsIo.fromFile(path, ServingSubprocessArgs.class);
    }

    public Path writeToTempFile() throws IOException {
        return SubprocessArgsIo.writeToTempFile(this, "serving-args-");
    }

    /**
     * Create default args for local development.
     */
    public static ServingSubprocessArgs defaults() {
        return new ServingSubprocessArgs(
                8091, "0.0.0.0",
                KompileServerConstants.DEFAULT_STAGING_URL,
                null, null, null,
                null,
                85, 90, 95, 5000,
                85, 90, 95,
                80,
                85, 90, 95,
                256, 0.7, 0,
                null, null, null
        );
    }
}
