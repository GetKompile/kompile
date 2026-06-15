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

package ai.kompile.app.web.controllers;

import ai.kompile.app.ingest.domain.CrawlJobRecord;
import ai.kompile.app.ingest.domain.CrawlJobRecord.CrawlJobStatus;
import ai.kompile.app.ingest.domain.IngestEvent.IngestPhase;
import ai.kompile.app.ingest.domain.IndexingJobHistory.FailureReason;
import ai.kompile.app.ingest.domain.JobLogEntry.LogLevel;
import ai.kompile.app.ingest.service.IndexingJobHistoryService;
import ai.kompile.app.ingest.service.JobLogService;
import ai.kompile.app.services.CrawlJobPersistenceService;
import ai.kompile.app.services.DocumentIngestService;
import ai.kompile.app.services.scheduler.ResourceAwareJobScheduler;
import ai.kompile.core.crawler.*;
import ai.kompile.core.crawler.pipeline.*;
import ai.kompile.crawler.CrawlPipelineRouter;
import ai.kompile.crawler.CrawlerService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.http.ResponseEntity;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.web.bind.annotation.*;

import jakarta.annotation.PreDestroy;

import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

/**
 * REST API for managing crawl jobs with multi-pipeline routing.
 *
 * <p>Each crawl job can define multiple {@link IngestPipelineDefinition}s with
 * {@link ContentRouteRule}s that route discovered items to the correct pipeline
 * based on content type, file extension, URL pattern, or source type.</p>
 *
 * <h3>Endpoints</h3>
 * <ul>
 *   <li>{@code GET  /api/crawlers}                   — list available crawlers</li>
 *   <li>{@code POST /api/crawlers/start}              — start a crawl with pipeline config</li>
 *   <li>{@code GET  /api/crawlers/jobs}                — list all jobs</li>
 *   <li>{@code GET  /api/crawlers/jobs/active}         — list running/paused jobs</li>
 *   <li>{@code GET  /api/crawlers/jobs/{id}}            — job details + pipeline stats</li>
 *   <li>{@code POST /api/crawlers/jobs/{id}/pause}     — pause</li>
 *   <li>{@code POST /api/crawlers/jobs/{id}/resume}    — resume</li>
 *   <li>{@code POST /api/crawlers/jobs/{id}/cancel}    — cancel</li>
 *   <li>{@code POST /api/crawlers/jobs/cleanup}         — remove finished jobs</li>
 *   <li>{@code POST /api/crawlers/validate}            — validate config</li>
 * </ul>
 *
 * <p>WebSocket progress at {@code /topic/crawl/progress} and {@code /topic/crawl/complete}.</p>
 */
@RestController
@RequestMapping("/api/crawlers")
@ConditionalOnBean(CrawlerService.class)
public class CrawlerController {

    private static final Logger log = LoggerFactory.getLogger(CrawlerController.class);

    /**
     * Max concurrent ingest tasks dispatched by crawl jobs. Limits how many files
     * the crawler can submit for ingest at once. When full, CallerRunsPolicy blocks
     * the crawler thread, providing natural backpressure — the crawler slows down
     * to match the ingest pipeline's capacity instead of flooding the system.
     */
    private static final int CRAWL_INGEST_POOL_SIZE = 4;
    private static final int CRAWL_INGEST_QUEUE_SIZE = 2;

    private final CrawlerService crawlerService;
    private final DocumentIngestService documentIngestService;
    private final SimpMessagingTemplate messagingTemplate;
    private final Map<String, ConcurrentMap<String, CrawlPipelineRuntimeStats>> pipelineStatsByJob =
            new ConcurrentHashMap<>();

    @Autowired(required = false)
    private IndexingJobHistoryService jobHistoryService;

    @Autowired(required = false)
    private JobLogService jobLogService;

    @Autowired(required = false)
    private CrawlJobPersistenceService crawlJobPersistenceService;

    @Autowired(required = false)
    private ResourceAwareJobScheduler jobScheduler;

    /**
     * Bounded thread pool for crawler-initiated ingests. Decoupled from Spring's
     * taskExecutor to prevent crawl jobs from starving other async work.
     * CallerRunsPolicy provides backpressure: when pool + queue are full, the
     * crawler's walkFileTree thread itself runs the ingest, naturally throttling
     * file discovery to match ingest throughput.
     */
    private final ThreadPoolExecutor crawlIngestExecutor = new ThreadPoolExecutor(
            CRAWL_INGEST_POOL_SIZE,
            CRAWL_INGEST_POOL_SIZE,
            60L, TimeUnit.SECONDS,
            new LinkedBlockingQueue<>(CRAWL_INGEST_QUEUE_SIZE),
            r -> {
                Thread t = new Thread(r, "crawl-ingest-" + Thread.currentThread().getId());
                t.setDaemon(true);
                return t;
            },
            new ThreadPoolExecutor.CallerRunsPolicy()
    );

    public CrawlerController(CrawlerService crawlerService,
                             DocumentIngestService documentIngestService,
                             SimpMessagingTemplate messagingTemplate) {
        this.crawlerService = crawlerService;
        this.documentIngestService = documentIngestService;
        this.messagingTemplate = messagingTemplate;
    }

    @PreDestroy
    public void shutdown() {
        crawlIngestExecutor.shutdown();
        try {
            if (!crawlIngestExecutor.awaitTermination(60, TimeUnit.SECONDS)) {
                crawlIngestExecutor.shutdownNow();
            }
        } catch (InterruptedException e) {
            crawlIngestExecutor.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    /** List all registered crawler implementations */
    @GetMapping
    public ResponseEntity<List<Map<String, Object>>> listCrawlers() {
        return ResponseEntity.ok(crawlerService.listCrawlers());
    }

    /**
     * Start a new crawl job with optional multi-pipeline configuration.
     *
     * <p>Example request body with pipeline routing:</p>
     * <pre>{@code
     * {
     *   "crawlerId": "web",
     *   "seed": "https://docs.example.com",
     *   "maxDepth": 3,
     *   "pipelines": [
     *     {"pipelineId": "html", "pipelineType": "STANDARD_TEXT", "chunkerName": "recursive-character"},
     *     {"pipelineId": "pdf-vlm", "pipelineType": "VLM", "enableVlm": true, "loaderName": "PDF Extended Loader"},
     *     {"pipelineId": "images", "pipelineType": "VLM", "enableVlm": true, "keywordOnly": true}
     *   ],
     *   "routeRules": [
     *     {"pipelineId": "pdf-vlm", "contentTypes": ["application/pdf"], "priority": 10},
     *     {"pipelineId": "images", "contentTypes": ["image/*"], "priority": 10},
     *     {"pipelineId": "html", "contentTypes": ["text/html"], "priority": 20}
     *   ],
     *   "defaultPipelineId": "html"
     * }
     * }</pre>
     */
    @PostMapping("/start")
    public ResponseEntity<?> startCrawl(@RequestBody CrawlConfig config) {
        try {
            AtomicReference<String> jobIdRef = new AtomicReference<>();
            PipelineAwareCrawlListener listener = createPipelineAwareListener(config, jobIdRef);
            CrawlJob job = crawlerService.startCrawl(config, listener);
            jobIdRef.set(job.getJobId());
            initializePipelineStats(job);
            publishCrawlerJobStarted(job, config);
            persistCrawlJobStart(job, config);

            Map<String, Object> response = new LinkedHashMap<>();
            response.put("jobId", job.getJobId());
            response.put("historyTaskId", crawlerHistoryTaskId(job.getJobId()));
            response.put("status", job.getStatus());
            response.put("message", "Crawl job started");
            response.put("crawlerId", config.getCrawlerId());
            response.put("seed", config.getSeed());
            response.put("pipelineCount", config.getPipelines() != null ? config.getPipelines().size() : 1);
            response.put("routeRuleCount", config.getRouteRules() != null ? config.getRouteRules().size() : 0);

            return ResponseEntity.ok(response);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        } catch (Exception e) {
            log.error("Failed to start crawl", e);
            return ResponseEntity.internalServerError()
                    .body(Map.of("error", "Failed to start crawl: " + e.getMessage()));
        }
    }

    /** Validate a crawl config without starting */
    @PostMapping("/validate")
    public ResponseEntity<?> validateConfig(@RequestBody CrawlConfig config) {
        try {
            List<String> errors = new ArrayList<>();
            if (config.getSeed() == null || config.getSeed().isBlank()) {
                errors.add("seed is required");
            }
            if (config.getMaxDepth() < 0) {
                errors.add("maxDepth must be >= 0");
            }
            // Validate pipeline references in route rules
            if (config.getRouteRules() != null && config.getPipelines() != null) {
                Set<String> pipelineIds = config.getPipelines().stream()
                        .map(IngestPipelineDefinition::getPipelineId)
                        .collect(Collectors.toSet());
                for (ContentRouteRule rule : config.getRouteRules()) {
                    if (!pipelineIds.contains(rule.getPipelineId())) {
                        errors.add("Route rule references unknown pipeline: " + rule.getPipelineId());
                    }
                }
            }

            if (errors.isEmpty()) {
                return ResponseEntity.ok(Map.of("valid", true));
            } else {
                return ResponseEntity.badRequest().body(Map.of("valid", false, "errors", errors));
            }
        } catch (Exception e) {
            return ResponseEntity.internalServerError().body(Map.of("error", e.getMessage()));
        }
    }

    @GetMapping("/jobs")
    public ResponseEntity<List<Map<String, Object>>> listJobs() {
        return ResponseEntity.ok(crawlerService.getAllJobs().stream()
                .map(this::jobToMap).collect(Collectors.toList()));
    }

    @GetMapping("/jobs/active")
    public ResponseEntity<List<Map<String, Object>>> listActiveJobs() {
        return ResponseEntity.ok(crawlerService.getActiveJobs().stream()
                .map(this::jobToMap).collect(Collectors.toList()));
    }

    @GetMapping("/jobs/{jobId}")
    public ResponseEntity<?> getJob(@PathVariable String jobId) {
        return crawlerService.getJob(jobId)
                .map(job -> ResponseEntity.ok(jobToMap(job)))
                .orElse(ResponseEntity.notFound().build());
    }

    @PostMapping("/jobs/{jobId}/pause")
    public ResponseEntity<?> pauseJob(@PathVariable String jobId) {
        if (crawlerService.pauseJob(jobId)) {
            updateCrawlerHistory(jobId, IngestPhase.LOADING, 0, LogLevel.WARN, "Crawler job paused");
            return ResponseEntity.ok(Map.of("message", "Job paused", "jobId", jobId));
        }
        return ResponseEntity.badRequest().body(Map.of("error", "Job not found or not running"));
    }

    @PostMapping("/jobs/{jobId}/resume")
    public ResponseEntity<?> resumeJob(@PathVariable String jobId) {
        if (crawlerService.resumeJob(jobId)) {
            updateCrawlerHistory(jobId, IngestPhase.LOADING, 0, LogLevel.INFO, "Crawler job resumed");
            return ResponseEntity.ok(Map.of("message", "Job resumed", "jobId", jobId));
        }
        return ResponseEntity.badRequest().body(Map.of("error", "Job not found or not paused"));
    }

    @PostMapping("/jobs/{jobId}/cancel")
    public ResponseEntity<?> cancelJob(@PathVariable String jobId) {
        if (crawlerService.cancelJob(jobId)) {
            markCrawlerCancelled(jobId, "User cancelled crawler job");
            return ResponseEntity.ok(Map.of("message", "Job cancelled", "jobId", jobId));
        }
        return ResponseEntity.badRequest().body(Map.of("error", "Job not found or already finished"));
    }

    @PostMapping("/jobs/cleanup")
    public ResponseEntity<?> cleanupJobs() {
        int removed = crawlerService.cleanupJobs();
        Set<String> retainedJobIds = crawlerService.getAllJobs().stream()
                .map(CrawlJob::getJobId)
                .collect(Collectors.toSet());
        pipelineStatsByJob.keySet().removeIf(jobId -> !retainedJobIds.contains(jobId));
        return ResponseEntity.ok(Map.of("removed", removed));
    }

    // ---- Resume endpoints ----

    /**
     * List crawl jobs that can be resumed from checkpoint.
     * Includes jobs that were interrupted (app restart), failed, or cancelled
     * and have a saved checkpoint with state data.
     */
    @GetMapping("/jobs/resumable")
    public ResponseEntity<?> listResumableJobs() {
        if (crawlJobPersistenceService == null) {
            return ResponseEntity.ok(Collections.emptyList());
        }
        List<CrawlJobRecord> resumable = crawlJobPersistenceService.listResumable();
        List<Map<String, Object>> result = resumable.stream().map(r -> {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("crawlJobId", r.getCrawlJobId());
            map.put("crawlerId", r.getCrawlerId());
            map.put("seed", r.getSeed());
            map.put("status", r.getStatus().name());
            map.put("startedAt", r.getStartedAt() != null ? r.getStartedAt().toString() : null);
            map.put("lastCheckpointAt", r.getLastCheckpointAt() != null ? r.getLastCheckpointAt().toString() : null);
            map.put("endedAt", r.getEndedAt() != null ? r.getEndedAt().toString() : null);
            map.put("documentsDiscovered", r.getDocumentsDiscovered());
            map.put("documentsProcessed", r.getDocumentsProcessed());
            map.put("documentsFailed", r.getDocumentsFailed());
            map.put("errorMessage", r.getErrorMessage());
            map.put("historyTaskId", r.getHistoryTaskId());
            map.put("hasCheckpoint", r.getLastCheckpointJson() != null);
            map.put("resumedFromJobId", r.getResumedFromJobId());
            return map;
        }).collect(Collectors.toList());
        return ResponseEntity.ok(result);
    }

    /**
     * Restart a crawl job from its last checkpoint.
     * Loads the saved CrawlConfig and CrawlState, sets the state as previousState,
     * and starts a new crawl that skips already-visited URLs and resumes from
     * the saved frontier.
     */
    @PostMapping("/jobs/{jobId}/restart")
    public ResponseEntity<?> restartFromCheckpoint(@PathVariable String jobId) {
        if (crawlJobPersistenceService == null) {
            return ResponseEntity.badRequest()
                    .body(Map.of("error", "Crawl job persistence is not available"));
        }

        Optional<CrawlConfig> configOpt = crawlJobPersistenceService.loadConfig(jobId);
        if (configOpt.isEmpty()) {
            return ResponseEntity.badRequest()
                    .body(Map.of("error", "No saved configuration found for job " + jobId));
        }

        Optional<CrawlState> checkpointOpt = crawlJobPersistenceService.loadCheckpoint(jobId);
        if (checkpointOpt.isEmpty()) {
            return ResponseEntity.badRequest()
                    .body(Map.of("error", "No checkpoint found for job " + jobId));
        }

        CrawlConfig config = configOpt.get();
        config.setPreviousState(checkpointOpt.get());

        try {
            AtomicReference<String> jobIdRef = new AtomicReference<>();
            PipelineAwareCrawlListener listener = createPipelineAwareListener(config, jobIdRef);
            CrawlJob newJob = crawlerService.startCrawl(config, listener);
            jobIdRef.set(newJob.getJobId());
            initializePipelineStats(newJob);
            publishCrawlerJobStarted(newJob, config);
            persistCrawlJobStart(newJob, config);
            // Mark the new crawl record as resumed from the original job
            persistCrawlResumeLineage(newJob.getJobId(), jobId);

            CrawlState checkpoint = checkpointOpt.get();
            int resumedUrlCount = checkpoint.getVisitedUrls() != null ? checkpoint.getVisitedUrls().size() : 0;
            int pendingUrlCount = checkpoint.getPendingUrls() != null ? checkpoint.getPendingUrls().size() : 0;

            Map<String, Object> response = new LinkedHashMap<>();
            response.put("jobId", newJob.getJobId());
            response.put("historyTaskId", crawlerHistoryTaskId(newJob.getJobId()));
            response.put("originalJobId", jobId);
            response.put("status", newJob.getStatus());
            response.put("message", "Crawl job resumed from checkpoint");
            response.put("resumedFromCheckpoint", true);
            response.put("resumedUrlCount", resumedUrlCount);
            response.put("pendingUrlCount", pendingUrlCount);

            String safeSeed = config.getSeed() != null ? config.getSeed().replace('\n', ' ').replace('\r', ' ') : "";
            log.info("Resumed crawl job {} from checkpoint of job {} (seed: {}, {} URLs already visited, {} pending)",
                    newJob.getJobId(), jobId, safeSeed, resumedUrlCount, pendingUrlCount);

            return ResponseEntity.ok(response);
        } catch (Exception e) {
            log.error("Failed to restart crawl from checkpoint for job {}", jobId, e);
            return ResponseEntity.internalServerError()
                    .body(Map.of("error", "Failed to restart crawl: " + e.getMessage()));
        }
    }

    // ---- Periodic checkpoint ----

    /**
     * Periodically checkpoint all running crawl jobs to the database.
     * This ensures that if the application crashes, the crawl can be resumed
     * from the last checkpoint rather than starting from scratch.
     */
    @Scheduled(fixedDelayString = "${kompile.crawl.checkpoint.interval-ms:60000}")
    public void periodicCrawlCheckpoint() {
        if (crawlJobPersistenceService == null) return;
        for (CrawlJob job : crawlerService.getActiveJobs()) {
            if (job.getStatus() == CrawlStatus.RUNNING || job.getStatus() == CrawlStatus.PAUSED) {
                try {
                    CrawlState state = job.checkpoint();
                    crawlJobPersistenceService.saveCheckpoint(
                            job.getJobId(), state, job.getProgress());
                } catch (Exception e) {
                    log.warn("Periodic checkpoint failed for crawl job {}: {}",
                            job.getJobId(), e.getMessage(), e);
                }
            }
        }
    }

    // ---- Internal ----

    private Map<String, Object> jobToMap(CrawlJob job) {
        CrawlProgress progress = job.getProgress();
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("jobId", job.getJobId());
        map.put("historyTaskId", crawlerHistoryTaskId(job.getJobId()));
        map.put("status", job.getStatus());
        map.put("crawlerId", job.getConfig().getCrawlerId());
        map.put("seed", job.getConfig().getSeed());
        // Indicate if this job was resumed from a previous crawl's checkpoint
        boolean isResumed = job.getConfig().getPreviousState() != null;
        map.put("resumedFromCheckpoint", isResumed);
        if (isResumed && job.getConfig().getPreviousState().getVisitedUrls() != null) {
            map.put("resumedUrlCount", job.getConfig().getPreviousState().getVisitedUrls().size());
        }
        map.put("progress", Map.of(
                "discovered", progress.discovered(),
                "processed", progress.processed(),
                "failed", progress.failed(),
                "queued", progress.queued(),
                "currentDepth", progress.currentDepth(),
                "maxDepth", progress.maxDepth(),
                "currentItem", progress.currentItem() != null ? progress.currentItem() : "",
                "estimatedPercent", progress.estimatedPercent()
        ));
        map.put("ingestQueue", Map.of(
                "activeThreads", crawlIngestExecutor.getActiveCount(),
                "poolSize", crawlIngestExecutor.getPoolSize(),
                "maxThreads", crawlIngestExecutor.getMaximumPoolSize(),
                "queuedTasks", crawlIngestExecutor.getQueue().size(),
                "queueCapacity", CRAWL_INGEST_QUEUE_SIZE,
                "completedTasks", crawlIngestExecutor.getCompletedTaskCount()
        ));
        map.put("pipelines", pipelineInfo(job));
        return map;
    }

    private List<Map<String, Object>> pipelineInfo(CrawlJob job) {
        return crawlerService.getRouter(job.getJobId())
                .map(router -> {
                    List<Map<String, Object>> pipelines = new ArrayList<>();
                    for (IngestPipelineDefinition p : router.getAllPipelines()) {
                        pipelines.add(pipelineInfoMap(job, router, p));
                    }
                    return pipelines;
                })
                .orElseGet(Collections::emptyList);
    }

    private Map<String, Object> pipelineInfoMap(CrawlJob job,
                                                CrawlPipelineRouter router,
                                                IngestPipelineDefinition pipeline) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("pipelineId", pipeline.getPipelineId());
        m.put("displayName", pipeline.getDisplayName() != null
                ? pipeline.getDisplayName()
                : pipeline.getPipelineId());
        m.put("pipelineType", pipeline.getPipelineType().name());
        m.put("isDefault", pipeline.getPipelineId().equals(router.getDefaultPipeline().getPipelineId()));
        m.put("loaderName", pipeline.getLoaderName());
        m.put("chunkerName", pipeline.getChunkerName());
        m.put("embeddingModelName", pipeline.getEmbeddingModelName());
        m.put("keywordOnly", pipeline.isKeywordOnly());
        m.put("enableVlm", pipeline.isEnableVlm());
        m.put("enableGraphExtraction", pipeline.isEnableGraphExtraction());

        CrawlPipelineRuntimeStats stats = statsFor(job.getJobId(), pipeline);
        int routed = stats.routedItems.get();
        int dispatched = stats.dispatchedTasks.get();
        int failed = stats.failedDispatches.get();
        int queued = stats.queuedTasks.get();
        int active = stats.activeTasks.get();
        int finished = dispatched + failed;
        int progressPercent = routed == 0 ? 0 : Math.min(100, Math.max(0, finished * 100 / routed));

        m.put("routedItems", routed);
        m.put("queuedTasks", queued);
        m.put("activeTasks", active);
        m.put("dispatchedTasks", dispatched);
        m.put("failedDispatches", failed);
        m.put("progressPercent", progressPercent);
        m.put("lastItem", stats.lastItem.get());
        m.put("latestTaskId", stats.latestTaskId.get());
        m.put("lastUpdatedEpochMs", stats.lastUpdatedEpochMs.get());
        m.put("status", pipelineStatus(job.getStatus(), routed, queued, active, failed, finished));
        return m;
    }

    private String pipelineStatus(CrawlStatus jobStatus,
                                  int routed,
                                  int queued,
                                  int active,
                                  int failed,
                                  int finished) {
        if (routed == 0) {
            return isTerminal(jobStatus) ? "SKIPPED" : "PENDING";
        }
        if (queued > 0 || active > 0) {
            return "RUNNING";
        }
        if (failed > 0 && finished >= routed) {
            return "FAILED";
        }
        if (isTerminal(jobStatus) || finished >= routed) {
            return "COMPLETED";
        }
        return "PENDING";
    }

    private boolean isTerminal(CrawlStatus status) {
        return status == CrawlStatus.COMPLETED
                || status == CrawlStatus.FAILED
                || status == CrawlStatus.CANCELLED;
    }

    private void initializePipelineStats(CrawlJob job) {
        crawlerService.getRouter(job.getJobId()).ifPresent(router -> {
            for (IngestPipelineDefinition pipeline : router.getAllPipelines()) {
                statsFor(job.getJobId(), pipeline);
            }
        });
    }

    private CrawlPipelineRuntimeStats statsFor(String jobId, IngestPipelineDefinition pipeline) {
        ConcurrentMap<String, CrawlPipelineRuntimeStats> statsByPipeline =
                pipelineStatsByJob.computeIfAbsent(jobId, ignored -> new ConcurrentHashMap<>());
        return statsByPipeline.computeIfAbsent(pipeline.getPipelineId(), ignored -> new CrawlPipelineRuntimeStats());
    }

    private static final class CrawlPipelineRuntimeStats {
        private final AtomicInteger routedItems = new AtomicInteger();
        private final AtomicInteger queuedTasks = new AtomicInteger();
        private final AtomicInteger activeTasks = new AtomicInteger();
        private final AtomicInteger dispatchedTasks = new AtomicInteger();
        private final AtomicInteger failedDispatches = new AtomicInteger();
        private final AtomicReference<String> lastItem = new AtomicReference<>("");
        private final AtomicReference<String> latestTaskId = new AtomicReference<>("");
        private final AtomicLong lastUpdatedEpochMs = new AtomicLong(System.currentTimeMillis());

        void markRouted(String item, String taskId) {
            routedItems.incrementAndGet();
            queuedTasks.incrementAndGet();
            lastItem.set(item != null ? item : "");
            latestTaskId.set(taskId != null ? taskId : "");
            lastUpdatedEpochMs.set(System.currentTimeMillis());
        }

        void markDispatchStarted() {
            queuedTasks.updateAndGet(v -> Math.max(0, v - 1));
            activeTasks.incrementAndGet();
            lastUpdatedEpochMs.set(System.currentTimeMillis());
        }

        void markDispatchComplete() {
            activeTasks.updateAndGet(v -> Math.max(0, v - 1));
            dispatchedTasks.incrementAndGet();
            lastUpdatedEpochMs.set(System.currentTimeMillis());
        }

        void markDispatchFailed() {
            queuedTasks.updateAndGet(v -> Math.max(0, v - 1));
            activeTasks.updateAndGet(v -> Math.max(0, v - 1));
            failedDispatches.incrementAndGet();
            lastUpdatedEpochMs.set(System.currentTimeMillis());
        }
    }

    private String crawlerHistoryTaskId(String jobId) {
        return "crawler-" + jobId;
    }

    private void publishCrawlerJobStarted(CrawlJob job, CrawlConfig config) {
        if (job == null || job.getJobId() == null || jobHistoryService == null) {
            return;
        }
        String taskId = crawlerHistoryTaskId(job.getJobId());
        try {
            String crawlerId = config.getCrawlerId() != null ? config.getCrawlerId() : "auto";
            String seed = config.getSeed() != null ? config.getSeed() : "";
            jobHistoryService.createJobWithEnvironment(taskId,
                    "[CRAWLER] " + crawlerId + " " + seed,
                    null,
                    null,
                    "crawler");
            jobHistoryService.markJobRunning(taskId);
            jobHistoryService.updateJobParameters(taskId,
                    "Crawler",
                    null,
                    crawlerId,
                    "PipelineRouter",
                    null,
                    null,
                    null,
                    CRAWL_INGEST_POOL_SIZE,
                    true,
                    true);
            logCrawlerSystem(taskId, LogLevel.INFO, "Started crawler job using " + crawlerId + " from " + seed);
        } catch (Exception e) {
            log.debug("Failed to publish crawler job {} to history: {}", job.getJobId(), e.getMessage());
        }
    }

    private void updateCrawlerHistory(String jobId,
                                      IngestPhase phase,
                                      int progressPercent,
                                      LogLevel level,
                                      String message) {
        if (jobId == null) {
            return;
        }
        String taskId = crawlerHistoryTaskId(jobId);
        try {
            if (jobHistoryService != null) {
                jobHistoryService.updateJobProgress(taskId, phase, clampPercent(progressPercent));
            }
            logCrawlerSystem(taskId, level, message);
        } catch (Exception e) {
            log.debug("Failed to update crawler job history for {}: {}", jobId, e.getMessage());
        }
    }

    private void updateCrawlerStats(String jobId, CrawlProgress progress) {
        if (jobId == null || progress == null || jobHistoryService == null) {
            return;
        }
        try {
            jobHistoryService.updateJobStats(crawlerHistoryTaskId(jobId),
                    progress.processed(),
                    null,
                    null,
                    null);
            jobHistoryService.updateJobProgress(crawlerHistoryTaskId(jobId),
                    IngestPhase.LOADING,
                    crawlProgressPercent(progress));
        } catch (Exception e) {
            log.debug("Failed to update crawler stats for {}: {}", jobId, e.getMessage());
        }
    }

    private void markCrawlerComplete(String jobId, CrawlSummary summary) {
        if (jobId == null || summary == null || jobHistoryService == null) {
            return;
        }
        String taskId = crawlerHistoryTaskId(jobId);
        try {
            jobHistoryService.updateJobStats(taskId, summary.totalProcessed(), null, null, null);
            if (summary.status() == CrawlStatus.COMPLETED) {
                logCrawlerSystem(taskId, LogLevel.INFO, "Crawler completed: "
                        + summary.totalProcessed() + " processed, "
                        + summary.totalFailed() + " failed, "
                        + summary.totalSkipped() + " skipped");
                jobHistoryService.markJobCompleted(taskId);
            } else if (summary.status() == CrawlStatus.CANCELLED) {
                markCrawlerCancelled(jobId, "Crawler job cancelled");
            } else if (summary.status() == CrawlStatus.FAILED) {
                String errorMessage = summary.errors() != null && !summary.errors().isEmpty()
                        ? summary.errors().stream()
                                .limit(3)
                                .map(entry -> entry.getKey() + ": " + entry.getValue())
                                .collect(Collectors.joining("; "))
                        : "Crawler job failed";
                logCrawlerSystem(taskId, LogLevel.ERROR, errorMessage);
                jobHistoryService.markJobFailed(taskId,
                        IngestPhase.LOADING,
                        errorMessage,
                        null,
                        FailureReason.UNKNOWN);
            }
        } catch (Exception e) {
            log.debug("Failed to complete crawler job history for {}: {}", jobId, e.getMessage());
        }
    }

    private void markCrawlerCancelled(String jobId, String reason) {
        if (jobId == null || jobHistoryService == null) {
            return;
        }
        String taskId = crawlerHistoryTaskId(jobId);
        try {
            logCrawlerSystem(taskId, LogLevel.WARN, reason);
            jobHistoryService.markJobCancelled(taskId, IngestPhase.LOADING, reason);
        } catch (Exception e) {
            log.debug("Failed to cancel crawler job history for {}: {}", jobId, e.getMessage());
        }
    }

    private void logCrawlerSystem(String taskId, LogLevel level, String message) {
        if (jobLogService == null || !jobLogService.isEnabled() || message == null || message.isBlank()) {
            return;
        }
        try {
            jobLogService.logSystem(taskId, level, message);
        } catch (Exception e) {
            log.debug("Failed to write crawler log for {}: {}", taskId, e.getMessage());
        }
    }

    private int crawlProgressPercent(CrawlProgress progress) {
        if (progress.estimatedPercent() >= 0) {
            return clampPercent(progress.estimatedPercent());
        }
        int total = progress.processed() + progress.failed() + progress.queued();
        if (total <= 0) {
            return 0;
        }
        return clampPercent((progress.processed() + progress.failed()) * 100 / total);
    }

    private int clampPercent(int value) {
        return Math.max(0, Math.min(100, value));
    }

    /**
     * Creates a pipeline-aware listener that:
     * 1. Dispatches each routed item to DocumentIngestService with the correct loader/chunker
     * 2. Sends WebSocket progress updates
     */
    private PipelineAwareCrawlListener createPipelineAwareListener(CrawlConfig config,
                                                                   AtomicReference<String> jobIdRef) {
        return new PipelineAwareCrawlListener() {

            @Override
            public void onItemRouted(RoutedCrawlItem routedItem) {
                IngestPipelineDefinition pipeline = routedItem.pipeline();
                CrawlItem item = routedItem.item();
                String url = item.getUrl() != null ? item.getUrl().replaceAll("[\\r\\n]", " ") : "";
                CrawlPipelineRuntimeStats stats = null;

                try {
                    String taskId = UUID.randomUUID().toString();
                    Path path = null;
                    String jobId = jobIdRef.get();
                    stats = jobId != null ? statsFor(jobId, pipeline) : null;
                    if (stats != null) {
                        stats.markRouted(url, taskId);
                    }

                    // For file-based items, use the file path directly
                    if (item.getSourceDescriptor() != null
                            && item.getSourceDescriptor().getPathOrUrl() != null) {
                        String pathOrUrl = item.getSourceDescriptor().getPathOrUrl();
                        if (!pathOrUrl.startsWith("http://") && !pathOrUrl.startsWith("https://")) {
                            path = Path.of(pathOrUrl);
                        }
                    }

                    // Dispatch to ingest service via bounded pool.
                    // CallerRunsPolicy blocks the crawler thread when the pool is full,
                    // providing natural backpressure — discovery slows to match ingest capacity.
                    if (path != null) {
                        Map<String, Object> options = null;
                        if (pipeline.isEnableGraphExtraction()) {
                            options = new HashMap<>();
                            options.put("enableGraphExtraction", true);
                        }

                        final Path filePath = path;
                        final Map<String, Object> finalOptions = options;
                        final CrawlPipelineRuntimeStats capturedStats = stats;

                        // Route through resource-aware scheduler when available
                        if (jobScheduler != null) {
                            if (capturedStats != null) {
                                capturedStats.markDispatchStarted();
                            }
                            documentIngestService.scheduleIngest(
                                    taskId, filePath,
                                    pipeline.getLoaderName(),
                                    pipeline.getChunkerName(),
                                    finalOptions
                            ).whenComplete((result, error) -> {
                                if (error != null || (result != null && !result.success())) {
                                    if (capturedStats != null) capturedStats.markDispatchFailed();
                                    log.warn("Scheduled ingest failed for '{}': {}",
                                            url, error != null ? error.getMessage() :
                                                    (result != null ? result.errorMessage() : "unknown"));
                                } else {
                                    if (capturedStats != null) capturedStats.markDispatchComplete();
                                }
                            });
                        } else {
                            // Fallback: direct executor dispatch
                            crawlIngestExecutor.submit(() -> {
                                if (capturedStats != null) {
                                    capturedStats.markDispatchStarted();
                                }
                                try {
                                    documentIngestService.processDocumentAsync(
                                            taskId,
                                            filePath,
                                            pipeline.getLoaderName(),
                                            pipeline.getChunkerName(),
                                            null,
                                            finalOptions
                                    );
                                    if (capturedStats != null) {
                                        capturedStats.markDispatchComplete();
                                    }
                                } catch (Exception ex) {
                                    if (capturedStats != null) {
                                        capturedStats.markDispatchFailed();
                                    }
                                    log.warn("Ingest failed for '{}': {}", url, ex.getMessage());
                                }
                            });
                        }
                    } else if (stats != null) {
                        stats.markDispatchStarted();
                        stats.markDispatchComplete();
                    }

                    log.debug("Dispatched item '{}' to pipeline '{}' (type={})",
                            url, pipeline.getPipelineId(), pipeline.getPipelineType());

                } catch (Exception e) {
                    if (stats != null) {
                        stats.markDispatchFailed();
                    }
                    log.warn("Failed to dispatch crawl item '{}' to pipeline '{}': {}",
                            url, pipeline.getPipelineId(), e.getMessage());
                }
            }

            @Override
            public void onDocumentDiscovered(CrawlItem item) {
                // Discovery is handled by onItemRouted
                String jobId = jobIdRef.get();
                if (jobId != null && item != null) {
                    logCrawlerSystem(crawlerHistoryTaskId(jobId), LogLevel.INFO,
                            "Discovered " + (item.getUrl() != null ? item.getUrl() : "crawl item"));
                }
            }

            @Override
            public void onDocumentProcessed(CrawlItem item) {
                String jobId = jobIdRef.get();
                if (jobId != null && item != null) {
                    logCrawlerSystem(crawlerHistoryTaskId(jobId), LogLevel.INFO,
                            "Processed " + (item.getUrl() != null ? item.getUrl() : "crawl item"));
                }
            }

            @Override
            public void onProgress(CrawlProgress progress) {
                try {
                    messagingTemplate.convertAndSend("/topic/crawl/progress", Map.of(
                            "crawlerId", config.getCrawlerId() != null ? config.getCrawlerId() : "",
                            "seed", config.getSeed() != null ? config.getSeed() : "",
                            "discovered", progress.discovered(),
                            "processed", progress.processed(),
                            "failed", progress.failed(),
                            "currentDepth", progress.currentDepth(),
                            "currentItem", progress.currentItem() != null ? progress.currentItem() : "",
                            "estimatedPercent", progress.estimatedPercent()
                    ));
                } catch (Exception e) {
                    log.debug("Failed to send WebSocket progress: {}", e.getMessage());
                }
                updateCrawlerStats(jobIdRef.get(), progress);
            }

            @Override
            public void onComplete(CrawlSummary summary) {
                try {
                    messagingTemplate.convertAndSend("/topic/crawl/complete", Map.of(
                            "status", summary.status().name(),
                            "totalDiscovered", summary.totalDiscovered(),
                            "totalProcessed", summary.totalProcessed(),
                            "totalFailed", summary.totalFailed(),
                            "totalSkipped", summary.totalSkipped(),
                            "duration", summary.duration() != null ? summary.duration().toSeconds() : 0
                    ));
                } catch (Exception e) {
                    log.debug("Failed to send WebSocket completion: {}", e.getMessage());
                }
                markCrawlerComplete(jobIdRef.get(), summary);
                persistCrawlJobComplete(jobIdRef.get(), summary);
            }

            @Override
            public void onDocumentFailed(CrawlItem item, Exception error) {
                String jobId = jobIdRef.get();
                if (jobId != null) {
                    String itemName = item != null && item.getUrl() != null ? item.getUrl() : "crawl item";
                    logCrawlerSystem(crawlerHistoryTaskId(jobId), LogLevel.ERROR,
                            "Failed " + itemName + ": " + (error != null ? error.getMessage() : "unknown error"));
                }
            }
        };
    }

    private void persistCrawlJobStart(CrawlJob job, CrawlConfig config) {
        if (crawlJobPersistenceService == null || job == null) return;
        try {
            crawlJobPersistenceService.createRecord(job, config, crawlerHistoryTaskId(job.getJobId()));
        } catch (Exception e) {
            log.debug("Failed to persist crawl job start for {}: {}", job.getJobId(), e.getMessage());
        }
    }

    private void persistCrawlResumeLineage(String newJobId, String originalJobId) {
        if (crawlJobPersistenceService == null) return;
        try {
            crawlJobPersistenceService.setResumedFromJobId(newJobId, originalJobId);
        } catch (Exception e) {
            log.debug("Failed to set resume lineage for {}: {}", newJobId, e.getMessage());
        }
    }

    private void persistCrawlJobComplete(String jobId, CrawlSummary summary) {
        if (crawlJobPersistenceService == null || jobId == null || summary == null) return;
        try {
            CrawlJobStatus dbStatus;
            String errorMessage = null;
            switch (summary.status()) {
                case COMPLETED:
                    dbStatus = CrawlJobStatus.COMPLETED;
                    break;
                case CANCELLED:
                    dbStatus = CrawlJobStatus.CANCELLED;
                    break;
                case FAILED:
                    dbStatus = CrawlJobStatus.FAILED;
                    if (summary.errors() != null && !summary.errors().isEmpty()) {
                        errorMessage = summary.errors().stream()
                                .limit(5)
                                .map(e -> e.getKey() + ": " + e.getValue())
                                .collect(Collectors.joining("; "));
                    }
                    break;
                default:
                    dbStatus = CrawlJobStatus.FAILED;
            }
            crawlJobPersistenceService.finalizeRecord(
                    jobId, dbStatus, summary.finalState(), null, errorMessage);
        } catch (Exception e) {
            log.debug("Failed to persist crawl job completion for {}: {}", jobId, e.getMessage());
        }
    }
}
