/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package ai.kompile.knowledgegraph.embedding.service;

import ai.kompile.core.kgembedding.*;
import ai.kompile.knowledgegraph.embedding.domain.KGEmbeddingJob;
import ai.kompile.knowledgegraph.embedding.domain.KGEmbeddingJob.JobStatus;
import ai.kompile.knowledgegraph.embedding.impl.RotatEModel;
import ai.kompile.knowledgegraph.embedding.impl.TransEModel;
import ai.kompile.knowledgegraph.embedding.repository.KGEmbeddingJobRepository;
import ai.kompile.knowledgegraph.staging.ModelTrainedEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

import ai.kompile.knowledgegraph.embedding.adapter.KgEmbeddingGraphAdapter;

/**
 * Service for managing KG embedding training jobs.
 */
@Service
public class KGEmbeddingJobService {

    /** No-arg constructor for CGLIB proxy instantiation in GraalVM native image. */
    protected KGEmbeddingJobService() {}


    private static final Logger log = LoggerFactory.getLogger(KGEmbeddingJobService.class);

    private KGEmbeddingJobRepository jobRepository;
    private KGEmbeddingStorageService storageService;
    private SimpMessagingTemplate messagingTemplate;

    /**
     * Data directory root for writing KGE checkpoint stubs.
     * Defaults to {@code ~/.kompile} when not configured.
     */
    @Value("${kompile.data.dir:}")
    private String dataDir;

    /**
     * Spring event publisher for {@link ModelTrainedEvent}.
     * Optional (field-injected) so the existing constructor signature is unchanged and
     * contexts without a publisher (plain unit tests) still compile cleanly.
     */
    @Autowired(required = false)
    private ApplicationEventPublisher eventPublisher;

    /**
     * Store-agnostic graph adapters (JPA + live matrix store). Field-injected and optional so the
     * existing constructor signature is unchanged; when no adapters are wired (e.g. plain unit tests)
     * the job falls back to the JPA {@link KGEmbeddingStorageService} directly.
     */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private List<KgEmbeddingGraphAdapter> graphAdapters;

    // Track running models for cancellation
    private final Map<String, KGEmbeddingModel> runningModels = new ConcurrentHashMap<>();

    @Autowired
    public KGEmbeddingJobService(
            KGEmbeddingJobRepository jobRepository,
            KGEmbeddingStorageService storageService,
            @Autowired(required = false) SimpMessagingTemplate messagingTemplate
    ) {
        this.jobRepository = jobRepository;
        this.storageService = storageService;
        this.messagingTemplate = messagingTemplate;
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // JOB MANAGEMENT
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * Starts a new training job.
     */
    @Transactional
    public KGEmbeddingJob startTraining(Long factSheetId, KGEmbeddingAlgorithm algorithm, KGEmbeddingConfig config) {
        // Check for existing running job
        if (jobRepository.hasRunningJob(factSheetId)) {
            throw new IllegalStateException("A training job is already running for this fact sheet");
        }

        // Create job record
        KGEmbeddingJob job = KGEmbeddingJob.builder()
                .factSheetId(factSheetId)
                .algorithm(algorithm)
                .status(JobStatus.PENDING)
                .embeddingDim(config.embeddingDim())
                .epochs(config.epochs())
                .learningRate(config.learningRate())
                .batchSize(config.batchSize())
                .margin(config.margin())
                .negativeSamples(config.negativeSamples())
                .createdAt(Instant.now())
                .build();

        job = jobRepository.save(job);

        // Start async training
        executeTrainingAsync(job.getJobId(), factSheetId, algorithm, config);

        return job;
    }

    /**
     * Executes training asynchronously.
     */
    @Async
    public void executeTrainingAsync(String jobId, Long factSheetId, KGEmbeddingAlgorithm algorithm, KGEmbeddingConfig config) {
        KGEmbeddingJob job = jobRepository.findByJobId(jobId).orElse(null);
        if (job == null) {
            log.error("Job {} not found", jobId);
            return;
        }

        try {
            // Update status to running
            job.setStatus(JobStatus.RUNNING);
            job.setStartedAt(Instant.now());
            jobRepository.save(job);

            // Resolve which store actually holds the graph (prefer the live/matrix store), then extract
            // triples from it. Falls back to the JPA storage service when no adapters are wired.
            KgEmbeddingGraphAdapter adapter = resolveGraphAdapter(factSheetId);
            if (adapter != null) {
                log.info("Training KG embeddings for fact sheet {} from the '{}' store", factSheetId, adapter.storeType());
            }

            // Extract triples
            List<Triple> triples = adapter != null
                    ? adapter.extractTriples(factSheetId)
                    : storageService.extractTriples(factSheetId);
            job.setTotalTriples(triples.size());
            jobRepository.save(job);

            if (triples.isEmpty()) {
                job.setStatus(JobStatus.FAILED);
                job.setErrorMessage("No triples found for training. Create some graph edges first.");
                job.setCompletedAt(Instant.now());
                jobRepository.save(job);
                return;
            }

            // Create model
            KGEmbeddingModel model = createModel(algorithm);
            runningModels.put(jobId, model);

            // Configure with progress callback
            KGEmbeddingConfig configWithCallback = config.withProgressCallback(progress -> {
                // Update job progress
                job.setCurrentEpoch(progress.epoch());
                job.setCurrentLoss(progress.loss());
                jobRepository.save(job);

                // Send WebSocket update
                sendProgressUpdate(jobId, progress);
            });

            // Train
            TrainingResult result = model.train(triples, configWithCallback);

            if (result.success()) {
                // Store embeddings back into the same store the triples came from.
                Long version = System.currentTimeMillis();
                if (adapter != null) {
                    adapter.storeEmbeddings(model, factSheetId, version);
                } else {
                    storageService.storeEmbeddings(model, factSheetId, version);
                }

                job.setStatus(JobStatus.COMPLETED);
                job.setEmbeddingVersion(version);
                job.setEntitiesEmbedded(result.entitiesCount());
                job.setRelationsEmbedded(result.relationsCount());
                job.setCurrentLoss(result.finalLoss());

                // Publish ModelTrainedEvent so app-main can stage the KGE artifact.
                // Write a JSON stub file that carries embedding metadata (version, entity/relation
                // counts, loss) and serves as the staged artifact in the model registry.
                if (eventPublisher != null) {
                    try {
                        Path kgeArtifact = writeKgeCheckpointStub(factSheetId, version,
                                result.entitiesCount(), result.relationsCount(), result.finalLoss());
                        eventPublisher.publishEvent(
                                new ModelTrainedEvent(this, "kge", factSheetId, kgeArtifact, "kge-embedding"));
                        log.info("KGE training factSheet={}: published ModelTrainedEvent(kge, v{})", factSheetId, version);
                    } catch (Exception e) {
                        log.warn("KGE training factSheet={}: could not publish KGE ModelTrainedEvent — {}",
                                factSheetId, e.getMessage());
                        // Non-fatal: embeddings already stored in the graph store
                    }
                }
            } else {
                job.setStatus(result.errorMessage() != null && result.errorMessage().contains("cancelled")
                        ? JobStatus.CANCELLED : JobStatus.FAILED);
                job.setErrorMessage(result.errorMessage());
            }

        } catch (Exception e) {
            log.error("Training job {} failed", jobId, e);
            job.setStatus(JobStatus.FAILED);
            job.setErrorMessage(e.getMessage());
        } finally {
            runningModels.remove(jobId);
            job.setCompletedAt(Instant.now());
            jobRepository.save(job);

            // Send completion update
            sendCompletionUpdate(jobId, job);
        }
    }

    /**
     * Cancels a running job.
     */
    @Transactional
    public boolean cancelJob(String jobId) {
        KGEmbeddingModel model = runningModels.get(jobId);
        if (model != null) {
            model.cancelTraining();
            log.info("Cancellation requested for job {}", jobId);
            return true;
        }

        // Update job status if not running in this instance
        Optional<KGEmbeddingJob> jobOpt = jobRepository.findByJobId(jobId);
        if (jobOpt.isPresent()) {
            KGEmbeddingJob job = jobOpt.get();
            if (job.getStatus() == JobStatus.PENDING || job.getStatus() == JobStatus.RUNNING) {
                job.setStatus(JobStatus.CANCELLED);
                job.setCompletedAt(Instant.now());
                jobRepository.save(job);
                return true;
            }
        }

        return false;
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // QUERIES
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * Gets a job by ID.
     */
    @Transactional(readOnly = true)
    public Optional<KGEmbeddingJob> getJob(String jobId) {
        return jobRepository.findByJobId(jobId);
    }

    /**
     * Gets jobs for a fact sheet.
     */
    @Transactional(readOnly = true)
    public Page<KGEmbeddingJob> getJobs(Long factSheetId, Pageable pageable) {
        return jobRepository.findByFactSheetIdOrderByCreatedAtDesc(factSheetId, pageable);
    }

    /**
     * Gets the most recent completed job for a fact sheet.
     */
    @Transactional(readOnly = true)
    public Optional<KGEmbeddingJob> getMostRecentCompletedJob(Long factSheetId) {
        List<KGEmbeddingJob> jobs = jobRepository.findMostRecentCompletedJob(factSheetId, PageRequest.of(0, 1));
        return jobs.isEmpty() ? Optional.empty() : Optional.of(jobs.get(0));
    }

    /**
     * Checks if there's a running job for a fact sheet.
     */
    @Transactional(readOnly = true)
    public boolean hasRunningJob(Long factSheetId) {
        return jobRepository.hasRunningJob(factSheetId);
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // HELPERS
    // ═══════════════════════════════════════════════════════════════════════════

    private KGEmbeddingModel createModel(KGEmbeddingAlgorithm algorithm) {
        return switch (algorithm) {
            case TRANSE -> new TransEModel();
            case ROTATE -> new RotatEModel();
        };
    }

    /**
     * Picks the graph adapter for a fact sheet: the highest-priority adapter that actually has graph
     * data (so the live matrix store wins when populated), else the highest-priority adapter as a
     * best-effort fallback. Returns {@code null} when no adapters are wired (unit-test / minimal
     * context), in which case the caller uses the JPA storage service directly.
     */
    private KgEmbeddingGraphAdapter resolveGraphAdapter(Long factSheetId) {
        if (graphAdapters == null || graphAdapters.isEmpty()) {
            return null;
        }
        List<KgEmbeddingGraphAdapter> byPriority = graphAdapters.stream()
                .sorted(java.util.Comparator.comparingInt(KgEmbeddingGraphAdapter::priority).reversed())
                .toList();
        for (KgEmbeddingGraphAdapter adapter : byPriority) {
            try {
                if (adapter.hasGraphData(factSheetId)) {
                    return adapter;
                }
            } catch (Exception e) {
                log.warn("Adapter '{}' failed hasGraphData check for fact sheet {}: {}",
                        adapter.storeType(), factSheetId, e.getMessage());
            }
        }
        return byPriority.get(0);
    }

    private void sendProgressUpdate(String jobId, TrainingProgress progress) {
        if (messagingTemplate != null) {
            try {
                messagingTemplate.convertAndSend(
                        "/topic/kg-embedding/jobs/" + jobId,
                        Map.of(
                                "type", "progress",
                                "jobId", jobId,
                                "epoch", progress.epoch(),
                                "totalEpochs", progress.totalEpochs(),
                                "loss", progress.loss(),
                                "progressPercent", progress.progressPercent()
                        )
                );
            } catch (Exception e) {
                log.debug("Failed to send progress update: {}", e.getMessage());
            }
        }
    }

    /**
     * Write a lightweight JSON checkpoint stub for a completed KGE training run.
     * The file records the embedding version, entity/relation counts, and final loss so
     * that the model registry has a concrete artifact to stage without needing to
     * serialise the full embedding tensors.
     *
     * <p>Path: {@code <dataDir>/models/kge/<factSheetId>-v<version>.json}</p>
     *
     * @param factSheetId     fact sheet identifier
     * @param version         embedding version (Unix epoch millis)
     * @param entitiesCount   number of entity embeddings produced
     * @param relationsCount  number of relation embeddings produced
     * @param finalLoss       final training loss
     * @return absolute path to the written stub file
     */
    private Path writeKgeCheckpointStub(Long factSheetId, Long version,
                                        int entitiesCount, int relationsCount, double finalLoss) {
        Path kgeDir = resolveKgeDir();
        try {
            Files.createDirectories(kgeDir);
        } catch (IOException e) {
            throw new UncheckedIOException("KGEmbeddingJobService: cannot create KGE dir " + kgeDir, e);
        }
        Path stub = kgeDir.resolve(factSheetId + "-v" + version + ".json");
        String json = "{\"factSheetId\":" + factSheetId
                + ",\"version\":" + version
                + ",\"entitiesCount\":" + entitiesCount
                + ",\"relationsCount\":" + relationsCount
                + ",\"finalLoss\":" + String.format(java.util.Locale.ROOT, "%.17g", finalLoss)
                + "}";
        try {
            Files.writeString(stub, json, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("KGEmbeddingJobService: cannot write KGE stub " + stub, e);
        }
        log.debug("KGE checkpoint stub written: {}", stub);
        return stub;
    }

    private Path resolveKgeDir() {
        Path base = (dataDir == null || dataDir.isBlank())
                ? Path.of(System.getProperty("user.home"), ".kompile")
                : Path.of(dataDir);
        return base.resolve("models").resolve("kge");
    }

    private void sendCompletionUpdate(String jobId, KGEmbeddingJob job) {
        if (messagingTemplate != null) {
            try {
                messagingTemplate.convertAndSend(
                        "/topic/kg-embedding/jobs/" + jobId,
                        Map.of(
                                "type", "completed",
                                "jobId", jobId,
                                "status", job.getStatus().name(),
                                "entitiesEmbedded", job.getEntitiesEmbedded() != null ? job.getEntitiesEmbedded() : 0,
                                "relationsEmbedded", job.getRelationsEmbedded() != null ? job.getRelationsEmbedded() : 0,
                                "errorMessage", job.getErrorMessage() != null ? job.getErrorMessage() : ""
                        )
                );
            } catch (Exception e) {
                log.debug("Failed to send completion update: {}", e.getMessage());
            }
        }
    }
}
