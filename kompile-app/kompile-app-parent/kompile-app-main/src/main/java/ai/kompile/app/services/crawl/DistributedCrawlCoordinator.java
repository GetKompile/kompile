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

import ai.kompile.app.services.scheduler.ExternalJobSchedulerDelegate;
import ai.kompile.app.services.scheduler.ExternalJobSchedulerDelegate.ExternalJobRef;
import ai.kompile.app.services.scheduler.JobResourceProfile;
import ai.kompile.core.crawl.graph.*;
import ai.kompile.core.crawl.graph.UnifiedCrawlRequest.DistributionConfig;
import ai.kompile.core.crawl.graph.UnifiedCrawlRequest.PartitionStrategy;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
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

    /** Active distributed jobs keyed by coordinator job ID */
    private final ConcurrentMap<String, DistributedCrawlSession> activeSessions =
            new ConcurrentHashMap<>();

    @Autowired(required = false)
    private UnifiedCrawlService unifiedCrawlService;

    public DistributedCrawlCoordinator(List<ExternalJobSchedulerDelegate> delegates,
                                        ObjectMapper objectMapper) {
        // Pick the first available delegate — Kubernetes takes precedence if both are present
        this.delegate = delegates.stream()
                .filter(d -> d.getClass().getSimpleName().contains("Kubernetes"))
                .findFirst()
                .orElse(delegates.get(0));
        this.objectMapper = objectMapper;
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

        // Partition sources across workers
        List<List<UnifiedCrawlSource>> partitions = partitionSources(sources, distConfig);
        int workerCount = partitions.size();

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
                    .processingRoute(request.getProcessingRoute())
                    .runtimeConfig(request.getRuntimeConfig())
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
                int numWorkers = workerCount > 0 ? workerCount : Math.min(sources.size(), 4);
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
                int numWorkers = workerCount > 0 ? workerCount : 2;
                List<List<UnifiedCrawlSource>> result = new ArrayList<>();
                for (int i = 0; i < numWorkers; i++) {
                    result.add(new ArrayList<>(sources));
                }
                yield result;
            }
            case BY_TYPE, BY_SIZE -> {
                // Fall back to round-robin for BY_TYPE and BY_SIZE
                int numWorkers = workerCount > 0 ? workerCount : Math.min(sources.size(), 4);
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
