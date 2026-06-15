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

package ai.kompile.app.services;

import ai.kompile.app.ingest.domain.IndexedDocument;
import ai.kompile.app.ingest.domain.IndexedPassage;
import ai.kompile.app.services.CrossIndexTrackingService.CrossIndexResolutionResult;
import ai.kompile.core.embeddings.EmbeddingModel;
import ai.kompile.core.embeddings.VectorStore;
import ai.kompile.knowledgegraph.service.KnowledgeGraphService;
import lombok.extern.slf4j.Slf4j;
import org.nd4j.linalg.api.ndarray.INDArray;
import org.springframework.ai.document.Document;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Service for synchronizing documents/passages across indexes.
 * Supports auto-sync on query and manual sync triggers.
 */
@Slf4j
@Service
public class IndexSyncService {

    /** No-arg constructor for CGLIB proxy instantiation in GraalVM native image. */
    protected IndexSyncService() {}


    @Autowired
    private CrossIndexTrackingService trackingService;
    @Autowired
    private ApplicationEventPublisher eventPublisher;
    @Autowired(required = false)
    private EmbeddingModel embeddingModel;
    @Autowired(required = false)
    private VectorStore vectorStore;
    @Autowired(required = false)
    private KnowledgeGraphService knowledgeGraphService;

    // Self-reference through proxy to ensure @Async is honored on self-calls.
    // Uses ApplicationContext lookup instead of @Lazy self-injection to avoid
    // CGLIB proxy callback mismatch in GraalVM native image.
    @Autowired
    private ApplicationContext applicationContext;

    @Autowired
    public IndexSyncService(CrossIndexTrackingService trackingService,
                            ApplicationEventPublisher eventPublisher,
                            @Autowired(required = false) EmbeddingModel embeddingModel,
                            @Autowired(required = false) VectorStore vectorStore,
                            @Autowired(required = false) KnowledgeGraphService knowledgeGraphService) {
        this.trackingService = trackingService;
        this.eventPublisher = eventPublisher;
        this.embeddingModel = embeddingModel;
        this.vectorStore = vectorStore;
        this.knowledgeGraphService = knowledgeGraphService;
    }

    // Active sync jobs
    private final ConcurrentMap<String, SyncJob> activeJobs = new ConcurrentHashMap<>();

    // Auto-sync configuration per fact sheet
    private final ConcurrentMap<Long, AutoSyncConfig> autoSyncConfigs = new ConcurrentHashMap<>();

    // Default configuration
    private static final AutoSyncConfig DEFAULT_CONFIG = new AutoSyncConfig(
            true,           // enabled
            100,            // maxPassagesPerSync
            Duration.ofSeconds(30), // syncTimeout
            true,           // syncOnSearch
            true            // syncOnIngest
    );

    // ═══════════════════════════════════════════════════════════════════════════
    // QUERY-TIME AUTO-SYNC
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * Called during similarity search to check and sync missing entries.
     * Returns immediately if all entries are present; triggers async sync otherwise.
     */
    public SyncCheckResult checkAndTriggerSync(List<String> queryResultChunkIds, Long factSheetId) {
        if (queryResultChunkIds == null || queryResultChunkIds.isEmpty()) {
            return new SyncCheckResult(true, 0, null, false);
        }

        AutoSyncConfig config = getAutoSyncConfig(factSheetId);
        if (!config.enabled() || !config.syncOnSearch()) {
            return new SyncCheckResult(true, 0, null, false);
        }

        // Check cross-index status
        CrossIndexResolutionResult resolution = trackingService.resolveQueryResults(
                queryResultChunkIds, factSheetId);

        if (!resolution.needsSync()) {
            return new SyncCheckResult(true, 0, null, false);
        }

        int missingCount = resolution.missingFromVectorChunkIds().size() +
                resolution.missingFromGraphChunkIds().size();

        // Trigger async sync if missing entries found
        String jobId = UUID.randomUUID().toString();
        applicationContext.getBean(IndexSyncService.class).triggerAsyncSync(jobId, factSheetId, resolution, config);

        log.info("Auto-sync triggered for {} missing passages in fact sheet {}",
                missingCount, factSheetId);

        return new SyncCheckResult(false, missingCount, jobId, true);
    }

    /**
     * Trigger async sync for missing entries.
     */
    @Async
    public void triggerAsyncSync(String jobId, Long factSheetId,
                                     CrossIndexResolutionResult resolution,
                                     AutoSyncConfig config) {
        SyncJob job = new SyncJob(jobId, factSheetId, SyncTarget.ALL);
        activeJobs.put(jobId, job);

        try {
            job.setStatus(SyncStatus.RUNNING);
            job.setTotalPassages(resolution.missingFromVectorChunkIds().size() +
                    resolution.missingFromGraphChunkIds().size());

            // Sync to vector store
            if (!resolution.missingFromVectorChunkIds().isEmpty()) {
                syncPassagesToVector(job, resolution.missingFromVectorChunkIds(), config);
            }

            // Sync to knowledge graph
            if (!resolution.missingFromGraphChunkIds().isEmpty()) {
                syncPassagesToGraph(job, resolution.missingFromGraphChunkIds(), config);
            }

            job.setStatus(SyncStatus.COMPLETED);
            job.setEndTime(Instant.now());

        } catch (Exception e) {
            log.error("Auto-sync failed for job {}: {}", jobId, e.getMessage(), e);
            job.setStatus(SyncStatus.FAILED);
            job.getErrors().add(e.getMessage());
            job.setEndTime(Instant.now());
        }

        // Keep job in map for status queries (clean up after 1 hour)
        scheduleJobCleanup(jobId, Duration.ofHours(1));
    }

    /**
     * Synchronously wait for auto-sync to complete (with timeout).
     */
    public SyncResult awaitSync(String syncJobId, Duration timeout) {
        SyncJob job = activeJobs.get(syncJobId);
        if (job == null) {
            return new SyncResult(syncJobId, SyncStatus.FAILED, 0, 0, 1,
                    List.of("Job not found: " + syncJobId), Duration.ZERO);
        }

        long startWait = System.currentTimeMillis();
        long timeoutMs = timeout.toMillis();

        while (job.getStatus() == SyncStatus.RUNNING || job.getStatus() == SyncStatus.PENDING) {
            if (System.currentTimeMillis() - startWait > timeoutMs) {
                return new SyncResult(syncJobId, SyncStatus.PARTIAL,
                        job.getDocumentsProcessed().get(),
                        job.getPassagesProcessed().get(),
                        job.getErrorCount().get(),
                        new ArrayList<>(job.getErrors()),
                        Duration.ofMillis(System.currentTimeMillis() - startWait));
            }

            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }

        return new SyncResult(
                syncJobId,
                job.getStatus(),
                job.getDocumentsProcessed().get(),
                job.getPassagesProcessed().get(),
                job.getErrorCount().get(),
                new ArrayList<>(job.getErrors()),
                job.getDuration()
        );
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // MANUAL SYNC TRIGGERS
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * Sync all documents missing from vector store.
     */
    public CompletableFuture<SyncResult> syncToVectorStore(Long factSheetId) {
        String jobId = UUID.randomUUID().toString();
        return CompletableFuture.supplyAsync(() -> {
            SyncJob job = new SyncJob(jobId, factSheetId, SyncTarget.VECTOR_STORE);
            activeJobs.put(jobId, job);

            try {
                job.setStatus(SyncStatus.RUNNING);

                List<IndexedDocument> docsNeedingSync = trackingService.findDocumentsNeedingSync(factSheetId);
                job.setTotalDocuments(docsNeedingSync.size());

                for (IndexedDocument doc : docsNeedingSync) {
                    if (job.isCancelled()) {
                        job.setStatus(SyncStatus.CANCELLED);
                        break;
                    }

                    if (doc.needsVectorIndexing()) {
                        try {
                            indexDocumentPassagesToVector(doc, job);
                        } catch (Exception e) {
                            log.warn("Failed to vector-index document {}", doc.getId(), e);
                            job.getErrorCount().incrementAndGet();
                            job.getErrors().add("Doc " + doc.getId() + ": " + e.getMessage());
                        }
                        job.getDocumentsProcessed().incrementAndGet();
                    }
                }

                if (!job.isCancelled()) {
                    job.setStatus(SyncStatus.COMPLETED);
                }

            } catch (Exception e) {
                log.error("Vector sync failed: {}", e.getMessage(), e);
                job.setStatus(SyncStatus.FAILED);
                job.getErrors().add(e.getMessage());
            }

            job.setEndTime(Instant.now());
            scheduleJobCleanup(jobId, Duration.ofHours(1));

            return new SyncResult(
                    jobId,
                    job.getStatus(),
                    job.getDocumentsProcessed().get(),
                    job.getPassagesProcessed().get(),
                    job.getErrorCount().get(),
                    new ArrayList<>(job.getErrors()),
                    job.getDuration()
            );
        });
    }

    /**
     * Sync all documents missing from knowledge graph.
     */
    public CompletableFuture<SyncResult> syncToKnowledgeGraph(Long factSheetId) {
        String jobId = UUID.randomUUID().toString();
        return CompletableFuture.supplyAsync(() -> {
            SyncJob job = new SyncJob(jobId, factSheetId, SyncTarget.KNOWLEDGE_GRAPH);
            activeJobs.put(jobId, job);

            try {
                job.setStatus(SyncStatus.RUNNING);

                List<IndexedPassage> passagesNeedingSync =
                        trackingService.findPassagesMissingFromGraph(factSheetId);
                job.setTotalPassages(passagesNeedingSync.size());

                for (IndexedPassage passage : passagesNeedingSync) {
                    if (job.isCancelled()) {
                        job.setStatus(SyncStatus.CANCELLED);
                        break;
                    }

                    try {
                        indexPassageToGraph(passage);
                    } catch (Exception e) {
                        log.warn("Failed to graph-index passage {}", passage.getChunkId(), e);
                        job.getErrorCount().incrementAndGet();
                        job.getErrors().add("Passage " + passage.getChunkId() + ": " + e.getMessage());
                    }
                    job.getPassagesProcessed().incrementAndGet();
                }

                if (!job.isCancelled()) {
                    job.setStatus(SyncStatus.COMPLETED);
                }

            } catch (Exception e) {
                log.error("Graph sync failed: {}", e.getMessage(), e);
                job.setStatus(SyncStatus.FAILED);
                job.getErrors().add(e.getMessage());
            }

            job.setEndTime(Instant.now());
            scheduleJobCleanup(jobId, Duration.ofHours(1));

            return new SyncResult(
                    jobId,
                    job.getStatus(),
                    job.getDocumentsProcessed().get(),
                    job.getPassagesProcessed().get(),
                    job.getErrorCount().get(),
                    new ArrayList<>(job.getErrors()),
                    job.getDuration()
            );
        });
    }

    /**
     * Sync all documents to all indexes.
     */
    public CompletableFuture<SyncResult> syncAll(Long factSheetId) {
        String jobId = UUID.randomUUID().toString();
        return CompletableFuture.supplyAsync(() -> {
            SyncJob job = new SyncJob(jobId, factSheetId, SyncTarget.ALL);
            activeJobs.put(jobId, job);

            try {
                job.setStatus(SyncStatus.RUNNING);

                List<IndexedDocument> docsNeedingSync = trackingService.findDocumentsNeedingSync(factSheetId);
                job.setTotalDocuments(docsNeedingSync.size());

                for (IndexedDocument doc : docsNeedingSync) {
                    if (job.isCancelled()) {
                        job.setStatus(SyncStatus.CANCELLED);
                        break;
                    }

                    // Sync to each index as needed
                    try {
                        if (doc.needsVectorIndexing()) {
                            indexDocumentPassagesToVector(doc, job);
                        }
                        if (doc.needsGraphIndexing()) {
                            indexDocumentPassagesToGraph(doc, job);
                        }
                    } catch (Exception e) {
                        log.warn("Failed to sync document {}", doc.getId(), e);
                        job.getErrorCount().incrementAndGet();
                        job.getErrors().add("Doc " + doc.getId() + ": " + e.getMessage());
                    }

                    job.getDocumentsProcessed().incrementAndGet();
                }

                if (!job.isCancelled()) {
                    job.setStatus(SyncStatus.COMPLETED);
                }

            } catch (Exception e) {
                log.error("Full sync failed: {}", e.getMessage(), e);
                job.setStatus(SyncStatus.FAILED);
                job.getErrors().add(e.getMessage());
            }

            job.setEndTime(Instant.now());
            scheduleJobCleanup(jobId, Duration.ofHours(1));

            return new SyncResult(
                    jobId,
                    job.getStatus(),
                    job.getDocumentsProcessed().get(),
                    job.getPassagesProcessed().get(),
                    job.getErrorCount().get(),
                    new ArrayList<>(job.getErrors()),
                    job.getDuration()
            );
        });
    }

    /**
     * Sync specific documents by ID.
     */
    public CompletableFuture<SyncResult> syncDocuments(List<Long> documentIds, SyncTarget... targets) {
        String jobId = UUID.randomUUID().toString();
        Set<SyncTarget> targetSet = targets.length > 0 ?
                EnumSet.copyOf(Arrays.asList(targets)) : EnumSet.allOf(SyncTarget.class);

        return CompletableFuture.supplyAsync(() -> {
            SyncJob job = new SyncJob(jobId, null, SyncTarget.ALL);
            activeJobs.put(jobId, job);

            try {
                job.setStatus(SyncStatus.RUNNING);
                job.setTotalDocuments(documentIds.size());

                for (Long docId : documentIds) {
                    if (job.isCancelled()) {
                        job.setStatus(SyncStatus.CANCELLED);
                        break;
                    }

                    trackingService.findDocument(docId).ifPresent(doc -> {
                        try {
                            if (targetSet.contains(SyncTarget.VECTOR_STORE) && doc.needsVectorIndexing()) {
                                indexDocumentPassagesToVector(doc, job);
                            }
                            if (targetSet.contains(SyncTarget.KNOWLEDGE_GRAPH) && doc.needsGraphIndexing()) {
                                indexDocumentPassagesToGraph(doc, job);
                            }
                        } catch (Exception e) {
                            log.warn("Failed to sync document {}", docId, e);
                            job.getErrorCount().incrementAndGet();
                            job.getErrors().add("Doc " + docId + ": " + e.getMessage());
                        }
                        job.getDocumentsProcessed().incrementAndGet();
                    });
                }

                if (!job.isCancelled()) {
                    job.setStatus(SyncStatus.COMPLETED);
                }

            } catch (Exception e) {
                log.error("Document sync failed: {}", e.getMessage(), e);
                job.setStatus(SyncStatus.FAILED);
                job.getErrors().add(e.getMessage());
            }

            job.setEndTime(Instant.now());
            scheduleJobCleanup(jobId, Duration.ofHours(1));

            return new SyncResult(
                    jobId,
                    job.getStatus(),
                    job.getDocumentsProcessed().get(),
                    job.getPassagesProcessed().get(),
                    job.getErrorCount().get(),
                    new ArrayList<>(job.getErrors()),
                    job.getDuration()
            );
        });
    }

    /**
     * Sync specific passages by chunk ID.
     */
    public CompletableFuture<SyncResult> syncPassages(List<String> chunkIds, SyncTarget... targets) {
        String jobId = UUID.randomUUID().toString();
        Set<SyncTarget> targetSet = targets.length > 0 ?
                EnumSet.copyOf(Arrays.asList(targets)) : EnumSet.allOf(SyncTarget.class);

        return CompletableFuture.supplyAsync(() -> {
            SyncJob job = new SyncJob(jobId, null, SyncTarget.ALL);
            activeJobs.put(jobId, job);

            try {
                job.setStatus(SyncStatus.RUNNING);
                job.setTotalPassages(chunkIds.size());

                // Batch-load all passages upfront to avoid N+1 queries
                Map<String, ai.kompile.app.ingest.domain.IndexedPassage> passageMap = new HashMap<>();
                List<String> chunkIdList = new ArrayList<>(chunkIds);
                int batchSize = 500;
                for (int i = 0; i < chunkIdList.size(); i += batchSize) {
                    List<String> batch = chunkIdList.subList(i, Math.min(i + batchSize, chunkIdList.size()));
                    for (ai.kompile.app.ingest.domain.IndexedPassage p : trackingService.findPassagesByChunkIds(batch)) {
                        passageMap.put(p.getChunkId(), p);
                    }
                }

                for (String chunkId : chunkIds) {
                    if (job.isCancelled()) {
                        job.setStatus(SyncStatus.CANCELLED);
                        break;
                    }

                    ai.kompile.app.ingest.domain.IndexedPassage passage = passageMap.get(chunkId);
                    if (passage != null) {
                        try {
                            if (targetSet.contains(SyncTarget.VECTOR_STORE) && passage.needsVectorIndexing()) {
                                indexPassageToVector(passage);
                            }
                            if (targetSet.contains(SyncTarget.KNOWLEDGE_GRAPH) && passage.needsGraphIndexing()) {
                                indexPassageToGraph(passage);
                            }
                        } catch (Exception e) {
                            log.warn("Failed to sync passage {}", chunkId, e);
                            job.getErrorCount().incrementAndGet();
                            job.getErrors().add("Passage " + chunkId + ": " + e.getMessage());
                        }
                        job.getPassagesProcessed().incrementAndGet();
                    }
                }

                if (!job.isCancelled()) {
                    job.setStatus(SyncStatus.COMPLETED);
                }

            } catch (Exception e) {
                log.error("Passage sync failed: {}", e.getMessage(), e);
                job.setStatus(SyncStatus.FAILED);
                job.getErrors().add(e.getMessage());
            }

            job.setEndTime(Instant.now());
            scheduleJobCleanup(jobId, Duration.ofHours(1));

            return new SyncResult(
                    jobId,
                    job.getStatus(),
                    job.getDocumentsProcessed().get(),
                    job.getPassagesProcessed().get(),
                    job.getErrorCount().get(),
                    new ArrayList<>(job.getErrors()),
                    job.getDuration()
            );
        });
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // JOB MANAGEMENT
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * Get status of a sync job.
     */
    public Optional<SyncJobStatus> getJobStatus(String jobId) {
        SyncJob job = activeJobs.get(jobId);
        if (job == null) {
            return Optional.empty();
        }

        return Optional.of(new SyncJobStatus(
                job.getJobId(),
                job.getStatus(),
                job.getDocumentsProcessed().get(),
                job.getPassagesProcessed().get(),
                job.getTotalDocuments(),
                job.getTotalPassages(),
                job.getProgressPercent(),
                new ArrayList<>(job.getErrors()),
                job.getStartTime(),
                job.getEndTime()
        ));
    }

    /**
     * Cancel a running sync job.
     */
    public boolean cancelJob(String jobId) {
        SyncJob job = activeJobs.get(jobId);
        if (job == null) {
            return false;
        }

        job.setCancelled(true);
        job.setStatus(SyncStatus.CANCELLED);
        job.setEndTime(Instant.now());
        log.info("Cancelled sync job {}", jobId);
        return true;
    }

    /**
     * Get all active sync jobs.
     */
    public List<SyncJobStatus> getActiveJobs() {
        return activeJobs.values().stream()
                .filter(job -> job.getStatus() == SyncStatus.RUNNING ||
                        job.getStatus() == SyncStatus.PENDING)
                .map(job -> new SyncJobStatus(
                        job.getJobId(),
                        job.getStatus(),
                        job.getDocumentsProcessed().get(),
                        job.getPassagesProcessed().get(),
                        job.getTotalDocuments(),
                        job.getTotalPassages(),
                        job.getProgressPercent(),
                        new ArrayList<>(job.getErrors()),
                        job.getStartTime(),
                        job.getEndTime()
                ))
                .toList();
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // CONFIGURATION
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * Enable/disable auto-sync on query.
     */
    public void setAutoSyncEnabled(Long factSheetId, boolean enabled) {
        AutoSyncConfig current = getAutoSyncConfig(factSheetId);
        autoSyncConfigs.put(factSheetId, new AutoSyncConfig(
                enabled,
                current.maxPassagesPerSync(),
                current.syncTimeout(),
                current.syncOnSearch(),
                current.syncOnIngest()
        ));
    }

    /**
     * Get current auto-sync configuration.
     */
    public AutoSyncConfig getAutoSyncConfig(Long factSheetId) {
        return autoSyncConfigs.getOrDefault(factSheetId, DEFAULT_CONFIG);
    }

    /**
     * Update auto-sync configuration.
     */
    public void updateAutoSyncConfig(Long factSheetId, AutoSyncConfig config) {
        autoSyncConfigs.put(factSheetId, config);
    }

    /**
     * Check if auto-sync is enabled for a fact sheet.
     */
    public boolean isAutoSyncEnabled(Long factSheetId) {
        return getAutoSyncConfig(factSheetId).enabled();
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // HELPER METHODS
    // ═══════════════════════════════════════════════════════════════════════════

    private void syncPassagesToVector(SyncJob job, List<String> chunkIds, AutoSyncConfig config) {
        int limit = Math.min(chunkIds.size(), config.maxPassagesPerSync());
        List<String> toSync = chunkIds.subList(0, limit);

        for (String chunkId : toSync) {
            if (job.isCancelled()) {
                break;
            }

            try {
                trackingService.findPassage(chunkId).ifPresent(passage -> {
                    try {
                        indexPassageToVector(passage);
                    } catch (Exception e) {
                        log.warn("Failed to vector-index passage {}", chunkId, e);
                        job.getErrorCount().incrementAndGet();
                        job.getErrors().add("Passage " + chunkId + ": " + e.getMessage());
                    }
                });
            } catch (Exception e) {
                log.warn("Failed to find passage {}", chunkId, e);
                job.getErrorCount().incrementAndGet();
            }

            job.getPassagesProcessed().incrementAndGet();
        }

        if (chunkIds.size() > limit) {
            log.warn("Auto-sync limited to {} passages (config limit), {} remaining",
                    limit, chunkIds.size() - limit);
        }
    }

    private void syncPassagesToGraph(SyncJob job, List<String> chunkIds, AutoSyncConfig config) {
        int limit = Math.min(chunkIds.size(), config.maxPassagesPerSync());
        List<String> toSync = chunkIds.subList(0, limit);

        for (String chunkId : toSync) {
            if (job.isCancelled()) {
                break;
            }

            try {
                trackingService.findPassage(chunkId).ifPresent(passage -> {
                    try {
                        indexPassageToGraph(passage);
                    } catch (Exception e) {
                        log.warn("Failed to graph-index passage {}", chunkId, e);
                        job.getErrorCount().incrementAndGet();
                        job.getErrors().add("Passage " + chunkId + ": " + e.getMessage());
                    }
                });
            } catch (Exception e) {
                log.warn("Failed to find passage {}", chunkId, e);
                job.getErrorCount().incrementAndGet();
            }

            job.getPassagesProcessed().incrementAndGet();
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // INDEXING INTEGRATION
    // ═══════════════════════════════════════════════════════════════════════════

    private void indexPassageToVector(IndexedPassage passage) {
        if (embeddingModel == null || vectorStore == null) {
            log.debug("Vector indexing unavailable: embeddingModel={}, vectorStore={}",
                    embeddingModel != null, vectorStore != null);
            return;
        }

        String content = passage.getFullContent();
        if (content == null || content.isBlank()) {
            content = passage.getContentPreview();
        }
        if (content == null || content.isBlank()) {
            log.debug("Skipping passage {} with no content", passage.getChunkId());
            return;
        }

        INDArray embedding = embeddingModel.embed(content);
        Document doc = new Document(passage.getChunkId(), content,
                Map.of("chunkId", passage.getChunkId(),
                        "chunkIndex", passage.getChunkIndex() != null ? passage.getChunkIndex() : 0));
        vectorStore.addWithEmbeddings(List.of(doc), embedding);
        trackingService.markPassageVectorIndexed(passage.getChunkId(), passage.getChunkId());
    }

    private void indexPassageToGraph(IndexedPassage passage) {
        if (knowledgeGraphService == null) {
            log.debug("Graph indexing unavailable: knowledgeGraphService is null");
            return;
        }

        String content = passage.getFullContent();
        if (content == null || content.isBlank()) {
            content = passage.getContentPreview();
        }
        if (content == null || content.isBlank()) {
            log.debug("Skipping passage {} with no content for graph indexing", passage.getChunkId());
            return;
        }

        var graphNode = knowledgeGraphService.createSnippetNode(
                null, // parent document node - let service resolve
                passage.getChunkId(),
                content,
                passage.getChunkIndex() != null ? passage.getChunkIndex() : 0
        );
        if (graphNode != null) {
            trackingService.markPassageGraphIndexed(passage.getChunkId(), String.valueOf(graphNode.getId()));
        }
    }

    private void indexDocumentPassagesToVector(IndexedDocument doc, SyncJob job) {
        if (embeddingModel == null || vectorStore == null) {
            log.debug("Vector indexing unavailable for document {}", doc.getId());
            return;
        }

        List<IndexedPassage> passages = doc.getPassages();
        if (passages == null || passages.isEmpty()) {
            return;
        }

        for (IndexedPassage passage : passages) {
            if (job.isCancelled()) break;
            if (!passage.needsVectorIndexing()) continue;

            try {
                indexPassageToVector(passage);
                job.getPassagesProcessed().incrementAndGet();
            } catch (Exception e) {
                log.warn("Failed to vector-index passage {} of doc {}: {}",
                        passage.getChunkId(), doc.getId(), e.getMessage());
                job.getErrorCount().incrementAndGet();
            }
        }
    }

    private void indexDocumentPassagesToGraph(IndexedDocument doc, SyncJob job) {
        if (knowledgeGraphService == null) {
            log.debug("Graph indexing unavailable for document {}", doc.getId());
            return;
        }

        List<IndexedPassage> passages = doc.getPassages();
        if (passages == null || passages.isEmpty()) {
            return;
        }

        for (IndexedPassage passage : passages) {
            if (job.isCancelled()) break;
            if (!passage.needsGraphIndexing()) continue;

            try {
                indexPassageToGraph(passage);
                job.getPassagesProcessed().incrementAndGet();
            } catch (Exception e) {
                log.warn("Failed to graph-index passage {} of doc {}: {}",
                        passage.getChunkId(), doc.getId(), e.getMessage());
                job.getErrorCount().incrementAndGet();
            }
        }
    }

    private void scheduleJobCleanup(String jobId, Duration delay) {
        // Simple cleanup - in production, use a proper scheduler
        CompletableFuture.delayedExecutor(delay.toMillis(), TimeUnit.MILLISECONDS)
                .execute(() -> activeJobs.remove(jobId));
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // ENUMS AND RECORDS
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * Sync target specification.
     */
    public enum SyncTarget {
        KEYWORD_INDEX,
        VECTOR_STORE,
        KNOWLEDGE_GRAPH,
        ALL
    }

    /**
     * Sync job status.
     */
    public enum SyncStatus {
        PENDING,
        RUNNING,
        COMPLETED,
        PARTIAL,
        FAILED,
        CANCELLED
    }

    /**
     * Result of sync check operation.
     */
    public record SyncCheckResult(
            boolean allPresent,
            int missingCount,
            String syncJobId,
            boolean syncTriggered
    ) {}

    /**
     * Result of a completed sync operation.
     */
    public record SyncResult(
            String jobId,
            SyncStatus status,
            int documentsProcessed,
            int passagesProcessed,
            int errorCount,
            List<String> errors,
            Duration duration
    ) {}

    /**
     * Auto-sync configuration.
     */
    public record AutoSyncConfig(
            boolean enabled,
            int maxPassagesPerSync,
            Duration syncTimeout,
            boolean syncOnSearch,
            boolean syncOnIngest
    ) {}

    /**
     * Sync job status for API responses.
     */
    public record SyncJobStatus(
            String jobId,
            SyncStatus status,
            int documentsProcessed,
            int passagesProcessed,
            int totalDocuments,
            int totalPassages,
            int progressPercent,
            List<String> errors,
            Instant startTime,
            Instant endTime
    ) {}

    /**
     * Internal sync job tracking.
     */
    private static class SyncJob {
        private String jobId;
        private Long factSheetId;
        private SyncTarget target;
        private Instant startTime;
        private volatile Instant endTime;
        private volatile SyncStatus status;
        private volatile boolean cancelled;
        private volatile int totalDocuments;
        private volatile int totalPassages;
        private final AtomicInteger documentsProcessed = new AtomicInteger(0);
        private final AtomicInteger passagesProcessed = new AtomicInteger(0);
        private final AtomicInteger errorCount = new AtomicInteger(0);
        private final List<String> errors = Collections.synchronizedList(new ArrayList<>());

        SyncJob(String jobId, Long factSheetId, SyncTarget target) {
            this.jobId = jobId;
            this.factSheetId = factSheetId;
            this.target = target;
            this.startTime = Instant.now();
            this.status = SyncStatus.PENDING;
        }

        String getJobId() { return jobId; }
        Long getFactSheetId() { return factSheetId; }
        SyncTarget getTarget() { return target; }
        Instant getStartTime() { return startTime; }
        Instant getEndTime() { return endTime; }
        void setEndTime(Instant endTime) { this.endTime = endTime; }
        SyncStatus getStatus() { return status; }
        void setStatus(SyncStatus status) { this.status = status; }
        boolean isCancelled() { return cancelled; }
        void setCancelled(boolean cancelled) { this.cancelled = cancelled; }
        int getTotalDocuments() { return totalDocuments; }
        void setTotalDocuments(int totalDocuments) { this.totalDocuments = totalDocuments; }
        int getTotalPassages() { return totalPassages; }
        void setTotalPassages(int totalPassages) { this.totalPassages = totalPassages; }
        AtomicInteger getDocumentsProcessed() { return documentsProcessed; }
        AtomicInteger getPassagesProcessed() { return passagesProcessed; }
        AtomicInteger getErrorCount() { return errorCount; }
        List<String> getErrors() { return errors; }

        int getProgressPercent() {
            int total = totalDocuments + totalPassages;
            if (total == 0) return 0;
            int processed = documentsProcessed.get() + passagesProcessed.get();
            return (int) ((processed * 100.0) / total);
        }

        Duration getDuration() {
            Instant end = endTime != null ? endTime : Instant.now();
            return Duration.between(startTime, end);
        }
    }
}
