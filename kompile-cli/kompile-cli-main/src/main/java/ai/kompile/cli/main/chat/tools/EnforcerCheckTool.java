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

package ai.kompile.cli.main.chat.tools;

import ai.kompile.cli.common.util.JsonUtils;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

/**
 * MCP tool for checking enforcer policy violations detected by the background monitor.
 * Reads from the enforcer interrupt file ({@code .kompile/enforcer-interrupt.json})
 * written by the background enforcer monitor process. Communication is purely through
 * file I/O — no runtime dependency on the monitor class itself.
 *
 * <p>Actions:
 * <ul>
 *   <li>{@code check} (default) — return any pending (unacknowledged) violations</li>
 *   <li>{@code acknowledge} — mark all violations as seen and write the file back</li>
 *   <li>{@code status} — show enforcer config summary and whether a config exists</li>
 * </ul></p>
 */
public class EnforcerCheckTool implements CliTool {

    private static final ObjectMapper MAPPER = JsonUtils.newStandardMapper()
            .enable(SerializationFeature.INDENT_OUTPUT);
    private static final ObjectMapper SCHEMA_MAPPER = MAPPER;

    private static final String INTERRUPT_FILE = ".kompile/enforcer-interrupt.json";
    private static final String CONFIG_FILE     = ".kompile/enforcer-config.json";

    @Override
    public String id() {
        return "enforcer_check";
    }

    @Override
    public String description() {
        return "Check for enforcer policy violations detected by the background monitor. "
                + "Call this before executing potentially dangerous operations (shell commands, "
                + "file deletions, force pushes). Returns any pending violations that need "
                + "attention, or 'clear' if no violations. "
                + "Actions: 'check' (default — get pending violations), "
                + "'acknowledge' (mark all violations as seen), "
                + "'status' (show enforcer config summary and monitor state).";
    }

    @Override
    public JsonNode parameterSchema() {
        ObjectNode schema = SCHEMA_MAPPER.createObjectNode();
        schema.put("type", "object");
        ObjectNode props = schema.putObject("properties");

        props.putObject("action")
                .put("type", "string")
                .put("description", "Action: 'check' (default), 'acknowledge', 'status'");

        return schema;
    }

    @Override
    public String permissionKey() {
        return "enforcer_check";
    }

    @Override
    public McpToolAnnotations mcpAnnotations() {
        return McpToolAnnotations.READ_ONLY;
    }

    @Override
    public ToolResult execute(JsonNode params, ToolContext context) throws ToolExecutionException {
        String action = params.path("action").asText("check");
        Path wd = context.getWorkingDirectory();

        try {
            return switch (action) {
                case "check"       -> executeCheck(wd);
                case "acknowledge" -> executeAcknowledge(wd);
                case "status"      -> executeStatus(wd);
                default -> ToolResult.error("Unknown action: " + action
                        + ". Use: check, acknowledge, status");
            };
        } catch (Exception e) {
            return ToolResult.error("Enforcer check error: " + e.getMessage());
        }
    }

    /**
     * Resolve the interrupt file path. Checks the KOMPILE_ENFORCER_INTERRUPT_FILE
     * env var first (set by PassthroughCommand for background monitor mode),
     * then falls back to the working directory default.
     */
    private Path resolveInterruptFile(Path wd) {
        String envPath = System.getenv("KOMPILE_ENFORCER_INTERRUPT_FILE");
        if (envPath != null && !envPath.isBlank()) {
            return Path.of(envPath);
        }
        return wd.resolve(INTERRUPT_FILE);
    }

    // ── Action implementations ──────────────────────────────────────────────

    private ToolResult executeCheck(Path wd) throws Exception {
        Path interruptFile = resolveInterruptFile(wd);

        if (!Files.exists(interruptFile)) {
            return new ToolResult("Enforcer Check",
                    "CLEAR — no policy violations detected.",
                    Map.of());
        }

        String raw = Files.readString(interruptFile).strip();
        if (raw.isEmpty()) {
            return new ToolResult("Enforcer Check",
                    "CLEAR — no policy violations detected.",
                    Map.of());
        }

        JsonNode root = MAPPER.readTree(raw);
        JsonNode violations = root.path("violations");

        if (!violations.isArray() || violations.isEmpty()) {
            return new ToolResult("Enforcer Check",
                    "CLEAR — no policy violations detected.",
                    Map.of());
        }

        // Collect only unacknowledged violations
        StringBuilder sb = new StringBuilder();
        int pendingCount = 0;

        for (JsonNode v : violations) {
            if (v.path("acknowledged").asBoolean(false)) {
                continue;
            }
            pendingCount++;
            sb.append("VIOLATION #").append(pendingCount).append("\n");

            String timestamp = v.path("timestamp").asText(null);
            if (timestamp != null) {
                sb.append("  Timestamp: ").append(timestamp).append("\n");
            }

            String rule = v.path("rule").asText(null);
            if (rule != null) {
                sb.append("  Rule:      ").append(rule).append("\n");
            }

            String severity = v.path("severity").asText(null);
            if (severity != null) {
                sb.append("  Severity:  ").append(severity).append("\n");
            }

            String message = v.path("message").asText(null);
            if (message != null) {
                sb.append("  Message:   ").append(message).append("\n");
            }

            String context = v.path("context").asText(null);
            if (context != null) {
                sb.append("  Context:   ").append(context).append("\n");
            }

            sb.append("\n");
        }

        if (pendingCount == 0) {
            return new ToolResult("Enforcer Check",
                    "CLEAR — all violations have been acknowledged.",
                    Map.of());
        }

        return new ToolResult("Enforcer Check",
                "VIOLATIONS DETECTED (" + pendingCount + " pending):\n\n" + sb.toString().trim(),
                Map.of("pendingCount", pendingCount));
    }

    private ToolResult executeAcknowledge(Path wd) throws Exception {
        Path interruptFile = resolveInterruptFile(wd);

        if (!Files.exists(interruptFile)) {
            return new ToolResult("Enforcer Check",
                    "No interrupt file found — nothing to acknowledge.",
                    Map.of());
        }

        String raw = Files.readString(interruptFile).strip();
        if (raw.isEmpty()) {
            return new ToolResult("Enforcer Check",
                    "Interrupt file is empty — nothing to acknowledge.",
                    Map.of());
        }

        JsonNode root = MAPPER.readTree(raw);
        JsonNode violations = root.path("violations");

        if (!violations.isArray() || violations.isEmpty()) {
            return new ToolResult("Enforcer Check",
                    "No violations found to acknowledge.",
                    Map.of());
        }

        // Set acknowledged = true on each violation
        int count = 0;
        for (JsonNode v : violations) {
            if (!v.path("acknowledged").asBoolean(false)) {
                ((ObjectNode) v).put("acknowledged", true);
                count++;
            }
        }

        // Write the updated JSON back
        Files.writeString(interruptFile, MAPPER.writeValueAsString(root));

        return new ToolResult("Enforcer Check",
                "Acknowledged " + count + " violation(s). Enforcer interrupt cleared.",
                Map.of("acknowledgedCount", count));
    }

    private ToolResult executeStatus(Path wd) throws Exception {
        Path configFile    = wd.resolve(CONFIG_FILE);
        Path interruptFile = resolveInterruptFile(wd);

        StringBuilder sb = new StringBuilder();
        sb.append("Enforcer Status\n");
        sb.append("──────────────────────────────────────\n");

        // Config file presence
        if (Files.exists(configFile)) {
            sb.append("Config:          present (").append(configFile).append(")\n");

            // Parse a few key fields for a quick summary
            try {
                JsonNode config = MAPPER.readTree(Files.readString(configFile));

                boolean keywordMode = config.path("keywordMode").asBoolean(true);
                sb.append("Mode:            ").append(keywordMode ? "keyword" : "LLM judge").append("\n");

                String semanticMode = config.path("semanticMode").asText("none");
                sb.append("Semantic mode:   ").append(semanticMode).append("\n");

                JsonNode bannedKeywords = config.path("bannedKeywords");
                int keywordCount = bannedKeywords.isArray() ? bannedKeywords.size() : 0;
                sb.append("Banned keywords: ").append(keywordCount).append("\n");

                JsonNode bannedTools = config.path("bannedTools");
                int toolCount = bannedTools.isArray() ? bannedTools.size() : 0;
                sb.append("Banned tools:    ").append(toolCount).append("\n");

                JsonNode diffRules = config.path("diffPatternRules");
                int ruleCount = diffRules.isArray() ? diffRules.size() : 0;
                sb.append("Diff rules:      ").append(ruleCount).append("\n");

            } catch (Exception e) {
                sb.append("  (could not parse config: ").append(e.getMessage()).append(")\n");
            }
        } else {
            sb.append("Config:          not found (").append(configFile).append(")\n");
            sb.append("                 Run 'enforcer_config' with action='init' to create one.\n");
        }

        // Monitor / interrupt file state
        sb.append("\n");
        if (Files.exists(interruptFile)) {
            sb.append("Monitor:         interrupt file present\n");
            try {
                String raw = Files.readString(interruptFile).strip();
                if (!raw.isEmpty()) {
                    JsonNode root = MAPPER.readTree(raw);
                    JsonNode violations = root.path("violations");
                    if (violations.isArray()) {
                        long pending = 0;
                        for (JsonNode v : violations) {
                            if (!v.path("acknowledged").asBoolean(false)) pending++;
                        }
                        sb.append("Violations:      ").append(violations.size())
                                .append(" total, ").append(pending).append(" pending\n");
                    }
                } else {
                    sb.append("Violations:      none\n");
                }
            } catch (Exception e) {
                sb.append("  (could not parse interrupt file: ").append(e.getMessage()).append(")\n");
            }
        } else {
            sb.append("Monitor:         no interrupt file (monitor not running or no violations)\n");
        }

        return new ToolResult("Enforcer Status", sb.toString().trim(), Map.of());
    }
}
