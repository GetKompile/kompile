package ai.kompile.compute.graph.engine;

import ai.kompile.compute.graph.model.ComputeNode;
import ai.kompile.compute.graph.model.ExecutionResult;
import ai.kompile.compute.graph.model.NodeExecutionType;

import java.util.Map;
import java.util.Set;

/**
 * Strategy interface for executing a single compute node.
 * Each implementation handles a specific execution type (JS, Python, Drools, etc.)
 */
public interface NodeExecutor {

    /**
     * Execute the given node with the provided inputs.
     *
     * @param node    The compute node definition (contains script, parameters, limits)
     * @param inputs  Input data from upstream nodes, keyed by variable name
     * @param context The execution context (graph-level state, artifact store, etc.)
     * @return The execution result containing outputs or error information
     */
    ExecutionResult execute(ComputeNode node, Map<String, Object> inputs, ExecutionContext context);

    /**
     * The execution types this executor can handle.
     */
    Set<NodeExecutionType> supportedTypes();

    /**
     * Validate that a node's configuration is correct for this executor.
     * Called before execution to catch errors early.
     *
     * @return null if valid, or an error message describing the problem
     */
    default String validate(ComputeNode node) {
        return null;
    }
}
