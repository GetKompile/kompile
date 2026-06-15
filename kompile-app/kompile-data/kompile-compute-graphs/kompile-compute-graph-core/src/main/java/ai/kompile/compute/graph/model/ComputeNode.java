package ai.kompile.compute.graph.model;

import com.fasterxml.jackson.annotation.JsonTypeInfo;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.HashMap;
import java.util.Map;

/**
 * Represents a single computation node in a compute graph.
 * Each node has attached code (script or rule definition) that executes
 * when the node is reached during graph traversal.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonTypeInfo(use = JsonTypeInfo.Id.CLASS, property = "@class")
public class ComputeNode {

    private String id;
    private String name;
    private String description;

    /**
     * The type of executor that should handle this node.
     * E.g., "javascript", "python", "drools", "expression"
     */
    private NodeExecutionType executionType;

    /**
     * The script/rule source code attached to this node.
     * For scripting nodes: JavaScript or Python source code.
     * For Drools nodes: DRL rule definition or rule name reference.
     */
    private String script;

    /**
     * Static configuration parameters for this node.
     * Available to the script as read-only bindings.
     */
    @Builder.Default
    private Map<String, Object> parameters = new HashMap<>();

    /**
     * Expected input bindings — names of variables this node reads from upstream.
     */
    @Builder.Default
    private Map<String, String> inputBindings = new HashMap<>();

    /**
     * Expected output bindings — names of variables this node produces.
     */
    @Builder.Default
    private Map<String, String> outputBindings = new HashMap<>();

    /**
     * Resource limits for script execution on this node.
     */
    @Builder.Default
    private ExecutionLimits limits = ExecutionLimits.defaults();

    /**
     * Metadata for this node (tags, annotations, etc.)
     */
    @Builder.Default
    private Map<String, Object> metadata = new HashMap<>();
}
