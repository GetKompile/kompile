package ai.kompile.pipelines.framework.runtime.pipeline.graph;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.HashMap;
import java.util.Map;

/**
 * Defines the type of a step within a GraphPipeline, determining its behavior
 * in terms of input/output handling and execution logic.
 * Based on ADR-0004-Graph_pipelines.md
 */
public enum GraphStepType {
    /**
     * A standard pipeline step (1-to-1 Data transformation).
     * This will wrap a regular {@link ai.kompile.pipelines.framework.api.StepConfig}.
     */
    STANDARD,

    /**
     * A switch operation (1 input to 1-of-N outputs).
     * Routes the input Data to one of its N output connections based on some criteria.
     */
    SWITCH,

    /**
     * A merge operation (N inputs to 1 output).
     * Combines Data instances from multiple input connections into a single output Data instance.
     * Typically involves copying all key-value pairs from input Datas into the output Data.
     */
    MERGE,

    /**
     * An "any" operation (N inputs to 1 output).
     * Forwards the first available Data instance from any of its N input connections.
     * Often used where only one branch of a preceding SWITCH is expected to produce output.
     */
    ANY,

    /**
     * A combine function (N inputs to 1 output).
     * Applies an arbitrary function to combine Data instances from multiple input connections.
     * This is more general than MERGE and can involve custom logic (e.g., averaging, weighted sum).
     */
    COMBINE_FN,

    /**
     * Represents the designated input node(s) for the graph.
     * Not a processing step itself, but a marker for graph entry points.
     * A graph typically has one implicit "PIPELINE_INPUT".
     */
    GRAPH_INPUT,

    /**
     * Represents the designated output node(s) for the graph.
     * Not a processing step itself, but a marker for graph exit points.
     * A graph typically has one designated output step that provides the "PIPELINE_OUTPUT".
     */
    GRAPH_OUTPUT; // May not be needed if output is simply the result of a specific named step

    private static final Map<String, GraphStepType> NAME_MAP = new HashMap<>();

    static {
        for (GraphStepType vt : values()) {
            NAME_MAP.put(vt.name().toUpperCase(), vt);
        }
    }

    @JsonCreator
    public static GraphStepType fromString(@JsonProperty("graphStepType") String name) {
        if (name == null) {
            return null;
        }
        GraphStepType type = NAME_MAP.get(name.toUpperCase());
        if (type == null) {
            throw new IllegalArgumentException("Unknown GraphStepType: " + name);
        }
        return type;
    }
}