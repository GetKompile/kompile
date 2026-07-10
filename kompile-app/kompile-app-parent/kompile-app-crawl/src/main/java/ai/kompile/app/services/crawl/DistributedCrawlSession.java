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

import ai.kompile.core.crawl.graph.UnifiedCrawlJob;
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
            w.setLastProgressAt(Instant.now());
        }
    }

    private static boolean isTerminal(WorkerStatus s) {
        return s == WorkerStatus.COMPLETED || s == WorkerStatus.FAILED || s == WorkerStatus.CANCELLED;
    }

    /**
     * Store the latest progress snapshot a worker reported (Phase C). The first snapshot also confirms the
     * worker is actually running (flips DISPATCHING → RUNNING).
     */
    public void updateWorkerSnapshot(String workerId, UnifiedCrawlJob.ProgressSnapshot snapshot) {
        WorkerInfo w = workers.get(workerId);
        if (w != null && snapshot != null) {
            w.setLatestSnapshot(snapshot);
            w.setLastProgressAt(Instant.now());
            if (w.getStatus() == WorkerStatus.DISPATCHING) {
                w.setStatus(WorkerStatus.RUNNING);
            }
        }
    }

    /**
     * Mark a worker as completed.
     */
    public void workerCompleted(String workerId, Map<String, Object> resultData) {
        WorkerInfo w = workers.get(workerId);
        if (w != null && !isTerminal(w.getStatus())) { // idempotent: a revived/reassigned twin can't double-count
            w.setStatus(WorkerStatus.COMPLETED);
            w.setCompletedAt(Instant.now());
            w.setLastProgressAt(Instant.now());
            w.setResultData(resultData);
            completedWorkers.incrementAndGet();
        }
    }

    /**
     * Mark a worker as failed.
     */
    public void workerFailed(String workerId, String errorMessage) {
        WorkerInfo w = workers.get(workerId);
        if (w != null && !isTerminal(w.getStatus())) { // idempotent: only the first terminal outcome counts
            w.setStatus(WorkerStatus.FAILED);
            w.setCompletedAt(Instant.now());
            w.setErrorMessage(errorMessage);
            failedWorkers.incrementAndGet();
            errors.add("[" + workerId + "] " + errorMessage);
        }
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
        /** Latest live progress snapshot reported by the worker (Phase C); null until first report. */
        private volatile UnifiedCrawlJob.ProgressSnapshot latestSnapshot;
        /** Wall-clock of the last progress/callback from this worker; drives loss detection (Phase E). */
        private volatile Instant lastProgressAt;
        /** How many times this partition has been re-dispatched after a worker loss (Phase E). */
        private volatile int reassignmentCount;

        /** Completed-source keys restored from a persisted manifest (Phase 1); used when {@code latestSnapshot}
         *  is null after a coordinator restart. Live snapshots take precedence. */
        private volatile Set<String> restoredCompletedSourceKeys;

        /**
         * Source keys (label, else path/URL) this worker has already finished — from the live snapshot when
         * present, otherwise the set restored from a persisted manifest. Drives checkpoint-aware reassignment
         * so a re-dispatched partition doesn't re-crawl sources already written to the shared stores.
         */
        public Set<String> completedSourceKeys() {
            if (latestSnapshot != null && latestSnapshot.getSourceProgress() != null) {
                Set<String> done = new HashSet<>();
                for (UnifiedCrawlJob.SourceProgress sp : latestSnapshot.getSourceProgress()) {
                    if (isCompletedStatus(sp.getStatus())) {
                        done.add(sourceKey(sp.getLabel(), sp.getPathOrUrl()));
                    }
                }
                if (!done.isEmpty()) {
                    return done;
                }
            }
            return restoredCompletedSourceKeys != null ? restoredCompletedSourceKeys : Set.of();
        }
    }

    /** Stable key for matching a source across snapshot/request: the label, else the path/URL. */
    public static String sourceKey(String label, String pathOrUrl) {
        if (label != null && !label.isBlank()) {
            return label;
        }
        return pathOrUrl != null ? pathOrUrl : "";
    }

    private static boolean isCompletedStatus(UnifiedCrawlJob.Status s) {
        return s == UnifiedCrawlJob.Status.COMPLETED
                || s == UnifiedCrawlJob.Status.COMPLETED_PENDING_EMBEDDING
                || s == UnifiedCrawlJob.Status.COMPLETED_PENDING_GRAPH;
    }

    // ---- Phase 1: durable persistence manifest ----

    /** Lightweight, JSON-serializable snapshot of a session — enough to reconcile + reassign after a restart. */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Manifest {
        private String sessionId;
        private Status status;
        private int totalWorkers;
        private Instant startedAt;
        private Instant completedAt;
        private int completedWorkers;
        private int failedWorkers;
        private UnifiedCrawlRequest originalRequest;
        private List<String> errors;
        private List<WorkerManifest> workers;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class WorkerManifest {
        private String workerId;
        private List<UnifiedCrawlSource> sources;
        private WorkerStatus status;
        private String externalRef;
        private Instant createdAt;
        private Instant startedAt;
        private Instant completedAt;
        private String errorMessage;
        private int reassignmentCount;
        private Instant lastProgressAt;
        private List<String> completedSourceKeys;
    }

    /**
     * Capture the persistable state. The live progress snapshot is intentionally dropped — only the derived
     * completed-source keys + {@code lastProgressAt} are kept (small, and enough for reconcile/reassign).
     */
    public Manifest toManifest() {
        List<WorkerManifest> wms = new ArrayList<>();
        for (WorkerInfo w : workers.values()) {
            wms.add(WorkerManifest.builder()
                    .workerId(w.getWorkerId())
                    .sources(w.getSources())
                    .status(w.getStatus())
                    .externalRef(w.getExternalRef())
                    .createdAt(w.getCreatedAt())
                    .startedAt(w.getStartedAt())
                    .completedAt(w.getCompletedAt())
                    .errorMessage(w.getErrorMessage())
                    .reassignmentCount(w.getReassignmentCount())
                    .lastProgressAt(w.getLastProgressAt())
                    .completedSourceKeys(new ArrayList<>(w.completedSourceKeys()))
                    .build());
        }
        return Manifest.builder()
                .sessionId(sessionId)
                .status(status)
                .totalWorkers(totalWorkers)
                .startedAt(startedAt)
                .completedAt(completedAt)
                .completedWorkers(completedWorkers.get())
                .failedWorkers(failedWorkers.get())
                .originalRequest(originalRequest)
                .errors(new ArrayList<>(errors))
                .workers(wms)
                .build();
    }

    /** Rehydrate a session from a persisted manifest (counts + per-worker state + restored completed-source keys). */
    public static DistributedCrawlSession fromManifest(Manifest m) {
        DistributedCrawlSession s = DistributedCrawlSession.builder()
                .sessionId(m.getSessionId())
                .originalRequest(m.getOriginalRequest())
                .status(m.getStatus())
                .totalWorkers(m.getTotalWorkers())
                .startedAt(m.getStartedAt())
                .completedAt(m.getCompletedAt())
                .build();
        s.getCompletedWorkers().set(m.getCompletedWorkers());
        s.getFailedWorkers().set(m.getFailedWorkers());
        if (m.getErrors() != null) {
            s.getErrors().addAll(m.getErrors());
        }
        if (m.getWorkers() != null) {
            for (WorkerManifest wm : m.getWorkers()) {
                WorkerInfo w = WorkerInfo.builder()
                        .workerId(wm.getWorkerId())
                        .sources(wm.getSources())
                        .status(wm.getStatus())
                        .externalRef(wm.getExternalRef())
                        .createdAt(wm.getCreatedAt())
                        .startedAt(wm.getStartedAt())
                        .completedAt(wm.getCompletedAt())
                        .errorMessage(wm.getErrorMessage())
                        .reassignmentCount(wm.getReassignmentCount())
                        .lastProgressAt(wm.getLastProgressAt())
                        .build();
                w.setRestoredCompletedSourceKeys(wm.getCompletedSourceKeys() != null
                        ? new HashSet<>(wm.getCompletedSourceKeys()) : null);
                s.getWorkers().put(w.getWorkerId(), w);
            }
        }
        return s;
    }
}
