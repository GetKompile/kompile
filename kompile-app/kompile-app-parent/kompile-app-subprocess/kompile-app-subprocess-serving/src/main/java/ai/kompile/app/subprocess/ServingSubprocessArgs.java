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
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Arguments passed to the LLM serving subprocess via JSON file.
 *
 * <p>The serving subprocess starts a bounded JDK HTTP server exposing
 * LLM load/unload/generate/status endpoints. It runs as an independent process
 * with its own ND4J backend (CPU or CUDA) and can be deployed modularly.</p>
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ServingSubprocessArgs(
        // Server configuration
        int port,                           // HTTP server port (default 8091)
        String host,                        // Bind address (default 127.0.0.1)

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
        Double temperature,                 // Explicit temperature; null = model-family default
        Integer topK,                       // Explicit top-k; null = model-family default

        // DSP / optimizer flags
        Boolean dspEnabled,
        Boolean optimizerEnabled,
        Boolean optimizerFp16,

        // Model/runtime knobs — null/0 = model-owned defaults. These mirror the opts
        // SameDiffLanguageModelImpl reads (chatTemplate, KV/prefill/continuation) so the
        // local serving path exposes the same controls the staging execution path has.
        String chatTemplate,
        String kvCacheType,
        Integer maxKvCacheLength,
        Integer maxPrefillLength,
        Boolean continuationEnabled,
        Integer continuationChunkTokens,
        Boolean prefixCacheEnabled,
        Long prefixCacheMaxBytes,
        Integer prefixCacheBlockSize,

        // Optional positive byte ceilings in logical visible-device order; null preserves limits.
        List<Long> deviceMemoryLimitsBytes
) {
    public ServingSubprocessArgs {
        if (deviceMemoryLimitsBytes != null) {
            if (deviceMemoryLimitsBytes.isEmpty()
                    || deviceMemoryLimitsBytes.stream().anyMatch(limit -> limit == null || limit <= 0)) {
                throw new IllegalArgumentException("deviceMemoryLimitsBytes must contain positive byte limits");
            }
            deviceMemoryLimitsBytes = List.copyOf(deviceMemoryLimitsBytes);
        }
    }

    public static ServingSubprocessArgs fromFile(Path path) throws IOException {
        JsonNode root = SubprocessArgsIo.mapper().readerFor(JsonNode.class)
                .with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                .readValue(Files.readString(path));
        if (root == null || !root.isObject()) {
            throw new IOException("Serving args must be a JSON object");
        }
        // Validate the original JSON tokens before Jackson can coerce strings or
        // truncate floating-point values while binding List<Long>.
        JsonNode limits = root.get("deviceMemoryLimitsBytes");
        if (limits != null && !limits.isNull()) {
            if (!limits.isArray() || limits.isEmpty()) {
                throw new IOException("deviceMemoryLimitsBytes must be a nonempty array");
            }
            for (JsonNode limit : limits) {
                if (!limit.isIntegralNumber() || !limit.canConvertToLong() || limit.longValue() <= 0) {
                    throw new IOException("deviceMemoryLimitsBytes must contain positive 64-bit integers");
                }
            }
        }
        return SubprocessArgsIo.mapper().treeToValue(root, ServingSubprocessArgs.class);
    }

    public Path writeToTempFile() throws IOException {
        return SubprocessArgsIo.writeToTempFile(this, "serving-args-");
    }

    /**
     * Create default args for local development.
     */
    public static ServingSubprocessArgs defaults() {
        return new ServingSubprocessArgs(
                8091, "127.0.0.1",
                KompileServerConstants.DEFAULT_STAGING_URL,
                null, null, null,
                null,
                85, 90, 95, 5000,
                85, 90, 95,
                80,
                85, 90, 95,
                256, null, null,
                null, null, null,
                null, null, null, null, null, null,
                null, null, null, null
        );
    }
}
