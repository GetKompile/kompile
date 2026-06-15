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

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Result of a SameDiff benchmark run.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record SamediffBenchmarkResult(
        @JsonProperty("configName") String configName,
        @JsonProperty("passed") boolean passed,
        @JsonProperty("failureMessage") String failureMessage,

        // Timing metrics (milliseconds)
        @JsonProperty("resetMs") long resetMs,
        @JsonProperty("compileMs") long compileMs,
        @JsonProperty("decodeMs") long decodeMs,
        @JsonProperty("validateMs") long validateMs,
        @JsonProperty("totalMs") long totalMs,

        // Token metrics
        @JsonProperty("tokenCount") int tokenCount,
        @JsonProperty("tokPerSec") double tokPerSec,
        @JsonProperty("decodeTokPerSec") double decodeTokPerSec,
        @JsonProperty("firstTokenMs") long firstTokenMs,

        // Triton metrics
        @JsonProperty("tritonLaunches") int tritonLaunches,
        @JsonProperty("tritonCacheHits") int tritonCacheHits,

        // Output preview
        @JsonProperty("generatedTextPreview") String generatedTextPreview,
        @JsonProperty("finishReason") String finishReason,

        // Timestamp
        @JsonProperty("timestamp") String timestamp
) {

    /**
     * Creates a failed benchmark result.
     */
    public static SamediffBenchmarkResult failed(String configName, String failureMessage) {
        return new SamediffBenchmarkResult(
                configName, false, failureMessage,
                0, 0, 0, 0, 0,
                0, 0.0, 0.0, 0,
                0, 0,
                null, "error",
                java.time.Instant.now().toString()
        );
    }
}
