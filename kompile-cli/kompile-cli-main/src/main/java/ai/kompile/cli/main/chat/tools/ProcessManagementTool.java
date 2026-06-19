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
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * CLI tool for managing background processes. Allows the agent to launch
 * long-running commands in the background, check their status, read output,
 * and kill them.
 *
 * Actions:
 * <ul>
 *   <li><b>list</b> - List all tracked processes with status</li>
 *   <li><b>launch</b> - Launch a background process</li>
 *   <li><b>kill</b> - Kill a process by ID</li>
 *   <li><b>output</b> - Read captured output of a process</li>
 *   <li><b>status</b> - Get detailed status of a specific process</li>
 *   <li><b>cleanup</b> - Remove old completed process entries</li>
 * </ul>
 */
public class ProcessManagementTool implements CliTool {

    private static final int DEFAULT_TAIL_LINES = 50;

    private final BackgroundProcessManager processManager;

    public ProcessManagementTool(BackgroundProcessManager processManager) {
        this.processManager = processManager;
    }

    @Override
    public String id() { return "process"; }

    @Override
    public String description() {
        return "Manage background processes. Launch long-running commands (builds, servers, tests) " +
                "in the background and monitor their progress. Actions: list (show all processes), " +
                "launch (start a background command), kill (stop a process by ID), output (read process " +
                "stdout/stderr), status (detailed info for one process), cleanup (remove old entries).";
    }

    @Override
    public JsonNode parameterSchema() {
        ObjectMapper om = JsonUtils.standardMapper();
        ObjectNode schema = om.createObjectNode();
        schema.put("type", "object");
        ObjectNode props = schema.putObject("properties");

        ObjectNode action = props.putObject("action");
        action.put("type", "string");
        action.put("description", "Action to perform: list, launch, kill, output, status, cleanup");
        action.putArray("enum").add("list").add("launch").add("kill")
                .add("output").add("status").add("cleanup");

        ObjectNode processId = props.putObject("process_id");
        processId.put("type", "string");
        processId.put("description", "Process ID (e.g. proc-001) for kill, output, and status actions");

        ObjectNode command = props.putObject("command");
        command.put("type", "string");
        command.put("description", "Shell command to launch in the background (for launch action)");

        ObjectNode desc = props.putObject("description");
        desc.put("type", "string");
        desc.put("description", "Human-readable description of what the process does");

        ObjectNode tailLines = props.putObject("tail_lines");
        tailLines.put("type", "integer");
        tailLines.put("description", "Number of output lines to return (default: 50, for output action)");

        schema.putArray("required").add("action");
        return schema;
    }

    @Override
    public String permissionKey() { return "process"; }

    @Override
    public McpToolAnnotations mcpAnnotations() { return McpToolAnnotations.DESTRUCTIVE; }

    @Override
    public ToolResult execute(JsonNode params, ToolContext context) throws ToolExecutionException {
        String action = params.path("action").asText("");
        if (action.isEmpty()) {
            return ToolResult.error("action is required");
        }

        switch (action) {
            case "list":
                return executeList();
            case "launch":
                return executeLaunch(params, context);
            case "kill":
                return executeKill(params, context);
            case "output":
                return executeOutput(params);
            case "status":
                return executeStatus(params);
            case "cleanup":
                return executeCleanup();
            default:
                return ToolResult.error("Unknown action: " + action +
                        ". Valid actions: list, launch, kill, output, status, cleanup");
        }
    }

    private ToolResult executeList() {
        List<BackgroundProcessManager.ProcessEntry> all = processManager.listAll();
        if (all.isEmpty()) {
            return ToolResult.success("No tracked processes");
        }

        StringBuilder sb = new StringBuilder();
        sb.append(String.format("%-12s %-9s %-8s %-10s %-12s %-6s %s\n",
                "ID", "KIND", "PID", "STATE", "DURATION", "EXIT", "COMMAND"));
        sb.append("-".repeat(96)).append("\n");

        for (BackgroundProcessManager.ProcessEntry entry : all) {
            String duration = formatDuration(entry.getDuration());
            String exitStr = entry.getExitCode() != null ? String.valueOf(entry.getExitCode()) : "-";
            String cmd = entry.getCommand();
            if (cmd.length() > 40) {
                cmd = cmd.substring(0, 37) + "...";
            }

            String pidStr = entry.getPid() > 0 ? String.valueOf(entry.getPid()) : "-";
            sb.append(String.format("%-12s %-9s %-8s %-10s %-12s %-6s %s\n",
                    entry.getId(),
                    entry.getKind().label(),
                    pidStr,
                    entry.getState(),
                    duration,
                    exitStr,
                    cmd));

            if (entry.getDescription() != null && !entry.getDescription().isEmpty()
                    && !entry.getDescription().equals(entry.getCommand())) {
                sb.append(String.format("%-12s %s\n", "", "  -> " + entry.getDescription()));
            }
            if (!entry.getMetadata().isEmpty()) {
                sb.append(String.format("%-12s %s\n", "", "  -> " + formatMetadata(entry.getMetadata())));
            }
        }

        long running = all.stream().filter(BackgroundProcessManager.ProcessEntry::isRunning).count();
        sb.append("\n").append(all.size()).append(" total, ").append(running).append(" running");

        return ToolResult.success("processes", sb.toString(),
                Map.of("total", all.size(), "running", running));
    }

    private ToolResult executeLaunch(JsonNode params, ToolContext context) throws ToolExecutionException {
        String command = params.path("command").asText("");
        String description = params.path("description").asText(command);

        if (command.isEmpty()) {
            return ToolResult.error("command is required for launch action");
        }

        // Require permission for launching processes
        context.checkPermission(permissionKey(), "Launch background process: " + description);

        try {
            BackgroundProcessManager.ProcessEntry entry =
                    processManager.launch(command, description, context.getWorkingDirectory());

            String output = String.format("Launched background process:\n" +
                            "  ID:      %s\n" +
                            "  PID:     %d\n" +
                            "  Command: %s\n" +
                            "  Output:  %s\n" +
                            "  Desc:    %s",
                    entry.getId(), entry.getPid(), command,
                    entry.getOutputFile(), description);

            return ToolResult.success("launched " + entry.getId(), output,
                    Map.of("processId", entry.getId(), "pid", entry.getPid()));

        } catch (IOException e) {
            return ToolResult.error("Failed to launch process: " + e.getMessage());
        }
    }

    private ToolResult executeKill(JsonNode params, ToolContext context) throws ToolExecutionException {
        String processId = params.path("process_id").asText("");
        if (processId.isEmpty()) {
            return ToolResult.error("process_id is required for kill action");
        }

        // Require permission for killing processes
        context.checkPermission(permissionKey(), "Kill process: " + processId);

        BackgroundProcessManager.ProcessEntry entry = processManager.get(processId);
        if (entry == null) {
            return ToolResult.error("Process not found: " + processId);
        }

        if (!entry.isRunning()) {
            return ToolResult.success("Process " + processId + " is already " + entry.getState() +
                    " (exit code: " + entry.getExitCode() + ")");
        }

        boolean killed = processManager.kill(processId);
        if (killed) {
            String pidText = entry.getPid() > 0 ? " (PID " + entry.getPid() + ")" : "";
            return ToolResult.success("killed " + processId,
                    "Process " + processId + pidText + " killed.\n" +
                            "Duration: " + formatDuration(entry.getDuration()),
                    Map.of("processId", processId,
                            "pid", entry.getPid(),
                            "kind", entry.getKind().label()));
        } else {
            return ToolResult.error("Failed to kill process " + processId);
        }
    }

    private ToolResult executeOutput(JsonNode params) {
        String processId = params.path("process_id").asText("");
        if (processId.isEmpty()) {
            return ToolResult.error("process_id is required for output action");
        }

        int tailLines = params.path("tail_lines").asInt(DEFAULT_TAIL_LINES);
        if (tailLines <= 0) tailLines = DEFAULT_TAIL_LINES;

        BackgroundProcessManager.ProcessEntry entry = processManager.get(processId);
        if (entry == null) {
            return ToolResult.error("Process not found: " + processId);
        }

        String output = processManager.readOutput(processId, tailLines);

        String pidText = entry.getPid() > 0 ? "PID " + entry.getPid() : "no pid";
        String header = String.format("[%s] %s | %s | %s | %s\n---\n",
                entry.getId(), entry.getKind().label(), pidText, entry.getState(),
                formatDuration(entry.getDuration()));

        return ToolResult.success("output " + processId, header + output,
                Map.of("processId", processId, "state", entry.getState().name(),
                        "tailLines", tailLines));
    }

    private ToolResult executeStatus(JsonNode params) {
        String processId = params.path("process_id").asText("");
        if (processId.isEmpty()) {
            return ToolResult.error("process_id is required for status action");
        }

        BackgroundProcessManager.ProcessEntry entry = processManager.get(processId);
        if (entry == null) {
            return ToolResult.error("Process not found: " + processId);
        }

        StringBuilder sb = new StringBuilder();
        sb.append("Process: ").append(entry.getId()).append("\n");
        sb.append("  Kind:        ").append(entry.getKind().label()).append("\n");
        sb.append("  PID:         ").append(entry.getPid() > 0 ? String.valueOf(entry.getPid()) : "-").append("\n");
        sb.append("  State:       ").append(entry.getState()).append("\n");
        sb.append("  Command:     ").append(entry.getCommand()).append("\n");
        sb.append("  Description: ").append(entry.getDescription()).append("\n");
        if (!entry.getMetadata().isEmpty()) {
            sb.append("  Metadata:    ").append(formatMetadata(entry.getMetadata())).append("\n");
        }
        sb.append("  Started:     ").append(entry.getStartTime()).append("\n");
        if (entry.getEndTime() != null) {
            sb.append("  Ended:       ").append(entry.getEndTime()).append("\n");
        }
        sb.append("  Duration:    ").append(formatDuration(entry.getDuration())).append("\n");
        if (entry.getExitCode() != null) {
            sb.append("  Exit Code:   ").append(entry.getExitCode()).append("\n");
        }
        sb.append("  Output File: ").append(entry.getOutputFile()).append("\n");

        return ToolResult.success("status " + processId, sb.toString(),
                Map.of("processId", processId,
                        "state", entry.getState().name(),
                        "pid", entry.getPid()));
    }

    private ToolResult executeCleanup() {
        int removed = processManager.cleanup();
        return ToolResult.success("Cleaned up " + removed + " completed process entries",
                "Removed " + removed + " old entries. " +
                        processManager.listAll().size() + " entries remaining (" +
                        processManager.listRunning().size() + " running).",
                Map.of("removed", removed));
    }

    private static String formatMetadata(Map<String, String> metadata) {
        if (metadata == null || metadata.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        metadata.forEach((key, value) -> {
            if (sb.length() > 0) {
                sb.append(", ");
            }
            sb.append(key).append("=").append(value);
        });
        return sb.toString();
    }

    /**
     * Format a duration as a human-readable string.
     */
    public static String formatDuration(Duration d) {
        long totalSeconds = d.getSeconds();
        if (totalSeconds < 60) {
            long millis = d.toMillis();
            if (millis < 1000) {
                return millis + "ms";
            }
            return String.format("%.1fs", millis / 1000.0);
        }
        long minutes = totalSeconds / 60;
        long seconds = totalSeconds % 60;
        if (minutes < 60) {
            return String.format("%dm%ds", minutes, seconds);
        }
        long hours = minutes / 60;
        minutes = minutes % 60;
        return String.format("%dh%dm%ds", hours, minutes, seconds);
    }
}
