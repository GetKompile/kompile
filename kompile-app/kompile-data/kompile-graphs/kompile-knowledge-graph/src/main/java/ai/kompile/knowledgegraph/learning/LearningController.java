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
package ai.kompile.knowledgegraph.learning;

import ai.kompile.core.kgembedding.KGEmbeddingAlgorithm;
import ai.kompile.core.kgembedding.KGEmbeddingConfig;
import ai.kompile.knowledgegraph.embedding.domain.KGEmbeddingJob;
import ai.kompile.knowledgegraph.embedding.service.KGEmbeddingJobService;
import ai.kompile.knowledgegraph.reasoning.IncrementalReasoningOrchestrator;
import ai.kompile.knowledgegraph.reasoning.MebnTheoryRegistrationService;
import ai.kompile.knowledgegraph.reasoning.RegroundResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.lang.Nullable;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * REST controller for on-demand KGE + PSL weight-learning runs.
 *
 * <p>Exposes {@code POST /api/knowledge-graph/learn} which triggers:
 * <ol>
 *   <li>Complete KGE embedding training via {@link KGEmbeddingJobService} using the selected
 *       algorithm's library defaults.</li>
 *   <li>Rebuild the registered reasoning graph so it contains the newly persisted vectors.</li>
 *   <li>If {@link IncrementalReasoningOrchestrator} is available, run a synchronous full-reground
 *       (hybrid consensus, PSL, and registered MEBN learning) for the same fact sheet.</li>
 * </ol>
 *
 * <p>Returns a {@link LearningJobResponse} JSON with the KGE job ID, PSL versions
 * written, and overall status.</p>
 */
@RestController
@CrossOrigin
@RequestMapping("/api/knowledge-graph")
public class LearningController {

    private static final Logger log = LoggerFactory.getLogger(LearningController.class);

    private final KGEmbeddingJobService kgeJobService;

    @Nullable
    private final IncrementalReasoningOrchestrator reasoningOrchestrator;

    @Nullable
    private final MebnTheoryRegistrationService mebnRegistrationService;

    @Autowired
    public LearningController(
            KGEmbeddingJobService kgeJobService,
            @Autowired(required = false) IncrementalReasoningOrchestrator reasoningOrchestrator,
            @Autowired(required = false) MebnTheoryRegistrationService mebnRegistrationService) {
        this.kgeJobService = kgeJobService;
        this.reasoningOrchestrator = reasoningOrchestrator;
        this.mebnRegistrationService = mebnRegistrationService;
    }

    /**
     * Triggers KGE training and (optionally) PSL weight-learning on-demand for a
     * given fact sheet.
     *
     * @param factSheetId the fact sheet to train on (required)
     * @param algorithm   embedding algorithm name, e.g. {@code TRANSE} or {@code ROTATE}
     *                    (case-insensitive; defaults to {@code TRANSE})
     * @return a {@link LearningJobResponse} describing what was started/completed
     */
    @PostMapping("/learn")
    public ResponseEntity<LearningJobResponse> triggerLearning(
            @RequestParam Long factSheetId,
            @RequestParam(required = false, defaultValue = "TRANSE") String algorithm) {

        log.info("On-demand learning requested: factSheetId={}, algorithm={}", factSheetId, algorithm);

        KGEmbeddingAlgorithm algo = KGEmbeddingAlgorithm.fromString(algorithm);

        KGEmbeddingConfig config = KGEmbeddingConfig.defaultsFor(algo);

        // Complete KGE first. Starting an async job and immediately re-grounding would train the
        // reasoning models against stale vectors, defeating semantic consensus in this full run.
        KGEmbeddingJob job;
        try {
            job = kgeJobService.trainSynchronously(
                    "api-learning-" + UUID.randomUUID(), factSheetId, algo, config, null);
            log.info("KGE training job finished: jobId={}, factSheetId={}, algorithm={}, status={}",
                    job.getJobId(), factSheetId, algo, job.getStatus());
        } catch (IllegalStateException e) {
            log.warn("KGE training already running for factSheetId={}: {}", factSheetId, e.getMessage());
            return ResponseEntity.status(409).body(new LearningJobResponse(
                    null, factSheetId, algo.name(), "ALREADY_RUNNING", 0, 0,
                    "A training job is already running for this fact sheet: " + e.getMessage()));
        }

        if (job.getStatus() != KGEmbeddingJob.JobStatus.COMPLETED) {
            log.warn("KGE training did not complete for factSheetId={}: status={}, error={}",
                    factSheetId, job.getStatus(), job.getErrorMessage());
            return ResponseEntity.ok(new LearningJobResponse(
                    job.getJobId(), factSheetId, algo.name(),
                    job.getStatus() != null ? job.getStatus().name() : "FAILED", 0, 0,
                    "KGE training did not complete; reasoning learning was skipped: "
                            + job.getErrorMessage()));
        }

        // Register after KGE so the ReasoningGraph carries the vectors from this training run.
        int mebnMFragsRegistered = 0;
        if (mebnRegistrationService != null) {
            try {
                mebnMFragsRegistered = mebnRegistrationService.registerMTheoryForFactSheet(factSheetId);
                log.info("MEBN theory registered for factSheetId={}: {} MFrag(s)", factSheetId, mebnMFragsRegistered);
            } catch (Exception e) {
                log.warn("MEBN theory registration failed for factSheetId={}: {}", factSheetId, e.getMessage());
            }
        }

        // Optionally run PSL weight-learning synchronously
        int pslVersionsWritten = 0;
        String pslRunId = null;
        if (reasoningOrchestrator != null) {
            try {
                log.info("Running PSL weight-learning (reground) for factSheetId={}", factSheetId);
                RegroundResult reground = reasoningOrchestrator.runFullReground(factSheetId);
                pslVersionsWritten = reground.versionsWritten();
                pslRunId = reground.runId();
                log.info("PSL weight-learning complete: factSheetId={}, versionsWritten={}, runId={}",
                        factSheetId, pslVersionsWritten, pslRunId);
            } catch (Exception e) {
                log.warn("PSL weight-learning failed for factSheetId={}: {}", factSheetId, e.getMessage(), e);
                // Non-fatal: KGE completed successfully, return partial success.
                return ResponseEntity.ok(new LearningJobResponse(
                        job.getJobId(), factSheetId, algo.name(),
                        job.getStatus() != null ? job.getStatus().name() : "STARTED",
                        0, mebnMFragsRegistered,
                        "KGE completed but PSL reground failed: " + e.getMessage()));
            }
        }

        String message = "KGE training complete"
                + (mebnMFragsRegistered > 0 ? "; MEBN: " + mebnMFragsRegistered + " MFrag(s) registered" : "")
                + (reasoningOrchestrator != null
                   ? "; PSL reground complete (versionsWritten=" + pslVersionsWritten
                     + (pslRunId != null ? ", runId=" + pslRunId : "") + ")"
                   : "; PSL reground skipped (no reasoningOrchestrator wired)");

        return ResponseEntity.ok(new LearningJobResponse(
                job.getJobId(),
                factSheetId,
                algo.name(),
                job.getStatus() != null ? job.getStatus().name() : "STARTED",
                pslVersionsWritten,
                mebnMFragsRegistered,
                message));
    }

    /**
     * Response payload for {@link #triggerLearning}.
     *
     * @param kgeJobId           the KGE training job ID (UUID string)
     * @param factSheetId        the fact sheet the job was started for
     * @param algorithm          the resolved algorithm name (e.g. {@code TRANSE})
     * @param status             KGE job status at response time (e.g. {@code PENDING}, {@code RUNNING})
     * @param pslVersionsWritten number of PSL weight versions persisted during this run (0 if skipped)
     * @param mebnMFragsRegistered number of MEBN MFrags registered (0 if skipped)
     * @param message            human-readable summary
     */
    public record LearningJobResponse(
            String kgeJobId,
            Long factSheetId,
            String algorithm,
            String status,
            int pslVersionsWritten,
            int mebnMFragsRegistered,
            String message) {}
}
