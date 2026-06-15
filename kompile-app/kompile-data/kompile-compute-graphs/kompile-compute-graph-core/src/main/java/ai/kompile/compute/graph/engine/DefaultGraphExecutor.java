package ai.kompile.compute.graph.engine;

import ai.kompile.compute.graph.model.*;
import ai.kompile.compute.graph.store.ArtifactStore;
import lombok.extern.slf4j.Slf4j;
import org.springframework.expression.ExpressionParser;
import org.springframework.expression.spel.standard.SpelExpressionParser;
import org.springframework.expression.spel.support.StandardEvaluationContext;

import java.time.Duration;
import java.time.Instant;
import java.util.*;

/**
 * Default DAG executor that traverses the compute graph in topological order,
 * evaluates conditional edges, routes data between nodes, and collects artifacts.
 */
@Slf4j
public class DefaultGraphExecutor implements ComputeGraphEngine {

    private final Map<NodeExecutionType, NodeExecutor> executors;
    private final ArtifactStore artifactStore;
    private final ExpressionParser spelParser = new SpelExpressionParser();

    public DefaultGraphExecutor(List<NodeExecutor> executorList, ArtifactStore artifactStore) {
        this.artifactStore = artifactStore;
        this.executors = new EnumMap<>(NodeExecutionType.class);
        for (NodeExecutor executor : executorList) {
            for (NodeExecutionType type : executor.supportedTypes()) {
                executors.put(type, executor);
            }
        }
    }

    @Override
    public GraphExecutionResult execute(ComputeGraph graph, Map<String, Object> inputs) {
        String executionId = UUID.randomUUID().toString();
        Instant startedAt = Instant.now();

        GraphExecutionResult.GraphExecutionResultBuilder resultBuilder = GraphExecutionResult.builder()
                .executionId(executionId)
                .graphId(graph.getId())
                .status(ExecutionStatus.RUNNING)
                .startedAt(startedAt);

        ExecutionContext context = new ExecutionContext(executionId, graph, artifactStore);
        Map<String, ExecutionResult> nodeResults = new LinkedHashMap<>();
        List<String> executionOrder = new ArrayList<>();
        Set<String> skippedNodes = new HashSet<>();
        List<ComputeArtifact> allArtifacts = new ArrayList<>();

        try {
            List<ComputeNode> sorted = graph.topologicalSort();

            // Seed root node inputs
            Map<String, Map<String, Object>> pendingInputs = new HashMap<>();
            for (ComputeNode root : graph.getRootNodes()) {
                pendingInputs.put(root.getId(), new HashMap<>(inputs));
            }

            for (ComputeNode node : sorted) {
                // Gather inputs for this node from all incoming edges
                Map<String, Object> nodeInputs = pendingInputs.getOrDefault(node.getId(), new HashMap<>());

                // Check if all incoming conditional edges were satisfied
                List<ComputeEdge> incomingEdges = graph.getIncomingEdges(node.getId());
                if (!incomingEdges.isEmpty() && !pendingInputs.containsKey(node.getId())) {
                    // No data arrived — skip this node
                    skippedNodes.add(node.getId());
                    nodeResults.put(node.getId(), ExecutionResult.builder()
                            .nodeId(node.getId())
                            .executionId(executionId)
                            .status(ExecutionStatus.SKIPPED)
                            .build());
                    continue;
                }

                // Execute the node
                NodeExecutor executor = executors.get(node.getExecutionType());
                if (executor == null) {
                    String error = "No executor registered for type: " + node.getExecutionType();
                    nodeResults.put(node.getId(), ExecutionResult.failure(node.getId(), executionId, error, null));
                    resultBuilder.status(ExecutionStatus.FAILED).error(error);
                    break;
                }

                log.debug("Executing node '{}' (type={})", node.getName(), node.getExecutionType());
                ExecutionResult nodeResult = executor.execute(node, nodeInputs, context);
                nodeResult.setExecutionId(executionId);
                nodeResults.put(node.getId(), nodeResult);
                executionOrder.add(node.getId());

                if (nodeResult.getStatus() == ExecutionStatus.FAILED) {
                    resultBuilder.status(ExecutionStatus.FAILED)
                            .error("Node '" + node.getName() + "' failed: " + nodeResult.getError());
                    break;
                }

                // Record outputs in context
                context.recordNodeOutputs(node.getId(), nodeResult.getOutputs());

                // Collect artifacts
                if (nodeResult.getArtifacts() != null) {
                    allArtifacts.addAll(nodeResult.getArtifacts().values());
                }

                // Route outputs to downstream nodes via edges
                routeOutputs(graph, node, nodeResult.getOutputs(), pendingInputs);
            }

            // Collect final outputs from terminal nodes
            Map<String, Object> finalOutputs = new HashMap<>();
            for (ComputeNode terminal : graph.getTerminalNodes()) {
                ExecutionResult termResult = nodeResults.get(terminal.getId());
                if (termResult != null && termResult.getOutputs() != null) {
                    finalOutputs.putAll(termResult.getOutputs());
                }
            }

            Instant completedAt = Instant.now();
            ExecutionStatus finalStatus = resultBuilder.build().getStatus();
            if (finalStatus == ExecutionStatus.RUNNING) {
                finalStatus = ExecutionStatus.COMPLETED;
            }

            return resultBuilder
                    .status(finalStatus)
                    .nodeResults(nodeResults)
                    .finalOutputs(finalOutputs)
                    .artifacts(allArtifacts)
                    .executionOrder(executionOrder)
                    .skippedNodes(skippedNodes)
                    .completedAt(completedAt)
                    .totalDuration(Duration.between(startedAt, completedAt))
                    .build();

        } catch (Exception e) {
            log.error("Graph execution failed", e);
            return resultBuilder
                    .status(ExecutionStatus.FAILED)
                    .error(e.getMessage())
                    .nodeResults(nodeResults)
                    .executionOrder(executionOrder)
                    .completedAt(Instant.now())
                    .totalDuration(Duration.between(startedAt, Instant.now()))
                    .build();
        }
    }

    @Override
    public GraphExecutionResult executeSingleNode(ComputeGraph graph, String nodeId, Map<String, Object> inputs) {
        ComputeNode node = graph.findNode(nodeId)
                .orElseThrow(() -> new IllegalArgumentException("Node not found: " + nodeId));

        String executionId = UUID.randomUUID().toString();
        ExecutionContext context = new ExecutionContext(executionId, graph, artifactStore);

        NodeExecutor executor = executors.get(node.getExecutionType());
        if (executor == null) {
            return GraphExecutionResult.builder()
                    .executionId(executionId)
                    .graphId(graph.getId())
                    .status(ExecutionStatus.FAILED)
                    .error("No executor for type: " + node.getExecutionType())
                    .build();
        }

        ExecutionResult nodeResult = executor.execute(node, inputs, context);
        nodeResult.setExecutionId(executionId);

        return GraphExecutionResult.builder()
                .executionId(executionId)
                .graphId(graph.getId())
                .status(nodeResult.getStatus())
                .nodeResults(Map.of(nodeId, nodeResult))
                .finalOutputs(nodeResult.getOutputs())
                .executionOrder(List.of(nodeId))
                .completedAt(Instant.now())
                .build();
    }

    @Override
    public String validate(ComputeGraph graph) {
        List<String> errors = new ArrayList<>();

        // Check for cycles
        try {
            graph.topologicalSort();
        } catch (IllegalStateException e) {
            errors.add(e.getMessage());
        }

        // Check for missing executor types
        for (ComputeNode node : graph.getNodes()) {
            if (node.getExecutionType() == null) {
                errors.add("Node '" + node.getName() + "' has no execution type");
            } else if (!executors.containsKey(node.getExecutionType())) {
                errors.add("No executor registered for type " + node.getExecutionType()
                        + " (node: " + node.getName() + ")");
            } else {
                String validationError = executors.get(node.getExecutionType()).validate(node);
                if (validationError != null) {
                    errors.add("Node '" + node.getName() + "': " + validationError);
                }
            }
        }

        // Check edge references
        Set<String> nodeIds = new HashSet<>();
        for (ComputeNode node : graph.getNodes()) {
            nodeIds.add(node.getId());
        }
        for (ComputeEdge edge : graph.getEdges()) {
            if (!nodeIds.contains(edge.getSourceNodeId())) {
                errors.add("Edge references non-existent source node: " + edge.getSourceNodeId());
            }
            if (!nodeIds.contains(edge.getTargetNodeId())) {
                errors.add("Edge references non-existent target node: " + edge.getTargetNodeId());
            }
        }

        return errors.isEmpty() ? null : String.join("; ", errors);
    }

    /**
     * Route outputs from a completed node to its downstream nodes via edges.
     * Evaluates conditional edges and applies data mapping.
     */
    private void routeOutputs(ComputeGraph graph, ComputeNode sourceNode,
                              Map<String, Object> outputs, Map<String, Map<String, Object>> pendingInputs) {
        for (ComputeEdge edge : graph.getOutgoingEdges(sourceNode.getId())) {
            // Evaluate condition if present
            if (edge.getCondition() != null && !edge.getCondition().isBlank()) {
                if (!evaluateCondition(edge.getCondition(), outputs)) {
                    log.debug("Edge condition not met: {} -> {}", sourceNode.getId(), edge.getTargetNodeId());
                    continue;
                }
            }

            // Apply data mapping
            Map<String, Object> mappedOutputs;
            if (edge.getDataMapping() != null && !edge.getDataMapping().isEmpty()) {
                mappedOutputs = new HashMap<>();
                for (Map.Entry<String, String> mapping : edge.getDataMapping().entrySet()) {
                    Object value = outputs.get(mapping.getKey());
                    if (value != null) {
                        mappedOutputs.put(mapping.getValue(), value);
                    }
                }
            } else {
                mappedOutputs = new HashMap<>(outputs);
            }

            // Merge into pending inputs for target node
            pendingInputs.computeIfAbsent(edge.getTargetNodeId(), k -> new HashMap<>())
                    .putAll(mappedOutputs);
        }
    }

    /**
     * Evaluate a SpEL condition against the output data.
     */
    private boolean evaluateCondition(String condition, Map<String, Object> outputs) {
        try {
            StandardEvaluationContext evalContext = new StandardEvaluationContext();
            evalContext.setVariable("output", outputs);
            for (Map.Entry<String, Object> entry : outputs.entrySet()) {
                evalContext.setVariable(entry.getKey(), entry.getValue());
            }
            Boolean result = spelParser.parseExpression(condition).getValue(evalContext, Boolean.class);
            return Boolean.TRUE.equals(result);
        } catch (Exception e) {
            log.warn("Failed to evaluate edge condition '{}': {}", condition, e.getMessage());
            return false;
        }
    }
}
