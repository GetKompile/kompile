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

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class BfsTraversal {

    private BfsTraversal() {}

    /**
     * Breadth-first traversal returning nodes grouped by depth level.
     * Level 0 contains the start node; level k contains all nodes first
     * reached at distance k.
     */
    public static Map<Integer, List<String>> traverse(AdjacencyView view, String start, int maxDepth) {
        Map<Integer, List<String>> levels = new LinkedHashMap<>();
        if (start == null || view.indexOf(start) < 0 || maxDepth < 0) return levels;

        Set<String> visited = new HashSet<>();
        Deque<String> frontier = new ArrayDeque<>();
        frontier.add(start);
        visited.add(start);

        for (int depth = 0; depth <= maxDepth && !frontier.isEmpty(); depth++) {
            List<String> levelNodes = new ArrayList<>(frontier);
            levels.put(depth, levelNodes);

            Deque<String> next = new ArrayDeque<>();
            for (String node : frontier) {
                for (String neighbor : view.neighbors(node)) {
                    if (visited.add(neighbor)) {
                        next.add(neighbor);
                    }
                }
            }
            frontier = next;
        }
        return levels;
    }
}
