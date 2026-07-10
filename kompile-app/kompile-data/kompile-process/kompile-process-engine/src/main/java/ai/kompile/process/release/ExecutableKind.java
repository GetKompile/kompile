package ai.kompile.process.release;

/**
 * Stable executable artifact categories understood by the process release layer.
 */
public enum ExecutableKind {
    SCRIPT,
    COMPUTE_GRAPH,
    PIPELINE,
    CAMEL_ROUTE,
    WORKFLOW,
    DECISION_TABLE,
    AGENT_SESSION
}
