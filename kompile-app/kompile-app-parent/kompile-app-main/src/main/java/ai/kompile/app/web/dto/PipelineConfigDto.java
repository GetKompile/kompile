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
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * DTO for pipeline configuration settings.
 * All settings are optional on update - only provided values are changed.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class PipelineConfigDto {

    // ========== Batch Size Settings ==========
    /**
     * Minimum batch size for processing.
     * Range: 1-1000
     */
    private Integer minBatchSize;

    /**
     * Default batch size for processing.
     * Range: 1-10000
     */
    private Integer defaultBatchSize;

    /**
     * Maximum batch size for processing.
     * Range: 1-100000
     */
    private Integer maxBatchSize;

    // ========== Queue Settings ==========
    /**
     * Queue capacity for chunks waiting to be processed.
     * Range: 100-100000
     */
    private Integer queueCapacity;

    /**
     * Queue poll timeout in milliseconds.
     * Range: 10-10000
     */
    private Long queuePollTimeoutMs;

    /**
     * Maximum wait time for batch accumulation in milliseconds.
     * Range: 10-60000
     */
    private Long maxBatchWaitMs;

    /**
     * Minimum wait time for batch accumulation in milliseconds.
     * Range: 1-10000
     */
    private Long minBatchWaitMs;

    // ========== Thread Settings ==========
    /**
     * Number of threads for chunking documents.
     * Range: 1-64
     */
    private Integer chunkingThreads;

    /**
     * Number of threads for computing embeddings.
     * Range: 1-16
     */
    private Integer embeddingThreads;

    /**
     * Number of threads for Lucene indexing.
     * Range: 1-32
     */
    private Integer indexingThreads;

    /**
     * Number of chunks to accumulate before committing to Lucene index.
     * Range: 1-1000
     */
    private Integer indexingBatchAccumulationSize;

    // ========== Mode Settings ==========
    /**
     * Skip embedding computation entirely (keyword-only mode).
     */
    private Boolean skipEmbedding;

    /**
     * Whether to apply graph optimization when loading SameDiff models.
     *
     * <p>When enabled, applies optimizations like:
     * <ul>
     *   <li>MatMul + Add fusion into xw_plus_b (reduces ops)</li>
     *   <li>Constant folding (pre-computes static expressions)</li>
     *   <li>Dead code elimination (removes unused operations)</li>
     * </ul>
     *
     * <p>This significantly improves inference performance by reducing
     * the number of operations and fusing compatible ops together.
     *
     * <p><b>NOTE:</b> When enabled from a previously disabled state, the embedding
     * model will be automatically reloaded to apply the optimizations.
     *
     * Default: true (optimization is enabled)
     */
    private Boolean optimizeGraphOnLoad;

    // ========== Timeout Settings ==========
    /**
     * Timeout for each embedding batch in seconds.
     * 0 = no timeout.
     * Range: 0-3600 (0 to 1 hour)
     */
    private Integer embeddingTimeoutSeconds;

    // ========== Read-Only Status ==========
    /**
     * Current preset name if using a preset, null if custom.
     * Read-only.
     */
    private String currentPreset;

    /**
     * Whether the configuration has been modified from defaults.
     * Read-only.
     */
    private Boolean isModified;

    /**
     * Available presets.
     * Read-only.
     */
    private String[] availablePresets;

    /**
     * Validate the DTO values are within acceptable ranges.
     */
    public void validate() {
        if (minBatchSize != null && (minBatchSize < 1 || minBatchSize > 1000)) {
            throw new IllegalArgumentException("minBatchSize must be between 1 and 1000");
        }
        if (defaultBatchSize != null && (defaultBatchSize < 1 || defaultBatchSize > 10000)) {
            throw new IllegalArgumentException("defaultBatchSize must be between 1 and 10000");
        }
        if (maxBatchSize != null && (maxBatchSize < 1 || maxBatchSize > 100000)) {
            throw new IllegalArgumentException("maxBatchSize must be between 1 and 100000");
        }
        if (queueCapacity != null && (queueCapacity < 100 || queueCapacity > 100000)) {
            throw new IllegalArgumentException("queueCapacity must be between 100 and 100000");
        }
        if (queuePollTimeoutMs != null && (queuePollTimeoutMs < 10 || queuePollTimeoutMs > 10000)) {
            throw new IllegalArgumentException("queuePollTimeoutMs must be between 10 and 10000");
        }
        if (maxBatchWaitMs != null && (maxBatchWaitMs < 10 || maxBatchWaitMs > 60000)) {
            throw new IllegalArgumentException("maxBatchWaitMs must be between 10 and 60000");
        }
        if (minBatchWaitMs != null && (minBatchWaitMs < 1 || minBatchWaitMs > 10000)) {
            throw new IllegalArgumentException("minBatchWaitMs must be between 1 and 10000");
        }
        if (chunkingThreads != null && (chunkingThreads < 1 || chunkingThreads > 64)) {
            throw new IllegalArgumentException("chunkingThreads must be between 1 and 64");
        }
        if (embeddingThreads != null && (embeddingThreads < 1 || embeddingThreads > 16)) {
            throw new IllegalArgumentException("embeddingThreads must be between 1 and 16");
        }
        if (indexingThreads != null && (indexingThreads < 1 || indexingThreads > 32)) {
            throw new IllegalArgumentException("indexingThreads must be between 1 and 32");
        }
        if (indexingBatchAccumulationSize != null && (indexingBatchAccumulationSize < 1 || indexingBatchAccumulationSize > 1000)) {
            throw new IllegalArgumentException("indexingBatchAccumulationSize must be between 1 and 1000");
        }
        if (embeddingTimeoutSeconds != null && (embeddingTimeoutSeconds < 0 || embeddingTimeoutSeconds > 3600)) {
            throw new IllegalArgumentException("embeddingTimeoutSeconds must be between 0 and 3600");
        }
    }
}
