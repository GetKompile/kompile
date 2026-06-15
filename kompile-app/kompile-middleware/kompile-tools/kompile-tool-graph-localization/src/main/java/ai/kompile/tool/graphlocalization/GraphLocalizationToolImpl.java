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
package ai.kompile.tool.graphlocalization;

import ai.kompile.core.mcp.optimization.McpOptimizationConfigProvider;
import ai.kompile.core.mcp.optimization.ResultReferenceCache;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * MCP Tool for graph localization search.
 *
 * <p>Provides structural graph queries that go beyond text-based search:
 * multi-hop neighborhood exploration with type/weight filters, structural
 * pattern matching (degree, connectivity), quantitative neighborhood profiling,
 * hub/centrality detection, constrained path search, community detection,
 * and neighborhood comparison.
 *
 * <p>These tools let an LLM reason about graph topology and discover
 * neighborhoods matching complex structural criteria.
 */
@Component
@ConditionalOnBean(GraphLocalizationService.class)
public class GraphLocalizationToolImpl {

    private static final Logger log = LoggerFactory.getLogger(GraphLocalizationToolImpl.class);

    private final GraphLocalizationService localizationService;
    private final McpOptimizationConfigProvider optimizationProvider;
    private final ResultReferenceCache resultCache;

    // ═══════════════════════════════════════════════════════════════════════════
    // INPUT RECORDS
    // ═══════════════════════════════════════════════════════════════════════════

    public record NeighborhoodExploreInput(
            @ToolParam(description = "Node ID or external ID of the seed node to explore from") String seedNodeId,
            @ToolParam(description = "Maximum hop distance from seed (1-4, default 2)") Integer maxDepth,
            @ToolParam(description = "Maximum number of nodes to return (default 50)") Integer maxNodes,
            @ToolParam(description = "Filter to only include nodes connected to entities of these types (e.g. ['PERSON', 'ORGANIZATION'])") List<String> entityTypeFilter,
            @ToolParam(description = "Filter to only traverse edges of these types: HIERARCHICAL, EMBEDDING_SIMILARITY, SHARED_ENTITY, USER_DEFINED, CITATION, TEMPORAL, CROSS_SOURCE, CONTAINS") List<String> edgeTypeFilter,
            @ToolParam(description = "Minimum edge weight threshold (0.0 to 1.0)") Double minEdgeWeight,
            @ToolParam(description = "Fact sheet ID to scope the search (null for global)") Long factSheetId
    ) {}

    public record StructuralSearchInput(
            @ToolParam(description = "Minimum number of connections a node must have") Integer minDegree,
            @ToolParam(description = "Maximum number of connections a node can have") Integer maxDegree,
            @ToolParam(description = "Node must have at least one edge of each listed type") List<String> requiredEdgeTypes,
            @ToolParam(description = "Node must be connected to entities of all listed types (e.g. ['PERSON', 'LOCATION'])") List<String> requiredEntityTypes,
            @ToolParam(description = "Filter by node type: SOURCE, DOCUMENT, SNIPPET, ENTITY, TABLE") String nodeType,
            @ToolParam(description = "Restrict search to the neighborhood of this node ID") String withinNeighborhoodOf,
            @ToolParam(description = "Hop distance for neighborhood restriction (default 2)") Integer neighborhoodHops,
            @ToolParam(description = "Maximum results to return (default 20)") Integer maxResults,
            @ToolParam(description = "Fact sheet ID to scope the search") Long factSheetId
    ) {}

    public record NeighborhoodProfileInput(
            @ToolParam(description = "Node ID to profile the neighborhood of") String seedNodeId,
            @ToolParam(description = "Depth of neighborhood to profile (1-3, default 2)") Integer depth,
            @ToolParam(description = "Fact sheet ID to scope the profile") Long factSheetId
    ) {}

    public record FindHubsInput(
            @ToolParam(description = "Centrality metric: 'degree' (most connections), 'pagerank' (most important), 'betweenness' (most bridging)") String metric,
            @ToolParam(description = "Filter hubs by node type: SOURCE, DOCUMENT, SNIPPET, ENTITY") String nodeType,
            @ToolParam(description = "Restrict to the neighborhood of this node ID (null for global)") String scopeNodeId,
            @ToolParam(description = "Hop distance for scope restriction") Integer scopeHops,
            @ToolParam(description = "Number of top hubs to return (default 10)") Integer topK,
            @ToolParam(description = "Fact sheet ID to scope the search") Long factSheetId
    ) {}

    public record ConstrainedPathInput(
            @ToolParam(description = "Starting node ID") String fromNodeId,
            @ToolParam(description = "Destination node ID") String toNodeId,
            @ToolParam(description = "Only traverse edges of these types (null for any)") List<String> allowedEdgeTypes,
            @ToolParam(description = "Path must pass through nodes of these types") List<String> requiredIntermediateTypes,
            @ToolParam(description = "Maximum path length in hops (default 4)") Integer maxPathLength,
            @ToolParam(description = "Use edge weights for shortest path (default false)") Boolean weighted,
            @ToolParam(description = "Fact sheet ID to scope the search") Long factSheetId
    ) {}

    public record CompareNeighborhoodsInput(
            @ToolParam(description = "First node ID to compare") String nodeAId,
            @ToolParam(description = "Second node ID to compare") String nodeBId,
            @ToolParam(description = "Depth of neighborhood comparison (1-3, default 2)") Integer depth,
            @ToolParam(description = "Fact sheet ID to scope the comparison") Long factSheetId
    ) {}

    public record FindCommunityInput(
            @ToolParam(description = "Node ID to find the community of") String seedNodeId,
            @ToolParam(description = "Community detection algorithm: 'louvain' (modularity-based) or 'wcc' (weakly connected components)") String algorithm,
            @ToolParam(description = "Maximum community members to return (default 30)") Integer maxMembers,
            @ToolParam(description = "Fact sheet ID to scope the detection") Long factSheetId
    ) {}

    @Autowired
    public GraphLocalizationToolImpl(
            GraphLocalizationService localizationService,
            @Autowired(required = false) McpOptimizationConfigProvider optimizationProvider,
            @Autowired(required = false) ResultReferenceCache resultCache) {
        this.localizationService = localizationService;
        this.optimizationProvider = optimizationProvider != null
                ? optimizationProvider
                : McpOptimizationConfigProvider.ofDefaults();
        this.resultCache = resultCache;
        log.info("GraphLocalizationToolImpl initialized");
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // TOOL METHODS
    // ═══════════════════════════════════════════════════════════════════════════

    @Tool(name = "graph_explore_neighborhood",
          description = "Explore the multi-hop neighborhood of a node in the knowledge graph. " +
                        "Returns the actual subgraph structure (nodes and edges) around a seed node, " +
                        "with filters on entity types, edge types, and minimum edge weight. " +
                        "Use this to discover what surrounds a node and how things connect.")
    public Map<String, Object> exploreNeighborhood(NeighborhoodExploreInput input) {
        log.info("Graph Localization: exploring neighborhood of {}", input.seedNodeId());

        if (input.seedNodeId() == null || input.seedNodeId().isBlank()) {
            return Map.of("error", "seedNodeId is required");
        }

        try {
            return localizationService.exploreNeighborhood(
                    input.seedNodeId(),
                    input.maxDepth() != null ? input.maxDepth() : 2,
                    input.maxNodes() != null ? input.maxNodes() : 50,
                    input.entityTypeFilter(),
                    input.edgeTypeFilter(),
                    input.minEdgeWeight(),
                    input.factSheetId()
            );
        } catch (Exception e) {
            log.error("Neighborhood exploration failed: {}", e.getMessage(), e);
            return Map.of("error", "Exploration failed: " + e.getMessage());
        }
    }

    @Tool(name = "graph_structural_search",
          description = "Find nodes matching structural criteria in the knowledge graph. " +
                        "Filter by degree (number of connections), required edge types, " +
                        "required connected entity types, node type, and optionally restrict " +
                        "to the neighborhood of a specific node. " +
                        "Use this to find nodes with specific connectivity patterns " +
                        "(e.g. 'documents connected to both a PERSON and an ORGANIZATION with at least 5 edges').")
    public Map<String, Object> structuralSearch(StructuralSearchInput input) {
        log.info("Graph Localization: structural search");

        try {
            return localizationService.structuralSearch(
                    input.minDegree(),
                    input.maxDegree(),
                    input.requiredEdgeTypes(),
                    input.requiredEntityTypes(),
                    input.nodeType(),
                    input.withinNeighborhoodOf(),
                    input.neighborhoodHops(),
                    input.maxResults() != null ? input.maxResults() : 20,
                    input.factSheetId()
            );
        } catch (Exception e) {
            log.error("Structural search failed: {}", e.getMessage(), e);
            return Map.of("error", "Search failed: " + e.getMessage());
        }
    }

    @Tool(name = "graph_neighborhood_profile",
          description = "Generate a quantitative profile of a node's neighborhood in the knowledge graph. " +
                        "Returns: node/entity/edge type distributions, graph density, " +
                        "top nodes ranked by PageRank centrality, BFS layer sizes, " +
                        "and bridge nodes (whose removal would disconnect the neighborhood). " +
                        "Use this to understand the shape, composition, and key players of a graph region.")
    public Map<String, Object> profileNeighborhood(NeighborhoodProfileInput input) {
        log.info("Graph Localization: profiling neighborhood of {}", input.seedNodeId());

        if (input.seedNodeId() == null || input.seedNodeId().isBlank()) {
            return Map.of("error", "seedNodeId is required");
        }

        try {
            return localizationService.profileNeighborhood(
                    input.seedNodeId(),
                    input.depth() != null ? input.depth() : 2,
                    input.factSheetId()
            );
        } catch (Exception e) {
            log.error("Neighborhood profiling failed: {}", e.getMessage(), e);
            return Map.of("error", "Profiling failed: " + e.getMessage());
        }
    }

    @Tool(name = "graph_find_hubs",
          description = "Find the most important/connected hub nodes in the knowledge graph. " +
                        "Supports three centrality metrics: " +
                        "'degree' (most connections), " +
                        "'pagerank' (most important based on link structure), " +
                        "'betweenness' (most bridging between communities). " +
                        "Optionally filter by node type and restrict to a node's neighborhood. " +
                        "Use this to find key entities, central documents, or bridge nodes.")
    public Map<String, Object> findHubs(FindHubsInput input) {
        log.info("Graph Localization: finding hubs by {}", input.metric());

        try {
            return localizationService.findHubs(
                    input.metric(),
                    input.nodeType(),
                    input.scopeNodeId(),
                    input.scopeHops(),
                    input.topK() != null ? input.topK() : 10,
                    input.factSheetId()
            );
        } catch (Exception e) {
            log.error("Hub detection failed: {}", e.getMessage(), e);
            return Map.of("error", "Hub detection failed: " + e.getMessage());
        }
    }

    @Tool(name = "graph_constrained_path",
          description = "Find a path between two nodes with constraints on edge types and " +
                        "required intermediate node types. " +
                        "For example: find a path from node A to node B that only uses " +
                        "SHARED_ENTITY edges and passes through a DOCUMENT node. " +
                        "Use this to understand how two nodes are connected through specific relationship chains.")
    public Map<String, Object> constrainedPath(ConstrainedPathInput input) {
        log.info("Graph Localization: constrained path from {} to {}", input.fromNodeId(), input.toNodeId());

        if (input.fromNodeId() == null || input.fromNodeId().isBlank()) {
            return Map.of("error", "fromNodeId is required");
        }
        if (input.toNodeId() == null || input.toNodeId().isBlank()) {
            return Map.of("error", "toNodeId is required");
        }

        try {
            return localizationService.constrainedPathSearch(
                    input.fromNodeId(),
                    input.toNodeId(),
                    input.allowedEdgeTypes(),
                    input.requiredIntermediateTypes(),
                    input.maxPathLength() != null ? input.maxPathLength() : 4,
                    input.weighted() != null && input.weighted(),
                    input.factSheetId()
            );
        } catch (Exception e) {
            log.error("Constrained path search failed: {}", e.getMessage(), e);
            return Map.of("error", "Path search failed: " + e.getMessage());
        }
    }

    @Tool(name = "graph_compare_neighborhoods",
          description = "Compare the neighborhoods of two nodes in the knowledge graph. " +
                        "Returns: Jaccard similarity score, shared/unique node counts, " +
                        "shared entity types and names, neighborhood sizes. " +
                        "Use this to understand how two nodes relate structurally " +
                        "and what they have in common.")
    public Map<String, Object> compareNeighborhoods(CompareNeighborhoodsInput input) {
        log.info("Graph Localization: comparing neighborhoods of {} and {}",
                input.nodeAId(), input.nodeBId());

        if (input.nodeAId() == null || input.nodeAId().isBlank()) {
            return Map.of("error", "nodeAId is required");
        }
        if (input.nodeBId() == null || input.nodeBId().isBlank()) {
            return Map.of("error", "nodeBId is required");
        }

        try {
            return localizationService.compareNeighborhoods(
                    input.nodeAId(),
                    input.nodeBId(),
                    input.depth() != null ? input.depth() : 2,
                    input.factSheetId()
            );
        } catch (Exception e) {
            log.error("Neighborhood comparison failed: {}", e.getMessage(), e);
            return Map.of("error", "Comparison failed: " + e.getMessage());
        }
    }

    @Tool(name = "graph_find_community",
          description = "Find the community/cluster a node belongs to using graph community detection. " +
                        "Supports Louvain (modularity optimization, finds tightly-knit groups) " +
                        "and WCC (weakly connected components, finds disconnected subgraphs). " +
                        "Returns community members ranked by PageRank, entity type distribution, " +
                        "community size, and total community count. " +
                        "Use this to discover what group a node naturally belongs to.")
    public Map<String, Object> findCommunity(FindCommunityInput input) {
        log.info("Graph Localization: finding community for {}", input.seedNodeId());

        if (input.seedNodeId() == null || input.seedNodeId().isBlank()) {
            return Map.of("error", "seedNodeId is required");
        }

        try {
            return localizationService.findCommunity(
                    input.seedNodeId(),
                    input.algorithm(),
                    input.maxMembers() != null ? input.maxMembers() : 30,
                    input.factSheetId()
            );
        } catch (Exception e) {
            log.error("Community detection failed: {}", e.getMessage(), e);
            return Map.of("error", "Community detection failed: " + e.getMessage());
        }
    }
}
