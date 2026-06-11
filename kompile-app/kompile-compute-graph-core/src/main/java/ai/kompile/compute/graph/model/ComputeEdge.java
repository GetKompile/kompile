package ai.kompile.compute.graph.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.HashMap;
import java.util.Map;

/**
 * A directed edge between two compute nodes, representing data flow.
 * Edges can optionally carry a condition that must be satisfied for
 * data to flow along this path (enables conditional branching).
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ComputeEdge {

    private String id;

    /**
     * Source node ID — where data flows from.
     */
    private String sourceNodeId;

    /**
     * Target node ID — where data flows to.
     */
    private String targetNodeId;

    /**
     * Optional condition expression (SpEL) evaluated against the source node's output.
     * If null/empty, the edge is unconditional (always flows).
     * Example: "#{output['score'] > 0.8}"
     */
    private String condition;

    /**
     * Mapping of source output names to target input names.
     * Key = output variable name from source node.
     * Value = input variable name expected by target node.
     * If empty, all source outputs are passed through with their original names.
     */
    @Builder.Default
    private Map<String, String> dataMapping = new HashMap<>();

    /**
     * Priority for ordering when multiple edges leave the same source.
     * Lower values = higher priority.
     */
    @Builder.Default
    private int priority = 0;

    /**
     * Optional label for this edge (for visualization/debugging).
     */
    private String label;
}
