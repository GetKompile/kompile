/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */
package ai.kompile.knowledgegraph.service;

import ai.kompile.knowledgegraph.domain.GraphEdge;
import ai.kompile.knowledgegraph.domain.GraphNode;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Optional storage-backed read capability for callers that must not materialize an entire graph.
 *
 * <p>The ordinary {@link KnowledgeGraphService} API remains the compatibility surface for stores
 * that expose whole collections. Large matrix/vector stores implement this capability so exact
 * node lookup and neighborhood expansion stay bounded and use their persisted topology directly.</p>
 */
public interface BoundedKnowledgeGraphReader {

    enum Direction { OUTGOING, INCOMING, BOTH }

    /**
     * A bounded incident-edge result. {@code truncated} means additional matching edges exist and
     * callers must not interpret absence from {@code edges} as proof that an edge does not exist.
     */
    record IncidentEdges(List<GraphEdge> edges, boolean truncated) {
        public IncidentEdges {
            edges = edges == null ? List.of() : List.copyOf(edges);
        }
    }

    /**
     * A bounded neighborhood: the nodes found in scope, in discovery order, and the edges whose
     * endpoints are both among them. {@code truncated} means a budget cut the traversal short, so
     * callers must not read absence as proof that a node or edge does not exist.
     */
    record Neighborhood(List<GraphNode> nodes, List<GraphEdge> edges, boolean truncated) {
        public Neighborhood {
            nodes = nodes == null ? List.of() : List.copyOf(nodes);
            edges = edges == null ? List.of() : List.copyOf(edges);
        }
    }

    /** The store's bounded reads, or point reads over its compatibility API when it has none. */
    static BoundedKnowledgeGraphReader of(KnowledgeGraphService store) {
        if (store instanceof BoundedKnowledgeGraphReader bounded) return bounded;
        return new BoundedKnowledgeGraphReader() {
            @Override
            public Optional<GraphNode> getNodeInScope(String nodeId, Long factSheetId) {
                return store.getNode(nodeId)
                        .filter(node -> factSheetId == null || factSheetId.equals(node.getFactSheetId()));
            }

            @Override
            public IncidentEdges getIncidentEdges(
                    String nodeId, Long factSheetId, Direction direction, int maxEdges) {
                // Whole-collection compatibility methods offer no memory bound. Returning an explicitly
                // truncated empty batch is safer than materializing the complete graph behind a bounded API.
                return new IncidentEdges(List.of(), true);
            }
        };
    }

    /** Exact node-id lookup within the selected fact-sheet graph; null selects the global scope. */
    Optional<GraphNode> getNodeInScope(String nodeId, Long factSheetId);

    /**
     * Read at most {@code maxEdges} incident edges in the requested direction without loading the
     * graph matrix or returning an unbounded collection.
     */
    IncidentEdges getIncidentEdges(
            String nodeId, Long factSheetId, Direction direction, int maxEdges);

    /**
     * Breadth-first bounded neighborhood. Every seed is point-loaded; the traversal starts only from
     * the {@code expansionSeedIds} found in scope and follows at most {@code maxDepth} hops. A seed or
     * neighbor beyond {@code maxNodes}, or an incident read cut by the {@code maxEdges} budget, marks
     * the result truncated. Once the edge budget is spent, nodes already reached are still loaded so
     * the edges collected to them are kept.
     *
     * <p>This default costs one point read per node and one incident read per expanded node. A store
     * behind a process boundary overrides it to run the whole traversal where the data lives, in one
     * call.</p>
     */
    default Neighborhood getNeighborhood(Long factSheetId,
                                         Collection<String> seedIds,
                                         Collection<String> expansionSeedIds,
                                         int maxDepth,
                                         int maxNodes,
                                         Direction direction,
                                         int maxEdges) {
        Direction traversal = direction == null ? Direction.BOTH : direction;
        Set<String> visited = new LinkedHashSet<>();
        Map<String, GraphNode> nodes = new LinkedHashMap<>();
        Map<String, GraphEdge> edges = new LinkedHashMap<>();
        ArrayDeque<Map.Entry<String, Integer>> queue = new ArrayDeque<>();
        boolean truncated = false;
        for (String seed : seedIds == null ? List.<String>of() : seedIds) {
            if (seed == null || seed.isBlank() || visited.contains(seed)) continue;
            if (visited.size() >= maxNodes) {
                truncated = true;
                continue;
            }
            visited.add(seed);
            getNodeInScope(seed, factSheetId).ifPresent(node -> putNode(nodes, node));
        }
        Set<String> queued = new HashSet<>();
        for (String root : expansionSeedIds == null ? List.<String>of() : expansionSeedIds) {
            if (root == null || root.isBlank()) continue;
            if (!visited.contains(root)) {
                if (visited.size() >= maxNodes) {
                    truncated = true;
                    continue;
                }
                visited.add(root);
                getNodeInScope(root, factSheetId).ifPresent(node -> putNode(nodes, node));
            }
            if (nodes.containsKey(root) && queued.add(root)) queue.addLast(Map.entry(root, 0));
        }

        while (!queue.isEmpty()) {
            Map.Entry<String, Integer> current = queue.removeFirst();
            String nodeId = current.getKey();
            if (!nodes.containsKey(nodeId)) {
                Optional<GraphNode> node = getNodeInScope(nodeId, factSheetId);
                if (node.isEmpty()) continue;
                putNode(nodes, node.get());
            }
            if (current.getValue() >= maxDepth) continue;
            int remainingEdges = maxEdges - edges.size();
            if (remainingEdges <= 0) {
                truncated = true;
                continue;
            }
            IncidentEdges incident = getIncidentEdges(nodeId, factSheetId, traversal, remainingEdges);
            truncated |= incident.truncated();
            for (GraphEdge edge : incident.edges()) {
                if (edge == null) continue;
                String source = edge.getSourceNodeId();
                String target = edge.getTargetNodeId();
                if (source == null || target == null) continue;
                String neighbor = nodeId.equals(source) ? target : nodeId.equals(target) ? source : null;
                if (neighbor == null) continue;
                if (!visited.contains(neighbor)) {
                    if (visited.size() >= maxNodes) {
                        truncated = true;
                        continue;
                    }
                    visited.add(neighbor);
                    queue.addLast(Map.entry(neighbor, current.getValue() + 1));
                }
                String key = edge.getEdgeId() != null ? edge.getEdgeId()
                        : source + "\n" + target + "\n" + edge.getRelationType();
                edges.putIfAbsent(key, edge);
            }
        }

        List<GraphEdge> closed = new ArrayList<>();
        for (GraphEdge edge : edges.values()) {
            if (nodes.containsKey(edge.getSourceNodeId()) && nodes.containsKey(edge.getTargetNodeId())) {
                closed.add(edge);
            }
        }
        return new Neighborhood(new ArrayList<>(nodes.values()), closed, truncated);
    }

    private static void putNode(Map<String, GraphNode> nodes, GraphNode node) {
        if (node != null && node.getNodeId() != null) nodes.putIfAbsent(node.getNodeId(), node);
    }
}
