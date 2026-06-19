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

package ai.kompile.app.services.crawl;

import ai.kompile.core.crawl.graph.UnifiedCrawlRequest;
import ai.kompile.core.crawl.graph.UnifiedCrawlSource;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Tracks the state of a distributed crawl job across multiple workers.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DistributedCrawlSession {

    public enum Status {
        DISPATCHING, RUNNING, COMPLETED, PARTIALLY_COMPLETED, FAILED, CANCELLED
    }

    public enum WorkerStatus {
        DISPATCHING, RUNNING, COMPLETED, FAILED, CANCELLED
    }

    private String sessionId;
    private UnifiedCrawlRequest originalRequest;
    private volatile Status status;
    private int totalWorkers;
    private Instant startedAt;
    private volatile Instant completedAt;

    @Builder.Default
    private AtomicInteger completedWorkers = new AtomicInteger(0);

    @Builder.Default
    private AtomicInteger failedWorkers = new AtomicInteger(0);

    @Builder.Default
    private Map<String, WorkerInfo> workers = new ConcurrentHashMap<>();

    @Builder.Default
    private List<String> errors = Collections.synchronizedList(new ArrayList<>());

    /**
     * Register a worker for this session.
     */
    public void addWorker(String workerId, List<UnifiedCrawlSource> sources) {
        workers.put(workerId, WorkerInfo.builder()
                .workerId(workerId)
                .sources(sources)
                .status(WorkerStatus.DISPATCHING)
                .createdAt(Instant.now())
                .build());
    }

    /**
     * Mark a worker as dispatched to the external scheduler.
     */
    public void workerDispatched(String workerId, String externalRef) {
        WorkerInfo w = workers.get(workerId);
        if (w != null) {
            w.setExternalRef(externalRef);
            w.setStatus(WorkerStatus.RUNNING);
            w.setStartedAt(Instant.now());
        }
    }

    /**
     * Mark a worker as completed.
     */
    public void workerCompleted(String workerId, Map<String, Object> resultData) {
        WorkerInfo w = workers.get(workerId);
        if (w != null) {
            w.setStatus(WorkerStatus.COMPLETED);
            w.setCompletedAt(Instant.now());
            w.setResultData(resultData);
            completedWorkers.incrementAndGet();
        }
    }

    /**
     * Mark a worker as failed.
     */
    public void workerFailed(String workerId, String errorMessage) {
        WorkerInfo w = workers.get(workerId);
        if (w != null) {
            w.setStatus(WorkerStatus.FAILED);
            w.setCompletedAt(Instant.now());
            w.setErrorMessage(errorMessage);
            failedWorkers.incrementAndGet();
        }
        errors.add("[" + workerId + "] " + errorMessage);
    }

    /**
     * Check if all workers have finished (success or failure).
     */
    public boolean isAllWorkersFinished() {
        return completedWorkers.get() + failedWorkers.get() >= totalWorkers;
    }

    /**
     * Build a summary snapshot for REST/WebSocket.
     */
    public Map<String, Object> toSnapshot() {
        Map<String, Object> snap = new LinkedHashMap<>();
        snap.put("sessionId", sessionId);
        snap.put("status", status.name());
        snap.put("totalWorkers", totalWorkers);
        snap.put("completedWorkers", completedWorkers.get());
        snap.put("failedWorkers", failedWorkers.get());
        snap.put("startedAt", startedAt != null ? startedAt.toString() : null);
        snap.put("completedAt", completedAt != null ? completedAt.toString() : null);
        snap.put("elapsedMs", startedAt != null
                ? Duration.between(startedAt, completedAt != null ? completedAt : Instant.now()).toMillis()
                : 0);

        List<Map<String, Object>> workerSnapshots = new ArrayList<>();
        for (WorkerInfo w : workers.values()) {
            Map<String, Object> ws = new LinkedHashMap<>();
            ws.put("workerId", w.getWorkerId());
            ws.put("status", w.getStatus().name());
            ws.put("externalRef", w.getExternalRef());
            ws.put("sourceCount", w.getSources() != null ? w.getSources().size() : 0);
            ws.put("sourceLabels", w.getSources() != null
                    ? w.getSources().stream()
                            .map(s -> s.getLabel() != null ? s.getLabel() : s.getPathOrUrl())
                            .toList()
                    : List.of());
            ws.put("startedAt", w.getStartedAt() != null ? w.getStartedAt().toString() : null);
            ws.put("completedAt", w.getCompletedAt() != null ? w.getCompletedAt().toString() : null);
            ws.put("errorMessage", w.getErrorMessage());
            workerSnapshots.add(ws);
        }
        snap.put("workers", workerSnapshots);

        if (!errors.isEmpty()) {
            snap.put("errors", new ArrayList<>(errors));
        }

        if (originalRequest != null && originalRequest.getName() != null) {
            snap.put("name", originalRequest.getName());
        }

        return snap;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class WorkerInfo {
        private String workerId;
        private List<UnifiedCrawlSource> sources;
        private WorkerStatus status;
        private String externalRef;
        private Instant createdAt;
        private Instant startedAt;
        private Instant completedAt;
        private String errorMessage;
        private Map<String, Object> resultData;
    }
}
