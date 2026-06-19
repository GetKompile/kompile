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

import ai.kompile.core.graphrag.model.Graph;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.ai.document.Document;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Tracks the state and progress of a unified crawl-to-graph job.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class UnifiedCrawlJob {

    public enum Status {
        PENDING, RUNNING, PAUSED, COMPLETED, COMPLETED_PENDING_EMBEDDING, COMPLETED_PENDING_GRAPH, FAILED, CANCELLED
    }

    public enum PipelineStepStatus {
        PENDING, RUNNING, BACKPRESSURE, COMPLETED, FAILED, SKIPPED, DEFERRED, ARCHIVED, CANCELLED
    }

    /** Unique job identifier */
    private String jobId;

    /** The original request */
    private UnifiedCrawlRequest request;

    /** Current job status */
    @Builder.Default
    private AtomicReference<Status> status = new AtomicReference<>(Status.PENDING);

    /** When the job was created */
    @Builder.Default
    private Instant createdAt = Instant.now();

    /** When the job started running */
    private Instant startedAt;

    /** When the job completed/failed/was cancelled */
    private Instant completedAt;

    // ---- Progress counters ----

    /** Total documents discovered across all sources */
    @Builder.Default
    private AtomicInteger documentsDiscovered = new AtomicInteger(0);

    /** Documents loaded and chunked */
    @Builder.Default
    private AtomicInteger documentsLoaded = new AtomicInteger(0);

    /** Chunks that have had graph extraction run */
    @Builder.Default
    private AtomicInteger chunksProcessed = new AtomicInteger(0);

    /** Documents indexed to vector store */
    @Builder.Default
    private AtomicInteger documentsIndexed = new AtomicInteger(0);

    /** Chunks that have been created by the chunker */
    @Builder.Default
    private AtomicInteger chunksCreated = new AtomicInteger(0);

    /** Chunks that have completed graph extraction */
    @Builder.Default
    private AtomicInteger graphChunksProcessed = new AtomicInteger(0);

    /** Total chunks scheduled for graph extraction */
    @Builder.Default
    private AtomicInteger graphChunksTotal = new AtomicInteger(0);

    /** Chunks queued for vector embedding/indexing */
    @Builder.Default
    private AtomicInteger chunksQueuedForEmbedding = new AtomicInteger(0);

    /** Chunks that have completed embedding */
    @Builder.Default
    private AtomicInteger chunksEmbedded = new AtomicInteger(0);

    /** Entities extracted */
    @Builder.Default
    private AtomicInteger entitiesExtracted = new AtomicInteger(0);

    /** Relationships extracted */
    @Builder.Default
    private AtomicInteger relationshipsExtracted = new AtomicInteger(0);

    /** Documents that have been preprocessed (e.g., translated, cleaned) */
    @Builder.Default
    private AtomicInteger documentsPreprocessed = new AtomicInteger(0);

    /** Documents that have been translated to a target language */
    @Builder.Default
    private AtomicInteger documentsTranslated = new AtomicInteger(0);

    /** Number of graph extraction LLM retries across all batches */
    @Builder.Default
    private AtomicInteger graphExtractionRetries = new AtomicInteger(0);

    /** Number of graph extraction batches that ultimately failed to parse (all retries exhausted) */
    @Builder.Default
    private AtomicInteger graphExtractionParseFailures = new AtomicInteger(0);

    /** Per-entity-type counts accumulated during extraction (e.g. REGIONAL_FORECAST: 50, CELL: 200) */
    @Builder.Default
    private Map<String, AtomicLong> entityTypeCounts = new ConcurrentHashMap<>();

    /** Per-relationship-type counts accumulated during extraction (e.g. CONTAINS: 100, RELATES_TO: 50) */
    @Builder.Default
    private Map<String, AtomicLong> relationshipTypeCounts = new ConcurrentHashMap<>();

    /** Errors encountered */
    @Builder.Default
    private AtomicInteger errorCount = new AtomicInteger(0);

    /** Name/path of the file currently being processed */
    @Builder.Default
    private AtomicReference<String> currentFile = new AtomicReference<>(null);

    /** Current high-level pipeline phase */
    @Builder.Default
    private AtomicReference<String> currentPhase = new AtomicReference<>("QUEUED");

    /** Estimated overall progress percentage */
    @Builder.Default
    private AtomicInteger progressPercent = new AtomicInteger(0);

    /** Queue position for PENDING jobs. 0 means running or not queued. */
    @Builder.Default
    private AtomicInteger queuePosition = new AtomicInteger(0);

    /** Number of active unified crawl jobs when the last snapshot was recorded */
    @Builder.Default
    private AtomicInteger activeJobs = new AtomicInteger(0);

    /** Number of queued unified crawl jobs when the last snapshot was recorded */
    @Builder.Default
    private AtomicInteger queuedJobs = new AtomicInteger(0);

    /** Configured max concurrent unified crawl jobs */
    @Builder.Default
    private AtomicInteger maxConcurrentJobs = new AtomicInteger(1);

    /** Configured queue capacity for unified crawl jobs */
    @Builder.Default
    private AtomicInteger queueCapacity = new AtomicInteger(0);

    /** Timestamp when the job entered the queue */
    private Instant queuedAt;

    /** Current JVM heap usage percentage for this job's last progress update */
    @Builder.Default
    private AtomicInteger memoryUsagePercent = new AtomicInteger(0);

    /** Peak JVM heap usage percentage observed for this job */
    @Builder.Default
    private AtomicInteger peakMemoryUsagePercent = new AtomicInteger(0);

    /** Current JavaCPP physical/native memory usage percentage, when available */
    @Builder.Default
    private AtomicInteger nativeMemoryUsagePercent = new AtomicInteger(0);

    /** Peak JavaCPP physical/native memory usage percentage observed for this job */
    @Builder.Default
    private AtomicInteger peakNativeMemoryUsagePercent = new AtomicInteger(0);

    /** Heap bytes used at last progress update */
    @Builder.Default
    private AtomicLong heapUsedBytes = new AtomicLong(0L);

    /** Max heap bytes at last progress update */
    @Builder.Default
    private AtomicLong heapMaxBytes = new AtomicLong(0L);

    /** JavaCPP physical bytes at last progress update, when available */
    @Builder.Default
    private AtomicLong nativePhysicalBytes = new AtomicLong(0L);

    /** Peak JavaCPP physical bytes observed for this job */
    @Builder.Default
    private AtomicLong peakNativePhysicalBytes = new AtomicLong(0L);

    /** JavaCPP tracked total bytes at last progress update, when available */
    @Builder.Default
    private AtomicLong nativeTotalBytes = new AtomicLong(0L);

    /** JavaCPP max physical byte limit for this process, when available */
    @Builder.Default
    private AtomicLong nativeMaxPhysicalBytes = new AtomicLong(0L);

    /** JVM direct buffer bytes at last progress update */
    @Builder.Default
    private AtomicLong directBufferBytes = new AtomicLong(0L);

    /** Total vector embedding/indexing batches planned */
    @Builder.Default
    private AtomicInteger vectorBatchesTotal = new AtomicInteger(0);

    /** Vector embedding/indexing batches completed */
    @Builder.Default
    private AtomicInteger vectorBatchesCompleted = new AtomicInteger(0);

    /** Size of the current vector embedding/indexing batch */
    @Builder.Default
    private AtomicInteger currentBatchSize = new AtomicInteger(0);

    /** Configured/effective embedding batch size */
    @Builder.Default
    private AtomicInteger embeddingBatchSize = new AtomicInteger(0);

    /** Embedding model optimal batch size, when known */
    @Builder.Default
    private AtomicInteger embeddingModelOptimalBatchSize = new AtomicInteger(0);

    /** Embedding model maximum batch size, when known */
    @Builder.Default
    private AtomicInteger embeddingModelMaxBatchSize = new AtomicInteger(0);

    /** Whether single DSP plan mode is active for embedding inference */
    @Builder.Default
    private AtomicBoolean embeddingSingleDspPlan = new AtomicBoolean(false);

    /** Fixed DSP plan batch size for embedding inference (0 = not configured) */
    @Builder.Default
    private AtomicInteger embeddingDspPlanBatchSize = new AtomicInteger(0);

    /** Current batch step or backpressure message */
    @Builder.Default
    private AtomicReference<String> currentBatchStep = new AtomicReference<>(null);

    // ---- Work-stealing scheduler stats ----

    /** Total tasks stolen by idle workers from busy workers' deques */
    @Builder.Default
    private AtomicLong workStealCount = new AtomicLong(0);

    /** Failed steal attempts (victim deque was empty) */
    @Builder.Default
    private AtomicLong workStealFailures = new AtomicLong(0);

    /** Total tasks dispatched locally (no steal needed) */
    @Builder.Default
    private AtomicLong localDispatchCount = new AtomicLong(0);

    /** Peak worker imbalance ratio observed (max/min queue depth) */
    @Builder.Default
    private AtomicLong workImbalanceRatioX100 = new AtomicLong(100);

    // ---- Dynamic batch sizing stats ----

    /** Current adaptive batch size (adjusted dynamically based on memory/throughput) */
    @Builder.Default
    private AtomicInteger adaptiveBatchSize = new AtomicInteger(0);

    /** Number of batch size adjustments made during this job */
    @Builder.Default
    private AtomicInteger batchSizeAdjustments = new AtomicInteger(0);

    /** Direction of last batch size adjustment: UP, DOWN, or HOLD */
    @Builder.Default
    private AtomicReference<String> lastBatchAdjustDirection = new AtomicReference<>("HOLD");

    /** Reason for last batch size adjustment */
    @Builder.Default
    private AtomicReference<String> lastBatchAdjustReason = new AtomicReference<>(null);

    /** EMA of batch processing time in milliseconds (x100 for precision) */
    @Builder.Default
    private AtomicLong batchEmaLatencyMsX100 = new AtomicLong(0);

    /** Best observed throughput in items/sec (x100 for precision) */
    @Builder.Default
    private AtomicLong peakThroughputX100 = new AtomicLong(0);

    // ---- Token budget / CLI usage stats ----

    /** Total input tokens consumed across all LLM backends */
    @Builder.Default
    private AtomicLong totalInputTokens = new AtomicLong(0);

    /** Total output tokens consumed across all LLM backends */
    @Builder.Default
    private AtomicLong totalOutputTokens = new AtomicLong(0);

    /** Estimated cost in USD cents (x100 for sub-cent precision) */
    @Builder.Default
    private AtomicLong estimatedCostCentsX100 = new AtomicLong(0);

    /** Per-backend token usage and routing stats */
    @Builder.Default
    private Map<String, BackendRoutingStats> backendStats = new ConcurrentHashMap<>();

    // ---- Workload rerouting stats ----

    /** Total work items rerouted from one backend to another */
    @Builder.Default
    private AtomicLong reroutedItems = new AtomicLong(0);

    /** Total work items dropped after all backends exhausted */
    @Builder.Default
    private AtomicLong droppedItems = new AtomicLong(0);

    /** Recent rerouting events for UI visibility (bounded to last 20) */
    @Builder.Default
    private List<RerouteEvent> recentRerouteEvents = new CopyOnWriteArrayList<>();

    // ---- Retry / fallback stats ----

    /** Total batches retried across all pipeline stages */
    @Builder.Default
    private AtomicLong retriedBatches = new AtomicLong(0);

    /** Total individual items retried */
    @Builder.Default
    private AtomicLong retriedItems = new AtomicLong(0);

    /** Items in the dead-letter queue (permanently failed after all retries) */
    @Builder.Default
    private AtomicInteger deadLetterCount = new AtomicInteger(0);

    /** Backends currently in cooldown (count) */
    @Builder.Default
    private AtomicInteger backendsCoolingDown = new AtomicInteger(0);

    /** Recent retry events for UI visibility (bounded to last 20) */
    @Builder.Default
    private List<RetryEvent> recentRetryEvents = new CopyOnWriteArrayList<>();

    // ---- Per-LLM-call observability ----

    /** Total individual LLM calls dispatched */
    @Builder.Default
    private AtomicLong llmCallsTotal = new AtomicLong(0);

    /** LLM calls that returned a successful response */
    @Builder.Default
    private AtomicLong llmCallsSucceeded = new AtomicLong(0);

    /** LLM calls that failed (exception, bad response, etc.) */
    @Builder.Default
    private AtomicLong llmCallsFailed = new AtomicLong(0);

    /** LLM calls that timed out */
    @Builder.Default
    private AtomicLong llmCallsTimedOut = new AtomicLong(0);

    /** LLM calls that were rate-limited (429 or quota) */
    @Builder.Default
    private AtomicLong llmCallsRateLimited = new AtomicLong(0);

    /** LLM calls routed through a circuit-breaker-tripped fallback */
    @Builder.Default
    private AtomicLong llmCallsCircuitBroken = new AtomicLong(0);

    /** EMA of per-call LLM latency in ms (x100 for precision) */
    @Builder.Default
    private AtomicLong llmCallEmaLatencyMsX100 = new AtomicLong(0);

    /** Peak single-call LLM latency observed in ms */
    @Builder.Default
    private AtomicLong llmCallPeakLatencyMs = new AtomicLong(0);

    /** Recent individual LLM call records for UI visibility (bounded to last 50) */
    @Builder.Default
    private List<LlmCallRecord> recentLlmCalls = new CopyOnWriteArrayList<>();

    /** Rolling list of recently discovered file/URL names (bounded to last 50) for live UI feed */
    @Builder.Default
    private List<DiscoveredItem> recentlyDiscoveredItems = new CopyOnWriteArrayList<>();

    /** Per-file error messages collected during the run */
    @Builder.Default
    private List<String> errors = new CopyOnWriteArrayList<>();

    /** Recent stage/progress events for UI and job history visibility */
    @Builder.Default
    private List<StageEvent> recentEvents = new CopyOnWriteArrayList<>();

    /** Structured per-step pipeline progress for UI and job history visibility */
    @Builder.Default
    private List<PipelineStepProgress> pipelineSteps = new CopyOnWriteArrayList<>();

    /** Fine-grained per-document graph/loading progress keyed by stable source path or document id */
    @Builder.Default
    private Map<String, DocumentProgress> documentProgress = new ConcurrentHashMap<>();

    /** Per-source progress */
    @Builder.Default
    private List<SourceProgress> sourceProgress = new CopyOnWriteArrayList<>();

    /** The assembled graph result (populated on completion) */
    private Graph resultGraph;

    /** Error message if the job failed */
    private String errorMessage;

    /** Chunks awaiting deferred embedding (populated when embedding model was not ready at job completion) */
    @Builder.Default
    private List<Document> deferredEmbeddingChunks = new CopyOnWriteArrayList<>();

    /** Vector index config for deferred embedding (preserved from original request) */
    private VectorIndexConfig deferredVectorIndexConfig;

    /** Chunks awaiting deferred graph extraction (previously-failed chunks that survived in-phase retries) */
    @Builder.Default
    private List<Document> deferredGraphChunks = new CopyOnWriteArrayList<>();

    /** Graph extraction config for deferred graph extraction (preserved from original request) */
    private GraphExtractionConfig deferredGraphExtractionConfig;

    /**
     * Progress for an individual source within the unified job.
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class SourceProgress {
        private String label;
        private String sourceType;
        private String pathOrUrl;
        private Status status;
        private int documentsDiscovered;
        private int documentsLoaded;
        private int chunksCreated;
        private int entitiesExtracted;
        private int relationshipsExtracted;
        private String currentPhase;
        private String currentItem;
        private String errorMessage;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class DiscoveredItem {
        private String name;
        private String sourceType;
        private String sourceLabel;
        private Instant discoveredAt;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class StageEvent {
        private Instant timestamp;
        private String phase;
        private String level;
        private String message;
        private String details;
        private Integer progressPercent;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class DocumentProgress {
        private String documentKey;
        private String fileName;
        private String sourcePath;
        private String sourceType;
        private String contentType;
        private String loaderName;
        private String phase;
        private String status;
        private String message;
        private String errorMessage;

        @Builder.Default
        private int chunksCreated = 0;

        @Builder.Default
        private int chunksEmbedded = 0;

        @Builder.Default
        private int chunksIndexed = 0;

        @Builder.Default
        private int entitiesExtracted = 0;

        @Builder.Default
        private int relationshipsExtracted = 0;

        @Builder.Default
        private int graphNodesCreated = 0;

        @Builder.Default
        private int graphEdgesCreated = 0;

        @Builder.Default
        private List<String> extractors = new ArrayList<>();

        private Instant startedAt;
        private Instant updatedAt;
        private Instant completedAt;
    }

    /**
     * Per-backend routing and token usage statistics.
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class BackendRoutingStats {
        private String backendId;
        private String backendType;
        @Builder.Default
        private long requestsDispatched = 0;
        @Builder.Default
        private long requestsCompleted = 0;
        @Builder.Default
        private long requestsFailed = 0;
        @Builder.Default
        private long requestsRerouted = 0;
        @Builder.Default
        private long inputTokens = 0;
        @Builder.Default
        private long outputTokens = 0;
        @Builder.Default
        private long estimatedCostCentsX100 = 0;
        /** EMA latency in ms (x100) */
        @Builder.Default
        private long emaLatencyMsX100 = 0;
        /** Current active request count */
        @Builder.Default
        private int activeRequests = 0;
        /** Max concurrent capacity */
        @Builder.Default
        private int maxConcurrent = 0;
        /** Whether this backend is currently healthy and accepting work */
        @Builder.Default
        private boolean healthy = true;
        /** Reason if unhealthy */
        private String unhealthyReason;
    }

    /**
     * A rerouting event recording when work was moved between backends.
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class RerouteEvent {
        private Instant timestamp;
        private String fromBackend;
        private String toBackend;
        private String taskType;
        private String reason;
        private int itemCount;
    }

    /**
     * A retry event recording when a failed batch or item was retried.
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class RetryEvent {
        private Instant timestamp;
        private String stage;
        private int attempt;
        private int maxAttempts;
        private int itemCount;
        private int originalBatchSize;
        private int reducedBatchSize;
        private String failureReason;
        private String backendId;
        private String fallbackBackendId;
        private long backoffMs;
        private boolean succeeded;
        private boolean sentToDeadLetter;
    }

    /**
     * Per-LLM-call observability record.
     * Captures timing, token usage, backend routing, and outcome for every
     * individual LLM prompt dispatched during a crawl job.
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class LlmCallRecord {
        private Instant timestamp;
        /** Backend that handled the call (e.g. "default", "claude-cli", "openai-api") */
        private String backendId;
        /** Processing task type (e.g. "llm", "vlm", "embedding") */
        private String taskType;
        /** Wall-clock latency of the call in milliseconds */
        private long latencyMs;
        /** Approximate input tokens (prompt length / 4) */
        private long inputTokens;
        /** Approximate output tokens (response length / 4) */
        private long outputTokens;
        /** Whether the call returned a usable response */
        private boolean success;
        /** Whether the call timed out */
        private boolean timedOut;
        /** Whether the call was rate-limited (429 / quota) */
        private boolean rateLimited;
        /** Whether a circuit breaker was tripped for this backend */
        private boolean circuitBroken;
        /** Error category if failed (from BatchRetryPolicy.FailureCategory) */
        private String errorCategory;
        /** Short error message if failed */
        private String errorMessage;
        /** Prompt length in characters (for cost correlation) */
        private int promptChars;
        /** Response length in characters */
        private int responseChars;
        /** Full prompt text sent to the LLM (excluded from progress snapshot to avoid bloat) */
        private String promptText;
        /** Full response text from the LLM (excluded from progress snapshot to avoid bloat) */
        private String responseText;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class PipelineStepProgress {
        private String stepId;
        private String displayName;
        private String stepType;

        @Builder.Default
        private AtomicReference<PipelineStepStatus> status =
                new AtomicReference<>(PipelineStepStatus.PENDING);

        @Builder.Default
        private AtomicInteger progressPercent = new AtomicInteger(0);

        @Builder.Default
        private AtomicInteger totalItems = new AtomicInteger(0);

        @Builder.Default
        private AtomicInteger completedItems = new AtomicInteger(0);

        @Builder.Default
        private AtomicInteger failedItems = new AtomicInteger(0);

        @Builder.Default
        private AtomicInteger activeTasks = new AtomicInteger(0);

        @Builder.Default
        private AtomicInteger totalBatches = new AtomicInteger(0);

        @Builder.Default
        private AtomicInteger completedBatches = new AtomicInteger(0);

        @Builder.Default
        private AtomicInteger currentBatchSize = new AtomicInteger(0);

        @Builder.Default
        private AtomicReference<String> currentItem = new AtomicReference<>(null);

        @Builder.Default
        private AtomicReference<String> message = new AtomicReference<>(null);

        private Instant startedAt;
        private Instant completedAt;
        private Instant lastUpdatedAt;

        public PipelineStepSnapshot toSnapshot() {
            return PipelineStepSnapshot.builder()
                    .stepId(stepId)
                    .displayName(displayName)
                    .stepType(stepType)
                    .status(status.get())
                    .progressPercent(progressPercent.get())
                    .totalItems(totalItems.get())
                    .completedItems(completedItems.get())
                    .failedItems(failedItems.get())
                    .activeTasks(activeTasks.get())
                    .totalBatches(totalBatches.get())
                    .completedBatches(completedBatches.get())
                    .currentBatchSize(currentBatchSize.get())
                    .currentItem(currentItem.get())
                    .message(message.get())
                    .startedAt(startedAt)
                    .completedAt(completedAt)
                    .lastUpdatedAt(lastUpdatedAt)
                    .elapsedMs(elapsedMs())
                    .build();
        }

        public long elapsedMs() {
            if (startedAt == null) return 0L;
            Instant end = completedAt != null ? completedAt : Instant.now();
            return end.toEpochMilli() - startedAt.toEpochMilli();
        }
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class PipelineStepSnapshot {
        private String stepId;
        private String displayName;
        private String stepType;
        private PipelineStepStatus status;
        private int progressPercent;
        private int totalItems;
        private int completedItems;
        private int failedItems;
        private int activeTasks;
        private int totalBatches;
        private int completedBatches;
        private int currentBatchSize;
        private String currentItem;
        private String message;
        private Instant startedAt;
        private Instant completedAt;
        private Instant lastUpdatedAt;
        private long elapsedMs;
    }

    /**
     * Returns elapsed milliseconds since the job started, or 0 if not yet started.
     */
    public long elapsedMs() {
        if (startedAt == null) return 0L;
        Instant end = completedAt != null ? completedAt : Instant.now();
        return end.toEpochMilli() - startedAt.toEpochMilli();
    }

    /**
     * Increment the entity type counter for the given type.
     */
    public void incrementEntityType(String entityType) {
        if (entityType != null && !entityType.isBlank()) {
            entityTypeCounts.computeIfAbsent(entityType, k -> new AtomicLong(0)).incrementAndGet();
        }
    }

    /**
     * Increment the relationship type counter for the given type.
     */
    public void incrementRelationshipType(String relationshipType) {
        if (relationshipType != null && !relationshipType.isBlank()) {
            relationshipTypeCounts.computeIfAbsent(relationshipType, k -> new AtomicLong(0)).incrementAndGet();
        }
    }

    /**
     * Record a newly discovered file or URL during crawling.
     * Maintains a bounded rolling list of the most recent 50 items.
     */
    public void recordDiscoveredItem(String name, String sourceType, String sourceLabel) {
        recentlyDiscoveredItems.add(DiscoveredItem.builder()
                .name(name)
                .sourceType(sourceType)
                .sourceLabel(sourceLabel)
                .discoveredAt(Instant.now())
                .build());
        // Trim to last 50
        while (recentlyDiscoveredItems.size() > 50) {
            recentlyDiscoveredItems.remove(0);
        }
    }

    /**
     * Snapshot the entity type counts as a plain Map for serialization.
     */
    public Map<String, Long> snapshotEntityTypeCounts() {
        Map<String, Long> snapshot = new java.util.LinkedHashMap<>();
        entityTypeCounts.forEach((k, v) -> snapshot.put(k, v.get()));
        return snapshot;
    }

    /**
     * Snapshot the relationship type counts as a plain Map for serialization.
     */
    public Map<String, Long> snapshotRelationshipTypeCounts() {
        Map<String, Long> snapshot = new java.util.LinkedHashMap<>();
        relationshipTypeCounts.forEach((k, v) -> snapshot.put(k, v.get()));
        return snapshot;
    }

    /**
     * Snapshot backend routing stats as a plain Map for serialization.
     */
    public Map<String, BackendRoutingStats> snapshotBackendStats() {
        Map<String, BackendRoutingStats> snapshot = new java.util.LinkedHashMap<>();
        backendStats.forEach((k, v) -> snapshot.put(k, BackendRoutingStats.builder()
                .backendId(v.getBackendId())
                .backendType(v.getBackendType())
                .requestsDispatched(v.getRequestsDispatched())
                .requestsCompleted(v.getRequestsCompleted())
                .requestsFailed(v.getRequestsFailed())
                .requestsRerouted(v.getRequestsRerouted())
                .inputTokens(v.getInputTokens())
                .outputTokens(v.getOutputTokens())
                .estimatedCostCentsX100(v.getEstimatedCostCentsX100())
                .emaLatencyMsX100(v.getEmaLatencyMsX100())
                .activeRequests(v.getActiveRequests())
                .maxConcurrent(v.getMaxConcurrent())
                .healthy(v.isHealthy())
                .unhealthyReason(v.getUnhealthyReason())
                .build()));
        return snapshot;
    }

    /**
     * Record a rerouting event. Maintains bounded list of last 20 events.
     */
    public void recordRerouteEvent(String fromBackend, String toBackend,
                                   String taskType, String reason, int itemCount) {
        recentRerouteEvents.add(RerouteEvent.builder()
                .timestamp(Instant.now())
                .fromBackend(fromBackend)
                .toBackend(toBackend)
                .taskType(taskType)
                .reason(reason)
                .itemCount(itemCount)
                .build());
        while (recentRerouteEvents.size() > 20) {
            recentRerouteEvents.remove(0);
        }
        reroutedItems.addAndGet(itemCount);
    }

    /**
     * Record a retry event. Maintains bounded list of last 20 events.
     */
    public void recordRetryEvent(RetryEvent event) {
        recentRetryEvents.add(event);
        while (recentRetryEvents.size() > 20) {
            recentRetryEvents.remove(0);
        }
        retriedBatches.incrementAndGet();
        retriedItems.addAndGet(event.getItemCount());
    }

    /**
     * Record an individual LLM call. Updates aggregate counters and maintains
     * a bounded rolling list of the last 50 calls for UI visibility.
     */
    public void recordLlmCall(LlmCallRecord record) {
        if (record == null) return;
        llmCallsTotal.incrementAndGet();
        if (record.isSuccess()) {
            llmCallsSucceeded.incrementAndGet();
        } else {
            llmCallsFailed.incrementAndGet();
        }
        if (record.isTimedOut()) {
            llmCallsTimedOut.incrementAndGet();
        }
        if (record.isRateLimited()) {
            llmCallsRateLimited.incrementAndGet();
        }
        if (record.isCircuitBroken()) {
            llmCallsCircuitBroken.incrementAndGet();
        }
        // Update EMA latency (alpha = 0.3)
        if (record.getLatencyMs() > 0) {
            long latX100 = record.getLatencyMs() * 100L;
            llmCallEmaLatencyMsX100.updateAndGet(prev ->
                    prev == 0 ? latX100 : (long) (0.3 * latX100 + 0.7 * prev));
            llmCallPeakLatencyMs.updateAndGet(prev ->
                    Math.max(prev, record.getLatencyMs()));
        }
        recentLlmCalls.add(record);
        while (recentLlmCalls.size() > 50) {
            recentLlmCalls.remove(0);
        }
    }

    /**
     * Build a progress snapshot suitable for serialization.
     */
    public ProgressSnapshot toProgressSnapshot() {
        List<PipelineStepSnapshot> stepSnapshots = new ArrayList<>();
        if (pipelineSteps != null) {
            for (PipelineStepProgress step : pipelineSteps) {
                if (step != null) {
                    stepSnapshots.add(step.toSnapshot());
                }
            }
        }

        List<DocumentProgress> documentSnapshots = new ArrayList<>();
        if (documentProgress != null && !documentProgress.isEmpty()) {
            documentSnapshots = documentProgress.values().stream()
                    .filter(dp -> dp != null)
                    .sorted(Comparator
                            .comparing((DocumentProgress dp) -> dp.getUpdatedAt() != null ? dp.getUpdatedAt() : Instant.EPOCH)
                            .reversed())
                    .toList();
        }

        return ProgressSnapshot.builder()
                .jobId(jobId)
                .name(request != null ? request.getName() : null)
                .status(status.get())
                .documentsDiscovered(documentsDiscovered.get())
                .documentsLoaded(documentsLoaded.get())
                .chunksProcessed(chunksProcessed.get())
                .chunksCreated(chunksCreated.get())
                .graphChunksProcessed(graphChunksProcessed.get())
                .graphChunksTotal(graphChunksTotal.get())
                .chunksQueuedForEmbedding(chunksQueuedForEmbedding.get())
                .chunksEmbedded(chunksEmbedded.get())
                .documentsIndexed(documentsIndexed.get())
                .entitiesExtracted(entitiesExtracted.get())
                .relationshipsExtracted(relationshipsExtracted.get())
                .documentsPreprocessed(documentsPreprocessed.get())
                .documentsTranslated(documentsTranslated.get())
                .graphExtractionRetries(graphExtractionRetries.get())
                .graphExtractionParseFailures(graphExtractionParseFailures.get())
                .errorCount(errorCount.get())
                .currentFile(currentFile.get())
                .currentPhase(currentPhase.get())
                .progressPercent(progressPercent.get())
                .queuePosition(queuePosition.get())
                .activeJobs(activeJobs.get())
                .queuedJobs(queuedJobs.get())
                .maxConcurrentJobs(maxConcurrentJobs.get())
                .queueCapacity(queueCapacity.get())
                .queuedAt(queuedAt)
                .memoryUsagePercent(memoryUsagePercent.get())
                .peakMemoryUsagePercent(peakMemoryUsagePercent.get())
                .heapUsedBytes(heapUsedBytes.get())
                .heapMaxBytes(heapMaxBytes.get())
                .nativeMemoryUsagePercent(nativeMemoryUsagePercent.get())
                .peakNativeMemoryUsagePercent(peakNativeMemoryUsagePercent.get())
                .nativePhysicalBytes(nativePhysicalBytes.get())
                .peakNativePhysicalBytes(peakNativePhysicalBytes.get())
                .nativeTotalBytes(nativeTotalBytes.get())
                .nativeMaxPhysicalBytes(nativeMaxPhysicalBytes.get())
                .directBufferBytes(directBufferBytes.get())
                .vectorBatchesTotal(vectorBatchesTotal.get())
                .vectorBatchesCompleted(vectorBatchesCompleted.get())
                .currentBatchSize(currentBatchSize.get())
                .embeddingBatchSize(embeddingBatchSize.get())
                .embeddingModelOptimalBatchSize(embeddingModelOptimalBatchSize.get())
                .embeddingModelMaxBatchSize(embeddingModelMaxBatchSize.get())
                .embeddingSingleDspPlan(embeddingSingleDspPlan.get())
                .embeddingDspPlanBatchSize(embeddingDspPlanBatchSize.get())
                .currentBatchStep(currentBatchStep.get())
                // Work-stealing stats
                .workStealCount(workStealCount.get())
                .workStealFailures(workStealFailures.get())
                .localDispatchCount(localDispatchCount.get())
                .workImbalanceRatioX100(workImbalanceRatioX100.get())
                // Dynamic batch sizing
                .adaptiveBatchSize(adaptiveBatchSize.get())
                .batchSizeAdjustments(batchSizeAdjustments.get())
                .lastBatchAdjustDirection(lastBatchAdjustDirection.get())
                .lastBatchAdjustReason(lastBatchAdjustReason.get())
                .batchEmaLatencyMsX100(batchEmaLatencyMsX100.get())
                .peakThroughputX100(peakThroughputX100.get())
                // Token budget
                .totalInputTokens(totalInputTokens.get())
                .totalOutputTokens(totalOutputTokens.get())
                .estimatedCostCentsX100(estimatedCostCentsX100.get())
                .backendStats(backendStats.isEmpty() ? null : snapshotBackendStats())
                // Workload rerouting
                .reroutedItems(reroutedItems.get())
                .droppedItems(droppedItems.get())
                .recentRerouteEvents(recentRerouteEvents.isEmpty() ? null : new ArrayList<>(recentRerouteEvents))
                // Retry / fallback
                .retriedBatches(retriedBatches.get())
                .retriedItems(retriedItems.get())
                .deadLetterCount(deadLetterCount.get())
                .backendsCoolingDown(backendsCoolingDown.get())
                .recentRetryEvents(recentRetryEvents.isEmpty() ? null : new ArrayList<>(recentRetryEvents))
                // LLM call observability
                .llmCallsTotal(llmCallsTotal.get())
                .llmCallsSucceeded(llmCallsSucceeded.get())
                .llmCallsFailed(llmCallsFailed.get())
                .llmCallsTimedOut(llmCallsTimedOut.get())
                .llmCallsRateLimited(llmCallsRateLimited.get())
                .llmCallsCircuitBroken(llmCallsCircuitBroken.get())
                .llmCallEmaLatencyMsX100(llmCallEmaLatencyMsX100.get())
                .llmCallPeakLatencyMs(llmCallPeakLatencyMs.get())
                .recentLlmCalls(recentLlmCalls.isEmpty() ? null : new ArrayList<>(recentLlmCalls))
                .errors(errors.isEmpty() ? null : new ArrayList<>(errors))
                .recentEvents(recentEvents.isEmpty() ? null : new ArrayList<>(recentEvents))
                .pipelineSteps(stepSnapshots.isEmpty() ? null : stepSnapshots)
                .documentProgress(documentSnapshots.isEmpty() ? null : documentSnapshots)
                .elapsedMs(elapsedMs())
                .sourceProgress(sourceProgress)
                .recentlyDiscoveredItems(recentlyDiscoveredItems.isEmpty() ? null : new ArrayList<>(recentlyDiscoveredItems))
                .entityTypeCounts(entityTypeCounts.isEmpty() ? null : snapshotEntityTypeCounts())
                .relationshipTypeCounts(relationshipTypeCounts.isEmpty() ? null : snapshotRelationshipTypeCounts())
                .createdAt(createdAt)
                .startedAt(startedAt)
                .completedAt(completedAt)
                .errorMessage(errorMessage)
                .build();
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ProgressSnapshot {
        private String jobId;
        private String name;
        private Status status;
        private int documentsDiscovered;
        private int documentsLoaded;
        private int chunksProcessed;
        private int chunksCreated;
        private int graphChunksProcessed;
        private int graphChunksTotal;
        private int chunksQueuedForEmbedding;
        private int chunksEmbedded;
        private int documentsIndexed;
        private int entitiesExtracted;
        private int relationshipsExtracted;
        private int documentsPreprocessed;
        private int documentsTranslated;
        private int graphExtractionRetries;
        private int graphExtractionParseFailures;
        private int errorCount;
        private String currentFile;
        private String currentPhase;
        private int progressPercent;
        private int queuePosition;
        private int activeJobs;
        private int queuedJobs;
        private int maxConcurrentJobs;
        private int queueCapacity;
        private Instant queuedAt;
        private int memoryUsagePercent;
        private int peakMemoryUsagePercent;
        private long heapUsedBytes;
        private long heapMaxBytes;
        private int nativeMemoryUsagePercent;
        private int peakNativeMemoryUsagePercent;
        private long nativePhysicalBytes;
        private long peakNativePhysicalBytes;
        private long nativeTotalBytes;
        private long nativeMaxPhysicalBytes;
        private long directBufferBytes;
        private int vectorBatchesTotal;
        private int vectorBatchesCompleted;
        private int currentBatchSize;
        private int embeddingBatchSize;
        private int embeddingModelOptimalBatchSize;
        private int embeddingModelMaxBatchSize;
        private boolean embeddingSingleDspPlan;
        private int embeddingDspPlanBatchSize;
        private String currentBatchStep;
        private List<String> errors;
        private List<StageEvent> recentEvents;
        private List<PipelineStepSnapshot> pipelineSteps;
        private List<DocumentProgress> documentProgress;
        private long elapsedMs;
        private List<SourceProgress> sourceProgress;
        private List<DiscoveredItem> recentlyDiscoveredItems;
        private Map<String, Long> entityTypeCounts;
        private Map<String, Long> relationshipTypeCounts;
        private Instant createdAt;
        private Instant startedAt;
        private Instant completedAt;
        private String errorMessage;
        // Work-stealing stats
        private long workStealCount;
        private long workStealFailures;
        private long localDispatchCount;
        private long workImbalanceRatioX100;
        // Dynamic batch sizing
        private int adaptiveBatchSize;
        private int batchSizeAdjustments;
        private String lastBatchAdjustDirection;
        private String lastBatchAdjustReason;
        private long batchEmaLatencyMsX100;
        private long peakThroughputX100;
        // Token budget
        private long totalInputTokens;
        private long totalOutputTokens;
        private long estimatedCostCentsX100;
        private Map<String, BackendRoutingStats> backendStats;
        // Workload rerouting
        private long reroutedItems;
        private long droppedItems;
        private List<RerouteEvent> recentRerouteEvents;
        // Retry / fallback
        private long retriedBatches;
        private long retriedItems;
        private int deadLetterCount;
        private int backendsCoolingDown;
        private List<RetryEvent> recentRetryEvents;
        // LLM call observability
        private long llmCallsTotal;
        private long llmCallsSucceeded;
        private long llmCallsFailed;
        private long llmCallsTimedOut;
        private long llmCallsRateLimited;
        private long llmCallsCircuitBroken;
        private long llmCallEmaLatencyMsX100;
        private long llmCallPeakLatencyMs;
        private List<LlmCallRecord> recentLlmCalls;
    }
}
