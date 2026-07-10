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

import ai.kompile.core.crawl.graph.HeavyMemoryCoordinator;
import ai.kompile.core.kgembedding.*;
import ai.kompile.core.kgembedding.KgeTrainingExecutor.KgeTrainingResult;
import ai.kompile.knowledgegraph.embedding.adapter.KgEmbeddingGraphAdapter;
import ai.kompile.knowledgegraph.embedding.domain.KGEmbeddingJob;
import ai.kompile.knowledgegraph.embedding.domain.KGEmbeddingJob.JobStatus;
import ai.kompile.knowledgegraph.embedding.config.KGEmbeddingConfigService;
import ai.kompile.knowledgegraph.embedding.impl.RotatEModel;
import ai.kompile.knowledgegraph.embedding.impl.SameDiffKgeModel;
import ai.kompile.knowledgegraph.embedding.impl.TransEModel;
import ai.kompile.knowledgegraph.embedding.repository.KGEmbeddingJobRepository;
import ai.kompile.knowledgegraph.staging.ModelTrainedEvent;
import org.nd4j.linalg.api.ndarray.INDArray;
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
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

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

    /**
     * Optional serialization gate for heavy in-memory model operations. When present (app-main
     * context) KGE training waits for any concurrent embedding/indexing op to finish before
     * calling {@code model.train()} — preventing the OOM crash on large-model hosts. When absent
     * (plain unit tests or contexts without app-main) training proceeds without the gate.
     */
    @Autowired(required = false)
    private HeavyMemoryCoordinator heavyMemoryCoordinator;

    /**
     * Optional out-of-process KGE training executor. When present and enabled
     * ({@code kompile.learning.subprocess.enabled=true}) the expensive
     * {@code model.train()} call is delegated to a separate JVM so that an OOM
     * there cannot kill the main app. When absent the existing in-JVM path is
     * used unchanged. Injected field-style from {@code kompile-app-main}.
     */
    @Autowired(required = false)
    private KgeTrainingExecutor kgeTrainingExecutor;

    /**
     * Optional managed-config service; when absent, {@code useSameDiffKge} defaults to {@code false}
     * (hand-rolled TransE/RotatE path stays active).  Wired automatically when the
     * {@code kompile-knowledge-graph} Spring context is present (unit tests and bare kompile-core
     * contexts lack it, which is the safe fallback).
     */
    @Autowired(required = false)
    private KGEmbeddingConfigService kgeConfigService;

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
     * Trains KGE embeddings <em>synchronously</em> — the calling thread blocks until training
     * completes or fails.
     *
     * <p>This is the inline-crawl path: the crawl pipeline calls this method so it can hold
     * the job in {@code RUNNING} state and forward per-epoch progress events to the crawl UI.
     * The normal {@link #startTraining} path uses {@link #executeTrainingAsync} ({@code @Async})
     * and returns immediately; this variant does not.</p>
     *
     * <p>When the out-of-process executor ({@link KgeTrainingExecutor}) is absent the method
     * falls back to the same in-JVM training path used by {@code executeTrainingAsync}.</p>
     *
     * @param crawlJobId  crawl job ID for keying progress callbacks (forwarded to the executor)
     * @param factSheetId fact sheet to train on
     * @param algorithm   KGE algorithm
     * @param config      training hyper-parameters
     * @param callback    per-epoch progress callback; may be {@code null}
     * @return the persisted {@link KGEmbeddingJob} record (COMPLETED or FAILED)
     * @throws IllegalStateException if a training job is already running for this fact sheet
     */
    @Transactional
    public KGEmbeddingJob trainSynchronously(String crawlJobId,
                                             Long factSheetId,
                                             KGEmbeddingAlgorithm algorithm,
                                             KGEmbeddingConfig config,
                                             KgeTrainingExecutor.ProgressCallback callback) {
        if (jobRepository.hasRunningJob(factSheetId)) {
            throw new IllegalStateException("A training job is already running for this fact sheet");
        }

        KGEmbeddingJob kgeJob = KGEmbeddingJob.builder()
                .factSheetId(factSheetId)
                .algorithm(algorithm)
                .status(JobStatus.RUNNING)
                .embeddingDim(config.embeddingDim())
                .epochs(config.epochs())
                .learningRate(config.learningRate())
                .batchSize(config.batchSize())
                .margin(config.margin())
                .negativeSamples(config.negativeSamples())
                .createdAt(Instant.now())
                .startedAt(Instant.now())
                .build();
        kgeJob = jobRepository.save(kgeJob);

        try {
            KgEmbeddingGraphAdapter adapter = resolveGraphAdapter(factSheetId);

            List<Triple> triples = adapter != null
                    ? adapter.extractTriples(factSheetId)
                    : storageService.extractTriples(factSheetId);
            kgeJob.setTotalTriples(triples.size());
            jobRepository.save(kgeJob);

            if (triples.isEmpty()) {
                kgeJob.setStatus(JobStatus.FAILED);
                kgeJob.setErrorMessage("No triples found for training. Create some graph edges first.");
                kgeJob.setCompletedAt(Instant.now());
                jobRepository.save(kgeJob);
                return kgeJob;
            }

            // ── Warm-start: load persisted embeddings from the prior run ──────────
            // If the adapter has saved vectors from a previous crawl, pass them to the
            // executor so the subprocess can seed from them (instead of random init) and
            // run only warmStartEpochs (a fraction of full epochs) as an incremental update.
            // Empty map = cold start (first crawl, or adapter doesn't support read-back).
            Map<String, INDArray> priorEmbeddings = adapter != null
                    ? adapter.loadEmbeddings(factSheetId)
                    : Collections.emptyMap();
            // Also load relation embeddings persisted via the sentinel-node path.
            // Empty = cold-start relations (first crawl or adapter doesn't support it).
            Map<String, INDArray> priorRelationEmbeddings = adapter != null
                    ? adapter.loadRelationEmbeddings(factSheetId)
                    : Collections.emptyMap();
            boolean isWarmStart = !priorEmbeddings.isEmpty();
            KGEmbeddingConfig effectiveConfig = isWarmStart
                    ? config.toBuilder().epochs(config.effectiveWarmStartEpochs()).build()
                    : config;
            if (isWarmStart) {
                log.info("[crawlJob={}] Warm-starting KGE: {} prior entity embeddings, {} relation embeddings " +
                        "(factSheet={}); using {} epochs instead of {}",
                        crawlJobId, priorEmbeddings.size(), priorRelationEmbeddings.size(),
                        factSheetId, effectiveConfig.epochs(), config.epochs());
            } else {
                log.info("[crawlJob={}] Cold-start KGE: no prior embeddings (factSheet={}); " +
                        "using {} epochs", crawlJobId, factSheetId, config.epochs());
            }

            if (kgeTrainingExecutor != null) {
                // Out-of-process path — blocks until the subprocess finishes.
                log.info("[crawlJob={}] Delegating inline KGE training to out-of-process executor (factSheet={})",
                        crawlJobId, factSheetId);
                KgeTrainingExecutor.KgeTrainingResult oopResult =
                        kgeTrainingExecutor.trainOutOfProcess(crawlJobId, factSheetId, algorithm, effectiveConfig,
                                triples, priorEmbeddings, priorRelationEmbeddings, callback);

                Long version = System.currentTimeMillis();
                if (oopResult.success()) {
                    if (adapter != null) {
                        adapter.storeEmbeddings(oopResult.model(), factSheetId, version);
                    } else {
                        storageService.storeEmbeddings(oopResult.model(), factSheetId, version);
                    }
                    kgeJob.setStatus(JobStatus.COMPLETED);
                    kgeJob.setEmbeddingVersion(version);
                    kgeJob.setEntitiesEmbedded(oopResult.model().getEntityCount());
                    kgeJob.setRelationsEmbedded(oopResult.model().getRelationCount());
                    kgeJob.setCurrentLoss(oopResult.finalLoss());
                    if (eventPublisher != null) {
                        try {
                            Path kgeArtifact = writeKgeCheckpointStub(factSheetId, version,
                                    oopResult.model().getEntityCount(),
                                    oopResult.model().getRelationCount(),
                                    oopResult.finalLoss());
                            eventPublisher.publishEvent(
                                    new ModelTrainedEvent(this, "kge", factSheetId, kgeArtifact, "kge-embedding"));
                        } catch (Exception e) {
                            log.warn("[crawlJob={}] KGE inline: could not publish ModelTrainedEvent — {}",
                                    crawlJobId, e.getMessage());
                        }
                    }
                } else {
                    kgeJob.setStatus(JobStatus.FAILED);
                    kgeJob.setErrorMessage(oopResult.errorMessage());
                }
            } else {
                // In-JVM fallback path — same as executeTrainingAsync but synchronous.
                KGEmbeddingModel model = createModel(algorithm);
                // Warm-start: import prior entity embeddings so train() seeds from them.
                // Also import relation embeddings when they are available; the model's
                // importRelationEmbeddings() handles dim-match and index rebuild internally.
                if (isWarmStart) {
                    model.importEntityEmbeddings(priorEmbeddings);
                }
                if (!priorRelationEmbeddings.isEmpty()) {
                    model.importRelationEmbeddings(priorRelationEmbeddings);
                }
                runningModels.put(kgeJob.getJobId(), model);
                try {
                    // Capture as effectively-final for lambda use; kgeJob was reassigned above.
                    final KGEmbeddingJob finalKgeJob = kgeJob;
                    KGEmbeddingConfig configWithCallback = effectiveConfig.withProgressCallback(progress -> {
                        finalKgeJob.setCurrentEpoch(progress.epoch());
                        finalKgeJob.setCurrentLoss(progress.loss());
                        jobRepository.save(finalKgeJob);
                        sendProgressUpdate(finalKgeJob.getJobId(), progress);
                        if (callback != null) {
                            try {
                                callback.onProgress(crawlJobId, progress.epoch(), config.epochs(), progress.loss());
                            } catch (Exception ignored) {}
                        }
                    });
                    TrainingResult result;
                    if (heavyMemoryCoordinator != null) {
                        try (AutoCloseable token = heavyMemoryCoordinator.acquire("kge-training-inline", kgeJob.getJobId())) {
                            result = model.train(triples, configWithCallback);
                        } catch (InterruptedException ie) {
                            Thread.currentThread().interrupt();
                            throw new RuntimeException("KGE inline training interrupted while waiting for heavy-memory gate", ie);
                        } catch (Exception e) {
                            if (e instanceof RuntimeException) throw (RuntimeException) e;
                            throw new RuntimeException(e);
                        }
                    } else {
                        result = model.train(triples, configWithCallback);
                    }
                    if (result.success()) {
                        Long version = System.currentTimeMillis();
                        if (adapter != null) {
                            adapter.storeEmbeddings(model, factSheetId, version);
                        } else {
                            storageService.storeEmbeddings(model, factSheetId, version);
                        }
                        kgeJob.setStatus(JobStatus.COMPLETED);
                        kgeJob.setEmbeddingVersion(version);
                        kgeJob.setEntitiesEmbedded(result.entitiesCount());
                        kgeJob.setRelationsEmbedded(result.relationsCount());
                        kgeJob.setCurrentLoss(result.finalLoss());
                        if (eventPublisher != null) {
                            try {
                                Path kgeArtifact = writeKgeCheckpointStub(factSheetId, version,
                                        result.entitiesCount(), result.relationsCount(), result.finalLoss());
                                eventPublisher.publishEvent(
                                        new ModelTrainedEvent(this, "kge", factSheetId, kgeArtifact, "kge-embedding"));
                            } catch (Exception e) {
                                log.warn("[crawlJob={}] KGE inline in-JVM: could not publish ModelTrainedEvent — {}",
                                        crawlJobId, e.getMessage());
                            }
                        }
                    } else {
                        kgeJob.setStatus(result.errorMessage() != null && result.errorMessage().contains("cancelled")
                                ? JobStatus.CANCELLED : JobStatus.FAILED);
                        kgeJob.setErrorMessage(result.errorMessage());
                    }
                } finally {
                    runningModels.remove(kgeJob.getJobId());
                }
            }
        } catch (Exception e) {
            log.error("[crawlJob={}] Inline KGE training failed (factSheet={}): {}", crawlJobId, factSheetId, e.getMessage(), e);
            kgeJob.setStatus(JobStatus.FAILED);
            kgeJob.setErrorMessage(e.getMessage());
        } finally {
            kgeJob.setCompletedAt(Instant.now());
            jobRepository.save(kgeJob);
            sendCompletionUpdate(kgeJob.getJobId(), kgeJob);
        }
        return kgeJob;
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

            // ── out-of-process path ────────────────────────────────────────────────
            // When the out-of-process executor is wired (kompile.learning.subprocess.enabled=true)
            // delegate training to a separate JVM so that an OOM there cannot kill the main app.
            if (kgeTrainingExecutor != null) {
                log.info("Delegating KGE training for job {} to out-of-process executor", jobId);
                // Warm-start: load prior embeddings and pass them to the executor so the subprocess
                // seeds from them instead of cold-starting every time. Uses the shared prepareModel()
                // seam so the OOP async path mirrors the warm-start behaviour of trainSynchronously.
                WarmStartContext warmStart = prepareModel(adapter, factSheetId, algorithm, config);
                if (warmStart.isWarmStart()) {
                    log.info("Warm-starting KGE async (OOP): job={} factSheet={} priorEntityCount={} "
                            + "priorRelCount={} epochs={}",
                            jobId, factSheetId, warmStart.priorEmbeddings().size(),
                            warmStart.priorRelationEmbeddings().size(), warmStart.effectiveConfig().epochs());
                } else {
                    log.info("Cold-starting KGE async (OOP): job={} factSheet={} epochs={}",
                            jobId, factSheetId, warmStart.effectiveConfig().epochs());
                }
                KgeTrainingResult oopResult = kgeTrainingExecutor.trainOutOfProcess(
                        jobId, factSheetId, algorithm, warmStart.effectiveConfig(), triples,
                        warmStart.priorEmbeddings(), warmStart.priorRelationEmbeddings(), null);

                Long version = System.currentTimeMillis();
                if (oopResult.success()) {
                    if (adapter != null) {
                        adapter.storeEmbeddings(oopResult.model(), factSheetId, version);
                    } else {
                        storageService.storeEmbeddings(oopResult.model(), factSheetId, version);
                    }
                    job.setStatus(JobStatus.COMPLETED);
                    job.setEmbeddingVersion(version);
                    job.setEntitiesEmbedded(oopResult.model().getEntityCount());
                    job.setRelationsEmbedded(oopResult.model().getRelationCount());
                    job.setCurrentLoss(oopResult.finalLoss());

                    if (eventPublisher != null) {
                        try {
                            Path kgeArtifact = writeKgeCheckpointStub(factSheetId, version,
                                    oopResult.model().getEntityCount(),
                                    oopResult.model().getRelationCount(),
                                    oopResult.finalLoss());
                            eventPublisher.publishEvent(
                                    new ModelTrainedEvent(this, "kge", factSheetId, kgeArtifact, "kge-embedding"));
                        } catch (Exception e) {
                            log.warn("KGE OOP training factSheet={}: could not publish ModelTrainedEvent — {}",
                                    factSheetId, e.getMessage());
                        }
                    }
                } else {
                    job.setStatus(JobStatus.FAILED);
                    job.setErrorMessage(oopResult.errorMessage());
                }
                // Skip the in-JVM path entirely
                return;
            }

            // ── in-JVM path (default when executor is absent) ──────────────────────
            // Warm-start: load prior embeddings, seed the model, and reduce epoch count.
            // Uses the shared prepareModel() seam so the async path is no longer a cold-start
            // every time — it now mirrors the warm-start behaviour of trainSynchronously.
            WarmStartContext warmStart = prepareModel(adapter, factSheetId, algorithm, config);
            KGEmbeddingModel model = warmStart.model();
            KGEmbeddingConfig effectiveConfig = warmStart.effectiveConfig();
            if (warmStart.isWarmStart()) {
                log.info("Warm-starting KGE async (in-JVM): job={} factSheet={} priorEntityCount={} epochs={}",
                        jobId, factSheetId, warmStart.priorEmbeddings().size(), effectiveConfig.epochs());
            } else {
                log.info("Cold-starting KGE async (in-JVM): job={} factSheet={} epochs={}",
                        jobId, factSheetId, effectiveConfig.epochs());
            }
            runningModels.put(jobId, model);

            // Configure with progress callback
            KGEmbeddingConfig configWithCallback = effectiveConfig.withProgressCallback(progress -> {
                // Update job progress
                job.setCurrentEpoch(progress.epoch());
                job.setCurrentLoss(progress.loss());
                jobRepository.save(job);

                // Send WebSocket update
                sendProgressUpdate(jobId, progress);
            });

            // Acquire the heavy-memory serialization gate before training so KGE never
            // overlaps a concurrent embedding or indexing step on the same host.
            // The gate is a Semaphore(1) in HeavyMemoryCoordinatorImpl (app-main); absent in
            // unit tests (no-op). try-with-resources guarantees release even on exception.
            TrainingResult result;
            if (heavyMemoryCoordinator != null) {
                try (AutoCloseable token = heavyMemoryCoordinator.acquire("kge-training", jobId)) {
                    result = model.train(triples, configWithCallback);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw new RuntimeException("KGE training interrupted while waiting for heavy-memory gate", ie);
                } catch (Exception e) {
                    if (e instanceof RuntimeException) throw (RuntimeException) e;
                    throw new RuntimeException(e);
                }
            } else {
                result = model.train(triples, configWithCallback);
            }

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

    /**
     * Creates the appropriate KGE model for {@code algorithm}.
     *
     * <p>When the managed-config flag {@code useSameDiffKge} is {@code true}
     * (set via {@link KGEmbeddingConfigService#updateUseSameDiffKge(boolean)}),
     * returns a {@link SameDiffKgeModel} backed by DL4J's {@code sd.graph().rotatE()} /
     * {@code sd.graph().transE()} scorers.  Otherwise falls through to the hand-rolled
     * {@link TransEModel} / {@link RotatEModel} (default, backward-compatible path).</p>
     *
     * <p><b>Parity gate:</b> the flag defaults to {@code false}.  Only flip it after
     * {@code SameDiffKgeParityTest} passes (Hits@K/MRR parity contract).</p>
     */
    private KGEmbeddingModel createModel(KGEmbeddingAlgorithm algorithm) {
        boolean useSameDiff = kgeConfigService != null
                && kgeConfigService.getConfig().useSameDiffKge();
        if (useSameDiff) {
            log.info("createModel: useSameDiffKge=true → SameDiffKgeModel({})", algorithm.name());
            return new SameDiffKgeModel(algorithm);
        }
        return switch (algorithm) {
            case TRANSE -> new TransEModel();
            case ROTATE -> new RotatEModel();
        };
    }

    /**
     * Holds the result of {@link #prepareModel}: a freshly created (and optionally seeded)
     * model plus the effective config with warm-start epoch reduction applied.
     */
    private record WarmStartContext(
            KGEmbeddingModel model,
            KGEmbeddingConfig effectiveConfig,
            boolean isWarmStart,
            Map<String, INDArray> priorEmbeddings,
            Map<String, INDArray> priorRelationEmbeddings) {}

    /**
     * Shared helper used by both {@link #trainSynchronously} and {@link #executeTrainingAsync}
     * to load prior embeddings, create a model, seed it for a warm start, and adjust the
     * epoch count. Extracted to a single place so neither caller can diverge.
     *
     * <p>When the adapter returns non-empty prior entity embeddings the method:
     * <ol>
     *   <li>Reduces epochs to {@code config.effectiveWarmStartEpochs()} for an incremental update.</li>
     *   <li>Calls {@code model.importEntityEmbeddings()} so training seeds from prior vectors,
     *       not random init — this is the resumability contract for KGE.</li>
     *   <li>Calls {@code model.importRelationEmbeddings()} when relation priors exist.</li>
     * </ol>
     * When the adapter is absent or returns empty embeddings the method falls back to a full
     * cold-start (random init, full epoch count).</p>
     *
     * @param adapter     graph adapter to load prior embeddings from; {@code null} = cold start
     * @param factSheetId fact sheet identifier
     * @param algorithm   KGE algorithm to create the model for
     * @param config      hyper-parameters from the caller
     * @return a {@link WarmStartContext} with the seeded model, effective config, and the raw
     *         prior-embedding maps (needed by the OOP executor path to pass to the subprocess)
     */
    private WarmStartContext prepareModel(KgEmbeddingGraphAdapter adapter,
                                         Long factSheetId,
                                         KGEmbeddingAlgorithm algorithm,
                                         KGEmbeddingConfig config) {
        Map<String, INDArray> priorEmbeddings = adapter != null
                ? adapter.loadEmbeddings(factSheetId)
                : Collections.emptyMap();
        Map<String, INDArray> priorRelEmbeddings = adapter != null
                ? adapter.loadRelationEmbeddings(factSheetId)
                : Collections.emptyMap();
        boolean isWarmStart = !priorEmbeddings.isEmpty();
        KGEmbeddingConfig effectiveConfig = isWarmStart
                ? config.toBuilder().epochs(config.effectiveWarmStartEpochs()).build()
                : config;
        KGEmbeddingModel model = createModel(algorithm);
        if (isWarmStart) {
            model.importEntityEmbeddings(priorEmbeddings);
        }
        if (!priorRelEmbeddings.isEmpty()) {
            model.importRelationEmbeddings(priorRelEmbeddings);
        }
        return new WarmStartContext(model, effectiveConfig, isWarmStart, priorEmbeddings, priorRelEmbeddings);
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
                .sorted(Comparator.comparingInt(KgEmbeddingGraphAdapter::priority).reversed())
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
                + ",\"finalLoss\":" + String.format(Locale.ROOT, "%.17g", finalLoss)
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
