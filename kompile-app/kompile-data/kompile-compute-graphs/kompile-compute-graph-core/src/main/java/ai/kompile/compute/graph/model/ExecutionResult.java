package ai.kompile.compute.graph.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

/**
 * The result of executing a single compute node.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ExecutionResult {

    private String nodeId;
    private String executionId;

    @Builder.Default
    private ExecutionStatus status = ExecutionStatus.PENDING;

    /**
     * Output data produced by this node, keyed by variable name.
     */
    @Builder.Default
    private Map<String, Object> outputs = new HashMap<>();

    /**
     * Error message if execution failed.
     */
    private String error;

    /**
     * Stack trace if execution failed.
     */
    private String stackTrace;

    /**
     * Wall-clock duration of execution.
     */
    private Duration duration;

    @Builder.Default
    private Instant startedAt = Instant.now();

    private Instant completedAt;

    /**
     * Console output captured during script execution.
     */
    private String consoleOutput;

    /**
     * Any artifacts produced during execution.
     */
    @Builder.Default
    private Map<String, ComputeArtifact> artifacts = new HashMap<>();

    public static ExecutionResult success(String nodeId, String executionId, Map<String, Object> outputs) {
        return ExecutionResult.builder()
                .nodeId(nodeId)
                .executionId(executionId)
                .status(ExecutionStatus.COMPLETED)
                .outputs(outputs)
                .completedAt(Instant.now())
                .build();
    }

    public static ExecutionResult failure(String nodeId, String executionId, String error, String stackTrace) {
        return ExecutionResult.builder()
                .nodeId(nodeId)
                .executionId(executionId)
                .status(ExecutionStatus.FAILED)
                .error(error)
                .stackTrace(stackTrace)
                .completedAt(Instant.now())
                .build();
    }
}
