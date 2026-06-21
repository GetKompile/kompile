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

import ai.kompile.app.config.ResourceSchedulerConfig;
import ai.kompile.app.services.scheduler.ExternalJobSchedulerDelegate;
import ai.kompile.app.services.scheduler.ExternalJobSchedulerDelegate.ExternalJobRef;
import ai.kompile.app.services.cluster.CrawlWorkerRegistry;
import ai.kompile.app.services.cluster.WorkerCapabilities;
import ai.kompile.app.services.scheduler.JobResourceProfile;
import ai.kompile.app.services.scheduler.ResourceSchedulerConfigService;
import ai.kompile.core.crawl.graph.*;
import ai.kompile.core.crawl.graph.UnifiedCrawlRequest.DistributionConfig;
import ai.kompile.core.crawl.graph.UnifiedCrawlRequest.PartitionStrategy;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Coordinates distributed crawl jobs across multiple workers.
 *
 * <p>When a {@link UnifiedCrawlRequest} has a non-null {@link DistributionConfig},
 * this coordinator:</p>
 * <ol>
 *   <li>Partitions the request's sources according to the partition strategy</li>
 *   <li>Creates a per-worker {@link UnifiedCrawlRequest} for each partition</li>
 *   <li>Submits each partition to a worker via {@link ExternalJobSchedulerDelegate}</li>
 *   <li>Tracks worker progress and handles completion callbacks</li>
 *   <li>Optionally merges results from all workers</li>
 * </ol>
 *
 * <p>This service requires an {@link ExternalJobSchedulerDelegate} bean
 * (Kubernetes or webhook) to be available.</p>
 */
@Service
@Slf4j
@ConditionalOnBean(ExternalJobSchedulerDelegate.class)
public class DistributedCrawlCoordinator {

    private final ExternalJobSchedulerDelegate delegate;
    private final ObjectMapper objectMapper;
    /** Nullable in unit tests; guarded everywhere it's read. */
    private final ResourceSchedulerConfigService configService;

    /** Active distributed jobs keyed by coordinator job ID */
    private final ConcurrentMap<String, DistributedCrawlSession> activeSessions =
            new ConcurrentHashMap<>();

    @Autowired(required = false)
    private UnifiedCrawlService unifiedCrawlService;

    /** Optional: live cluster view, so partitioning spreads across the actual workers (capability-aware). */
    @Autowired(required = false)
    private CrawlWorkerRegistry workerRegistry;

    /** Optional: republishes merged per-worker progress as CrawlProgressEvents so the unified SSE +
     *  step monitor render a distributed crawl as one live job (Phase C). Null in unit tests → no-op. */
    @Autowired(required = false)
    private ApplicationEventPublisher eventPublisher;

    @Autowired(required = false)
    private DistributedCrawlAggregator aggregator;

    public DistributedCrawlCoordinator(List<ExternalJobSchedulerDelegate> delegates,
                                        ResourceSchedulerConfigService configService,
                                        ObjectMapper objectMapper) {
        // Prefer the delegate matching the configured externalSchedulerMode (e.g. "cluster" routes crawl
        // partitions to remote peers); otherwise fall back to Kubernetes, then the first available delegate.
        String mode = configService != null && configService.getConfiguration() != null
                ? configService.getConfiguration().getExternalSchedulerMode() : null;
        this.delegate = delegates.stream()
                .filter(d -> mode != null && mode.equalsIgnoreCase(d.getMode()))
                .findFirst()
                .or(() -> delegates.stream()
                        .filter(d -> d.getClass().getSimpleName().contains("Kubernetes"))
                        .findFirst())
                .orElse(delegates.get(0));
        this.objectMapper = objectMapper;
        this.configService = configService;
        log.info("DistributedCrawlCoordinator using external delegate mode '{}'", this.delegate.getMode());
    }

    /**
     * Start a distributed crawl by partitioning the request and dispatching
     * to workers via the external scheduler delegate.
     *
     * @param request the original crawl request with distribution config
     * @return the distributed session with tracking info
     */
    public DistributedCrawlSession startDistributed(UnifiedCrawlRequest request) {
        DistributionConfig distConfig = request.getDistribution();
        if (distConfig == null) {
            throw new IllegalArgumentException("Distribution config is required for distributed crawl");
        }

        String sessionId = UUID.randomUUID().toString();
        List<UnifiedCrawlSource> sources = request.getSources();
        if (sources == null || sources.isEmpty()) {
            throw new IllegalArgumentException("At least one source is required");
        }

        // Choose the live workers once, then partition. When the request doesn't pin a worker count
        // (workerCount=0) and there's a live cluster, size partitions to each worker's capacity (weighted);
        // otherwise split evenly. pins.get(i) is the worker partition i is pinned to (null → delegate selects).
        List<WorkerCapabilities> liveWorkers = liveCrawlWorkers();
        // Crawl partitions are CPU-profiled (JobResourceProfile.cpuOnly below), so GPU is preferred-not-
        // required — keep CPU workers fully eligible; the weight still discounts by CPU load/pressure.
        boolean requiresGpu = false;
        PartitionStrategy strategy = distConfig.getPartitionStrategy();
        boolean weighted = !liveWorkers.isEmpty() && distConfig.getWorkerCount() <= 0
                && (strategy == PartitionStrategy.ROUND_ROBIN
                    || strategy == PartitionStrategy.BY_TYPE
                    || strategy == PartitionStrategy.BY_SIZE);

        List<List<UnifiedCrawlSource>> partitions = new ArrayList<>();
        List<WorkerCapabilities> pins = new ArrayList<>();
        if (weighted) {
            List<List<UnifiedCrawlSource>> wp = weightedPartitions(sources, liveWorkers, requiresGpu); // aligned to liveWorkers
            for (int i = 0; i < wp.size(); i++) {
                if (!wp.get(i).isEmpty()) {
                    partitions.add(wp.get(i));
                    pins.add(liveWorkers.get(i));
                }
            }
        } else {
            for (List<UnifiedCrawlSource> p : partitionSources(sources, distConfig)) {
                partitions.add(p);
                pins.add(liveWorkers.isEmpty() ? null : liveWorkers.get(pins.size() % liveWorkers.size()));
            }
        }
        int workerCount = partitions.size();

        // Phase B: optionally divide the global remote-LLM / backend concurrency across the workers so N
        // partitions don't each open the full concurrency against the same external API (default-off).
        UnifiedCrawlRequest.RuntimeConfig scaledRuntime =
                scaleRuntimeConfigForWorker(request.getRuntimeConfig(), workerCount);
        ProcessingRouteConfig scaledRoute =
                scaleProcessingRouteForWorker(request.getProcessingRoute(), workerCount);

        log.info("Distributing crawl session {} across {} workers (strategy={})",
                sessionId, workerCount, distConfig.getPartitionStrategy());

        DistributedCrawlSession session = DistributedCrawlSession.builder()
                .sessionId(sessionId)
                .originalRequest(request)
                .status(DistributedCrawlSession.Status.DISPATCHING)
                .totalWorkers(workerCount)
                .startedAt(Instant.now())
                .build();

        activeSessions.put(sessionId, session);

        // Dispatch each partition to a worker
        for (int i = 0; i < partitions.size(); i++) {
            List<UnifiedCrawlSource> partition = partitions.get(i);
            String workerId = sessionId + "-worker-" + i;

            // Build per-worker request (same config, different sources)
            UnifiedCrawlRequest workerRequest = UnifiedCrawlRequest.builder()
                    .name(request.getName() + " [worker " + i + "]")
                    .factSheetId(request.getFactSheetId())
                    .factSheetName(request.getFactSheetName())
                    .sources(partition)
                    .graphExtraction(request.getGraphExtraction())
                    .vectorIndex(request.getVectorIndex())
                    .preprocessing(request.getPreprocessing())
                    .processingRoute(scaledRoute)
                    .runtimeConfig(scaledRuntime)
                    .pipelines(request.getPipelines())
                    .routeRules(request.getRouteRules())
                    .defaultPipelineId(request.getDefaultPipelineId())
                    .distribution(null) // Workers run locally, not distributed further
                    .build();

            try {
                String requestJson = objectMapper.writeValueAsString(workerRequest);
                Map<String, Object> metadata = new HashMap<>();
                metadata.put("sessionId", sessionId);
                metadata.put("workerId", workerId);
                metadata.put("workerIndex", i);
                metadata.put("crawlRequestJson", requestJson);
                if (pins.get(i) != null) {
                    metadata.put("targetWorkerBaseUrl", pins.get(i).baseUrl());
                }
                if (distConfig.getCallbackUrl() != null) {
                    metadata.put("callbackUrl", distConfig.getCallbackUrl());
                }
                if (distConfig.getWorkerMetadata() != null) {
                    metadata.putAll(distConfig.getWorkerMetadata());
                }

                String sourceLabels = partition.stream()
                        .map(s -> s.getLabel() != null ? s.getLabel() : s.getPathOrUrl())
                        .reduce((a, b) -> a + ", " + b)
                        .orElse("unknown");

                CompletableFuture<ExternalJobRef> submitFuture = delegate.submitJob(
                        workerId,
                        "crawl",
                        "Distributed crawl worker " + i + ": " + sourceLabels,
                        JobResourceProfile.cpuOnly("crawl", "Distributed Crawl Worker",
                                512 * 1024 * 1024L),
                        metadata
                );

                int workerIdx = i;
                submitFuture.whenComplete((ref, error) -> {
                    if (error != null) {
                        log.error("Failed to dispatch worker {} for session {}: {}",
                                workerIdx, sessionId, error.getMessage());
                        session.workerFailed(workerId, error.getMessage());
                    } else if (ref == null || "FAILED".equals(ref.status())) {
                        String why = ref != null ? ref.message() : "null submission ref";
                        log.error("Worker {} submission rejected for session {}: {}", workerIdx, sessionId, why);
                        session.workerFailed(workerId, why);
                    } else {
                        log.info("Worker {} dispatched for session {}: externalId={}",
                                workerIdx, sessionId, ref.externalId());
                        session.workerDispatched(workerId, ref.externalId());
                    }
                });

                session.addWorker(workerId, partition);

            } catch (Exception e) {
                log.error("Failed to serialize worker request for session {}: {}",
                        sessionId, e.getMessage());
                session.workerFailed(workerId, e.getMessage());
            }
        }

        session.setStatus(DistributedCrawlSession.Status.RUNNING);
        return session;
    }

    /**
     * Handle a completion callback from a worker.
     */
    public void handleWorkerCallback(String sessionId, String workerId,
                                      boolean success, String message,
                                      Map<String, Object> resultData) {
        DistributedCrawlSession session = activeSessions.get(sessionId);
        if (session == null) {
            log.warn("Received callback for unknown session: {}", sessionId);
            return;
        }

        if (success) {
            session.workerCompleted(workerId, resultData);
            log.info("Worker {} completed for session {} — {}/{} done",
                    workerId, sessionId, session.getCompletedWorkers().get(),
                    session.getTotalWorkers());
        } else {
            session.workerFailed(workerId, message);
            log.warn("Worker {} failed for session {}: {}", workerId, sessionId, message);
        }

        // Check if all workers are done
        if (session.isAllWorkersFinished()) {
            session.setStatus(session.getFailedWorkers().get() > 0
                    ? DistributedCrawlSession.Status.PARTIALLY_COMPLETED
                    : DistributedCrawlSession.Status.COMPLETED);
            session.setCompletedAt(Instant.now());
            log.info("Distributed crawl session {} completed: {}/{} succeeded",
                    sessionId, session.getCompletedWorkers().get(), session.getTotalWorkers());
        }
        publishAggregateProgress(session);
    }

    /**
     * Handle a periodic progress report from a worker (Phase C). Stores the worker's latest
     * {@link UnifiedCrawlJob.ProgressSnapshot} and republishes the merged aggregate so the unified SSE +
     * step monitor render the distributed crawl as one live job.
     */
    public void handleWorkerProgress(String sessionId, String workerId,
                                     UnifiedCrawlJob.ProgressSnapshot snapshot) {
        DistributedCrawlSession session = activeSessions.get(sessionId);
        if (session == null || snapshot == null) {
            return;
        }
        session.updateWorkerSnapshot(workerId, snapshot);
        publishAggregateProgress(session);
    }

    /** Merge per-worker snapshots and publish as one CrawlProgressEvent (no-op without publisher/aggregator). */
    private void publishAggregateProgress(DistributedCrawlSession session) {
        if (eventPublisher == null || aggregator == null) {
            return;
        }
        try {
            UnifiedCrawlJob.ProgressSnapshot agg = aggregator.aggregate(session);
            CrawlProgressEvent.EventType type = session.isAllWorkersFinished()
                    ? CrawlProgressEvent.EventType.COMPLETED
                    : CrawlProgressEvent.EventType.PROGRESS;
            String msg = session.getCompletedWorkers().get() + "/" + session.getTotalWorkers() + " workers done";
            eventPublisher.publishEvent(new CrawlProgressEvent(
                    this, "distributed-" + session.getSessionId(), agg, type, msg));
        } catch (Exception e) {
            log.debug("Failed to publish aggregate progress for session {}: {}",
                    session.getSessionId(), e.getMessage());
        }
    }

    /**
     * Re-dispatch a lost worker's partition to another capable worker (Phase E). Reuses the SAME session
     * {@code workerId} key so accounting stays correct — callbacks are idempotent, so a silently-revived
     * original can't double-count against the reassigned twin. Returns true if a reassignment was dispatched.
     */
    public boolean reassignWorkerPartition(DistributedCrawlSession session,
                                           DistributedCrawlSession.WorkerInfo worker) {
        if (session == null || worker == null) {
            return false;
        }
        UnifiedCrawlRequest original = session.getOriginalRequest();
        if (original == null || worker.getSources() == null || worker.getSources().isEmpty()) {
            return false;
        }
        String deadUrl = worker.getExternalRef();
        WorkerCapabilities target = liveCrawlWorkers().stream()
                .filter(w -> deadUrl == null || !deadUrl.contains(w.baseUrl()))
                .max(Comparator.comparingInt(w -> WorkerWeightFunction.compute(w, false)))
                .orElse(null);
        if (target == null) {
            session.workerFailed(worker.getWorkerId(), "no live worker available for reassignment");
            return false;
        }
        int workerCount = Math.max(1, session.getTotalWorkers());
        try {
            UnifiedCrawlRequest workerRequest = UnifiedCrawlRequest.builder()
                    .name(original.getName() + " [" + worker.getWorkerId() + " reassigned]")
                    .factSheetId(original.getFactSheetId())
                    .factSheetName(original.getFactSheetName())
                    .sources(worker.getSources())
                    .graphExtraction(original.getGraphExtraction())
                    .vectorIndex(original.getVectorIndex())
                    .preprocessing(original.getPreprocessing())
                    .processingRoute(scaleProcessingRouteForWorker(original.getProcessingRoute(), workerCount))
                    .runtimeConfig(scaleRuntimeConfigForWorker(original.getRuntimeConfig(), workerCount))
                    .pipelines(original.getPipelines())
                    .routeRules(original.getRouteRules())
                    .defaultPipelineId(original.getDefaultPipelineId())
                    .distribution(null)
                    .build();
            Map<String, Object> metadata = new HashMap<>();
            metadata.put("sessionId", session.getSessionId());
            metadata.put("workerId", worker.getWorkerId());
            metadata.put("crawlRequestJson", objectMapper.writeValueAsString(workerRequest));
            metadata.put("targetWorkerBaseUrl", target.baseUrl());

            String newJobId = worker.getWorkerId() + "-r" + (worker.getReassignmentCount() + 1);
            delegate.submitJob(newJobId, "crawl",
                            "Reassigned distributed crawl " + worker.getWorkerId(),
                            JobResourceProfile.cpuOnly("crawl", "Distributed Crawl Worker", 512 * 1024 * 1024L),
                            metadata)
                    .whenComplete((ref, err) -> {
                        if (err != null || ref == null || "FAILED".equals(ref.status())) {
                            String why = err != null ? err.getMessage()
                                    : (ref != null ? ref.message() : "null submission ref");
                            log.warn("Reassignment of partition {} failed: {}", worker.getWorkerId(), why);
                            session.workerFailed(worker.getWorkerId(), "reassignment failed: " + why);
                        } else {
                            worker.setReassignmentCount(worker.getReassignmentCount() + 1);
                            worker.setExternalRef(ref.externalId());
                            worker.setStatus(DistributedCrawlSession.WorkerStatus.RUNNING);
                            worker.setLastProgressAt(Instant.now());
                            log.warn("Reassigned partition {} to worker {} (attempt {})",
                                    worker.getWorkerId(), target.workerId(), worker.getReassignmentCount());
                        }
                    });
            return true;
        } catch (Exception e) {
            log.warn("Reassignment of partition {} errored: {}", worker.getWorkerId(), e.getMessage());
            return false;
        }
    }

    /**
     * Cancel all workers in a distributed session.
     */
    public boolean cancelSession(String sessionId) {
        DistributedCrawlSession session = activeSessions.get(sessionId);
        if (session == null) return false;

        session.setStatus(DistributedCrawlSession.Status.CANCELLED);
        for (DistributedCrawlSession.WorkerInfo worker : session.getWorkers().values()) {
            if (worker.getExternalRef() != null
                    && worker.getStatus() == DistributedCrawlSession.WorkerStatus.RUNNING) {
                delegate.cancelJob(worker.getWorkerId(), worker.getExternalRef())
                        .whenComplete((success, err) -> {
                            if (err != null) {
                                log.debug("Error cancelling worker {}: {}", worker.getWorkerId(), err.getMessage());
                            }
                        });
            }
        }
        return true;
    }

    /**
     * Get a distributed session by ID.
     */
    public Optional<DistributedCrawlSession> getSession(String sessionId) {
        return Optional.ofNullable(activeSessions.get(sessionId));
    }

    /**
     * List all distributed sessions.
     */
    public List<DistributedCrawlSession> getAllSessions() {
        return new ArrayList<>(activeSessions.values());
    }

    /**
     * Remove completed/failed/cancelled sessions.
     */
    public int cleanupSessions() {
        int removed = 0;
        Iterator<Map.Entry<String, DistributedCrawlSession>> it = activeSessions.entrySet().iterator();
        while (it.hasNext()) {
            DistributedCrawlSession s = it.next().getValue();
            if (s.getStatus() == DistributedCrawlSession.Status.COMPLETED
                    || s.getStatus() == DistributedCrawlSession.Status.PARTIALLY_COMPLETED
                    || s.getStatus() == DistributedCrawlSession.Status.CANCELLED
                    || s.getStatus() == DistributedCrawlSession.Status.FAILED) {
                it.remove();
                removed++;
            }
        }
        return removed;
    }

    // ---- Partitioning ----

    /**
     * Live workers that can take a {@code crawl} partition right now (advertise crawl support, accepting work,
     * a free slot). Empty when there's no registry or no eligible worker — callers then fall back.
     */
    List<WorkerCapabilities> liveCrawlWorkers() {
        if (workerRegistry == null) {
            return List.of();
        }
        List<WorkerCapabilities> out = new ArrayList<>();
        for (WorkerCapabilities w : workerRegistry.liveWorkers(System.currentTimeMillis())) {
            if (w.canRun("crawl", false)) {
                out.add(w);
            }
        }
        return out;
    }

    /** Default partition count when the request doesn't pin one: the live cluster size, else a small fan-out. */
    private int defaultWorkerCount(List<UnifiedCrawlSource> sources) {
        int live = liveCrawlWorkers().size();
        return Math.min(sources.size(), live > 0 ? live : 4);
    }

    /**
     * Smooth weighted round-robin: one partition per worker, sources distributed proportionally to each
     * worker's effective capacity ({@link WorkerWeightFunction} — free slots discounted by CPU/GPU load and
     * pressure), kept in the input order so partition {@code i} belongs to worker {@code i}. Idler/beefier
     * workers get more sources; saturated/draining workers get a zero weight (empty partition). Nginx-style
     * smooth WRR — interleaved, no bursts.
     */
    /** CPU-profile default (no GPU requirement). */
    List<List<UnifiedCrawlSource>> weightedPartitions(List<UnifiedCrawlSource> sources,
                                                       List<WorkerCapabilities> workers) {
        return weightedPartitions(sources, workers, false);
    }

    List<List<UnifiedCrawlSource>> weightedPartitions(List<UnifiedCrawlSource> sources,
                                                       List<WorkerCapabilities> workers,
                                                       boolean requiresGpu) {
        int n = workers.size();
        List<List<UnifiedCrawlSource>> result = new ArrayList<>();
        int[] weight = new int[n];
        long[] current = new long[n];
        long total = 0;
        for (int i = 0; i < n; i++) {
            weight[i] = WorkerWeightFunction.compute(workers.get(i), requiresGpu);
            total += weight[i];
            result.add(new ArrayList<>());
        }
        if (total == 0) {
            // Every live worker is saturated/draining — fall back to an even split so work still flows.
            for (int i = 0; i < n; i++) {
                weight[i] = 1;
            }
            total = n;
        }
        for (UnifiedCrawlSource src : sources) {
            int best = 0;
            for (int i = 0; i < n; i++) {
                current[i] += weight[i];
                if (current[i] > current[best]) {
                    best = i;
                }
            }
            current[best] -= total;
            result.get(best).add(src);
        }
        return result;
    }

    // ---- Phase B: per-worker backend concurrency cap injection ----

    /**
     * Divide the global remote-LLM parallelism across the workers so N partitions don't each open the full
     * concurrency against the same backend. Returns {@code base} unchanged unless cluster cap scaling is
     * enabled (default-off) or there's only one worker. The same divisor applies to every partition.
     */
    UnifiedCrawlRequest.RuntimeConfig scaleRuntimeConfigForWorker(
            UnifiedCrawlRequest.RuntimeConfig base, int workerCount) {
        if (configService == null || workerCount <= 1) {
            return base;
        }
        ResourceSchedulerConfig cfg = configService.getConfiguration();
        if (cfg == null || !cfg.isClusterBackendCapScalingEnabled()) {
            return base;
        }
        int global = base != null && base.getGraphExtractionRemoteParallelism() != null
                ? base.getGraphExtractionRemoteParallelism()
                : cfg.getClusterDefaultRemoteParallelism();
        int perWorker = Math.max(1, global / workerCount);
        UnifiedCrawlRequest.RuntimeConfig copy = deepCopy(base, UnifiedCrawlRequest.RuntimeConfig.class);
        if (copy == null) {
            copy = UnifiedCrawlRequest.RuntimeConfig.builder().build();
        }
        copy.setGraphExtractionRemoteParallelism(perWorker);
        log.info("Cluster backend cap scaling: remote LLM parallelism {} -> {} per worker ({} workers)",
                global, perWorker, workerCount);
        return copy;
    }

    /**
     * Divide each processing backend's {@code maxConcurrent} by the worker count so the aggregate in-flight
     * against a shared API roughly matches the single-node limit. Returns {@code base} unchanged when cap
     * scaling is off / single worker / no backends.
     */
    ProcessingRouteConfig scaleProcessingRouteForWorker(ProcessingRouteConfig base, int workerCount) {
        if (configService == null || workerCount <= 1 || base == null) {
            return base;
        }
        ResourceSchedulerConfig cfg = configService.getConfiguration();
        if (cfg == null || !cfg.isClusterBackendCapScalingEnabled()) {
            return base;
        }
        ProcessingRouteConfig copy = deepCopy(base, ProcessingRouteConfig.class);
        if (copy == null || copy.getBackends() == null) {
            return base;
        }
        for (ProcessingRouteConfig.ProcessingBackend b : copy.getBackends()) {
            if (b != null && b.getMaxConcurrent() > 0) {
                b.setMaxConcurrent(Math.max(1, b.getMaxConcurrent() / workerCount));
            }
        }
        return copy;
    }

    /** Jackson round-trip deep copy; returns the original on failure (logged), null on null input. */
    private <T> T deepCopy(T value, Class<T> type) {
        if (value == null) {
            return null;
        }
        try {
            return objectMapper.readValue(objectMapper.writeValueAsString(value), type);
        } catch (Exception e) {
            log.warn("Deep copy of {} failed, using original: {}", type.getSimpleName(), e.getMessage());
            return value;
        }
    }

    List<List<UnifiedCrawlSource>> partitionSources(List<UnifiedCrawlSource> sources,
                                                     DistributionConfig config) {
        PartitionStrategy strategy = config.getPartitionStrategy();
        int workerCount = config.getWorkerCount();

        return switch (strategy) {
            case PER_SOURCE -> {
                // One worker per source
                List<List<UnifiedCrawlSource>> result = new ArrayList<>();
                for (UnifiedCrawlSource source : sources) {
                    result.add(List.of(source));
                }
                yield result;
            }
            case ROUND_ROBIN -> {
                int numWorkers = workerCount > 0 ? workerCount : defaultWorkerCount(sources);
                List<List<UnifiedCrawlSource>> result = new ArrayList<>();
                for (int i = 0; i < numWorkers; i++) {
                    result.add(new ArrayList<>());
                }
                for (int i = 0; i < sources.size(); i++) {
                    result.get(i % numWorkers).add(sources.get(i));
                }
                // Remove empty partitions
                result.removeIf(List::isEmpty);
                yield result;
            }
            case HASH_SHARD -> {
                // All sources go to each worker; workers disambiguate via shard index
                int live = liveCrawlWorkers().size();
                int numWorkers = workerCount > 0 ? workerCount : Math.max(2, live);
                List<List<UnifiedCrawlSource>> result = new ArrayList<>();
                for (int i = 0; i < numWorkers; i++) {
                    result.add(new ArrayList<>(sources));
                }
                yield result;
            }
            case BY_TYPE, BY_SIZE -> {
                // Fall back to round-robin for BY_TYPE and BY_SIZE
                int numWorkers = workerCount > 0 ? workerCount : defaultWorkerCount(sources);
                List<List<UnifiedCrawlSource>> result = new ArrayList<>();
                for (int i = 0; i < numWorkers; i++) {
                    result.add(new ArrayList<>());
                }
                for (int i = 0; i < sources.size(); i++) {
                    result.get(i % numWorkers).add(sources.get(i));
                }
                result.removeIf(List::isEmpty);
                yield result;
            }
        };
    }
}
