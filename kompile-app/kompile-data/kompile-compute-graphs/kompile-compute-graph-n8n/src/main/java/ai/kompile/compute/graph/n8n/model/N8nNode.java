package ai.kompile.compute.graph.n8n.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * An n8n workflow node.
 * Each node represents a single operation (trigger, HTTP request, code, etc.).
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class N8nNode {

    /**
     * UUID uniquely identifying the node within the workflow.
     */
    private String id;

    /**
     * Display name; also used as the connection map key.
     */
    private String name;

    /**
     * Node type: "n8n-nodes-base.webhook", "@n8n/n8n-nodes-langchain.openAi", etc.
     */
    private String type;

    /**
     * Version of the node type (e.g., 1, 1.3, 2.1).
     */
    private double typeVersion;

    /**
     * [x, y] canvas coordinates.
     */
    private List<Integer> position;

    /**
     * Skip this node during execution.
     */
    @Builder.Default
    private boolean disabled = false;

    /**
     * Node-specific configuration. Structure varies per node type.
     * Values can be strings (including n8n expressions like "={{ $json.name }}"),
     * numbers, booleans, objects, or arrays.
     */
    @Builder.Default
    private Map<String, Object> parameters = new HashMap<>();

    /**
     * Credential references: credentialTypeName → {id, name}.
     */
    @Builder.Default
    private Map<String, Map<String, String>> credentials = new HashMap<>();

    /**
     * Stable UUID for webhook/form URL.
     */
    private String webhookId;

    /**
     * Error handling: "stopWorkflow", "continueRegularOutput", "continueErrorOutput".
     */
    private String onError;

    /**
     * Auto-retry on failure.
     */
    @Builder.Default
    private boolean retryOnFail = false;

    /**
     * Max retries (used with retryOnFail).
     */
    @Builder.Default
    private int maxTries = 3;

    /**
     * Milliseconds between retries.
     */
    @Builder.Default
    private int waitBetweenTries = 1000;

    /**
     * Output data even on error.
     */
    @Builder.Default
    private boolean alwaysOutputData = false;

    /**
     * Execute only for the first input item.
     */
    @Builder.Default
    private boolean executeOnce = false;

    /**
     * Notes visible in the editor.
     */
    private String notes;

    @Builder.Default
    private boolean notesInFlow = false;

    /**
     * Whether this node is a trigger node.
     * n8n considers a node a trigger if its type contains "trigger" (case-insensitive)
     * or is one of the known trigger types.
     */
    public boolean isTriggerNode() {
        if (type == null) return false;
        String lower = type.toLowerCase();
        if (lower.contains("trigger")) return true;
        return lower.equals("n8n-nodes-base.webhook")
                || lower.equals("n8n-nodes-base.cron")
                || lower.equals("n8n-nodes-base.emailreadimap")
                || lower.equals("n8n-nodes-base.start");
    }

    /**
     * Extract the short node type name (after the last dot).
     * "n8n-nodes-base.httpRequest" → "httpRequest"
     */
    public String getShortType() {
        if (type == null) return null;
        int dot = type.lastIndexOf('.');
        return dot >= 0 ? type.substring(dot + 1) : type;
    }
}
