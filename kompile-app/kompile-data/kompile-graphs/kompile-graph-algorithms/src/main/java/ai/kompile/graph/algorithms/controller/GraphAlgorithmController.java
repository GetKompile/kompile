/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 */
package ai.kompile.graph.algorithms.controller;

import ai.kompile.graph.algorithms.DegreeCentrality;
import ai.kompile.graph.algorithms.JaccardNodeSimilarity;
import ai.kompile.graph.algorithms.ShortestPathAlgorithm;
import ai.kompile.graph.algorithms.community.CommunitySummary;
import ai.kompile.graph.algorithms.service.GraphAlgorithmService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * REST endpoints for graph algorithms over the JPA-backed knowledge graph.
 *
 * <p>All requests accept a {@code factSheetId} (null = full graph). The CLI talks to these
 * endpoints exclusively, so any backend change here is picked up automatically.
 */
@RestController
@RequestMapping("/api/graph/algorithms")
public class GraphAlgorithmController {

    private static final Logger log = LoggerFactory.getLogger(GraphAlgorithmController.class);

    private final GraphAlgorithmService service;

    public GraphAlgorithmController(GraphAlgorithmService service) {
        this.service = service;
    }

    @PostMapping("/pagerank")
    public ResponseEntity<Map<String, Double>> pageRank(@RequestBody PageRankRequest req) {
        double damping = req.damping() != null ? req.damping() : 0.85;
        int maxIter = req.maxIterations() != null ? req.maxIterations() : 100;
        double tol = req.tolerance() != null ? req.tolerance() : 1e-6;
        return ResponseEntity.ok(service.pageRank(req.factSheetId(), damping, maxIter, tol));
    }

    @PostMapping("/centrality/degree")
    public ResponseEntity<Map<String, Double>> degreeCentrality(@RequestBody DegreeRequest req) {
        DegreeCentrality.Type type = parseDegreeType(req.type());
        return ResponseEntity.ok(service.degreeCentrality(req.factSheetId(), type));
    }

    @PostMapping("/centrality/betweenness")
    public ResponseEntity<Map<String, Double>> betweenness(@RequestBody BetweennessRequest req) {
        int sample = req.sampleSize() != null ? req.sampleSize() : -1;
        long seed = req.randomSeed() != null ? req.randomSeed() : 0L;
        return ResponseEntity.ok(service.betweennessCentrality(req.factSheetId(), sample, seed));
    }

    @PostMapping("/path/shortest")
    public ResponseEntity<ShortestPathAlgorithm.PathResult> shortestPath(@RequestBody ShortestPathRequest req) {
        boolean weighted = Boolean.TRUE.equals(req.weighted());
        return ResponseEntity.ok(service.shortestPath(req.factSheetId(), req.fromNodeId(), req.toNodeId(), weighted));
    }

    @PostMapping("/traverse/bfs")
    public ResponseEntity<Map<Integer, List<String>>> bfsTraversal(@RequestBody BfsRequest req) {
        int depth = req.maxDepth() != null ? req.maxDepth() : 3;
        return ResponseEntity.ok(service.bfsTraversal(req.factSheetId(), req.startNodeId(), depth));
    }

    @PostMapping("/components/wcc")
    public ResponseEntity<Map<String, Integer>> weaklyConnected(@RequestBody ScopeRequest req) {
        return ResponseEntity.ok(service.weaklyConnectedComponents(req.factSheetId()));
    }

    @PostMapping("/communities/louvain")
    public ResponseEntity<Map<String, Integer>> louvain(@RequestBody LouvainRequest req) {
        int max = req.maxIterations() != null ? req.maxIterations() : 20;
        return ResponseEntity.ok(service.louvainCommunities(req.factSheetId(), max));
    }

    @PostMapping("/communities/{algorithm}/summarize")
    public ResponseEntity<List<CommunitySummary>> summarize(@PathVariable String algorithm,
                                                            @RequestBody SummarizeRequest req) {
        int max = req.maxNodesPerPrompt() != null ? req.maxNodesPerPrompt() : 25;
        return ResponseEntity.ok(service.summarizeCommunities(req.factSheetId(), algorithm, max));
    }

    @PostMapping("/similarity/jaccard")
    public ResponseEntity<JaccardResponse> jaccard(@RequestBody JaccardRequest req) {
        if (req.nodeAId() != null && req.nodeBId() != null) {
            double score = service.jaccardSimilarity(req.factSheetId(), req.nodeAId(), req.nodeBId());
            return ResponseEntity.ok(new JaccardResponse(score, null));
        }
        int k = req.topK() != null ? req.topK() : 10;
        double threshold = req.threshold() != null ? req.threshold() : 0.0;
        return ResponseEntity.ok(new JaccardResponse(null, service.jaccardTopK(req.factSheetId(), k, threshold)));
    }

    @PostMapping("/cache/invalidate")
    public ResponseEntity<Void> invalidate(@RequestBody ScopeRequest req) {
        if (req.factSheetId() == null) service.invalidateCache();
        else service.invalidateCache(req.factSheetId());
        return ResponseEntity.noContent().build();
    }

    private static DegreeCentrality.Type parseDegreeType(String type) {
        if (type == null) return DegreeCentrality.Type.TOTAL;
        return switch (type.toLowerCase()) {
            case "in" -> DegreeCentrality.Type.IN;
            case "out" -> DegreeCentrality.Type.OUT;
            default -> DegreeCentrality.Type.TOTAL;
        };
    }

    public record PageRankRequest(Long factSheetId, Double damping, Integer maxIterations, Double tolerance) {}
    public record DegreeRequest(Long factSheetId, String type) {}
    public record BetweennessRequest(Long factSheetId, Integer sampleSize, Long randomSeed) {}
    public record ShortestPathRequest(Long factSheetId, String fromNodeId, String toNodeId, Boolean weighted) {}
    public record BfsRequest(Long factSheetId, String startNodeId, Integer maxDepth) {}
    public record ScopeRequest(Long factSheetId) {}
    public record LouvainRequest(Long factSheetId, Integer maxIterations) {}
    public record SummarizeRequest(Long factSheetId, Integer maxNodesPerPrompt) {}
    public record JaccardRequest(Long factSheetId, String nodeAId, String nodeBId, Integer topK, Double threshold) {}
    public record JaccardResponse(Double score, List<JaccardNodeSimilarity.SimilarityPair> pairs) {}
}
