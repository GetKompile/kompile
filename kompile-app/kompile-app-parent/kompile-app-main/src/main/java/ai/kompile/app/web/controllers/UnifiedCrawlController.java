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

import ai.kompile.app.facts.domain.FactSheet;
import ai.kompile.app.facts.service.FactSheetService;
import ai.kompile.app.ingest.domain.IngestEvent.IngestPhase;
import ai.kompile.app.ingest.domain.JobLogEntry.LogLevel;
import ai.kompile.app.ingest.domain.IndexingJobHistory;
import ai.kompile.app.ingest.domain.IndexingJobHistory.FailureReason;
import ai.kompile.app.ingest.service.IndexingJobHistoryService;
import ai.kompile.app.ingest.service.JobLogService;
import ai.kompile.app.web.dto.IngestProgressUpdate;
import ai.kompile.app.services.GraphSchemaPresetService;
import ai.kompile.app.services.scheduler.ResourceAwareJobScheduler;
import ai.kompile.core.crawl.graph.*;
import ai.kompile.core.crawl.graph.archive.CrawlStepArchiveService;
import ai.kompile.crawl.graph.CrawlPipelineStepRegistry;
import ai.kompile.knowledgegraph.service.FactSheetGraphService;
import ai.kompile.knowledgegraph.service.KnowledgeGraphService;
import ai.kompile.knowledgegraph.domain.GraphNode;
import ai.kompile.knowledgegraph.domain.NodeLevel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.bytedeco.javacpp.Pointer;
import ai.kompile.core.loaders.DocumentSourceDescriptor;
import ai.kompile.loaders.orchestrator.config.AppDocumentSourceProperties;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.lang.management.BufferPoolMXBean;
import java.lang.management.ManagementFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;

/**
 * REST API for the unified crawl-to-graph pipeline.
 *
 * <h3>Endpoints</h3>
 * <ul>
 *   <li>{@code POST /api/unified-crawl/start}          — start a multi-source crawl job</li>
 *   <li>{@code GET  /api/unified-crawl/jobs}             — list all jobs</li>
 *   <li>{@code GET  /api/unified-crawl/jobs/active}      — list active jobs</li>
 *   <li>{@code GET  /api/unified-crawl/jobs/{id}}         — get job detail + progress</li>
 *   <li>{@code POST /api/unified-crawl/jobs/{id}/cancel} — cancel a job</li>
 *   <li>{@code POST /api/unified-crawl/jobs/cleanup}      — remove finished jobs</li>
 *   <li>{@code GET  /api/unified-crawl/source-types}      — list available source types</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/unified-crawl")
@ConditionalOnBean(UnifiedCrawlService.class)
public class UnifiedCrawlController {

    private static final Logger log = LoggerFactory.getLogger(UnifiedCrawlController.class);

    // Scheduling intervals
    private static final long CRAWL_SYNC_INTERVAL_MS = 15_000L; // 15 seconds
    private static final long CRAWL_LOG_PUBLISH_INTERVAL_MS = 2_000L; // 2 seconds

    private final UnifiedCrawlService unifiedCrawlService;

    @Autowired(required = false)
    private GraphSchemaPresetService schemaPresetService;

    @Autowired(required = false)
    private KnowledgeGraphService knowledgeGraphService;

    @Autowired(required = false)
    private FactSheetGraphService factSheetGraphService;

    @Autowired(required = false)
    private IndexingJobHistoryService jobHistoryService;

    @Autowired(required = false)
    private JobLogService jobLogService;

    @Autowired(required = false)
    private SimpMessagingTemplate messagingTemplate;

    @Autowired(required = false)
    private FactSheetService factSheetService;

    @Autowired(required = false)
    private AppDocumentSourceProperties appDocumentSourceProperties;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired(required = false)
    private ResourceAwareJobScheduler resourceScheduler;

    @Autowired(required = false)
    private CrawlStepArchiveService crawlStepArchiveService;

    /** Resolved uploads directory for file-based crawl jobs */
    private Path uploadsPath;

    @jakarta.annotation.PostConstruct
    private void initUploadsPath() {
        if (appDocumentSourceProperties != null
                && appDocumentSourceProperties.getUploadsPath() != null
                && !appDocumentSourceProperties.getUploadsPath().trim().isEmpty()) {
            this.uploadsPath = Paths.get(appDocumentSourceProperties.getUploadsPath()).toAbsolutePath();
        } else {
            this.uploadsPath = Paths.get("./uploads").toAbsolutePath();
        }
        try {
            if (!Files.exists(this.uploadsPath)) {
                Files.createDirectories(this.uploadsPath);
            }
        } catch (Exception e) {
            log.warn("Could not create uploads directory {}: {}", uploadsPath, e.getMessage());
        }
    }

    /** Track which crawl jobs have been published to job history */
    private final Set<String> publishedJobIds = ConcurrentHashMap.newKeySet();

    /** Map scheduler IDs (e.g., "crawl-55ef8175") to internal job UUIDs for consistent lookup */
    private final Map<String, String> schedulerIdToJobId = new ConcurrentHashMap<>();

    /** Track crawl stage events already mirrored into the shared job log stream */
    private final Map<String, Set<String>> publishedCrawlLogEventKeys = new ConcurrentHashMap<>();

    /** Live WebSocket sequence numbers for crawl job log streaming */
    private final Map<String, AtomicLong> crawlLogSequences = new ConcurrentHashMap<>();

    public UnifiedCrawlController(UnifiedCrawlService unifiedCrawlService) {
        this.unifiedCrawlService = unifiedCrawlService;
    }

    /**
     * Start a unified crawl-to-graph job.
     *
     * <p>Example request body:</p>
     * <pre>{@code
     * {
     *   "name": "Company knowledge base",
     *   "sources": [
     *     {"label": "Docs", "sourceType": "DIRECTORY", "pathOrUrl": "/data/docs"},
     *     {"label": "Website", "sourceType": "WEB_CRAWL", "pathOrUrl": "https://docs.example.com", "maxDepth": 2}
     *   ],
     *   "graphExtraction": {
     *     "enabled": true,
     *     "entityTypes": ["PERSON", "ORGANIZATION", "PRODUCT"],
     *     "llmProvider": "openai"
     *   },
     *   "vectorIndex": {
     *     "enabled": true,
     *     "collectionName": "company-kb"
     *   }
     * }
     * }</pre>
     */
    @PostMapping("/start")
    public ResponseEntity<?> startJob(@RequestBody UnifiedCrawlRequest request) {
        try {
            resolveFactSheetScope(request);
            // Resolve schema preset if specified — populate entityTypes/relationshipTypes
            resolveSchemaPreset(request);

            String jobName = request.getName() != null ? request.getName() : "Unified crawl";

            // Submit to resource scheduler if available — the scheduler gates the crawl start,
            // provides queuing, priority, phase-aware GPU yield, and skip-ahead
            if (resourceScheduler != null) {
                String schedulerJobId = "crawl-" + UUID.randomUUID().toString().substring(0, 8);
                try {
                    ai.kompile.app.services.scheduler.ScheduledJob scheduledJob =
                            ai.kompile.app.services.scheduler.ScheduledJob.builder()
                                    .jobId(schedulerJobId)
                                    .jobType("unifiedCrawl")
                                    .description("[CRAWL] " + jobName)
                                    .resourceProfile(ai.kompile.app.services.scheduler.JobResourceProfiles.UNIFIED_CRAWL)
                                    .executor(ctx -> {
                                        // Start crawl INSIDE the executor so the scheduler gates it
                                        UnifiedCrawlJob crawlJob = unifiedCrawlService.startJob(request);
                                        String internalJobId = crawlJob.getJobId();

                                        // Store mapping so both IDs resolve to the same job
                                        schedulerIdToJobId.put(ctx.jobId(), internalJobId);

                                        // Publish to job history using the INTERNAL job ID
                                        // so syncCrawlJobsToHistory() can find it consistently
                                        String historyTaskId = "crawl-" + internalJobId;
                                        if (jobHistoryService != null) {
                                            try {
                                                jobHistoryService.createJob(historyTaskId, "[CRAWL] " + jobName);
                                                jobHistoryService.markJobRunning(historyTaskId);
                                                publishedJobIds.add(internalJobId);
                                            } catch (Exception e) {
                                                log.warn("Failed to publish crawl job to history: {}", e.getMessage());
                                            }
                                        }

                                        // Poll until terminal, forwarding phase transitions
                                        String lastPhase = null;
                                        long crawlDeadline = System.currentTimeMillis() + java.util.concurrent.TimeUnit.HOURS.toMillis(24);
                                        while (System.currentTimeMillis() < crawlDeadline) {
                                            UnifiedCrawlJob.Status status = crawlJob.getStatus().get();
                                            if (status == UnifiedCrawlJob.Status.COMPLETED
                                                    || status == UnifiedCrawlJob.Status.COMPLETED_PENDING_EMBEDDING
                                                    || status == UnifiedCrawlJob.Status.FAILED
                                                    || status == UnifiedCrawlJob.Status.CANCELLED) {
                                                if (status == UnifiedCrawlJob.Status.FAILED) {
                                                    throw new RuntimeException("Crawl failed");
                                                }
                                                break;
                                            }
                                            // Forward phase transitions for GPU yield
                                            String currentPhase = crawlJob.getCurrentPhase().get();
                                            if (currentPhase != null && !currentPhase.equals(lastPhase)) {
                                                var profile = ai.kompile.app.services.scheduler.JobResourceProfiles.UNIFIED_CRAWL;
                                                boolean gpuPhase = profile.phaseRequiresGpu(currentPhase);
                                                long gpuMem = profile.gpuMemoryForPhase(currentPhase);
                                                ctx.phaseCallback().onPhaseTransition(
                                                        ctx.jobId(), currentPhase, gpuPhase, gpuMem);
                                                lastPhase = currentPhase;
                                            }
                                            try {
                                                Thread.sleep(1000);
                                            } catch (InterruptedException ie) {
                                                Thread.currentThread().interrupt();
                                                break;
                                            }
                                        }
                                    })
                                    .metadata(Map.of(
                                            "sourceCount", request.getSources().size(),
                                            "graphExtractionEnabled", request.getGraphExtraction() != null && request.getGraphExtraction().isEnabled(),
                                            "vectorIndexEnabled", request.getVectorIndex() != null && request.getVectorIndex().isEnabled()
                                    ))
                                    .priority(50)
                                    .build();
                    resourceScheduler.submit(scheduledJob);
                    log.info("Crawl job '{}' submitted to resource scheduler (gated)", schedulerJobId);

                    Map<String, Object> response = new LinkedHashMap<>();
                    response.put("jobId", schedulerJobId);
                    response.put("schedulerJobId", schedulerJobId);
                    response.put("status", "QUEUED");
                    response.put("factSheetId", request.getFactSheetId());
                    response.put("sourceCount", request.getSources().size());
                    response.put("graphExtractionEnabled",
                            request.getGraphExtraction() != null && request.getGraphExtraction().isEnabled());
                    response.put("vectorIndexEnabled",
                            request.getVectorIndex() != null && request.getVectorIndex().isEnabled());
                    response.put("scheduled", true);
                    response.put("message", "Unified crawl-to-graph job queued in scheduler");
                    return ResponseEntity.ok(response);
                } catch (Exception e) {
                    log.warn("Failed to submit crawl job to scheduler, falling back to direct start: {}", e.getMessage());
                    // Fall through to direct start
                }
            }

            // Direct start (no scheduler)
            UnifiedCrawlJob job = unifiedCrawlService.startJob(request);

            // Publish to job history using consistent "crawl-{UUID}" format
            if (jobHistoryService != null) {
                try {
                    String historyTaskId = "crawl-" + job.getJobId();
                    jobHistoryService.createJob(historyTaskId, "[CRAWL] " + jobName);
                    jobHistoryService.markJobRunning(historyTaskId);
                    publishedJobIds.add(job.getJobId());
                    log.debug("Published crawl job {} to job history", job.getJobId());
                } catch (Exception e) {
                    log.warn("Failed to publish crawl job to history: {}", e.getMessage());
                }
            }

            Map<String, Object> response = new LinkedHashMap<>();
            response.put("jobId", job.getJobId());
            response.put("status", job.getStatus().get().name());
            response.put("factSheetId", request.getFactSheetId());
            response.put("sourceCount", request.getSources().size());
            response.put("graphExtractionEnabled",
                    request.getGraphExtraction() != null && request.getGraphExtraction().isEnabled());
            response.put("vectorIndexEnabled",
                    request.getVectorIndex() != null && request.getVectorIndex().isEnabled());
            response.put("scheduled", false);
            response.put("message", "Unified crawl-to-graph job started");
            return ResponseEntity.ok(response);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        } catch (Exception e) {
            log.error("Failed to start unified crawl job", e);
            return ResponseEntity.internalServerError()
                    .body(Map.of("error", "Failed to start job: " + e.getMessage()));
        }
    }

    /**
     * Start a unified crawl job from uploaded files.
     * Accepts one or more files via multipart upload and runs the full unified
     * crawl pipeline (chunking, graph extraction, vector indexing) on each file.
     *
     * @param files the files to crawl
     * @param configJson optional JSON string with crawl configuration (name, graphExtraction, vectorIndex, processingRoute, factSheetId)
     */
    @PostMapping("/start-with-files")
    public ResponseEntity<?> startJobWithFiles(
            @RequestParam("files") MultipartFile[] files,
            @RequestParam(name = "config", required = false) String configJson) {
        try {
            if (files == null || files.length == 0) {
                return ResponseEntity.badRequest().body(Map.of("error", "No files provided"));
            }

            // Parse optional config
            UnifiedCrawlRequest request = new UnifiedCrawlRequest();
            if (configJson != null && !configJson.isBlank()) {
                UnifiedCrawlRequest parsed = objectMapper.readValue(configJson, UnifiedCrawlRequest.class);
                request.setName(parsed.getName());
                request.setFactSheetId(parsed.getFactSheetId());
                request.setGraphExtraction(parsed.getGraphExtraction());
                request.setVectorIndex(parsed.getVectorIndex());
                request.setProcessingRoute(parsed.getProcessingRoute());
            }

            if (request.getName() == null || request.getName().isBlank()) {
                request.setName(files.length == 1
                        ? "Crawl: " + files[0].getOriginalFilename()
                        : "Crawl: " + files.length + " documents");
            }

            // Save files and build sources
            List<UnifiedCrawlSource> sources = new ArrayList<>();
            for (MultipartFile file : files) {
                if (file.isEmpty()) continue;

                String originalName = file.getOriginalFilename();
                String sanitized = originalName != null
                        ? originalName.replaceAll("[^a-zA-Z0-9._-]", "_")
                        : "upload_" + UUID.randomUUID();

                Path destination = uploadsPath.resolve(sanitized).normalize();
                if (!destination.startsWith(uploadsPath)) {
                    return ResponseEntity.badRequest()
                            .body(Map.of("error", "Invalid filename: " + originalName));
                }

                // If destination already exists as a directory, remove it
                if (Files.exists(destination) && Files.isDirectory(destination)) {
                    try {
                        Files.walk(destination)
                                .sorted(Comparator.reverseOrder())
                                .forEach(p -> {
                                    try { Files.delete(p); }
                                    catch (Exception e) { log.warn("Failed to delete {}: {}", p, e.getMessage()); }
                                });
                    } catch (Exception e) {
                        log.warn("Failed to walk and delete existing destination directory {}: {}", destination, e.getMessage());
                    }
                }

                try (var in = file.getInputStream();
                     var out = Files.newOutputStream(destination,
                             StandardOpenOption.CREATE,
                             StandardOpenOption.TRUNCATE_EXISTING,
                             StandardOpenOption.WRITE)) {
                    in.transferTo(out);
                }

                sources.add(UnifiedCrawlSource.builder()
                        .label(originalName != null ? originalName : sanitized)
                        .sourceType(DocumentSourceDescriptor.SourceType.FILE)
                        .pathOrUrl(destination.toString())
                        .maxDepth(0)
                        .maxDocuments(1)
                        .build());

                log.info("Saved uploaded file for unified crawl: {} -> {}", originalName, destination);
            }

            if (sources.isEmpty()) {
                return ResponseEntity.badRequest().body(Map.of("error", "All uploaded files were empty"));
            }

            request.setSources(sources);
            resolveFactSheetScope(request);
            resolveSchemaPreset(request);

            UnifiedCrawlJob job = unifiedCrawlService.startJob(request);

            if (jobHistoryService != null) {
                String jobName = request.getName() != null ? request.getName() : "Document crawl";
                jobHistoryService.createJob("crawl-" + job.getJobId(), "[CRAWL] " + jobName);
                jobHistoryService.markJobRunning("crawl-" + job.getJobId());
                publishedJobIds.add(job.getJobId());
            }

            Map<String, Object> response = new LinkedHashMap<>();
            response.put("jobId", job.getJobId());
            response.put("status", job.getStatus().get().name());
            response.put("factSheetId", request.getFactSheetId());
            response.put("sourceCount", sources.size());
            response.put("fileNames", sources.stream()
                    .map(UnifiedCrawlSource::getLabel).collect(Collectors.toList()));
            response.put("graphExtractionEnabled",
                    request.getGraphExtraction() != null && request.getGraphExtraction().isEnabled());
            response.put("vectorIndexEnabled",
                    request.getVectorIndex() != null && request.getVectorIndex().isEnabled());
            response.put("message", "Unified crawl started for " + sources.size() + " file(s)");
            return ResponseEntity.ok(response);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        } catch (Exception e) {
            log.error("Failed to start file-based unified crawl job", e);
            return ResponseEntity.internalServerError()
                    .body(Map.of("error", "Failed to start job: " + e.getMessage()));
        }
    }

    @GetMapping("/jobs")
    public ResponseEntity<List<Map<String, Object>>> listJobs(
            @RequestParam(defaultValue = "true") boolean includeHistory) {
        // Live in-memory jobs
        List<Map<String, Object>> jobs = new ArrayList<>(unifiedCrawlService.getAllJobs().stream()
                .map(this::jobSummary)
                .collect(Collectors.toList()));

        // Merge historical crawl jobs that are no longer in memory
        if (includeHistory && jobHistoryService != null) {
            // Build the dedup set from both the internal jobId AND any scheduler ID already in the
            // live list (e.g. jobs submitted via the scheduler whose in-memory record is still live).
            Set<String> liveJobIds = jobs.stream()
                    .map(m -> (String) m.get("jobId"))
                    .collect(Collectors.toSet());
            // Only treat a scheduler mapping as "live" when its job is actually present in the live
            // list above. Completed jobs whose in-memory record was released keep a stale entry in
            // schedulerIdToJobId; adding those unconditionally polluted the dedup set and caused the
            // terminated job's history record to be skipped, so it vanished from the list entirely.
            Set<String> liveSnapshot = new HashSet<>(liveJobIds);
            schedulerIdToJobId.forEach((schedId, internalId) -> {
                if (liveSnapshot.contains(schedId) || liveSnapshot.contains(internalId)) {
                    liveJobIds.add(schedId);
                    liveJobIds.add(internalId);
                }
            });
            try {
                for (IndexingJobHistory history : jobHistoryService.getCrawlJobs()) {
                    String internalJobId = history.getTaskId().replaceFirst("^crawl-", "");
                    // Try to reconstruct summary from additionalDetails first (richer snapshot)
                    if (history.getAdditionalDetails() != null && !history.getAdditionalDetails().isBlank()) {
                        try {
                            @SuppressWarnings("unchecked")
                            Map<String, Object> snapshot = objectMapper.readValue(
                                    history.getAdditionalDetails(), Map.class);
                            // The snapshot's "jobId" is the internal UUID; but if this was a
                            // scheduler-submitted job the external-facing ID is "schedulerJobId".
                            // Use schedulerJobId as the canonical jobId so the UI sees the same
                            // ID it got from the original /start response.
                            String externalJobId = snapshot.containsKey("schedulerJobId")
                                    ? (String) snapshot.get("schedulerJobId")
                                    : internalJobId;
                            // Skip if already in the live list (either by internal or external ID)
                            if (liveJobIds.contains(externalJobId) || liveJobIds.contains(internalJobId)) continue;
                            snapshot.put("jobId", externalJobId);
                            snapshot.put("status", history.getStatus().name());
                            snapshot.put("fromHistory", true);
                            jobs.add(snapshot);
                            liveJobIds.add(externalJobId);
                            liveJobIds.add(internalJobId);
                            continue;
                        } catch (Exception e) {
                            log.debug("Failed to parse additionalDetails for {}", history.getTaskId());
                        }
                    }
                    // No additionalDetails snapshot: check dedup before falling back
                    if (liveJobIds.contains(internalJobId)) continue;
                    // Surface the same external ID the caller received from /start (the short
                    // schedulerJobId) when this job was scheduler-submitted, so the id form stays
                    // consistent with the snapshot path and the original /start response.
                    String externalJobId = schedulerIdToJobId.entrySet().stream()
                            .filter(e -> internalJobId.equals(e.getValue()))
                            .map(Map.Entry::getKey)
                            .findFirst()
                            .orElse(internalJobId);
                    if (liveJobIds.contains(externalJobId)) continue;
                    // Fallback: basic summary from history fields
                    Map<String, Object> basic = new LinkedHashMap<>();
                    basic.put("jobId", externalJobId);
                    basic.put("name", history.getFileName() != null
                            ? history.getFileName().replaceFirst("^\\[CRAWL\\] ", "") : internalJobId);
                    basic.put("status", history.getStatus().name());
                    basic.put("sourceCount", 0);
                    basic.put("documentsDiscovered", 0);
                    basic.put("documentsLoaded", history.getDocumentsLoaded() != null ? history.getDocumentsLoaded() : 0);
                    basic.put("chunksProcessed", 0);
                    basic.put("chunksCreated", history.getChunksCreated() != null ? history.getChunksCreated() : 0);
                    basic.put("chunksEmbedded", history.getChunksEmbedded() != null ? history.getChunksEmbedded() : 0);
                    basic.put("documentsIndexed", history.getDocumentsIndexed() != null ? history.getDocumentsIndexed() : 0);
                    basic.put("entitiesExtracted", 0);
                    basic.put("relationshipsExtracted", 0);
                    basic.put("errorCount", 0);
                    basic.put("elapsedMs", history.getTotalDurationMs() != null ? history.getTotalDurationMs() : 0);
                    basic.put("progressPercent", history.getProgressPercent() != null ? history.getProgressPercent() : 100);
                    basic.put("createdAt", history.getStartTime());
                    basic.put("startedAt", history.getStartTime());
                    basic.put("completedAt", history.getEndTime());
                    if (history.getErrorMessage() != null) basic.put("errorMessage", history.getErrorMessage());
                    basic.put("fromHistory", true);
                    jobs.add(basic);
                }
            } catch (Exception e) {
                log.debug("Failed to load crawl job history: {}", e.getMessage());
            }
        }
        return ResponseEntity.ok(jobs);
    }

    @GetMapping("/jobs/active")
    public ResponseEntity<List<Map<String, Object>>> listActiveJobs() {
        List<Map<String, Object>> jobs = unifiedCrawlService.getActiveJobs().stream()
                .map(this::jobSummary)
                .collect(Collectors.toList());
        return ResponseEntity.ok(jobs);
    }

    @GetMapping("/jobs/{jobId}")
    public ResponseEntity<?> getJob(@PathVariable String jobId) {
        // Resolve scheduler IDs (e.g., "crawl-55ef8175") to internal UUIDs
        String resolvedId = schedulerIdToJobId.getOrDefault(jobId, jobId);
        return unifiedCrawlService.getJob(resolvedId)
                .map(job -> {
                    refreshMemorySnapshot(job);
                    UnifiedCrawlJob.ProgressSnapshot snapshot = job.toProgressSnapshot();
                    Map<String, Object> result = new LinkedHashMap<>();
                    result.put("jobId", snapshot.getJobId());
                    result.put("name", snapshot.getName());
                    result.put("factSheetId", job.getRequest() != null ? job.getRequest().getFactSheetId() : null);
                    result.put("status", snapshot.getStatus().name());
                    result.put("createdAt", snapshot.getCreatedAt());
                    result.put("startedAt", snapshot.getStartedAt());
                    result.put("completedAt", snapshot.getCompletedAt());
                    result.put("documentsDiscovered", snapshot.getDocumentsDiscovered());
                    result.put("documentsLoaded", snapshot.getDocumentsLoaded());
                    result.put("chunksProcessed", snapshot.getChunksProcessed());
                    result.put("chunksCreated", snapshot.getChunksCreated());
                    result.put("graphChunksProcessed", snapshot.getGraphChunksProcessed());
                    result.put("graphChunksTotal", snapshot.getGraphChunksTotal());
                    result.put("chunksQueuedForEmbedding", snapshot.getChunksQueuedForEmbedding());
                    result.put("chunksEmbedded", snapshot.getChunksEmbedded());
                    result.put("documentsIndexed", snapshot.getDocumentsIndexed());
                    result.put("entitiesExtracted", snapshot.getEntitiesExtracted());
                    result.put("relationshipsExtracted", snapshot.getRelationshipsExtracted());
                    result.put("errorCount", snapshot.getErrorCount());
                    result.put("elapsedMs", snapshot.getElapsedMs());
                    result.put("currentPhase", snapshot.getCurrentPhase());
                    result.put("progressPercent", snapshot.getProgressPercent());
                    result.put("queuePosition", snapshot.getQueuePosition());
                    result.put("activeJobs", snapshot.getActiveJobs());
                    result.put("queuedJobs", snapshot.getQueuedJobs());
                    result.put("maxConcurrentJobs", snapshot.getMaxConcurrentJobs());
                    result.put("queueCapacity", snapshot.getQueueCapacity());
                    result.put("queuedAt", snapshot.getQueuedAt());
                    result.put("memoryUsagePercent", snapshot.getMemoryUsagePercent());
                    result.put("peakMemoryUsagePercent", snapshot.getPeakMemoryUsagePercent());
                    result.put("heapUsedBytes", snapshot.getHeapUsedBytes());
                    result.put("heapMaxBytes", snapshot.getHeapMaxBytes());
                    result.put("nativeMemoryUsagePercent", snapshot.getNativeMemoryUsagePercent());
                    result.put("peakNativeMemoryUsagePercent", snapshot.getPeakNativeMemoryUsagePercent());
                    result.put("nativePhysicalBytes", snapshot.getNativePhysicalBytes());
                    result.put("peakNativePhysicalBytes", snapshot.getPeakNativePhysicalBytes());
                    result.put("nativeTotalBytes", snapshot.getNativeTotalBytes());
                    result.put("nativeMaxPhysicalBytes", snapshot.getNativeMaxPhysicalBytes());
                    result.put("directBufferBytes", snapshot.getDirectBufferBytes());
                    Map<String, Long> rss = currentProcessRssBytes();
                    result.put("processRssBytes", rss.getOrDefault("self", 0L));
                    result.put("childProcessRssBytes", rss.getOrDefault("children", 0L));
                    result.put("embeddingSubprocessRssBytes", rss.getOrDefault("embeddingSubprocess", 0L));
                    result.put("otherChildProcessRssBytes", rss.getOrDefault("otherChildren", 0L));
                    result.put("processTreeRssBytes", rss.getOrDefault("tree", 0L));
                    result.put("vectorBatchesTotal", snapshot.getVectorBatchesTotal());
                    result.put("vectorBatchesCompleted", snapshot.getVectorBatchesCompleted());
                    result.put("currentBatchSize", snapshot.getCurrentBatchSize());
                    result.put("embeddingBatchSize", snapshot.getEmbeddingBatchSize());
                    result.put("embeddingModelOptimalBatchSize", snapshot.getEmbeddingModelOptimalBatchSize());
                    result.put("embeddingModelMaxBatchSize", snapshot.getEmbeddingModelMaxBatchSize());
                    result.put("embeddingSingleDspPlan", snapshot.isEmbeddingSingleDspPlan());
                    result.put("embeddingDspPlanBatchSize", snapshot.getEmbeddingDspPlanBatchSize());
                    result.put("currentBatchStep", snapshot.getCurrentBatchStep());
                    if (snapshot.getCurrentFile() != null) {
                        result.put("currentFile", snapshot.getCurrentFile());
                    }
                    if (snapshot.getErrors() != null && !snapshot.getErrors().isEmpty()) {
                        result.put("errors", snapshot.getErrors());
                    }
                    if (snapshot.getErrorMessage() != null) {
                        result.put("errorMessage", snapshot.getErrorMessage());
                    }
                    if (snapshot.getRecentEvents() != null && !snapshot.getRecentEvents().isEmpty()) {
                        result.put("recentEvents", snapshot.getRecentEvents());
                    }
                    if (snapshot.getPipelineSteps() != null && !snapshot.getPipelineSteps().isEmpty()) {
                        result.put("pipelineSteps", snapshot.getPipelineSteps().stream()
                                .map(this::pipelineStepMap)
                                .collect(Collectors.toList()));
                    }
                    if (snapshot.getDocumentProgress() != null && !snapshot.getDocumentProgress().isEmpty()) {
                        result.put("documentProgress", snapshot.getDocumentProgress().stream()
                                .map(this::documentProgressMap)
                                .collect(Collectors.toList()));
                    }
                    result.put("sources", snapshot.getSourceProgress() != null
                            ? snapshot.getSourceProgress().stream().map(this::sourceProgressMap).collect(Collectors.toList())
                            : List.of());

                    // Include recently discovered items for live discovery feed
                    if (snapshot.getRecentlyDiscoveredItems() != null && !snapshot.getRecentlyDiscoveredItems().isEmpty()) {
                        result.put("recentlyDiscoveredItems", snapshot.getRecentlyDiscoveredItems().stream().map(di -> {
                            Map<String, Object> dim = new LinkedHashMap<>();
                            dim.put("name", di.getName());
                            dim.put("sourceType", di.getSourceType());
                            dim.put("sourceLabel", di.getSourceLabel());
                            dim.put("discoveredAt", di.getDiscoveredAt());
                            return dim;
                        }).collect(Collectors.toList()));
                    }

                    // Include job type flags and LLM info at top level for the runtime grid
                    if (job.getRequest() != null) {
                        result.put("graphExtractionEnabled",
                                job.getRequest().getGraphExtraction() != null && job.getRequest().getGraphExtraction().isEnabled());
                        result.put("vectorIndexEnabled",
                                job.getRequest().getVectorIndex() != null && job.getRequest().getVectorIndex().isEnabled());
                        if (job.getRequest().getGraphExtraction() != null) {
                            if (job.getRequest().getGraphExtraction().getLlmProvider() != null) {
                                result.put("llmProvider", job.getRequest().getGraphExtraction().getLlmProvider());
                            }
                            if (job.getRequest().getGraphExtraction().getModelName() != null) {
                                result.put("llmModel", job.getRequest().getGraphExtraction().getModelName());
                            }
                        }
                    }

                    // Include the original request configuration so the UI can show job type details
                    if (job.getRequest() != null) {
                        result.put("requestConfig", buildRequestConfigMap(job.getRequest()));
                    }

                    // Include graph summary. Persisted JPA stats are the source of truth for the
                    // full graph; resultGraph is optional and only retained for explicitly configured
                    // diagnostic runs because it duplicates the persisted graph in memory.
                    Long factSheetId = job.getRequest() != null ? job.getRequest().getFactSheetId() : null;
                    if (job.getResultGraph() != null) {
                        Map<String, Object> graphSummary = buildLiveGraphSummary(factSheetId,
                                UnifiedCrawlJob.Status.RUNNING == snapshot.getStatus());
                        long extractedEntities = job.getResultGraph().getEntities() != null
                                ? job.getResultGraph().getEntities().size()
                                : 0;
                        long extractedRelationships = job.getResultGraph().getRelationships() != null
                                ? job.getResultGraph().getRelationships().size()
                                : 0;
                        graphSummary.put("extractedEntityCount", extractedEntities);
                        graphSummary.put("extractedRelationshipCount", extractedRelationships);
                        graphSummary.put("resultGraphRetained", true);
                        Object persistedEntityCount = graphSummary.get("entityCount");
                        Object persistedRelationshipCount = graphSummary.get("relationshipCount");
                        if (!(persistedEntityCount instanceof Number)
                                || (((Number) persistedEntityCount).longValue() == 0L && extractedEntities > 0L)) {
                            graphSummary.put("entityCount", extractedEntities);
                        }
                        if (!(persistedRelationshipCount instanceof Number)
                                || (((Number) persistedRelationshipCount).longValue() == 0L && extractedRelationships > 0L)) {
                            graphSummary.put("relationshipCount", extractedRelationships);
                        }

                        // Entity type breakdown
                        if (job.getResultGraph().getEntities() != null) {
                            Map<String, Long> typeCounts = job.getResultGraph().getEntities().stream()
                                    .filter(e -> e.getType() != null)
                                    .collect(Collectors.groupingBy(
                                            e -> e.getType(), Collectors.counting()));
                            graphSummary.putIfAbsent("entityTypeCounts", typeCounts);
                        }

                        result.put("graph", graphSummary);
                    } else if (knowledgeGraphService != null) {
                        // Provide persisted graph stats for running and completed jobs. This keeps the
                        // UI independent from the optional in-memory resultGraph buffer.
                        try {
                            Map<String, Object> graphSummary = buildLiveGraphSummary(factSheetId,
                                    UnifiedCrawlJob.Status.RUNNING == snapshot.getStatus());
                            graphSummary.put("extractedEntityCount", snapshot.getEntitiesExtracted());
                            graphSummary.put("extractedRelationshipCount", snapshot.getRelationshipsExtracted());
                            graphSummary.put("resultGraphRetained", false);
                            // Overlay in-memory entity/relationship type counts from the crawl job
                            if (snapshot.getEntityTypeCounts() != null && !snapshot.getEntityTypeCounts().isEmpty()) {
                                graphSummary.putIfAbsent("entityTypeCounts", snapshot.getEntityTypeCounts());
                            }
                            if (snapshot.getRelationshipTypeCounts() != null && !snapshot.getRelationshipTypeCounts().isEmpty()) {
                                graphSummary.putIfAbsent("relationshipTypeCounts", snapshot.getRelationshipTypeCounts());
                            }
                            result.put("graph", graphSummary);
                        } catch (Exception e) {
                            log.debug("Failed to get live graph stats for job {}: {}", jobId, e.getMessage());
                        }
                    }

                    return ResponseEntity.ok(result);
                })
                .orElse(ResponseEntity.notFound().build());
    }

    /**
     * Get a historical crawl job's full detail from persisted additionalDetails JSON.
     * Returns the same shape as getJob() so the frontend can render historical jobs
     * identically to live ones. Falls back to basic IndexingJobHistory fields if no
     * additionalDetails snapshot is available.
     */
    @GetMapping("/jobs/{jobId}/history")
    public ResponseEntity<?> getJobFromHistory(@PathVariable String jobId) {
        if (jobHistoryService == null) {
            return ResponseEntity.status(503).body(Map.of("error", "Job history service not available"));
        }
        // Resolve scheduler IDs to internal UUIDs via the in-memory map (populated while app is live).
        String resolvedId = schedulerIdToJobId.getOrDefault(jobId, jobId);
        String historyTaskId = "crawl-" + resolvedId;

        // After a restart the in-memory schedulerIdToJobId map is empty.  If the direct lookup
        // ("crawl-crawl-af676985") misses, fall back to scanning crawl job history for a record
        // whose additionalDetails snapshot contains a matching "schedulerJobId" field.
        java.util.function.Supplier<java.util.Optional<IndexingJobHistory>> historyLookup = () -> {
            java.util.Optional<IndexingJobHistory> direct = jobHistoryService.getJob(historyTaskId);
            if (direct.isPresent()) {
                return direct;
            }
            // Only attempt the scan when the input looks like an external scheduler ID
            // (i.e., it was not already resolved to a UUID by the in-memory map).
            if (resolvedId.equals(jobId)) {
                // jobId was not resolved — scan history for a snapshot whose schedulerJobId matches
                try {
                    for (IndexingJobHistory h : jobHistoryService.getCrawlJobs()) {
                        if (h.getAdditionalDetails() == null || h.getAdditionalDetails().isBlank()) continue;
                        try {
                            @SuppressWarnings("unchecked")
                            Map<String, Object> snap = objectMapper.readValue(h.getAdditionalDetails(), Map.class);
                            if (jobId.equals(snap.get("schedulerJobId"))) {
                                return java.util.Optional.of(h);
                            }
                        } catch (Exception ignored) { /* skip malformed entries */ }
                    }
                } catch (Exception ignored) { /* scan failure is non-fatal */ }
            }
            return java.util.Optional.empty();
        };

        return historyLookup.get()
                .map(history -> {
                    if (history.getAdditionalDetails() != null && !history.getAdditionalDetails().isBlank()) {
                        try {
                            @SuppressWarnings("unchecked")
                            Map<String, Object> snapshot = objectMapper.readValue(
                                    history.getAdditionalDetails(), Map.class);
                            // Overlay the current history status in case it was updated after snapshot
                            snapshot.put("status", history.getStatus().name());
                            snapshot.put("fromHistory", true);
                            // Expose resume lineage so the frontend can merge parent transcripts
                            if (history.getResumedFromTaskId() != null) {
                                snapshot.put("resumedFromTaskId", history.getResumedFromTaskId());
                            }
                            return ResponseEntity.ok(snapshot);
                        } catch (Exception e) {
                            log.warn("Failed to parse additionalDetails for {}: {}", historyTaskId, e.getMessage());
                        }
                    }
                    // Fallback: return basic fields from the history record
                    Map<String, Object> basic = new LinkedHashMap<>();
                    basic.put("jobId", jobId);
                    basic.put("name", history.getFileName() != null
                            ? history.getFileName().replaceFirst("^\\[CRAWL\\] ", "") : jobId);
                    basic.put("status", history.getStatus().name());
                    basic.put("createdAt", history.getStartTime());
                    basic.put("startedAt", history.getStartTime());
                    basic.put("completedAt", history.getEndTime());
                    basic.put("elapsedMs", history.getTotalDurationMs() != null ? history.getTotalDurationMs() : 0);
                    basic.put("documentsLoaded", history.getDocumentsLoaded() != null ? history.getDocumentsLoaded() : 0);
                    basic.put("chunksCreated", history.getChunksCreated() != null ? history.getChunksCreated() : 0);
                    basic.put("chunksEmbedded", history.getChunksEmbedded() != null ? history.getChunksEmbedded() : 0);
                    basic.put("documentsIndexed", history.getDocumentsIndexed() != null ? history.getDocumentsIndexed() : 0);
                    basic.put("progressPercent", history.getProgressPercent() != null ? history.getProgressPercent() : 100);
                    if (history.getErrorMessage() != null) basic.put("errorMessage", history.getErrorMessage());
                    basic.put("fromHistory", true);
                    basic.put("sources", List.of());
                    // Expose resume lineage so the frontend can merge parent transcripts
                    if (history.getResumedFromTaskId() != null) {
                        basic.put("resumedFromTaskId", history.getResumedFromTaskId());
                    }
                    return ResponseEntity.ok(basic);
                })
                .orElse(ResponseEntity.notFound().build());
    }

    @PostMapping("/jobs/{jobId}/cancel")
    public ResponseEntity<?> cancelJob(@PathVariable String jobId) {
        // Resolve scheduler IDs to internal UUIDs
        String resolvedId = schedulerIdToJobId.getOrDefault(jobId, jobId);
        IngestPhase cancelPhase = unifiedCrawlService.getJob(resolvedId)
                .map(UnifiedCrawlJob::toProgressSnapshot)
                .map(this::mapCrawlPhaseToIngestPhase)
                .orElse(IngestPhase.INDEXING);

        if (!unifiedCrawlService.cancelJob(resolvedId)) {
            return ResponseEntity.badRequest().body(Map.of("error", "Job not found or already finished"));
        }

        if (jobHistoryService != null) {
            try {
                String historyTaskId = "crawl-" + resolvedId;
                jobHistoryService.markJobCancelled(historyTaskId, cancelPhase, "User cancelled");
                publishedJobIds.remove(resolvedId);
                publishedCrawlLogEventKeys.remove("crawl-" + resolvedId);
                crawlLogSequences.remove("crawl-" + resolvedId);
            } catch (Exception e) {
                log.warn("Failed to mark crawl job {} as cancelled in history", resolvedId, e);
            }
        }

        return ResponseEntity.ok(Map.of("message", "Job cancelled", "jobId", resolvedId));
    }

    @PostMapping("/jobs/{jobId}/retry")
    public ResponseEntity<?> retryJob(@PathVariable String jobId,
                                       @RequestBody(required = false) Map<String, Object> body) {
        // Resolve scheduler IDs to internal UUIDs
        String resolvedId = schedulerIdToJobId.getOrDefault(jobId, jobId);
        String retryPhase = body != null && body.get("retryPhase") instanceof String
                ? (String) body.get("retryPhase") : null;
        @SuppressWarnings("unchecked")
        List<String> documentKeys = body != null && body.get("documentKeys") instanceof List
                ? (List<String>) body.get("documentKeys") : null;

        return unifiedCrawlService.retryJob(resolvedId, retryPhase, documentKeys)
                .map(retryJob -> {
                    // Register new retry job in history
                    if (jobHistoryService != null) {
                        try {
                            String historyTaskId = "crawl-" + retryJob.getJobId();
                            jobHistoryService.createJob(historyTaskId,
                                    retryJob.getRequest().getName() != null
                                            ? retryJob.getRequest().getName() : "Retry of " + jobId);
                        } catch (Exception e) {
                            log.warn("Failed to create history for retry job {}", retryJob.getJobId(), e);
                        }
                    }
                    return ResponseEntity.ok(Map.of(
                            "message", "Retry job started",
                            "originalJobId", jobId,
                            "retryJobId", retryJob.getJobId(),
                            "retryPhase", retryPhase != null ? retryPhase : "ALL_FAILED",
                            "documentsToRetry", retryJob.getRequest().getRetryDocumentKeys().size()
                    ));
                })
                .orElse(ResponseEntity.badRequest().body(Map.of(
                        "error", "Job not found or has no failed documents to retry")));
    }

    /** Catalog of pipeline steps (id, name, type, dependencies, flags) for the step-selection UI. */
    @GetMapping("/steps")
    public ResponseEntity<List<Map<String, Object>>> listPipelineSteps() {
        List<Map<String, Object>> steps = new ArrayList<>();
        for (CrawlPipelineStepRegistry.StepDescriptor d : CrawlPipelineStepRegistry.all()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", d.id());
            m.put("displayName", d.displayName());
            m.put("stepType", d.stepType());
            m.put("dependsOn", new ArrayList<>(d.hardDependsOn()));
            m.put("chunkConsumerOnly", d.chunkConsumerOnly());
            m.put("chunkProducer", d.chunkProducer());
            m.put("foundational", d.foundational());
            m.put("archivable", d.archivable());
            steps.add(m);
        }
        return ResponseEntity.ok(steps);
    }

    /** List crawl jobs that have archived steps on disk and can be resumed (including after a restart). */
    @GetMapping("/jobs/resumable")
    public ResponseEntity<List<Map<String, Object>>> listResumableJobs() {
        List<Map<String, Object>> out = new ArrayList<>();
        for (CrawlStepArchiveService.ResumableCrawlJob j : unifiedCrawlService.listResumableCrawlJobs()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("jobId", j.jobId());
            m.put("name", j.name());
            m.put("factSheetId", j.factSheetId());
            m.put("archivedSteps", j.archivedSteps());
            m.put("archivedAt", j.archivedAt());
            out.add(m);
        }
        return ResponseEntity.ok(out);
    }

    /** Archive a step's pending inputs to disk so it can be run later (currently VECTOR_INDEXING). */
    @PostMapping("/jobs/{jobId}/steps/{stepId}/archive")
    public ResponseEntity<?> archiveStep(@PathVariable String jobId, @PathVariable String stepId) {
        String resolvedId = schedulerIdToJobId.getOrDefault(jobId, jobId);
        String dir = unifiedCrawlService.archiveStep(resolvedId, stepId);
        if (dir == null) {
            return ResponseEntity.badRequest().body(Map.of(
                    "error", "Nothing to archive for step " + stepId + " on job " + jobId));
        }
        return ResponseEntity.ok(Map.of("jobId", resolvedId, "stepId", stepId, "archiveDir", dir,
                "message", "Step archived"));
    }

    /** Run a previously archived (or deferred) step now. */
    @PostMapping("/jobs/{jobId}/steps/{stepId}/run")
    public ResponseEntity<?> runArchivedStep(@PathVariable String jobId, @PathVariable String stepId) {
        String resolvedId = schedulerIdToJobId.getOrDefault(jobId, jobId);
        try {
            int processed = unifiedCrawlService.resumeArchivedStep(resolvedId, stepId);
            if (processed < 0) {
                return ResponseEntity.badRequest().body(Map.of(
                        "error", "Step " + stepId + " is not archived/resumable for job " + jobId));
            }
            return ResponseEntity.ok(Map.of("jobId", resolvedId, "stepId", stepId,
                    "itemsProcessed", processed, "message", "Step resumed"));
        } catch (Exception e) {
            log.error("Failed to resume step {} for job {}", stepId, resolvedId, e);
            return ResponseEntity.internalServerError().body(Map.of("error", String.valueOf(e.getMessage())));
        }
    }

    @PostMapping("/jobs/cleanup")
    public ResponseEntity<?> cleanupJobs() {
        // Sync terminal states to job history BEFORE removing in-memory jobs,
        // otherwise the history records stay stuck as RUNNING forever
        if (jobHistoryService != null) {
            for (UnifiedCrawlJob job : unifiedCrawlService.getAllJobs()) {
                UnifiedCrawlJob.Status status = job.getStatus().get();
                if (status == UnifiedCrawlJob.Status.COMPLETED
                        || status == UnifiedCrawlJob.Status.FAILED
                        || status == UnifiedCrawlJob.Status.CANCELLED) {
                    String historyTaskId = "crawl-" + job.getJobId();
                    try {
                        // Persist final snapshot before marking terminal state
                        UnifiedCrawlJob.ProgressSnapshot snap = job.toProgressSnapshot();
                        try {
                            Map<String, Object> fullSnapshot = buildJobDetailMap(job, snap);
                            String snapshotJson = objectMapper.writeValueAsString(fullSnapshot);
                            jobHistoryService.updateAdditionalDetails(historyTaskId, snapshotJson);
                        } catch (Exception jsonEx) {
                            log.debug("Failed to serialize final snapshot for {}: {}", job.getJobId(), jsonEx.getMessage());
                        }

                        if (status == UnifiedCrawlJob.Status.COMPLETED) {
                            jobHistoryService.markJobCompleted(historyTaskId);
                        } else if (status == UnifiedCrawlJob.Status.FAILED) {
                            jobHistoryService.markJobFailed(historyTaskId,
                                    IngestPhase.INDEXING,
                                    job.getErrorMessage() != null ? job.getErrorMessage() : "Crawl job failed",
                                    null, FailureReason.UNKNOWN);
                        } else {
                            jobHistoryService.markJobCancelled(historyTaskId,
                                    IngestPhase.INDEXING, "User cancelled");
                        }
                        publishedJobIds.remove(job.getJobId());
                        publishedCrawlLogEventKeys.remove("crawl-" + job.getJobId());
                        crawlLogSequences.remove("crawl-" + job.getJobId());
                    } catch (Exception e) {
                        log.debug("Failed to sync crawl job {} to history before cleanup", job.getJobId(), e);
                    }
                }
            }
        }
        return ResponseEntity.ok(Map.of("removed", unifiedCrawlService.cleanupJobs()));
    }

    @GetMapping("/source-types")
    public ResponseEntity<List<UnifiedCrawlService.AvailableSourceType>> getSourceTypes() {
        return ResponseEntity.ok(unifiedCrawlService.getAvailableSourceTypes());
    }

    /**
     * Get live graph statistics from the vector store (single source of truth for the graph).
     * Node and edge counts are read directly from the matrix/vector store where all graph
     * writes land — no JPA queries for graph operations.
     */
    @GetMapping("/graph-stats")
    public ResponseEntity<?> getLiveGraphStats(@RequestParam(required = false, name = "factSheetId") Long factSheetId) {
        if (knowledgeGraphService == null) {
            return ResponseEntity.ok(Map.of("available", false, "message", "KnowledgeGraphService not available"));
        }
        try {
            if (factSheetId == null && factSheetService != null) {
                factSheetId = factSheetService.getActiveSheet().getId();
            }
            Map<String, Object> stats = buildLiveGraphSummary(factSheetId, true);
            stats.put("available", true);
            // Overlay in-memory type counts from the active running job if available
            if (!stats.containsKey("entityTypeCounts") || !stats.containsKey("relationshipTypeCounts")) {
                unifiedCrawlService.getActiveJobs().stream()
                        .filter(j -> j.getStatus().get() == UnifiedCrawlJob.Status.RUNNING)
                        .findFirst()
                        .ifPresent(j -> {
                            Map<String, Long> etCounts = j.snapshotEntityTypeCounts();
                            if (!etCounts.isEmpty()) stats.putIfAbsent("entityTypeCounts", etCounts);
                            Map<String, Long> rtCounts = j.snapshotRelationshipTypeCounts();
                            if (!rtCounts.isEmpty()) stats.putIfAbsent("relationshipTypeCounts", rtCounts);
                        });
            }
            return ResponseEntity.ok(stats);
        } catch (Exception e) {
            log.error("Failed to get live graph stats", e);
            return ResponseEntity.internalServerError()
                    .body(Map.of("available", false, "error", e.getMessage()));
        }
    }

    // ---- Job History Sync ----

    /**
     * Periodically sync crawl job status to the indexing job history.
     * Runs every 15 seconds to keep the job history up to date.
     */
    @Scheduled(fixedRate = CRAWL_SYNC_INTERVAL_MS)
    public void syncCrawlJobsToHistory() {
        if (jobHistoryService == null || publishedJobIds.isEmpty()) return;

        for (String jobId : new ArrayList<>(publishedJobIds)) {
            try {
                var optJob = unifiedCrawlService.getJob(jobId);
                if (optJob.isEmpty()) {
                    // In-memory job was cleaned up (e.g., by cleanupJobs()) but history
                    // was never updated. Mark it completed so the UI doesn't show zombies.
                    String historyTaskId = "crawl-" + jobId;
                    log.debug("Crawl job {} no longer in memory, marking history as completed", jobId);
                    jobHistoryService.markJobCompleted(historyTaskId);
                    publishedJobIds.remove(jobId);
                    publishedCrawlLogEventKeys.remove("crawl-" + jobId);
                    crawlLogSequences.remove("crawl-" + jobId);
                    continue;
                }
                UnifiedCrawlJob job = optJob.get();
                String historyTaskId = "crawl-" + jobId;
                UnifiedCrawlJob.ProgressSnapshot snap = job.toProgressSnapshot();

                // Update stats
                jobHistoryService.updateJobStats(historyTaskId,
                        snap.getDocumentsLoaded(),
                        snap.getChunksCreated() > 0 ? snap.getChunksCreated() : snap.getChunksProcessed(),
                        snap.getChunksEmbedded(),
                        snap.getDocumentsIndexed());
                jobHistoryService.updateJobProgress(historyTaskId,
                        mapCrawlPhaseToIngestPhase(snap),
                        snap.getProgressPercent());
                jobHistoryService.updateMemoryUsage(historyTaskId, snap.getMemoryUsagePercent());
                jobHistoryService.updateJobParameters(historyTaskId,
                        "UnifiedCrawl",
                        null,
                        "batch=" + snap.getEmbeddingBatchSize(),
                        "VectorStore",
                        null,
                        null,
                        snap.getEmbeddingBatchSize() > 0 ? snap.getEmbeddingBatchSize() : null,
                        snap.getMaxConcurrentJobs(),
                        false,
                        true);

                // Persist full snapshot as JSON so historical jobs retain rich detail
                try {
                    Map<String, Object> fullSnapshot = buildJobDetailMap(job, snap);
                    String snapshotJson = objectMapper.writeValueAsString(fullSnapshot);
                    jobHistoryService.updateAdditionalDetails(historyTaskId, snapshotJson);
                } catch (Exception jsonEx) {
                    log.debug("Failed to serialize crawl snapshot for {}: {}", jobId, jsonEx.getMessage());
                }

                // Keep the on-disk archive manifest's snapshot fresh so a job with archived steps stays
                // durably resumable (including after a crash/restart).
                if (crawlStepArchiveService != null && hasArchivedStep(job)) {
                    try {
                        crawlStepArchiveService.refreshManifest(job);
                    } catch (Exception ex) {
                        log.debug("Failed to refresh archive manifest for {}: {}", jobId, ex.getMessage());
                    }
                }

                // Check terminal states
                if (snap.getStatus() == UnifiedCrawlJob.Status.COMPLETED) {
                    jobHistoryService.markJobCompleted(historyTaskId);
                    publishedJobIds.remove(jobId);
                    publishedCrawlLogEventKeys.remove(historyTaskId);
                    crawlLogSequences.remove(historyTaskId);
                } else if (snap.getStatus() == UnifiedCrawlJob.Status.FAILED) {
                    jobHistoryService.markJobFailed(historyTaskId,
                            IngestPhase.INDEXING,
                            snap.getErrorMessage() != null ? snap.getErrorMessage() : "Crawl job failed",
                            null, FailureReason.UNKNOWN);
                    publishedJobIds.remove(jobId);
                    publishedCrawlLogEventKeys.remove(historyTaskId);
                    crawlLogSequences.remove(historyTaskId);
                } else if (snap.getStatus() == UnifiedCrawlJob.Status.CANCELLED) {
                    jobHistoryService.markJobCancelled(historyTaskId,
                            IngestPhase.INDEXING, "User cancelled");
                    publishedJobIds.remove(jobId);
                    publishedCrawlLogEventKeys.remove(historyTaskId);
                    crawlLogSequences.remove(historyTaskId);
                }
            } catch (Exception e) {
                log.warn("Failed to sync crawl job {} to history: {}", jobId, e.getMessage(), e);
            }
        }
    }

    /** True if any of the job's pipeline steps is currently ARCHIVED (awaiting a later run). */
    private boolean hasArchivedStep(UnifiedCrawlJob job) {
        if (job.getPipelineSteps() == null) {
            return false;
        }
        for (UnifiedCrawlJob.PipelineStepProgress s : job.getPipelineSteps()) {
            if (s.getStatus().get() == UnifiedCrawlJob.PipelineStepStatus.ARCHIVED) {
                return true;
            }
        }
        return false;
    }

    /**
     * Mirror unified crawl stage events into the existing job log infrastructure.
     * The frontend can then use the same live log viewer as subprocess/ingest jobs.
     */
    @Scheduled(fixedRate = CRAWL_LOG_PUBLISH_INTERVAL_MS)
    public void publishCrawlEventsToJobLogs() {
        if ((jobLogService == null || !jobLogService.isEnabled()) && messagingTemplate == null) {
            return;
        }

        for (UnifiedCrawlJob job : unifiedCrawlService.getAllJobs()) {
            try {
                UnifiedCrawlJob.ProgressSnapshot snapshot = job.toProgressSnapshot();

                // Push structured progress to the standard crawl progress topic
                // so the Crawlers UI and any WebSocket subscriber gets real-time updates
                if (messagingTemplate != null) {
                    Map<String, Object> progressUpdate = jobSummary(job);
                    messagingTemplate.convertAndSend("/topic/crawl/progress", progressUpdate);
                    messagingTemplate.convertAndSend("/topic/unified-crawl/progress", progressUpdate);
                }

                if (snapshot.getRecentEvents() == null || snapshot.getRecentEvents().isEmpty()) {
                    continue;
                }
                String taskId = "crawl-" + job.getJobId();
                Set<String> seen = publishedCrawlLogEventKeys.computeIfAbsent(taskId, ignored -> ConcurrentHashMap.newKeySet());
                AtomicLong sequence = crawlLogSequences.computeIfAbsent(taskId, ignored -> {
                    long startingCount = 0L;
                    try {
                        if (jobLogService != null && jobLogService.isEnabled()) {
                            startingCount = jobLogService.getLogCount(taskId);
                        }
                    } catch (Exception e) {
                        log.debug("Could not read existing crawl log count for {}: {}", taskId, e.getMessage());
                    }
                    return new AtomicLong(startingCount);
                });

                for (UnifiedCrawlJob.StageEvent event : snapshot.getRecentEvents()) {
                    String eventKey = crawlEventKey(event);
                    if (!seen.add(eventKey)) {
                        continue;
                    }

                    LogLevel level = parseLogLevel(event.getLevel());
                    String message = formatCrawlLogMessage(event);
                    if (jobLogService != null && jobLogService.isEnabled()) {
                        jobLogService.logSystem(taskId, level, message);
                    }
                    long seq = sequence.incrementAndGet();
                    if (messagingTemplate != null) {
                        messagingTemplate.convertAndSend("/topic/ingest/" + taskId + "/logs",
                                new IngestProgressUpdate.IngestLogEntry(
                                        taskId,
                                        level.name(),
                                        "SYSTEM",
                                        message,
                                        null,
                                        event.getTimestamp() != null ? event.getTimestamp() : java.time.Instant.now(),
                                        seq));
                        messagingTemplate.convertAndSend("/topic/ingest/logs",
                                new IngestProgressUpdate.IngestLogEntry(
                                        taskId,
                                        level.name(),
                                        "SYSTEM",
                                        message,
                                        null,
                                        event.getTimestamp() != null ? event.getTimestamp() : java.time.Instant.now(),
                                        seq));
                    }
                }

            } catch (Exception e) {
                log.warn("Failed to publish crawl job logs: {}", e.getMessage(), e);
            }
        }
    }

    // ---- Helpers ----

    /**
     * If the request's graphExtraction config has a schemaPresetId set,
     * resolve it via GraphSchemaPresetService and populate entityTypes/relationshipTypes.
     */
    private void resolveSchemaPreset(UnifiedCrawlRequest request) {
        GraphExtractionConfig ge = request.getGraphExtraction();
        if (ge == null || ge.getSchemaPresetId() == null || ge.getSchemaPresetId().isEmpty()) {
            return;
        }
        if (schemaPresetService == null) {
            log.warn("Schema preset '{}' requested but GraphSchemaPresetService is not available",
                    ge.getSchemaPresetId());
            return;
        }
        schemaPresetService.getPresetTypeNames(ge.getSchemaPresetId()).ifPresentOrElse(
                typeNames -> {
                    List<String> entityTypes = typeNames.get("entityTypes");
                    List<String> relationshipTypes = typeNames.get("relationshipTypes");
                    if (entityTypes != null && !entityTypes.isEmpty()
                            && (ge.getEntityTypes() == null || ge.getEntityTypes().isEmpty())) {
                        ge.setEntityTypes(entityTypes);
                    }
                    if (relationshipTypes != null && !relationshipTypes.isEmpty()
                            && (ge.getRelationshipTypes() == null || ge.getRelationshipTypes().isEmpty())) {
                        ge.setRelationshipTypes(relationshipTypes);
                    }
                    log.info("Resolved schema preset '{}': {} entity types, {} relationship types",
                            ge.getSchemaPresetId(),
                            entityTypes != null ? entityTypes.size() : 0,
                            relationshipTypes != null ? relationshipTypes.size() : 0);
                },
                () -> log.warn("Schema preset '{}' not found", ge.getSchemaPresetId())
        );
    }

    private void resolveFactSheetScope(UnifiedCrawlRequest request) {
        if (request == null || request.getFactSheetId() != null || factSheetService == null) {
            return;
        }
        try {
            // Resolve by name first if provided
            if (request.getFactSheetName() != null && !request.getFactSheetName().isBlank()) {
                factSheetService.getSheetByName(request.getFactSheetName()).ifPresent(sheet -> {
                    request.setFactSheetId(sheet.getId());
                    log.info("Scoped unified crawl '{}' to fact sheet '{}' (id={})",
                            request.getName(), sheet.getName(), sheet.getId());
                });
                if (request.getFactSheetId() != null) return;
                log.warn("Fact sheet '{}' not found, falling back to active sheet",
                        request.getFactSheetName());
            }
            FactSheet activeSheet = factSheetService.getActiveSheet();
            if (activeSheet != null) {
                request.setFactSheetId(activeSheet.getId());
                log.info("Scoped unified crawl '{}' to active fact sheet {}",
                        request.getName(), activeSheet.getId());
            }
        } catch (Exception e) {
            log.warn("Could not resolve active fact sheet for unified crawl: {}", e.getMessage());
        }
    }

    private Map<String, Object> jobSummary(UnifiedCrawlJob job) {
        refreshMemorySnapshot(job);

        Map<String, Object> m = new LinkedHashMap<>();
        // Include scheduler ID if this job was submitted through the scheduler
        String schedulerId = schedulerIdToJobId.entrySet().stream()
                .filter(e -> e.getValue().equals(job.getJobId()))
                .map(Map.Entry::getKey)
                .findFirst().orElse(null);
        // Use schedulerJobId as the canonical jobId so the UI sees the same ID it received from
        // /start (which returns schedulerJobId, not the internal UUID).  Store the internal UUID
        // as internalJobId so callers can still resolve both forms.
        if (schedulerId != null) {
            m.put("jobId", schedulerId);
            m.put("schedulerJobId", schedulerId);
            m.put("internalJobId", job.getJobId());
        } else {
            m.put("jobId", job.getJobId());
        }
        m.put("name", job.getRequest() != null ? job.getRequest().getName() : null);
        m.put("factSheetId", job.getRequest() != null ? job.getRequest().getFactSheetId() : null);
        m.put("status", job.getStatus().get().name());
        m.put("sourceCount", job.getRequest() != null ? job.getRequest().getSources().size() : 0);
        m.put("documentsDiscovered", job.getDocumentsDiscovered().get());
        m.put("documentsLoaded", job.getDocumentsLoaded().get());
        m.put("chunksProcessed", job.getChunksProcessed().get());
        m.put("chunksCreated", job.getChunksCreated().get());
        m.put("graphChunksProcessed", job.getGraphChunksProcessed().get());
        m.put("graphChunksTotal", job.getGraphChunksTotal().get());
        m.put("chunksQueuedForEmbedding", job.getChunksQueuedForEmbedding().get());
        m.put("chunksEmbedded", job.getChunksEmbedded().get());
        m.put("documentsIndexed", job.getDocumentsIndexed().get());
        m.put("entitiesExtracted", job.getEntitiesExtracted().get());
        m.put("relationshipsExtracted", job.getRelationshipsExtracted().get());
        m.put("errorCount", job.getErrorCount().get());
        if (job.getErrorMessage() != null) {
            m.put("errorMessage", job.getErrorMessage());
        }
        if (job.getErrors() != null && !job.getErrors().isEmpty()) {
            m.put("errors", new ArrayList<>(job.getErrors()));
        }
        m.put("elapsedMs", job.elapsedMs());
        m.put("currentPhase", job.getCurrentPhase().get());
        m.put("progressPercent", job.getProgressPercent().get());
        m.put("queuePosition", job.getQueuePosition().get());
        m.put("activeJobs", job.getActiveJobs().get());
        m.put("queuedJobs", job.getQueuedJobs().get());
        m.put("maxConcurrentJobs", job.getMaxConcurrentJobs().get());
        m.put("queueCapacity", job.getQueueCapacity().get());
        m.put("queuedAt", job.getQueuedAt());
        m.put("memoryUsagePercent", job.getMemoryUsagePercent().get());
        m.put("peakMemoryUsagePercent", job.getPeakMemoryUsagePercent().get());
        m.put("heapUsedBytes", job.getHeapUsedBytes().get());
        m.put("heapMaxBytes", job.getHeapMaxBytes().get());
        m.put("nativeMemoryUsagePercent", job.getNativeMemoryUsagePercent().get());
        m.put("peakNativeMemoryUsagePercent", job.getPeakNativeMemoryUsagePercent().get());
        m.put("nativePhysicalBytes", job.getNativePhysicalBytes().get());
        m.put("peakNativePhysicalBytes", job.getPeakNativePhysicalBytes().get());
        m.put("nativeTotalBytes", job.getNativeTotalBytes().get());
        m.put("nativeMaxPhysicalBytes", job.getNativeMaxPhysicalBytes().get());
        m.put("directBufferBytes", job.getDirectBufferBytes().get());
        Map<String, Long> rss = currentProcessRssBytes();
        m.put("processRssBytes", rss.getOrDefault("self", 0L));
        m.put("childProcessRssBytes", rss.getOrDefault("children", 0L));
        m.put("embeddingSubprocessRssBytes", rss.getOrDefault("embeddingSubprocess", 0L));
        m.put("otherChildProcessRssBytes", rss.getOrDefault("otherChildren", 0L));
        m.put("processTreeRssBytes", rss.getOrDefault("tree", 0L));
        m.put("vectorBatchesTotal", job.getVectorBatchesTotal().get());
        m.put("vectorBatchesCompleted", job.getVectorBatchesCompleted().get());
        m.put("currentBatchSize", job.getCurrentBatchSize().get());
        m.put("embeddingBatchSize", job.getEmbeddingBatchSize().get());
        m.put("embeddingModelOptimalBatchSize", job.getEmbeddingModelOptimalBatchSize().get());
        m.put("embeddingModelMaxBatchSize", job.getEmbeddingModelMaxBatchSize().get());
        m.put("embeddingSingleDspPlan", job.getEmbeddingSingleDspPlan().get());
        m.put("embeddingDspPlanBatchSize", job.getEmbeddingDspPlanBatchSize().get());
        m.put("currentBatchStep", job.getCurrentBatchStep().get());
        if (job.getPipelineSteps() != null && !job.getPipelineSteps().isEmpty()) {
            m.put("pipelineSteps", job.getPipelineSteps().stream()
                    .map(step -> pipelineStepMap(step.toSnapshot()))
                    .collect(Collectors.toList()));
        }
        String cf = job.getCurrentFile().get();
        if (cf != null) m.put("currentFile", cf);
        m.put("createdAt", job.getCreatedAt());
        m.put("startedAt", job.getStartedAt());
        m.put("completedAt", job.getCompletedAt());

        // Include job type flags from the original request
        if (job.getRequest() != null) {
            m.put("graphExtractionEnabled",
                    job.getRequest().getGraphExtraction() != null && job.getRequest().getGraphExtraction().isEnabled());
            m.put("vectorIndexEnabled",
                    job.getRequest().getVectorIndex() != null && job.getRequest().getVectorIndex().isEnabled());
            if (job.getRequest().getGraphExtraction() != null && job.getRequest().getGraphExtraction().getLlmProvider() != null) {
                m.put("llmProvider", job.getRequest().getGraphExtraction().getLlmProvider());
            }
            if (job.getRequest().getGraphExtraction() != null && job.getRequest().getGraphExtraction().getModelName() != null) {
                m.put("llmModel", job.getRequest().getGraphExtraction().getModelName());
            }
        }

        // Populate live graph node/edge counts from the JPA repositories via KnowledgeGraphService.
        // These reflect the actual persisted graph state, so they show 8000+ nodes even for
        // completed jobs where resultGraph only tracks in-memory LLM extraction counters.
        long graphNodeCount = 0L;
        long graphEdgeCount = 0L;
        if (knowledgeGraphService != null) {
            try {
                Long factSheetId = job.getRequest() != null ? job.getRequest().getFactSheetId() : null;
                Map<String, Object> stats = factSheetId != null && factSheetGraphService != null
                        ? factSheetGraphService.getGraphStatistics(factSheetId)
                        : knowledgeGraphService.getGraphStatistics();
                graphNodeCount = statCount(stats, "totalNodes", "nodesByType");
                // Try "relationshipTypeCounts" (vector-store-backed key from FactSheetGraphServiceImpl)
                // then legacy "edgesByType" — both are now sourced from the vector store.
                graphEdgeCount = statCount(stats, "totalEdges", "relationshipTypeCounts");
                if (graphEdgeCount == 0) {
                    graphEdgeCount = statCount(stats, "totalEdges", "edgesByType");
                }
            } catch (Exception e) {
                log.debug("Failed to fetch live graph stats for job summary {}: {}", job.getJobId(), e.getMessage());
            }
        }
        m.put("graphNodeCount", graphNodeCount);
        m.put("graphEdgeCount", graphEdgeCount);

        // Include in-memory entity/relationship type breakdown for real-time UI
        Map<String, Long> entityTypeCounts = job.snapshotEntityTypeCounts();
        if (!entityTypeCounts.isEmpty()) {
            m.put("entityTypeCounts", entityTypeCounts);
        }
        Map<String, Long> relationshipTypeCounts = job.snapshotRelationshipTypeCounts();
        if (!relationshipTypeCounts.isEmpty()) {
            m.put("relationshipTypeCounts", relationshipTypeCounts);
        }

        // Include per-source progress so list cards show individual source status
        if (job.getSourceProgress() != null && !job.getSourceProgress().isEmpty()) {
            m.put("sources", job.getSourceProgress().stream().map(sp -> {
                Map<String, Object> sm = new LinkedHashMap<>();
                sm.put("label", sp.getLabel());
                sm.put("sourceType", sp.getSourceType());
                sm.put("pathOrUrl", sp.getPathOrUrl());
                sm.put("status", sp.getStatus() != null ? sp.getStatus().name() : "PENDING");
                sm.put("documentsDiscovered", sp.getDocumentsDiscovered());
                sm.put("documentsLoaded", sp.getDocumentsLoaded());
                sm.put("chunksCreated", sp.getChunksCreated());
                sm.put("entitiesExtracted", sp.getEntitiesExtracted());
                sm.put("relationshipsExtracted", sp.getRelationshipsExtracted());
                if (sp.getCurrentPhase() != null) sm.put("currentPhase", sp.getCurrentPhase());
                if (sp.getCurrentItem() != null) sm.put("currentItem", sp.getCurrentItem());
                if (sp.getErrorMessage() != null) sm.put("errorMessage", sp.getErrorMessage());
                return sm;
            }).collect(Collectors.toList()));
        }

        // Include top-10 most recently active documents so list cards can show a compact activity feed
        if (job.getDocumentProgress() != null && !job.getDocumentProgress().isEmpty()) {
            List<Map<String, Object>> topDocs = job.getDocumentProgress().values().stream()
                    .filter(dp -> dp != null)
                    .sorted(Comparator
                            .comparing((UnifiedCrawlJob.DocumentProgress dp) ->
                                    dp.getUpdatedAt() != null ? dp.getUpdatedAt() : java.time.Instant.EPOCH)
                            .reversed())
                    .limit(10)
                    .map(dp -> {
                        Map<String, Object> dm = new LinkedHashMap<>();
                        dm.put("documentKey", dp.getDocumentKey());
                        dm.put("fileName", dp.getFileName());
                        dm.put("status", dp.getStatus());
                        dm.put("phase", dp.getPhase());
                        dm.put("contentType", dp.getContentType());
                        dm.put("chunksCreated", dp.getChunksCreated());
                        dm.put("chunksEmbedded", dp.getChunksEmbedded());
                        dm.put("entitiesExtracted", dp.getEntitiesExtracted());
                        if (dp.getErrorMessage() != null) dm.put("errorMessage", dp.getErrorMessage());
                        dm.put("updatedAt", dp.getUpdatedAt());
                        return dm;
                    })
                    .collect(Collectors.toList());
            if (!topDocs.isEmpty()) {
                m.put("recentDocuments", topDocs);
            }
        }

        // Include recent events in summary (last 15) so the polling UI can show a live activity feed
        List<UnifiedCrawlJob.StageEvent> events = job.getRecentEvents();
        if (events != null && !events.isEmpty()) {
            int fromIndex = Math.max(0, events.size() - 15);
            m.put("recentEvents", events.subList(fromIndex, events.size()).stream()
                    .map(e -> {
                        Map<String, Object> em = new LinkedHashMap<>();
                        em.put("timestamp", e.getTimestamp());
                        em.put("phase", e.getPhase());
                        em.put("level", e.getLevel());
                        em.put("message", e.getMessage());
                        if (e.getDetails() != null) em.put("details", e.getDetails());
                        if (e.getProgressPercent() != null) em.put("progressPercent", e.getProgressPercent());
                        return em;
                    })
                    .collect(Collectors.toList()));
        }

        // Include recently discovered items for live discovery feed
        List<UnifiedCrawlJob.DiscoveredItem> discovered = job.getRecentlyDiscoveredItems();
        if (discovered != null && !discovered.isEmpty()) {
            m.put("recentlyDiscoveredItems", discovered.stream().map(di -> {
                Map<String, Object> dim = new LinkedHashMap<>();
                dim.put("name", di.getName());
                dim.put("sourceType", di.getSourceType());
                dim.put("sourceLabel", di.getSourceLabel());
                dim.put("discoveredAt", di.getDiscoveredAt());
                return dim;
            }).collect(Collectors.toList()));
        }

        return m;
    }

    /**
     * Build the full job detail map for persistence into additionalDetails.
     * Produces the same JSON shape as the getJob() endpoint so the frontend
     * can render historical jobs identically to live ones.
     */
    private Map<String, Object> buildJobDetailMap(UnifiedCrawlJob job, UnifiedCrawlJob.ProgressSnapshot snap) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("jobId", snap.getJobId());
        m.put("name", snap.getName());
        m.put("factSheetId", job.getRequest() != null ? job.getRequest().getFactSheetId() : null);
        // Persist the external-facing scheduler ID so the LIST endpoint can find this record after restart.
        // Without this, the history record's taskId is "crawl-{UUID}" (internal), but the caller knows the
        // job as "crawl-{8hexchars}" (schedulerJobId). Storing schedulerJobId here lets the list reconstruct
        // the correct external jobId and add it to the live-dedup set.
        String schedulerId = schedulerIdToJobId.entrySet().stream()
                .filter(e -> e.getValue().equals(snap.getJobId()))
                .map(Map.Entry::getKey)
                .findFirst().orElse(null);
        if (schedulerId != null) {
            m.put("schedulerJobId", schedulerId);
        }
        m.put("status", snap.getStatus().name());
        m.put("createdAt", snap.getCreatedAt());
        m.put("startedAt", snap.getStartedAt());
        m.put("completedAt", snap.getCompletedAt());
        m.put("documentsDiscovered", snap.getDocumentsDiscovered());
        m.put("documentsLoaded", snap.getDocumentsLoaded());
        m.put("chunksProcessed", snap.getChunksProcessed());
        m.put("chunksCreated", snap.getChunksCreated());
        m.put("graphChunksProcessed", snap.getGraphChunksProcessed());
        m.put("graphChunksTotal", snap.getGraphChunksTotal());
        m.put("chunksQueuedForEmbedding", snap.getChunksQueuedForEmbedding());
        m.put("chunksEmbedded", snap.getChunksEmbedded());
        m.put("documentsIndexed", snap.getDocumentsIndexed());
        m.put("entitiesExtracted", snap.getEntitiesExtracted());
        m.put("relationshipsExtracted", snap.getRelationshipsExtracted());
        m.put("errorCount", snap.getErrorCount());
        m.put("elapsedMs", snap.getElapsedMs());
        m.put("currentPhase", snap.getCurrentPhase());
        m.put("progressPercent", snap.getProgressPercent());
        m.put("queuePosition", snap.getQueuePosition());
        m.put("activeJobs", snap.getActiveJobs());
        m.put("queuedJobs", snap.getQueuedJobs());
        m.put("maxConcurrentJobs", snap.getMaxConcurrentJobs());
        m.put("queueCapacity", snap.getQueueCapacity());
        m.put("queuedAt", snap.getQueuedAt());
        m.put("memoryUsagePercent", snap.getMemoryUsagePercent());
        m.put("peakMemoryUsagePercent", snap.getPeakMemoryUsagePercent());
        m.put("heapUsedBytes", snap.getHeapUsedBytes());
        m.put("heapMaxBytes", snap.getHeapMaxBytes());
        m.put("nativeMemoryUsagePercent", snap.getNativeMemoryUsagePercent());
        m.put("peakNativeMemoryUsagePercent", snap.getPeakNativeMemoryUsagePercent());
        m.put("nativePhysicalBytes", snap.getNativePhysicalBytes());
        m.put("peakNativePhysicalBytes", snap.getPeakNativePhysicalBytes());
        m.put("nativeTotalBytes", snap.getNativeTotalBytes());
        m.put("nativeMaxPhysicalBytes", snap.getNativeMaxPhysicalBytes());
        m.put("directBufferBytes", snap.getDirectBufferBytes());
        m.put("vectorBatchesTotal", snap.getVectorBatchesTotal());
        m.put("vectorBatchesCompleted", snap.getVectorBatchesCompleted());
        m.put("currentBatchSize", snap.getCurrentBatchSize());
        m.put("embeddingBatchSize", snap.getEmbeddingBatchSize());
        m.put("embeddingModelOptimalBatchSize", snap.getEmbeddingModelOptimalBatchSize());
        m.put("embeddingModelMaxBatchSize", snap.getEmbeddingModelMaxBatchSize());
        m.put("embeddingSingleDspPlan", snap.isEmbeddingSingleDspPlan());
        m.put("embeddingDspPlanBatchSize", snap.getEmbeddingDspPlanBatchSize());
        m.put("currentBatchStep", snap.getCurrentBatchStep());
        if (snap.getCurrentFile() != null) m.put("currentFile", snap.getCurrentFile());
        if (snap.getErrors() != null && !snap.getErrors().isEmpty()) m.put("errors", snap.getErrors());
        if (snap.getErrorMessage() != null) m.put("errorMessage", snap.getErrorMessage());
        if (snap.getRecentEvents() != null && !snap.getRecentEvents().isEmpty()) {
            m.put("recentEvents", snap.getRecentEvents());
        }
        if (snap.getPipelineSteps() != null && !snap.getPipelineSteps().isEmpty()) {
            m.put("pipelineSteps", snap.getPipelineSteps().stream()
                    .map(this::pipelineStepMap).collect(Collectors.toList()));
        }
        if (snap.getDocumentProgress() != null && !snap.getDocumentProgress().isEmpty()) {
            m.put("documentProgress", snap.getDocumentProgress().stream()
                    .map(this::documentProgressMap).collect(Collectors.toList()));
        }
        m.put("sources", snap.getSourceProgress() != null
                ? snap.getSourceProgress().stream().map(this::sourceProgressMap).collect(Collectors.toList())
                : List.of());
        if (snap.getRecentlyDiscoveredItems() != null && !snap.getRecentlyDiscoveredItems().isEmpty()) {
            m.put("recentlyDiscoveredItems", snap.getRecentlyDiscoveredItems().stream().map(di -> {
                Map<String, Object> dim = new LinkedHashMap<>();
                dim.put("name", di.getName());
                dim.put("sourceType", di.getSourceType());
                dim.put("sourceLabel", di.getSourceLabel());
                dim.put("discoveredAt", di.getDiscoveredAt());
                return dim;
            }).collect(Collectors.toList()));
        }
        if (snap.getEntityTypeCounts() != null && !snap.getEntityTypeCounts().isEmpty()) {
            m.put("entityTypeCounts", snap.getEntityTypeCounts());
        }
        if (snap.getRelationshipTypeCounts() != null && !snap.getRelationshipTypeCounts().isEmpty()) {
            m.put("relationshipTypeCounts", snap.getRelationshipTypeCounts());
        }
        // Work-stealing stats
        m.put("workStealCount", snap.getWorkStealCount());
        m.put("workStealFailures", snap.getWorkStealFailures());
        m.put("localDispatchCount", snap.getLocalDispatchCount());
        m.put("workImbalanceRatioX100", snap.getWorkImbalanceRatioX100());
        // Dynamic batch sizing
        m.put("adaptiveBatchSize", snap.getAdaptiveBatchSize());
        m.put("batchSizeAdjustments", snap.getBatchSizeAdjustments());
        if (snap.getLastBatchAdjustDirection() != null) m.put("lastBatchAdjustDirection", snap.getLastBatchAdjustDirection());
        if (snap.getLastBatchAdjustReason() != null) m.put("lastBatchAdjustReason", snap.getLastBatchAdjustReason());
        m.put("batchEmaLatencyMsX100", snap.getBatchEmaLatencyMsX100());
        m.put("peakThroughputX100", snap.getPeakThroughputX100());
        // Token budget
        m.put("totalInputTokens", snap.getTotalInputTokens());
        m.put("totalOutputTokens", snap.getTotalOutputTokens());
        m.put("estimatedCostCentsX100", snap.getEstimatedCostCentsX100());
        if (snap.getBackendStats() != null && !snap.getBackendStats().isEmpty()) {
            m.put("backendStats", snap.getBackendStats());
        }
        // Workload rerouting
        m.put("reroutedItems", snap.getReroutedItems());
        m.put("droppedItems", snap.getDroppedItems());
        if (snap.getRecentRerouteEvents() != null && !snap.getRecentRerouteEvents().isEmpty()) {
            m.put("recentRerouteEvents", snap.getRecentRerouteEvents());
        }
        // Retry / fallback
        m.put("retriedBatches", snap.getRetriedBatches());
        m.put("retriedItems", snap.getRetriedItems());
        m.put("deadLetterCount", snap.getDeadLetterCount());
        m.put("backendsCoolingDown", snap.getBackendsCoolingDown());
        if (snap.getRecentRetryEvents() != null && !snap.getRecentRetryEvents().isEmpty()) {
            m.put("recentRetryEvents", snap.getRecentRetryEvents());
        }
        // LLM call observability
        m.put("llmCallsTotal", snap.getLlmCallsTotal());
        m.put("llmCallsSucceeded", snap.getLlmCallsSucceeded());
        m.put("llmCallsFailed", snap.getLlmCallsFailed());
        m.put("llmCallsTimedOut", snap.getLlmCallsTimedOut());
        m.put("llmCallsRateLimited", snap.getLlmCallsRateLimited());
        m.put("llmCallsCircuitBroken", snap.getLlmCallsCircuitBroken());
        m.put("llmCallEmaLatencyMsX100", snap.getLlmCallEmaLatencyMsX100());
        m.put("llmCallPeakLatencyMs", snap.getLlmCallPeakLatencyMs());
        if (snap.getRecentLlmCalls() != null && !snap.getRecentLlmCalls().isEmpty()) {
            m.put("recentLlmCalls", snap.getRecentLlmCalls());
        }
        // Job type flags from request
        if (job.getRequest() != null) {
            m.put("graphExtractionEnabled",
                    job.getRequest().getGraphExtraction() != null && job.getRequest().getGraphExtraction().isEnabled());
            m.put("vectorIndexEnabled",
                    job.getRequest().getVectorIndex() != null && job.getRequest().getVectorIndex().isEnabled());
            if (job.getRequest().getGraphExtraction() != null) {
                if (job.getRequest().getGraphExtraction().getLlmProvider() != null) {
                    m.put("llmProvider", job.getRequest().getGraphExtraction().getLlmProvider());
                }
                if (job.getRequest().getGraphExtraction().getModelName() != null) {
                    m.put("llmModel", job.getRequest().getGraphExtraction().getModelName());
                }
            }
            m.put("requestConfig", buildRequestConfigMap(job.getRequest()));
        }
        // Mark as historical snapshot
        m.put("fromHistory", true);
        return m;
    }

    private void refreshMemorySnapshot(UnifiedCrawlJob job) {
        if (job == null) {
            return;
        }
        try {
            Runtime runtime = Runtime.getRuntime();
            long heapUsed = runtime.totalMemory() - runtime.freeMemory();
            long heapMax = runtime.maxMemory();
            int heapPercent = heapMax > 0 ? (int) Math.round(heapUsed * 100.0 / heapMax) : 0;
            job.getHeapUsedBytes().set(heapUsed);
            job.getHeapMaxBytes().set(heapMax);
            job.getMemoryUsagePercent().set(heapPercent);
            job.getPeakMemoryUsagePercent().accumulateAndGet(heapPercent, Math::max);

            long physicalBytes = Pointer.physicalBytes();
            long totalBytes = Pointer.totalBytes();
            long maxPhysicalBytes = Pointer.maxPhysicalBytes();
            int nativePercent = maxPhysicalBytes > 0
                    ? (int) Math.round(physicalBytes * 100.0 / maxPhysicalBytes)
                    : 0;
            job.getNativePhysicalBytes().set(physicalBytes);
            job.getNativeTotalBytes().set(totalBytes);
            job.getNativeMaxPhysicalBytes().set(maxPhysicalBytes);
            job.getNativeMemoryUsagePercent().set(nativePercent);
            job.getPeakNativeMemoryUsagePercent().accumulateAndGet(nativePercent, Math::max);
            job.getPeakNativePhysicalBytes().accumulateAndGet(physicalBytes, Math::max);
            job.getDirectBufferBytes().set(currentDirectBufferBytes());
        } catch (Throwable t) {
            log.debug("Unable to refresh unified crawl memory snapshot: {}", t.getMessage());
        }
    }

    private long currentDirectBufferBytes() {
        long total = 0L;
        for (BufferPoolMXBean pool : ManagementFactory.getPlatformMXBeans(BufferPoolMXBean.class)) {
            if ("direct".equalsIgnoreCase(pool.getName()) || "mapped".equalsIgnoreCase(pool.getName())) {
                total += Math.max(0L, pool.getMemoryUsed());
            }
        }
        return total;
    }

    private Map<String, Long> currentProcessRssBytes() {
        long self = readProcessRssBytes(ProcessHandle.current().pid());
        long children = 0L;
        long embeddingSubprocess = 0L;
        long otherChildren = 0L;
        for (ProcessHandle handle : ProcessHandle.current().descendants().toList()) {
            long rss = readProcessRssBytes(handle.pid());
            children += rss;
            if (isEmbeddingSubprocess(handle)) {
                embeddingSubprocess += rss;
            } else {
                otherChildren += rss;
            }
        }
        return Map.of(
                "self", self,
                "children", children,
                "embeddingSubprocess", embeddingSubprocess,
                "otherChildren", otherChildren,
                "tree", self + children
        );
    }

    private boolean isEmbeddingSubprocess(ProcessHandle handle) {
        return handle.info()
                .commandLine()
                .or(() -> handle.info().command())
                .map(command -> command.contains("EmbeddingSubprocessMain")
                        || command.contains("ai.kompile.embedding.anserini.subprocess"))
                .orElse(false);
    }

    private long readProcessRssBytes(long pid) {
        Path status = Path.of("/proc", Long.toString(pid), "status");
        if (!Files.isReadable(status)) {
            return 0L;
        }
        try {
            for (String line : Files.readAllLines(status)) {
                if (line.startsWith("VmRSS:")) {
                    String[] parts = line.trim().split("\\s+");
                    if (parts.length >= 2) {
                        return Long.parseLong(parts[1]) * 1024L;
                    }
                }
            }
        } catch (Exception e) {
            log.debug("Unable to read RSS for process {}: {}", pid, e.getMessage());
        }
        return 0L;
    }

    private Map<String, Object> buildRequestConfigMap(UnifiedCrawlRequest request) {
        Map<String, Object> config = new LinkedHashMap<>();
        config.put("factSheetId", request.getFactSheetId());

        // Sources overview
        List<Map<String, Object>> sourceSummaries = new ArrayList<>();
        if (request.getSources() != null) {
            for (UnifiedCrawlSource src : request.getSources()) {
                Map<String, Object> s = new LinkedHashMap<>();
                s.put("label", src.getLabel());
                s.put("sourceType", src.getSourceType() != null ? src.getSourceType().name() : null);
                s.put("pathOrUrl", src.getPathOrUrl());
                s.put("maxDepth", src.getMaxDepth());
                s.put("maxDocuments", src.getMaxDocuments());
                if (src.getIncludePatterns() != null && !src.getIncludePatterns().isEmpty())
                    s.put("includePatterns", src.getIncludePatterns());
                if (src.getExcludePatterns() != null && !src.getExcludePatterns().isEmpty())
                    s.put("excludePatterns", src.getExcludePatterns());
                if (src.getAllowedContentTypes() != null && !src.getAllowedContentTypes().isEmpty())
                    s.put("allowedContentTypes", src.getAllowedContentTypes());
                sourceSummaries.add(s);
            }
        }
        config.put("sources", sourceSummaries);

        // Graph extraction config
        GraphExtractionConfig ge = request.getGraphExtraction();
        if (ge != null) {
            Map<String, Object> graph = new LinkedHashMap<>();
            graph.put("enabled", ge.isEnabled());
            graph.put("llmProvider", ge.getLlmProvider());
            if (ge.getModelName() != null) graph.put("modelName", ge.getModelName());
            if (ge.getEntityTypes() != null && !ge.getEntityTypes().isEmpty())
                graph.put("entityTypes", ge.getEntityTypes());
            if (ge.getRelationshipTypes() != null && !ge.getRelationshipTypes().isEmpty())
                graph.put("relationshipTypes", ge.getRelationshipTypes());
            graph.put("schemaMode", ge.getSchemaMode() != null ? ge.getSchemaMode().name() : null);
            graph.put("temperature", ge.getTemperature());
            graph.put("maxTokens", ge.getMaxTokens());
            graph.put("entityResolution", ge.isEntityResolution());
            graph.put("entityResolutionSimilarityThreshold", ge.getEntityResolutionSimilarityThreshold());
            graph.put("entityResolutionUseEmbeddings", ge.isEntityResolutionUseEmbeddings());
            graph.put("entityResolutionEmbeddingThreshold", ge.getEntityResolutionEmbeddingThreshold());
            graph.put("minConfidence", ge.getMinConfidence());
            if (ge.getSchemaPresetId() != null) graph.put("schemaPresetId", ge.getSchemaPresetId());
            config.put("graphExtraction", graph);
        }

        // Vector index config
        VectorIndexConfig vi = request.getVectorIndex();
        if (vi != null) {
            Map<String, Object> vector = new LinkedHashMap<>();
            vector.put("enabled", vi.isEnabled());
            if (vi.getCollectionName() != null) vector.put("collectionName", vi.getCollectionName());
            if (vi.getChunkerName() != null) vector.put("chunkerName", vi.getChunkerName());
            if (vi.getChunkSize() != null) vector.put("chunkSize", vi.getChunkSize());
            if (vi.getChunkOverlap() != null) vector.put("chunkOverlap", vi.getChunkOverlap());
            if (vi.getEmbeddingBatchSize() != null) vector.put("embeddingBatchSize", vi.getEmbeddingBatchSize());
            if (vi.getMaxEmbeddingBatchSize() != null) vector.put("maxEmbeddingBatchSize", vi.getMaxEmbeddingBatchSize());
            vector.put("adaptiveBatching", vi.isAdaptiveBatching());
            config.put("vectorIndex", vector);
        }

        return config;
    }

    private Map<String, Object> buildLiveGraphSummary(boolean live) {
        return buildLiveGraphSummary(null, live);
    }

    private Map<String, Object> buildLiveGraphSummary(Long factSheetId, boolean live) {
        Map<String, Object> graphSummary = new LinkedHashMap<>();
        if (knowledgeGraphService == null) {
            graphSummary.put("entityCount", 0L);
            graphSummary.put("relationshipCount", 0L);
            graphSummary.put("live", live);
            return graphSummary;
        }

        // Both FactSheetGraphServiceImpl and MatrixKnowledgeGraphService (via getGraphStatistics)
        // are now vector-store-only — the SINGLE SOURCE OF TRUTH for all graph counts.
        Map<String, Object> liveStats = factSheetId != null && factSheetGraphService != null
                ? factSheetGraphService.getGraphStatistics(factSheetId)
                : knowledgeGraphService.getGraphStatistics();
        graphSummary.put("factSheetId", factSheetId);
        Map<String, Long> nodesByType = numericMap(liveStats.get("nodesByType"));
        graphSummary.put("entityCount", liveStats.getOrDefault("entityCount", nodesByType.getOrDefault("ENTITY", 0L)));
        // relationshipCount: prefer "totalEdges" (set by both stat providers from the vector store).
        // statCount checks "totalEdges" first, then falls back to summing "relationshipTypeCounts"
        // (the key FactSheetGraphServiceImpl now sets) or the old "edgesByType" key.
        long relationshipCount = statCount(liveStats, "totalEdges", "relationshipTypeCounts");
        if (relationshipCount == 0) {
            relationshipCount = statCount(liveStats, "totalEdges", "edgesByType");
        }
        graphSummary.put("relationshipCount", relationshipCount);
        graphSummary.put("totalNodeCount", liveStats.getOrDefault("totalNodes", statCount(liveStats, "totalNodes", "nodesByType")));
        graphSummary.put("documentCount", liveStats.getOrDefault("documentCount", nodesByType.getOrDefault("DOCUMENT", 0L)));
        graphSummary.put("snippetCount", liveStats.getOrDefault("snippetCount", nodesByType.getOrDefault("SNIPPET", 0L)));
        graphSummary.put("tableCount", liveStats.getOrDefault("tableCount", nodesByType.getOrDefault("TABLE", 0L)));
        graphSummary.put("live", live);

        // Collect edge-type breakdown from any of the three possible key formats the
        // stat providers use: "edges_<type>" (MatrixKnowledgeGraphService global stats),
        // "relationshipTypeCounts" (FactSheetGraphServiceImpl, from vector store), or
        // legacy "edgesByType".  All are now vector-store-sourced.
        Map<String, Object> edgeTypeCounts = liveStats.entrySet().stream()
                .filter(entry -> entry.getKey() != null && entry.getKey().startsWith("edges_"))
                .filter(entry -> entry.getValue() instanceof Number)
                .collect(Collectors.toMap(
                        entry -> entry.getKey().substring("edges_".length()).toUpperCase(Locale.ROOT),
                        Map.Entry::getValue,
                        (a, b) -> a,
                        LinkedHashMap::new));
        if (edgeTypeCounts.isEmpty()) {
            edgeTypeCounts.putAll(numericMap(liveStats.get("relationshipTypeCounts")));
        }
        if (edgeTypeCounts.isEmpty()) {
            edgeTypeCounts.putAll(numericMap(liveStats.get("edgesByType")));
        }
        if (!edgeTypeCounts.isEmpty()) {
            graphSummary.put("edgeTypeCounts", edgeTypeCounts);
            graphSummary.put("relationshipTypeCounts", edgeTypeCounts);
        }

        if (factSheetId != null) {
            Object topConcepts = liveStats.get("topConcepts");
            if (topConcepts instanceof List<?> concepts && !concepts.isEmpty()) {
                List<Map<String, Object>> topList = concepts.stream()
                        .filter(Map.class::isInstance)
                        .map(Map.class::cast)
                        .map(concept -> {
                            Map<String, Object> e = new LinkedHashMap<>();
                            e.put("name", concept.get("name"));
                            e.put("type", "ENTITY");
                            e.put("connectionCount", concept.getOrDefault("count", 0));
                            return e;
                        })
                        .collect(Collectors.toList());
                graphSummary.put("topEntities", topList);
            }
            return graphSummary;
        }

        try {
            List<GraphNode> topEntities = knowledgeGraphService.searchNodes("", NodeLevel.ENTITY, 200);
            if (topEntities != null && !topEntities.isEmpty()) {
                List<Map<String, Object>> topList = topEntities.stream()
                        .filter(n -> !isTableCellNode(n))
                        .sorted((a, b) -> Integer.compare(
                                b.getEdgeCount() != null ? b.getEdgeCount() : 0,
                                a.getEdgeCount() != null ? a.getEdgeCount() : 0))
                        .limit(15)
                        .map(n -> {
                            Map<String, Object> e = new LinkedHashMap<>();
                            e.put("name", n.getTitle());
                            e.put("type", extractEntityType(n));
                            e.put("nodeId", n.getNodeId());
                            e.put("connectionCount", n.getEdgeCount() != null ? n.getEdgeCount() : 0);
                            return e;
                        })
                        .collect(Collectors.toList());
                graphSummary.put("topEntities", topList);

                Map<String, Long> typeCounts = topEntities.stream()
                        .collect(Collectors.groupingBy(
                                this::extractEntityType,
                                LinkedHashMap::new,
                                Collectors.counting()));
                graphSummary.put("entityTypeCounts", typeCounts);
            }
        } catch (Exception e) {
            log.debug("searchNodes unavailable during live graph summary (likely DB contention): {}", e.getMessage());
        }
        return graphSummary;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Long> numericMap(Object value) {
        if (!(value instanceof Map<?, ?> raw)) {
            return Map.of();
        }
        Map<String, Long> result = new LinkedHashMap<>();
        raw.forEach((key, val) -> {
            if (key != null && val instanceof Number number) {
                result.put(key.toString(), number.longValue());
            }
        });
        return result;
    }

    private long statCount(Map<String, Object> stats, String directKey, String groupedKey) {
        Object direct = stats.get(directKey);
        if (direct instanceof Number number) {
            return number.longValue();
        }
        return numericMap(stats.get(groupedKey)).values().stream()
                .mapToLong(Long::longValue)
                .sum();
    }

    private Map<String, Object> pipelineStepMap(UnifiedCrawlJob.PipelineStepSnapshot step) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("stepId", step.getStepId());
        m.put("displayName", step.getDisplayName());
        m.put("stepType", step.getStepType());
        m.put("status", step.getStatus() != null ? step.getStatus().name() : "PENDING");
        m.put("progressPercent", step.getProgressPercent());
        m.put("totalItems", step.getTotalItems());
        m.put("completedItems", step.getCompletedItems());
        m.put("failedItems", step.getFailedItems());
        m.put("activeTasks", step.getActiveTasks());
        m.put("totalBatches", step.getTotalBatches());
        m.put("completedBatches", step.getCompletedBatches());
        m.put("currentBatchSize", step.getCurrentBatchSize());
        m.put("elapsedMs", step.getElapsedMs());
        if (step.getCurrentItem() != null) m.put("currentItem", step.getCurrentItem());
        if (step.getMessage() != null) m.put("message", step.getMessage());
        if (step.getStartedAt() != null) m.put("startedAt", step.getStartedAt());
        if (step.getCompletedAt() != null) m.put("completedAt", step.getCompletedAt());
        if (step.getLastUpdatedAt() != null) m.put("lastUpdatedAt", step.getLastUpdatedAt());
        return m;
    }

    private Map<String, Object> documentProgressMap(UnifiedCrawlJob.DocumentProgress document) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("documentKey", document.getDocumentKey());
        m.put("fileName", document.getFileName());
        m.put("sourcePath", document.getSourcePath());
        m.put("sourceType", document.getSourceType());
        m.put("contentType", document.getContentType());
        m.put("loaderName", document.getLoaderName());
        m.put("phase", document.getPhase());
        m.put("status", document.getStatus());
        m.put("chunksCreated", document.getChunksCreated());
        m.put("chunksEmbedded", document.getChunksEmbedded());
        m.put("chunksIndexed", document.getChunksIndexed());
        m.put("entitiesExtracted", document.getEntitiesExtracted());
        m.put("relationshipsExtracted", document.getRelationshipsExtracted());
        m.put("graphNodesCreated", document.getGraphNodesCreated());
        m.put("graphEdgesCreated", document.getGraphEdgesCreated());
        if (document.getExtractors() != null && !document.getExtractors().isEmpty()) {
            m.put("extractors", document.getExtractors());
        }
        if (document.getMessage() != null) m.put("message", document.getMessage());
        if (document.getErrorMessage() != null) m.put("errorMessage", document.getErrorMessage());
        if (document.getStartedAt() != null) m.put("startedAt", document.getStartedAt());
        if (document.getUpdatedAt() != null) m.put("updatedAt", document.getUpdatedAt());
        if (document.getCompletedAt() != null) m.put("completedAt", document.getCompletedAt());
        return m;
    }

    private String crawlEventKey(UnifiedCrawlJob.StageEvent event) {
        return String.valueOf(event.getTimestamp()) + "|"
                + event.getPhase() + "|"
                + event.getLevel() + "|"
                + event.getMessage() + "|"
                + event.getDetails();
    }

    private LogLevel parseLogLevel(String level) {
        if (level == null || level.isBlank()) {
            return LogLevel.INFO;
        }
        try {
            return LogLevel.valueOf(level.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return LogLevel.INFO;
        }
    }

    private String formatCrawlLogMessage(UnifiedCrawlJob.StageEvent event) {
        StringBuilder sb = new StringBuilder();
        if (event.getPhase() != null && !event.getPhase().isBlank()) {
            sb.append('[').append(event.getPhase()).append("] ");
        }
        sb.append(event.getMessage() != null ? event.getMessage() : "Crawl progress");
        if (event.getDetails() != null && !event.getDetails().isBlank()) {
            sb.append(" - ").append(event.getDetails());
        }
        if (event.getProgressPercent() != null) {
            sb.append(" (").append(event.getProgressPercent()).append("%)");
        }
        return sb.toString();
    }

    /**
     * Check if a node is a table cell (not meaningful for "top entities" display).
     */
    private boolean isTableCellNode(GraphNode node) {
        String extId = node.getExternalId();
        return extId != null && (extId.startsWith("tbl:") || extId.contains("/cell:"));
    }

    /**
     * Extract a human-readable entity type from a graph node.
     * Checks metadata JSON for "entity_type" key first, then categorizes by externalId prefix.
     */
    private String extractEntityType(GraphNode node) {
        // Try metadata-based entity type first (from LLM extraction)
        if (node.getMetadataJson() != null) {
            try {
                // Quick extraction without full JSON parse
                String json = node.getMetadataJson();
                int idx = json.indexOf("\"entity_type\"");
                if (idx >= 0) {
                    int colonIdx = json.indexOf(':', idx);
                    int quoteStart = json.indexOf('"', colonIdx + 1);
                    int quoteEnd = json.indexOf('"', quoteStart + 1);
                    if (quoteStart >= 0 && quoteEnd > quoteStart) {
                        return json.substring(quoteStart + 1, quoteEnd);
                    }
                }
            } catch (Exception e) {
                log.debug("Failed to extract entity_type from metadata for node {}: {}", node.getNodeId(), e.getMessage());
            }
        }

        // Categorize by externalId prefix
        String extId = node.getExternalId();
        if (extId == null) return "ENTITY";
        if (extId.startsWith("tbl:")) return "TABLE_CELL";
        if (extId.startsWith("matrix:")) return "EXTRACTED";
        if (extId.startsWith("email:")) return "EMAIL";
        if (extId.startsWith("doc:")) return "DOCUMENT";
        return "ENTITY";
    }

    private Map<String, Object> sourceProgressMap(UnifiedCrawlJob.SourceProgress sp) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("label", sp.getLabel());
        m.put("sourceType", sp.getSourceType());
        m.put("pathOrUrl", sp.getPathOrUrl());
        m.put("status", sp.getStatus() != null ? sp.getStatus().name() : "UNKNOWN");
        m.put("documentsDiscovered", sp.getDocumentsDiscovered());
        m.put("documentsLoaded", sp.getDocumentsLoaded());
        m.put("chunksCreated", sp.getChunksCreated());
        m.put("entitiesExtracted", sp.getEntitiesExtracted());
        m.put("relationshipsExtracted", sp.getRelationshipsExtracted());
        if (sp.getCurrentPhase() != null) m.put("currentPhase", sp.getCurrentPhase());
        if (sp.getCurrentItem() != null) m.put("currentItem", sp.getCurrentItem());
        if (sp.getErrorMessage() != null) m.put("errorMessage", sp.getErrorMessage());
        return m;
    }

    // ---- Processing Route Config ----

    @Autowired(required = false)
    private ai.kompile.app.services.ProcessingRouteConfigService processingRouteConfigService;

    @Autowired(required = false)
    private ai.kompile.app.services.ProcessingCapacityTrackerImpl processingCapacityTracker;

    /**
     * Get the default processing route configuration.
     */
    @GetMapping("/processing-route")
    public ResponseEntity<?> getProcessingRouteConfig() {
        if (processingRouteConfigService == null) {
            return ResponseEntity.ok(Map.of("available", false, "message", "ProcessingRouteConfigService not available"));
        }
        try {
            ProcessingRouteConfig config = processingRouteConfigService.getConfig();
            return ResponseEntity.ok(Map.of("available", true, "config", config));
        } catch (Exception e) {
            return ResponseEntity.internalServerError()
                    .body(Map.of("available", false, "error", e.getMessage()));
        }
    }

    /**
     * Update the default processing route configuration.
     */
    @PutMapping("/processing-route")
    public ResponseEntity<?> updateProcessingRouteConfig(@RequestBody ProcessingRouteConfig config) {
        if (processingRouteConfigService == null) {
            return ResponseEntity.badRequest()
                    .body(Map.of("success", false, "message", "ProcessingRouteConfigService not available"));
        }
        try {
            processingRouteConfigService.updateConfig(config);
            return ResponseEntity.ok(Map.of("success", true, "config", processingRouteConfigService.getConfig()));
        } catch (Exception e) {
            return ResponseEntity.internalServerError()
                    .body(Map.of("success", false, "error", e.getMessage()));
        }
    }

    /**
     * Get a snapshot of current processing capacity across all configured backends.
     */
    @GetMapping("/processing-capacity")
    public ResponseEntity<?> getProcessingCapacity() {
        if (processingCapacityTracker == null || processingRouteConfigService == null) {
            return ResponseEntity.ok(Map.of("available", false, "message", "Capacity tracking not available"));
        }
        try {
            ProcessingRouteConfig config = processingRouteConfigService.getConfig();
            List<ProcessingRouteConfig.CapacitySnapshot> snapshots = processingCapacityTracker.getCapacitySnapshot(config);
            return ResponseEntity.ok(Map.of("available", true, "backends", snapshots));
        } catch (Exception e) {
            return ResponseEntity.internalServerError()
                    .body(Map.of("available", false, "error", e.getMessage()));
        }
    }

    /**
     * List available PDF routing modes.
     */
    @GetMapping("/pdf-routing-modes")
    public ResponseEntity<?> getPdfRoutingModes() {
        List<Map<String, String>> modes = new ArrayList<>();
        for (ProcessingRouteConfig.PdfRoutingMode mode : ProcessingRouteConfig.PdfRoutingMode.values()) {
            Map<String, String> modeInfo = new HashMap<>();
            modeInfo.put("value", mode.name());
            modeInfo.put("description", switch (mode) {
                case AUTO -> "Classify each PDF by content; text-only to cheap parser, image PDFs to VLM";
                case FORCE_VLM -> "Force all PDFs through VLM pipeline";
                case FORCE_TEXT -> "Force all PDFs through text extraction only";
                case DISABLED -> "Disable PDF-specific routing";
            });
            modes.add(modeInfo);
        }
        return ResponseEntity.ok(modes);
    }

    /**
     * List available processing backend types.
     */
    @GetMapping("/processing-backend-types")
    public ResponseEntity<?> getProcessingBackendTypes() {
        List<Map<String, String>> types = new ArrayList<>();
        for (ProcessingRouteConfig.ProcessingBackendType type : ProcessingRouteConfig.ProcessingBackendType.values()) {
            Map<String, String> typeInfo = new HashMap<>();
            typeInfo.put("value", type.name());
            typeInfo.put("description", switch (type) {
                case LOCAL_MODEL -> "Local model (SameDiff/ONNX) — cheapest but resource-constrained";
                case CLI_AGENT -> "CLI agent subprocess (Claude Code, Codex, Gemini CLI)";
                case API_AGENT -> "API endpoint (OpenAI, Anthropic, etc.) — unlimited capacity";
            });
            types.add(typeInfo);
        }
        return ResponseEntity.ok(types);
    }

    private IngestPhase mapCrawlPhaseToIngestPhase(UnifiedCrawlJob.ProgressSnapshot snap) {
        if (snap.getStatus() == UnifiedCrawlJob.Status.COMPLETED) return IngestPhase.COMPLETED;
        if (snap.getStatus() == UnifiedCrawlJob.Status.FAILED) return IngestPhase.FAILED;
        String phase = snap.getCurrentPhase();
        if (phase == null) return IngestPhase.LOADING;
        return switch (phase) {
            case "QUEUED" -> IngestPhase.QUEUED;
            case "DISCOVERING", "LOADING" -> IngestPhase.LOADING;
            case "CONVERTING", "ROUTING" -> IngestPhase.CONVERTING;
            case "CHUNKING" -> IngestPhase.CHUNKING;
            case "GRAPH_PREP", "GRAPH_EXTRACTION", "ENTITY_RESOLUTION", "EDGE_COMPUTATION" -> IngestPhase.GRAPH_EXTRACTION;
            case "EMBEDDING" -> IngestPhase.EMBEDDING;
            case "INDEXING", "VECTOR_INDEXING" -> IngestPhase.INDEXING;
            default -> IngestPhase.LOADING;
        };
    }
}
