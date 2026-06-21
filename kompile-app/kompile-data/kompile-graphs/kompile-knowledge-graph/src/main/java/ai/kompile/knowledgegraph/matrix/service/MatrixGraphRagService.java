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
package ai.kompile.knowledgegraph.matrix.service;

import ai.kompile.core.embeddings.EmbeddingModel;
import ai.kompile.core.graphrag.GraphRagService;
import ai.kompile.core.graphrag.model.Entity;
import ai.kompile.core.graphrag.model.Graph;
import ai.kompile.core.graphrag.model.Relationship;
import ai.kompile.core.graphrag.query.GraphRagQuery;
import ai.kompile.core.graphrag.query.GraphRagResult;
import ai.kompile.core.graphrag.query.SearchType;
import ai.kompile.core.llm.chat.LLMChat;
import ai.kompile.knowledgegraph.matrix.algorithms.MatrixGraphAlgorithms;
import ai.kompile.knowledgegraph.matrix.model.AdjacencyMatrixGraph;
import ai.kompile.knowledgegraph.matrix.model.MatrixGraphNode;
import ai.kompile.knowledgegraph.embedding.adapter.MatrixKgEmbeddingGraphAdapter;
import ai.kompile.knowledgegraph.matrix.store.MatrixGraphStore;
import ai.kompile.knowledgegraph.resolution.SessionEntityState;
import lombok.extern.slf4j.Slf4j;
import org.nd4j.linalg.api.ndarray.INDArray;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Matrix-based implementation of GraphRagService.
 * <p>
 * This service uses the matrix-based graph storage for efficient graph operations
 * and vector similarity search for retrieval-augmented generation.
 * </p>
 * <p>
 * This is the default Graph RAG implementation that works without external dependencies.
 * It is automatically enabled when MatrixGraphStore is available. EmbeddingModel and LLMChat
 * are optional - if not available, the service falls back to text-based search and returns
 * context without LLM synthesis.
 * </p>
 */
@Service
@Primary
@Slf4j
public class MatrixGraphRagService implements GraphRagService {

    @Autowired
    private MatrixGraphStore graphStore;
    @Autowired(required = false)
    private EmbeddingModel embeddingModel;
    @Autowired(required = false)
    private LLMChat llmChat;
    @Autowired(required = false)
    private CommunitySummaryService communitySummaryService;

    public MatrixGraphRagService() {}

    /** Test constructor. */
    public MatrixGraphRagService(MatrixGraphStore graphStore, EmbeddingModel embeddingModel, LLMChat llmChat) {
        this(graphStore, embeddingModel, llmChat, null);
    }

    /** Test constructor with community summarization for GLOBAL search. */
    public MatrixGraphRagService(MatrixGraphStore graphStore, EmbeddingModel embeddingModel, LLMChat llmChat,
                                 CommunitySummaryService communitySummaryService) {
        this.graphStore = graphStore;
        this.embeddingModel = embeddingModel;
        this.llmChat = llmChat;
        this.communitySummaryService = communitySummaryService;
    }

    // Per-conversation entity tracking for resolving ambiguous references
    private final Map<String, SessionEntityState> sessionEntities = new ConcurrentHashMap<>();
    private int turnCounter = 0;

    private static final Pattern ENTITY_MENTION_PATTERN = Pattern.compile(
            "\\b(that|the|this|those)\\s+(company|person|organization|place|product|event|ceo|founder|manager)\\b",
            Pattern.CASE_INSENSITIVE
    );

    /**
     * Default graph ID for RAG queries.
     */
    private static final String DEFAULT_GRAPH_ID = "default-knowledge-graph";

    @Override
    public GraphRagResult answerQuery(GraphRagQuery query) {
        log.debug("Processing GraphRAG query: {}", query.getQuery());
        turnCounter++;

        String conversationId = query.getConversationId() != null ? query.getConversationId() : "default";
        SessionEntityState entityState = sessionEntities.computeIfAbsent(
                conversationId, k -> new SessionEntityState());

        // Resolve ambiguous entity references
        String resolvedQuery = resolveEntityReferences(query.getQuery(), entityState);

        // Get or load the graph
        String graphId = DEFAULT_GRAPH_ID;
        Optional<AdjacencyMatrixGraph> graphOpt = graphStore.loadGraph(graphId);

        if (graphOpt.isEmpty()) {
            log.warn("No graph found for ID: {}. Returning empty result.", graphId);
            return GraphRagResult.builder()
                    .answer("I don't have any knowledge graph data to answer your question.")
                    .formattedContext("")
                    .build();
        }

        AdjacencyMatrixGraph matrixGraph = graphOpt.get();

        // Use resolved query for search
        GraphRagQuery resolvedRagQuery = GraphRagQuery.builder()
                .query(resolvedQuery)
                .searchType(query.getSearchType())
                .k(query.getK())
                .conversationId(conversationId)
                .vectorWeight(query.getVectorWeight())
                .entityType(query.getEntityType())
                .build();

        // Retrieve relevant context based on search type
        String context;
        if (query.getSearchType() == SearchType.GLOBAL) {
            context = retrieveGlobalContext(matrixGraph, resolvedRagQuery);
        } else if (query.getSearchType() == SearchType.HYBRID) {
            context = retrieveHybridContext(matrixGraph, resolvedRagQuery);
        } else {
            context = retrieveLocalContextWithTracking(matrixGraph, resolvedRagQuery, entityState);
        }

        if (context.isEmpty()) {
            return GraphRagResult.builder()
                    .answer("I couldn't find relevant information in the knowledge graph to answer your question.")
                    .formattedContext("")
                    .build();
        }

        // Synthesize answer using LLM
        String answer = synthesizeAnswer(resolvedQuery, context);

        return GraphRagResult.builder()
                .answer(answer)
                .formattedContext(context)
                .build();
    }

    /**
     * Resolve ambiguous references like "that company" using session entity state.
     */
    private String resolveEntityReferences(String query, SessionEntityState entityState) {
        if (entityState.size() == 0) return query;

        Matcher matcher = ENTITY_MENTION_PATTERN.matcher(query);
        StringBuffer sb = new StringBuffer();
        boolean modified = false;

        while (matcher.find()) {
            String fullMatch = matcher.group(0);
            SessionEntityState.TrackedEntity resolved = entityState.resolveReference(fullMatch);
            if (resolved != null) {
                matcher.appendReplacement(sb, Matcher.quoteReplacement(resolved.name()));
                modified = true;
                log.debug("Resolved '{}' -> '{}'", fullMatch, resolved.name());
            }
        }
        matcher.appendTail(sb);

        return modified ? sb.toString() : query;
    }

    /**
     * Get session entity state for a conversation.
     */
    public SessionEntityState getSessionEntityState(String conversationId) {
        return sessionEntities.computeIfAbsent(conversationId, k -> new SessionEntityState());
    }

    /**
     * Retrieves local context with entity tracking for session state.
     */
    private String retrieveLocalContextWithTracking(AdjacencyMatrixGraph graph, GraphRagQuery query, SessionEntityState entityState) {
        String context = retrieveLocalContext(graph, query);

        // Track entities found in local search results
        if (embeddingModel != null) {
            INDArray queryEmbedding = embeddingModel.embed(query.getQuery());
            if (queryEmbedding != null && !queryEmbedding.isEmpty()) {
                List<Map.Entry<String, Double>> similarNodes = graphStore.findSimilarNodes(
                        graph.getGraphId(), queryEmbedding, query.getK() > 0 ? query.getK() : 5, 0.0);
                for (Map.Entry<String, Double> entry : similarNodes) {
                    graph.getNode(entry.getKey()).ifPresent(node ->
                            entityState.trackEntity(
                                    node.getNodeId(), node.getTitle(),
                                    node.getNodeType() != null ? node.getNodeType() : "CONCEPT",
                                    List.of(), turnCounter, node.getNodeId()
                            )
                    );
                }
            }
        }

        return context;
    }

    /**
     * Retrieves local context by finding nodes similar to the query embedding.
     * Falls back to text search if EmbeddingModel is not available.
     */
    private String retrieveLocalContext(AdjacencyMatrixGraph graph, GraphRagQuery query) {
        int k = query.getK() > 0 ? query.getK() : 5;

        // If no embedding model, use text search directly
        if (embeddingModel == null) {
            log.debug("No EmbeddingModel available, using text search for local context");
            List<MatrixGraphNode> searchResults = graphStore.searchNodes(
                    graph.getGraphId(), query.getQuery(), k);
            return formatNodesAsContext(searchResults, graph);
        }

        // Embed the query
        INDArray queryEmbedding = embeddingModel.embed(query.getQuery());
        if (queryEmbedding == null || queryEmbedding.isEmpty()) {
            // Fallback to text search if embedding fails
            List<MatrixGraphNode> searchResults = graphStore.searchNodes(
                    graph.getGraphId(), query.getQuery(), k);
            return formatNodesAsContext(searchResults, graph);
        }

        // Find similar nodes using the graph store
        List<Map.Entry<String, Double>> similarNodes = graphStore.findSimilarNodes(
                graph.getGraphId(), queryEmbedding, k, 0.0);

        if (similarNodes.isEmpty()) {
            // Fallback: use text search
            List<MatrixGraphNode> searchResults = graphStore.searchNodes(
                    graph.getGraphId(), query.getQuery(), k);

            return formatNodesAsContext(searchResults, graph);
        }

        // Get the nodes and their relationships
        List<MatrixGraphNode> relevantNodes = new ArrayList<>();
        for (Map.Entry<String, Double> entry : similarNodes) {
            graph.getNode(entry.getKey()).ifPresent(relevantNodes::add);
        }

        // Expand context with immediate neighbors
        Set<String> expandedNodeIds = new HashSet<>();
        for (MatrixGraphNode node : relevantNodes) {
            expandedNodeIds.add(node.getNodeId());
            List<Map.Entry<String, Double>> neighbors = graph.getNeighbors(node.getNodeId(), null);
            for (Map.Entry<String, Double> neighbor : neighbors) {
                if (expandedNodeIds.size() < k * 2) {
                    expandedNodeIds.add(neighbor.getKey());
                }
            }
        }

        List<MatrixGraphNode> allRelevantNodes = expandedNodeIds.stream()
                .map(id -> graph.getNode(id))
                .filter(Optional::isPresent)
                .map(Optional::get)
                .collect(Collectors.toList());

        return formatNodesAsContext(allRelevantNodes, graph);
    }

    /**
     * Retrieves context via embedding-seeded Personalized PageRank ("HYBRID" search).
     * <p>
     * Embeds the query, finds the most similar nodes (vector ANN over node embeddings) and uses them
     * as PPR seeds weighted by similarity, then runs Personalized PageRank over the graph structure.
     * The final ranking blends the structural PPR score with the seed vector similarity
     * ({@code vectorWeight} controls the mix), so results include multi-hop nodes reachable from the
     * query's entry points — going beyond the 1-hop expansion of {@link #retrieveLocalContext}.
     * Falls back to local/text retrieval when no embedding model is available or no seeds are found.
     * </p>
     */
    private String retrieveHybridContext(AdjacencyMatrixGraph graph, GraphRagQuery query) {
        int k = query.getK() > 0 ? query.getK() : 5;

        // Without an embedding model we cannot seed PPR; degrade to text-based local retrieval.
        if (embeddingModel == null) {
            log.debug("No EmbeddingModel available for HYBRID search, falling back to local context");
            return retrieveLocalContext(graph, query);
        }

        INDArray queryEmbedding = embeddingModel.embed(query.getQuery());
        if (queryEmbedding == null || queryEmbedding.isEmpty()) {
            return retrieveLocalContext(graph, query);
        }

        // Seed set: most similar nodes by vector similarity. Pull a few more than k so PPR has
        // enough entry points to propagate from.
        int seedCount = Math.max(k, 10);
        List<Map.Entry<String, Double>> similarNodes = graphStore.findSimilarNodes(
                graph.getGraphId(), queryEmbedding, seedCount, 0.0);

        if (similarNodes.isEmpty()) {
            return retrieveLocalContext(graph, query);
        }

        // Seed weights from (non-negative) similarity; remember raw similarity for the blend.
        Map<String, Double> seedWeights = new HashMap<>();
        Map<String, Double> seedSimilarity = new HashMap<>();
        for (Map.Entry<String, Double> entry : similarNodes) {
            seedSimilarity.put(entry.getKey(), entry.getValue());
            double sim = Math.max(0.0, entry.getValue());
            if (sim > 0) {
                seedWeights.put(entry.getKey(), sim);
            }
        }
        if (seedWeights.isEmpty()) {
            // All similarities non-positive; seed uniformly over the returned nodes.
            for (Map.Entry<String, Double> entry : similarNodes) {
                seedWeights.put(entry.getKey(), 1.0);
            }
        }

        // Personalized PageRank over the graph structure, seeded by the embedding matches.
        Map<String, Double> ppr = MatrixGraphAlgorithms.personalizedPageRank(graph, seedWeights);
        if (ppr.isEmpty()) {
            return retrieveLocalContext(graph, query);
        }

        // Blend structural PPR importance with direct vector similarity (different scales, so
        // normalize each to [0,1] first). vectorWeight=1 -> pure similarity; 0 -> pure structure.
        double maxPpr = ppr.values().stream().mapToDouble(Double::doubleValue).max().orElse(1.0);
        double maxSim = seedSimilarity.values().stream().mapToDouble(Double::doubleValue).max().orElse(1.0);
        if (maxPpr <= 0) maxPpr = 1.0;
        if (maxSim <= 0) maxSim = 1.0;
        double alpha = Math.min(1.0, Math.max(0.0, query.getVectorWeight()));

        Map<String, Double> blended = new HashMap<>();
        for (Map.Entry<String, Double> e : ppr.entrySet()) {
            double structural = e.getValue() / maxPpr;
            double sim = seedSimilarity.getOrDefault(e.getKey(), 0.0) / maxSim;
            blended.put(e.getKey(), (1 - alpha) * structural + alpha * sim);
        }

        // Structural KG-embedding (TransE/RotatE) signal: when nodes carry trained KGE vectors, pull in
        // / boost entities that are structurally similar (in KGE space) to the seeds — even if they are
        // far in the text-embedding + PageRank views. This is the trained KG embeddings actually
        // participating in retrieval, not just hybrid vector search. Empty (no-op) until embeddings exist.
        Map<String, Double> kgeSim = kgeStructuralSimilarity(graph, seedSimilarity.keySet());
        Set<String> candidateIds = new HashSet<>(blended.keySet());
        candidateIds.addAll(kgeSim.keySet());

        Map<String, Double> finalScores = new HashMap<>();
        for (String id : candidateIds) {
            double base = blended.getOrDefault(id, 0.0);
            double kge = kgeSim.getOrDefault(id, 0.0);
            finalScores.put(id, base + KGE_RETRIEVAL_WEIGHT * kge);
        }

        List<MatrixGraphNode> rankedNodes = finalScores.entrySet().stream()
                .sorted((a, b) -> Double.compare(b.getValue(), a.getValue()))
                .map(e -> graph.getNode(e.getKey()))
                .filter(Optional::isPresent)
                .map(Optional::get)
                .filter(node -> matchesEntityType(node, query.getEntityType()))
                .limit(k)
                .collect(Collectors.toList());

        // PathRAG augmentation: append the most reliable relational paths connecting the key entities.
        String baseContext = formatNodesAsContext(rankedNodes, graph);
        List<String> keyEntityIds = rankedNodes.stream()
                .map(MatrixGraphNode::getNodeId)
                .collect(Collectors.toList());
        return baseContext + retrievePathContext(graph, keyEntityIds);
    }

    /** Blend weight for the structural KG-embedding similarity term in HYBRID retrieval. */
    private static final double KGE_RETRIEVAL_WEIGHT = 0.5;

    /**
     * Computes, for every node carrying a structural KG embedding, its best cosine similarity (in KGE
     * space) to any seed node that also has one. Returns an empty map when no KGE vectors are present
     * (i.e. embeddings have not been trained for this graph), so HYBRID degrades cleanly to PPR +
     * vector similarity.
     */
    private Map<String, Double> kgeStructuralSimilarity(AdjacencyMatrixGraph graph, Set<String> seedNodeIds) {
        List<INDArray> seedVectors = new ArrayList<>();
        for (String id : seedNodeIds) {
            INDArray v = kgeVectorOf(graph, id);
            if (v != null) {
                seedVectors.add(v);
            }
        }
        if (seedVectors.isEmpty()) {
            return Collections.emptyMap();
        }
        Map<String, Double> sims = new HashMap<>();
        for (String nodeId : graph.getNodeById().keySet()) {
            INDArray v = kgeVectorOf(graph, nodeId);
            if (v == null) {
                continue;
            }
            double best = 0.0;
            for (INDArray seed : seedVectors) {
                best = Math.max(best, cosineSimilarity(v, seed));
            }
            if (best > 0) {
                sims.put(nodeId, best);
            }
        }
        return sims;
    }

    private INDArray kgeVectorOf(AdjacencyMatrixGraph graph, String nodeId) {
        MatrixGraphNode node = graph.getNode(nodeId).orElse(null);
        if (node == null || node.getMetadata() == null) {
            return null;
        }
        return MatrixKgEmbeddingGraphAdapter.decode(
                node.getMetadata().get(MatrixKgEmbeddingGraphAdapter.KGE_EMBEDDING_KEY));
    }

    private static double cosineSimilarity(INDArray a, INDArray b) {
        if (a.length() != b.length()) {
            return 0.0;
        }
        double dot = a.mul(b).sumNumber().doubleValue();
        double na = a.norm2Number().doubleValue();
        double nb = b.norm2Number().doubleValue();
        if (na == 0.0 || nb == 0.0) {
            return 0.0;
        }
        return dot / (na * nb);
    }

    /** Max distinct key entities to connect with paths (bounds the O(n^2) pair search). */
    private static final int PATH_MAX_KEY_ENTITIES = 6;
    private static final int PATH_MAX_HOPS = 4;
    private static final int PATH_MAX_PER_PAIR = 3;
    private static final int PATH_MAX_RESULTS = 5;

    private record ScoredPath(List<String> path, double reliability) {}

    /**
     * PathRAG-style augmentation: surfaces the most reliable relational paths connecting the top key
     * entities, so the context explains HOW the query-relevant entities relate, not just that they are
     * relevant. Paths are scored by reliability (product of edge weights), pruned to the strongest few,
     * and rendered as readable chains. Returns "" when there are fewer than two key entities or no paths.
     */
    private String retrievePathContext(AdjacencyMatrixGraph graph, List<String> keyEntityIds) {
        if (keyEntityIds == null || keyEntityIds.size() < 2) {
            return "";
        }
        List<String> tops = keyEntityIds.size() > PATH_MAX_KEY_ENTITIES
                ? keyEntityIds.subList(0, PATH_MAX_KEY_ENTITIES)
                : keyEntityIds;

        List<ScoredPath> scored = new ArrayList<>();
        for (int i = 0; i < tops.size(); i++) {
            for (int j = 0; j < tops.size(); j++) {
                if (i == j) {
                    continue;
                }
                for (List<String> path : MatrixGraphAlgorithms.findPaths(
                        graph, tops.get(i), tops.get(j), PATH_MAX_HOPS, PATH_MAX_PER_PAIR)) {
                    scored.add(new ScoredPath(path, pathReliability(graph, path)));
                }
            }
        }
        if (scored.isEmpty()) {
            return "";
        }
        scored.sort((a, b) -> Double.compare(b.reliability(), a.reliability()));

        StringBuilder sb = new StringBuilder("\nConnecting relationships (paths between key entities):\n");
        int count = 0;
        Set<String> seen = new HashSet<>();
        for (ScoredPath sp : scored) {
            if (count >= PATH_MAX_RESULTS) {
                break;
            }
            String rendered = renderPath(graph, sp.path());
            if (seen.add(rendered)) {
                sb.append("- ").append(rendered).append("\n");
                count++;
            }
        }
        return sb.toString();
    }

    /** Path reliability = product of edge weights along the path (weaker links lower the score). */
    private double pathReliability(AdjacencyMatrixGraph graph, List<String> path) {
        double reliability = 1.0;
        for (int i = 0; i < path.size() - 1; i++) {
            double weight = 1.0;
            for (Map.Entry<String, Double> nb : graph.getNeighbors(path.get(i), null)) {
                if (nb.getKey().equals(path.get(i + 1))) {
                    weight = nb.getValue() != null ? nb.getValue() : 1.0;
                    break;
                }
            }
            reliability *= weight;
        }
        return reliability;
    }

    private String renderPath(AdjacencyMatrixGraph graph, List<String> path) {
        return path.stream()
                .map(id -> graph.getNode(id).map(MatrixGraphNode::getTitle).orElse(id))
                .collect(Collectors.joining(" --> "));
    }

    /**
     * Ontology-typed (schema-aware) gate: true if there is no type constraint, or the node's semantic
     * type matches it. The semantic type is taken from the structural node type or, preferentially,
     * the free-form {@code entity_type} in node metadata (set by the extractor / ontology).
     */
    private static boolean matchesEntityType(MatrixGraphNode node, String entityType) {
        if (entityType == null || entityType.isBlank()) {
            return true;
        }
        if (node.getMetadata() != null) {
            Object et = node.getMetadata().get("entity_type");
            if (et != null && entityType.equalsIgnoreCase(et.toString())) {
                return true;
            }
        }
        return node.getNodeType() != null && entityType.equalsIgnoreCase(node.getNodeType());
    }

    /**
     * Retrieves global context using PageRank to identify important nodes.
     */
    private String retrieveGlobalContext(AdjacencyMatrixGraph graph, GraphRagQuery query) {
        int k = query.getK() > 0 ? query.getK() : 10;

        // Microsoft-GraphRAG-style global search: when community reports exist, answer from the
        // query-relevant ones (sensemaking over summarized communities) instead of the query-agnostic
        // top-PageRank-nodes + stats fallback below.
        if (communitySummaryService != null) {
            List<CommunitySummaryService.CommunityReport> reports = communitySummaryService.getOrBuildReports(graph);
            if (reports != null && !reports.isEmpty()) {
                return buildCommunityContext(reports, query);
            }
        }

        // Compute PageRank to find important nodes
        Map<String, Double> pageRankScores = MatrixGraphAlgorithms.pageRank(graph);

        // Sort by PageRank and take top k
        List<String> topNodeIds = pageRankScores.entrySet().stream()
                .sorted((a, b) -> Double.compare(b.getValue(), a.getValue()))
                .limit(k)
                .map(Map.Entry::getKey)
                .collect(Collectors.toList());

        // Get the high-importance nodes
        List<MatrixGraphNode> importantNodes = topNodeIds.stream()
                .map(id -> graph.getNode(id))
                .filter(Optional::isPresent)
                .map(Optional::get)
                .collect(Collectors.toList());

        // Find communities for broader context
        List<Set<String>> components = MatrixGraphAlgorithms.findConnectedComponents(graph);

        StringBuilder contextBuilder = new StringBuilder();

        // Add summary of graph structure
        contextBuilder.append("Knowledge Graph Overview:\n");
        contextBuilder.append(String.format("- Total entities: %d\n", graph.getNodeCount()));
        contextBuilder.append(String.format("- Total relationships: %d\n", graph.getEdgeCount()));
        contextBuilder.append(String.format("- Connected components: %d\n\n", components.size()));

        // Add top entities
        contextBuilder.append("Key Entities (by importance):\n");
        for (MatrixGraphNode node : importantNodes) {
            contextBuilder.append(formatNodeContext(node));
            contextBuilder.append("\n");
        }

        // Add relationship context for top nodes
        contextBuilder.append("\nKey Relationships:\n");
        for (MatrixGraphNode node : importantNodes.subList(0, Math.min(5, importantNodes.size()))) {
            List<Map.Entry<String, Double>> neighbors = graph.getNeighbors(node.getNodeId(), null);
            for (Map.Entry<String, Double> neighbor : neighbors) {
                Optional<MatrixGraphNode> targetOpt = graph.getNode(neighbor.getKey());
                if (targetOpt.isPresent()) {
                    contextBuilder.append(String.format("- %s -> %s (weight: %.2f)\n",
                            node.getTitle(), targetOpt.get().getTitle(), neighbor.getValue()));
                }
            }
        }

        return contextBuilder.toString();
    }

    /**
     * Builds GLOBAL-search context from community reports, ordered by relevance to the query. When the
     * query and reports both have embeddings, reports are ranked by cosine similarity; otherwise all
     * reports are included in detection order. The top reports' summaries are concatenated as context
     * for the final LLM synthesis (map-reduce: per-community summaries are the "map", selection +
     * concatenation the "reduce").
     */
    private String buildCommunityContext(List<CommunitySummaryService.CommunityReport> reports, GraphRagQuery query) {
        int topN = query.getK() > 0 ? query.getK() : 5;

        INDArray queryEmbedding = embeddingModel != null ? embeddingModel.embed(query.getQuery()) : null;
        boolean haveEmbeddings = queryEmbedding != null && !queryEmbedding.isEmpty()
                && reports.stream().anyMatch(r -> r.summaryEmbedding() != null);

        List<CommunitySummaryService.CommunityReport> ranked;
        if (haveEmbeddings) {
            Map<Integer, Double> score = new HashMap<>();
            for (CommunitySummaryService.CommunityReport r : reports) {
                score.put(r.communityId(),
                        r.summaryEmbedding() != null ? cosineSimilarity(queryEmbedding, r.summaryEmbedding()) : -1.0);
            }
            ranked = reports.stream()
                    .sorted((a, b) -> Double.compare(score.get(b.communityId()), score.get(a.communityId())))
                    .collect(Collectors.toList());
        } else {
            ranked = reports;
        }

        StringBuilder contextBuilder = new StringBuilder();
        contextBuilder.append("Knowledge Graph Community Reports (most relevant first):\n\n");
        int count = 0;
        for (CommunitySummaryService.CommunityReport r : ranked) {
            if (count >= topN) {
                break;
            }
            contextBuilder.append(String.format("Community %d (%d entities):\n",
                    r.communityId(), r.memberNodeIds().size()));
            contextBuilder.append(r.summary() == null ? "" : r.summary().trim()).append("\n\n");
            count++;
        }
        return contextBuilder.toString();
    }

    /**
     * Formats a list of nodes as context string.
     */
    private String formatNodesAsContext(List<MatrixGraphNode> nodes, AdjacencyMatrixGraph graph) {
        if (nodes.isEmpty()) {
            return "";
        }

        StringBuilder contextBuilder = new StringBuilder();
        contextBuilder.append("Relevant Knowledge:\n\n");

        for (MatrixGraphNode node : nodes) {
            contextBuilder.append(formatNodeContext(node));

            // Add relationships
            List<Map.Entry<String, Double>> neighbors = graph.getNeighbors(node.getNodeId(), null);
            if (!neighbors.isEmpty()) {
                contextBuilder.append("  Related to:\n");
                for (Map.Entry<String, Double> neighbor : neighbors.subList(0, Math.min(3, neighbors.size()))) {
                    graph.getNode(neighbor.getKey()).ifPresent(target ->
                            contextBuilder.append(String.format("    - %s\n", target.getTitle()))
                    );
                }
            }
            contextBuilder.append("\n");
        }

        return contextBuilder.toString();
    }

    /**
     * Formats a single node as context.
     */
    private String formatNodeContext(MatrixGraphNode node) {
        StringBuilder sb = new StringBuilder();
        sb.append(String.format("Entity: %s", node.getTitle()));
        if (node.getNodeType() != null) {
            sb.append(String.format(" [%s]", node.getNodeType()));
        }
        sb.append("\n");

        if (node.getDescription() != null && !node.getDescription().isEmpty()) {
            sb.append(String.format("  Description: %s\n", node.getDescription()));
        }

        return sb.toString();
    }

    /**
     * Synthesizes an answer using the LLM.
     * If no LLM is available, returns a message indicating context-only mode.
     *
     * @param query   the user's question
     * @param context the retrieved context
     * @return the synthesized answer or a context-only message
     */
    private String synthesizeAnswer(String query, String context) {
        // If no LLM is available, return context-only response
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

    /**
     * Queries using a pre-constructed Graph object.
     *
     * @param query The RAG query with embedded Graph
     * @return GraphRagResult with answer and context
     */
    public GraphRagResult answerQueryWithGraph(GraphRagQuery query) {
        Graph providedGraph = query.getGraph();
        if (providedGraph == null || providedGraph.getEntities() == null) {
            return answerQuery(query);
        }

        // Build context from the provided graph
        StringBuilder contextBuilder = new StringBuilder();
        contextBuilder.append("Knowledge Graph Context:\n\n");

        // Add entities
        contextBuilder.append("Entities:\n");
        for (Entity entity : providedGraph.getEntities()) {
            contextBuilder.append(String.format("- %s: %s\n",
                    entity.getTitle(),
                    entity.getDescription() != null ? entity.getDescription() : ""));
        }

        // Add relationships
        if (providedGraph.getRelationships() != null && !providedGraph.getRelationships().isEmpty()) {
            contextBuilder.append("\nRelationships:\n");
            for (Relationship rel : providedGraph.getRelationships()) {
                contextBuilder.append(String.format("- %s -> %s: %s\n",
                        rel.getSource(), rel.getTarget(),
                        rel.getDescription() != null ? rel.getDescription() : ""));
            }
        }

        String context = contextBuilder.toString();
        String answer = synthesizeAnswer(query.getQuery(), context);

        return GraphRagResult.builder()
                .answer(answer)
                .formattedContext(context)
                .build();
    }
}
