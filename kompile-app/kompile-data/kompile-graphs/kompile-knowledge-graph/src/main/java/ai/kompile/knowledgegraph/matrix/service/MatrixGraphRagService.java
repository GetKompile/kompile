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

    public MatrixGraphRagService() {}

    /** Test constructor. */
    public MatrixGraphRagService(MatrixGraphStore graphStore, EmbeddingModel embeddingModel, LLMChat llmChat) {
        this.graphStore = graphStore;
        this.embeddingModel = embeddingModel;
        this.llmChat = llmChat;
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
                .build();

        // Retrieve relevant context based on search type
        String context;
        if (query.getSearchType() == SearchType.GLOBAL) {
            context = retrieveGlobalContext(matrixGraph, resolvedRagQuery);
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
     * Retrieves global context using PageRank to identify important nodes.
     */
    private String retrieveGlobalContext(AdjacencyMatrixGraph graph, GraphRagQuery query) {
        int k = query.getK() > 0 ? query.getK() : 10;

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
