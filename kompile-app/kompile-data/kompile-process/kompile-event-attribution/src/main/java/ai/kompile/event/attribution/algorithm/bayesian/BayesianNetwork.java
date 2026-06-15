/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 */
package ai.kompile.event.attribution.algorithm.bayesian;

import java.util.*;
import java.util.stream.Collectors;

/**
 * A Bayesian network: a directed acyclic graph (DAG) of {@link BayesianNode}s
 * with conditional probability tables (CPTs).
 *
 * <p>Provides network-level operations:</p>
 * <ul>
 *   <li>Node management (add, lookup by variable name or KG node ID)</li>
 *   <li>Parent-child edge creation with cycle detection</li>
 *   <li>Topological ordering for variable elimination</li>
 *   <li>Children lookup for message passing</li>
 * </ul>
 */
public class BayesianNetwork {

    private final Map<String, BayesianNode> nodesByVariable = new LinkedHashMap<>();
    private final Map<String, String> kgNodeIdToVariable = new HashMap<>();
    private final Map<String, List<String>> childrenMap = new HashMap<>();

    /**
     * Add a node to the network.
     */
    public void addNode(BayesianNode node) {
        nodesByVariable.put(node.getVariableName(), node);
        kgNodeIdToVariable.put(node.getKgNodeId(), node.getVariableName());
        childrenMap.putIfAbsent(node.getVariableName(), new ArrayList<>());
    }

    /**
     * Add a directed edge from parent to child.
     *
     * @throws IllegalArgumentException if the edge would create a cycle
     */
    public void addEdge(String parentVariable, String childVariable) {
        BayesianNode parent = nodesByVariable.get(parentVariable);
        BayesianNode child = nodesByVariable.get(childVariable);
        if (parent == null) throw new IllegalArgumentException("Unknown parent: " + parentVariable);
        if (child == null) throw new IllegalArgumentException("Unknown child: " + childVariable);

        // Check for cycle: child must not be an ancestor of parent
        if (isAncestor(childVariable, parentVariable)) {
            throw new IllegalArgumentException(
                    "Adding edge " + parentVariable + " → " + childVariable + " would create a cycle");
        }

        child.addParent(parent);
        childrenMap.computeIfAbsent(parentVariable, k -> new ArrayList<>()).add(childVariable);
    }

    /**
     * Get a node by its variable name.
     */
    public BayesianNode getNode(String variableName) {
        return nodesByVariable.get(variableName);
    }

    /**
     * Get a node by its original KG node ID.
     */
    public BayesianNode getNodeByKgId(String kgNodeId) {
        String varName = kgNodeIdToVariable.get(kgNodeId);
        return varName != null ? nodesByVariable.get(varName) : null;
    }

    /**
     * Get the variable name corresponding to a KG node ID.
     */
    public String getVariableForKgNodeId(String kgNodeId) {
        return kgNodeIdToVariable.get(kgNodeId);
    }

    /**
     * Get all nodes in the network.
     */
    public Collection<BayesianNode> getNodes() {
        return Collections.unmodifiableCollection(nodesByVariable.values());
    }

    /**
     * Get the children of a node.
     */
    public List<BayesianNode> getChildren(String variableName) {
        List<String> childVars = childrenMap.getOrDefault(variableName, List.of());
        return childVars.stream()
                .map(nodesByVariable::get)
                .filter(Objects::nonNull)
                .toList();
    }

    /**
     * Number of nodes in the network.
     */
    public int size() {
        return nodesByVariable.size();
    }

    /**
     * Compute a topological ordering of the network nodes.
     * Parents always appear before their children.
     *
     * @return list of variable names in topological order
     */
    public List<String> topologicalOrder() {
        Map<String, Integer> inDegree = new HashMap<>();
        for (BayesianNode node : nodesByVariable.values()) {
            inDegree.putIfAbsent(node.getVariableName(), 0);
            for (BayesianNode parent : node.getParents()) {
                inDegree.merge(node.getVariableName(), 1, Integer::sum);
            }
        }

        Queue<String> queue = new ArrayDeque<>();
        for (Map.Entry<String, Integer> entry : inDegree.entrySet()) {
            if (entry.getValue() == 0) queue.add(entry.getKey());
        }

        List<String> order = new ArrayList<>();
        while (!queue.isEmpty()) {
            String var = queue.poll();
            order.add(var);
            for (String child : childrenMap.getOrDefault(var, List.of())) {
                int newDegree = inDegree.merge(child, -1, Integer::sum);
                if (newDegree == 0) queue.add(child);
            }
        }

        if (order.size() != nodesByVariable.size()) {
            throw new IllegalStateException("Cycle detected in Bayesian network — " +
                    "topological sort returned " + order.size() + " of " + nodesByVariable.size() + " nodes");
        }
        return order;
    }

    /**
     * Get the Markov blanket of a node: its parents, children, and co-parents
     * (other parents of its children).
     */
    public Set<String> getMarkovBlanket(String variableName) {
        BayesianNode node = nodesByVariable.get(variableName);
        if (node == null) return Set.of();

        Set<String> blanket = new LinkedHashSet<>();

        // Parents
        for (BayesianNode parent : node.getParents()) {
            blanket.add(parent.getVariableName());
        }

        // Children and co-parents
        for (String childVar : childrenMap.getOrDefault(variableName, List.of())) {
            blanket.add(childVar);
            BayesianNode child = nodesByVariable.get(childVar);
            if (child != null) {
                for (BayesianNode coParent : child.getParents()) {
                    blanket.add(coParent.getVariableName());
                }
            }
        }

        blanket.remove(variableName); // Don't include self
        return blanket;
    }

    /**
     * Get all CPTs as factors for inference.
     */
    public List<Factor> getAllFactors() {
        return nodesByVariable.values().stream()
                .map(BayesianNode::getCpt)
                .filter(Objects::nonNull)
                .toList();
    }

    /**
     * Summary statistics about the network.
     */
    public Map<String, Object> getStatistics() {
        int edgeCount = nodesByVariable.values().stream()
                .mapToInt(n -> n.getParents().size())
                .sum();
        int rootCount = (int) nodesByVariable.values().stream()
                .filter(BayesianNode::isRoot)
                .count();
        int leafCount = (int) nodesByVariable.values().stream()
                .filter(n -> childrenMap.getOrDefault(n.getVariableName(), List.of()).isEmpty())
                .count();

        Map<String, Object> stats = new LinkedHashMap<>();
        stats.put("nodeCount", nodesByVariable.size());
        stats.put("edgeCount", edgeCount);
        stats.put("rootNodes", rootCount);
        stats.put("leafNodes", leafCount);
        stats.put("variables", nodesByVariable.keySet().stream().toList());
        return stats;
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // CYCLE DETECTION
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * Check if 'ancestor' is an ancestor of 'descendant' via parent edges.
     */
    private boolean isAncestor(String ancestor, String descendant) {
        if (ancestor.equals(descendant)) return true;

        Set<String> visited = new HashSet<>();
        Queue<String> queue = new ArrayDeque<>();
        queue.add(descendant);

        while (!queue.isEmpty()) {
            String current = queue.poll();
            if (visited.contains(current)) continue;
            visited.add(current);

            BayesianNode node = nodesByVariable.get(current);
            if (node == null) continue;

            for (BayesianNode parent : node.getParents()) {
                if (parent.getVariableName().equals(ancestor)) return true;
                queue.add(parent.getVariableName());
            }
        }
        return false;
    }
}
