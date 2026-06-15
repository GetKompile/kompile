package ai.kompile.compute.graph.xircuits.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * A port on a Xircuits node.
 * Ports are either input (left, in=true) or output (right, in=false).
 * <p>
 * Naming conventions:
 * <ul>
 *   <li>Execution flow: "in-0" (input) / "out-0" (output)</li>
 *   <li>Data input: "parameter-{type}-{varname}" e.g. "parameter-string-a"</li>
 *   <li>Data output: "parameter-out-{type}-{varname}" e.g. "parameter-out-string-out"</li>
 *   <li>Dynamic: "parameter-dynalist-{varname}-{index}"</li>
 * </ul>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class XircuitsPort {

    private String id;
    private String type;

    @Builder.Default
    private Map<String, Object> extras = new HashMap<>();

    private double x;
    private double y;

    /**
     * Port identifier following naming convention.
     */
    private String name;

    /**
     * "left" = input port, "right" = output port.
     */
    private String alignment;

    private String parentNode;

    /**
     * IDs of links connected to this port.
     */
    @Builder.Default
    private List<String> links = new ArrayList<>();

    /**
     * true = input port, false = output port.
     */
    @JsonProperty("in")
    private boolean in;

    /**
     * Display label shown on canvas.
     * For Literal nodes, this contains the literal value.
     */
    private String label;

    /**
     * Python attribute name on the component class.
     */
    private String varName;

    private String portType;

    /**
     * Data type: "string", "int", "float", "boolean", "any", "dynalist", "dynatuple", or "".
     */
    private String dataType;

    /**
     * Whether this is an execution flow port (in-0 or out-0).
     */
    public boolean isFlowPort() {
        return name != null && (name.startsWith("in-") || name.startsWith("out-"));
    }

    /**
     * Whether this is a data/parameter port.
     */
    public boolean isParameterPort() {
        return name != null && name.startsWith("parameter-");
    }
}
