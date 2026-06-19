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
package ai.kompile.app.ontology;

import ai.kompile.app.ingest.domain.JobLogEntry;
import ai.kompile.app.ingest.service.JobLogService;
import ai.kompile.app.services.IngestProgressTracker;
import ai.kompile.app.web.dto.ontology.DeriveOntologyRequest;
import ai.kompile.process.ontology.OntologySchema;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Runs {@link OntologyDerivationService#derive} as a background job so the UI can watch the agent
 * generate in real time. Each job streams progress + the full prompt/response transcript through the
 * existing job-log pipeline:
 * <ul>
 *   <li>{@link IngestProgressTracker#sendLog} broadcasts each line live to
 *       {@code /topic/ingest/{taskId}/logs} (consumed by {@code <app-job-log-viewer>}).</li>
 *   <li>{@link JobLogService#logEntry} persists each line (and the transcript, tagged
 *       {@code LLM_TRANSCRIPT}) so it survives reloads and is fetchable via the job-log REST API.</li>
 * </ul>
 * The job's {@code taskId} is {@code ontology-derive-{jobId}}.
 */
@Service
public class OntologyDerivationJobService {

    private static final Logger log = LoggerFactory.getLogger(OntologyDerivationJobService.class);
    private static final String LOGGER_NAME = "ontology-derivation";

    public static final String STATUS_RUNNING = "RUNNING";
    public static final String STATUS_COMPLETED = "COMPLETED";
    public static final String STATUS_FAILED = "FAILED";

    private final OntologyDerivationService derivationService;
    private final IngestProgressTracker progressTracker;
    private final JobLogService jobLogService;

    private final ExecutorService executor = Executors.newFixedThreadPool(3, r -> {
        Thread t = new Thread(r, LOGGER_NAME);
        t.setDaemon(true);
        return t;
    });
    private final ConcurrentHashMap<String, Job> jobs = new ConcurrentHashMap<>();
    private final AtomicLong sequence = new AtomicLong();

    public OntologyDerivationJobService(OntologyDerivationService derivationService,
                                        IngestProgressTracker progressTracker,
                                        JobLogService jobLogService) {
        this.derivationService = derivationService;
        this.progressTracker = progressTracker;
        this.jobLogService = jobLogService;
    }

    /** Submit a derivation to run in the background; returns immediately with a RUNNING job. */
    public Job start(DeriveOntologyRequest request) {
        String jobId = "od-" + sequence.incrementAndGet();
        String taskId = "ontology-derive-" + jobId;
        Job job = new Job(jobId, taskId);
        jobs.put(jobId, job);
        DerivationProgress progress = sinkFor(taskId);
        executor.submit(() -> run(job, request, progress));
        return job;
    }

    /** Look up a job by id (null if unknown / evicted). */
    public Job get(String jobId) {
        return jobs.get(jobId);
    }

    private void run(Job job, DeriveOntologyRequest request, DerivationProgress progress) {
        try {
            progress.log("Starting ontology derivation…");
            OntologySchema draft = derivationService.derive(request, progress);
            job.draft = draft;
            job.status = STATUS_COMPLETED;
            progress.log("Derivation completed — review the draft before saving.");
        } catch (Exception e) {
            job.error = e.getMessage() != null ? e.getMessage() : e.toString();
            job.status = STATUS_FAILED;
            progress.log("ERROR: " + job.error);
            log.warn("Ontology derivation job {} failed", job.jobId, e);
        }
    }

    private DerivationProgress sinkFor(String taskId) {
        return new DerivationProgress() {
            @Override
            public void log(String message) {
                broadcast(taskId, "SYSTEM", "INFO", message);
                persist(taskId, JobLogEntry.LogLevel.INFO, JobLogEntry.LogSource.SYSTEM, message);
            }

            @Override
            public void transcript(String provider, String model, String prompt, String response) {
                String block = "Provider: " + provider + " | Model: " + model
                        + "\n--- PROMPT ---\n" + prompt + "\n--- RESPONSE ---\n" + response;
                persist(taskId, JobLogEntry.LogLevel.INFO, JobLogEntry.LogSource.LLM_TRANSCRIPT, block);
                broadcast(taskId, "STDOUT", "INFO",
                        "Transcript [" + provider + "/" + model + "]:\n" + response);
            }
        };
    }

    private void broadcast(String taskId, String source, String level, String message) {
        try {
            progressTracker.sendLog(taskId, source, level, message);
        } catch (Exception e) {
            log.debug("Live log broadcast failed for {}: {}", taskId, e.toString());
        }
    }

    private void persist(String taskId, JobLogEntry.LogLevel level, JobLogEntry.LogSource source, String message) {
        try {
            jobLogService.logEntry(taskId, level, source, message, LOGGER_NAME, Thread.currentThread().getName());
        } catch (Exception e) {
            log.debug("Log persistence failed for {}: {}", taskId, e.toString());
        }
    }

    /** In-memory job handle. Status transitions RUNNING → COMPLETED | FAILED. */
    public static final class Job {
        private final String jobId;
        private final String taskId;
        private volatile String status = STATUS_RUNNING;
        private volatile OntologySchema draft;
        private volatile String error;

        Job(String jobId, String taskId) {
            this.jobId = jobId;
            this.taskId = taskId;
        }

        public String getJobId() {
            return jobId;
        }

        public String getTaskId() {
            return taskId;
        }

        public String getStatus() {
            return status;
        }

        public OntologySchema getDraft() {
            return draft;
        }

        public String getError() {
            return error;
        }
    }
}
