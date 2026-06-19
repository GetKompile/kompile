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
package ai.kompile.knowledgegraph.impl;

import ai.kompile.core.embeddings.EmbeddingModel;
import ai.kompile.core.graphrag.GraphRagService;
import ai.kompile.core.graphrag.model.Community;
import ai.kompile.core.graphrag.model.Entity;
import ai.kompile.core.graphrag.model.Relationship;
import ai.kompile.core.graphrag.query.GraphRagQuery;
import ai.kompile.core.graphrag.query.GraphRagResult;
import ai.kompile.core.graphrag.query.SearchType;
import ai.kompile.core.llm.chat.LLMChat;
import ai.kompile.knowledgegraph.domain.EdgeType;
import ai.kompile.knowledgegraph.domain.GraphEdge;
import ai.kompile.knowledgegraph.domain.GraphNode;
import ai.kompile.knowledgegraph.domain.NodeLevel;
import ai.kompile.knowledgegraph.repository.GraphEdgeRepository;
import ai.kompile.knowledgegraph.repository.GraphNodeRepository;
import ai.kompile.knowledgegraph.resolution.SessionEntityState;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * JPA-backed implementation of GraphRagService supporting LOCAL, GLOBAL, and HYBRID search.
 * <p>
 * <b>LOCAL</b>: Finds seed entities via text search, then hops outward along
 * relationships to collect context from the traversal neighborhood.
 * <p>
 * <b>GLOBAL</b>: Scans all entity nodes, builds connected component communities,
 * and ranks by edge count for importance.
 * <p>
 * <b>HYBRID</b>: Combines text/embedding search with multi-hop graph traversal.
 * Seed entities are found via search, then the graph is traversed outward for
 * configurable depth. Results are scored using a weighted combination of
 * search relevance and graph proximity (1/hop_distance).
 */
@Service
@ConditionalOnMissingBean(GraphRagService.class)
@Slf4j
public class JpaGraphRagService implements GraphRagService {

    private final GraphNodeRepository nodeRepository;
    private final GraphEdgeRepository edgeRepository;
    private final EmbeddingModel embeddingModel;
    private final LLMChat llmChat;

    private final Map<String, SessionEntityState> sessionEntities = new ConcurrentHashMap<>();
    private int turnCounter = 0;

    private static final Pattern ENTITY_MENTION_PATTERN = Pattern.compile(
            "\\b(that|the|this|those)\\s+(company|person|organization|place|product|event|ceo|founder|manager)\\b",
            Pattern.CASE_INSENSITIVE
    );

    @Autowired
    public JpaGraphRagService(
            GraphNodeRepository nodeRepository,
            GraphEdgeRepository edgeRepository,
            @Autowired(required = false) EmbeddingModel embeddingModel,
            @Autowired(required = false) LLMChat llmChat) {
        this.nodeRepository = nodeRepository;
        this.edgeRepository = edgeRepository;
        this.embeddingModel = embeddingModel;
        this.llmChat = llmChat;

        log.info("JpaGraphRagService initialized - EmbeddingModel: {}, LLMChat: {}",
                embeddingModel != null ? embeddingModel.getClass().getSimpleName() : "N/A (text search only)",
                llmChat != null ? llmChat.getClass().getSimpleName() : "N/A (context only, no synthesis)");
    }

    @Override
    @Transactional(readOnly = true)
    public GraphRagResult answerQuery(GraphRagQuery query) {
        log.debug("JPA GraphRAG query: type={}, query={}", query.getSearchType(), query.getQuery());
        turnCounter++;

        String conversationId = query.getConversationId() != null ? query.getConversationId() : "default";
        SessionEntityState entityState = sessionEntities.computeIfAbsent(
                conversationId, k -> new SessionEntityState());

        String resolvedQuery = resolveEntityReferences(query.getQuery(), entityState);
        SearchType searchType = query.getSearchType() != null ? query.getSearchType() : SearchType.HYBRID;

        return switch (searchType) {
            case LOCAL -> executeLocalSearch(resolvedQuery, query, entityState);
            case GLOBAL -> executeGlobalSearch(resolvedQuery, query);
            case HYBRID -> executeHybridSearch(resolvedQuery, query, entityState);
        };
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // LOCAL SEARCH — seed entities + configurable hop depth
    // ═══════════════════════════════════════════════════════════════════════════

    private GraphRagResult executeLocalSearch(String query, GraphRagQuery ragQuery,
                                              SessionEntityState entityState) {
        int k = ragQuery.getK() > 0 ? ragQuery.getK() : 5;
        int hopDepth = ragQuery.getHopDepth();
        int maxTraversal = ragQuery.getMaxTraversalNodes();

        List<ScoredNode> seedNodes = findSeedNodes(query, k, ragQuery.getFactSheetId());
        if (seedNodes.isEmpty()) {
            return emptyResult(SearchType.LOCAL);
        }

        TraversalResult traversal = traverseFromSeeds(seedNodes, hopDepth, maxTraversal);

        for (ScoredNode node : seedNodes) {
            entityState.trackEntity(
                    node.node.getNodeId(), node.node.getTitle(),
                    node.node.getNodeType() != null ? node.node.getNodeType().name() : "CONCEPT",
                    List.of(), turnCounter, node.node.getNodeId());
        }

        String context = formatTraversalContext(traversal);
        String answer = synthesizeAnswer(query, context);
        return buildResult(answer, context, traversal, SearchType.LOCAL);
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // GLOBAL SEARCH — community detection via connected components
    // ═══════════════════════════════════════════════════════════════════════════

    private GraphRagResult executeGlobalSearch(String query, GraphRagQuery ragQuery) {
        int k = ragQuery.getK() > 0 ? ragQuery.getK() : 10;

        // Get top entities by edge count (proxy for importance)
        List<GraphNode> allEntities = nodeRepository.findByNodeType(NodeLevel.ENTITY, PageRequest.of(0, 200))
                .getContent();
        if (allEntities.isEmpty()) {
            allEntities = nodeRepository.findAll(PageRequest.of(0, 200)).getContent();
        }

        // Sort by edge count descending
        allEntities.sort((a, b) -> Integer.compare(b.getEdgeCount(), a.getEdgeCount()));
        List<GraphNode> topNodes = allEntities.subList(0, Math.min(k, allEntities.size()));

        // Build connected components for community detection
        Set<String> allNodeIds = allEntities.stream()
                .map(GraphNode::getNodeId)
                .collect(Collectors.toSet());
        List<Set<String>> components = findConnectedComponents(allNodeIds);

        List<Community> communities = new ArrayList<>();
        for (int i = 0; i < components.size(); i++) {
            Set<String> component = components.get(i);
            if (component.size() < 2) continue;

            Community community = new Community();
            community.setId("community_" + i);
            community.setEntities(new ArrayList<>(component));

            List<String> memberTitles = component.stream()
                    .limit(10)
                    .map(nid -> nodeRepository.findByNodeId(nid)
                            .map(GraphNode::getTitle).orElse(null))
                    .filter(Objects::nonNull)
                    .collect(Collectors.toList());
            community.setSummary("Community of: " + String.join(", ", memberTitles));
            communities.add(community);
        }

        List<Entity> entities = topNodes.stream()
                .map(this::toEntity)
                .collect(Collectors.toList());

        // Collect relationships between top nodes
        Set<String> topNodeIds = topNodes.stream().map(GraphNode::getNodeId).collect(Collectors.toSet());
        List<Relationship> relationships = collectRelationships(topNodeIds);

        StringBuilder contextBuilder = new StringBuilder();
        contextBuilder.append("Knowledge Graph Overview:\n");
        contextBuilder.append(String.format("- Total entities: %d\n", nodeRepository.count()));
        contextBuilder.append(String.format("- Total relationships: %d\n", edgeRepository.count()));
        contextBuilder.append(String.format("- Communities: %d\n\n", communities.size()));

        contextBuilder.append("Key Entities (by connectivity):\n");
        for (GraphNode node : topNodes) {
            contextBuilder.append(formatNodeContext(node));
            contextBuilder.append("\n");
        }

        if (ragQuery.isIncludeCommunities() && !communities.isEmpty()) {
            contextBuilder.append("\nCommunity Summaries:\n");
            for (Community c : communities.subList(0, Math.min(5, communities.size()))) {
                contextBuilder.append(String.format("- %s: %s\n", c.getId(), c.getSummary()));
            }
        }

        contextBuilder.append("\nKey Relationships:\n");
        for (Relationship rel : relationships.subList(0, Math.min(20, relationships.size()))) {
            contextBuilder.append(String.format("- %s -[%s]-> %s\n",
                    rel.getSource(), rel.getType() != null ? rel.getType() : "RELATED", rel.getTarget()));
        }

        String context = contextBuilder.toString();
        String answer = synthesizeAnswer(query, context);

        return GraphRagResult.builder()
                .answer(answer)
                .formattedContext(context)
                .entities(entities)
                .relationships(relationships)
                .communities(communities)
                .searchType(SearchType.GLOBAL)
                .nodesVisited(topNodes.size())
                .build();
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // HYBRID SEARCH — text/vector similarity + multi-hop graph traversal
    // ═══════════════════════════════════════════════════════════════════════════

    private GraphRagResult executeHybridSearch(String query, GraphRagQuery ragQuery,
                                               SessionEntityState entityState) {
        int k = ragQuery.getK() > 0 ? ragQuery.getK() : 5;
        int hopDepth = ragQuery.getHopDepth();
        int maxTraversal = ragQuery.getMaxTraversalNodes();
        double vectorWeight = ragQuery.getVectorWeight();
        double graphWeight = 1.0 - vectorWeight;

        // Step 1: Find seed entities via text search
        List<ScoredNode> seeds = findSeedNodes(query, k, ragQuery.getFactSheetId());
        if (seeds.isEmpty()) {
            return emptyResult(SearchType.HYBRID);
        }

        // Step 2: Multi-hop traversal from seeds
        TraversalResult traversal = traverseFromSeeds(seeds, hopDepth, maxTraversal);

        // Step 3: Score merging
        Map<String, Map<String, Double>> scoreBreakdown = new LinkedHashMap<>();
        Map<String, Double> combinedScores = new LinkedHashMap<>();

        for (Map.Entry<String, ScoredNode> entry : traversal.allNodes.entrySet()) {
            String nodeId = entry.getKey();
            ScoredNode scored = entry.getValue();

            double vScore = scored.searchScore;
            double gScore = scored.hopDistance == 0 ? 1.0 : 1.0 / (1.0 + scored.hopDistance);
            double combined = (vectorWeight * vScore) + (graphWeight * gScore);
            combinedScores.put(nodeId, combined);

            Map<String, Double> breakdown = new LinkedHashMap<>();
            breakdown.put("vectorScore", vScore);
            breakdown.put("graphScore", gScore);
            breakdown.put("combined", combined);
            breakdown.put("hopDistance", (double) scored.hopDistance);
            scoreBreakdown.put(nodeId, breakdown);
        }

        // Step 4: Rank by combined score
        List<String> rankedNodeIds = combinedScores.entrySet().stream()
                .sorted((a, b) -> Double.compare(b.getValue(), a.getValue()))
                .map(Map.Entry::getKey)
                .collect(Collectors.toList());

        // Track entities for session
        for (ScoredNode node : seeds) {
            entityState.trackEntity(
                    node.node.getNodeId(), node.node.getTitle(),
                    node.node.getNodeType() != null ? node.node.getNodeType().name() : "CONCEPT",
                    List.of(), turnCounter, node.node.getNodeId());
        }

        // Step 5: Build rich context
        StringBuilder contextBuilder = new StringBuilder();
        contextBuilder.append("Hybrid Search Results (text similarity + graph traversal):\n\n");

        List<Entity> entities = new ArrayList<>();
        int contextNodeCount = 0;
        for (String nodeId : rankedNodeIds) {
            if (contextNodeCount >= k * 3) break;

            ScoredNode scored = traversal.allNodes.get(nodeId);
            if (scored == null) continue;
            GraphNode node = scored.node;

            Map<String, Double> scores = scoreBreakdown.get(nodeId);
            contextBuilder.append(formatNodeContext(node));
            contextBuilder.append(String.format("  [combined=%.3f, search=%.3f, graph=%.3f, hops=%d]\n",
                    scores.getOrDefault("combined", 0.0),
                    scores.getOrDefault("vectorScore", 0.0),
                    scores.getOrDefault("graphScore", 0.0),
                    scores.get("hopDistance") != null ? scores.get("hopDistance").intValue() : 0));

            // Add relationships from this node
            List<GraphEdge> nodeEdges = edgeRepository.findAllEdgesForNodeId(nodeId);
            if (!nodeEdges.isEmpty()) {
                contextBuilder.append("  Related to:\n");
                int relCount = 0;
                for (GraphEdge edge : nodeEdges) {
                    if (relCount >= 3) break;
                    GraphNode other = edge.getSourceNode().getNodeId().equals(nodeId)
                            ? edge.getTargetNode() : edge.getSourceNode();
                    if (other != null) {
                        contextBuilder.append(String.format("    - %s (weight: %.2f)\n",
                                other.getTitle(), edge.getWeight()));
                        relCount++;
                    }
                }
            }
            contextBuilder.append("\n");

            entities.add(toEntity(node));
            contextNodeCount++;
        }

        List<Relationship> relationships = collectRelationships(traversal.allNodes.keySet());

        String context = contextBuilder.toString();
        String answer = synthesizeAnswer(query, context);

        return GraphRagResult.builder()
                .answer(answer)
                .formattedContext(context)
                .entities(entities)
                .relationships(relationships)
                .communities(List.of())
                .sourceChunks(List.of())
                .searchType(SearchType.HYBRID)
                .traversalPaths(traversal.paths)
                .scoreBreakdown(scoreBreakdown)
                .hopsPerformed(hopDepth)
                .nodesVisited(traversal.allNodes.size())
                .build();
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // GRAPH TRAVERSAL ENGINE
    // ═══════════════════════════════════════════════════════════════════════════

    private List<ScoredNode> findSeedNodes(String query, int k, Long factSheetId) {
        PageRequest pageable = PageRequest.of(0, k);
        List<GraphNode> results;

        if (factSheetId != null) {
            results = nodeRepository.findByFactSheetIdAndNodeType(factSheetId, NodeLevel.ENTITY);
            String lowerQuery = query.toLowerCase();
            results = results.stream()
                    .filter(n -> (n.getTitle() != null && n.getTitle().toLowerCase().contains(lowerQuery))
                            || (n.getDescription() != null && n.getDescription().toLowerCase().contains(lowerQuery)))
                    .limit(k)
                    .collect(Collectors.toList());
        } else {
            results = nodeRepository.searchByTitleOrDescription(query, pageable).getContent();
        }

        if (results.isEmpty()) {
            return List.of();
        }

        // Assign decreasing scores based on position
        List<ScoredNode> seeds = new ArrayList<>();
        for (int i = 0; i < results.size(); i++) {
            double score = 1.0 - (i * 0.1);
            seeds.add(new ScoredNode(results.get(i), Math.max(0.1, score), 0));
        }
        return seeds;
    }

    /**
     * BFS traversal from seed nodes, hopping outward along edges up to maxHops deep.
     */
    private TraversalResult traverseFromSeeds(List<ScoredNode> seeds, int maxHops, int maxNodes) {
        Map<String, ScoredNode> allNodes = new LinkedHashMap<>();
        Map<String, List<String>> paths = new LinkedHashMap<>();

        Queue<String> frontier = new ArrayDeque<>();
        Map<String, Integer> hopDistances = new HashMap<>();

        for (ScoredNode seed : seeds) {
            String seedId = seed.node.getNodeId();
            allNodes.put(seedId, seed);
            hopDistances.put(seedId, 0);
            frontier.add(seedId);
            paths.put(seedId, new ArrayList<>(List.of(seedId)));
        }

        int currentHop = 0;
        while (currentHop < maxHops && !frontier.isEmpty() && allNodes.size() < maxNodes) {
            int levelSize = frontier.size();
            currentHop++;

            for (int i = 0; i < levelSize && allNodes.size() < maxNodes; i++) {
                String currentId = frontier.poll();
                if (currentId == null) break;

                List<GraphEdge> edges = edgeRepository.findAllEdgesForNodeId(currentId);
                for (GraphEdge edge : edges) {
                    GraphNode neighborNode = edge.getSourceNode().getNodeId().equals(currentId)
                            ? edge.getTargetNode() : edge.getSourceNode();
                    if (neighborNode == null) continue;
                    String neighborId = neighborNode.getNodeId();
                    if (allNodes.containsKey(neighborId)) continue;
                    if (allNodes.size() >= maxNodes) break;

                    // Propagate decayed score from parent
                    ScoredNode parent = allNodes.get(currentId);
                    double propagatedScore = parent.searchScore * edge.getWeight() * 0.5;

                    ScoredNode scored = new ScoredNode(neighborNode, propagatedScore, currentHop);
                    allNodes.put(neighborId, scored);
                    hopDistances.put(neighborId, currentHop);
                    frontier.add(neighborId);

                    // Track path from nearest seed
                    String seedAncestor = findSeedAncestor(currentId, paths);
                    if (seedAncestor != null) {
                        paths.computeIfAbsent(seedAncestor, kk -> new ArrayList<>()).add(neighborId);
                    }
                }
            }
        }

        return new TraversalResult(allNodes, paths, currentHop);
    }

    private String findSeedAncestor(String nodeId, Map<String, List<String>> paths) {
        for (Map.Entry<String, List<String>> entry : paths.entrySet()) {
            if (entry.getValue().contains(nodeId)) {
                return entry.getKey();
            }
        }
        if (paths.containsKey(nodeId)) {
            return nodeId;
        }
        return paths.keySet().stream().findFirst().orElse(null);
    }

    /**
     * Simple connected components via BFS over a set of node IDs.
     */
    private List<Set<String>> findConnectedComponents(Set<String> nodeIds) {
        List<Set<String>> components = new ArrayList<>();
        Set<String> remaining = new HashSet<>(nodeIds);

        while (!remaining.isEmpty()) {
            String start = remaining.iterator().next();
            Set<String> component = new HashSet<>();
            Queue<String> queue = new ArrayDeque<>();
            queue.add(start);
            component.add(start);
            remaining.remove(start);

            while (!queue.isEmpty()) {
                String current = queue.poll();
                List<GraphEdge> edges = edgeRepository.findAllEdgesForNodeId(current);
                for (GraphEdge edge : edges) {
                    String neighbor = edge.getSourceNode().getNodeId().equals(current)
                            ? edge.getTargetNode().getNodeId()
                            : edge.getSourceNode().getNodeId();
                    if (remaining.contains(neighbor)) {
                        remaining.remove(neighbor);
                        component.add(neighbor);
                        queue.add(neighbor);
                    }
                }
            }
            components.add(component);
        }

        // Sort by size descending
        components.sort((a, b) -> Integer.compare(b.size(), a.size()));
        return components;
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // CONTEXT FORMATTING
    // ═══════════════════════════════════════════════════════════════════════════

    private String formatTraversalContext(TraversalResult traversal) {
        if (traversal.allNodes.isEmpty()) return "";

        StringBuilder contextBuilder = new StringBuilder();
        contextBuilder.append("Relevant Knowledge:\n\n");

        List<ScoredNode> sorted = traversal.allNodes.values().stream()
                .sorted(Comparator.comparingInt((ScoredNode n) -> n.hopDistance)
                        .thenComparing((ScoredNode n) -> -n.searchScore))
                .collect(Collectors.toList());

        for (ScoredNode scored : sorted) {
            contextBuilder.append(formatNodeContext(scored.node));

            List<GraphEdge> edges = edgeRepository.findAllEdgesForNodeId(scored.node.getNodeId());
            if (!edges.isEmpty()) {
                contextBuilder.append("  Related to:\n");
                int relCount = 0;
                for (GraphEdge edge : edges) {
                    if (relCount >= 3) break;
                    GraphNode other = edge.getSourceNode().getNodeId().equals(scored.node.getNodeId())
                            ? edge.getTargetNode() : edge.getSourceNode();
                    if (other != null) {
                        contextBuilder.append(String.format("    - %s\n", other.getTitle()));
                        relCount++;
                    }
                }
            }
            contextBuilder.append("\n");
        }

        return contextBuilder.toString();
    }

    private String formatNodeContext(GraphNode node) {
        StringBuilder sb = new StringBuilder();
        sb.append(String.format("Entity: %s", node.getTitle()));
        if (node.getNodeType() != null) {
            sb.append(String.format(" [%s]", node.getNodeType().name()));
        }
        sb.append("\n");
        if (node.getDescription() != null && !node.getDescription().isEmpty()) {
            sb.append(String.format("  Description: %s\n", node.getDescription()));
        }
        return sb.toString();
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // RESULT BUILDING
    // ═══════════════════════════════════════════════════════════════════════════

    private GraphRagResult buildResult(String answer, String context, TraversalResult traversal,
                                       SearchType searchType) {
        List<Entity> entities = traversal.allNodes.values().stream()
                .map(sn -> toEntity(sn.node))
                .collect(Collectors.toList());

        return GraphRagResult.builder()
                .answer(answer)
                .formattedContext(context)
                .entities(entities)
                .relationships(List.of())
                .communities(List.of())
                .sourceChunks(List.of())
                .searchType(searchType)
                .traversalPaths(traversal.paths)
                .hopsPerformed(traversal.maxHopReached)
                .nodesVisited(traversal.allNodes.size())
                .build();
    }

    private GraphRagResult emptyResult(SearchType searchType) {
        return GraphRagResult.builder()
                .answer("I couldn't find relevant information in the knowledge graph to answer your question.")
                .formattedContext("")
                .entities(List.of())
                .relationships(List.of())
                .communities(List.of())
                .searchType(searchType)
                .build();
    }

    private Entity toEntity(GraphNode node) {
        Entity entity = new Entity();
        entity.setId(node.getNodeId());
        entity.setTitle(node.getTitle());
        entity.setType(node.getNodeType() != null ? node.getNodeType().name() : "UNKNOWN");
        entity.setDescription(node.getDescription());
        return entity;
    }

    private List<Relationship> collectRelationships(Set<String> nodeIds) {
        List<Relationship> relationships = new ArrayList<>();
        Set<String> seen = new HashSet<>();

        for (String nodeId : nodeIds) {
            List<GraphEdge> edges = edgeRepository.findAllEdgesForNodeId(nodeId);
            for (GraphEdge edge : edges) {
                String srcId = edge.getSourceNode().getNodeId();
                String tgtId = edge.getTargetNode().getNodeId();
                if (nodeIds.contains(srcId) && nodeIds.contains(tgtId)) {
                    String edgeKey = srcId + "->" + tgtId;
                    if (seen.add(edgeKey)) {
                        Relationship rel = new Relationship();
                        rel.setSource(edge.getSourceNode().getTitle());
                        rel.setTarget(edge.getTargetNode().getTitle());
                        rel.setType(edge.getEdgeType() != null ? edge.getEdgeType().name() : "RELATED");
                        rel.setWeight(edge.getWeight());
                        rel.setDescription(edge.getDescription());
                        relationships.add(rel);
                    }
                }
            }
        }
        return relationships;
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // ENTITY RESOLUTION & LLM
    // ═══════════════════════════════════════════════════════════════════════════

    private String resolveEntityReferences(String query, SessionEntityState entityState) {
        if (entityState.size() == 0) return query;

        Matcher matcher = ENTITY_MENTION_PATTERN.matcher(query);
        StringBuilder sb = new StringBuilder();
        boolean modified = false;

        while (matcher.find()) {
            String fullMatch = matcher.group(0);
            SessionEntityState.TrackedEntity resolved = entityState.resolveReference(fullMatch);
            if (resolved != null) {
                matcher.appendReplacement(sb, Matcher.quoteReplacement(resolved.name()));
                modified = true;
            }
        }
        matcher.appendTail(sb);

        return modified ? sb.toString() : query;
    }

    public SessionEntityState getSessionEntityState(String conversationId) {
        return sessionEntities.computeIfAbsent(conversationId, k -> new SessionEntityState());
    }

    private String synthesizeAnswer(String query, String context) {
        if (llmChat == null) {
            log.debug("No LLMChat available, returning context without synthesis");
            return "[No LLM configured - showing retrieved context]\n\n" + context;
        }

        String prompt = String.format("""
                Based on the following knowledge graph context, answer the user's question.
                If the context doesn't contain enough information, say so honestly.

                Context:
                %s

                Question: %s

                Answer:
                """, context, query);

        try {
            return llmChat.prompt().user(prompt).call().content();
        } catch (Exception e) {
            log.error("Failed to synthesize answer with LLM", e);
            return "I encountered an error while generating an answer. Please try again.";
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // INTERNAL TYPES
    // ═══════════════════════════════════════════════════════════════════════════

    static class ScoredNode {
        final GraphNode node;
        final double searchScore;
        final int hopDistance;

        ScoredNode(GraphNode node, double searchScore, int hopDistance) {
            this.node = node;
            this.searchScore = searchScore;
            this.hopDistance = hopDistance;
        }
    }

    static class TraversalResult {
        final Map<String, ScoredNode> allNodes;
        final Map<String, List<String>> paths;
        final int maxHopReached;

        TraversalResult(Map<String, ScoredNode> allNodes, Map<String, List<String>> paths, int maxHopReached) {
            this.allNodes = allNodes;
            this.paths = paths;
            this.maxHopReached = maxHopReached;
        }
    }
}
