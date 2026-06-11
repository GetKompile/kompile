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
        PENDING, RUNNING, PAUSED, COMPLETED, COMPLETED_PENDING_EMBEDDING, FAILED, CANCELLED
    }

    public enum PipelineStepStatus {
        PENDING, RUNNING, BACKPRESSURE, COMPLETED, FAILED, SKIPPED, DEFERRED, CANCELLED
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
    private List<SourceProgress> sourceProgress = new ArrayList<>();

    /** The assembled graph result (populated on completion) */
    private Graph resultGraph;

    /** Error message if the job failed */
    private String errorMessage;

    /** Chunks awaiting deferred embedding (populated when embedding model was not ready at job completion) */
    @Builder.Default
    private List<Document> deferredEmbeddingChunks = new CopyOnWriteArrayList<>();

    /** Vector index config for deferred embedding (preserved from original request) */
    private VectorIndexConfig deferredVectorIndexConfig;

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
    }
}
