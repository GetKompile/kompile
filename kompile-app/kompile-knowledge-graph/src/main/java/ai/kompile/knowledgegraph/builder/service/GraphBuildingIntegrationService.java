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
package ai.kompile.knowledgegraph.builder.service;

import ai.kompile.core.graphbuilder.*;
import ai.kompile.core.retrievers.RetrievedDoc;
import ai.kompile.knowledgegraph.builder.domain.ExtractionJob;
import ai.kompile.knowledgegraph.builder.domain.ExtractionJob.JobStatus;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * Service for integrating knowledge graph building with the document indexing pipeline.
 *
 * <p>This service provides:
 * <ul>
 *   <li>Hooks for triggering graph building after document indexing</li>
 *   <li>Async job execution with progress reporting</li>
 *   <li>Integration with fact sheet configuration</li>
 * </ul>
 *
 * <p>Usage from pipeline:
 * <pre>
 * // After chunks are indexed, trigger graph building if enabled
 * if (factSheet.getEnableGraphBuilding()) {
 *     graphBuildingIntegrationService.triggerGraphBuildingAsync(factSheetId, chunks);
 * }
 * </pre>
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class GraphBuildingIntegrationService {

    private final GraphBuilderRegistry builderRegistry;
    private final ExtractionJobService jobService;
    private final ObjectMapper objectMapper;

    // Track running jobs for cancellation
    private final ConcurrentHashMap<String, Boolean> cancelledJobs = new ConcurrentHashMap<>();

    /**
     * Trigger graph building asynchronously for a fact sheet's indexed chunks.
     *
     * @param factSheetId the fact sheet ID
     * @param chunks the chunks to process
     * @param builderType the builder type (e.g., "llm-builder")
     * @param config optional builder configuration
     * @param progressCallback optional callback for progress updates
     * @return CompletableFuture with the job ID
     */
    @Async
    public CompletableFuture<String> triggerGraphBuildingAsync(
            Long factSheetId,
            List<RetrievedDoc> chunks,
            String builderType,
            BuilderConfig config,
            Consumer<BuildProgress> progressCallback) {

        // Create job
        ExtractionJob job = jobService.createJob(factSheetId, builderType, config);
        String jobId = job.getJobId();

        log.info("Starting graph building job {} for fact sheet {} with {} chunks",
                jobId, factSheetId, chunks.size());

        // Get builder
        Optional<KnowledgeGraphBuilder> builderOpt = builderRegistry.getBuilderByTypeString(builderType);
        if (builderOpt.isEmpty()) {
            log.error("Builder not found: {}", builderType);
            jobService.failJob(jobId, "Builder not found: " + builderType);
            return CompletableFuture.completedFuture(jobId);
        }

        KnowledgeGraphBuilder builder = builderOpt.get();

        // Configure builder
        if (config != null) {
            builder.configure(config);
        }

        // Run extraction
        try {
            runExtraction(job, builder, chunks, progressCallback);
        } catch (Exception e) {
            log.error("Graph building job {} failed", jobId, e);
            jobService.failJob(jobId, e.getMessage());
        }

        return CompletableFuture.completedFuture(jobId);
    }

    /**
     * Run extraction with progress tracking.
     */
    private void runExtraction(
            ExtractionJob job,
            KnowledgeGraphBuilder builder,
            List<RetrievedDoc> chunks,
            Consumer<BuildProgress> progressCallback) {

        String jobId = job.getJobId();

        // Create context
        GraphBuildContext context = new GraphBuildContext(
                jobId,
                job.getFactSheetId(),
                "jpa" // Default to JPA storage
        );

        // Start job
        jobService.startJob(jobId, chunks.size());

        // Combined progress handler
        Consumer<BuildProgress> combinedCallback = progress -> {
            // Update job status
            jobService.updateJobProgress(jobId, progress.processedChunks(), progress.proposalsCreated());

            // Check for cancellation
            if (cancelledJobs.getOrDefault(jobId, false)) {
                throw new RuntimeException("Job cancelled");
            }

            // Forward to external callback
            if (progressCallback != null) {
                progressCallback.accept(progress);
            }

            // Log progress
            if (progress.processedChunks() % 10 == 0) {
                log.debug("Job {}: {}/{} chunks, {} proposals",
                        jobId, progress.processedChunks(), progress.totalChunks(), progress.proposalsCreated());
            }
        };

        try {
            // Run extraction
            List<ProposedTriple> proposals = builder.buildFromChunks(chunks, context, combinedCallback);

            // Create proposal entities
            int created = jobService.createProposalsFromTriples(jobId, job.getFactSheetId(), proposals);

            // Complete job
            jobService.completeJob(jobId, created);

            log.info("Graph building job {} completed: {} proposals created", jobId, created);

        } catch (Exception e) {
            if (cancelledJobs.getOrDefault(jobId, false)) {
                log.info("Graph building job {} was cancelled", jobId);
            } else {
                throw e;
            }
        } finally {
            cancelledJobs.remove(jobId);
        }
    }

    /**
     * Request cancellation of a running job.
     */
    public void requestCancellation(String jobId) {
        cancelledJobs.put(jobId, true);
        log.info("Cancellation requested for job {}", jobId);
    }

    /**
     * Check if graph building should be triggered for a fact sheet.
     *
     * @param enableGraphBuilding whether graph building is enabled
     * @param graphBuilderType the configured builder type
     * @return true if graph building should be triggered
     */
    public boolean shouldTriggerGraphBuilding(Boolean enableGraphBuilding, String graphBuilderType) {
        if (enableGraphBuilding == null || !enableGraphBuilding) {
            return false;
        }
        if (graphBuilderType == null || graphBuilderType.isEmpty()) {
            return false;
        }
        return builderRegistry.getBuilderByTypeString(graphBuilderType).isPresent();
    }

    /**
     * Get the configured builder for a fact sheet.
     */
    public Optional<KnowledgeGraphBuilder> getBuilderForFactSheet(
            String graphBuilderType,
            String graphBuilderConfigJson) {

        Optional<KnowledgeGraphBuilder> builderOpt = builderRegistry.getBuilderByTypeString(graphBuilderType);
        if (builderOpt.isEmpty()) {
            return Optional.empty();
        }

        KnowledgeGraphBuilder builder = builderOpt.get();

        // Apply config if provided
        if (graphBuilderConfigJson != null && !graphBuilderConfigJson.isEmpty()) {
            try {
                BuilderConfig config = objectMapper.readValue(graphBuilderConfigJson, BuilderConfig.class);
                builder.configure(config);
            } catch (Exception e) {
                log.warn("Failed to parse graph builder config: {}", e.getMessage());
            }
        }

        return Optional.of(builder);
    }

    /**
     * Trigger graph building for a fact sheet using its configured settings.
     *
     * @param factSheetId the fact sheet ID
     * @param chunks the chunks to process
     * @param graphBuilderType the configured builder type
     * @param graphBuilderConfigJson the serialized builder configuration
     * @param progressCallback optional callback for progress updates
     * @return CompletableFuture with the job ID, or empty if graph building is disabled/unavailable
     */
    public CompletableFuture<Optional<String>> triggerForFactSheet(
            Long factSheetId,
            List<RetrievedDoc> chunks,
            String graphBuilderType,
            String graphBuilderConfigJson,
            Consumer<BuildProgress> progressCallback) {

        if (!shouldTriggerGraphBuilding(true, graphBuilderType)) {
            return CompletableFuture.completedFuture(Optional.empty());
        }

        BuilderConfig config = null;
        if (graphBuilderConfigJson != null && !graphBuilderConfigJson.isEmpty()) {
            try {
                config = objectMapper.readValue(graphBuilderConfigJson, BuilderConfig.class);
            } catch (Exception e) {
                log.warn("Failed to parse graph builder config: {}", e.getMessage());
            }
        }

        return triggerGraphBuildingAsync(factSheetId, chunks, graphBuilderType, config, progressCallback)
                .thenApply(Optional::of);
    }
}
