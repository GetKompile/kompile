/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 */
package ai.kompile.graph.reasoning.claims;

import ai.kompile.graph.reasoning.model.GraphRelation;
import ai.kompile.graph.reasoning.model.ReasoningGraph;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.PriorityQueue;

/**
 * Knowledge-Linker path scorer for claim evidence.
 *
 * <p>Given a subject entity and an object entity, searches the graph (treating all edges as
 * undirected) for the path whose weight is maximized. The weight of a path is the product of
 * inverse log-degrees over all <em>intermediate</em> nodes:
 *
 * <pre>
 *   W = ∏ over intermediate v of  1 / log(max(e, degree(v)))
 * </pre>
 *
 * where {@code degree(v) = |relationsOf(v)|} (all incident edges in either direction) and
 * {@code max(e, ...)} ensures the logarithm is never zero when degree = 1.
 * A pair of direct neighbors (no intermediate node) gets score 1.0.
 *
 * <p>Edges whose type matches {@code excludePredicate} (case-insensitive) are excluded from
 * traversal. This lets callers hide the very predicate being evaluated so the path must supply
 * independent evidence.
 *
 * <p>The search is a best-first (Dijkstra-style) minimization of the accumulated
 * negative-log-product cost, capped by configurable {@link #maxDepth()} and
 * {@link #maxExpansions()}.
 *
 * <p>Reference: Ciampaglia et al., "Computational Fact-Checking from Knowledge Networks,"
 * <em>PLoS ONE</em>, 2015.
 */
public final class KnowledgeLinkerScorer {

    /** Default maximum path length (number of hops). */
    public static final int DEFAULT_MAX_DEPTH = 4;

    /** Default maximum number of nodes expanded in the priority queue. */
    public static final int DEFAULT_MAX_EXPANSIONS = 10_000;

    private final int maxDepth;
    private final int maxExpansions;

    /** Create a scorer with default knob values. */
    public KnowledgeLinkerScorer() {
        this(DEFAULT_MAX_DEPTH, DEFAULT_MAX_EXPANSIONS);
    }

    /**
     * Create a scorer with explicit knob values.
     *
     * @param maxDepth       maximum path length in hops (inclusive)
     * @param maxExpansions  maximum number of node expansions before giving up
     */
    public KnowledgeLinkerScorer(int maxDepth, int maxExpansions) {
        if (maxDepth < 1) throw new IllegalArgumentException("maxDepth must be >= 1, got " + maxDepth);
        if (maxExpansions < 1) throw new IllegalArgumentException("maxExpansions must be >= 1, got " + maxExpansions);
        this.maxDepth = maxDepth;
        this.maxExpansions = maxExpansions;
    }

    public int maxDepth() { return maxDepth; }
    public int maxExpansions() { return maxExpansions; }

    /**
     * Score the path between {@code subjectId} and {@code objectId} in {@code graph},
     * excluding edges whose type equals {@code excludePredicate} (case-insensitive, null = exclude nothing).
     *
     * @param graph            the knowledge graph to search (must not be null)
     * @param subjectId        source entity id
     * @param objectId         target entity id
     * @param excludePredicate edge type to exclude from traversal (case-insensitive), or null
     * @return the best-scoring path evidence, or {@code null} if no path is found within bounds
     */
    public PathEvidence score(ReasoningGraph graph, String subjectId, String objectId,
                              String excludePredicate) {
        Objects.requireNonNull(graph,    "graph must not be null");
        Objects.requireNonNull(subjectId, "subjectId must not be null");
        Objects.requireNonNull(objectId,  "objectId must not be null");

        if (subjectId.equals(objectId)) {
            // Trivial self-path with score 1.0 — single-node list, no edges
            return new PathEvidence(1.0, List.of(subjectId), List.of());
        }

        // Check for direct neighbor (no intermediate needed) — score 1.0
        // Only if there IS a direct edge of non-excluded type
        if (hasDirectEdge(graph, subjectId, objectId, excludePredicate)) {
            String edgeType = directEdgeType(graph, subjectId, objectId, excludePredicate);
            return new PathEvidence(1.0, List.of(subjectId, objectId),
                    List.of(edgeType != null ? edgeType : ""));
        }

        // Best-first search minimizing accumulated cost (-log W = Σ log log degree of intermediates)
        // State: (accumulatedCost, currentNode, depth, path, edgeTypes)
        // priority queue ordered by ascending accumulatedCost (= minimizing cost = maximizing W)
        PriorityQueue<SearchState> pq = new PriorityQueue<>();
        // Start: from subject; cost = 0.0 (no intermediates yet); depth = 0
        pq.add(new SearchState(0.0, subjectId, 0, new ArrayList<>(List.of(subjectId)), new ArrayList<>()));

        // visited tracks best cost seen per node to avoid re-expansion at worse cost
        Map<String, Double> bestCostSeen = new HashMap<>();
        bestCostSeen.put(subjectId, 0.0);

        int expansions = 0;

        while (!pq.isEmpty() && expansions < maxExpansions) {
            SearchState curr = pq.poll();
            expansions++;

            // Explore neighbors (undirected)
            List<NeighborEdge> neighbors = neighborsOf(graph, curr.nodeId, excludePredicate);

            for (NeighborEdge ne : neighbors) {
                String neighborId = ne.neighborId;
                String edgeType = ne.edgeType;
                int newDepth = curr.depth + 1;

                // Build path lists eagerly (we need them for the result)
                List<String> newPath = new ArrayList<>(curr.pathNodes);
                newPath.add(neighborId);
                List<String> newEdges = new ArrayList<>(curr.edgeTypes);
                newEdges.add(edgeType);

                if (neighborId.equals(objectId)) {
                    // Found the target — reconstruct result
                    double w = Math.exp(-curr.accumulatedCost); // W = e^(-cost)
                    // clamp to (0,1] to be safe; should already be in range
                    double score = Math.max(1e-9, Math.min(1.0, w));
                    return new PathEvidence(score, newPath, newEdges);
                }

                if (newDepth >= maxDepth) continue; // don't expand beyond maxDepth

                // Compute incremental cost for neighborId as intermediate.
                // W = Π 1/log(max(e, degree)) over intermediates
                // → cost = -log(W) = Σ log(log(max(e, degree))) over intermediates
                // We use log(max(e, degree)) because log(max(e,1)) = log(e) = 1 as floor,
                // ensuring cost is always finite and non-negative.
                int degree = graph.relationsOf(neighborId).size();
                double logDegree = Math.log(Math.max(Math.E, degree)); // log(max(e,d)) ≥ 1
                // log(logDegree) can be 0 when degree=e (edge case); use max to keep cost ≥ 0
                double stepCost = Math.log(Math.max(1.0, logDegree));
                double newCost = curr.accumulatedCost + stepCost;

                // Skip if we've seen this node at a better or equal cost
                Double seen = bestCostSeen.get(neighborId);
                if (seen != null && seen <= newCost) continue;
                bestCostSeen.put(neighborId, newCost);

                pq.add(new SearchState(newCost, neighborId, newDepth, newPath, newEdges));
            }
        }

        // No path found within bounds
        return null;
    }

    // ── Private helpers ────────────────────────────────────────────────────────

    /**
     * Check whether there is any direct edge of non-excluded type between subject and object,
     * treating all edges as undirected (both directions considered).
     */
    private static boolean hasDirectEdge(ReasoningGraph graph, String sourceId, String targetId,
                                          String excludePredicate) {
        // subject→object directed edge
        for (GraphRelation r : graph.outgoing(sourceId)) {
            if (targetId.equals(r.targetId()) && !isExcluded(r.type(), excludePredicate)) return true;
        }
        // object→subject directed edge (treated as undirected in KL traversal)
        for (GraphRelation r : graph.outgoing(targetId)) {
            if (sourceId.equals(r.targetId()) && !isExcluded(r.type(), excludePredicate)) return true;
        }
        // undirected edges stored under subject's incoming index (subject is the "target" end)
        for (GraphRelation r : graph.incoming(sourceId)) {
            if (!r.directed() && targetId.equals(r.sourceId()) && !isExcluded(r.type(), excludePredicate)) return true;
        }
        return false;
    }

    /** Returns the type of any direct non-excluded edge between subject and object (undirected search), or null. */
    private static String directEdgeType(ReasoningGraph graph, String sourceId, String targetId,
                                          String excludePredicate) {
        for (GraphRelation r : graph.outgoing(sourceId)) {
            if (targetId.equals(r.targetId()) && !isExcluded(r.type(), excludePredicate)) return r.type();
        }
        for (GraphRelation r : graph.outgoing(targetId)) {
            if (sourceId.equals(r.targetId()) && !isExcluded(r.type(), excludePredicate)) return r.type();
        }
        for (GraphRelation r : graph.incoming(sourceId)) {
            if (!r.directed() && targetId.equals(r.sourceId()) && !isExcluded(r.type(), excludePredicate)) return r.type();
        }
        return null;
    }

    /**
     * Collect all undirected neighbors of a node, treating all edges bidirectionally.
     * Excludes edges of the excluded predicate type.
     * Loop avoidance is handled by cost-gating in the main search, not here.
     */
    private static List<NeighborEdge> neighborsOf(ReasoningGraph graph, String nodeId,
                                                    String excludePredicate) {
        List<NeighborEdge> result = new ArrayList<>();
        // Forward edges: nodeId is the source
        for (GraphRelation r : graph.outgoing(nodeId)) {
            if (!isExcluded(r.type(), excludePredicate)) {
                result.add(new NeighborEdge(r.targetId(), r.type()));
            }
        }
        // Reverse edges: nodeId is the target; the other end is r.sourceId()
        for (GraphRelation r : graph.incoming(nodeId)) {
            if (!isExcluded(r.type(), excludePredicate)) {
                result.add(new NeighborEdge(r.sourceId(), r.type()));
            }
        }
        return result;
    }

    private static boolean isExcluded(String edgeType, String excludePredicate) {
        if (excludePredicate == null || excludePredicate.isBlank()) return false;
        return excludePredicate.equalsIgnoreCase(edgeType);
    }

    // ── Internal types ─────────────────────────────────────────────────────────

    private record NeighborEdge(String neighborId, String edgeType) {}

    private static final class SearchState implements Comparable<SearchState> {
        final double accumulatedCost;
        final String nodeId;
        final int depth;
        final List<String> pathNodes;
        final List<String> edgeTypes;

        SearchState(double accumulatedCost, String nodeId, int depth,
                    List<String> pathNodes, List<String> edgeTypes) {
            this.accumulatedCost = accumulatedCost;
            this.nodeId = nodeId;
            this.depth = depth;
            this.pathNodes = pathNodes;
            this.edgeTypes = edgeTypes;
        }

        @Override
        public int compareTo(SearchState other) {
            return Double.compare(this.accumulatedCost, other.accumulatedCost);
        }
    }
}
