package ai.kompile.compute.graph.n8n.model;

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
 * Root model for an n8n workflow JSON file.
 * Minimum valid file requires only {@code nodes} and {@code connections}.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class N8nWorkflow {

    /**
     * Workflow ID (auto-assigned by n8n, not required in import files).
     */
    private String id;

    /**
     * Human-readable workflow name.
     */
    private String name;

    /**
     * Optional description.
     */
    private String description;

    /**
     * Whether the workflow is activated (triggers listening).
     */
    @Builder.Default
    private boolean active = false;

    /**
     * Soft-deleted flag.
     */
    @Builder.Default
    private boolean isArchived = false;

    /**
     * Array of node definitions.
     */
    @Builder.Default
    private List<N8nNode> nodes = new ArrayList<>();

    /**
     * Connection graph: sourceNodeName → connectionType → outputIndex → targets.
     * Structure: { "NodeName": { "main": [ [ {node, type, index} ] ] } }
     */
    @Builder.Default
    private Map<String, Map<String, List<List<N8nConnection>>>> connections = new HashMap<>();

    /**
     * Optional execution settings (timezone, timeout, error workflow, etc.).
     */
    @Builder.Default
    private Map<String, Object> settings = new HashMap<>();

    /**
     * Persisted key/value store for stateful nodes.
     */
    @Builder.Default
    private Map<String, Object> staticData = new HashMap<>();

    /**
     * Pinned test data per node: { nodeName: [{json: {...}}] }.
     */
    @Builder.Default
    private Map<String, Object> pinData = new HashMap<>();

    /**
     * UUID tracking workflow version.
     */
    private String versionId;

    /**
     * Frontend metadata (templateId, onboardingId, etc.).
     */
    @Builder.Default
    private Map<String, Object> meta = new HashMap<>();

    /**
     * Find the trigger/start node using n8n's priority rules:
     * 1. Nodes with type containing "trigger" (case-insensitive)
     * 2. manualTrigger, executeWorkflowTrigger, errorTrigger
     * 3. webhook, cron, start nodes
     * 4. First node as fallback
     */
    public N8nNode findStartNode() {
        if (nodes == null || nodes.isEmpty()) return null;
        if (nodes.size() == 1) return nodes.get(0);

        // Priority 1: trigger nodes
        for (N8nNode node : nodes) {
            if (node.isTriggerNode()) return node;
        }

        // Priority 2: known start types
        String[] startTypes = {
                "n8n-nodes-base.manualTrigger",
                "n8n-nodes-base.executeWorkflowTrigger",
                "n8n-nodes-base.errorTrigger",
                "n8n-nodes-base.webhook",
                "n8n-nodes-base.cron",
                "n8n-nodes-base.start"
        };
        for (String startType : startTypes) {
            for (N8nNode node : nodes) {
                if (startType.equals(node.getType())) return node;
            }
        }

        // Fallback: first node
        return nodes.get(0);
    }
}
