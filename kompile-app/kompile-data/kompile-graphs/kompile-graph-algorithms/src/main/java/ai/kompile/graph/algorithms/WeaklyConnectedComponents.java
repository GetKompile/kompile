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
import ai.kompile.knowledgegraph.matrix.algorithms.MatrixGraphAlgorithms;

import java.util.*;

/**
 * Weakly Connected Components. For small graphs (≤ {@link AdjacencyView#DENSE_NODE_CAP} nodes)
 * delegates to the matrix BFS in {@link MatrixGraphAlgorithms#findConnectedComponents(org.nd4j.linalg.api.ndarray.INDArray, List)}.
 * For large graphs uses a pure adjacency-list BFS (no dense {@code [n×n]} matrix allocated).
 */
public final class WeaklyConnectedComponents {

    private WeaklyConnectedComponents() {}

    public static Map<String, Integer> compute(AdjacencyView view) {
        List<Set<String>> components;
        if (view.isLarge()) {
            // Adjacency-list BFS for large graphs — symmetrize by scanning out-neighbors bidirectionally.
            components = findComponentsAdjacencyList(view);
        } else {
            components = MatrixGraphAlgorithms.findConnectedComponents(
                    view.toAdjacencyMatrix(), view.nodeIds());
        }
        Map<String, Integer> assignments = new HashMap<>();
        for (int cid = 0; cid < components.size(); cid++) {
            for (String nodeId : components.get(cid)) {
                assignments.put(nodeId, cid);
            }
        }
        return assignments;
    }

    /**
     * Pure adjacency-list WCC BFS for large {@link AdjacencyView} instances.
     * Builds an undirected neighbor map in O(E) then runs BFS in O(V+E) — no dense matrix.
     */
    private static List<Set<String>> findComponentsAdjacencyList(AdjacencyView view) {
        List<String> nodeIds = view.nodeIds();
        int n = nodeIds.size();

        // Build undirected adjacency: each directed edge (u→v) contributes to both adj[u] and adj[v].
        Map<String, Set<String>> undirected = new HashMap<>(n * 2);
        for (String id : nodeIds) {
            undirected.put(id, new HashSet<>());
        }
        for (String u : nodeIds) {
            for (String v : view.outNeighbors(u)) {
                if (undirected.containsKey(v)) {
                    undirected.get(u).add(v);
                    undirected.get(v).add(u);
                }
            }
        }

        Set<String> visited = new HashSet<>(n * 2);
        List<Set<String>> components = new ArrayList<>();
        for (String startId : nodeIds) {
            if (visited.contains(startId)) continue;
            Set<String> component = new HashSet<>();
            Deque<String> queue = new ArrayDeque<>();
            queue.add(startId);
            visited.add(startId);
            while (!queue.isEmpty()) {
                String cur = queue.poll();
                component.add(cur);
                for (String nb : undirected.getOrDefault(cur, Collections.emptySet())) {
                    if (!visited.contains(nb)) {
                        visited.add(nb);
                        queue.add(nb);
                    }
                }
            }
            components.add(component);
        }
        return components;
    }
}
