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

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.List;

/**
 * Structured result from the enforcer judge.
 */
public class EnforcerDecision {

    private final boolean compliant;
    private final boolean stop;
    private final String severity;
    private final List<String> violations;
    private final String correctionPrompt;
    private final String reasoning;

    public EnforcerDecision(boolean compliant, boolean stop, String severity,
                            List<String> violations, String correctionPrompt,
                            String reasoning) {
        this.compliant = compliant;
        this.stop = stop;
        this.severity = severity != null && !severity.isBlank() ? severity : "info";
        this.violations = violations != null ? List.copyOf(violations) : List.of();
        this.correctionPrompt = correctionPrompt != null ? correctionPrompt.trim() : "";
        this.reasoning = reasoning != null ? reasoning.trim() : "";
    }

    public boolean isCompliant() {
        return compliant;
    }

    public boolean isStop() {
        return stop;
    }

    public String getSeverity() {
        return severity;
    }

    public List<String> getViolations() {
        return violations;
    }

    public String getCorrectionPrompt() {
        return correctionPrompt;
    }

    public String getReasoning() {
        return reasoning;
    }

    public static EnforcerDecision pass(String reasoning) {
        return new EnforcerDecision(true, false, "info", List.of(), "", reasoning);
    }

    public static EnforcerDecision fail(List<String> violations, String correctionPrompt, String reasoning) {
        return new EnforcerDecision(false, false, "error", violations, correctionPrompt, reasoning);
    }

    public static EnforcerDecision stop(List<String> violations, String reasoning) {
        return new EnforcerDecision(false, true, "critical", violations, "", reasoning);
    }

    public static EnforcerDecision parse(ObjectMapper mapper, String responseText) {
        String json = extractJson(responseText);
        if (json == null) {
            return stop(List.of("Enforcer judge did not return valid JSON"),
                    responseText != null ? truncate(responseText, 240) : "empty judge response");
        }

        try {
            JsonNode root = mapper.readTree(json);
            String action = root.path("action").asText("");
            boolean compliant = root.path("compliant").asBoolean("pass".equalsIgnoreCase(action));
            boolean stop = root.path("stop").asBoolean("stop".equalsIgnoreCase(action));
            String severity = root.path("severity").asText(stop ? "critical" : compliant ? "info" : "error");
            String correction = root.path("correction_prompt").asText("");
            if (correction.isBlank()) {
                correction = root.path("correction").asText("");
            }
            String reasoning = root.path("reasoning").asText("");

            List<String> violations = new ArrayList<>();
            JsonNode violationNode = root.path("violations");
            if (violationNode.isArray()) {
                violationNode.forEach(v -> {
                    String text = v.asText("");
                    if (!text.isBlank()) {
                        violations.add(text);
                    }
                });
            } else if (violationNode.isTextual() && !violationNode.asText().isBlank()) {
                violations.add(violationNode.asText());
            }

            if (!compliant && violations.isEmpty() && !reasoning.isBlank()) {
                violations.add(reasoning);
            }

            return new EnforcerDecision(compliant, stop, severity, violations, correction, reasoning);
        } catch (Exception e) {
            return stop(List.of("Enforcer judge JSON parse failed: " + e.getMessage()),
                    truncate(responseText, 240));
        }
    }

    static String extractJson(String text) {
        if (text == null) {
            return null;
        }
        String trimmed = text.trim();

        // Strip markdown code fences: ```json ... ``` or ``` ... ```
        if (trimmed.startsWith("```")) {
            int firstNewline = trimmed.indexOf('\n');
            if (firstNewline > 0) {
                trimmed = trimmed.substring(firstNewline + 1);
            }
            if (trimmed.endsWith("```")) {
                trimmed = trimmed.substring(0, trimmed.length() - 3);
            }
            trimmed = trimmed.trim();
        }

        // Direct JSON object
        if (trimmed.startsWith("{") && trimmed.endsWith("}")) {
            return trimmed;
        }

        // JSON embedded in prose — find the outermost { ... } with balanced braces
        int start = trimmed.indexOf('{');
        if (start < 0) return null;

        int depth = 0;
        boolean inString = false;
        boolean escape = false;
        for (int i = start; i < trimmed.length(); i++) {
            char c = trimmed.charAt(i);
            if (escape) {
                escape = false;
                continue;
            }
            if (c == '\\' && inString) {
                escape = true;
                continue;
            }
            if (c == '"') {
                inString = !inString;
                continue;
            }
            if (!inString) {
                if (c == '{') depth++;
                else if (c == '}') {
                    depth--;
                    if (depth == 0) {
                        return trimmed.substring(start, i + 1);
                    }
                }
            }
        }
        return null;
    }

    private static String truncate(String text, int max) {
        if (text == null || text.length() <= max) {
            return text;
        }
        return text.substring(0, max - 3) + "...";
    }
}
