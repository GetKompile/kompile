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
