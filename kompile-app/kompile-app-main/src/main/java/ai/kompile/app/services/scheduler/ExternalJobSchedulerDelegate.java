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

package ai.kompile.app.services.scheduler;

import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * SPI for external job scheduler integration (Kubernetes, webhook, etc.).
 *
 * <p>When configured, the {@link ResourceAwareJobScheduler} delegates job execution
 * to an external system instead of running subprocesses locally. The local scheduler
 * still manages the queue, priority ordering, and GPU resource tracking — only the
 * actual execution is delegated.</p>
 *
 * <h3>Lifecycle</h3>
 * <ol>
 *   <li>Local scheduler accepts job submission, applies queue/priority logic</li>
 *   <li>When a job is dispatched, calls {@link #submitJob} instead of local execution</li>
 *   <li>External system runs the job (e.g., as a Kubernetes Job/Pod)</li>
 *   <li>External system calls back via {@code POST /api/scheduler/callback} on completion</li>
 *   <li>Local scheduler updates job state, releases GPU reservation, dispatches next</li>
 * </ol>
 *
 * <h3>Implementations</h3>
 * <ul>
 *   <li>{@code KubernetesJobSchedulerDelegate} — creates Kubernetes Jobs via kubectl/API</li>
 *   <li>{@code WebhookJobSchedulerDelegate} — POSTs to a webhook URL</li>
 * </ul>
 */
public interface ExternalJobSchedulerDelegate {

    /**
     * The external scheduler mode this delegate handles (e.g., "kubernetes", "webhook").
     */
    String getMode();

    /**
     * Submit a job to the external scheduler.
     *
     * @param jobId unique job identifier
     * @param jobType the service type (e.g., "ingest", "vectorPopulation")
     * @param description human-readable description
     * @param resourceProfile the job's resource requirements
     * @param metadata caller-supplied metadata (filePath, etc.)
     * @return a future that resolves when the external system acknowledges the submission
     */
    CompletableFuture<ExternalJobRef> submitJob(
            String jobId, String jobType, String description,
            JobResourceProfile resourceProfile,
            Map<String, Object> metadata);

    /**
     * Cancel a job in the external scheduler.
     */
    CompletableFuture<Boolean> cancelJob(String jobId, String externalRef);

    /**
     * Check the status of a job in the external scheduler.
     */
    CompletableFuture<ExternalJobStatus> getJobStatus(String jobId, String externalRef);

    /**
     * Check if this delegate is properly configured and reachable.
     */
    boolean isAvailable();

    /**
     * Reference returned by the external system after job submission.
     */
    record ExternalJobRef(
            String externalId,
            String status,
            String message
    ) {}

    /**
     * Status of a job in the external system.
     */
    record ExternalJobStatus(
            String externalId,
            String status, // PENDING, RUNNING, COMPLETED, FAILED
            String message,
            Map<String, Object> details
    ) {}
}
