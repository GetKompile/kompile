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
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * Brandes' algorithm for betweenness centrality. Edges treated as undirected and unweighted.
 */
public final class BetweennessCentrality {

    private BetweennessCentrality() {}

    public static Map<String, Double> compute(AdjacencyView view) {
        return compute(view, -1, 0L);
    }

    /**
     * @param sampleSize -1 for exact (all source nodes), or a positive integer for sampling.
     */
    public static Map<String, Double> compute(AdjacencyView view, int sampleSize, long randomSeed) {
        List<String> nodes = view.nodeIds();
        int n = nodes.size();
        if (n == 0) return Map.of();

        Map<String, Double> betweenness = new HashMap<>();
        for (String s : nodes) betweenness.put(s, 0.0);

        List<String> sources;
        if (sampleSize > 0 && sampleSize < n) {
            Random rng = new Random(randomSeed);
            List<String> shuffled = new ArrayList<>(nodes);
            Collections.shuffle(shuffled, rng);
            sources = shuffled.subList(0, sampleSize);
        } else {
            sources = nodes;
        }

        for (String s : sources) {
            Deque<String> stack = new ArrayDeque<>();
            Map<String, List<String>> predecessors = new HashMap<>();
            Map<String, Long> sigma = new HashMap<>();
            Map<String, Integer> distance = new HashMap<>();
            for (String v : nodes) {
                predecessors.put(v, new ArrayList<>());
                sigma.put(v, 0L);
                distance.put(v, -1);
            }
            sigma.put(s, 1L);
            distance.put(s, 0);

            Deque<String> queue = new ArrayDeque<>();
            queue.add(s);
            while (!queue.isEmpty()) {
                String v = queue.poll();
                stack.push(v);
                for (String w : view.neighbors(v)) {
                    if (distance.get(w) < 0) {
                        distance.put(w, distance.get(v) + 1);
                        queue.add(w);
                    }
                    if (distance.get(w) == distance.get(v) + 1) {
                        sigma.merge(w, sigma.get(v), Long::sum);
                        predecessors.get(w).add(v);
                    }
                }
            }

            Map<String, Double> delta = new HashMap<>();
            for (String v : nodes) delta.put(v, 0.0);
            while (!stack.isEmpty()) {
                String w = stack.pop();
                for (String v : predecessors.get(w)) {
                    double contribution = ((double) sigma.get(v) / sigma.get(w)) * (1 + delta.get(w));
                    delta.merge(v, contribution, Double::sum);
                }
                if (!w.equals(s)) {
                    betweenness.merge(w, delta.get(w), Double::sum);
                }
            }
        }

        // Scale for sampling and normalize for undirected (divide by 2)
        double scale = sampleSize > 0 && sampleSize < n ? (double) n / sampleSize : 1.0;
        Map<String, Double> result = new LinkedHashMap<>();
        for (Map.Entry<String, Double> e : betweenness.entrySet()) {
            result.put(e.getKey(), (e.getValue() * scale) / 2.0);
        }
        return result.entrySet().stream()
                .sorted(Map.Entry.<String, Double>comparingByValue().reversed())
                .collect(LinkedHashMap::new,
                         (acc, en) -> acc.put(en.getKey(), en.getValue()),
                         LinkedHashMap::putAll);
    }
}
