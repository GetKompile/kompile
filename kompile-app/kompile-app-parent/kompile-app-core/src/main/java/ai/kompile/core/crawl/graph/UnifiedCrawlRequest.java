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

package ai.kompile.core.crawl.graph;

import ai.kompile.core.crawler.pipeline.ContentRouteRule;
import ai.kompile.core.crawler.pipeline.IngestPipelineDefinition;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Request to start a unified crawl-to-graph job.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class UnifiedCrawlRequest {

    /** Human-readable name for this crawl job */
    private String name;

    /** Fact-sheet scope where graph/vector crawl output should be written */
    private Long factSheetId;

    /** Fact-sheet name to resolve ID from (used when factSheetId is not set) */
    private String factSheetName;

    /** List of sources to crawl */
    @Builder.Default
    private List<UnifiedCrawlSource> sources = new ArrayList<>();

    /** Graph extraction configuration (null or disabled = skip graph extraction) */
    private GraphExtractionConfig graphExtraction;

    /** Vector indexing configuration (null or disabled = skip vector indexing) */
    private VectorIndexConfig vectorIndex;

    /** Processing route configuration (null = use defaults from ~/.kompile/config/processing-route-config.json) */
    private ProcessingRouteConfig processingRoute;

    /** Per-request runtime overrides (null fields fall back to global config) */
    private RuntimeConfig runtimeConfig;

    /** Pre-processing configuration (null = use defaults) */
    private Object preprocessing;

    /** Named ingest pipeline definitions (empty list = use defaults) */
    @Builder.Default
    private List<IngestPipelineDefinition> pipelines = new ArrayList<>();

    /** Content routing rules for directing sources to specific pipelines (empty list = use defaults) */
    @Builder.Default
    private List<ContentRouteRule> routeRules = new ArrayList<>();

    /** Default pipeline ID to use when no route rule matches (null = system default) */
    private String defaultPipelineId;

    /** Distribution configuration for multi-worker crawls (null = single-worker) */
    private DistributionConfig distribution;

    // ---- Selective retry fields ----

    /** When set, this job is a retry of a previous job. Only failed documents from that job are re-processed. */
    private String retryFromJobId;

    /** Phase to retry from (e.g. "GRAPH_EXTRACTION"). If null, retries all failed documents regardless of phase. */
    private String retryPhase;

    /** Explicit set of document keys to retry. If null/empty, all failed documents from retryFromJobId are retried. */
    @Builder.Default
    private List<String> retryDocumentKeys = new ArrayList<>();

    /** Maximum number of validation retries per document before marking as permanently failed */
    @Builder.Default
    private int maxValidationRetries = 2;

    // ---- Modular step selection ----

    /**
     * Explicit whitelist of pipeline step IDs to run (e.g. "LOADING", "CHUNKING", "VECTOR_INDEXING").
     * When non-null and non-empty, every step NOT listed defaults to SKIP. Null/empty = run all
     * steps (backward compatible). Matches the step IDs tracked by PipelineStepTracker.
     */
    @Builder.Default
    private List<String> enabledSteps = new ArrayList<>();

    /**
     * Pipeline step IDs to ARCHIVE rather than run. Their inputs (e.g. chunked documents) are
     * persisted to disk and the step is marked ARCHIVED so it can be run later — including after a
     * process restart. Null/empty = archive nothing.
     */
    @Builder.Default
    private List<String> archivedSteps = new ArrayList<>();

    /**
     * Strategy for partitioning sources across workers in distributed crawls.
     */
    public enum PartitionStrategy {
        /** Round-robin assignment across workers */
        ROUND_ROBIN,
        /** Partition by source type (e.g., all PDFs to one worker) */
        BY_TYPE,
        /** Partition by estimated size for load balancing */
        BY_SIZE,
        /** One worker per source */
        PER_SOURCE,
        /** Hash-based sharding across workers */
        HASH_SHARD
    }

    /**
     * Configuration for distributing a crawl across multiple workers.
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class DistributionConfig {
        /** Number of workers to distribute across */
        @Builder.Default
        private int workerCount = 1;

        /** How to partition sources across workers */
        @Builder.Default
        private PartitionStrategy partitionStrategy = PartitionStrategy.ROUND_ROBIN;

        /** Callback URL for worker completion notifications */
        private String callbackUrl;

        /** Additional metadata to pass to each worker */
        private Map<String, Object> workerMetadata;
    }

    /**
     * Per-request runtime configuration overrides.
     * Any non-null field overrides the corresponding global config value.
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class RuntimeConfig {
        /** Graph extraction parallelism (1-32). CLI/I/O-bound extraction benefits from 8-16. */
        private Integer graphExtractionParallelism;

        /** Max items per graph extraction batch */
        private Integer graphExtractionBatchSize;

        /** Target characters per graph extraction batch */
        private Integer graphExtractionTargetCharsPerBatch;

        /** Source loading parallelism (1-32) */
        private Integer sourceLoadParallelism;

        /** Chunking parallelism (1-32) */
        private Integer chunkingParallelism;

        /** Vector indexing batch size (0 = auto) */
        private Integer vectorBatchSize;

        /** Whether to sort chunks by estimated cost before batching */
        private Boolean costSortChunks;

        /** Batch size for entity resolution (deduplication) pass */
        private Integer entityResolutionBatchSize;

        /** Parallelism for edge computation (relationship resolution) */
        private Integer edgeComputationParallelism;

        /** Parallelism for vector indexing */
        private Integer vectorIndexingParallelism;

        /** Whether to run vector indexing and graph extraction in parallel */
        private Boolean parallelVectorAndGraph;

        /** Timeout in seconds for a single LLM call */
        private Integer llmCallTimeoutSeconds;

        /** Timeout in seconds for an entire graph extraction batch */
        private Integer graphExtractionBatchTimeoutSeconds;
    }
}
