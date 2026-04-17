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

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Weakly Connected Components. Delegates to {@link MatrixGraphAlgorithms#findConnectedComponents}
 * which runs BFS over the symmetrized adjacency matrix.
 */
public final class WeaklyConnectedComponents {

    private WeaklyConnectedComponents() {}

    public static Map<String, Integer> compute(AdjacencyView view) {
        List<Set<String>> components = MatrixGraphAlgorithms.findConnectedComponents(
                view.toAdjacencyMatrix(), view.nodeIds());
        Map<String, Integer> assignments = new HashMap<>();
        for (int cid = 0; cid < components.size(); cid++) {
            for (String nodeId : components.get(cid)) {
                assignments.put(nodeId, cid);
            }
        }
        return assignments;
    }
}
