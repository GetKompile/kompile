package ai.kompile.compute.graph.model;

/**
 * Status of a node or graph execution.
 */
public enum ExecutionStatus {
    PENDING,
    RUNNING,
    COMPLETED,
    FAILED,
    SKIPPED,
    TIMED_OUT,
    CANCELLED
}
