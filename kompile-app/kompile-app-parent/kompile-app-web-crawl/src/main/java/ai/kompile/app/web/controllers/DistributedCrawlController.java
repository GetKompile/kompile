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

import ai.kompile.app.ingest.domain.JobLogEntry;
import ai.kompile.app.ingest.service.JobLogService;
import ai.kompile.app.services.crawl.ClusterBackendHealthAdapter;
import ai.kompile.app.services.crawl.DistributedCrawlAggregator;
import ai.kompile.app.services.crawl.DistributedCrawlCoordinator;
import ai.kompile.app.services.crawl.DistributedCrawlSession;
import ai.kompile.app.services.scheduler.ResourceSchedulerConfigService;
import ai.kompile.crawl.graph.ClusterBackendHealth;
import ai.kompile.core.crawl.graph.UnifiedCrawlJob;
import ai.kompile.core.crawl.graph.UnifiedCrawlRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.stream.Collectors;

/**
 * REST API for managing distributed crawl jobs.
 *
 * <h3>Endpoints</h3>
 * <ul>
 *   <li>{@code POST /api/distributed-crawl/start}          — start a distributed crawl</li>
 *   <li>{@code GET  /api/distributed-crawl/sessions}        — list all sessions</li>
 *   <li>{@code GET  /api/distributed-crawl/sessions/{id}}   — get session details</li>
 *   <li>{@code POST /api/distributed-crawl/sessions/{id}/cancel} — cancel a session</li>
 *   <li>{@code POST /api/distributed-crawl/callback}         — worker completion callback</li>
 *   <li>{@code POST /api/distributed-crawl/cleanup}          — remove finished sessions</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/distributed-crawl")
@RequiredArgsConstructor
@Slf4j
@ConditionalOnBean(DistributedCrawlCoordinator.class)
public class DistributedCrawlController {

    private final DistributedCrawlCoordinator coordinator;
    private final DistributedCrawlAggregator aggregator;
    private final ResourceSchedulerConfigService configService;
    private final JobLogService jobLogService;

    /** Optional cluster-wide backend breaker (Phase 4); field-injected so the constructor (and its tests) are unchanged. */
    @Autowired(required = false)
    private ClusterBackendHealthAdapter backendHealth;

    /**
     * Start a distributed crawl. The request must include a distribution config.
     *
     * <p>Example:</p>
     * <pre>{@code
     * {
     *   "name": "Multi-source distributed crawl",
     *   "sources": [
     *     {"label": "S3 docs", "sourceType": "S3", "pathOrUrl": "bucket/docs", "properties": {...}},
     *     {"label": "SFTP reports", "sourceType": "SFTP", "pathOrUrl": "/reports", "properties": {...}},
     *     {"label": "Local files", "sourceType": "DIRECTORY", "pathOrUrl": "/data/files"}
     *   ],
     *   "distribution": {
     *     "partitionStrategy": "PER_SOURCE",
     *     "timeoutMinutes": 120,
     *     "mergeResults": true
     *   },
     *   "vectorIndex": {"enabled": true},
     *   "graphExtraction": {"enabled": true}
     * }
     * }</pre>
     */
    @PostMapping("/start")
    public ResponseEntity<Map<String, Object>> startDistributed(
            @RequestBody UnifiedCrawlRequest request,
            @RequestHeader(value = "Authorization", required = false) String auth) {
        if (!authorized(auth)) return ResponseEntity.status(401).body(Map.of("error", "unauthorized"));
        try {
            if (request.getDistribution() == null) {
                return ResponseEntity.badRequest().body(Map.of(
                        "error", "distribution config is required for distributed crawl"));
            }

            DistributedCrawlSession session = coordinator.startDistributed(request);
            return ResponseEntity.ok(session.toSnapshot());
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        } catch (Exception e) {
            log.error("Failed to start distributed crawl", e);
            return ResponseEntity.internalServerError()
                    .body(Map.of("error", "Failed to start distributed crawl: " + e.getMessage()));
        }
    }

    /**
     * List all distributed crawl sessions.
     */
    @GetMapping("/sessions")
    public ResponseEntity<List<Map<String, Object>>> listSessions(
            @RequestHeader(value = "Authorization", required = false) String auth) {
        if (!authorized(auth)) return ResponseEntity.status(401).build();
        return ResponseEntity.ok(
                coordinator.getAllSessions().stream()
                        .map(DistributedCrawlSession::toSnapshot)
                        .collect(Collectors.toList()));
    }

    /**
     * Get details of a specific distributed session.
     */
    @GetMapping("/sessions/{sessionId}")
    public ResponseEntity<Object> getSession(@PathVariable String sessionId,
                                             @RequestParam(defaultValue = "false") boolean aggregate,
                                             @RequestHeader(value = "Authorization", required = false) String auth) {
        if (!authorized(auth)) return ResponseEntity.status(401).build();
        // aggregate=true → the merged per-worker ProgressSnapshot the unified step monitor renders;
        // otherwise the lightweight session summary (per-worker terminal status).
        return coordinator.getSession(sessionId)
                .<ResponseEntity<Object>>map(s -> ResponseEntity.ok(
                        aggregate ? aggregator.aggregate(s) : s.toSnapshot()))
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    /**
     * Cancel a distributed crawl session (cancels all workers).
     */
    @PostMapping("/sessions/{sessionId}/cancel")
    public ResponseEntity<Map<String, Object>> cancelSession(
            @PathVariable String sessionId,
            @RequestHeader(value = "Authorization", required = false) String auth) {
        if (!authorized(auth)) return ResponseEntity.status(401).body(Map.of("error", "unauthorized"));
        if (coordinator.cancelSession(sessionId)) {
            return ResponseEntity.ok(Map.of("message", "Session cancelled", "sessionId", sessionId));
        }
        return ResponseEntity.notFound().build();
    }

    @PostMapping("/sessions/{sessionId}/retry")
    public ResponseEntity<Map<String, Object>> retrySession(
            @PathVariable String sessionId,
            @RequestHeader(value = "Authorization", required = false) String auth) {
        if (!authorized(auth)) return ResponseEntity.status(401).body(Map.of("error", "unauthorized"));
        return coordinator.retrySession(sessionId)
                .map(session -> ResponseEntity.ok(session.toSnapshot()))
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    /**
     * Worker completion callback. Workers POST here when they finish their partition.
     *
     * <p>Expected body:</p>
     * <pre>{@code
     * {
     *   "sessionId": "abc-123",
     *   "workerId": "abc-123-worker-0",
     *   "success": true,
     *   "message": "Crawl completed: 150 documents processed",
     *   "resultData": {
     *     "documentsProcessed": 150,
     *     "chunksCreated": 450,
     *     "entitiesExtracted": 200
     *   }
     * }
     * }</pre>
     */
    @PostMapping("/callback")
    public ResponseEntity<Map<String, Object>> workerCallback(
            @RequestBody WorkerCallbackRequest callback,
            @RequestHeader(value = "Authorization", required = false) String auth) {
        if (!authorized(auth)) {
            return ResponseEntity.status(401).body(Map.of("ok", false, "error", "unauthorized"));
        }
        try {
            coordinator.handleWorkerCallback(
                    callback.sessionId(),
                    callback.workerId(),
                    callback.attempt(),
                    callback.success(),
                    callback.message(),
                    callback.resultData()
            );
            return ResponseEntity.ok(Map.of("acknowledged", true));
        } catch (Exception e) {
            log.error("Failed to process worker callback", e);
            return ResponseEntity.internalServerError()
                    .body(Map.of("error", e.getMessage()));
        }
    }

    /**
     * Periodic worker progress report (Phase C). Workers POST their live {@link UnifiedCrawlJob.ProgressSnapshot}
     * here every few seconds; the coordinator merges them so the distributed crawl renders as one live job in
     * the unified step monitor.
     */
    @PostMapping("/progress")
    public ResponseEntity<Map<String, Object>> workerProgress(
            @RequestBody WorkerProgressRequest progress,
            @RequestHeader(value = "Authorization", required = false) String auth) {
        if (!authorized(auth)) {
            return ResponseEntity.status(401).body(Map.of("ok", false, "error", "unauthorized"));
        }
        coordinator.handleWorkerProgress(
                progress.sessionId(), progress.workerId(), progress.attempt(), progress.snapshot());
        return ResponseEntity.ok(Map.of("acknowledged", true));
    }

    @PostMapping("/barrier")
    public ResponseEntity<Map<String, Object>> partitionBarrier(
            @RequestBody WorkerBarrierRequest request,
            @RequestHeader(value = "Authorization", required = false) String auth,
            @RequestHeader(value = DistributedGraphAuthorityController.LEASE_HEADER,
                    required = false) String lease) {
        if (!authorized(auth)) {
            return ResponseEntity.status(401).body(Map.of("ok", false, "error", "unauthorized"));
        }
        DistributedCrawlCoordinator.WriterLeaseVerdict verdict = coordinator.validateWriterLease(
                request.sessionId(), request.workerId(), request.attempt(), lease, false);
        if (verdict != DistributedCrawlCoordinator.WriterLeaseVerdict.VALID) {
            return ResponseEntity.status(verdict == DistributedCrawlCoordinator.WriterLeaseVerdict.EXPIRED
                    ? 410 : 409).body(Map.of("ok", false, "error", verdict.name()));
        }
        Optional<ai.kompile.core.crawl.graph.DistributedCrawlPartitionBarrier.Decision> decision =
                coordinator.handlePartitionBarrier(
                        request.sessionId(), request.workerId(), request.attempt(), request.snapshot());
        if (decision.isEmpty()) {
            return ResponseEntity.status(202).body(Map.of("ok", true, "decision", "WAIT"));
        }
        return ResponseEntity.ok(Map.of("ok", true, "decision", decision.get().name()));
    }

    /**
     * Worker LLM-transcript forwarding (Phase: distributed transcripts). Workers POST batches of their local
     * {@code LLM_TRANSCRIPT} entries; the coordinator stores them under {@code crawl-distributed-<sessionId>}
     * (each message worker-prefixed) so the unified monitor's transcript viewer works for distributed crawls.
     */
    @PostMapping("/transcripts")
    public ResponseEntity<Map<String, Object>> workerTranscripts(
            @RequestBody WorkerTranscriptsRequest req,
            @RequestHeader(value = "Authorization", required = false) String auth) {
        if (!authorized(auth)) {
            return ResponseEntity.status(401).body(Map.of("ok", false, "error", "unauthorized"));
        }
        int stored = 0;
        if (jobLogService != null && jobLogService.isEnabled()
                && req.sessionId() != null && coordinator.getSession(req.sessionId()).isPresent()
                && coordinator.isCurrentWorkerAttempt(req.sessionId(), req.workerId(), req.attempt())
                && req.entries() != null) {
            String taskId = "crawl-distributed-" + req.sessionId();
            int idx = DistributedCrawlAggregator.workerIndex(req.workerId());
            for (TranscriptEntry e : req.entries()) {
                jobLogService.logEntry(taskId, parseLevel(e.level()), JobLogEntry.LogSource.LLM_TRANSCRIPT,
                        "[W" + idx + "] " + (e.message() != null ? e.message() : ""),
                        "distributed-crawl", req.workerId());
                stored++;
            }
        }
        return ResponseEntity.ok(Map.of("acknowledged", true, "stored", stored));
    }

    private static JobLogEntry.LogLevel parseLevel(String level) {
        if (level == null) {
            return JobLogEntry.LogLevel.INFO;
        }
        try {
            return JobLogEntry.LogLevel.valueOf(level.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            return JobLogEntry.LogLevel.INFO;
        }
    }

    /**
     * Worker backend-health report (Phase 4). A worker POSTs a single LLM-backend failure event; the orchestrator
     * folds it into the cluster-wide breaker and returns the current open-set, so the worker can avoid that backend
     * without having to independently trip its own per-JVM breaker.
     */
    @PostMapping("/backend-health")
    public ResponseEntity<Map<String, Object>> backendHealth(
            @RequestBody BackendHealthRequest req,
            @RequestHeader(value = "Authorization", required = false) String auth) {
        if (!authorized(auth)) {
            return ResponseEntity.status(401).body(Map.of("ok", false, "error", "unauthorized"));
        }
        java.util.Set<String> open = (backendHealth != null && req.backendId() != null)
                ? backendHealth.applyRemoteEvent(req.backendId(), parseBackendEvent(req.event()))
                : java.util.Set.of();
        return ResponseEntity.ok(Map.of("openBackends", open));
    }

    private static ClusterBackendHealth.Event parseBackendEvent(String event) {
        if (event == null) {
            return ClusterBackendHealth.Event.FAILURE;
        }
        try {
            return ClusterBackendHealth.Event.valueOf(event.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            return ClusterBackendHealth.Event.FAILURE;
        }
    }

    /**
     * Remove completed/failed/cancelled sessions.
     */
    @PostMapping("/cleanup")
    public ResponseEntity<Map<String, Object>> cleanup(
            @RequestHeader(value = "Authorization", required = false) String auth) {
        if (!authorized(auth)) return ResponseEntity.status(401).body(Map.of("error", "unauthorized"));
        int removed = coordinator.cleanupSessions();
        return ResponseEntity.ok(Map.of("removed", removed));
    }

    /** Optional Bearer-token gate (matches the cluster callbacks); blank token = open (local/dev). */
    private boolean authorized(String authHeader) {
        String token = configService != null && configService.getConfiguration() != null
                ? configService.getConfiguration().getExternalAuthToken() : null;
        if (token == null || token.isBlank()) {
            return configService == null || configService.getConfiguration() == null
                    || (!configService.getConfiguration().isClusterWorker()
                    && !configService.getConfiguration().isClusterOrchestrator());
        }
        byte[] expected = ("Bearer " + token).getBytes(StandardCharsets.UTF_8);
        byte[] actual = authHeader == null ? new byte[0] : authHeader.getBytes(StandardCharsets.UTF_8);
        return MessageDigest.isEqual(expected, actual);
    }

    public record WorkerCallbackRequest(
            String sessionId,
            String workerId,
            int attempt,
            boolean success,
            String message,
            Map<String, Object> resultData
    ) {
        public WorkerCallbackRequest(String sessionId, String workerId, boolean success,
                                     String message, Map<String, Object> resultData) {
            this(sessionId, workerId, 0, success, message, resultData);
        }
    }

    public record WorkerProgressRequest(
            String sessionId,
            String workerId,
            int attempt,
            UnifiedCrawlJob.ProgressSnapshot snapshot
    ) {
        public WorkerProgressRequest(String sessionId, String workerId,
                                     UnifiedCrawlJob.ProgressSnapshot snapshot) {
            this(sessionId, workerId, 0, snapshot);
        }
    }

    public record WorkerBarrierRequest(
            String sessionId,
            String workerId,
            int attempt,
            UnifiedCrawlJob.ProgressSnapshot snapshot
    ) {}

    public record WorkerTranscriptsRequest(
            String sessionId,
            String workerId,
            int attempt,
            List<TranscriptEntry> entries
    ) {
        public WorkerTranscriptsRequest(String sessionId, String workerId,
                                        List<TranscriptEntry> entries) {
            this(sessionId, workerId, 0, entries);
        }
    }

    public record TranscriptEntry(
            String timestamp,
            String level,
            String message
    ) {}

    public record BackendHealthRequest(
            String backendId,
            String event
    ) {}
}
