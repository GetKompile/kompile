package ai.kompile.compute.graph.n8n.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.HashMap;
import java.util.Map;

/**
 * Parsed n8n CLI execution output (the IRun JSON object).
 * Structure: { data: { resultData: { runData: { ... }, lastNodeExecuted, error } }, mode, status, startedAt, stoppedAt }
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class N8nExecutionResult {

    /**
     * Full execution data containing resultData with runData per node.
     */
    @Builder.Default
    private Map<String, Object> data = new HashMap<>();

    /**
     * Execution mode: "cli", "manual", "trigger", etc.
     */
    private String mode;

    /**
     * Execution status: "success", "error", "canceled", "running", "waiting", "new".
     */
    private String status;

    private String startedAt;
    private String stoppedAt;

    /**
     * Whether the execution was successful.
     */
    public boolean isSuccess() {
        return "success".equals(status);
    }

    /**
     * Extract the runData map (node name → execution data).
     */
    @SuppressWarnings("unchecked")
    public Map<String, Object> getRunData() {
        if (data == null) return Map.of();
        Object resultData = data.get("resultData");
        if (resultData instanceof Map) {
            Object runData = ((Map<String, Object>) resultData).get("runData");
            if (runData instanceof Map) {
                return (Map<String, Object>) runData;
            }
        }
        return Map.of();
    }

    /**
     * Extract the last node that was executed.
     */
    @SuppressWarnings("unchecked")
    public String getLastNodeExecuted() {
        if (data == null) return null;
        Object resultData = data.get("resultData");
        if (resultData instanceof Map) {
            return (String) ((Map<String, Object>) resultData).get("lastNodeExecuted");
        }
        return null;
    }

    /**
     * Extract error information if execution failed.
     */
    @SuppressWarnings("unchecked")
    public Object getError() {
        if (data == null) return null;
        Object resultData = data.get("resultData");
        if (resultData instanceof Map) {
            return ((Map<String, Object>) resultData).get("error");
        }
        return null;
    }
}
