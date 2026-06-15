package ai.kompile.compute.graph.xircuits.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * A node in a Xircuits workflow diagram.
 * Represents a component instance on the canvas.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class XircuitsNode {

    private String id;

    /**
     * Always "custom-node" for Xircuits.
     */
    private String type;

    private boolean selected;

    /**
     * Node metadata including component type, source file path, and line numbers.
     */
    @Builder.Default
    private Map<String, Object> extras = new HashMap<>();

    private double x;
    private double y;

    @Builder.Default
    private List<XircuitsPort> ports = new ArrayList<>();

    /**
     * Display name / component class name.
     * For special nodes: "Start", "Finish", "Literal String", "Argument (string): argName", etc.
     */
    private String name;

    private String color;

    /**
     * Input port IDs in visual order (left side).
     */
    @Builder.Default
    private List<String> portsInOrder = new ArrayList<>();

    /**
     * Output port IDs in visual order (right side).
     */
    @Builder.Default
    private List<String> portsOutOrder = new ArrayList<>();

    // ---- Convenience accessors for extras ----

    /**
     * Component type from extras: "Start", "Finish", "string", "int", "float",
     * "boolean", "list", "dict", "comment", "xircuits_workflow", or "debug".
     * null for regular component nodes (which use extras.path instead).
     */
    public String getComponentType() {
        return (String) extras.get("type");
    }

    /**
     * Python source file path for component nodes (e.g., "xai_components/xai_utils/utils.py").
     * null for literal/special nodes.
     */
    public String getSourcePath() {
        return (String) extras.get("path");
    }

    public String getDescription() {
        return (String) extras.get("description");
    }

    /**
     * Whether this is a Start node.
     */
    public boolean isStart() {
        return "Start".equals(getComponentType());
    }

    /**
     * Whether this is a Finish node.
     */
    public boolean isFinish() {
        return "Finish".equals(getComponentType());
    }

    /**
     * Whether this is an Argument node (CLI input parameter).
     * Argument nodes have names like "Argument (string): argName".
     */
    public boolean isArgument() {
        return name != null && name.startsWith("Argument ");
    }

    /**
     * Whether this is a Literal node (inline constant value).
     */
    public boolean isLiteral() {
        String ct = getComponentType();
        return ct != null && (ct.equals("string") || ct.equals("int") || ct.equals("float")
                || ct.equals("boolean") || ct.equals("list") || ct.equals("dict") || ct.equals("tuple"));
    }

    /**
     * For Argument nodes, extract the argument name.
     * "Argument (string): myArg" → "myArg"
     */
    public String getArgumentName() {
        if (!isArgument()) return null;
        int idx = name.indexOf("): ");
        return idx >= 0 ? name.substring(idx + 3).trim() : null;
    }

    /**
     * For Argument nodes, extract the type.
     * "Argument (string): myArg" → "string"
     */
    public String getArgumentType() {
        if (!isArgument()) return null;
        int start = name.indexOf('(');
        int end = name.indexOf(')');
        return (start >= 0 && end > start) ? name.substring(start + 1, end) : null;
    }
}
