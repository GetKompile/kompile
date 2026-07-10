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
 * Data model representing a GPU device with its memory capacity.
 *
 * <p>Fields use nvidia-smi device index, which may differ from the CUDA runtime
 * device index visible inside the JVM. The {@code cudaRuntimeIndex} field captures
 * the mapping so callers can set {@code nd4j.environment.cudaCurrentDevice} correctly.</p>
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record GpuDevice(
        /** nvidia-smi device index (0-based) */
        @JsonProperty("nvidiaSmiIndex") int nvidiaSmiIndex,

        /** CUDA runtime device index as seen by the JVM (may differ from nvidia-smi) */
        @JsonProperty("cudaRuntimeIndex") int cudaRuntimeIndex,

        /** Device name, e.g. "NVIDIA GeForce RTX 4090" */
        @JsonProperty("name") String name,

        /** Total GPU memory in bytes */
        @JsonProperty("totalMemoryBytes") long totalMemoryBytes,

        /** Node identifier for multi-node extension. "local" for the current machine. */
        @JsonProperty("nodeId") String nodeId
) {

    /**
     * Total GPU memory in megabytes (convenience).
     */
    public long totalMemoryMb() {
        return totalMemoryBytes / (1024L * 1024L);
    }

    /**
     * Create a GpuDevice for the local node.
     */
    public static GpuDevice local(int nvidiaSmiIndex, int cudaRuntimeIndex, String name, long totalMemoryBytes) {
        return new GpuDevice(nvidiaSmiIndex, cudaRuntimeIndex, name, totalMemoryBytes, "local");
    }

    @Override
    public String toString() {
        return String.format("GPU[smi=%d, cuda=%d, %s, %dMB, node=%s]",
                nvidiaSmiIndex, cudaRuntimeIndex, name, totalMemoryMb(), nodeId);
    }
}
