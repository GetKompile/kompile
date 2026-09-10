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
import java.util.concurrent.atomic.AtomicLong;

/**
 * Tracks the state of a distributed crawl job across multiple workers.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DistributedCrawlSession {

    public enum Status {
        PREPARING, DISPATCHING, RUNNING, SEALING, ABORTING,
        COMPLETED, PARTIALLY_COMPLETED, FAILED, CANCELLING, CANCELLED
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
    private UnifiedCrawlJob.GraphGenerationSnapshot graphGeneration;
    private UnifiedCrawlJob.GraphActivationSnapshot graphActivation;
    private String finalizerWorkerId;
    @Builder.Default
    private AtomicLong persistenceRevision = new AtomicLong(0);

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
        addWorker(workerId, sources, parsePartitionIndex(workerId));
    }

    public void addWorker(String workerId, List<UnifiedCrawlSource> sources, int partitionIndex) {
        workers.put(workerId, WorkerInfo.builder()
                .workerId(workerId)
                .sources(sources)
                .partitionIndex(Math.max(0, partitionIndex))
                .status(WorkerStatus.DISPATCHING)
                .attempt(1)
                .currentExternalJobId(workerId)
                .partitionPhase("DISPATCHING")
                .createdAt(Instant.now())
                .build());
    }

    private static int parsePartitionIndex(String workerId) {
        if (workerId == null) return 0;
        int marker = workerId.lastIndexOf("-worker-");
        if (marker < 0) return 0;
        try {
            return Integer.parseInt(workerId.substring(marker + "-worker-".length()));
        } catch (NumberFormatException ignored) {
            return 0;
        }
    }

    /**
     * Mark a worker as dispatched to the external scheduler.
     */
    public void workerDispatched(String workerId, String externalRef) {
        WorkerInfo current = workers.get(workerId);
        workerDispatched(workerId, externalRef,
                current != null ? current.getCurrentExternalJobId() : workerId,
                current != null ? current.getAttempt() : 1);
    }

    public void workerDispatched(String workerId, String externalRef,
                                 String externalJobId, int attempt) {
        WorkerInfo w = workers.get(workerId);
        if (w != null && w.getAttempt() == attempt && !isTerminal(w.getStatus())) {
            w.setExternalRef(externalRef);
            w.setCurrentExternalJobId(externalJobId);
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
        WorkerInfo current = workers.get(workerId);
        updateWorkerSnapshot(workerId, current != null ? current.getAttempt() : 1, snapshot);
    }

    public void updateWorkerSnapshot(String workerId, int attempt,
                                     UnifiedCrawlJob.ProgressSnapshot snapshot) {
        WorkerInfo w = workers.get(workerId);
        if (w != null && w.getAttempt() == normalizeAttempt(attempt, w) && snapshot != null) {
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
        WorkerInfo current = workers.get(workerId);
        workerCompleted(workerId, current != null ? current.getAttempt() : 1, resultData);
    }

    public void workerCompleted(String workerId, int attempt, Map<String, Object> resultData) {
        WorkerInfo w = workers.get(workerId);
        if (w != null && w.getAttempt() == normalizeAttempt(attempt, w) && !isTerminal(w.getStatus())) {
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
        WorkerInfo current = workers.get(workerId);
        workerFailed(workerId, current != null ? current.getAttempt() : 1, errorMessage);
    }

    public void workerFailed(String workerId, int attempt, String errorMessage) {
        WorkerInfo w = workers.get(workerId);
        if (w != null && w.getAttempt() == normalizeAttempt(attempt, w) && !isTerminal(w.getStatus())) {
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

    public boolean isCurrentAttempt(String workerId, int attempt) {
        WorkerInfo worker = workers.get(workerId);
        return worker != null && worker.getAttempt() == normalizeAttempt(attempt, worker);
    }

    public int currentAttempt(String workerId) {
        WorkerInfo worker = workers.get(workerId);
        return worker != null ? worker.getAttempt() : -1;
    }

    public void beginAttempt(String workerId, String externalJobId, int attempt) {
        WorkerInfo worker = workers.get(workerId);
        if (worker == null) throw new IllegalArgumentException("Unknown worker partition: " + workerId);
        if (attempt <= worker.getAttempt()) {
            throw new IllegalArgumentException("Attempt must advance for " + workerId);
        }
        worker.setAttempt(attempt);
        worker.setCurrentExternalJobId(externalJobId);
        worker.setReassignmentCount(attempt - 1);
        worker.setExternalRef(null);
        worker.setStatus(WorkerStatus.DISPATCHING);
        worker.setErrorMessage(null);
        worker.setCompletedAt(null);
        worker.setLeaseTokenHash(null);
        worker.setLeaseExpiresAt(null);
        worker.setLeaseRevoked(false);
        worker.setAcceptedGraphWrites(false);
        worker.setPartitionPhase("DISPATCHING");
        worker.setLastProgressAt(Instant.now());
    }

    private static int normalizeAttempt(int attempt, WorkerInfo worker) {
        return attempt <= 0 && worker.getAttempt() == 1 ? 1 : attempt;
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
            ws.put("currentExternalJobId", w.getCurrentExternalJobId());
            ws.put("attempt", w.getAttempt());
            ws.put("leaseExpiresAt", w.getLeaseExpiresAt() != null ? w.getLeaseExpiresAt().toString() : null);
            ws.put("acceptedGraphWrites", w.isAcceptedGraphWrites());
            ws.put("partitionPhase", w.getPartitionPhase());
            ws.put("finalizer", w.isFinalizer());
            ws.put("partitionIndex", w.getPartitionIndex());
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
        if (graphGeneration != null) snap.put("graphGeneration", graphGeneration);
        if (graphActivation != null) snap.put("graphActivation", graphActivation);
        if (finalizerWorkerId != null) snap.put("finalizerWorkerId", finalizerWorkerId);

        return snap;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class WorkerInfo {
        private String workerId;
        private List<UnifiedCrawlSource> sources;
        private int partitionIndex;
        private WorkerStatus status;
        private String externalRef;
        private String currentExternalJobId;
        @Builder.Default
        private int attempt = 1;
        private String leaseTokenHash;
        private Instant leaseExpiresAt;
        private boolean leaseRevoked;
        private boolean acceptedGraphWrites;
        private String partitionPhase;
        private boolean finalizer;
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
        private UnifiedCrawlJob.GraphGenerationSnapshot graphGeneration;
        private UnifiedCrawlJob.GraphActivationSnapshot graphActivation;
        private String finalizerWorkerId;
        private List<String> errors;
        private List<WorkerManifest> workers;
        private long persistenceRevision;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class WorkerManifest {
        private String workerId;
        private List<UnifiedCrawlSource> sources;
        private int partitionIndex;
        private WorkerStatus status;
        private String externalRef;
        private String currentExternalJobId;
        private int attempt;
        private String leaseTokenHash;
        private Instant leaseExpiresAt;
        private boolean leaseRevoked;
        private boolean acceptedGraphWrites;
        private String partitionPhase;
        private boolean finalizer;
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
        long revision = persistenceRevision.incrementAndGet();
        List<WorkerManifest> wms = new ArrayList<>();
        for (WorkerInfo w : workers.values()) {
            wms.add(WorkerManifest.builder()
                    .workerId(w.getWorkerId())
                    .sources(w.getSources())
                    .partitionIndex(w.getPartitionIndex())
                    .status(w.getStatus())
                    .externalRef(w.getExternalRef())
                    .currentExternalJobId(w.getCurrentExternalJobId())
                    .attempt(w.getAttempt())
                    .leaseTokenHash(w.getLeaseTokenHash())
                    .leaseExpiresAt(w.getLeaseExpiresAt())
                    .leaseRevoked(w.isLeaseRevoked())
                    .acceptedGraphWrites(w.isAcceptedGraphWrites())
                    .partitionPhase(w.getPartitionPhase())
                    .finalizer(w.isFinalizer())
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
                .graphGeneration(graphGeneration)
                .graphActivation(graphActivation)
                .finalizerWorkerId(finalizerWorkerId)
                .errors(new ArrayList<>(errors))
                .workers(wms)
                .persistenceRevision(revision)
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
                .graphGeneration(m.getGraphGeneration())
                .graphActivation(m.getGraphActivation())
                .finalizerWorkerId(m.getFinalizerWorkerId())
                .build();
        s.getCompletedWorkers().set(m.getCompletedWorkers());
        s.getFailedWorkers().set(m.getFailedWorkers());
        s.getPersistenceRevision().set(m.getPersistenceRevision());
        if (m.getErrors() != null) {
            s.getErrors().addAll(m.getErrors());
        }
        if (m.getWorkers() != null) {
            for (WorkerManifest wm : m.getWorkers()) {
                WorkerInfo w = WorkerInfo.builder()
                        .workerId(wm.getWorkerId())
                        .sources(wm.getSources())
                        .partitionIndex(wm.getPartitionIndex())
                        .status(wm.getStatus())
                        .externalRef(wm.getExternalRef())
                        .currentExternalJobId(wm.getCurrentExternalJobId() != null
                                ? wm.getCurrentExternalJobId() : wm.getWorkerId())
                        .attempt(wm.getAttempt() > 0 ? wm.getAttempt()
                                : Math.max(1, wm.getReassignmentCount() + 1))
                        .leaseTokenHash(wm.getLeaseTokenHash())
                        .leaseExpiresAt(wm.getLeaseExpiresAt())
                        .leaseRevoked(wm.isLeaseRevoked())
                        .acceptedGraphWrites(wm.isAcceptedGraphWrites())
                        .partitionPhase(wm.getPartitionPhase())
                        .finalizer(wm.isFinalizer())
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
