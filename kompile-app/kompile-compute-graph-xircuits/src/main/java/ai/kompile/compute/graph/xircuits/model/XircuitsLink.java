package ai.kompile.compute.graph.xircuits.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * A link (connection) between two ports in a Xircuits workflow.
 * Two types:
 * <ul>
 *   <li>"triangle-link" — execution flow (sequence arrows)</li>
 *   <li>"parameter-link" — data flow between parameter ports</li>
 * </ul>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class XircuitsLink {

    private String id;

    /**
     * "triangle-link" (execution flow) or "parameter-link" (data flow).
     */
    private String type;

    private boolean selected;

    /**
     * Source node UUID.
     */
    private String source;

    /**
     * Source port UUID.
     */
    private String sourcePort;

    /**
     * Target node UUID.
     */
    private String target;

    /**
     * Target port UUID.
     */
    private String targetPort;

    @Builder.Default
    private List<Map<String, Object>> points = new ArrayList<>();

    @Builder.Default
    private List<Object> labels = new ArrayList<>();

    @Builder.Default
    private int width = 3;

    @Builder.Default
    private String color = "gray";

    @Builder.Default
    private int curvyness = 50;

    private String selectedColor;

    /**
     * Whether this is an execution flow link.
     */
    public boolean isFlowLink() {
        return "triangle-link".equals(type);
    }

    /**
     * Whether this is a data/parameter link.
     */
    public boolean isDataLink() {
        return "parameter-link".equals(type);
    }
}
