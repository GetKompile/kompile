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
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;

public final class ShortestPathAlgorithm {

    public record PathResult(List<String> path, double length, boolean found) {
        public static PathResult notFound() {
            return new PathResult(List.of(), Double.POSITIVE_INFINITY, false);
        }
    }

    private ShortestPathAlgorithm() {}

    public static PathResult bfs(AdjacencyView view, String from, String to) {
        if (from == null || to == null) return PathResult.notFound();
        if (from.equals(to)) return new PathResult(List.of(from), 0.0, true);
        if (view.indexOf(from) < 0 || view.indexOf(to) < 0) return PathResult.notFound();

        Map<String, String> parents = new HashMap<>();
        Deque<String> queue = new ArrayDeque<>();
        queue.add(from);
        parents.put(from, null);

        while (!queue.isEmpty()) {
            String current = queue.poll();
            for (String neighbor : view.outNeighbors(current)) {
                if (parents.containsKey(neighbor)) continue;
                parents.put(neighbor, current);
                if (neighbor.equals(to)) {
                    return new PathResult(reconstruct(parents, to), pathLength(parents, to), true);
                }
                queue.add(neighbor);
            }
        }
        return PathResult.notFound();
    }

    public static PathResult dijkstra(AdjacencyView view, String from, String to) {
        if (from == null || to == null) return PathResult.notFound();
        if (from.equals(to)) return new PathResult(List.of(from), 0.0, true);
        if (view.indexOf(from) < 0 || view.indexOf(to) < 0) return PathResult.notFound();

        Map<String, Double> dist = new HashMap<>();
        Map<String, String> parents = new HashMap<>();
        for (String n : view.nodeIds()) dist.put(n, Double.POSITIVE_INFINITY);
        dist.put(from, 0.0);
        parents.put(from, null);

        PriorityQueue<String> pq = new PriorityQueue<>((a, b) -> Double.compare(dist.get(a), dist.get(b)));
        pq.add(from);

        while (!pq.isEmpty()) {
            String current = pq.poll();
            if (current.equals(to)) break;
            double currentDist = dist.get(current);
            for (String neighbor : view.outNeighbors(current)) {
                double w = view.weight(current, neighbor);
                if (w <= 0) w = 1.0;
                double cost = 1.0 / w; // higher weight => stronger relationship => shorter cost
                double alt = currentDist + cost;
                if (alt < dist.getOrDefault(neighbor, Double.POSITIVE_INFINITY)) {
                    dist.put(neighbor, alt);
                    parents.put(neighbor, current);
                    pq.add(neighbor);
                }
            }
        }

        Double finalDist = dist.get(to);
        if (finalDist == null || finalDist.isInfinite()) return PathResult.notFound();
        return new PathResult(reconstruct(parents, to), finalDist, true);
    }

    private static List<String> reconstruct(Map<String, String> parents, String to) {
        List<String> path = new ArrayList<>();
        String cur = to;
        while (cur != null) {
            path.add(cur);
            cur = parents.get(cur);
        }
        Collections.reverse(path);
        return path;
    }

    private static double pathLength(Map<String, String> parents, String to) {
        double len = 0;
        String cur = to;
        while (parents.get(cur) != null) {
            len++;
            cur = parents.get(cur);
        }
        return len;
    }
}
