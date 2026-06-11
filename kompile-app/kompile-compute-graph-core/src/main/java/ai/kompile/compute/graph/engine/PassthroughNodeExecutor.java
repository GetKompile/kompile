package ai.kompile.compute.graph.engine;

import ai.kompile.compute.graph.model.ComputeNode;
import ai.kompile.compute.graph.model.ExecutionResult;
import ai.kompile.compute.graph.model.ExecutionStatus;
import ai.kompile.compute.graph.model.NodeExecutionType;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/**
 * No-op executor that passes inputs through as outputs.
 * Useful for fan-in/aggregation nodes.
 */
public class PassthroughNodeExecutor implements NodeExecutor {

    @Override
    public ExecutionResult execute(ComputeNode node, Map<String, Object> inputs, ExecutionContext context) {
        Map<String, Object> outputs = new HashMap<>(inputs);
        // Also include any static parameters from the node definition
        if (node.getParameters() != null) {
            outputs.putAll(node.getParameters());
        }

        return ExecutionResult.builder()
                .nodeId(node.getId())
                .status(ExecutionStatus.COMPLETED)
                .outputs(outputs)
                .startedAt(Instant.now())
                .completedAt(Instant.now())
                .build();
    }

    @Override
    public Set<NodeExecutionType> supportedTypes() {
        return Set.of(NodeExecutionType.PASSTHROUGH);
    }
}
