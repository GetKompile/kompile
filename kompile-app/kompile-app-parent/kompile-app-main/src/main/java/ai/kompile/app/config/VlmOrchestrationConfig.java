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

/**
 * Configuration for VLM GPU memory orchestration.
 * Controls vision encoder lifecycle, multi-GPU device assignment, and Triton JIT cache.
 *
 * <p>Persisted as JSON at {@code ~/.kompile/config/vlm-orchestration-config.json}</p>
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record VlmOrchestrationConfig(
        @JsonProperty("releaseEncoderAfterEncoding") Boolean releaseEncoderAfterEncoding,
        @JsonProperty("encoderDeviceId") Integer encoderDeviceId,
        @JsonProperty("decoderDeviceId") Integer decoderDeviceId,
        @JsonProperty("tritonCacheEnabled") Boolean tritonCacheEnabled,
        @JsonProperty("tritonCacheDir") String tritonCacheDir,
        @JsonProperty("tritonAutoImport") Boolean tritonAutoImport,
        @JsonProperty("tritonAutoExport") Boolean tritonAutoExport
) {

    private static final String DEFAULT_TRITON_CACHE_DIR =
            System.getProperty("user.home") + "/.nd4j/triton_cache";

    /**
     * Returns the default configuration.
     */
    public static VlmOrchestrationConfig defaults() {
        return new VlmOrchestrationConfig(
                true,   // releaseEncoderAfterEncoding
                -1,     // encoderDeviceId (-1 = auto)
                -1,     // decoderDeviceId (-1 = auto)
                true,   // tritonCacheEnabled
                DEFAULT_TRITON_CACHE_DIR,
                true,   // tritonAutoImport
                true    // tritonAutoExport
        );
    }

    /**
     * Merge non-null fields from the given update into this config.
     */
    public VlmOrchestrationConfig merge(VlmOrchestrationConfig update) {
        if (update == null) return this;
        return new VlmOrchestrationConfig(
                update.releaseEncoderAfterEncoding() != null ? update.releaseEncoderAfterEncoding() : this.releaseEncoderAfterEncoding(),
                update.encoderDeviceId() != null ? update.encoderDeviceId() : this.encoderDeviceId(),
                update.decoderDeviceId() != null ? update.decoderDeviceId() : this.decoderDeviceId(),
                update.tritonCacheEnabled() != null ? update.tritonCacheEnabled() : this.tritonCacheEnabled(),
                update.tritonCacheDir() != null ? update.tritonCacheDir() : this.tritonCacheDir(),
                update.tritonAutoImport() != null ? update.tritonAutoImport() : this.tritonAutoImport(),
                update.tritonAutoExport() != null ? update.tritonAutoExport() : this.tritonAutoExport()
        );
    }

    /**
     * Resolve the effective Triton cache directory, defaulting if null.
     */
    public String resolveTritonCacheDir() {
        return tritonCacheDir != null ? tritonCacheDir : DEFAULT_TRITON_CACHE_DIR;
    }
}
