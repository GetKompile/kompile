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

import ai.kompile.knowledgegraph.service.ConceptExtractor.ExtractionConfig;
import ai.kompile.knowledgegraph.service.SourceLinkingService.LinkingConfig;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * Service for building and managing knowledge graphs scoped to fact sheets.
 * Integrates with indexed documents to extract concepts and create graph structures.
 */
public interface FactSheetGraphService {

    /**
     * Configuration for graph building from indices.
     */
    record GraphBuildConfig(
        ExtractionConfig extractionConfig,  // Concept extraction settings
        LinkingConfig linkingConfig,        // Source linking settings
        int minConceptConfidence,           // Minimum concept confidence to include
        int minSharedConceptsForEdge,       // Minimum shared concepts to create edge
        boolean includeHierarchicalEdges,   // Create parent-child edges
        boolean computeConceptEdges,        // Create edges based on shared concepts
        boolean computeSourceLinks,         // Link sources together
        boolean asyncProcessing,            // Run asynchronously
        int maxDocumentsToProcess           // Limit documents per source (0 = unlimited)
    ) {
        public static GraphBuildConfig defaults() {
            return new GraphBuildConfig(
                ExtractionConfig.defaults(),
                LinkingConfig.defaults(),
                30,     // minConceptConfidence (as percentage)
                2,      // minSharedConceptsForEdge
                true,   // includeHierarchicalEdges
                true,   // computeConceptEdges
                true,   // computeSourceLinks
                true,   // asyncProcessing
                0       // maxDocumentsToProcess (unlimited)
            );
        }
    }

    /**
     * Status of a graph building job.
     */
    record GraphBuildStatus(
        String jobId,
        String factSheetName,
        Long factSheetId,
        String status,           // PENDING, RUNNING, COMPLETED, FAILED, CANCELLED
        int totalDocuments,
        int processedDocuments,
        int nodesCreated,
        int edgesCreated,
        int conceptsExtracted,
        String errorMessage,
        long startTime,
        Long endTime,
        Map<String, Object> statistics
    ) {}

    /**
     * Result of graph visualization request.
     */
    record GraphVisualizationData(
        List<Map<String, Object>> nodes,
        List<Map<String, Object>> edges,
        Map<String, Object> metadata
    ) {}

    /**
     * Build knowledge graph for a fact sheet from its indexed documents.
     *
     * @param factSheetId The fact sheet to build graph for
     * @param config      Build configuration
     * @return Build status with job ID
     */
    GraphBuildStatus buildGraphFromIndex(Long factSheetId, GraphBuildConfig config);

    /**
     * Build knowledge graph asynchronously.
     *
     * @param factSheetId The fact sheet to build graph for
     * @param config      Build configuration
     * @return Future that completes when build is done
     */
    CompletableFuture<GraphBuildStatus> buildGraphFromIndexAsync(Long factSheetId, GraphBuildConfig config);

    /**
     * Get the status of a graph building job.
     *
     * @param jobId The job ID
     * @return Current build status
     */
    GraphBuildStatus getBuildStatus(String jobId);

    /**
     * Cancel a running graph building job.
     *
     * @param jobId The job ID to cancel
     * @return True if cancelled
     */
    boolean cancelBuild(String jobId);

    /**
     * Get D3-compatible visualization data for a fact sheet's graph.
     *
     * @param factSheetId The fact sheet
     * @param maxNodes    Maximum nodes to return (0 = unlimited)
     * @param maxEdges    Maximum edges to return (0 = unlimited)
     * @return Visualization data
     */
    GraphVisualizationData getVisualizationData(Long factSheetId, int maxNodes, int maxEdges);

    /**
     * Get graph statistics for a fact sheet.
     *
     * @param factSheetId The fact sheet
     * @return Map of statistics
     */
    Map<String, Object> getGraphStatistics(Long factSheetId);

    /**
     * Clear the graph for a fact sheet (delete all nodes, edges, and mentions).
     *
     * @param factSheetId The fact sheet
     * @return Number of entities deleted
     */
    int clearGraph(Long factSheetId);

    /**
     * Get all running build jobs.
     *
     * @return List of running job statuses
     */
    List<GraphBuildStatus> getRunningJobs();

    /**
     * Process a single document from the index and add to the graph.
     *
     * @param factSheetId The fact sheet
     * @param documentId  Document ID in the index
     * @param content     Document content
     * @param metadata    Document metadata
     * @param sourceId    Source ID this document belongs to
     * @param config      Build configuration
     * @return Number of concepts extracted
     */
    int processIndexedDocument(Long factSheetId, String documentId, String content,
                               Map<String, Object> metadata, String sourceId, GraphBuildConfig config);

    /**
     * Rebuild edges based on shared concepts within a fact sheet.
     *
     * @param factSheetId The fact sheet
     * @param minSharedConcepts Minimum shared concepts to create edge
     * @return Number of edges created
     */
    int rebuildConceptEdges(Long factSheetId, int minSharedConcepts);

    /**
     * Get top concepts in a fact sheet's graph.
     *
     * @param factSheetId The fact sheet
     * @param limit       Maximum number to return
     * @return List of concept info maps
     */
    List<Map<String, Object>> getTopConcepts(Long factSheetId, int limit);

    /**
     * Search for nodes in a fact sheet's graph.
     *
     * @param factSheetId The fact sheet
     * @param query       Search query
     * @param limit       Maximum results
     * @return List of matching nodes
     */
    List<Map<String, Object>> searchNodes(Long factSheetId, String query, int limit);

    /**
     * Get document nodes that share concepts with a given document.
     *
     * @param factSheetId The fact sheet
     * @param documentNodeId The document node ID
     * @param minSharedConcepts Minimum shared concepts
     * @param limit Maximum results
     * @return List of related document info
     */
    List<Map<String, Object>> getRelatedDocuments(Long factSheetId, String documentNodeId,
                                                   int minSharedConcepts, int limit);
}
