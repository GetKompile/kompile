/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 */
package ai.kompile.graph.algorithms;

import ai.kompile.graph.algorithms.adjacency.AdjacencyView;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Louvain modularity-maximizing community detection.
 *
 * <p>Treats edges as undirected; if (u,v) and (v,u) both exist, weights sum.
 * Returns a map from nodeId to a stable communityId in [0, k).
 */
public final class LouvainCommunityDetection {

    private LouvainCommunityDetection() {}

    public static Map<String, Integer> compute(AdjacencyView view) {
        return compute(view, 20);
    }

    public static Map<String, Integer> compute(AdjacencyView view, int maxIterations) {
        List<String> nodes = view.nodeIds();
        int n = nodes.size();
        Map<String, Integer> assignments = new LinkedHashMap<>();
        if (n == 0) return assignments;

        // Build symmetric weighted adjacency (sum of in/out weights for undirected view)
        Map<String, Map<String, Double>> adj = new HashMap<>();
        Map<String, Double> nodeWeight = new HashMap<>();
        double totalWeight = 0.0;
        for (String u : nodes) {
            adj.put(u, new HashMap<>());
            nodeWeight.put(u, 0.0);
        }
        for (String u : nodes) {
            for (String v : view.outNeighbors(u)) {
                double w = view.weight(u, v);
                if (w <= 0) w = 1.0;
                adj.get(u).merge(v, w, Double::sum);
                adj.get(v).merge(u, w, Double::sum);
                nodeWeight.merge(u, w, Double::sum);
                nodeWeight.merge(v, w, Double::sum);
                totalWeight += w;
            }
        }
        // Each edge contributes to both endpoints; m = total edge weight
        double m = totalWeight;
        if (m <= 0) {
            for (int i = 0; i < n; i++) assignments.put(nodes.get(i), i);
            return assignments;
        }

        // Initialize each node in its own community
        Map<String, Integer> community = new HashMap<>();
        Map<Integer, Double> communityTotal = new HashMap<>();
        for (int i = 0; i < n; i++) {
            String node = nodes.get(i);
            community.put(node, i);
            communityTotal.put(i, nodeWeight.get(node));
        }

        boolean improved = true;
        int iter = 0;
        while (improved && iter < maxIterations) {
            improved = false;
            iter++;
            for (String u : nodes) {
                int currentComm = community.get(u);
                double ku = nodeWeight.get(u);
                Map<Integer, Double> commWeights = neighborCommunityWeights(u, adj, community);

                // Remove u from its community
                communityTotal.merge(currentComm, -ku, Double::sum);

                int bestComm = currentComm;
                double bestGain = 0.0;
                for (Map.Entry<Integer, Double> e : commWeights.entrySet()) {
                    int candidate = e.getKey();
                    double kInComm = e.getValue();
                    double sigmaTot = communityTotal.getOrDefault(candidate, 0.0);
                    double gain = kInComm - sigmaTot * ku / (2.0 * m);
                    if (gain > bestGain) {
                        bestGain = gain;
                        bestComm = candidate;
                    }
                }

                community.put(u, bestComm);
                communityTotal.merge(bestComm, ku, Double::sum);
                if (bestComm != currentComm) improved = true;
            }
        }

        // Renumber communities to dense [0, k) range, preserving node order
        Map<Integer, Integer> rename = new HashMap<>();
        int next = 0;
        for (String node : nodes) {
            int c = community.get(node);
            Integer mapped = rename.get(c);
            if (mapped == null) {
                mapped = next++;
                rename.put(c, mapped);
            }
            assignments.put(node, mapped);
        }
        return assignments;
    }

    /**
     * Compute the modularity score for an assignment. Useful for tests.
     */
    public static double modularity(AdjacencyView view, Map<String, Integer> assignments) {
        Map<String, Map<String, Double>> adj = new HashMap<>();
        Map<String, Double> nodeWeight = new HashMap<>();
        double totalWeight = 0.0;
        for (String u : view.nodeIds()) {
            adj.put(u, new HashMap<>());
            nodeWeight.put(u, 0.0);
        }
        for (String u : view.nodeIds()) {
            for (String v : view.outNeighbors(u)) {
                double w = view.weight(u, v);
                if (w <= 0) w = 1.0;
                adj.get(u).merge(v, w, Double::sum);
                adj.get(v).merge(u, w, Double::sum);
                nodeWeight.merge(u, w, Double::sum);
                nodeWeight.merge(v, w, Double::sum);
                totalWeight += w;
            }
        }
        double m = totalWeight;
        if (m <= 0) return 0.0;

        double q = 0.0;
        for (String i : view.nodeIds()) {
            for (String j : view.nodeIds()) {
                if (!assignments.get(i).equals(assignments.get(j))) continue;
                double aij = adj.get(i).getOrDefault(j, 0.0);
                double ki = nodeWeight.get(i);
                double kj = nodeWeight.get(j);
                q += (aij - (ki * kj) / (2.0 * m));
            }
        }
        return q / (2.0 * m);
    }

    private static Map<Integer, Double> neighborCommunityWeights(String u,
                                                                 Map<String, Map<String, Double>> adj,
                                                                 Map<String, Integer> community) {
        Map<Integer, Double> result = new HashMap<>();
        Map<String, Double> neighbors = adj.get(u);
        if (neighbors == null) return result;
        for (Map.Entry<String, Double> e : neighbors.entrySet()) {
            String v = e.getKey();
            if (v.equals(u)) continue;
            int comm = community.get(v);
            result.merge(comm, e.getValue(), Double::sum);
        }
        return result;
    }

    /**
     * Group node IDs by their community assignment, preserving insertion order within each community.
     */
    public static Map<Integer, List<String>> groupByCommunity(Map<String, Integer> assignments) {
        Map<Integer, List<String>> grouped = new LinkedHashMap<>();
        for (Map.Entry<String, Integer> e : assignments.entrySet()) {
            grouped.computeIfAbsent(e.getValue(), k -> new ArrayList<>()).add(e.getKey());
        }
        return grouped;
    }

    /**
     * Returns the set of distinct community IDs in the assignment.
     */
    public static Set<Integer> distinctCommunities(Map<String, Integer> assignments) {
        return new HashSet<>(assignments.values());
    }
}
