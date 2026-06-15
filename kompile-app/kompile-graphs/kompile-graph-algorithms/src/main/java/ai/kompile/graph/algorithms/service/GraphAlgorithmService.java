/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 */
package ai.kompile.graph.algorithms.service;

import ai.kompile.graph.algorithms.BetweennessCentrality;
import ai.kompile.graph.algorithms.BfsTraversal;
import ai.kompile.graph.algorithms.DegreeCentrality;
import ai.kompile.graph.algorithms.JaccardNodeSimilarity;
import ai.kompile.graph.algorithms.LouvainCommunityDetection;
import ai.kompile.graph.algorithms.PageRankAlgorithm;
import ai.kompile.graph.algorithms.ShortestPathAlgorithm;
import ai.kompile.graph.algorithms.WeaklyConnectedComponents;
import ai.kompile.graph.algorithms.adjacency.AdjacencyView;
import ai.kompile.graph.algorithms.adjacency.AdjacencyViewBuilder;
import ai.kompile.graph.algorithms.community.CommunitySummarizer;
import ai.kompile.graph.algorithms.community.CommunitySummary;
import ai.kompile.knowledgegraph.service.KnowledgeGraphService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Orchestrates graph algorithms over the JPA-backed knowledge graph.
 *
 * <p>An {@link AdjacencyView} is materialized lazily per scope (factSheetId or null
 * for full graph) and cached until {@link #invalidateCache} is called. Callers should
 * invalidate after mutating writes if they want fresh structure.
 */
@Service
public class GraphAlgorithmService {

    private static final int DEFAULT_MAX_DEPTH = Integer.MAX_VALUE;

    private final KnowledgeGraphService graphService;
    private final CommunitySummarizer communitySummarizer;
    private final Map<String, AdjacencyView> viewCache = new ConcurrentHashMap<>();

    @Autowired
    public GraphAlgorithmService(KnowledgeGraphService graphService,
                                  CommunitySummarizer communitySummarizer) {
        this.graphService = graphService;
        this.communitySummarizer = communitySummarizer;
    }

    public AdjacencyView view(Long factSheetId) {
        String key = factSheetId == null ? "*" : factSheetId.toString();
        return viewCache.computeIfAbsent(key, k -> buildView(factSheetId));
    }

    public void invalidateCache() {
        viewCache.clear();
    }

    public void invalidateCache(Long factSheetId) {
        viewCache.remove(factSheetId == null ? "*" : factSheetId.toString());
    }

    public Map<String, Double> pageRank(Long factSheetId, double damping, int maxIterations, double tolerance) {
        return PageRankAlgorithm.compute(view(factSheetId), damping, maxIterations, tolerance);
    }

    public Map<String, Double> degreeCentrality(Long factSheetId, DegreeCentrality.Type type) {
        return DegreeCentrality.compute(view(factSheetId), type);
    }

    public Map<String, Double> betweennessCentrality(Long factSheetId, int sampleSize, long randomSeed) {
        return BetweennessCentrality.compute(view(factSheetId), sampleSize, randomSeed);
    }

    public ShortestPathAlgorithm.PathResult shortestPath(Long factSheetId,
                                                         String fromNodeId,
                                                         String toNodeId,
                                                         boolean weighted) {
        AdjacencyView v = view(factSheetId);
        return weighted
                ? ShortestPathAlgorithm.dijkstra(v, fromNodeId, toNodeId)
                : ShortestPathAlgorithm.bfs(v, fromNodeId, toNodeId);
    }

    public Map<Integer, List<String>> bfsTraversal(Long factSheetId, String startNodeId, int maxDepth) {
        return BfsTraversal.traverse(view(factSheetId), startNodeId, maxDepth);
    }

    public Map<String, Integer> weaklyConnectedComponents(Long factSheetId) {
        return WeaklyConnectedComponents.compute(view(factSheetId));
    }

    public Map<String, Integer> louvainCommunities(Long factSheetId, int maxIterations) {
        return LouvainCommunityDetection.compute(view(factSheetId), maxIterations);
    }

    public List<CommunitySummary> summarizeCommunities(Long factSheetId,
                                                       String algorithm,
                                                       int maxNodesPerPrompt) {
        Map<String, Integer> assignments;
        if ("wcc".equalsIgnoreCase(algorithm)) {
            assignments = weaklyConnectedComponents(factSheetId);
        } else {
            assignments = louvainCommunities(factSheetId, 20);
        }
        return communitySummarizer.summarize(assignments, graphService, maxNodesPerPrompt);
    }

    public double jaccardSimilarity(Long factSheetId, String nodeAId, String nodeBId) {
        return JaccardNodeSimilarity.similarity(view(factSheetId), nodeAId, nodeBId);
    }

    public List<JaccardNodeSimilarity.SimilarityPair> jaccardTopK(Long factSheetId, int topK, double threshold) {
        return JaccardNodeSimilarity.topK(view(factSheetId), topK, threshold);
    }

    private AdjacencyView buildView(Long factSheetId) {
        if (factSheetId == null) {
            return AdjacencyViewBuilder.fromGraph(graphService, null, DEFAULT_MAX_DEPTH);
        }
        return AdjacencyViewBuilder.fromFactSheet(graphService, factSheetId);
    }
}
