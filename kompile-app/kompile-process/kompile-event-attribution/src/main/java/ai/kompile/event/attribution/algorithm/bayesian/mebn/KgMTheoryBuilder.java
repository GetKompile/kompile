/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 */
package ai.kompile.event.attribution.algorithm.bayesian.mebn;

import ai.kompile.event.attribution.algorithm.CausalTraversal;
import ai.kompile.event.attribution.algorithm.bayesian.mebn.logic.Constraints;
import ai.kompile.knowledgegraph.domain.EdgeType;
import ai.kompile.knowledgegraph.domain.GraphEdge;
import ai.kompile.knowledgegraph.domain.GraphNode;
import ai.kompile.knowledgegraph.domain.NodeLevel;
import ai.kompile.knowledgegraph.service.KnowledgeGraphService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Automatically constructs an {@link MTheory} from a live knowledge graph subgraph.
 *
 * <p>This is the bridge between "here is a KG with entities" and "here is an MTheory
 * that describes their probabilistic structure." The builder:</p>
 * <ol>
 *   <li><b>Discovers nodes</b> via BFS from seed node IDs, respecting maxDepth/maxNodes</li>
 *   <li><b>Groups nodes by {@link NodeLevel}</b> into {@link EntityType} instances,
 *       with a subtype hierarchy (ENTITY/TABLE/ATTACHMENT are subtypes of a base
 *       "GraphEntity" type)</li>
 *   <li><b>Analyzes edge patterns</b> to define MFrag templates:
 *     <ul>
 *       <li><b>EntityRelevance MFrag</b>: per-entity unary RV "isRelevant(entity)" —
 *           how likely is this entity to be active/important?</li>
 *       <li><b>CausalInfluence MFrag</b>: binary RV "influences(source, target)" —
 *           does source causally influence target? Context: edge exists between them</li>
 *       <li><b>InformationFlow MFrag</b>: binary RV "informedBy(entity, document)" —
 *           does this document inform this entity's state? Context: CONTAINS/EXTRACTED_FROM edge</li>
 *       <li><b>RiskPropagation MFrag</b>: unary RV "isRisky(entity)" with parent
 *           "isRelevant(entity)" — chains relevance into risk assessment</li>
 *     </ul>
 *   </li>
 *   <li><b>Derives edge strengths</b> from KG edge weights, confidence, and provenance</li>
 * </ol>
 *
 * <p>The resulting MTheory can be grounded into an SSBN via {@link SSBNGenerator}
 * and queried with variable elimination for entity-specific posteriors.</p>
 */
public class KgMTheoryBuilder {

    private static final Logger log = LoggerFactory.getLogger(KgMTheoryBuilder.class);

    private final KnowledgeGraphService graphService;

    private int maxDepth = 3;
    private int maxNodes = 100;
    private double minEdgeWeight = 0.05;
    private boolean includeRiskMFrag = true;

    public KgMTheoryBuilder(KnowledgeGraphService graphService) {
        this.graphService = graphService;
    }

    public KgMTheoryBuilder maxDepth(int maxDepth) {
        this.maxDepth = maxDepth;
        return this;
    }

    public KgMTheoryBuilder maxNodes(int maxNodes) {
        this.maxNodes = maxNodes;
        return this;
    }

    public KgMTheoryBuilder minEdgeWeight(double minEdgeWeight) {
        this.minEdgeWeight = minEdgeWeight;
        return this;
    }

    public KgMTheoryBuilder includeRiskMFrag(boolean includeRiskMFrag) {
        this.includeRiskMFrag = includeRiskMFrag;
        return this;
    }

    /**
     * Build an MTheory from the KG subgraph reachable from the given seed nodes.
     *
     * @param seedNodeIds KG node IDs to start BFS from
     * @return the constructed MTheory, ready for SSBN generation
     */
    public MTheory build(Collection<String> seedNodeIds) {
        log.info("Building MTheory from {} seed nodes, maxDepth={}, maxNodes={}",
                seedNodeIds.size(), maxDepth, maxNodes);

        // 1. Discover subgraph via BFS
        Map<String, GraphNode> discoveredNodes = new LinkedHashMap<>();
        List<DiscoveredEdge> discoveredEdges = new ArrayList<>();
        discoverSubgraph(seedNodeIds, discoveredNodes, discoveredEdges);

        if (discoveredNodes.isEmpty()) {
            log.warn("No nodes discovered from seeds: {}", seedNodeIds);
            return new MTheory("empty");
        }

        log.info("Discovered {} nodes, {} edges for MTheory construction",
                discoveredNodes.size(), discoveredEdges.size());

        // 2. Group nodes by NodeLevel into EntityTypes
        Map<NodeLevel, EntityType> entityTypes = buildEntityTypes(discoveredNodes);

        // 3. Build the MTheory
        MTheory mTheory = new MTheory("kg_auto_" + System.currentTimeMillis());

        // Register all entity types
        entityTypes.values().forEach(mTheory::addEntityType);

        // Also create a union type covering all nodes for cross-type MFrags
        EntityType allNodesType = new EntityType("AllNodes", "Union of all graph nodes");
        discoveredNodes.keySet().forEach(allNodesType::addEntity);
        mTheory.addEntityType(allNodesType);

        // 4. Build MFrags
        buildEntityRelevanceMFrag(mTheory, allNodesType, discoveredNodes);
        buildCausalInfluenceMFrag(mTheory, allNodesType, discoveredEdges);
        buildInformationFlowMFrag(mTheory, entityTypes, discoveredEdges);

        if (includeRiskMFrag) {
            buildRiskPropagationMFrag(mTheory, allNodesType);
        }

        List<String> validationErrors = mTheory.validate();
        if (!validationErrors.isEmpty()) {
            log.warn("MTheory validation warnings: {}", validationErrors);
        }

        log.info("MTheory built: {}", mTheory.getStatistics());
        return mTheory;
    }

    /**
     * Build an MTheory from a single target node and its neighborhood.
     */
    public MTheory buildFromTarget(String targetNodeId) {
        return build(List.of(targetNodeId));
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // SUBGRAPH DISCOVERY
    // ═══════════════════════════════════════════════════════════════════════════

    private void discoverSubgraph(Collection<String> seedNodeIds,
                                   Map<String, GraphNode> discoveredNodes,
                                   List<DiscoveredEdge> discoveredEdges) {
        Set<String> visited = new HashSet<>();
        Set<String> edgeSeen = new HashSet<>();
        Queue<NodeAtDepth> queue = new ArrayDeque<>();

        for (String seedId : seedNodeIds) {
            Optional<GraphNode> seedOpt = graphService.getNode(seedId);
            if (seedOpt.isPresent()) {
                discoveredNodes.put(seedId, seedOpt.get());
                queue.add(new NodeAtDepth(seedId, 0));
                visited.add(seedId);
            }
        }

        while (!queue.isEmpty() && discoveredNodes.size() < maxNodes) {
            NodeAtDepth current = queue.poll();
            if (current.depth >= maxDepth) continue;

            List<GraphEdge> edges = graphService.getEdgesForNode(current.nodeId);
            for (GraphEdge edge : edges) {
                double weight = edge.getWeight() != null ? edge.getWeight() : 0.5;
                if (weight < minEdgeWeight) continue;

                String sourceId = edge.getSourceNode() != null ? edge.getSourceNode().getNodeId() : null;
                String targetId = edge.getTargetNode() != null ? edge.getTargetNode().getNodeId() : null;
                if (sourceId == null || targetId == null) continue;

                // Discover neighbor
                String neighborId = sourceId.equals(current.nodeId) ? targetId : sourceId;
                if (!discoveredNodes.containsKey(neighborId)) {
                    Optional<GraphNode> neighborOpt = graphService.getNode(neighborId);
                    if (neighborOpt.isEmpty()) continue;
                    discoveredNodes.put(neighborId, neighborOpt.get());
                }

                if (!visited.contains(neighborId) && discoveredNodes.size() < maxNodes) {
                    visited.add(neighborId);
                    queue.add(new NodeAtDepth(neighborId, current.depth + 1));
                }

                // Record edge (deduplicate)
                String edgeKey = sourceId + ":" + targetId + ":" + edge.getEdgeType();
                if (edgeSeen.contains(edgeKey)) continue;
                edgeSeen.add(edgeKey);

                discoveredEdges.add(new DiscoveredEdge(
                        sourceId, targetId, edge.getEdgeType(),
                        weight, edge.getConfidence(), edge.getProvenance(),
                        edge.getEdgeId()));
            }
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // ENTITY TYPE CONSTRUCTION
    // ═══════════════════════════════════════════════════════════════════════════

    private Map<NodeLevel, EntityType> buildEntityTypes(Map<String, GraphNode> nodes) {
        Map<NodeLevel, EntityType> types = new EnumMap<>(NodeLevel.class);

        for (Map.Entry<String, GraphNode> entry : nodes.entrySet()) {
            NodeLevel level = entry.getValue().getNodeType();
            if (level == null) continue;

            types.computeIfAbsent(level, lvl -> new EntityType(
                    lvl.name(), "KG nodes at level " + lvl.name()
            )).addEntity(entry.getKey());
        }

        // Set up subtype hierarchy: ENTITY, TABLE, ATTACHMENT are subtypes of a
        // conceptual "ExtractedItem" — they're all derived from documents
        EntityType entity = types.get(NodeLevel.ENTITY);
        EntityType table = types.get(NodeLevel.TABLE);
        EntityType attachment = types.get(NodeLevel.ATTACHMENT);
        EntityType document = types.get(NodeLevel.DOCUMENT);

        if (document != null) {
            if (entity != null) entity.setSuperType(document);
            if (table != null) table.setSuperType(document);
            if (attachment != null) attachment.setSuperType(document);
        }

        EntityType snippet = types.get(NodeLevel.SNIPPET);
        if (snippet != null && document != null) {
            snippet.setSuperType(document);
        }

        return types;
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // MFRAG CONSTRUCTION
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * EntityRelevance MFrag: models P(isRelevant(node) = TRUE) based on the
     * node's graph properties (confidence, edge count, provenance quality).
     *
     * <p>This is the foundational MFrag — every node gets a relevance score
     * that flows into downstream MFrags.</p>
     */
    private void buildEntityRelevanceMFrag(MTheory mTheory, EntityType allNodesType,
                                            Map<String, GraphNode> discoveredNodes) {
        MFrag relevanceFrag = new MFrag("EntityRelevance");

        RandomVariable isRelevant = RandomVariable.unary(
                "isRelevant", allNodesType, RandomVariable.NodeRole.RESIDENT);
        relevanceFrag.addResidentNode(isRelevant);

        // Context: entity must exist in the KG
        relevanceFrag.addContextConstraint(Constraints.entityExists("AllNodes_0"));

        // Local distribution: prior based on node confidence and connectivity
        relevanceFrag.setLocalDistribution((rvName, parentStrengths) -> {
            // Extract entity ID from grounded name: "isRelevant(nodeId)"
            String entityId = extractEntityId(rvName);
            GraphNode node = entityId != null ? discoveredNodes.get(entityId) : null;

            double prior;
            if (node != null) {
                double confidence = node.getConfidence() != null ? node.getConfidence() : 0.5;
                int edgeCount = node.getEdgeCount() != null ? node.getEdgeCount() : 0;
                // Higher confidence and more connections → higher relevance prior
                double connectivityBonus = Math.min(0.2, edgeCount * 0.02);
                prior = Math.min(0.95, confidence * 0.7 + connectivityBonus + 0.1);
            } else {
                prior = 0.5;
            }

            // Binary CPT: [P(FALSE), P(TRUE)]
            return new double[]{1.0 - prior, prior};
        });

        mTheory.addMFrag(relevanceFrag);
    }

    /**
     * CausalInfluence MFrag: models P(influences(source, target) = TRUE)
     * conditioned on whether both entities are relevant and an edge exists
     * between them.
     *
     * <p>Context constraints ensure we only ground for pairs that actually
     * have edges in the KG. Edge weight and provenance drive the CPT strength.</p>
     */
    private void buildCausalInfluenceMFrag(MTheory mTheory, EntityType allNodesType,
                                            List<DiscoveredEdge> edges) {
        if (edges.isEmpty()) return;

        // Collect causal edge types (not purely structural)
        Set<EdgeType> causalTypes = EnumSet.of(
                EdgeType.SHARED_ENTITY, EdgeType.CITATION, EdgeType.TEMPORAL,
                EdgeType.CROSS_SOURCE, EdgeType.USER_DEFINED);

        boolean hasCausalEdges = edges.stream()
                .anyMatch(e -> causalTypes.contains(e.edgeType));
        if (!hasCausalEdges) return;

        MFrag causalFrag = new MFrag("CausalInfluence");

        // Resident: influences(source, target) — binary RV over pairs
        RandomVariable influences = RandomVariable.binary(
                "influences", allNodesType, allNodesType, RandomVariable.NodeRole.RESIDENT);
        causalFrag.addResidentNode(influences);

        // Input: isRelevant from the EntityRelevance MFrag
        RandomVariable relevantInput = RandomVariable.unary(
                "isRelevant", allNodesType, RandomVariable.NodeRole.INPUT);
        causalFrag.addInputNode(relevantInput);

        // Parent edge: isRelevant → influences (relevant entities influence more strongly)
        causalFrag.addParentEdge("isRelevant", "influences", 0.7);

        // Context: source and target must be different, and an edge must exist
        causalFrag.addContextConstraint(Constraints.notEqual("AllNodes_0", "AllNodes_1"));
        causalFrag.addContextConstraint(Constraints.edgeExists("AllNodes_0", "AllNodes_1"));

        // Local distribution: edge weight drives causal strength
        Map<String, DiscoveredEdge> edgeIndex = new HashMap<>();
        for (DiscoveredEdge de : edges) {
            edgeIndex.put(de.sourceId + ":" + de.targetId, de);
        }

        causalFrag.setLocalDistribution((rvName, parentStrengths) -> {
            // Parse grounded name: "influences(nodeA,nodeB)"
            String[] entities = extractBinaryEntityIds(rvName);
            DiscoveredEdge edge = entities != null ?
                    edgeIndex.get(entities[0] + ":" + entities[1]) : null;

            double parentRelevance = parentStrengths.length > 0 ? parentStrengths[0] : 0.5;

            double edgeStrength;
            if (edge != null) {
                double weight = edge.weight;
                double confidence = edge.confidence != null ? edge.confidence : 0.5;
                double provenanceMult = getProvenanceMultiplier(edge.provenance);
                edgeStrength = weight * confidence * provenanceMult;
            } else {
                edgeStrength = 0.1; // Weak default for unknown edges
            }

            // Noisy-OR: P(influences=TRUE | isRelevant) combines parent relevance with edge strength
            double pFalseGivenParent = (1.0 - edgeStrength * parentRelevance);
            double pTrue = 1.0 - pFalseGivenParent;
            return new double[]{1.0 - pTrue, pTrue};
        });

        mTheory.addMFrag(causalFrag);
    }

    /**
     * InformationFlow MFrag: models the flow of information from documents
     * to entities via CONTAINS, EXTRACTED_FROM, or AUTHORED_BY edges.
     *
     * <p>Only created when both DOCUMENT/SOURCE and ENTITY/TABLE types exist
     * in the discovered subgraph.</p>
     */
    private void buildInformationFlowMFrag(MTheory mTheory,
                                            Map<NodeLevel, EntityType> entityTypes,
                                            List<DiscoveredEdge> edges) {
        // Need both document-level and entity-level nodes
        EntityType docType = entityTypes.get(NodeLevel.DOCUMENT);
        if (docType == null) docType = entityTypes.get(NodeLevel.SOURCE);
        EntityType entityType = entityTypes.get(NodeLevel.ENTITY);
        if (entityType == null) entityType = entityTypes.get(NodeLevel.TABLE);

        if (docType == null || entityType == null) return;
        if (docType.getEntityIds().isEmpty() || entityType.getEntityIds().isEmpty()) return;

        // Check if there are information flow edges
        Set<EdgeType> infoFlowTypes = EnumSet.of(
                EdgeType.CONTAINS, EdgeType.EXTRACTED_FROM,
                EdgeType.AUTHORED_BY, EdgeType.HIERARCHICAL);

        boolean hasInfoFlowEdges = edges.stream()
                .anyMatch(e -> infoFlowTypes.contains(e.edgeType));
        if (!hasInfoFlowEdges) return;

        MFrag infoFlowFrag = new MFrag("InformationFlow");

        // Resident: informedBy(entity, document)
        RandomVariable informedBy = RandomVariable.binary(
                "informedBy", entityType, docType, RandomVariable.NodeRole.RESIDENT);
        infoFlowFrag.addResidentNode(informedBy);

        // Input: isRelevant for the document
        RandomVariable docRelevant = RandomVariable.unary(
                "isRelevant", docType, RandomVariable.NodeRole.INPUT);
        infoFlowFrag.addInputNode(docRelevant);

        // Parent: document relevance flows into information flow
        infoFlowFrag.addParentEdge("isRelevant", "informedBy", 0.8);

        // Context: there must be an edge between the entity and the document
        // (in either direction — EXTRACTED_FROM goes entity→doc, CONTAINS goes doc→entity)
        infoFlowFrag.addContextConstraint(Constraints.or(
                Constraints.edgeExists(entityType.getTypeName() + "_0", docType.getTypeName() + "_1"),
                Constraints.edgeExists(docType.getTypeName() + "_1", entityType.getTypeName() + "_0")
        ));

        mTheory.addMFrag(infoFlowFrag);
    }

    /**
     * RiskPropagation MFrag: models P(isRisky(node) = TRUE) as a function
     * of the node's relevance and the influence of other risky nodes.
     *
     * <p>This creates the interpretability chain: relevance → influence → risk.
     * The SSBN grounds this per-entity, giving posteriors like
     * P(isRisky(invoice_42) | observed evidence).</p>
     */
    private void buildRiskPropagationMFrag(MTheory mTheory, EntityType allNodesType) {
        MFrag riskFrag = new MFrag("RiskPropagation");

        // Resident: isRisky(entity)
        RandomVariable isRisky = RandomVariable.unary(
                "isRisky", allNodesType, RandomVariable.NodeRole.RESIDENT);
        riskFrag.addResidentNode(isRisky);

        // Input: isRelevant from EntityRelevance MFrag
        RandomVariable relevantInput = RandomVariable.unary(
                "isRelevant", allNodesType, RandomVariable.NodeRole.INPUT);
        riskFrag.addInputNode(relevantInput);

        // Parent: relevance drives risk (relevant entities can be risky)
        riskFrag.addParentEdge("isRelevant", "isRisky", 0.6);

        // Context: entity must exist
        riskFrag.addContextConstraint(Constraints.entityExists("AllNodes_0"));

        mTheory.addMFrag(riskFrag);
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // HELPERS
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * Extract entity ID from a grounded variable name like "isRelevant(node-123)".
     */
    static String extractEntityId(String groundedName) {
        int open = groundedName.indexOf('(');
        int close = groundedName.lastIndexOf(')');
        if (open < 0 || close <= open) return null;
        return groundedName.substring(open + 1, close);
    }

    /**
     * Extract two entity IDs from a grounded binary variable name like "influences(nodeA,nodeB)".
     */
    static String[] extractBinaryEntityIds(String groundedName) {
        int open = groundedName.indexOf('(');
        int close = groundedName.lastIndexOf(')');
        if (open < 0 || close <= open) return null;
        String args = groundedName.substring(open + 1, close);
        String[] parts = args.split(",", 2);
        return parts.length == 2 ? parts : null;
    }

    /**
     * Discount factor for edge provenance, matching BayesianNetworkBuilder.
     */
    private static double getProvenanceMultiplier(String provenance) {
        if (provenance == null) return 0.8;
        return switch (provenance.toUpperCase()) {
            case "EXTRACTED" -> 1.0;
            case "INFERRED" -> 0.7;
            case "AMBIGUOUS" -> 0.4;
            default -> 0.8;
        };
    }

    private record NodeAtDepth(String nodeId, int depth) {}

    /**
     * An edge discovered during subgraph BFS.
     */
    record DiscoveredEdge(String sourceId, String targetId, EdgeType edgeType,
                          double weight, Double confidence, String provenance,
                          String edgeId) {}
}
