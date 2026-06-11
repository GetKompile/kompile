package ai.kompile.compute.graph.engine;

import ai.kompile.compute.graph.model.ComputeGraph;
import ai.kompile.compute.graph.model.GraphExecutionResult;

import java.util.Map;

/**
 * Main interface for executing compute graphs.
 * Implementations handle DAG traversal, node scheduling,
 * conditional edge evaluation, and artifact collection.
 */
public interface ComputeGraphEngine {

    /**
     * Execute an entire compute graph with the given initial inputs.
     *
     * @param graph  The graph definition to execute
     * @param inputs Initial input data fed to root nodes
     * @return The complete execution result with all node outputs and artifacts
     */
    GraphExecutionResult execute(ComputeGraph graph, Map<String, Object> inputs);

    /**
     * Execute a single node in isolation (for testing/debugging).
     *
     * @param graph  The graph containing the node
     * @param nodeId The specific node to execute
     * @param inputs Input data for the node
     * @return The execution result for that single node
     */
    GraphExecutionResult executeSingleNode(ComputeGraph graph, String nodeId, Map<String, Object> inputs);

    /**
     * Validate a graph definition without executing it.
     * Checks for cycles, missing references, invalid scripts, etc.
     *
     * @return null if valid, or a description of the validation errors
     */
    String validate(ComputeGraph graph);
}
