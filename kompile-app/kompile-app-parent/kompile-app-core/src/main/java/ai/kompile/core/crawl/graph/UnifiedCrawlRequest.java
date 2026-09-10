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
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Request to start a unified crawl-to-graph job.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(value = "distributedGraphRuntimeContext", ignoreUnknown = true)
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

    /** Graph extraction configuration. Graph extraction is mandatory; null is normalized to defaults. */
    @Builder.Default
    private GraphExtractionConfig graphExtraction = GraphExtractionConfig.builder().build();

    /** Shared production document chunking policy (null = project/chunker defaults). */
    private CrawlChunkingConfig chunking;

    /** Vector indexing configuration (null or disabled = skip vector indexing) */
    private VectorIndexConfig vectorIndex;

    /** Processing route configuration (null = use defaults from ~/.kompile/config/processing-route-config.json) */
    private ProcessingRouteConfig processingRoute;

    /** Per-request runtime overrides (null fields fall back to global config) */
    private RuntimeConfig runtimeConfig;

    /** Pre-processing configuration (null = use defaults) */
    private Object preprocessing;

    /**
     * ENRICHMENT step configuration for the graph hydration pipeline
     * (DERIVATION → PRUNE_COMPACT → HEALTH). When null, defaults are used
     * (all stages enabled, confidencePruneThreshold=0.4, dryRun=false).
     *
     * <p>The matching {@code ai.kompile.crawl.graph.HydrationConfig} used internally
     * by the orchestrator is constructed from this request field.</p>
     */
    private HydrationConfig hydration;

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

    /** Internal coordinator-issued shared-graph binding for one delegated partition. */
    private DistributedGraphExecution distributedGraphExecution;

    /** Runtime-only secret gateway context attached after delegated-job JSON deserialization. */
    @JsonIgnore
    private transient DistributedGraphRuntimeContext distributedGraphRuntimeContext;

    // ---- Selective retry fields ----

    /** When set, this job is a retry of a previous job. Only failed documents from that job are re-processed. */
    private String retryFromJobId;

    /** Phase to retry from (e.g. "GRAPH_EXTRACTION"). If null, retries all failed documents regardless of phase. */
    private String retryPhase;

    /** Explicit set of document keys to retry. If null/empty, all failed documents from retryFromJobId are retried. */
    @Builder.Default
    private List<String> retryDocumentKeys = new ArrayList<>();

    /** Maximum validation-repair retries per model unit (schema batch, document, or chunk group). */
    @Builder.Default
    private int maxValidationRetries = 2;

    /**
     * When not {@code false}, the crawl derives + binds a structural ontology from the built graph
     * during ENRICHMENT, so OWL is-a/has-a reasoning + entity classification operate automatically.
     * Default (null) = enabled.
     */
    private Boolean deriveOntology;

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
     * When TRUE and {@link #enabledSteps} is non-empty, the resolved step plan honors the selection
     * strictly: only the enabled steps, their transitive hard dependencies, and foundational steps
     * RUN — the mandatory graph spine (GRAPH_PREP/GRAPH_EXTRACTION/SURFACING/ENTITY_RESOLUTION/
     * EDGE_COMPUTATION) is NOT force-added. Null/false = legacy behavior (spine always seeded).
     * Lets a single-source run select e.g. only VECTOR_INDEXING or only GRAPH_EXTRACTION.
     */
    private Boolean strictSteps;

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
        /** Number of workers to distribute across; {@code 0} = auto (size to the live cluster). */
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
     * Configuration for the ENRICHMENT step graph hydration pipeline.
     *
     * <p>Mirrors {@code ai.kompile.crawl.graph.HydrationConfig} so the request
     * can be serialized/deserialized independently of the crawl-graph module.
     *
     * <p>Stage IDs: {@code DERIVATION}, {@code PRUNE_COMPACT}, {@code HEALTH}.
     * Empty {@code enabledStageIds} means run all stages (default behaviour).
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class HydrationConfig {
        /**
         * Stage IDs to execute. Empty/null = run all stages.
         * Valid values: {@code DERIVATION}, {@code PRUNE_COMPACT}, {@code HEALTH}.
         */
        private Set<String> enabledStageIds;

        /**
         * Confidence threshold for pruning low-confidence inferred edges.
         * Default: 0.4. Values outside [0.0, 1.0] fall back to 0.4.
         */
        @Builder.Default
        private double confidencePruneThreshold = 0.4;

        /**
         * When true no writes are performed — stages execute in dry-run mode.
         */
        @Builder.Default
        private boolean dryRun = false;
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

        /** Concurrent in-flight REMOTE (CLI/API) extraction calls (1-32). Few fat calls beat many; each
         *  remote call has a large fixed cost. Local models use {@link #graphExtractionParallelism}. */
        private Integer graphExtractionRemoteParallelism;

        /** Safety cap on chunks packed into one extraction batch (the model-derived char budget is the
         *  primary control; this prevents item count binding before the char budget). */
        private Integer graphExtractionMaxItemsPerBatch;

        /**
         * Per-request override: when {@code Boolean.FALSE}, disable incremental content-hash
         * skipping for this crawl even if {@code crawlIncrementalByContentHash=true} globally.
         * When {@code Boolean.TRUE}, enable it even if the global flag is off.
         * {@code null} = use global setting (default).
         */
        private Boolean incrementalByContentHash;

        /**
         * When true, force a full re-crawl for this request — every file is (re-)processed
         * and the hash store is updated, regardless of the global
         * {@code crawlForceFullRecrawl} flag. {@code null} = use global setting (default).
         */
        private Boolean forceFullRecrawl;

        /**
         * [FIX-4] When {@code Boolean.TRUE}, clear the fact sheet's graph at the very start
         * of this crawl (before LOADING) — the crawl starts from a clean slate.
         * When {@code Boolean.FALSE} or {@code null}, merge/update (default, non-destructive).
         *
         * <p>This is a destructive per-request opt-in. Use only when you need a full graph
         * replacement rather than incremental enrichment.</p>
         */
        private Boolean clearGraphBeforeRun;

        /** Per-request override for post-enrichment knowledge-graph embedding training. */
        private Boolean trainEmbeddingsAfterEnrichment;

        /** Knowledge-graph embedding algorithm: TRANSE or ROTATE. */
        private String embeddingAlgorithm;

        /** Entity/relation embedding dimension. Null uses the algorithm default. */
        private Integer embeddingDim;

        /** Cold-start training epochs. Null uses the algorithm default. */
        private Integer embeddingEpochs;

        /** Training batch size. Null uses the crawl/global or algorithm default. */
        private Integer embeddingBatchSize;

        /** Epochs used when incrementally warm-starting a persisted model. */
        private Integer embeddingWarmStartEpochs;
    }
}
