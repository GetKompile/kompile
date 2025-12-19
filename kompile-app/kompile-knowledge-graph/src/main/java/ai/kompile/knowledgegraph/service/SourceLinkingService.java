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

import ai.kompile.knowledgegraph.domain.EdgeType;

import java.util.List;
import java.util.Map;

/**
 * Service for creating and managing links between sources based on shared concepts,
 * entities, and embedding similarity.
 */
public interface SourceLinkingService {

    /**
     * Configuration for source linking.
     */
    record LinkingConfig(
        int minSharedConcepts,         // Minimum shared concepts to create link
        double minSimilarity,          // Minimum embedding similarity
        double minConceptOverlap,      // Minimum Jaccard overlap of concepts
        boolean createBidirectional,   // Create bidirectional edges
        boolean useEmbeddingSimilarity,// Use embedding similarity for linking
        boolean useConceptOverlap,     // Use concept overlap for linking
        boolean createCrossSourceEdges // Create CROSS_SOURCE edge type
    ) {
        public static LinkingConfig defaults() {
            return new LinkingConfig(
                3,      // minSharedConcepts
                0.7,    // minSimilarity
                0.2,    // minConceptOverlap (20% Jaccard)
                true,   // createBidirectional
                true,   // useEmbeddingSimilarity
                true,   // useConceptOverlap
                true    // createCrossSourceEdges
            );
        }
    }

    /**
     * Result of linking operation.
     */
    record LinkingResult(
        int sourcesAnalyzed,
        int linksCreated,
        int conceptBasedLinks,
        int similarityBasedLinks,
        List<SourceLink> links,
        Map<String, Object> statistics
    ) {}

    /**
     * Represents a link between two sources.
     */
    record SourceLink(
        String sourceId1,
        String sourceName1,
        String sourceId2,
        String sourceName2,
        String linkType,          // SHARED_CONCEPTS, EMBEDDING_SIMILARITY, CROSS_SOURCE
        double strength,
        List<String> sharedConcepts,
        String description
    ) {}

    /**
     * Link sources within a fact sheet based on shared concepts.
     *
     * @param factSheetId The fact sheet to analyze
     * @param config      Linking configuration
     * @return Result of the linking operation
     */
    LinkingResult linkSourcesBySharedConcepts(Long factSheetId, LinkingConfig config);

    /**
     * Link sources within a fact sheet based on embedding similarity.
     * Requires access to the vector store.
     *
     * @param factSheetId The fact sheet to analyze
     * @param config      Linking configuration
     * @return Result of the linking operation
     */
    LinkingResult linkSourcesByEmbeddingSimilarity(Long factSheetId, LinkingConfig config);

    /**
     * Create all possible links between sources using both concept and similarity methods.
     *
     * @param factSheetId The fact sheet to analyze
     * @param config      Linking configuration
     * @return Combined result of all linking operations
     */
    LinkingResult linkAllSources(Long factSheetId, LinkingConfig config);

    /**
     * Get existing links between sources in a fact sheet.
     *
     * @param factSheetId The fact sheet to query
     * @return List of existing source links
     */
    List<SourceLink> getSourceLinks(Long factSheetId);

    /**
     * Get links for a specific source.
     *
     * @param factSheetId The fact sheet
     * @param sourceNodeId The source node ID
     * @return List of links involving this source
     */
    List<SourceLink> getLinksForSource(Long factSheetId, String sourceNodeId);

    /**
     * Create a manual link between two sources.
     *
     * @param factSheetId   The fact sheet
     * @param sourceNodeId1 First source node ID
     * @param sourceNodeId2 Second source node ID
     * @param description   Optional description
     * @param strength      Link strength (0.0 to 1.0)
     * @return The created link
     */
    SourceLink createManualLink(Long factSheetId, String sourceNodeId1, String sourceNodeId2,
                                String description, double strength);

    /**
     * Remove a link between two sources.
     *
     * @param factSheetId   The fact sheet
     * @param sourceNodeId1 First source node ID
     * @param sourceNodeId2 Second source node ID
     * @return True if link was removed
     */
    boolean removeLink(Long factSheetId, String sourceNodeId1, String sourceNodeId2);

    /**
     * Get a summary of source connectivity within a fact sheet.
     *
     * @param factSheetId The fact sheet to analyze
     * @return Map of statistics about source connectivity
     */
    Map<String, Object> getConnectivitySummary(Long factSheetId);

    /**
     * Find isolated sources (sources with no links to other sources).
     *
     * @param factSheetId The fact sheet to analyze
     * @return List of source node IDs that have no links
     */
    List<String> findIsolatedSources(Long factSheetId);

    /**
     * Find the most connected sources.
     *
     * @param factSheetId The fact sheet to analyze
     * @param limit       Maximum number of sources to return
     * @return List of source node IDs sorted by connection count
     */
    List<Map<String, Object>> findMostConnectedSources(Long factSheetId, int limit);

    // ═══════════════════════════════════════════════════════════════════════════
    // TERM-BASED LINKING
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * Result of term-based linking operation.
     */
    record TermLinkingResult(
        String term,
        int nodesFound,
        int linksCreated,
        List<String> linkedNodeIds,
        String message
    ) {}

    /**
     * Link all nodes that contain a specific term/entity.
     * Creates edges between all nodes that mention the given term.
     *
     * @param term         The term to search for (will be normalized)
     * @param factSheetId  Optional fact sheet scope (null for global)
     * @param edgeType     Type of edge to create (default: SHARED_ENTITY)
     * @param weight       Weight for the created edges (default: 0.7)
     * @return Result of the linking operation
     */
    TermLinkingResult linkNodesByTerm(String term, Long factSheetId, EdgeType edgeType, Double weight);

    /**
     * Link nodes that share any of the specified terms.
     * For each term, links all nodes mentioning that term.
     *
     * @param terms        List of terms to link by
     * @param factSheetId  Optional fact sheet scope
     * @param edgeType     Type of edge to create
     * @param weight       Weight for the created edges
     * @return List of results, one per term
     */
    List<TermLinkingResult> linkNodesByTerms(List<String> terms, Long factSheetId, EdgeType edgeType, Double weight);

    /**
     * Create a user-defined relation between two nodes based on a term/concept.
     *
     * @param sourceNodeId  Source node ID
     * @param targetNodeId  Target node ID
     * @param relationTerm  The term/concept that defines this relation
     * @param description   Optional description of the relation
     * @param weight        Relation weight (0.0 to 1.0)
     * @param bidirectional Whether the relation is bidirectional
     * @return The created link
     */
    SourceLink createTermBasedRelation(String sourceNodeId, String targetNodeId, String relationTerm,
                                       String description, double weight, boolean bidirectional);

    /**
     * Find all nodes containing a specific term/entity.
     *
     * @param term        The term to search for
     * @param factSheetId Optional fact sheet scope
     * @param limit       Maximum results
     * @return List of node IDs containing the term
     */
    List<String> findNodesWithTerm(String term, Long factSheetId, int limit);

    /**
     * Get all unique terms/entities across the knowledge graph.
     *
     * @param factSheetId Optional fact sheet scope
     * @param limit       Maximum results
     * @return List of terms with their occurrence counts
     */
    List<Map<String, Object>> getAllTerms(Long factSheetId, int limit);

    /**
     * Get terms shared between two nodes.
     *
     * @param nodeId1     First node ID
     * @param nodeId2     Second node ID
     * @param factSheetId Optional fact sheet scope
     * @return List of shared terms
     */
    List<String> getSharedTerms(String nodeId1, String nodeId2, Long factSheetId);
}
