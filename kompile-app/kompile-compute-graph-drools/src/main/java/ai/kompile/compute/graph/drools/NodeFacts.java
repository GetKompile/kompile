package ai.kompile.compute.graph.drools;

import lombok.Data;

import java.util.HashMap;
import java.util.Map;

/**
 * The primary fact object inserted into Drools working memory for node execution.
 * Rules can read from inputs/parameters and write to outputs.
 */
@Data
public class NodeFacts {

    private final String nodeId;
    private final String executionId;

    /**
     * Input data from upstream nodes (read-only by convention).
     */
    private Map<String, Object> inputs = new HashMap<>();

    /**
     * Static parameters defined on the node (read-only by convention).
     */
    private Map<String, Object> parameters = new HashMap<>();

    /**
     * Output data produced by rules (writable by rules).
     */
    private Map<String, Object> outputs = new HashMap<>();

    /**
     * Global state accessible to the node.
     */
    private Map<String, Object> globalState = new HashMap<>();

    public NodeFacts(String nodeId, String executionId) {
        this.nodeId = nodeId;
        this.executionId = executionId;
    }

    /**
     * Convenience method for rules to set an output value.
     */
    public void setOutput(String key, Object value) {
        outputs.put(key, value);
    }

    /**
     * Convenience method for rules to get an input value.
     */
    public Object getInput(String key) {
        return inputs.get(key);
    }

    /**
     * Convenience method for rules to get a parameter value.
     */
    public Object getParam(String key) {
        return parameters.get(key);
    }

    /**
     * Convenience method for rules to get an input as a number.
     */
    public double getInputAsDouble(String key) {
        Object val = inputs.get(key);
        if (val instanceof Number) {
            return ((Number) val).doubleValue();
        }
        return Double.parseDouble(String.valueOf(val));
    }
}
