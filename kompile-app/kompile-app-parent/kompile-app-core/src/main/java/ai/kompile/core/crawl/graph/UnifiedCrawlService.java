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

package ai.kompile.core.crawl.graph;

import ai.kompile.core.crawl.graph.archive.CrawlStepArchiveService;

import java.util.List;
import java.util.Optional;

/**
 * Service interface for the unified crawl-to-graph pipeline.
 * Orchestrates multi-source crawling with automatic graph extraction
 * and vector indexing.
 */
public interface UnifiedCrawlService {

    /**
     * Start a unified crawl-to-graph job.
     * Crawls all specified sources, extracts a knowledge graph via LLM,
     * and indexes documents to the vector store.
     *
     * @param request the crawl request with sources and extraction config
     * @return the created job with its ID for tracking
     */
    UnifiedCrawlJob startJob(UnifiedCrawlRequest request);

    /**
     * Get a job by ID.
     */
    Optional<UnifiedCrawlJob> getJob(String jobId);

    /**
     * Get all jobs.
     */
    List<UnifiedCrawlJob> getAllJobs();

    /**
     * Get all active (PENDING or RUNNING) jobs.
     */
    List<UnifiedCrawlJob> getActiveJobs();

    /**
     * Cancel a running or paused job.
     *
     * @return true if the job was cancelled, false if not found or already finished
     */
    boolean cancelJob(String jobId);

    /**
     * Remove completed/failed/cancelled jobs from memory.
     *
     * @return number of jobs removed
     */
    int cleanupJobs();

    /**
     * Resume embedding for a job left in {@code COMPLETED_PENDING_EMBEDDING}.
     *
     * <p>Drains the job's deferred chunks through the embedding/vector-index path and,
     * on success, transitions the job to {@code COMPLETED}. Invoked by the
     * {@code DeferredEmbeddingResumer} once GPU capacity and the embedding model are available.
     * This is a bridge so callers outside the crawl-graph module (e.g. app-main) can trigger
     * the package-private indexing logic without depending on its internals.</p>
     *
     * @param jobId the job to resume
     * @return number of chunks embedded (0 if the job is not eligible or has no deferred chunks)
     */
    int resumeDeferredEmbedding(String jobId);

    /**
     * Re-run graph extraction for a job's re-accumulated, previously-failed chunks (those that
     * survived all in-phase retries). Invoked by the deferred-graph resumer once the failing
     * condition has cleared. Chunks that succeed are removed from the deferred pool; persistent
     * failures stay pooled (never dropped). When the pool empties, the job advances past
     * {@code COMPLETED_PENDING_GRAPH}.
     *
     * @param jobId the job to resume
     * @return number of chunks re-extracted successfully this pass
     */
    int resumeDeferredGraph(String jobId);

    /**
     * Resume a previously archived pipeline step for a job.
     *
     * <p>Loads the step's archived chunks + config from disk and runs just that step. Works whether the
     * job is still in memory or must be rehydrated from the persisted snapshot after a restart. This
     * generalizes {@link #resumeDeferredEmbedding(String)} / {@link #resumeDeferredGraph(String)} to any
     * step the user chose to archive (run later) rather than defer for resource reasons.</p>
     *
     * @param jobId  the job whose step should be run
     * @param stepId the canonical step ID to resume (e.g. {@code "VECTOR_INDEXING"})
     * @return number of items processed, or a negative value if the step/archive was not found
     */
    int resumeArchivedStep(String jobId, String stepId);

    /**
     * Archive a step on demand for an in-memory job, persisting its pending inputs to disk so it can be
     * run later. Primarily converts a job's deferred embedding chunks into a durable archive.
     *
     * @return the archive directory path, or {@code null} if the step had no archivable inputs
     */
    String archiveStep(String jobId, String stepId);

    /**
     * Register a job reconstructed from a persisted snapshot back into the in-memory job map, so it is
     * findable by {@link #getJob(String)} while its archived steps are resumed.
     */
    void registerRehydratedJob(UnifiedCrawlJob job);

    /** List crawl jobs that have archived steps on disk and can be resumed (incl. after a restart). */
    List<CrawlStepArchiveService.ResumableCrawlJob> listResumableCrawlJobs();

    /**
     * Retry failed documents from a previous job.
     * Creates a new job that re-processes only documents that failed in the original.
     *
     * @param originalJobId the ID of the completed/failed job to retry from
     * @param retryPhase    optional phase filter (e.g. "GRAPH_EXTRACTION"); null = retry all failed
     * @param documentKeys  optional explicit document keys to retry; null/empty = all failed
     * @return the new retry job, or empty if original job not found or has no failed documents
     */
    Optional<UnifiedCrawlJob> retryJob(String originalJobId, String retryPhase, List<String> documentKeys);

    /**
     * List the available source types that can be crawled.
     */
    List<AvailableSourceType> getAvailableSourceTypes();

    /**
     * Describes an available source type with its configuration options.
     */
    record AvailableSourceType(
            String type,
            String displayName,
            String description,
            boolean available,
            List<String> requiredProperties,
            List<String> optionalProperties
    ) {}
}
