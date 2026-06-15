/*
 *   Copyright 2025 Kompile Inc.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *   distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 * limitations under the License.
 */

package ai.kompile.cli.main.chat.enforcer;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.Map;

/**
 * Judge decision for a proposed MCP tool call.
 */
public class EnforcerToolCallDecision {

    public enum Action {
        ALLOW,
        BLOCK,
        REWRITE
    }

    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {};

    private final Action action;
    private final String reason;
    private final List<String> violations;
    private final String correctionPrompt;
    private final Map<String, Object> rewrittenArgs;

    public EnforcerToolCallDecision(Action action, String reason, List<String> violations,
                                    String correctionPrompt, Map<String, Object> rewrittenArgs) {
        this.action = action != null ? action : Action.BLOCK;
        this.reason = reason != null ? reason.trim() : "";
        this.violations = violations != null ? List.copyOf(violations) : List.of();
        this.correctionPrompt = correctionPrompt != null ? correctionPrompt.trim() : "";
        this.rewrittenArgs = rewrittenArgs != null ? Map.copyOf(rewrittenArgs) : null;
    }

    public static EnforcerToolCallDecision allow(String reason) {
        return new EnforcerToolCallDecision(Action.ALLOW, reason, List.of(), "", null);
    }

    public static EnforcerToolCallDecision block(String reason) {
        return new EnforcerToolCallDecision(Action.BLOCK, reason, List.of(reason), "", null);
    }

    public static EnforcerToolCallDecision parse(ObjectMapper mapper, String responseText) {
        String json = EnforcerDecision.extractJson(responseText);
        if (json == null) {
            return block("Enforcer tool judge did not return valid JSON");
        }

        try {
            JsonNode root = mapper.readTree(json);
            String actionText = root.path("action").asText("");
            if (actionText.isBlank()) {
                boolean allowed = root.path("allowed").asBoolean(root.path("compliant").asBoolean(false));
                actionText = allowed ? "ALLOW" : "BLOCK";
            }

            Action action;
            try {
                action = Action.valueOf(actionText.toUpperCase());
            } catch (IllegalArgumentException e) {
                action = Action.BLOCK;
            }

            String reason = root.path("reason").asText(root.path("reasoning").asText(""));
            String correction = root.path("correction_prompt").asText(root.path("correction").asText(""));
            List<String> violations = parseViolations(root);
            Map<String, Object> rewrittenArgs = null;
            if (action == Action.REWRITE && root.has("rewrittenArgs") && root.get("rewrittenArgs").isObject()) {
                rewrittenArgs = mapper.convertValue(root.get("rewrittenArgs"), MAP_TYPE);
            }

            if (action != Action.ALLOW && violations.isEmpty() && !reason.isBlank()) {
                violations = List.of(reason);
            }
            return new EnforcerToolCallDecision(action, reason, violations, correction, rewrittenArgs);
        } catch (Exception e) {
            return block("Enforcer tool judge JSON parse failed: " + e.getMessage());
        }
    }

    public Action getAction() {
        return action;
    }

    public boolean isAllowed() {
        return action == Action.ALLOW || action == Action.REWRITE;
    }

    public boolean isRewrite() {
        return action == Action.REWRITE;
    }

    public String getReason() {
        return reason;
    }

    public List<String> getViolations() {
        return violations;
    }

    public String getCorrectionPrompt() {
        return correctionPrompt;
    }

    public Map<String, Object> getRewrittenArgs() {
        return rewrittenArgs;
    }

    public String blockMessage() {
        if (!violations.isEmpty()) {
            return String.join("; ", violations);
        }
        if (!reason.isBlank()) {
            return reason;
        }
        return "Tool call violates the active enforcer rules";
    }

    private static List<String> parseViolations(JsonNode root) {
        JsonNode violationsNode = root.path("violations");
        if (violationsNode.isArray()) {
            java.util.ArrayList<String> values = new java.util.ArrayList<>();
            violationsNode.forEach(v -> {
                String text = v.asText("");
                if (!text.isBlank()) {
                    values.add(text);
                }
            });
            return values;
        }
        if (violationsNode.isTextual() && !violationsNode.asText().isBlank()) {
            return List.of(violationsNode.asText());
        }
        return List.of();
    }
}
