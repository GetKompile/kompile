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
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class JaccardNodeSimilarity {

    public record SimilarityPair(String nodeA, String nodeB, double score) {}

    private JaccardNodeSimilarity() {}

    public static double similarity(AdjacencyView view, String a, String b) {
        if (a == null || b == null || a.equals(b)) return a == null || b == null ? 0.0 : 1.0;
        Set<String> na = view.neighbors(a);
        Set<String> nb = view.neighbors(b);
        if (na.isEmpty() && nb.isEmpty()) return 0.0;
        Set<String> intersection = new HashSet<>(na);
        intersection.retainAll(nb);
        Set<String> union = new HashSet<>(na);
        union.addAll(nb);
        if (union.isEmpty()) return 0.0;
        return (double) intersection.size() / union.size();
    }

    public static List<SimilarityPair> topK(AdjacencyView view, int k, double threshold) {
        List<String> nodes = view.nodeIds();
        List<SimilarityPair> all = new ArrayList<>();
        for (int i = 0; i < nodes.size(); i++) {
            for (int j = i + 1; j < nodes.size(); j++) {
                double s = similarity(view, nodes.get(i), nodes.get(j));
                if (s >= threshold) {
                    all.add(new SimilarityPair(nodes.get(i), nodes.get(j), s));
                }
            }
        }
        all.sort((p1, p2) -> Double.compare(p2.score, p1.score));
        return all.size() > k ? all.subList(0, k) : all;
    }

    public static Map<String, Double> similarityToAll(AdjacencyView view, String node) {
        java.util.Map<String, Double> out = new java.util.LinkedHashMap<>();
        for (String other : view.nodeIds()) {
            if (other.equals(node)) continue;
            out.put(other, similarity(view, node, other));
        }
        return out;
    }
}
