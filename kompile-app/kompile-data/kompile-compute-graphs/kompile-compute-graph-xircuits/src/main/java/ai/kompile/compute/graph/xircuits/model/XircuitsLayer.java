package ai.kompile.compute.graph.xircuits.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.HashMap;
import java.util.Map;

/**
 * A layer in a .xircuits diagram. Two types exist:
 * - "diagram-links": contains link models (connections)
 * - "diagram-nodes": contains node models
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class XircuitsLayer {

    private String id;

    /**
     * Either "diagram-links" or "diagram-nodes".
     */
    private String type;

    @Builder.Default
    private boolean isSvg = false;

    @Builder.Default
    private boolean transformed = true;

    /**
     * Map of model ID → model object.
     * For "diagram-links": values are {@link XircuitsLink} objects.
     * For "diagram-nodes": values are {@link XircuitsNode} objects.
     * Jackson deserializes these as raw maps; use typed accessors.
     */
    @Builder.Default
    private Map<String, Object> models = new HashMap<>();
}
