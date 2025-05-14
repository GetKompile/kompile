package ai.kompile.pipelines.framework.runtime.pipeline.graph;

import ai.kompile.pipelines.framework.api.Pipeline;
import ai.kompile.pipelines.framework.api.PipelineExecutor;
import ai.kompile.pipelines.framework.api.StepConfig;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Getter;
import lombok.NonNull;
import lombok.ToString;
import lombok.extern.slf4j.Slf4j;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Implementation of {@link Pipeline} for directed acyclic graph (DAG) based pipelines.
 * Allows for branching, merging, and parallel execution paths.
 * The structure is defined by a map of {@link GraphNodeConfig} objects,
 * where keys are unique node names and values define the node's type, inputs, and configuration.
 *
 * Based on ADR-0004-Graph_pipelines.md
 */

@Getter
@ToString
public class GraphPipeline implements Pipeline {
    private static final long serialVersionUID = 1L;

    public static final String DEFAULT_GRAPH_INPUT_NAME = "pipeline_input";

    private final String id;
    // Using a Map to store graph nodes by name for easy lookup.
    // The value is GraphNodeConfig which holds the type, inputs, and specific step config.
    private final Map<String, GraphNodeConfig> graphNodes;
    private final String inputNodeName; // Name of the effective input node for the graph
    private final String outputNodeName; // Name of the node whose output is the graph's output

    /**
     * Creates a new GraphPipeline.
     * The GraphNodeConfig list is converted to a map internally.
     *
     * @param id Unique identifier for the pipeline. If null, a UUID is generated.
     * @param nodeConfigs List of {@link GraphNodeConfig} defining all nodes in the graph.
     * @param inputNodeName The name of the node to be treated as the primary input for the graph.
     * If null, defaults to "pipeline_input" and implies the actual first processing
     * node(s) will declare this as their input.
     * @param outputNodeName The name of the node whose output will be considered the final output of the graph.
     */
    @JsonCreator
    public GraphPipeline(
            @JsonProperty("id") String id,
            @NonNull @JsonProperty(value = "nodes", required = true) List<GraphNodeConfig> nodeConfigs,
            @JsonProperty("inputNodeName") String inputNodeName,
            @NonNull @JsonProperty(value = "outputNodeName", required = true) String outputNodeName) {

        this.id = (id != null && !id.trim().isEmpty()) ? id : UUID.randomUUID().toString();

        Map<String, GraphNodeConfig> nodesMap = new LinkedHashMap<>();
        if (nodeConfigs == null) {
            throw new IllegalArgumentException("Node configurations list cannot be null for GraphPipeline " + this.id);
        }
        for (GraphNodeConfig nodeConfig : nodeConfigs) {
            if (nodeConfig.getName() == null || nodeConfig.getName().trim().isEmpty()) {
                throw new IllegalArgumentException("GraphNodeConfig must have a valid name. Pipeline ID: " + this.id);
            }
            if (nodesMap.containsKey(nodeConfig.getName())) {
                throw new IllegalArgumentException("Duplicate node name '" + nodeConfig.getName() + "' in GraphPipeline " + this.id);
            }
            nodesMap.put(nodeConfig.getName(), nodeConfig);
        }
        this.graphNodes = Collections.unmodifiableMap(nodesMap);

        this.inputNodeName = (inputNodeName != null && !inputNodeName.trim().isEmpty()) ? inputNodeName : DEFAULT_GRAPH_INPUT_NAME;
        this.outputNodeName = Objects.requireNonNull(outputNodeName, "Output node name cannot be null for GraphPipeline " + this.id);

        // Initial validation can be done here or deferred to validate()
        try {
            validate();
        } catch (IllegalStateException e) {
            // Log validation failure during construction if desired, but allow construction
        }
    }

    /**
     * Gets the list of StepConfig objects. For a GraphPipeline, this means extracting
     * the StepConfig from any StandardGraphNodeConfig instances. Other node types
     * (MERGE, SWITCH) don't directly correspond to a user-defined StepConfig with a runner.
     * This method is mainly for compatibility with the Pipeline interface if some tools
     * expect a flat list of runnable StepConfigs.
     *
     * @return A list of StepConfig from standard nodes in the graph.
     */
    @Override
    public List<StepConfig> getSteps() {
        return graphNodes.values().stream()
                .filter(node -> node instanceof StandardGraphNodeConfig)
                .map(node -> ((StandardGraphNodeConfig) node).getStepConfig())
                .collect(Collectors.toList());
    }

    /**
     * Returns all GraphNodeConfig objects in the graph.
     * This provides the full definition of the graph.
     * @return An unmodifiable map of node names to their GraphNodeConfig.
     */
    public Map<String, GraphNodeConfig> getGraphNodes() {
        return graphNodes;
    }


    @Override
    public PipelineExecutor createExecutor() throws Exception {
        // Pass true to initialize runners immediately.
        return new GraphPipelineExecutor(this, true);
    }

    @Override
    public void validate() throws IllegalStateException {
        if (graphNodes == null || graphNodes.isEmpty()) {
            throw new IllegalStateException("GraphPipeline '" + this.id + "' must have at least one node defined.");
        }
        if (outputNodeName == null || !graphNodes.containsKey(outputNodeName)) {
            throw new IllegalStateException("GraphPipeline '" + this.id + "': Output node '" + outputNodeName + "' is not defined in the graph nodes.");
        }

        // Basic validation: check if all declared input names for each node exist as node names in the graph,
        // or if they are the special pipeline input name.
        for (Map.Entry<String, GraphNodeConfig> entry : graphNodes.entrySet()) {
            String nodeName = entry.getKey();
            GraphNodeConfig nodeConfig = entry.getValue();

            if (nodeConfig.getInputs() == null) {
                throw new IllegalStateException("GraphPipeline '" + this.id + "', Node '" + nodeName + "': Inputs list cannot be null.");
            }

            for (String inputSourceNodeName : nodeConfig.getInputs()) {
                if (inputSourceNodeName == null || inputSourceNodeName.trim().isEmpty()) {
                    throw new IllegalStateException("GraphPipeline '" + this.id + "', Node '" + nodeName +
                            "': Contains a null or empty input source name.");
                }
                if (!inputSourceNodeName.equals(this.inputNodeName) && !graphNodes.containsKey(inputSourceNodeName)) {
                    throw new IllegalStateException("GraphPipeline '" + this.id + "', Node '" + nodeName +
                            "': Declares input from unknown node or non-pipeline input '" +
                            inputSourceNodeName + "'.");
                }
            }
            // Validate specific node types
            if (nodeConfig instanceof StandardGraphNodeConfig) {
                StepConfig sc = ((StandardGraphNodeConfig) nodeConfig).getStepConfig();
                if (sc == null || sc.runnerClassName() == null || sc.runnerClassName().trim().isEmpty()) {
                    throw new IllegalStateException("GraphPipeline '" + this.id + "', STANDARD Node '" + nodeName +
                            "': Associated StepConfig or its runnerClassName is null or empty.");
                }
            } else if (nodeConfig instanceof SwitchNodeConfig) {
                SwitchNodeConfig sc = (SwitchNodeConfig) nodeConfig;
                if (sc.getSwitchFunctionClassName() == null || sc.getSwitchFunctionClassName().trim().isEmpty()) {
                    throw new IllegalStateException("GraphPipeline '" + this.id + "', SWITCH Node '" + nodeName +
                            "': switchFunctionClassName is null or empty.");
                }
            }
            // Add similar checks for CombineNodeConfig, etc.
        }

        // More advanced validation: DAG check (no cycles), reachability of output node from input.
        // This would require graph traversal algorithms. For now, keeping validation basic.
        // A simple cycle check can be done during topological sort in the executor.
    }

    @Override
    public String id() {
        return this.id;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        GraphPipeline that = (GraphPipeline) o;
        return id.equals(that.id) &&
                graphNodes.equals(that.graphNodes) &&
                inputNodeName.equals(that.inputNodeName) &&
                outputNodeName.equals(that.outputNodeName);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id, graphNodes, inputNodeName, outputNodeName);
    }
}