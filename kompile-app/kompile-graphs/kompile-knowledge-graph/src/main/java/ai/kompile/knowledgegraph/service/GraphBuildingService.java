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
package ai.kompile.knowledgegraph.service;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * Service for automatically building knowledge graphs from document sources.
 * Processes documents to extract entities and create relationships.
 */
public interface GraphBuildingService {

    /**
     * Status of a graph building job.
     */
    record BuildStatus(
        String jobId,
        String status,  // PENDING, RUNNING, COMPLETED, FAILED
        int totalSources,
        int processedSources,
        int totalDocuments,
        int processedDocuments,
        int entitiesExtracted,
        int edgesCreated,
        String errorMessage,
        long startTime,
        Long endTime
    ) {}

    /**
     * Configuration for graph building.
     */
    record BuildConfig(
        double minEntityConfidence,
        int minSharedEntitiesForEdge,
        boolean includeHierarchicalEdges,
        boolean computeSimilarityEdges,
        double similarityThreshold,
        List<String> entityTypesToExtract,
        int maxEntitiesPerDocument,
        boolean asyncProcessing
    ) {
        public static BuildConfig defaults() {
            return new BuildConfig(
                0.6,
                1,
                true,
                true,
                0.7,
                List.of("PERSON", "ORGANIZATION", "LOCATION", "TECHNICAL_TERM", "CONCEPT"),
                100,
                true
            );
        }
    }

    /**
     * Build graph from all indexed sources.
     *
     * @param config Build configuration
     * @return Build status with job ID
     */
    BuildStatus buildGraphFromAllSources(BuildConfig config);

    /**
     * Build graph from specific sources.
     *
     * @param sourceIds List of source IDs to process
     * @param config Build configuration
     * @return Build status with job ID
     */
    BuildStatus buildGraphFromSources(List<String> sourceIds, BuildConfig config);

    /**
     * Build graph asynchronously from all sources.
     *
     * @param config Build configuration
     * @return Future that completes when build is done
     */
    CompletableFuture<BuildStatus> buildGraphFromAllSourcesAsync(BuildConfig config);

    /**
     * Get the status of a graph building job.
     *
     * @param jobId The job ID
     * @return Current build status
     */
    BuildStatus getBuildStatus(String jobId);

    /**
     * Cancel a running graph building job.
     *
     * @param jobId The job ID to cancel
     * @return True if cancelled, false if not running
     */
    boolean cancelBuild(String jobId);

    /**
     * Process a single document and add to graph.
     *
     * @param documentId Document ID in the index
     * @param content Document content
     * @param metadata Document metadata
     * @param sourceId Source ID this document belongs to
     * @param config Build configuration
     * @return Number of entities extracted
     */
    int processDocument(String documentId, String content, Map<String, Object> metadata,
                        String sourceId, BuildConfig config);

    /**
     * Find and create edges between documents that share entities.
     *
     * @param minSharedEntities Minimum number of shared entities to create an edge
     * @return Number of edges created
     */
    int createSharedEntityEdges(int minSharedEntities);

    /**
     * Get all running build jobs.
     *
     * @return List of running job statuses
     */
    List<BuildStatus> getRunningJobs();

    /**
     * Clear all graph data and start fresh.
     * Use with caution!
     */
    void clearGraph();

    /**
     * Get statistics about the graph building process.
     *
     * @return Map of statistics
     */
    Map<String, Object> getBuildStatistics();
}
