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

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * Arguments passed to the graph subprocess via JSON file.
 *
 * <p>Contains configuration for running graph extraction and matrix graph
 * algorithms in an isolated subprocess, preventing CUDA/ND4J crashes from
 * affecting the main orchestrator process.</p>
 *
 * <p>The subprocess receives document chunks, runs LLM-based entity/relationship
 * extraction, executes matrix graph algorithms (PageRank, HITS, etc.), and
 * writes results to an output file for the main app to persist.</p>
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record GraphSubprocessArgs(
        /** Unique task identifier for tracking and logging */
        String taskId,

        /** Fact sheet ID for scoping graph operations (nullable) */
        Long factSheetId,

        /** Collection name for graph scoping */
        String collectionName,

        /** Document chunks to process — each entry has content + metadata */
        List<DocumentChunk> documentChunks,

        // === Graph Extraction Configuration ===

        /** Entity types to extract (empty = extract all) */
        List<String> entityTypes,

        /** Relationship types to extract (empty = extract all) */
        List<String> relationshipTypes,

        /** LLM provider for extraction (e.g., "openai", "anthropic", "llm-chat") */
        String llmProvider,

        /** LLM model name (null = provider default) */
        String llmModelName,

        /** LLM temperature for extraction */
        double llmTemperature,

        /** Max tokens for LLM response */
        int llmMaxTokens,

        /** Custom extraction prompt (null = use default) */
        String customPrompt,

        /** Schema enforcement mode: NONE, LENIENT, STRICT */
        String schemaEnforcementMode,

        /** Minimum confidence threshold for keeping extracted entities */
        double minConfidence,

        /** Batch size for document processing */
        int batchSize,

        /** Whether to skip entity embedding generation */
        boolean skipEmbedding,

        /** Whether to run matrix graph algorithms (PageRank, HITS, etc.) */
        boolean runMatrixAlgorithms,

        /** Whether to persist the matrix graph */
        boolean persistMatrixGraph,

        /** Whether to run entity resolution */
        boolean entityResolution,

        /** Similarity threshold for entity resolution */
        double entityResolutionThreshold,

        // === Model Source Configuration ===

        /** Model source type: "staging" or "archive" */
        String modelSourceType,

        /** Model identifier for embeddings */
        String modelIdentifier,

        /** Staging service URL */
        String stagingUrl,

        /** Staging service API key */
        String stagingApiKey,

        /** Archive path for local model files */
        String archivePath,

        // === Subprocess Infrastructure ===

        /** Base URL for HTTP callbacks to main app */
        String callbackBaseUrl,

        /** ND4J environment configuration as JSON string */
        String nd4jConfigJson,

        /** Path to write extraction results JSON */
        String outputPath,

        // === Memory Monitoring Configuration ===

        int memoryThresholdPercent,
        int memoryCriticalPercent,
        int memoryKillThresholdPercent,
        long memoryCheckIntervalMs,

        // === GPU Memory Monitoring ===

        int gpuMemoryThresholdPercent,
        int gpuMemoryCriticalPercent,
        int gpuMemoryKillThresholdPercent,

        // === Off-Heap (JavaCPP/Native) Memory Monitoring ===

        int offHeapThresholdPercent,
        int offHeapCriticalPercent,
        int offHeapKillThresholdPercent,

        /** Additional options */
        Map<String, Object> options
) {
    // Memory watchdog defaults
    public static final int DEFAULT_MEMORY_THRESHOLD_PERCENT = 80;
    public static final int DEFAULT_MEMORY_CRITICAL_PERCENT = 90;
    public static final int DEFAULT_MEMORY_KILL_THRESHOLD_PERCENT = 95;
    public static final long DEFAULT_MEMORY_CHECK_INTERVAL_MS = 2000;
    // GPU threshold defaults
    public static final int DEFAULT_GPU_MEMORY_THRESHOLD_PERCENT = 75;
    public static final int DEFAULT_GPU_MEMORY_CRITICAL_PERCENT = 85;
    public static final int DEFAULT_GPU_MEMORY_KILL_THRESHOLD_PERCENT = 92;
    // Off-heap threshold defaults
    public static final int DEFAULT_OFF_HEAP_THRESHOLD_PERCENT = 80;
    public static final int DEFAULT_OFF_HEAP_CRITICAL_PERCENT = 90;
    public static final int DEFAULT_OFF_HEAP_KILL_THRESHOLD_PERCENT = 95;
    // Graph defaults
    public static final int DEFAULT_BATCH_SIZE = 10;
    public static final double DEFAULT_MIN_CONFIDENCE = 0.5;
    public static final double DEFAULT_ENTITY_RESOLUTION_THRESHOLD = 0.85;

    /** Compact record for passing document chunks to subprocess */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record DocumentChunk(
            String id,
            String content,
            Map<String, Object> metadata
    ) {}

    /** Result of graph extraction, written to outputPath */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record GraphExtractionResult(
            String taskId,
            int entitiesExtracted,
            int relationshipsExtracted,
            int documentsProcessed,
            long extractionDurationMs,
            long matrixAlgorithmDurationMs,
            List<ExtractedEntity> entities,
            List<ExtractedRelationship> relationships,
            Map<String, Double> pageRankScores,
            Map<String, Double> authorityScores,
            Map<String, Double> hubScores
    ) {
        public static GraphExtractionResult fromFile(Path path) throws IOException {
            return SubprocessArgsIo.fromFile(path, GraphExtractionResult.class);
        }

        public void toFile(Path path) throws IOException {
            SubprocessArgsIo.toFile(path, this);
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ExtractedEntity(
            String id,
            String title,
            String type,
            String description,
            double confidence,
            List<String> aliases,
            Map<String, Object> metadata
    ) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ExtractedRelationship(
            String sourceId,
            String targetId,
            String type,
            String description,
            double weight,
            double confidence
    ) {}

    public GraphSubprocessArgs {
        if (batchSize <= 0) batchSize = DEFAULT_BATCH_SIZE;
        if (minConfidence <= 0) minConfidence = DEFAULT_MIN_CONFIDENCE;
        if (entityResolutionThreshold <= 0) entityResolutionThreshold = DEFAULT_ENTITY_RESOLUTION_THRESHOLD;
        if (schemaEnforcementMode == null || schemaEnforcementMode.isBlank()) schemaEnforcementMode = "LENIENT";
        if (llmProvider == null || llmProvider.isBlank()) llmProvider = "default";
        if (llmMaxTokens <= 0) llmMaxTokens = 4096;
        // Memory watchdog defaults
        if (memoryThresholdPercent <= 0) memoryThresholdPercent = DEFAULT_MEMORY_THRESHOLD_PERCENT;
        if (memoryCriticalPercent <= 0) memoryCriticalPercent = DEFAULT_MEMORY_CRITICAL_PERCENT;
        if (memoryKillThresholdPercent < 0) memoryKillThresholdPercent = DEFAULT_MEMORY_KILL_THRESHOLD_PERCENT;
        if (memoryCheckIntervalMs <= 0) memoryCheckIntervalMs = DEFAULT_MEMORY_CHECK_INTERVAL_MS;
        if (gpuMemoryThresholdPercent <= 0) gpuMemoryThresholdPercent = DEFAULT_GPU_MEMORY_THRESHOLD_PERCENT;
        if (gpuMemoryCriticalPercent <= 0) gpuMemoryCriticalPercent = DEFAULT_GPU_MEMORY_CRITICAL_PERCENT;
        if (gpuMemoryKillThresholdPercent < 0) gpuMemoryKillThresholdPercent = DEFAULT_GPU_MEMORY_KILL_THRESHOLD_PERCENT;
        if (offHeapThresholdPercent <= 0) offHeapThresholdPercent = DEFAULT_OFF_HEAP_THRESHOLD_PERCENT;
        if (offHeapCriticalPercent <= 0) offHeapCriticalPercent = DEFAULT_OFF_HEAP_CRITICAL_PERCENT;
        if (offHeapKillThresholdPercent < 0) offHeapKillThresholdPercent = DEFAULT_OFF_HEAP_KILL_THRESHOLD_PERCENT;
    }

    public static GraphSubprocessArgs fromFile(Path path) throws IOException {
        return SubprocessArgsIo.fromFile(path, GraphSubprocessArgs.class);
    }

    public void toFile(Path path) throws IOException {
        SubprocessArgsIo.toFile(path, this);
    }
}
