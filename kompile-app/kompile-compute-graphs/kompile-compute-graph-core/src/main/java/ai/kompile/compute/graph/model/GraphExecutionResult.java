package ai.kompile.compute.graph.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Duration;
import java.time.Instant;
import java.util.*;

/**
 * The result of executing an entire compute graph.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class GraphExecutionResult {

    private String executionId;
    private String graphId;

    @Builder.Default
    private ExecutionStatus status = ExecutionStatus.PENDING;

    /**
     * Results for each node, keyed by node ID.
     */
    @Builder.Default
    private Map<String, ExecutionResult> nodeResults = new LinkedHashMap<>();

    /**
     * Final outputs from terminal nodes, aggregated.
     */
    @Builder.Default
    private Map<String, Object> finalOutputs = new HashMap<>();

    /**
     * All artifacts produced during this graph execution.
     */
    @Builder.Default
    private List<ComputeArtifact> artifacts = new ArrayList<>();

    @Builder.Default
    private Instant startedAt = Instant.now();

    private Instant completedAt;
    private Duration totalDuration;

    /**
     * Execution order of nodes (topological).
     */
    @Builder.Default
    private List<String> executionOrder = new ArrayList<>();

    /**
     * Nodes that were skipped (due to conditional edges not being satisfied).
     */
    @Builder.Default
    private Set<String> skippedNodes = new HashSet<>();

    private String error;
}
