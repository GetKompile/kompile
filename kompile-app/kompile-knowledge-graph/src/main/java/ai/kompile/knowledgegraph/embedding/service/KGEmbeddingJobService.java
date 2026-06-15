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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
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

            // Extract triples
            List<Triple> triples = storageService.extractTriples(factSheetId);
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
                // Store embeddings
                Long version = System.currentTimeMillis();
                storageService.storeEmbeddings(model, factSheetId, version);

                job.setStatus(JobStatus.COMPLETED);
                job.setEmbeddingVersion(version);
                job.setEntitiesEmbedded(result.entitiesCount());
                job.setRelationsEmbedded(result.relationsCount());
                job.setCurrentLoss(result.finalLoss());
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
