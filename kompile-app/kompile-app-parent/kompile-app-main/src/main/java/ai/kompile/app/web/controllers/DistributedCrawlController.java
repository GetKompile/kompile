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

import ai.kompile.app.services.crawl.DistributedCrawlCoordinator;
import ai.kompile.app.services.crawl.DistributedCrawlSession;
import ai.kompile.core.crawl.graph.UnifiedCrawlRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
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
            @RequestBody UnifiedCrawlRequest request) {
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
    public ResponseEntity<List<Map<String, Object>>> listSessions() {
        return ResponseEntity.ok(
                coordinator.getAllSessions().stream()
                        .map(DistributedCrawlSession::toSnapshot)
                        .collect(Collectors.toList()));
    }

    /**
     * Get details of a specific distributed session.
     */
    @GetMapping("/sessions/{sessionId}")
    public ResponseEntity<Map<String, Object>> getSession(@PathVariable String sessionId) {
        return coordinator.getSession(sessionId)
                .map(s -> ResponseEntity.ok(s.toSnapshot()))
                .orElse(ResponseEntity.notFound().build());
    }

    /**
     * Cancel a distributed crawl session (cancels all workers).
     */
    @PostMapping("/sessions/{sessionId}/cancel")
    public ResponseEntity<Map<String, Object>> cancelSession(@PathVariable String sessionId) {
        if (coordinator.cancelSession(sessionId)) {
            return ResponseEntity.ok(Map.of("message", "Session cancelled", "sessionId", sessionId));
        }
        return ResponseEntity.notFound().build();
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
            @RequestBody WorkerCallbackRequest callback) {
        try {
            coordinator.handleWorkerCallback(
                    callback.sessionId(),
                    callback.workerId(),
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
     * Remove completed/failed/cancelled sessions.
     */
    @PostMapping("/cleanup")
    public ResponseEntity<Map<String, Object>> cleanup() {
        int removed = coordinator.cleanupSessions();
        return ResponseEntity.ok(Map.of("removed", removed));
    }

    public record WorkerCallbackRequest(
            String sessionId,
            String workerId,
            boolean success,
            String message,
            Map<String, Object> resultData
    ) {}
}
