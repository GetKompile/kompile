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

package ai.kompile.core.embeddings;

/**
 * Interface for GPU-aware batch size calculation.
 * <p>
 * Implementations integrate with GPU memory management infrastructure
 * (e.g., GpuResourceManager) to cap batch sizes based on actual GPU
 * memory availability and per-service reservations.
 */
public interface GpuAwareBatchSizer {

    /**
     * Calculates a GPU-aware batch size, potentially reducing the base batch size
     * if GPU memory constraints require it.
     *
     * @param baseBatchSize         the configured batch size before GPU capping
     * @param deviceMemoryRatio     fraction of GPU memory to use (0.0-1.0)
     * @param serviceType           service type for GPU reservation lookup (e.g., "embedding")
     * @param estimatedBytesPerSample estimated GPU memory per batch sample (0 = skip GPU check)
     * @return GPU-aware batch size (may be less than baseBatchSize)
     */
    int calculateGpuAwareBatchSize(int baseBatchSize, double deviceMemoryRatio,
                                    String serviceType, long estimatedBytesPerSample);

    /**
     * Gets the effective device memory ratio for a given use case.
     *
     * @param useCase the use case identifier (e.g., "embedding", "entityResolution")
     * @return device memory ratio (0.0-1.0), defaults to 0.7
     */
    double getDeviceMemoryRatio(String useCase);
}
