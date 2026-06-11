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

package ai.kompile.codeindexer.service;

import ai.kompile.codeindexer.domain.*;
import ai.kompile.codeindexer.service.CodebaseIndexer.IndexingStatus;
import ai.kompile.knowledgegraph.domain.EdgeType;
import ai.kompile.knowledgegraph.domain.GraphNode;
import ai.kompile.knowledgegraph.domain.NodeLevel;
import ai.kompile.knowledgegraph.io.format.HtmlGraphExporter;
import ai.kompile.knowledgegraph.io.format.PortableGraph;
import ai.kompile.knowledgegraph.io.format.SvgGraphExporter;
import ai.kompile.knowledgegraph.io.model.PortableEdge;
import ai.kompile.knowledgegraph.io.model.PortableNode;
import ai.kompile.knowledgegraph.service.KnowledgeGraphService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.stream.Collectors;

/**
 * High-level service that orchestrates building a complete, fully-connected
 * code knowledge graph for a project. Wraps {@link CodebaseIndexer} and adds
 * post-processing passes to ensure every parent-child and cross-reference
 * relationship is represented as an edge in the knowledge graph.
 */
@Service
public class CodeGraphBuilder {

    private static final Logger log = LoggerFactory.getLogger(CodeGraphBuilder.class);

    private final CodebaseIndexer codebaseIndexer;
    private final CodeSearchService codeSearchService;
    private final CodeEntityRepository entityRepository;
    private final CodeRelationRepository relationRepository;
    private final KnowledgeGraphService knowledgeGraphService;
    private final TestCoverageAnalyzer testCoverageAnalyzer;

    /** Maps CodeRelationType → knowledge graph EdgeType for relation promotion. */
    private static final Map<CodeRelationType, EdgeType> RELATION_EDGE_TYPE_MAP;
    static {
        Map<CodeRelationType, EdgeType> m = new EnumMap<>(CodeRelationType.class);
        m.put(CodeRelationType.CALLS, EdgeType.SHARED_ENTITY);
        m.put(CodeRelationType.OVERRIDES, EdgeType.USER_DEFINED);
        m.put(CodeRelationType.ANNOTATED_BY, EdgeType.CITATION);
        m.put(CodeRelationType.DEPENDS_ON, EdgeType.CROSS_SOURCE);
        m.put(CodeRelationType.RETURNS, EdgeType.SHARED_ENTITY);
        m.put(CodeRelationType.PARAMETER_TYPE, EdgeType.SHARED_ENTITY);
        m.put(CodeRelationType.FIELD_TYPE, EdgeType.SHARED_ENTITY);
        RELATION_EDGE_TYPE_MAP = Collections.unmodifiableMap(m);
    }

    @Autowired
    public CodeGraphBuilder(CodebaseIndexer codebaseIndexer,
                            CodeSearchService codeSearchService,
                            CodeEntityRepository entityRepository,
                            CodeRelationRepository relationRepository,
                            @Autowired(required = false) KnowledgeGraphService knowledgeGraphService,
                            TestCoverageAnalyzer testCoverageAnalyzer) {
        this.codebaseIndexer = codebaseIndexer;
        this.codeSearchService = codeSearchService;
        this.entityRepository = entityRepository;
        this.relationRepository = relationRepository;
        this.knowledgeGraphService = knowledgeGraphService;
        this.testCoverageAnalyzer = testCoverageAnalyzer;
    }

    // ── Main graph construction ───────────────────────────────────────────────

    /**
     * Main entry point. Indexes the given directory under {@code projectId},
     * then runs a connectivity post-processing pass to fill in any missing edges.
     *
     * @param projectId     Logical project identifier
     * @param directoryPath Absolute path to the root source directory
     * @return A result map containing indexing statistics and connectivity counts
     */
    public Map<String, Object> buildGraph(String projectId, String directoryPath) {
        log.info("Building code graph: projectId={}, directory={}", projectId, directoryPath);

        IndexingStatus status = codebaseIndexer.indexDirectory(projectId, directoryPath);

        Map<String, Object> result = new LinkedHashMap<>(status.toMap());

        int edgesAdded = ensureConnectivity(projectId);
        result.put("connectivityEdgesAdded", edgesAdded);

        log.info("Graph build complete: projectId={}, edgesAdded={}", projectId, edgesAdded);
        return result;
    }

    /**
     * Index multiple directories into the same project graph, then run a single
     * connectivity pass across all of them.
     *
     * @param projectId Logical project identifier
     * @param paths     List of absolute directory paths to index
     * @return A result map summarising each directory and the final connectivity count
     */
    public Map<String, Object> buildGraphFromMultipleDirectories(String projectId, List<String> paths) {
        log.info("Building graph from {} directories: projectId={}", paths.size(), projectId);

        List<Map<String, Object>> directoryResults = new ArrayList<>();
        for (String path : paths) {
            log.info("Indexing directory: {}", path);
            IndexingStatus status = codebaseIndexer.indexDirectory(projectId, path);
            directoryResults.add(status.toMap());
        }

        int edgesAdded = ensureConnectivity(projectId);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("projectId", projectId);
        result.put("directoriesIndexed", paths.size());
        result.put("directoryResults", directoryResults);
        result.put("connectivityEdgesAdded", edgesAdded);
        return result;
    }

    // ── Post-processing: connectivity ─────────────────────────────────────────

    /**
     * Post-processing pass that ensures complete connectivity in the knowledge
     * graph for the given project.
     *
     * <ul>
     *   <li>For each entity with a {@code parentFqn}, verifies a
     *       {@link EdgeType#HIERARCHICAL} edge exists from parent → child and
     *       creates it if missing.</li>
     *   <li>For entities whose type implies a cross-source relationship
     *       (EXTENDS, IMPLEMENTS, IMPORTS), locates the target within the
     *       project and creates a {@link EdgeType#CROSS_SOURCE} or
     *       {@link EdgeType#USER_DEFINED} edge if missing.</li>
     * </ul>
     *
     * @param projectId Logical project identifier
     * @return Total number of edges created during this pass
     */
    public int ensureConnectivity(String projectId) {
        if (knowledgeGraphService == null) {
            log.debug("KnowledgeGraphService not available; skipping connectivity pass");
            return 0;
        }

        log.info("Running connectivity pass for projectId={}", projectId);

        List<CodeEntity> allEntities = entityRepository.findByProjectId(projectId);

        // Build a lookup map: FQN → graph node ID string
        Map<String, String> fqnToNodeId = buildFqnToNodeIdMap(allEntities);

        int edgesAdded = 0;

        for (CodeEntity entity : allEntities) {
            // ── Parent → child (HIERARCHICAL) ──────────────────────────────
            if (entity.getParentFqn() != null && !entity.getParentFqn().isBlank()) {
                String childNodeId  = fqnToNodeId.get(entity.getFullyQualifiedName());
                String parentNodeId = fqnToNodeId.get(entity.getParentFqn());

                if (childNodeId != null && parentNodeId != null) {
                    if (!knowledgeGraphService.edgeExists(parentNodeId, childNodeId)) {
                        try {
                            knowledgeGraphService.createEdge(
                                    parentNodeId, childNodeId,
                                    EdgeType.HIERARCHICAL, 1.0,
                                    "hierarchical: " + entity.getParentFqn()
                                            + " -> " + entity.getFullyQualifiedName());
                            edgesAdded++;
                        } catch (Exception e) {
                            log.warn("Failed to create HIERARCHICAL edge {} -> {}: {}",
                                    entity.getParentFqn(), entity.getFullyQualifiedName(), e.getMessage());
                        }
                    }
                } else {
                    // Parent not yet in the graph — create a stub SOURCE node so the
                    // edge can be materialised later when the parent is indexed.
                    if (parentNodeId == null && entity.getParentFqn() != null) {
                        log.debug("Parent FQN {} not found in graph for project {}; skipping edge",
                                entity.getParentFqn(), projectId);
                    }
                }
            }

            // ── Cross-reference edges (EXTENDS / IMPLEMENTS / IMPORTS) ──────
            // The metadata JSON may carry these relationships; fall back to
            // searching for entities whose name matches well-known annotation
            // patterns.  The primary mechanism is handled by searching for
            // sibling entities that share the same parent FQN or have a name
            // that appears in signature text (best-effort without full AST).
            edgesAdded += ensureCrossReferenceEdges(entity, fqnToNodeId, projectId);
        }

        // ── Promote CodeRelation records to graph edges ──────────────────
        edgesAdded += promoteRelationsToEdges(projectId, fqnToNodeId);

        log.info("Connectivity pass complete: projectId={}, edgesAdded={}", projectId, edgesAdded);
        return edgesAdded;
    }

    // ── Graph visualisation ───────────────────────────────────────────────────

    /**
     * Return graph data suitable for D3.js / force-directed UI rendering.
     *
     * @param projectId Logical project identifier
     * @param maxNodes  Maximum number of nodes to include
     * @return Map with {@code "nodes"} and {@code "edges"} lists, or empty map if
     *         the graph service is unavailable
     */
    public Map<String, Object> getGraphVisualization(String projectId, int maxNodes) {
        if (knowledgeGraphService == null) {
            log.debug("KnowledgeGraphService not available; returning empty visualization");
            return Map.of("nodes", List.of(), "edges", List.of());
        }

        // Use the project root node as anchor if it exists, otherwise pass null
        // so the service returns the entire (sampled) graph.
        Optional<GraphNode> rootNode = knowledgeGraphService.getNodeByExternalId(
                projectId, NodeLevel.SOURCE);
        String rootNodeId = rootNode.map(GraphNode::getNodeId).orElse(null);

        return knowledgeGraphService.getVisualizationData(rootNodeId, 5, maxNodes);
    }

    // ── Combined search ───────────────────────────────────────────────────────

    /**
     * Combined search across indexed code entities and the knowledge graph.
     *
     * @param projectId  Logical project identifier
     * @param query      Search query
     * @param maxResults Maximum results to return per source
     * @return Map with {@code "codeEntities"} and {@code "graphNodes"} lists
     */
    public Map<String, Object> searchGraph(String projectId, String query, int maxResults) {
        List<CodeEntity> codeResults = codeSearchService.search(projectId, query, maxResults);

        List<GraphNode> graphNodes = Collections.emptyList();
        if (knowledgeGraphService != null) {
            graphNodes = knowledgeGraphService.searchNodes(query, null, maxResults);
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("codeEntities", codeResults);
        result.put("graphNodes", graphNodes);
        result.put("totalCodeEntities", codeResults.size());
        result.put("totalGraphNodes", graphNodes.size());
        return result;
    }

    // ── Symbol subgraph ───────────────────────────────────────────────────────

    /**
     * Return the subgraph centred on a specific symbol (class, method, etc.)
     * up to {@code depth} hops away.
     *
     * @param projectId Logical project identifier
     * @param fqn       Fully qualified name of the target symbol
     * @param depth     Maximum traversal depth (hops)
     * @return Map with the focal entity, connected graph nodes, and visualization data
     */
    public Map<String, Object> getSymbolGraph(String projectId, String fqn, int depth) {
        Map<String, Object> result = new LinkedHashMap<>();

        Optional<CodeEntity> entityOpt = entityRepository
                .findByProjectIdAndFullyQualifiedName(projectId, fqn);
        result.put("entity", entityOpt.orElse(null));

        if (knowledgeGraphService == null) {
            result.put("connectedNodes", List.of());
            result.put("visualization", Map.of("nodes", List.of(), "edges", List.of()));
            return result;
        }

        // Resolve the graph node for this FQN — try all node levels
        Optional<GraphNode> nodeOpt = knowledgeGraphService.getNodeByExternalId(fqn, NodeLevel.ENTITY);
        if (nodeOpt.isEmpty()) {
            nodeOpt = knowledgeGraphService.getNodeByExternalId(fqn, NodeLevel.SNIPPET);
        }
        if (nodeOpt.isEmpty()) {
            nodeOpt = knowledgeGraphService.getNodeByExternalId(fqn, NodeLevel.DOCUMENT);
        }
        if (nodeOpt.isEmpty()) {
            nodeOpt = knowledgeGraphService.getNodeByExternalId(fqn, NodeLevel.SOURCE);
        }
        if (nodeOpt.isEmpty()) {
            nodeOpt = knowledgeGraphService.getNodeByExternalId(fqn, NodeLevel.CUSTOM);
        }

        if (nodeOpt.isEmpty()) {
            result.put("connectedNodes", List.of());
            result.put("visualization", Map.of("nodes", List.of(), "edges", List.of()));
            return result;
        }

        GraphNode focalNode = nodeOpt.get();
        String focalNodeId   = focalNode.getNodeId();

        List<GraphNode> connected = knowledgeGraphService.getConnectedNodes(focalNodeId, depth);
        Map<String, Object> vizData = knowledgeGraphService.getVisualizationData(
                focalNodeId, depth, connected.size() + 1);

        result.put("focalNode", focalNode);
        result.put("connectedNodes", connected);
        result.put("visualization", vizData);
        return result;
    }

    // ── File subgraph ─────────────────────────────────────────────────────────

    /**
     * Return all symbols defined in a file together with their knowledge-graph
     * connections.
     *
     * @param projectId Logical project identifier
     * @param filePath  Relative file path (as stored in {@link CodeEntity#getFilePath()})
     * @return Map with the file's entities, graph nodes, and visualisation data
     */
    public Map<String, Object> getFileGraph(String projectId, String filePath) {
        List<CodeEntity> fileEntities = entityRepository
                .findByProjectIdAndFilePath(projectId, filePath);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("filePath", filePath);
        result.put("entities", fileEntities);
        result.put("entityCount", fileEntities.size());

        if (knowledgeGraphService == null || fileEntities.isEmpty()) {
            result.put("graphNodes", List.of());
            result.put("visualization", Map.of("nodes", List.of(), "edges", List.of()));
            return result;
        }

        // Collect graph node IDs for all entities in the file
        List<GraphNode> fileGraphNodes = fileEntities.stream()
                .filter(e -> e.getGraphNodeId() != null)
                .map(e -> knowledgeGraphService.getNode(e.getGraphNodeId().toString()))
                .filter(Optional::isPresent)
                .map(Optional::get)
                .collect(Collectors.toList());

        // Also look up the FILE entity node (if it exists) as the visualisation root
        Optional<CodeEntity> fileEntityOpt = fileEntities.stream()
                .filter(e -> e.getEntityType() == CodeEntityType.FILE)
                .findFirst();

        String rootNodeId = fileEntityOpt
                .filter(e -> e.getGraphNodeId() != null)
                .map(e -> e.getGraphNodeId().toString())
                .orElse(fileGraphNodes.isEmpty() ? null : fileGraphNodes.get(0).getNodeId());

        Map<String, Object> vizData = rootNodeId != null
                ? knowledgeGraphService.getVisualizationData(rootNodeId, 2, 200)
                : Map.of("nodes", List.of(), "edges", List.of());

        result.put("graphNodes", fileGraphNodes);
        result.put("visualization", vizData);
        return result;
    }

    // ── Shortest path ─────────────────────────────────────────────────────────

    /**
     * Find the shortest path between two code entities (by FQN) through the
     * knowledge graph.  Returns the ordered list of nodes along the path
     * with their edge labels.
     *
     * @param projectId Logical project identifier
     * @param fromFqn   Fully qualified name of the source symbol
     * @param toFqn     Fully qualified name of the target symbol
     * @param edgeType  Optional edge type filter (null for all)
     * @return Map with the path nodes, edges, and hop count
     */
    public Map<String, Object> getShortestPath(String projectId, String fromFqn, String toFqn,
                                                String edgeType) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("from", fromFqn);
        result.put("to", toFqn);

        if (knowledgeGraphService == null) {
            result.put("error", "Knowledge graph not available");
            return result;
        }

        // Resolve FQNs to graph node IDs
        String fromNodeId = resolveNodeId(fromFqn);
        String toNodeId = resolveNodeId(toFqn);

        if (fromNodeId == null) {
            result.put("error", "Could not find graph node for: " + fromFqn);
            return result;
        }
        if (toNodeId == null) {
            result.put("error", "Could not find graph node for: " + toFqn);
            return result;
        }

        // Resolve edge type filter
        ai.kompile.knowledgegraph.domain.EdgeType edgeTypeFilter = null;
        if (edgeType != null && !edgeType.isEmpty()) {
            try {
                edgeTypeFilter = ai.kompile.knowledgegraph.domain.EdgeType.valueOf(edgeType.toUpperCase());
            } catch (IllegalArgumentException e) {
                result.put("error", "Invalid edge type: " + edgeType);
                return result;
            }
        }

        // edgeTypeFilter is resolved above but findShortestPath uses BFS without per-edge-type filtering
        List<GraphNode> path = knowledgeGraphService.findShortestPath(fromNodeId, toNodeId, 20);

        if (path.isEmpty()) {
            result.put("pathFound", false);
            result.put("hops", -1);
            result.put("path", List.of());
            return result;
        }

        result.put("pathFound", true);
        result.put("hops", path.size() - 1);
        result.put("path", path.stream().map(n -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("nodeId", n.getNodeId());
            m.put("externalId", n.getExternalId());
            m.put("title", n.getTitle());
            m.put("type", n.getNodeType() != null ? n.getNodeType().name() : null);
            m.put("description", n.getDescription());
            return m;
        }).collect(Collectors.toList()));

        return result;
    }

    /**
     * Resolve a fully-qualified name to a knowledge-graph node ID by trying
     * all node levels in order.
     */
    private String resolveNodeId(String fqn) {
        for (NodeLevel level : NodeLevel.values()) {
            Optional<GraphNode> nodeOpt = knowledgeGraphService.getNodeByExternalId(fqn, level);
            if (nodeOpt.isPresent()) return nodeOpt.get().getNodeId();
        }
        return null;
    }

    // ── Composite: impact analysis ──────────────────────────────────────────

    /**
     * Blast-radius analysis: given a symbol, return everything that would be
     * affected by changing it.  Answers: <em>"if I change X, what breaks?"</em>
     *
     * <p>Collects callers (reverse CALLS), subclasses (reverse EXTENDS),
     * implementors (reverse IMPLEMENTS), importers (reverse IMPORTS),
     * and all other incoming relations.</p>
     *
     * @param projectId Logical project identifier
     * @param fqn       Fully qualified name of the symbol under analysis
     * @return Structured map with categorised impact sets
     */
    public Map<String, Object> getImpactAnalysis(String projectId, String fqn) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("symbol", fqn);

        // Entity details
        Optional<CodeEntity> entityOpt = entityRepository
                .findByProjectIdAndFullyQualifiedName(projectId, fqn);
        result.put("entity", entityOpt.map(this::entityToMap).orElse(null));

        // Callers — anything that CALLS this symbol (by simple name)
        String simpleName = fqn.contains(".") ? fqn.substring(fqn.lastIndexOf('.') + 1) : fqn;
        List<CodeRelation> callers = relationRepository.findCallsToName(projectId, simpleName);
        result.put("callers", callers.stream().map(this::relationToMap).collect(Collectors.toList()));

        // Reverse relations by type — things that point AT this symbol
        List<CodeRelation> reverseExtends = relationRepository
                .findByProjectIdAndTargetFqnAndRelationType(projectId, fqn, CodeRelationType.EXTENDS);
        result.put("subclasses", reverseExtends.stream().map(this::relationToMap).collect(Collectors.toList()));

        List<CodeRelation> reverseImplements = relationRepository
                .findByProjectIdAndTargetFqnAndRelationType(projectId, fqn, CodeRelationType.IMPLEMENTS);
        result.put("implementors", reverseImplements.stream().map(this::relationToMap).collect(Collectors.toList()));

        List<CodeRelation> reverseImports = relationRepository
                .findByProjectIdAndTargetFqnAndRelationType(projectId, fqn, CodeRelationType.IMPORTS);
        result.put("importedBy", reverseImports.stream().map(this::relationToMap).collect(Collectors.toList()));

        // All other incoming relations (overrides-of, field-type-of, parameter-type-of, etc.)
        List<CodeRelation> allReverse = relationRepository.findByProjectIdAndTargetFqn(projectId, fqn);
        Set<String> alreadyCovered = new HashSet<>();
        callers.forEach(r -> alreadyCovered.add(r.getSourceFqn() + ":" + r.getRelationType()));
        reverseExtends.forEach(r -> alreadyCovered.add(r.getSourceFqn() + ":" + r.getRelationType()));
        reverseImplements.forEach(r -> alreadyCovered.add(r.getSourceFqn() + ":" + r.getRelationType()));
        reverseImports.forEach(r -> alreadyCovered.add(r.getSourceFqn() + ":" + r.getRelationType()));
        List<Map<String, Object>> otherDependants = allReverse.stream()
                .filter(r -> !alreadyCovered.contains(r.getSourceFqn() + ":" + r.getRelationType()))
                .map(this::relationToMap)
                .collect(Collectors.toList());
        result.put("otherDependants", otherDependants);

        // Summary counts
        Map<String, Integer> summary = new LinkedHashMap<>();
        summary.put("callers", callers.size());
        summary.put("subclasses", reverseExtends.size());
        summary.put("implementors", reverseImplements.size());
        summary.put("importedBy", reverseImports.size());
        summary.put("otherDependants", otherDependants.size());
        summary.put("totalImpact", callers.size() + reverseExtends.size()
                + reverseImplements.size() + reverseImports.size() + otherDependants.size());
        result.put("summary", summary);

        return result;
    }

    // ── Composite: dependency tree ───────────────────────────────────────────

    /**
     * Dependency tree: what does this symbol depend on?  Follows outgoing
     * CALLS, EXTENDS, IMPLEMENTS, IMPORTS, DEPENDS_ON, RETURNS,
     * PARAMETER_TYPE, and FIELD_TYPE relations recursively up to
     * {@code maxDepth} hops.  Answers: <em>"what does X need?"</em>
     *
     * @param projectId Logical project identifier
     * @param fqn       Fully qualified name of the root symbol
     * @param maxDepth  Maximum recursion depth (hops)
     * @return Tree structure with dependency layers
     */
    public Map<String, Object> getDependencyTree(String projectId, String fqn, int maxDepth) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("root", fqn);
        result.put("maxDepth", maxDepth);

        Optional<CodeEntity> entityOpt = entityRepository
                .findByProjectIdAndFullyQualifiedName(projectId, fqn);
        result.put("entity", entityOpt.map(this::entityToMap).orElse(null));

        // BFS over outgoing relations
        Set<String> visited = new LinkedHashSet<>();
        visited.add(fqn);
        List<Map<String, Object>> layers = new ArrayList<>();
        Set<String> frontier = new LinkedHashSet<>();
        frontier.add(fqn);

        for (int depth = 0; depth < maxDepth && !frontier.isEmpty(); depth++) {
            List<Map<String, Object>> layerEntries = new ArrayList<>();
            Set<String> nextFrontier = new LinkedHashSet<>();

            for (String currentFqn : frontier) {
                List<CodeRelation> outgoing = relationRepository
                        .findByProjectIdAndSourceFqn(projectId, currentFqn);
                for (CodeRelation rel : outgoing) {
                    String targetFqn = rel.getTargetFqn();
                    if (targetFqn == null || targetFqn.isBlank()) continue;
                    if (visited.contains(targetFqn)) continue;

                    visited.add(targetFqn);
                    nextFrontier.add(targetFqn);

                    Map<String, Object> entry = new LinkedHashMap<>();
                    entry.put("fqn", targetFqn);
                    entry.put("relationType", rel.getRelationType().name());
                    entry.put("from", currentFqn);
                    // Attach entity details if available
                    entityRepository.findByProjectIdAndFullyQualifiedName(projectId, targetFqn)
                            .ifPresent(e -> {
                                entry.put("entityType", e.getEntityType().name());
                                entry.put("name", e.getName());
                                entry.put("filePath", e.getFilePath());
                                entry.put("language", e.getLanguage());
                            });
                    layerEntries.add(entry);
                }
            }

            if (!layerEntries.isEmpty()) {
                Map<String, Object> layer = new LinkedHashMap<>();
                layer.put("depth", depth + 1);
                layer.put("count", layerEntries.size());
                layer.put("dependencies", layerEntries);
                layers.add(layer);
            }
            frontier = nextFrontier;
        }

        result.put("layers", layers);
        result.put("totalDependencies", visited.size() - 1); // exclude root
        return result;
    }

    // ── Composite: component map ─────────────────────────────────────────────

    /**
     * Module/package boundary view: all types in a file or under a given
     * parent FQN (package), their inheritance hierarchies, and cross-module
     * edges.  Answers: <em>"what belongs here?"</em>
     *
     * @param projectId     Logical project identifier
     * @param pathOrPackage File path (contains '/') or parent FQN (package)
     * @return Map with member entities, inheritance, and external references
     */
    public Map<String, Object> getComponentMap(String projectId, String pathOrPackage) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("scope", pathOrPackage);

        // Determine if the scope is a file path or a package/parent FQN
        boolean isFilePath = pathOrPackage.contains("/") || pathOrPackage.contains("\\")
                || pathOrPackage.endsWith(".java") || pathOrPackage.endsWith(".py")
                || pathOrPackage.endsWith(".ts") || pathOrPackage.endsWith(".js");

        List<CodeEntity> members;
        if (isFilePath) {
            members = entityRepository.findByProjectIdAndFilePath(projectId, pathOrPackage);
            result.put("scopeType", "file");
        } else {
            members = entityRepository.findByProjectIdAndParentFqn(projectId, pathOrPackage);
            result.put("scopeType", "package");
        }

        result.put("members", members.stream().map(this::entityToMap).collect(Collectors.toList()));
        result.put("memberCount", members.size());

        // Group by entity type
        Map<String, Long> typeCounts = members.stream()
                .collect(Collectors.groupingBy(
                        e -> e.getEntityType().name(),
                        Collectors.counting()));
        result.put("typeBreakdown", typeCounts);

        // Inheritance: outgoing EXTENDS / IMPLEMENTS from members
        Set<String> memberFqns = members.stream()
                .map(CodeEntity::getFullyQualifiedName)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
        List<Map<String, Object>> inheritance = new ArrayList<>();
        List<Map<String, Object>> externalRefs = new ArrayList<>();

        for (String memberFqn : memberFqns) {
            List<CodeRelation> outgoing = relationRepository
                    .findByProjectIdAndSourceFqn(projectId, memberFqn);
            for (CodeRelation rel : outgoing) {
                Map<String, Object> entry = relationToMap(rel);
                if (rel.getRelationType() == CodeRelationType.EXTENDS
                        || rel.getRelationType() == CodeRelationType.IMPLEMENTS) {
                    inheritance.add(entry);
                }
                // Track cross-module references (target outside this scope)
                if (rel.getTargetFqn() != null && !memberFqns.contains(rel.getTargetFqn())) {
                    externalRefs.add(entry);
                }
            }
        }

        result.put("inheritance", inheritance);
        result.put("externalReferences", externalRefs);
        result.put("externalReferenceCount", externalRefs.size());

        return result;
    }

    // ── Composite: symbol dossier ────────────────────────────────────────────

    /**
     * Complete dossier for a single symbol: entity details, parent, children,
     * all incoming and outgoing relations, callers, and source location.
     * Answers: <em>"tell me everything about X."</em>
     *
     * @param projectId Logical project identifier
     * @param fqn       Fully qualified name of the symbol
     * @return Comprehensive symbol profile
     */
    public Map<String, Object> getSymbolDossier(String projectId, String fqn) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("fqn", fqn);

        // Entity details
        Optional<CodeEntity> entityOpt = entityRepository
                .findByProjectIdAndFullyQualifiedName(projectId, fqn);
        if (entityOpt.isEmpty()) {
            result.put("found", false);
            return result;
        }

        CodeEntity entity = entityOpt.get();
        result.put("found", true);
        result.put("entity", entityToMap(entity));

        // Parent
        if (entity.getParentFqn() != null && !entity.getParentFqn().isBlank()) {
            entityRepository.findByProjectIdAndFullyQualifiedName(projectId, entity.getParentFqn())
                    .ifPresent(parent -> result.put("parent", entityToMap(parent)));
        }

        // Children (entities whose parentFqn == this entity's FQN)
        List<CodeEntity> children = entityRepository
                .findByProjectIdAndParentFqn(projectId, fqn);
        result.put("children", children.stream().map(this::entityToMap).collect(Collectors.toList()));
        result.put("childCount", children.size());

        // Outgoing relations (what this symbol depends on)
        List<CodeRelation> outgoing = relationRepository
                .findByProjectIdAndSourceFqn(projectId, fqn);
        Map<String, List<Map<String, Object>>> outgoingByType = outgoing.stream()
                .map(this::relationToMap)
                .collect(Collectors.groupingBy(m -> String.valueOf(m.get("relationType"))));
        result.put("outgoingRelations", outgoingByType);
        result.put("outgoingCount", outgoing.size());

        // Incoming relations (what depends on this symbol)
        List<CodeRelation> incoming = relationRepository
                .findByProjectIdAndTargetFqn(projectId, fqn);
        Map<String, List<Map<String, Object>>> incomingByType = incoming.stream()
                .map(this::relationToMap)
                .collect(Collectors.groupingBy(m -> String.valueOf(m.get("relationType"))));
        result.put("incomingRelations", incomingByType);
        result.put("incomingCount", incoming.size());

        // Callers (by simple name, may overlap with incoming CALLS)
        String simpleName = fqn.contains(".") ? fqn.substring(fqn.lastIndexOf('.') + 1) : fqn;
        List<CodeRelation> callers = relationRepository.findCallsToName(projectId, simpleName);
        result.put("callers", callers.stream().map(this::relationToMap).collect(Collectors.toList()));
        result.put("callerCount", callers.size());

        return result;
    }

    // ── Composite: localized graph export ────────────────────────────────────

    /**
     * Generate a localized visualisation (SVG, HTML, or JSON) for a specific
     * symbol neighborhood or file subgraph, rather than the entire project.
     *
     * <p>This feeds the symbol subgraph or file subgraph through the same
     * {@link SvgGraphExporter} or {@link HtmlGraphExporter} used for full
     * project exports, producing a focused, agent-readable artefact.</p>
     *
     * @param projectId Logical project identifier
     * @param focus     FQN of a symbol or file path to centre the export on
     * @param format    One of: svg, html, json
     * @param depth     Traversal depth for symbol graphs (ignored for file graphs)
     * @return Map with keys: data (byte[]), contentType, filename
     */
    @SuppressWarnings("unchecked")
    public Map<String, Object> exportLocalizedGraph(String projectId, String focus,
                                                     String format, int depth) {
        // Determine if focus is a file path or FQN
        boolean isFile = focus.contains("/") || focus.contains("\\")
                || focus.endsWith(".java") || focus.endsWith(".py")
                || focus.endsWith(".ts") || focus.endsWith(".js")
                || focus.endsWith(".go") || focus.endsWith(".rs")
                || focus.endsWith(".cpp") || focus.endsWith(".c")
                || focus.endsWith(".h") || focus.endsWith(".cs");

        Map<String, Object> vizData;
        if (isFile) {
            Map<String, Object> fileGraph = getFileGraph(projectId, focus);
            vizData = (Map<String, Object>) fileGraph.getOrDefault("visualization",
                    Map.of("nodes", List.of(), "edges", List.of()));
        } else {
            Map<String, Object> symbolGraph = getSymbolGraph(projectId, focus, depth);
            vizData = (Map<String, Object>) symbolGraph.getOrDefault("visualization",
                    Map.of("nodes", List.of(), "edges", List.of()));
        }

        // Convert KGS visualization data → PortableGraph
        List<Map<String, Object>> nodeList = (List<Map<String, Object>>) vizData.getOrDefault("nodes", List.of());
        List<Map<String, Object>> edgeList = (List<Map<String, Object>>) vizData.getOrDefault("edges", List.of());

        List<PortableNode> portableNodes = new ArrayList<>();
        for (Map<String, Object> n : nodeList) {
            String id = String.valueOf(n.get("id"));
            String label = n.get("label") != null ? String.valueOf(n.get("label")) : id;
            String type = n.get("type") != null ? String.valueOf(n.get("type")).toUpperCase() : "ENTITY";
            String desc = n.get("description") != null ? String.valueOf(n.get("description")) : null;
            portableNodes.add(new PortableNode(id, label, desc, type, null));
        }

        List<PortableEdge> portableEdges = new ArrayList<>();
        for (Map<String, Object> e : edgeList) {
            String from = String.valueOf(e.get("source"));
            String to = String.valueOf(e.get("target"));
            String edgeType = e.get("type") != null ? String.valueOf(e.get("type")).toUpperCase() : null;
            Double weight = e.get("weight") instanceof Number ? ((Number) e.get("weight")).doubleValue() : 1.0;
            String desc = e.get("description") != null ? String.valueOf(e.get("description")) : null;
            portableEdges.add(new PortableEdge(from, to, edgeType, weight, desc, null, null));
        }

        PortableGraph graph = new PortableGraph(portableNodes, portableEdges);
        String safeLabel = focus.replaceAll("[^a-zA-Z0-9._-]", "_");

        return switch (format.toLowerCase()) {
            case "svg" -> Map.of(
                    "data", new SvgGraphExporter().toBytes(graph),
                    "contentType", "image/svg+xml",
                    "filename", "local-graph-" + safeLabel + ".svg");
            case "html" -> Map.of(
                    "data", new HtmlGraphExporter().toBytes(graph),
                    "contentType", "text/html",
                    "filename", "local-graph-" + safeLabel + ".html");
            case "json" -> {
                try {
                    byte[] jsonBytes = new ObjectMapper().writerWithDefaultPrettyPrinter()
                            .writeValueAsBytes(graph);
                    yield Map.of(
                            "data", jsonBytes,
                            "contentType", "application/json",
                            "filename", "local-graph-" + safeLabel + ".json");
                } catch (Exception ex) {
                    throw new RuntimeException("Failed to serialize localized graph to JSON", ex);
                }
            }
            default -> throw new IllegalArgumentException(
                    "Unsupported export format: " + format + ". Supported: svg, html, json");
        };
    }

    // ── Test coverage & code paths ─────────────────────────────────────────

    /**
     * Detect test frameworks present in the indexed project by scanning
     * annotations, file paths, and naming conventions across all languages.
     */
    public Map<String, Object> getTestFrameworks(String projectId) {
        return testCoverageAnalyzer.detectTestFrameworks(projectId);
    }

    /**
     * Get a test coverage report: test/production ratio, coverage percentage,
     * untested methods, and framework breakdown.
     */
    public Map<String, Object> getTestCoverage(String projectId) {
        return testCoverageAnalyzer.getTestCoverageReport(projectId);
    }

    /**
     * Find tests that exercise a given production symbol by tracing reverse
     * CALLS relations back to test entities (direct and indirect).
     */
    public Map<String, Object> getTestsForSymbol(String projectId, String fqn) {
        return testCoverageAnalyzer.getTestsForSymbol(projectId, fqn);
    }

    /**
     * Trace execution paths from an entry point through CALLS relations,
     * collecting all reachable symbols up to {@code maxDepth}.
     */
    public Map<String, Object> getCodePaths(String projectId, String fromFqn, int maxDepth) {
        return testCoverageAnalyzer.traceCodePaths(projectId, fromFqn, maxDepth);
    }

    // ── Composite helpers ────────────────────────────────────────────────────

    /** Convert a CodeEntity to a flat map for JSON serialisation. */
    private Map<String, Object> entityToMap(CodeEntity e) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("name", e.getName());
        m.put("fqn", e.getFullyQualifiedName());
        m.put("entityType", e.getEntityType().name());
        m.put("filePath", e.getFilePath());
        m.put("startLine", e.getStartLine());
        m.put("endLine", e.getEndLine());
        m.put("language", e.getLanguage());
        m.put("signature", e.getSignature());
        m.put("visibility", e.getVisibility());
        m.put("parentFqn", e.getParentFqn());
        m.put("packageName", e.getPackageName());
        if (e.getDocComment() != null) m.put("docComment", e.getDocComment());
        return m;
    }

    /** Convert a CodeRelation to a flat map for JSON serialisation. */
    private Map<String, Object> relationToMap(CodeRelation r) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("relationType", r.getRelationType().name());
        m.put("sourceFqn", r.getSourceFqn());
        m.put("targetName", r.getTargetName());
        m.put("targetFqn", r.getTargetFqn());
        m.put("filePath", r.getFilePath());
        m.put("line", r.getLine());
        return m;
    }

    // ── Statistics ────────────────────────────────────────────────────────────

    /**
     * Return combined statistics from the code indexer and knowledge graph.
     *
     * @param projectId Logical project identifier
     * @return Aggregated statistics map
     */
    public Map<String, Object> getStatistics(String projectId) {
        Map<String, Object> stats = new LinkedHashMap<>();

        // Code indexer stats
        Map<String, Object> indexerStats = codeSearchService.getStatistics(projectId);
        stats.put("codeIndex", indexerStats);

        // Directory tracking stats
        List<ai.kompile.codeindexer.domain.IndexedDirectory> dirs =
                codebaseIndexer.listDirectories(projectId);
        stats.put("trackedDirectories", dirs.size());
        stats.put("directories", dirs.stream()
                .map(d -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("path", d.getAbsolutePath());
                    m.put("status", d.getStatus());
                    m.put("filesIndexed", d.getFilesIndexed());
                    m.put("entitiesFound", d.getEntitiesFound());
                    m.put("lastIndexedAt", d.getLastIndexedAt());
                    return m;
                })
                .collect(Collectors.toList()));

        // Knowledge graph stats
        if (knowledgeGraphService != null) {
            Map<String, Object> graphStats = knowledgeGraphService.getGraphStatistics();
            stats.put("knowledgeGraph", graphStats);
        } else {
            stats.put("knowledgeGraph", Map.of("available", false));
        }

        return stats;
    }

    // ── Relation promotion ──────────────────────────────────────────────────

    /**
     * Iterate over all {@link CodeRelation} records for this project and
     * promote CALLS, OVERRIDES, ANNOTATED_BY, DEPENDS_ON, RETURNS,
     * PARAMETER_TYPE, and FIELD_TYPE relations into knowledge graph edges
     * where both source and target FQNs resolve to graph nodes.
     *
     * <p>Relations whose target is only a simple name (no resolved {@code targetFqn})
     * are matched best-effort against the FQN map by suffix.  This bridges the
     * gap left by parsers that cannot resolve cross-file targets.</p>
     *
     * @return number of edges created
     */
    private int promoteRelationsToEdges(String projectId, Map<String, String> fqnToNodeId) {
        if (knowledgeGraphService == null) return 0;

        int added = 0;
        for (Map.Entry<CodeRelationType, EdgeType> entry : RELATION_EDGE_TYPE_MAP.entrySet()) {
            CodeRelationType relType = entry.getKey();
            EdgeType edgeType = entry.getValue();

            List<CodeRelation> relations = relationRepository
                    .findByProjectIdAndRelationType(projectId, relType);

            for (CodeRelation rel : relations) {
                String sourceNodeId = fqnToNodeId.get(rel.getSourceFqn());
                if (sourceNodeId == null) continue;

                // Try resolved FQN first, then fall back to simple-name suffix matching
                String targetNodeId = null;
                if (rel.getTargetFqn() != null && !rel.getTargetFqn().isBlank()) {
                    targetNodeId = fqnToNodeId.get(rel.getTargetFqn());
                }
                if (targetNodeId == null && rel.getTargetName() != null) {
                    targetNodeId = resolveBySuffix(rel.getTargetName(), fqnToNodeId);
                }
                if (targetNodeId == null) continue;

                // Skip self-edges and duplicates
                if (sourceNodeId.equals(targetNodeId)) continue;
                if (knowledgeGraphService.edgeExists(sourceNodeId, targetNodeId)) continue;

                try {
                    knowledgeGraphService.createEdge(
                            sourceNodeId, targetNodeId,
                            edgeType, 0.8,
                            relType.name().toLowerCase() + ": " + rel.getSourceFqn()
                                    + " -> " + (rel.getTargetFqn() != null ? rel.getTargetFqn() : rel.getTargetName()));
                    added++;
                } catch (Exception e) {
                    log.warn("Failed to promote {} relation {} -> {}: {}",
                            relType, rel.getSourceFqn(), rel.getTargetName(), e.getMessage());
                }
            }
        }

        if (added > 0) {
            log.info("Promoted {} code relations to graph edges for projectId={}", added, projectId);
        }
        return added;
    }

    /**
     * Best-effort: find a FQN in the map that ends with {@code "." + simpleName}.
     * Returns the first match, or null if no match is found.
     */
    private String resolveBySuffix(String simpleName, Map<String, String> fqnToNodeId) {
        String suffix = "." + simpleName;
        for (Map.Entry<String, String> e : fqnToNodeId.entrySet()) {
            if (e.getKey().endsWith(suffix) || e.getKey().equals(simpleName)) {
                return e.getValue();
            }
        }
        return null;
    }

    // ── Internal helpers ──────────────────────────────────────────────────────

    /**
     * Build a map from FQN → knowledge graph node ID string for all entities
     * that have an associated graph node.
     */
    private Map<String, String> buildFqnToNodeIdMap(List<CodeEntity> entities) {
        Map<String, String> map = new HashMap<>();
        for (CodeEntity entity : entities) {
            if (entity.getFullyQualifiedName() != null && entity.getGraphNodeId() != null) {
                map.put(entity.getFullyQualifiedName(), entity.getGraphNodeId().toString());
            }
        }
        return map;
    }

    /**
     * Best-effort: create CROSS_SOURCE or USER_DEFINED edges for semantic
     * relationships (EXTENDS, IMPLEMENTS, IMPORTS) that the initial indexing
     * pass may have missed if the target FQN was not yet in the graph.
     *
     * <p>This implementation matches entities by scanning the {@code metadataJson}
     * field for keys {@code extendsType}, {@code implementsTypes}, and
     * {@code importedType}.  Callers that store richer relationship data in
     * separate relation records will already have had those edges created by
     * {@link CodebaseIndexer}; this pass fills gaps for entities that encode
     * relationships only in metadata.</p>
     *
     * @return Number of edges created
     */
    private int ensureCrossReferenceEdges(CodeEntity entity,
                                          Map<String, String> fqnToNodeId,
                                          String projectId) {
        if (knowledgeGraphService == null) return 0;
        if (entity.getMetadataJson() == null || entity.getMetadataJson().isBlank()) return 0;

        String sourceNodeId = fqnToNodeId.get(entity.getFullyQualifiedName());
        if (sourceNodeId == null) return 0;

        int added = 0;

        // Parse lightweight: look for known keys in the raw JSON without a full
        // ObjectMapper dependency to avoid circular Spring context issues.
        String meta = entity.getMetadataJson();

        added += createEdgeIfTargetExists(meta, "extendsType",
                sourceNodeId, fqnToNodeId, EdgeType.USER_DEFINED,
                "extends", entity.getFullyQualifiedName());

        added += createEdgesForArrayKey(meta, "implementsTypes",
                sourceNodeId, fqnToNodeId, EdgeType.USER_DEFINED,
                "implements", entity.getFullyQualifiedName());

        added += createEdgeIfTargetExists(meta, "importedType",
                sourceNodeId, fqnToNodeId, EdgeType.CROSS_SOURCE,
                "imports", entity.getFullyQualifiedName());

        return added;
    }

    /**
     * Extract a single string value for {@code key} from a JSON snippet and
     * create an edge if the target FQN is indexed in this project.
     */
    private int createEdgeIfTargetExists(String json, String key,
                                         String sourceNodeId,
                                         Map<String, String> fqnToNodeId,
                                         EdgeType edgeType,
                                         String relLabel,
                                         String sourceFqn) {
        String value = extractJsonStringValue(json, key);
        if (value == null) return 0;
        return createEdgeToTarget(value, sourceNodeId, fqnToNodeId, edgeType, relLabel, sourceFqn);
    }

    /**
     * Extract a JSON array of strings for {@code key} and create edges for each
     * element that resolves to a known FQN.
     */
    private int createEdgesForArrayKey(String json, String key,
                                       String sourceNodeId,
                                       Map<String, String> fqnToNodeId,
                                       EdgeType edgeType,
                                       String relLabel,
                                       String sourceFqn) {
        List<String> values = extractJsonStringArray(json, key);
        if (values.isEmpty()) return 0;
        int added = 0;
        for (String value : values) {
            added += createEdgeToTarget(value, sourceNodeId, fqnToNodeId, edgeType, relLabel, sourceFqn);
        }
        return added;
    }

    /**
     * Resolve {@code targetFqn} in the project's node map and create an edge
     * if one does not yet exist.
     */
    private int createEdgeToTarget(String targetFqn,
                                   String sourceNodeId,
                                   Map<String, String> fqnToNodeId,
                                   EdgeType edgeType,
                                   String relLabel,
                                   String sourceFqn) {
        String targetNodeId = fqnToNodeId.get(targetFqn);
        if (targetNodeId == null) return 0;

        if (knowledgeGraphService.edgeExists(sourceNodeId, targetNodeId)) return 0;

        try {
            knowledgeGraphService.createEdge(
                    sourceNodeId, targetNodeId,
                    edgeType, 1.0,
                    relLabel + ": " + sourceFqn + " -> " + targetFqn);
            return 1;
        } catch (Exception e) {
            log.warn("Failed to create {} edge {} -> {}: {}", relLabel, sourceFqn, targetFqn, e.getMessage());
            return 0;
        }
    }

    // ── Export ────────────────────────────────────────────────────────────────

    /**
     * Export the code graph in the specified format.
     *
     * @param projectId project identifier
     * @param format    one of: svg, html, json
     * @param maxNodes  maximum nodes to include
     * @return map with keys: data (byte[]), contentType, filename
     */
    @SuppressWarnings("unchecked")
    public Map<String, Object> exportGraph(String projectId, String format, int maxNodes) {
        Map<String, Object> viz = getGraphVisualization(projectId, maxNodes);

        List<Map<String, Object>> nodeList = (List<Map<String, Object>>) viz.getOrDefault("nodes", List.of());
        List<Map<String, Object>> edgeList = (List<Map<String, Object>>) viz.getOrDefault("edges", List.of());

        List<PortableNode> portableNodes = new ArrayList<>();
        for (Map<String, Object> n : nodeList) {
            String id = String.valueOf(n.get("id"));
            String label = n.get("label") != null ? String.valueOf(n.get("label")) : id;
            String type = n.get("type") != null ? String.valueOf(n.get("type")).toUpperCase() : "ENTITY";
            String desc = n.get("description") != null ? String.valueOf(n.get("description")) : null;
            Map<String, Object> meta = new LinkedHashMap<>();
            if (n.get("childCount") != null) meta.put("childCount", n.get("childCount"));
            if (n.get("edgeCount") != null) meta.put("edgeCount", n.get("edgeCount"));
            if (n.get("sourceType") != null) meta.put("sourceType", n.get("sourceType"));
            portableNodes.add(new PortableNode(id, label, desc, type, meta.isEmpty() ? null : meta));
        }

        List<PortableEdge> portableEdges = new ArrayList<>();
        for (Map<String, Object> e : edgeList) {
            String from = String.valueOf(e.get("source"));
            String to = String.valueOf(e.get("target"));
            String edgeType = e.get("type") != null ? String.valueOf(e.get("type")).toUpperCase() : null;
            Double weight = e.get("weight") instanceof Number ? ((Number) e.get("weight")).doubleValue() : 1.0;
            String desc = e.get("description") != null ? String.valueOf(e.get("description")) : null;
            portableEdges.add(new PortableEdge(from, to, edgeType, weight, desc, null, null));
        }

        PortableGraph graph = new PortableGraph(portableNodes, portableEdges);

        return switch (format.toLowerCase()) {
            case "svg" -> Map.of(
                    "data", new SvgGraphExporter().toBytes(graph),
                    "contentType", "image/svg+xml",
                    "filename", "code-graph-" + projectId + ".svg");
            case "html" -> Map.of(
                    "data", new HtmlGraphExporter().toBytes(graph),
                    "contentType", "text/html",
                    "filename", "code-graph-" + projectId + ".html");
            case "json" -> {
                try {
                    byte[] jsonBytes = new ObjectMapper().writerWithDefaultPrettyPrinter()
                            .writeValueAsBytes(graph);
                    yield Map.of(
                            "data", jsonBytes,
                            "contentType", "application/json",
                            "filename", "code-graph-" + projectId + ".json");
                } catch (Exception ex) {
                    throw new RuntimeException("Failed to serialize code graph to JSON", ex);
                }
            }
            default -> throw new IllegalArgumentException(
                    "Unsupported export format: " + format + ". Supported: svg, html, json");
        };
    }

    /**
     * Minimal JSON string-value extractor for a flat key (no full parse required).
     * Returns {@code null} if the key is absent or the value is not a quoted string.
     */
    private String extractJsonStringValue(String json, String key) {
        String search = "\"" + key + "\"";
        int keyIdx = json.indexOf(search);
        if (keyIdx < 0) return null;
        int colonIdx = json.indexOf(':', keyIdx + search.length());
        if (colonIdx < 0) return null;
        int quoteStart = json.indexOf('"', colonIdx + 1);
        if (quoteStart < 0) return null;
        int quoteEnd = json.indexOf('"', quoteStart + 1);
        if (quoteEnd < 0) return null;
        String value = json.substring(quoteStart + 1, quoteEnd).trim();
        return value.isEmpty() ? null : value;
    }

    /**
     * Minimal JSON array extractor for a key whose value is a JSON array of strings.
     * Returns an empty list if the key is absent or parsing fails.
     */
    private List<String> extractJsonStringArray(String json, String key) {
        String search = "\"" + key + "\"";
        int keyIdx = json.indexOf(search);
        if (keyIdx < 0) return List.of();
        int colonIdx = json.indexOf(':', keyIdx + search.length());
        if (colonIdx < 0) return List.of();
        int bracketStart = json.indexOf('[', colonIdx + 1);
        if (bracketStart < 0) return List.of();
        int bracketEnd = json.indexOf(']', bracketStart + 1);
        if (bracketEnd < 0) return List.of();

        String inner = json.substring(bracketStart + 1, bracketEnd).trim();
        if (inner.isEmpty()) return List.of();

        List<String> results = new ArrayList<>();
        int pos = 0;
        while (pos < inner.length()) {
            int qs = inner.indexOf('"', pos);
            if (qs < 0) break;
            int qe = inner.indexOf('"', qs + 1);
            if (qe < 0) break;
            String val = inner.substring(qs + 1, qe).trim();
            if (!val.isEmpty()) results.add(val);
            pos = qe + 1;
        }
        return results;
    }
}
