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
import ai.kompile.core.graphbuilder.GraphBuildCompletedEvent;
import ai.kompile.knowledgegraph.generation.GraphGeneration;
import ai.kompile.knowledgegraph.generation.GraphGenerationJournal;
import ai.kompile.knowledgegraph.service.KnowledgeGraphService;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.Duration;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
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

    private static final SecureRandom LEASE_RANDOM = new SecureRandom();
    private static final Duration WRITER_LEASE_DURATION = Duration.ofMinutes(15);
    private static final Set<String> RESERVED_WORKER_METADATA = Set.of(
            "sessionId", "workerId", "workerIndex", "attempt", "externalJobId",
            "crawlRequestJson", "targetWorkerBaseUrl", "callbackUrl", "graphWriterLease");

    public enum WriterLeaseVerdict {
        VALID, UNKNOWN_SESSION, UNKNOWN_PARTITION, STALE_ATTEMPT, REVOKED, EXPIRED, INVALID_TOKEN
    }

    private final ExternalJobSchedulerDelegate delegate;
    private final ObjectMapper objectMapper;
    /** Nullable in unit tests; guarded everywhere it's read. */
    private final ResourceSchedulerConfigService configService;

    /** Active distributed jobs keyed by coordinator job ID */
    private final ConcurrentMap<String, DistributedCrawlSession> activeSessions =
            new ConcurrentHashMap<>();

    @Autowired(required = false)
    private UnifiedCrawlService unifiedCrawlService;

    /** Optional full-application fact-sheet resolver; workers must receive a concrete scope. */
    @Autowired(required = false)
    private CrawlFactSheetScopeResolver factSheetScopeResolver;

    /** Optional: live cluster view, so partitioning spreads across the actual workers (capability-aware). */
    @Autowired(required = false)
    private CrawlWorkerRegistry workerRegistry;
    @Autowired(required = false)
    private KnowledgeGraphService knowledgeGraphService;

    /** Optional: republishes merged per-worker progress as CrawlProgressEvents so the unified SSE +
     *  step monitor render a distributed crawl as one live job (Phase C). Null in unit tests → no-op. */
    @Autowired(required = false)
    private ApplicationEventPublisher eventPublisher;

    @Autowired(required = false)
    private DistributedCrawlAggregator aggregator;

    /** Optional: durable session persistence + crash recovery (Phase 1). Null in unit tests → in-memory only. */
    @Autowired(required = false)
    private DistributedCrawlSessionStore sessionStore;

    private final ConcurrentMap<String, Long> lastPersistMs = new ConcurrentHashMap<>();
    private static final long PROGRESS_PERSIST_THROTTLE_MS = 30_000L;

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
        if (request == null) {
            throw new IllegalArgumentException("Crawl request is required");
        }
        if (factSheetScopeResolver != null) {
            factSheetScopeResolver.resolveScope(request);
        } else if (request.getFactSheetId() == null
                && request.getFactSheetName() != null
                && !request.getFactSheetName().isBlank()) {
            throw new IllegalArgumentException("Fact sheet '" + request.getFactSheetName().trim()
                    + "' must resolve to a concrete ID before distributed dispatch");
        }

        DistributionConfig distConfig = request.getDistribution();
        if (distConfig == null) {
            throw new IllegalArgumentException("Distribution config is required for distributed crawl");
        }
        boolean replacement = request.getRuntimeConfig() != null
                && Boolean.TRUE.equals(request.getRuntimeConfig().getClearGraphBeforeRun());
        if (distConfig.getWorkerMetadata() != null) {
            Set<String> reserved = new LinkedHashSet<>(distConfig.getWorkerMetadata().keySet());
            reserved.retainAll(RESERVED_WORKER_METADATA);
            if (!reserved.isEmpty()) {
                throw new IllegalArgumentException("workerMetadata contains reserved keys: " + reserved);
            }
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

        if (replacement) {
            validateReplacementPreflight(request, pins, workerCount);
        }

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
                .status(replacement ? DistributedCrawlSession.Status.PREPARING
                        : DistributedCrawlSession.Status.DISPATCHING)
                .totalWorkers(workerCount)
                .startedAt(Instant.now())
                .build();

        GraphGeneration.Ref sharedGeneration = null;
        if (replacement) {
            sharedGeneration = knowledgeGraphService.beginFactSheetGeneration(
                    request.getFactSheetId(), sessionId, "distributed:" + sessionId);
            session.setGraphGeneration(toSnapshot(sharedGeneration, "BUILDING", null));
            session.setStatus(DistributedCrawlSession.Status.DISPATCHING);
        }

        activeSessions.put(sessionId, session);
        persist(session, true);

        // Dispatch each partition to a worker
        for (int i = 0; i < partitions.size(); i++) {
            List<UnifiedCrawlSource> partition = partitions.get(i);
            String workerId = sessionId + "-worker-" + i;

            // Build per-worker request (same config, different sources)
            UnifiedCrawlRequest workerRequest = copyForWorker(
                    request, partition, request.getName() + " [worker " + i + "]",
                    scaledRuntime, scaledRoute, sessionId, workerId, i, workerCount, 1,
                    sharedGeneration);

            try {
                session.addWorker(workerId, partition, i);
                String graphLease = issueWriterLease(session, workerId, 1);
                String requestJson = objectMapper.writeValueAsString(workerRequest);
                Map<String, Object> metadata = new HashMap<>();
                metadata.put("sessionId", sessionId);
                metadata.put("workerId", workerId);
                metadata.put("workerIndex", i);
                metadata.put("attempt", 1);
                metadata.put("externalJobId", workerId);
                metadata.put("graphWriterLease", graphLease);
                metadata.put("crawlRequestJson", requestJson);
                if (pins.get(i) != null) {
                    metadata.put("targetWorkerBaseUrl", pins.get(i).baseUrl());
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
                        session.workerFailed(workerId, 1, error.getMessage());
                        failReplacementSession(session, error.getMessage());
                    } else if (ref == null || "FAILED".equals(ref.status())) {
                        String why = ref != null ? ref.message() : "null submission ref";
                        log.error("Worker {} submission rejected for session {}: {}", workerIdx, sessionId, why);
                        session.workerFailed(workerId, 1, why);
                        failReplacementSession(session, why);
                    } else {
                        log.info("Worker {} dispatched for session {}: externalId={}",
                                workerIdx, sessionId, ref.externalId());
                        session.workerDispatched(workerId, ref.externalId(), workerId, 1);
                    }
                });

            } catch (Exception e) {
                log.error("Failed to serialize worker request for session {}: {}",
                        sessionId, e.getMessage());
                if (!session.getWorkers().containsKey(workerId)) session.addWorker(workerId, partition);
                session.workerFailed(workerId, 1, e.getMessage());
                failReplacementSession(session, e.getMessage());
            }
        }

        if (session.getStatus() == DistributedCrawlSession.Status.DISPATCHING) {
            session.setStatus(DistributedCrawlSession.Status.RUNNING);
        }
        persist(session, true);
        return session;
    }

    /**
     * Handle a completion callback from a worker.
     */
    public void handleWorkerCallback(String sessionId, String workerId,
                                      boolean success, String message,
                                      Map<String, Object> resultData) {
        DistributedCrawlSession session = activeSessions.get(sessionId);
        handleWorkerCallback(sessionId, workerId,
                session != null ? session.currentAttempt(workerId) : -1,
                success, message, resultData);
    }

    public void handleWorkerCallback(String sessionId, String workerId, int attempt,
                                     boolean success, String message,
                                     Map<String, Object> resultData) {
        DistributedCrawlSession session = activeSessions.get(sessionId);
        if (session == null) {
            log.warn("Received callback for unknown session: {}", sessionId);
            return;
        }
        if (isTerminal(session.getStatus())
                || session.getStatus() == DistributedCrawlSession.Status.CANCELLING
                || session.getStatus() == DistributedCrawlSession.Status.ABORTING) {
            log.debug("Ignoring callback for terminal/closing distributed session {}", sessionId);
            return;
        }
        if (!session.isCurrentAttempt(workerId, attempt)) {
            log.warn("Ignoring stale callback for session {} worker {} attempt {} (current={})",
                    sessionId, workerId, attempt, session.currentAttempt(workerId));
            return;
        }

        synchronized (session) {
        if (success) {
            session.workerCompleted(workerId, attempt, resultData);
            log.info("Worker {} completed for session {} — {}/{} done",
                    workerId, sessionId, session.getCompletedWorkers().get(),
                    session.getTotalWorkers());
        } else if (shouldReassignOnFailure(session, workerId, message, resultData)) {
            log.warn("Worker {} reported a retriable failure for session {} ({}) — reassigning partition",
                    workerId, sessionId, message);
            if (!reassignWorkerPartition(session, session.getWorkers().get(workerId))) {
                session.workerFailed(workerId, attempt, "reassignment rejected after failure: " + message);
                failReplacementSession(session, message);
            }
        } else {
            session.workerFailed(workerId, attempt, message);
            log.warn("Worker {} failed for session {}: {}", workerId, sessionId, message);
            failReplacementSession(session, message);
        }

        // Check if all workers are done
        if (session.isAllWorkersFinished()) {
            if (session.getGraphGeneration() != null) {
                completeReplacementSession(session);
                persist(session, true);
                publishAggregateProgress(session);
                return;
            }
            session.setStatus(session.getFailedWorkers().get() > 0
                    ? DistributedCrawlSession.Status.PARTIALLY_COMPLETED
                    : DistributedCrawlSession.Status.COMPLETED);
            session.setCompletedAt(Instant.now());
            log.info("Distributed crawl session {} completed: {}/{} succeeded",
                    sessionId, session.getCompletedWorkers().get(), session.getTotalWorkers());
        }
        persist(session, true);
        publishAggregateProgress(session);
        }
    }

    /**
     * Handle a periodic progress report from a worker (Phase C). Stores the worker's latest
     * {@link UnifiedCrawlJob.ProgressSnapshot} and republishes the merged aggregate so the unified SSE +
     * step monitor render the distributed crawl as one live job.
     */
    public void handleWorkerProgress(String sessionId, String workerId,
                                     UnifiedCrawlJob.ProgressSnapshot snapshot) {
        DistributedCrawlSession session = activeSessions.get(sessionId);
        handleWorkerProgress(sessionId, workerId,
                session != null ? session.currentAttempt(workerId) : -1, snapshot);
    }

    public void handleWorkerProgress(String sessionId, String workerId, int attempt,
                                     UnifiedCrawlJob.ProgressSnapshot snapshot) {
        DistributedCrawlSession session = activeSessions.get(sessionId);
        if (session == null || snapshot == null || !session.isCurrentAttempt(workerId, attempt)) {
            return;
        }
        synchronized (session) {
            session.updateWorkerSnapshot(workerId, attempt, snapshot);
            DistributedCrawlSession.WorkerInfo worker = session.getWorkers().get(workerId);
            if (worker != null) worker.setLastProgressAt(Instant.now());
            persist(session, false);
            publishAggregateProgress(session);
        }
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

    public boolean isCurrentWorkerAttempt(String sessionId, String workerId, int attempt) {
        DistributedCrawlSession session = activeSessions.get(sessionId);
        return session != null && session.isCurrentAttempt(workerId, attempt);
    }

    public WriterLeaseVerdict validateWriterLease(
            String sessionId, String workerId, int attempt, String rawToken, boolean markWrite) {
        DistributedCrawlSession session = activeSessions.get(sessionId);
        if (session == null) return WriterLeaseVerdict.UNKNOWN_SESSION;
        DistributedCrawlSession.WorkerInfo worker = session.getWorkers().get(workerId);
        if (worker == null) return WriterLeaseVerdict.UNKNOWN_PARTITION;
        if (!session.isCurrentAttempt(workerId, attempt)) return WriterLeaseVerdict.STALE_ATTEMPT;
        if (worker.isLeaseRevoked()) return WriterLeaseVerdict.REVOKED;
        if (worker.getLeaseExpiresAt() == null || worker.getLeaseExpiresAt().isBefore(Instant.now())) {
            return WriterLeaseVerdict.EXPIRED;
        }
        if (rawToken == null || worker.getLeaseTokenHash() == null
                || !MessageDigest.isEqual(hashLease(rawToken).getBytes(StandardCharsets.US_ASCII),
                worker.getLeaseTokenHash().getBytes(StandardCharsets.US_ASCII))) {
            return WriterLeaseVerdict.INVALID_TOKEN;
        }
        worker.setLeaseExpiresAt(Instant.now().plus(WRITER_LEASE_DURATION));
        if (markWrite) worker.setAcceptedGraphWrites(true);
        return WriterLeaseVerdict.VALID;
    }

    public Optional<DistributedCrawlPartitionBarrier.Decision> handlePartitionBarrier(
            String sessionId, String workerId, int attempt,
            UnifiedCrawlJob.ProgressSnapshot snapshot) {
        DistributedCrawlSession session = activeSessions.get(sessionId);
        if (session == null || !session.isCurrentAttempt(workerId, attempt)) return Optional.empty();
        synchronized (session) {
            DistributedCrawlSession.WorkerInfo worker = session.getWorkers().get(workerId);
            worker.setLatestSnapshot(snapshot);
            worker.setLastProgressAt(Instant.now());
            if (!partitionSucceeded(snapshot)) {
                worker.setPartitionPhase("LOCAL_GRAPH_FAILED");
                session.workerFailed(workerId, attempt, "partition reported a hard local graph failure");
                failReplacementSession(session, "partition hard failure before barrier");
                return Optional.of(DistributedCrawlPartitionBarrier.Decision.ABORT);
            }
            worker.setPartitionPhase("LOCAL_GRAPH_READY");
            boolean allReady = session.getWorkers().values().stream().allMatch(candidate ->
                    "LOCAL_GRAPH_READY".equals(candidate.getPartitionPhase())
                            || candidate.getStatus() == DistributedCrawlSession.WorkerStatus.COMPLETED);
            if (!allReady) {
                persist(session, false);
                return Optional.empty();
            }
            if (session.getFinalizerWorkerId() == null) {
                DistributedCrawlSession.WorkerInfo finalizer = session.getWorkers().values().stream()
                        .min(Comparator.comparingInt(DistributedCrawlSession.WorkerInfo::getPartitionIndex))
                        .orElseThrow();
                session.setFinalizerWorkerId(finalizer.getWorkerId());
                finalizer.setFinalizer(true);
            }
            boolean finalizer = workerId.equals(session.getFinalizerWorkerId());
            session.getWorkers().values().stream()
                    .filter(candidate -> !candidate.getWorkerId().equals(session.getFinalizerWorkerId()))
                    .forEach(candidate -> candidate.setLeaseRevoked(true));
            persist(session, true);
            return Optional.of(finalizer
                    ? DistributedCrawlPartitionBarrier.Decision.RUN_CORPUS_FINALIZATION
                    : DistributedCrawlPartitionBarrier.Decision.COMPLETE_PARTITION);
        }
    }

    private static boolean partitionSucceeded(UnifiedCrawlJob.ProgressSnapshot snapshot) {
        if (snapshot == null || snapshot.getStatus() == UnifiedCrawlJob.Status.FAILED
                || snapshot.getStatus() == UnifiedCrawlJob.Status.CANCELLED
                || snapshot.getStatus() == UnifiedCrawlJob.Status.CANCELLING) return false;
        if (snapshot.getSourceProgress() != null && snapshot.getSourceProgress().stream().anyMatch(source ->
                source != null && source.getStatus() == UnifiedCrawlJob.Status.FAILED)) return false;
        if (snapshot.getPipelineSteps() == null) return true;
        Set<String> hard = Set.of("LOADING", "GRAPH_PREP", "GRAPH_EXTRACTION", "SURFACING");
        return snapshot.getPipelineSteps().stream().noneMatch(step ->
                step != null && hard.contains(step.getStepId())
                        && step.getStatus() == UnifiedCrawlJob.PipelineStepStatus.FAILED);
    }

    public void revokeWriterLeases(String sessionId) {
        DistributedCrawlSession session = activeSessions.get(sessionId);
        if (session == null) return;
        session.getWorkers().values().forEach(worker -> worker.setLeaseRevoked(true));
        persist(session, true);
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
        if (session.getGraphGeneration() != null && worker.isAcceptedGraphWrites()) {
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
            persist(session, true);
            return false;
        }
        // Phase 1: don't re-crawl sources this partition already finished — the shared graph/vector stores
        // already hold them. Match the worker's completed-source keys against its assigned sources.
        Set<String> alreadyDone = worker.completedSourceKeys();
        List<UnifiedCrawlSource> remaining = worker.getSources().stream()
                .filter(s -> !alreadyDone.contains(
                        DistributedCrawlSession.sourceKey(s.getLabel(), s.getPathOrUrl())))
                .toList();
        if (remaining.isEmpty()) {
            log.info("Reassignment of partition {}: all {} source(s) already completed — marking done",
                    worker.getWorkerId(), worker.getSources().size());
            session.workerCompleted(worker.getWorkerId(), Map.of("note", "all sources completed before loss"));
            persist(session, true);
            return true;
        }
        if (remaining.size() < worker.getSources().size()) {
            log.info("Reassignment of partition {}: skipping {} already-completed source(s), re-crawling {}",
                    worker.getWorkerId(), worker.getSources().size() - remaining.size(), remaining.size());
        }
        int workerCount = Math.max(1, session.getTotalWorkers());
        int nextAttempt = Math.max(2, worker.getAttempt() + 1);
        try {
            UnifiedCrawlRequest workerRequest = copyForWorker(
                    original, remaining,
                    original.getName() + " [" + worker.getWorkerId() + " reassigned]",
                    scaleRuntimeConfigForWorker(original.getRuntimeConfig(), workerCount),
                    scaleProcessingRouteForWorker(original.getProcessingRoute(), workerCount),
                    session.getSessionId(), worker.getWorkerId(), worker.getPartitionIndex(),
                    workerCount, nextAttempt, generationFromSession(session));
            String newJobId = worker.getWorkerId() + "-r" + (nextAttempt - 1);
            session.beginAttempt(worker.getWorkerId(), newJobId, nextAttempt);
            String graphLease = issueWriterLease(session, worker.getWorkerId(), nextAttempt);
            Map<String, Object> metadata = new HashMap<>();
            metadata.put("sessionId", session.getSessionId());
            metadata.put("workerId", worker.getWorkerId());
            metadata.put("attempt", nextAttempt);
            metadata.put("externalJobId", newJobId);
            metadata.put("graphWriterLease", graphLease);
            metadata.put("crawlRequestJson", objectMapper.writeValueAsString(workerRequest));
            metadata.put("targetWorkerBaseUrl", target.baseUrl());

            delegate.submitJob(newJobId, "crawl",
                            "Reassigned distributed crawl " + worker.getWorkerId(),
                            JobResourceProfile.cpuOnly("crawl", "Distributed Crawl Worker", 512 * 1024 * 1024L),
                            metadata)
                    .whenComplete((ref, err) -> {
                        if (err != null || ref == null || "FAILED".equals(ref.status())) {
                            String why = err != null ? err.getMessage()
                                    : (ref != null ? ref.message() : "null submission ref");
                            log.warn("Reassignment of partition {} failed: {}", worker.getWorkerId(), why);
                            session.workerFailed(worker.getWorkerId(), nextAttempt,
                                    "reassignment failed: " + why);
                        } else {
                            session.workerDispatched(worker.getWorkerId(), ref.externalId(),
                                    newJobId, nextAttempt);
                            log.warn("Reassigned partition {} to worker {} (attempt {})",
                                    worker.getWorkerId(), target.workerId(), nextAttempt);
                        }
                        persist(session, true);
                    });
            return true;
        } catch (Exception e) {
            log.warn("Reassignment of partition {} errored: {}", worker.getWorkerId(), e.getMessage());
            session.workerFailed(worker.getWorkerId(), worker.getAttempt(),
                    "reassignment errored: " + e.getMessage());
            persist(session, true);
            return false;
        }
    }

    public void handlePartitionLoss(DistributedCrawlSession session,
                                    DistributedCrawlSession.WorkerInfo worker,
                                    String reason) {
        handlePartitionLoss(session, worker, reason, true);
    }

    void handlePartitionLoss(DistributedCrawlSession session,
                             DistributedCrawlSession.WorkerInfo worker,
                             String reason,
                             boolean allowReassignment) {
        if (session == null || worker == null) return;
        synchronized (session) {
            if (allowReassignment && reassignWorkerPartition(session, worker)) return;
            session.workerFailed(worker.getWorkerId(), worker.getAttempt(), reason);
            failReplacementSession(session, reason);
            persist(session, true);
        }
    }

    /**
     * Cancel all workers in a distributed session.
     */
    public boolean cancelSession(String sessionId) {
        DistributedCrawlSession session = activeSessions.get(sessionId);
        if (session == null) return false;

        synchronized (session) {
        session.setStatus(DistributedCrawlSession.Status.CANCELLING);
        revokeWriterLeases(sessionId);
        for (DistributedCrawlSession.WorkerInfo worker : session.getWorkers().values()) {
            if (worker.getExternalRef() != null
                    && worker.getStatus() == DistributedCrawlSession.WorkerStatus.RUNNING) {
                String externalJobId = worker.getCurrentExternalJobId() != null
                        ? worker.getCurrentExternalJobId() : worker.getWorkerId();
                delegate.cancelJob(externalJobId, worker.getExternalRef())
                        .whenComplete((success, err) -> {
                            if (err != null) {
                                log.debug("Error cancelling worker {}: {}", worker.getWorkerId(), err.getMessage());
                            }
                        });
            }
        }
        if (session.getGraphGeneration() != null) {
            abortReplacementSession(session, "distributed crawl cancelled");
        }
        session.setStatus(DistributedCrawlSession.Status.CANCELLED);
        persist(session, true);
        return true;
        }
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
                if (sessionStore != null) {
                    sessionStore.delete(s.getSessionId());
                }
                lastPersistMs.remove(s.getSessionId());
                removed++;
            }
        }
        return removed;
    }

    // ---- Phase 1: durable persistence + crash recovery ----

    /**
     * Persist the session manifest when persistence is enabled. {@code force=true} writes immediately (lifecycle
     * transitions); {@code force=false} throttles to the progress cadence so ~4s worker reports don't thrash the
     * disk. No-op without a store or when the feature is disabled.
     */
    private void persist(DistributedCrawlSession session, boolean force) {
        if (sessionStore == null || session == null || session.getSessionId() == null) {
            return;
        }
        if (configService == null || configService.getConfiguration() == null
                || !configService.getConfiguration().isClusterSessionPersistenceEnabled()) {
            return;
        }
        long now = System.currentTimeMillis();
        if (!force) {
            Long last = lastPersistMs.get(session.getSessionId());
            if (last != null && now - last < PROGRESS_PERSIST_THROTTLE_MS) {
                return;
            }
        }
        lastPersistMs.put(session.getSessionId(), now);
        if (force) sessionStore.persist(session);
        else sessionStore.persistAsync(session);
    }

    private static boolean isTerminal(DistributedCrawlSession.Status s) {
        return s == DistributedCrawlSession.Status.COMPLETED
                || s == DistributedCrawlSession.Status.PARTIALLY_COMPLETED
                || s == DistributedCrawlSession.Status.FAILED
                || s == DistributedCrawlSession.Status.CANCELLED;
    }

    // ---- Phase 2: reassign on reported failure ----

    /** Whether a reported worker failure should be re-dispatched: feature on, worker non-terminal, under the
     *  reassignment bound, and the failure looks retriable. */
    private boolean shouldReassignOnFailure(DistributedCrawlSession session, String workerId,
                                            String message, Map<String, Object> resultData) {
        ResourceSchedulerConfig cfg = configService != null ? configService.getConfiguration() : null;
        if (cfg == null || !cfg.isClusterReassignOnFailure()) {
            return false;
        }
        DistributedCrawlSession.WorkerInfo w = session.getWorkers().get(workerId);
        if (w == null
                || w.getStatus() == DistributedCrawlSession.WorkerStatus.COMPLETED
                || w.getStatus() == DistributedCrawlSession.WorkerStatus.FAILED
                || w.getStatus() == DistributedCrawlSession.WorkerStatus.CANCELLED) {
            return false; // unknown or already terminal (idempotent against duplicate callbacks)
        }
        if (w.getReassignmentCount() >= Math.max(0, cfg.getClusterMaxReassignments())) {
            return false; // bound reached — let it fail
        }
        return isRetriableFailure(message, resultData);
    }

    private static final List<String> FATAL_FAILURE_MARKERS = List.of(
            "missing", "invalid", "no unifiedcrawlservice", "unauthorized", "forbidden",
            "not found", "malformed", "bad request", "unsupported", "cancelled");

    /**
     * Classify a reported worker failure as transient (worth a bounded retry — OOM, infra, backend) vs
     * deterministic/fatal (cancelled, missing/invalid config, auth — would just fail again). An explicit
     * {@code resultData.retriable} Boolean from the worker wins; otherwise classify by {@code status} + message,
     * defaulting to retriable (the retry count is bounded by {@code clusterMaxReassignments}).
     */
    static boolean isRetriableFailure(String message, Map<String, Object> resultData) {
        if (resultData != null && resultData.get("retriable") instanceof Boolean b) {
            return b;
        }
        Object status = resultData != null ? resultData.get("status") : null;
        if (status != null && "CANCELLED".equalsIgnoreCase(status.toString())) {
            return false;
        }
        if (message != null) {
            String m = message.toLowerCase(Locale.ROOT);
            for (String fatal : FATAL_FAILURE_MARKERS) {
                if (m.contains(fatal)) {
                    return false;
                }
            }
        }
        return true;
    }

    /**
     * On startup, reload persisted non-terminal sessions and reconcile each worker against the live scheduler
     * (Phase 1). Orchestrator-only and gated by {@code clusterSessionPersistenceEnabled}. A worker still running
     * resumes tracking; one that finished during downtime is marked complete; one that's gone is reassigned (if
     * {@code clusterReassignOnLoss}) or failed.
     */
    @EventListener(ApplicationReadyEvent.class)
    public void reconcilePersistedSessions() {
        if (sessionStore == null) {
            return;
        }
        ResourceSchedulerConfig cfg = configService != null ? configService.getConfiguration() : null;
        if (cfg == null || !cfg.isClusterSessionPersistenceEnabled() || !cfg.isClusterOrchestrator()) {
            return;
        }
        int resumed = 0;
        for (DistributedCrawlSession.Manifest m : sessionStore.loadAll()) {
            if (m.getStatus() == null) {
                continue;
            }
            if (isTerminal(m.getStatus())) {
                sessionStore.delete(m.getSessionId()); // finished before the restart — nothing to recover
                continue;
            }
            DistributedCrawlSession session = DistributedCrawlSession.fromManifest(m);
            activeSessions.put(session.getSessionId(), session);
            if (!reconcileReplacementLifecycle(session)) {
                reconcileWorkers(session);
            }
            resumed++;
        }
        if (resumed > 0) {
            log.info("Reconciled {} persisted distributed-crawl session(s) on startup", resumed);
        }
    }

    /** Reconcile each non-terminal worker of a reloaded session against the external scheduler's current view. */
    private void reconcileWorkers(DistributedCrawlSession session) {
        boolean reassignEnabled = configService != null && configService.getConfiguration() != null
                && configService.getConfiguration().isClusterReassignOnLoss();
        for (DistributedCrawlSession.WorkerInfo w : session.getWorkers().values()) {
            if (w.getStatus() != DistributedCrawlSession.WorkerStatus.RUNNING
                    && w.getStatus() != DistributedCrawlSession.WorkerStatus.DISPATCHING) {
                continue; // already terminal in the manifest
            }
            String statusStr = null;
            if (w.getExternalRef() != null) {
                try {
                    ExternalJobSchedulerDelegate.ExternalJobStatus st =
                            delegate.getJobStatus(
                                    w.getCurrentExternalJobId() != null
                                            ? w.getCurrentExternalJobId() : w.getWorkerId(),
                                    w.getExternalRef()).get(10, TimeUnit.SECONDS);
                    statusStr = st != null ? st.status() : null;
                } catch (Exception e) {
                    log.debug("Reconcile: status check for {} failed: {}", w.getWorkerId(), e.getMessage());
                }
            }
            if ("COMPLETED".equalsIgnoreCase(statusStr)) {
                session.workerCompleted(w.getWorkerId(), Map.of("note", "reconciled: completed during downtime"));
            } else if ("RUNNING".equalsIgnoreCase(statusStr) || "PENDING".equalsIgnoreCase(statusStr)) {
                w.setStatus(DistributedCrawlSession.WorkerStatus.RUNNING);
                w.setLastProgressAt(Instant.now()); // alive — reset the loss clock for the reaper
            } else if (reassignEnabled) {
                if (!reassignWorkerPartition(session, w)) {
                    session.workerFailed(w.getWorkerId(), w.getAttempt(),
                            "reconcile: partition could not be safely reassigned");
                    failReplacementSession(session, "worker loss after graph writes");
                }
            } else {
                session.workerFailed(w.getWorkerId(),
                        "reconcile: worker not recoverable (status=" + statusStr + ")");
            }
        }
        if (session.isAllWorkersFinished()) {
            if (session.getGraphGeneration() != null) {
                completeReplacementSession(session);
            } else if (session.getStatus() == DistributedCrawlSession.Status.RUNNING) {
                session.setStatus(session.getFailedWorkers().get() > 0
                        ? DistributedCrawlSession.Status.PARTIALLY_COMPLETED
                        : DistributedCrawlSession.Status.COMPLETED);
                session.setCompletedAt(Instant.now());
            }
        }
        persist(session, true);
    }

    private boolean reconcileReplacementLifecycle(DistributedCrawlSession session) {
        if (session.getGraphGeneration() == null || knowledgeGraphService == null) return false;
        try {
            Optional<GraphGenerationJournal.Entry> status = knowledgeGraphService
                    .getFactSheetGenerationStatus(session.getGraphGeneration().factSheetId());
            if (status.isPresent()
                    && status.get().state() == GraphGenerationJournal.State.ACTIVE
                    && Objects.equals(status.get().pointer().activePhysicalGraphId(),
                    session.getGraphGeneration().physicalGraphId())) {
                GraphGeneration.Activation activation = status.get().activation();
                if (activation != null) {
                    session.setGraphActivation(new UnifiedCrawlJob.GraphActivationSnapshot(
                            activation.logicalGraphId(), activation.activePhysicalGraphId(),
                            activation.previousPhysicalGraphId(), activation.revision(), activation.activatedAt()));
                }
                session.setStatus(DistributedCrawlSession.Status.COMPLETED);
                session.setCompletedAt(Instant.now());
                persist(session, true);
                return true;
            }
            if (status.isPresent() && status.get().state() == GraphGenerationJournal.State.ABORTED) {
                session.setGraphGeneration(new UnifiedCrawlJob.GraphGenerationSnapshot(
                        session.getGraphGeneration().factSheetId(), session.getGraphGeneration().logicalGraphId(),
                        session.getGraphGeneration().physicalGraphId(), session.getGraphGeneration().generationId(),
                        session.getGraphGeneration().expectedActivePhysicalGraphId(),
                        session.getGraphGeneration().expectedRevision(), "ABORTED", status.get().lastError()));
                session.setStatus(DistributedCrawlSession.Status.FAILED);
                session.setCompletedAt(Instant.now());
                persist(session, true);
                return true;
            }
            if (session.getStatus() == DistributedCrawlSession.Status.SEALING) {
                completeReplacementSession(session);
                persist(session, true);
                return isTerminal(session.getStatus());
            }
            if (session.getStatus() == DistributedCrawlSession.Status.ABORTING
                    || session.getStatus() == DistributedCrawlSession.Status.CANCELLING) {
                abortReplacementSession(session, "coordinator restarted during abort/cancellation");
                persist(session, true);
                return true;
            }
        } catch (RuntimeException unavailable) {
            log.warn("Could not reconcile replacement generation {}: {}",
                    session.getSessionId(), unavailable.getMessage());
        }
        return false;
    }

    public Optional<DistributedCrawlSession> retrySession(String sessionId) {
        DistributedCrawlSession previous = activeSessions.get(sessionId);
        if (previous == null || !isTerminal(previous.getStatus()) || previous.getOriginalRequest() == null) {
            return Optional.empty();
        }
        UnifiedCrawlRequest retry = objectMapper.convertValue(
                objectMapper.valueToTree(previous.getOriginalRequest()), UnifiedCrawlRequest.class);
        retry.setName((retry.getName() == null ? "Distributed crawl" : retry.getName()) + " (retry)");
        return Optional.of(startDistributed(retry));
    }

    // ---- Partitioning ----

    /** Deep-copy the complete request, then override only partition-local execution fields. */
    private UnifiedCrawlRequest copyForWorker(
            UnifiedCrawlRequest original,
            List<UnifiedCrawlSource> sources,
            String name,
            UnifiedCrawlRequest.RuntimeConfig runtimeConfig,
            ProcessingRouteConfig processingRoute,
            String sessionId,
            String partitionId,
            int partitionIndex,
            int partitionCount,
            int attempt,
            GraphGeneration.Ref generation) {
        UnifiedCrawlRequest copy = objectMapper.convertValue(
                objectMapper.valueToTree(original), UnifiedCrawlRequest.class);
        copy.setName(name);
        copy.setSources(sources == null ? List.of() : new ArrayList<>(sources));
        UnifiedCrawlRequest.RuntimeConfig workerRuntime = runtimeConfig == null
                ? UnifiedCrawlRequest.RuntimeConfig.builder().build()
                : objectMapper.convertValue(objectMapper.valueToTree(runtimeConfig),
                UnifiedCrawlRequest.RuntimeConfig.class);
        // Phase-1 fail-closed invariant: a worker must never infer replacement mode from its
        // local/global config while it has distribution=null and still targets a local graph child.
        workerRuntime.setClearGraphBeforeRun(false);
        copy.setRuntimeConfig(workerRuntime);
        copy.setProcessingRoute(processingRoute);
        copy.setDistribution(null);
        copy.setDistributedGraphExecution(generation == null ? null : new DistributedGraphExecution(
                1, sessionId, partitionId, partitionIndex, partitionCount, attempt,
                "distributed:" + sessionId, copy.getFactSheetId(),
                generation.logicalGraphId(), generation.physicalGraphId(), generation.generationId(),
                generation.expectedActivePhysicalGraphId(), generation.expectedRevision(), true, true));
        return copy;
    }

    private void validateReplacementPreflight(
            UnifiedCrawlRequest request, List<WorkerCapabilities> pins, int workerCount) {
        if (request.getFactSheetId() == null) {
            throw new IllegalArgumentException("Distributed replacement requires a resolved fact sheet");
        }
        if (knowledgeGraphService == null || !knowledgeGraphService.supportsGraphGenerations()) {
            throw new IllegalArgumentException("Authoritative graph generation service is unavailable");
        }
        ResourceSchedulerConfig config = configService != null ? configService.getConfiguration() : null;
        if (config == null || config.getExternalAuthToken() == null
                || config.getExternalAuthToken().isBlank()) {
            throw new IllegalArgumentException("Distributed replacement requires a nonblank cluster token");
        }
        if (!config.isClusterSessionPersistenceEnabled() || sessionStore == null) {
            throw new IllegalArgumentException("Distributed replacement requires durable session persistence");
        }
        if (!"cluster".equalsIgnoreCase(delegate.getMode())) {
            throw new IllegalArgumentException(
                    "Distributed replacement currently requires the authenticated remote-peer cluster delegate");
        }
        if (workerCount <= 0 || pins.size() != workerCount || pins.stream().anyMatch(Objects::isNull)) {
            throw new IllegalArgumentException("Distributed replacement requires explicitly compatible live workers");
        }
        if (pins.stream().anyMatch(worker -> !worker.supportsDistributedGraphWriter(1)
                || worker.distributedPartitionBarrierVersion() < 1)) {
            throw new IllegalArgumentException(
                    "Every replacement worker must advertise graph writer v1 and barrier v1");
        }
    }

    private GraphGeneration.Ref generationFromSession(DistributedCrawlSession session) {
        UnifiedCrawlJob.GraphGenerationSnapshot snapshot = session.getGraphGeneration();
        return snapshot == null ? null : new GraphGeneration.Ref(
                snapshot.factSheetId(), snapshot.logicalGraphId(), snapshot.physicalGraphId(),
                snapshot.generationId(), snapshot.expectedActivePhysicalGraphId(),
                snapshot.expectedRevision());
    }

    private void completeReplacementSession(DistributedCrawlSession session) {
        if (session.getGraphActivation() != null) {
            session.setStatus(DistributedCrawlSession.Status.COMPLETED);
            return;
        }
        if (session.getFailedWorkers().get() > 0
                || session.getFinalizerWorkerId() == null
                || session.getWorkers().get(session.getFinalizerWorkerId()).getStatus()
                != DistributedCrawlSession.WorkerStatus.COMPLETED) {
            abortReplacementSession(session, "replacement partition or finalizer failed");
            return;
        }
        GraphGeneration.Ref generation = generationFromSession(session);
        try {
            revokeWriterLeases(session.getSessionId());
            session.setStatus(DistributedCrawlSession.Status.SEALING);
            GraphGeneration.Validation validation =
                    knowledgeGraphService.validateFactSheetGeneration(generation);
            if (!validation.valid()) {
                throw new IllegalStateException("distributed replacement validation failed: " + validation.errors());
            }
            GraphGeneration.Activation activation = activateDistributedGeneration(session, generation);
            session.setGraphActivation(new UnifiedCrawlJob.GraphActivationSnapshot(
                    activation.logicalGraphId(), activation.activePhysicalGraphId(),
                    activation.previousPhysicalGraphId(), activation.revision(), activation.activatedAt()));
            session.setGraphGeneration(toSnapshot(generation, "ACTIVE", null));
            session.setStatus(DistributedCrawlSession.Status.COMPLETED);
            session.setCompletedAt(Instant.now());
            publishReplacementCompleted(session);
        } catch (RuntimeException failure) {
            abortReplacementSession(session, failure.getMessage());
        }
    }

    private void failReplacementSession(DistributedCrawlSession session, String reason) {
        if (session != null && session.getGraphGeneration() != null
                && session.getGraphActivation() == null) {
            abortReplacementSession(session, reason);
        }
    }

    private void publishReplacementCompleted(DistributedCrawlSession session) {
        if (eventPublisher == null || session.getOriginalRequest() == null) return;
        int entities = session.getWorkers().values().stream()
                .map(DistributedCrawlSession.WorkerInfo::getLatestSnapshot)
                .filter(Objects::nonNull)
                .mapToInt(UnifiedCrawlJob.ProgressSnapshot::getEntitiesExtracted).sum();
        int relationships = session.getWorkers().values().stream()
                .map(DistributedCrawlSession.WorkerInfo::getLatestSnapshot)
                .filter(Objects::nonNull)
                .mapToInt(UnifiedCrawlJob.ProgressSnapshot::getRelationshipsExtracted).sum();
        try {
            eventPublisher.publishEvent(new GraphBuildCompletedEvent(
                    this, "distributed-" + session.getSessionId(), entities, relationships,
                    session.getOriginalRequest().getFactSheetId(), Map.of()));
        } catch (RuntimeException listenerFailure) {
            log.warn("Distributed replacement {} activated but completion event failed: {}",
                    session.getSessionId(), listenerFailure.getMessage());
        }
    }

    private GraphGeneration.Activation activateDistributedGeneration(
            DistributedCrawlSession session, GraphGeneration.Ref generation) {
        String operationId = "distributed:" + session.getSessionId() + ":activate";
        RuntimeException lastFailure = null;
        for (int attempt = 0; attempt < 2; attempt++) {
            try {
                return knowledgeGraphService.activateFactSheetGeneration(generation, operationId);
            } catch (RuntimeException failure) {
                lastFailure = failure;
                Optional<GraphGenerationJournal.Entry> status = knowledgeGraphService
                        .getFactSheetGenerationStatus(generation.factSheetId());
                if (status.isPresent()
                        && status.get().state() == GraphGenerationJournal.State.ACTIVE
                        && Objects.equals(status.get().pointer().activePhysicalGraphId(),
                        generation.physicalGraphId())
                        && status.get().activation() != null) {
                    return status.get().activation();
                }
            }
        }
        throw lastFailure != null ? lastFailure
                : new IllegalStateException("distributed replacement activation returned no result");
    }

    private void abortReplacementSession(DistributedCrawlSession session, String reason) {
        if (session.getGraphGeneration() == null || session.getGraphActivation() != null
                || "ABORTED".equals(session.getGraphGeneration().state())) return;
        session.setStatus(DistributedCrawlSession.Status.ABORTING);
        revokeWriterLeases(session.getSessionId());
        GraphGeneration.Ref generation = generationFromSession(session);
        boolean aborted = false;
        try {
            knowledgeGraphService.abortFactSheetGeneration(generation, reason);
            aborted = true;
        } catch (RuntimeException abortFailure) {
            log.error("Could not abort distributed replacement {}: {}",
                    session.getSessionId(), abortFailure.getMessage(), abortFailure);
            try {
                Optional<GraphGenerationJournal.Entry> status = knowledgeGraphService
                        .getFactSheetGenerationStatus(generation.factSheetId());
                if (status.isPresent() && status.get().state() == GraphGenerationJournal.State.ABORTED) {
                    aborted = true;
                } else if (status.isPresent() && status.get().state() == GraphGenerationJournal.State.ACTIVE
                        && status.get().activation() != null) {
                    GraphGeneration.Activation activation = status.get().activation();
                    session.setGraphActivation(new UnifiedCrawlJob.GraphActivationSnapshot(
                            activation.logicalGraphId(), activation.activePhysicalGraphId(),
                            activation.previousPhysicalGraphId(), activation.revision(), activation.activatedAt()));
                    session.setStatus(DistributedCrawlSession.Status.COMPLETED);
                    session.setCompletedAt(Instant.now());
                    return;
                }
            } catch (RuntimeException reconciliationFailure) {
                abortFailure.addSuppressed(reconciliationFailure);
            }
        }
        session.setGraphGeneration(toSnapshot(generation, aborted ? "ABORTED" : "ABORT_UNCERTAIN", reason));
        session.setStatus(aborted ? DistributedCrawlSession.Status.FAILED
                : DistributedCrawlSession.Status.ABORTING);
        session.setCompletedAt(aborted ? Instant.now() : null);
    }

    private static UnifiedCrawlJob.GraphGenerationSnapshot toSnapshot(
            GraphGeneration.Ref generation, String state, String error) {
        return new UnifiedCrawlJob.GraphGenerationSnapshot(
                generation.factSheetId(), generation.logicalGraphId(), generation.physicalGraphId(),
                generation.generationId(), generation.expectedActivePhysicalGraphId(),
                generation.expectedRevision(), state, error);
    }

    private String issueWriterLease(DistributedCrawlSession session, String workerId, int attempt) {
        byte[] tokenBytes = new byte[32];
        LEASE_RANDOM.nextBytes(tokenBytes);
        String raw = Base64.getUrlEncoder().withoutPadding().encodeToString(tokenBytes);
        DistributedCrawlSession.WorkerInfo worker = session.getWorkers().get(workerId);
        if (worker == null || worker.getAttempt() != attempt) {
            throw new IllegalStateException("Cannot issue lease for stale worker attempt");
        }
        worker.setLeaseTokenHash(hashLease(raw));
        worker.setLeaseExpiresAt(Instant.now().plus(WRITER_LEASE_DURATION));
        worker.setLeaseRevoked(false);
        return raw;
    }

    private static String hashLease(String raw) {
        try {
            return Base64.getUrlEncoder().withoutPadding().encodeToString(
                    MessageDigest.getInstance("SHA-256").digest(raw.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }

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
