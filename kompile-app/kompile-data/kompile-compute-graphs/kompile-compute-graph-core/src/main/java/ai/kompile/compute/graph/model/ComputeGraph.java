package ai.kompile.compute.graph.model;

import com.fasterxml.jackson.annotation.JsonTypeInfo;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;

/**
 * A directed acyclic graph (DAG) of compute nodes connected by edges.
 * The graph defines the complete computation workflow — each node
 * has attached code, and edges define data flow between nodes.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonTypeInfo(use = JsonTypeInfo.Id.CLASS, property = "@class")
public class ComputeGraph {

    private String id;
    private String name;
    private String description;
    private String version;

    @Builder.Default
    private List<ComputeNode> nodes = new ArrayList<>();

    @Builder.Default
    private List<ComputeEdge> edges = new ArrayList<>();

    /**
     * Global parameters available to all nodes in the graph.
     */
    @Builder.Default
    private Map<String, Object> globalParameters = new HashMap<>();

    /**
     * Metadata (author, tags, creation time, etc.)
     */
    @Builder.Default
    private Map<String, Object> metadata = new HashMap<>();

    @Builder.Default
    private Instant createdAt = Instant.now();

    private Instant updatedAt;

    /**
     * Find all root nodes (nodes with no incoming edges).
     */
    public List<ComputeNode> getRootNodes() {
        Set<String> nodesWithIncoming = edges.stream()
                .map(ComputeEdge::getTargetNodeId)
                .collect(Collectors.toSet());
        return nodes.stream()
                .filter(n -> !nodesWithIncoming.contains(n.getId()))
                .collect(Collectors.toList());
    }

    /**
     * Find all terminal nodes (nodes with no outgoing edges).
     */
    public List<ComputeNode> getTerminalNodes() {
        Set<String> nodesWithOutgoing = edges.stream()
                .map(ComputeEdge::getSourceNodeId)
                .collect(Collectors.toSet());
        return nodes.stream()
                .filter(n -> !nodesWithOutgoing.contains(n.getId()))
                .collect(Collectors.toList());
    }

    /**
     * Get outgoing edges for a given node, sorted by priority.
     */
    public List<ComputeEdge> getOutgoingEdges(String nodeId) {
        return edges.stream()
                .filter(e -> e.getSourceNodeId().equals(nodeId))
                .sorted(Comparator.comparingInt(ComputeEdge::getPriority))
                .collect(Collectors.toList());
    }

    /**
     * Get incoming edges for a given node.
     */
    public List<ComputeEdge> getIncomingEdges(String nodeId) {
        return edges.stream()
                .filter(e -> e.getTargetNodeId().equals(nodeId))
                .collect(Collectors.toList());
    }

    /**
     * Find a node by ID.
     */
    public Optional<ComputeNode> findNode(String nodeId) {
        return nodes.stream()
                .filter(n -> n.getId().equals(nodeId))
                .findFirst();
    }

    /**
     * Compute topological ordering of nodes for execution.
     * Throws IllegalStateException if cycles are detected.
     */
    public List<ComputeNode> topologicalSort() {
        Map<String, Integer> inDegree = new HashMap<>();
        Map<String, ComputeNode> nodeMap = new HashMap<>();

        for (ComputeNode node : nodes) {
            inDegree.put(node.getId(), 0);
            nodeMap.put(node.getId(), node);
        }

        for (ComputeEdge edge : edges) {
            inDegree.merge(edge.getTargetNodeId(), 1, Integer::sum);
        }

        Queue<String> queue = new LinkedList<>();
        for (Map.Entry<String, Integer> entry : inDegree.entrySet()) {
            if (entry.getValue() == 0) {
                queue.add(entry.getKey());
            }
        }

        List<ComputeNode> sorted = new ArrayList<>();
        while (!queue.isEmpty()) {
            String nodeId = queue.poll();
            sorted.add(nodeMap.get(nodeId));
            for (ComputeEdge edge : getOutgoingEdges(nodeId)) {
                int newDegree = inDegree.merge(edge.getTargetNodeId(), -1, Integer::sum);
                if (newDegree == 0) {
                    queue.add(edge.getTargetNodeId());
                }
            }
        }

        if (sorted.size() != nodes.size()) {
            throw new IllegalStateException("Cycle detected in compute graph — cannot determine execution order");
        }

        return sorted;
    }
}
