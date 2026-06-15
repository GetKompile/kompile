package ai.kompile.compute.graph.xircuits.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;

/**
 * Root model for a .xircuits workflow file.
 * A .xircuits file is a JSON diagram with exactly two layers:
 * layer 0 = links (connections), layer 1 = nodes.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class XircuitsWorkflow {

    private String id;

    @JsonProperty("offsetX")
    @Builder.Default
    private double offsetX = 0;

    @JsonProperty("offsetY")
    @Builder.Default
    private double offsetY = 0;

    @Builder.Default
    private double zoom = 100;

    @Builder.Default
    private int gridSize = 0;

    /**
     * Always exactly 2 layers:
     * [0] = diagram-links (connections between nodes)
     * [1] = diagram-nodes (all nodes on canvas)
     */
    @Builder.Default
    private List<XircuitsLayer> layers = new ArrayList<>();

    /**
     * Convenience: extract the links layer.
     */
    public XircuitsLayer getLinksLayer() {
        if (layers != null && layers.size() > 0) {
            return layers.get(0);
        }
        return null;
    }

    /**
     * Convenience: extract the nodes layer.
     */
    public XircuitsLayer getNodesLayer() {
        if (layers != null && layers.size() > 1) {
            return layers.get(1);
        }
        return null;
    }
}
