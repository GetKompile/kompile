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
import ai.kompile.cli.main.chat.enforcer.EnforcerToolCallDecision;
import ai.kompile.cli.main.chat.enforcer.ShellMandatePolicy;
import ai.kompile.cli.main.coordination.CoordinationStateManager;
import ai.kompile.cli.main.coordination.ProcessCoordEntry;
import ai.kompile.utils.StringUtils;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

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
 *   <li><b>monitor</b> - Wake the agent when a specific process exits</li>
 *   <li><b>unmonitor</b> - Cancel a process completion monitor</li>
 *   <li><b>monitors</b> - List active process completion monitors</li>
 *   <li><b>cleanup</b> - Remove old completed process entries</li>
 * </ul>
 */
public class ProcessManagementTool implements CliTool {

    private static final int DEFAULT_TAIL_LINES = 50;
    private static final int DEFAULT_STREAM_SECONDS = 5;
    private static final int MAX_STREAM_SECONDS = 30;
    private static final int MAX_STREAM_OUTPUT_CHARS = 30_000;
    private static final int MAX_STREAM_LINE_CHARS = 4_000;

    private final BackgroundProcessManager processManager;
    private final CoordinationStateManager coordinator;

    public ProcessManagementTool(BackgroundProcessManager processManager) {
        this(processManager, null);
    }

    public ProcessManagementTool(BackgroundProcessManager processManager,
                                 CoordinationStateManager coordinator) {
        this.processManager = processManager;
        this.coordinator = coordinator;
        if (processManager != null && coordinator != null) {
            processManager.addExitListener(this::syncProcessState);
        }
    }

    @Override
    public String id() { return "process"; }

    @Override
    public String description() {
        return "Manage background processes. Every launch detaches immediately and installs a one-shot " +
                "completion monitor at the host boundary; multiple commands may run concurrently. " +
                "Launched processes are also published " +
                "to edit_coordinator when coordination is available, so other agents can see running builds. " +
                "Shell content reads/writes require dedicated file or memory tools. Filesystem administration " +
                "(rm, mv, mkdir, chmod) is risk-classified and subject to permissions and judge policy. " +
                "Actions: list (show local and shared WIP processes), launch (start a background command), " +
                "kill (stop a local process by ID), output (live tail snapshot), stream (follow output briefly), " +
                "status (detailed info plus recent output), monitor (wake this agent when one local process exits), " +
                "unmonitor (cancel a monitor), monitors (list active monitors), cleanup (remove old local entries).";
    }

    @Override
    public JsonNode parameterSchema() {
        ObjectMapper om = JsonUtils.standardMapper();
        ObjectNode schema = om.createObjectNode();
        schema.put("type", "object");
        ObjectNode props = schema.putObject("properties");

        ObjectNode action = props.putObject("action");
        action.put("type", "string");
        action.put("description", "Action to perform: list, launch, kill, output, stream, status, monitor, unmonitor, monitors, cleanup");
        action.putArray("enum").add("list").add("launch").add("kill")
                .add("output").add("stream").add("status").add("monitor")
                .add("unmonitor").add("monitors").add("cleanup");

        ObjectNode processId = props.putObject("process_id");
        processId.put("type", "string");
        processId.put("description", "Local process ID (e.g. proc-001) for kill, output, status, monitor, and unmonitor actions");

        ObjectNode command = props.putObject("command");
        command.put("type", "string");
        command.put("description", "Shell command to launch in the background (for launch action)");

        ObjectNode desc = props.putObject("description");
        desc.put("type", "string");
        desc.put("description", "Human-readable description of what the process does");

        ObjectNode monitor = props.putObject("monitor");
        monitor.put("type", "boolean");
        monitor.put("default", true);
        monitor.put("description", "Compatibility flag for launch. The harness always installs a one-shot completion monitor; false is ignored.");

        ObjectNode monitorMessage = props.putObject("monitor_message");
        monitorMessage.put("type", "string");
        monitorMessage.put("description", "Optional instructions included in the agent wake-up for monitor or monitored launch actions");

        ObjectNode tailLines = props.putObject("tail_lines");
        tailLines.put("type", "integer");
        tailLines.put("description", "Number of output lines to return (default: 50, for output/status/stream actions)");

        ObjectNode followSeconds = props.putObject("follow_seconds");
        followSeconds.put("type", "integer");
        followSeconds.put("description", "For stream action, seconds to follow a running output file (default: 5, max: 30)");

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
            case "stream":
                return executeStream(params, context);
            case "status":
                return executeStatus(params);
            case "monitor":
                return executeMonitor(params);
            case "unmonitor":
                return executeUnmonitor(params);
            case "monitors":
                return executeMonitors();
            case "cleanup":
                return executeCleanup();
            default:
                return ToolResult.error("Unknown action: " + action +
                        ". Valid actions: list, launch, kill, output, stream, status, monitor, unmonitor, monitors, cleanup");
        }
    }

    private ToolResult executeList() {
        List<BackgroundProcessManager.ProcessEntry> all = processManager.listAll();
        List<ProcessCoordEntry> shared = sharedProcessEntries(all);
        if (all.isEmpty() && shared.isEmpty()) {
            return ToolResult.success("No tracked processes");
        }

        StringBuilder sb = new StringBuilder();
        sb.append(String.format("%-12s %-10s %-9s %-8s %-10s %-12s %-6s %s\n",
                "ID", "OWNER", "KIND", "PID", "STATE", "DURATION", "EXIT", "COMMAND"));
        sb.append("-".repeat(110)).append("\n");

        for (BackgroundProcessManager.ProcessEntry entry : all) {
            syncProcessState(entry);
            String duration = formatDuration(entry.getDuration());
            String exitStr = entry.getExitCode() != null ? String.valueOf(entry.getExitCode()) : "-";
            String cmd = StringUtils.truncateToLength(entry.getCommand(), 40);
            String pidStr = entry.getPid() > 0 ? String.valueOf(entry.getPid()) : "-";
            sb.append(String.format("%-12s %-10s %-9s %-8s %-10s %-12s %-6s %s\n",
                    entry.getId(),
                    "local",
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
            appendRecentOutputSummary(sb, entry.getId(), entry.getOutputFile(), 2);
            if (!entry.getMetadata().isEmpty()) {
                sb.append(String.format("%-12s %s\n", "", "  -> " + formatMetadata(entry.getMetadata())));
            }
        }

        for (ProcessCoordEntry entry : shared) {
            String duration = formatDuration(entry.getDuration());
            String pidStr = entry.getPid() > 0 ? String.valueOf(entry.getPid()) : "-";
            String owner = StringUtils.truncateToLength(firstNonBlank(entry.getAgentName(), entry.getSessionId(), "shared"), 10);
            sb.append(String.format("%-12s %-10s %-9s %-8s %-10s %-12s %-6s %s\n",
                    entry.getProcessId(),
                    owner,
                    "shared",
                    pidStr,
                    firstNonBlank(entry.getState(), "RUNNING"),
                    duration,
                    "-",
                    StringUtils.truncateToLength(entry.getCommand(), 40)));
            if (entry.getDescription() != null && !entry.getDescription().isBlank()) {
                sb.append(String.format("%-12s %s\n", "", "  -> " + entry.getDescription()));
            }
            appendRecentOutputSummary(sb, entry.getProcessId(), pathOrNull(entry.getOutputFile()), 2);
        }

        long localRunning = all.stream().filter(BackgroundProcessManager.ProcessEntry::isRunning).count();
        long sharedRunning = shared.stream().filter(e -> isRunningState(e.getState())).count();
        int total = all.size() + shared.size();
        long running = localRunning + sharedRunning;
        sb.append("\n").append(total).append(" total, ").append(running).append(" running");
        if (!shared.isEmpty()) {
            sb.append(" (").append(shared.size()).append(" shared)");
        }

        return ToolResult.success("processes", sb.toString(),
                Map.of("total", total, "running", running, "shared", shared.size()));
    }

    private ToolResult executeLaunch(JsonNode params, ToolContext context) throws ToolExecutionException {
        String command = params.path("command").asText("");
        String description = params.path("description").asText(command);

        if (command.isEmpty()) {
            return ToolResult.error("command is required for launch action");
        }

        EnforcerToolCallDecision mandate = ShellMandatePolicy.evaluateCommand(id(), command);
        if (mandate != null) {
            return ToolResult.error(mandate.getCorrectionPrompt());
        }

        // Require permission for launching processes
        context.checkPermission(permissionKey(), "Launch background process: " + description);
        context.checkPermission(BashTool.commandPermissionKey(command), "Background command: " + command);

        try {
            boolean monitorForced = params.has("monitor")
                    && !params.path("monitor").asBoolean(true);
            String monitorMessage = params.path("monitor_message").asText("");
            ResourcePolicy.Decision resource = ResourcePolicy.ACTIVE_LAUNCH.get();
            if (resource == null) resource = ResourcePolicy.classify(
                    coordinator != null ? coordinator.getProjectRoot() : context.getWorkingDirectory(), id(), params);
            BackgroundProcessManager.ProcessEntry entry =
                    processManager.launchMonitored(command, description,
                            context.getWorkingDirectory(), monitorMessage);
            publishProcess(entry, context, resource.resourceClass());

            String output = String.format("Launched background process:\n" +
                            "  ID:      %s\n" +
                            "  PID:     %d\n" +
                            "  Command: %s\n" +
                            "  Output:  %s\n" +
                            "  Desc:    %s\n" +
                            "  Monitor: agent wake-up on exit (required by harness)%s",
                    entry.getId(), entry.getPid(), command,
                    entry.getOutputFile(), description,
                    monitorForced ? " — caller monitor=false ignored" : "");

            return ToolResult.success("launched " + entry.getId(), output,
                    Map.of("processId", entry.getId(), "pid", entry.getPid(),
                            "monitored", true, "monitorEnforced", true,
                            "monitorForced", monitorForced));

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
            syncProcessState(entry);
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

        int tailLines = normalizeTailLines(params.path("tail_lines").asInt(DEFAULT_TAIL_LINES));

        BackgroundProcessManager.ProcessEntry entry = processManager.get(processId);
        if (entry != null) {
            syncProcessState(entry);
            String output = processManager.readOutput(processId, tailLines);
            String header = localOutputHeader(entry, "live tail snapshot");
            return ToolResult.success("output " + processId, header + output,
                    Map.of("processId", processId, "state", entry.getState().name(),
                            "tailLines", tailLines, "scope", "local"));
        }

        ProcessCoordEntry shared = findSharedProcess(processId);
        if (shared == null) {
            return ToolResult.error("Process not found: " + processId);
        }

        Path outputFile = pathOrNull(shared.getOutputFile());
        String output = BackgroundProcessManager.readOutputFile(outputFile, tailLines);
        String header = sharedOutputHeader(shared, "live tail snapshot");
        return ToolResult.success("output " + shared.getProcessId(), header + output,
                Map.of("processId", shared.getProcessId(),
                        "state", firstNonBlank(shared.getState(), "RUNNING"),
                        "tailLines", tailLines, "scope", "shared"));
    }

    private ToolResult executeStream(JsonNode params, ToolContext context) {
        String processId = params.path("process_id").asText("");
        if (processId.isEmpty()) {
            return ToolResult.error("process_id is required for stream action");
        }

        int tailLines = normalizeTailLines(params.path("tail_lines").asInt(DEFAULT_TAIL_LINES));
        int followSeconds = params.path("follow_seconds").asInt(DEFAULT_STREAM_SECONDS);
        if (followSeconds < 0) followSeconds = 0;
        if (followSeconds > MAX_STREAM_SECONDS) followSeconds = MAX_STREAM_SECONDS;
        Consumer<String> liveOutput = context == null ? null : context.getOutputConsumer();
        BooleanSupplier abortProbe = context == null
                ? () -> Thread.currentThread().isInterrupted()
                : context::isAborted;

        BackgroundProcessManager.ProcessEntry local = processManager.get(processId);
        if (local != null) {
            syncProcessState(local);
            String liveHeader = localOutputHeader(local, "stream followed " + followSeconds + "s");
            emitBlock(liveOutput, liveHeader);
            String output = followOutput(local.getOutputFile(), tailLines, followSeconds,
                    () -> {
                        syncProcessState(local);
                        return local.isRunning();
                    }, abortProbe, liveOutput);
            String resultHeader = localOutputHeader(
                    local, "stream followed " + followSeconds + "s");
            return ToolResult.success("stream " + processId,
                    resultHeader + output,
                    Map.of("processId", processId, "state", local.getState().name(),
                            "tailLines", tailLines, "followSeconds", followSeconds,
                            ToolResult.OUTPUT_STREAMED_METADATA, liveOutput != null,
                            "scope", "local"));
        }

        ProcessCoordEntry shared = findSharedProcess(processId);
        if (shared == null) {
            return ToolResult.error("Process not found: " + processId);
        }
        Path outputFile = pathOrNull(shared.getOutputFile());
        String liveHeader = sharedOutputHeader(shared, "stream followed " + followSeconds + "s");
        emitBlock(liveOutput, liveHeader);
        String output = followOutput(outputFile, tailLines, followSeconds,
                () -> isSharedProcessRunning(shared), abortProbe, liveOutput);
        ProcessCoordEntry latest = findSharedProcess(firstNonBlank(shared.getSessionId(), "") + "/" + shared.getProcessId());
        if (latest == null) latest = shared;
        String resultHeader = sharedOutputHeader(
                latest, "stream followed " + followSeconds + "s");
        return ToolResult.success("stream " + latest.getProcessId(),
                resultHeader + output,
                Map.of("processId", latest.getProcessId(),
                        "state", firstNonBlank(latest.getState(), "RUNNING"),
                        "tailLines", tailLines, "followSeconds", followSeconds,
                        ToolResult.OUTPUT_STREAMED_METADATA, liveOutput != null,
                        "scope", "shared"));
    }

    private ToolResult executeStatus(JsonNode params) {
        String processId = params.path("process_id").asText("");
        if (processId.isEmpty()) {
            return ToolResult.error("process_id is required for status action");
        }
        int tailLines = normalizeTailLines(params.path("tail_lines").asInt(20));

        BackgroundProcessManager.ProcessEntry entry = processManager.get(processId);
        if (entry != null) {
            syncProcessState(entry);

            StringBuilder sb = new StringBuilder();
            sb.append("Process: ").append(entry.getId()).append("\n");
            sb.append("  Scope:       local\n");
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
            appendRecentOutputBlock(sb, entry.getOutputFile(), tailLines);

            return ToolResult.success("status " + processId, sb.toString(),
                    Map.of("processId", processId,
                            "state", entry.getState().name(),
                            "pid", entry.getPid(),
                            "scope", "local"));
        }

        ProcessCoordEntry shared = findSharedProcess(processId);
        if (shared == null) {
            return ToolResult.error("Process not found: " + processId);
        }

        StringBuilder sb = new StringBuilder();
        sb.append("Process: ").append(shared.getProcessId()).append("\n");
        sb.append("  Scope:       shared\n");
        sb.append("  Session:     ").append(firstNonBlank(shared.getSessionId(), "-")).append("\n");
        sb.append("  Agent:       ").append(firstNonBlank(shared.getAgentName(), "-")).append("\n");
        sb.append("  PID:         ").append(shared.getPid() > 0 ? String.valueOf(shared.getPid()) : "-").append("\n");
        sb.append("  State:       ").append(firstNonBlank(shared.getState(), "RUNNING")).append("\n");
        sb.append("  Command:     ").append(firstNonBlank(shared.getCommand(), "-")).append("\n");
        sb.append("  Description: ").append(firstNonBlank(shared.getDescription(), "-")).append("\n");
        sb.append("  Started:     ").append(shared.getStartedAt()).append("\n");
        sb.append("  Duration:    ").append(formatDuration(shared.getDuration())).append("\n");
        sb.append("  Output File: ").append(firstNonBlank(shared.getOutputFile(), "-")).append("\n");
        appendRecentOutputBlock(sb, pathOrNull(shared.getOutputFile()), tailLines);

        return ToolResult.success("status " + shared.getProcessId(), sb.toString(),
                Map.of("processId", shared.getProcessId(),
                        "state", firstNonBlank(shared.getState(), "RUNNING"),
                        "pid", shared.getPid(),
                        "scope", "shared"));
    }

    private ToolResult executeMonitor(JsonNode params) {
        String processId = params.path("process_id").asText("");
        if (processId.isBlank()) {
            return ToolResult.error("process_id is required for monitor action");
        }
        BackgroundProcessManager.ProcessEntry entry = processManager.get(processId);
        if (entry == null) {
            return ToolResult.error("Only local tracked processes can be monitored: " + processId);
        }
        if (!entry.isRunning()) {
            return ToolResult.error("Process " + processId + " is already " + entry.getState()
                    + "; no monitor was created");
        }
        String message = params.path("monitor_message").asText("");
        BackgroundProcessManager.ProcessMonitor monitor =
                processManager.monitor(processId, message);
        if (monitor == null) {
            return ToolResult.error("Process " + processId
                    + " cannot be monitored or exited during registration");
        }
        String detail = "Monitoring " + processId + ". This agent will be woken when it exits."
                + (monitor.message().isBlank() ? ""
                : "\nWake-up instructions: " + monitor.message());
        return ToolResult.success("monitoring " + processId, detail,
                Map.of("processId", processId, "message", monitor.message()));
    }

    private ToolResult executeUnmonitor(JsonNode params) {
        String processId = params.path("process_id").asText("");
        if (processId.isBlank()) {
            return ToolResult.error("process_id is required for unmonitor action");
        }
        if (!processManager.removeMonitor(processId)) {
            return ToolResult.error("No active monitor for process: " + processId);
        }
        return ToolResult.success("unmonitored " + processId,
                "Cancelled the completion monitor for " + processId + ".",
                Map.of("processId", processId));
    }

    private ToolResult executeMonitors() {
        List<BackgroundProcessManager.ProcessMonitor> monitors = processManager.listMonitors();
        if (monitors.isEmpty()) {
            return ToolResult.success("No active process monitors");
        }
        StringBuilder output = new StringBuilder();
        for (BackgroundProcessManager.ProcessMonitor monitor : monitors) {
            BackgroundProcessManager.ProcessEntry entry = processManager.get(monitor.processId());
            output.append(monitor.processId());
            if (entry != null) {
                output.append(" · ").append(entry.getState())
                        .append(" · ").append(entry.getDescription());
            }
            if (!monitor.message().isBlank()) {
                output.append("\n  -> ").append(monitor.message());
            }
            output.append("\n");
        }
        return ToolResult.success("process monitors", output.toString().stripTrailing(),
                Map.of("count", monitors.size()));
    }

    private ToolResult executeCleanup() {
        int removed = processManager.cleanup();
        return ToolResult.success("Cleaned up " + removed + " completed process entries",
                "Removed " + removed + " old entries. " +
                        processManager.listAll().size() + " entries remaining (" +
                        processManager.listRunning().size() + " running).",
                Map.of("removed", removed));
    }

    private void publishProcess(BackgroundProcessManager.ProcessEntry entry, ToolContext context, String resourceClass) {
        if (coordinator == null || entry == null) return;
        var agent = context != null ? context.getAgent() : null;
        String roleName = null;
        if (agent != null) {
            roleName = agent.getRoleName() != null && !agent.getRoleName().isBlank()
                    ? agent.getRoleName() : agent.getName();
        }
        coordinator.publishProcess(entry.getId(), entry.getCommand(), entry.getDescription(),
                entry.getPid(), entry.getState().name(),
                entry.getOutputFile() != null ? entry.getOutputFile().toString() : null,
                null, roleName, entry.getKind().label(), entry.getStartTime(),
                entry.getEndTime(), entry.getExitCode(), resourceClass);
        // A very short command can exit between reading the state above and the
        // coordination file becoming visible. One post-publication sync closes
        // that race; later exits are covered by the registered listener.
        syncProcessState(entry);
    }

    private void syncProcessState(BackgroundProcessManager.ProcessEntry entry) {
        if (coordinator == null || entry == null) return;
        coordinator.updateProcessState(entry.getId(), entry.getState().name(),
                entry.getEndTime(), entry.getExitCode());
    }

    private List<ProcessCoordEntry> sharedProcessEntries(List<BackgroundProcessManager.ProcessEntry> localEntries) {
        if (coordinator == null) return List.of();
        Set<String> localIds = new HashSet<>();
        if (localEntries != null) {
            for (BackgroundProcessManager.ProcessEntry entry : localEntries) {
                localIds.add(entry.getId());
            }
        }
        String localSession = coordinator.getSessionId();
        List<ProcessCoordEntry> shared = new ArrayList<>();
        try {
            for (ProcessCoordEntry entry : coordinator.queryProcesses()) {
                if (entry == null || entry.getProcessId() == null) continue;
                boolean localMirror = firstNonBlank(entry.getSessionId()).equals(localSession)
                        && localIds.contains(entry.getProcessId());
                if (!localMirror) {
                    shared.add(entry);
                }
            }
        } catch (Exception ignored) {
            return List.of();
        }
        return shared;
    }

    private ProcessCoordEntry findSharedProcess(String processId) {
        String wanted = processId == null ? "" : processId.trim();
        if (wanted.isEmpty() || coordinator == null) return null;
        ProcessCoordEntry fallback = null;
        try {
            for (ProcessCoordEntry entry : coordinator.queryProcesses()) {
                if (!matchesProcess(entry, wanted)) continue;
                if (isRunningState(entry.getState())) {
                    return entry;
                }
                if (fallback == null) fallback = entry;
            }
        } catch (Exception ignored) {
            return null;
        }
        return fallback;
    }

    private static boolean matchesProcess(ProcessCoordEntry entry, String wanted) {
        if (entry == null || wanted == null || wanted.isBlank()) return false;
        String processId = firstNonBlank(entry.getProcessId());
        String sessionId = firstNonBlank(entry.getSessionId());
        return wanted.equals(processId)
                || (!sessionId.isBlank() && wanted.equals(sessionId + "/" + processId));
    }

    private boolean isSharedProcessRunning(ProcessCoordEntry entry) {
        if (entry == null || coordinator == null) return false;
        try {
            for (ProcessCoordEntry candidate : coordinator.queryProcesses()) {
                if (candidate == null) continue;
                boolean sameSession = firstNonBlank(candidate.getSessionId()).equals(firstNonBlank(entry.getSessionId()));
                boolean sameProcess = firstNonBlank(candidate.getProcessId()).equals(firstNonBlank(entry.getProcessId()));
                if (sameSession && sameProcess) {
                    return isRunningState(candidate.getState());
                }
            }
        } catch (Exception ignored) {
            return false;
        }
        return false;
    }

    private static boolean isRunningState(String state) {
        if (state == null || state.isBlank()) return true;
        String normalized = state.trim().toUpperCase(Locale.ROOT);
        return normalized.equals("RUNNING") || normalized.equals("STARTING") || normalized.equals("ACTIVE");
    }

    private static int normalizeTailLines(int tailLines) {
        if (tailLines <= 0) return DEFAULT_TAIL_LINES;
        return Math.min(tailLines, 2_000);
    }

    private static String followOutput(Path outputFile, int tailLines, int followSeconds,
                                       BooleanSupplier runningProbe,
                                       BooleanSupplier abortProbe,
                                       Consumer<String> liveOutput) {
        FollowBuffer buffer = new FollowBuffer(tailLines + 2);
        long observedLines = 0;
        try {
            BackgroundProcessManager.TailResult initial = BackgroundProcessManager.tailOutputFile(outputFile, tailLines);
            appendTailResult(buffer, initial, liveOutput);
            observedLines = initial.omittedLines() + initial.lines().size();
        } catch (IOException e) {
            String error = "Error reading output: " + e.getMessage();
            buffer.add(error);
            emitLine(liveOutput, error);
        }

        Instant deadline = Instant.now().plusSeconds(followSeconds);
        while (followSeconds > 0 && !abortProbe.getAsBoolean()
                && runningProbe.getAsBoolean() && Instant.now().isBefore(deadline)) {
            try {
                Thread.sleep(500);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
            try {
                LinesAfterResult after = readLinesAfter(outputFile, observedLines, Math.max(200, tailLines * 4));
                observedLines = after.totalLines();
                appendLinesAfterResult(buffer, after, liveOutput);
            } catch (IOException e) {
                String error = "Error reading output: " + e.getMessage();
                buffer.add(error);
                emitLine(liveOutput, error);
                break;
            }
        }

        if (buffer.isEmpty()) {
            String empty = outputFile == null || !Files.exists(outputFile)
                    ? "(no output captured yet)" : "(no output)";
            buffer.add(empty);
            emitLine(liveOutput, empty);
        }
        buffer.add("---");
        emitLine(liveOutput, "---");
        String status = abortProbe.getAsBoolean()
                ? "stream status: cancelled"
                : runningProbe.getAsBoolean()
                        ? "stream status: process still running"
                        : "stream status: process no longer running";
        buffer.add(status);
        emitLine(liveOutput, status);
        return buffer.render();
    }

    private static void appendTailResult(FollowBuffer buffer, BackgroundProcessManager.TailResult tail,
                                         Consumer<String> liveOutput) {
        if (tail == null || tail.lines().isEmpty()) return;
        if (tail.omittedLines() > 0) {
            String omitted = "... (" + tail.omittedLines() + " earlier lines omitted)";
            buffer.add(omitted);
            emitLine(liveOutput, omitted);
        }
        for (String line : tail.lines()) {
            buffer.add(line);
            emitLine(liveOutput, line);
        }
    }

    private static void appendLinesAfterResult(FollowBuffer buffer, LinesAfterResult after,
                                               Consumer<String> liveOutput) {
        if (after == null || after.lines().isEmpty()) return;
        if (after.omittedNewLines() > 0) {
            String omitted = "... (" + after.omittedNewLines() + " new lines omitted)";
            buffer.add(omitted);
            emitLine(liveOutput, omitted);
        }
        for (String line : after.lines()) {
            buffer.add(line);
            emitLine(liveOutput, line);
        }
    }

    private static void emitBlock(Consumer<String> output, String block) {
        if (output == null || block == null || block.isEmpty()) return;
        for (String line : block.stripTrailing().split("\\R", -1)) {
            emitLine(output, line);
        }
    }

    private static void emitLine(Consumer<String> output, String line) {
        if (output == null) return;
        try {
            output.accept(line == null ? "" : line);
        } catch (RuntimeException ignored) {
            // A detached TUI/log sink must not terminate process following.
        }
    }

    private static final class FollowBuffer {
        private final int maxLines;
        private final ArrayDeque<String> lines = new ArrayDeque<>();
        private int chars;
        private long omittedLines;

        private FollowBuffer(int maxLines) {
            this.maxLines = Math.max(3, maxLines);
        }

        private void add(String value) {
            String line = value == null ? "" : value;
            if (line.length() > MAX_STREAM_LINE_CHARS) {
                line = line.substring(0, MAX_STREAM_LINE_CHARS - 1) + "…";
            }
            lines.addLast(line);
            chars += line.length();
            while (lines.size() > maxLines || chars > MAX_STREAM_OUTPUT_CHARS) {
                chars -= lines.removeFirst().length();
                omittedLines++;
            }
        }

        private boolean isEmpty() {
            return lines.isEmpty();
        }

        private String render() {
            StringBuilder output = new StringBuilder(chars + lines.size() + 64);
            if (omittedLines > 0) {
                appendWithNewline(output,
                        "... (" + omittedLines + " streamed lines omitted)");
            }
            for (String line : lines) appendWithNewline(output, line);
            return output.toString().stripTrailing();
        }
    }

    private static LinesAfterResult readLinesAfter(Path file, long skipLines, int maxLines) throws IOException {
        if (file == null || !Files.exists(file)) {
            return new LinesAfterResult(List.of(), 0, 0);
        }
        List<String> result = new ArrayList<>();
        long totalLines = 0;
        long newLines = 0;
        try (java.util.stream.Stream<String> lines = Files.lines(file)) {
            Iterator<String> iterator = lines.iterator();
            while (iterator.hasNext()) {
                totalLines++;
                String line = iterator.next();
                if (totalLines <= skipLines) continue;
                newLines++;
                result.add(line);
                while (result.size() > maxLines) {
                    result.remove(0);
                }
            }
        }
        return new LinesAfterResult(result, totalLines, Math.max(0, newLines - result.size()));
    }

    private record LinesAfterResult(List<String> lines, long totalLines, long omittedNewLines) {}

    private static String localOutputHeader(BackgroundProcessManager.ProcessEntry entry, String label) {
        String pidText = entry.getPid() > 0 ? "PID " + entry.getPid() : "no pid";
        return String.format("[%s] %s | %s | %s | %s | %s\n---\n",
                entry.getId(), entry.getKind().label(), pidText, entry.getState(),
                formatDuration(entry.getDuration()), label);
    }

    private static String sharedOutputHeader(ProcessCoordEntry entry, String label) {
        String pidText = entry.getPid() > 0 ? "PID " + entry.getPid() : "no pid";
        String owner = firstNonBlank(entry.getAgentName(), entry.getSessionId(), "shared");
        return String.format("[%s] shared:%s | %s | %s | %s | %s\n---\n",
                entry.getProcessId(), owner, pidText,
                firstNonBlank(entry.getState(), "RUNNING"),
                formatDuration(entry.getDuration()), label);
    }

    private static void appendRecentOutputSummary(StringBuilder sb, String processId, Path outputFile, int lines) {
        try {
            BackgroundProcessManager.TailResult tail = BackgroundProcessManager.tailOutputFile(outputFile, lines);
            if (tail.lines().isEmpty()) return;
            sb.append(String.format("%-12s %s\n", "", "  recent output:"));
            for (String line : tail.lines()) {
                sb.append(String.format("%-12s %s\n", "", "    " + StringUtils.truncateToLength(line, 90)));
            }
        } catch (IOException ignored) {
            sb.append(String.format("%-12s %s\n", "", "  recent output unavailable for " + processId));
        }
    }

    private static void appendRecentOutputBlock(StringBuilder sb, Path outputFile, int tailLines) {
        String output = BackgroundProcessManager.readOutputFile(outputFile, tailLines);
        sb.append("\nRecent Output:\n");
        for (String line : output.split("\\R", -1)) {
            sb.append("  ").append(line).append("\n");
        }
    }

    private static void appendWithNewline(StringBuilder sb, String line) {
        if (sb.length() > 0 && sb.charAt(sb.length() - 1) != '\n') {
            sb.append('\n');
        }
        sb.append(line).append('\n');
    }

    private static Path pathOrNull(String value) {
        if (value == null || value.isBlank()) return null;
        try {
            return Path.of(value);
        } catch (Exception ignored) {
            return null;
        }
    }

    private static String firstNonBlank(String... values) {
        if (values == null) return "";
        for (String value : values) {
            if (value != null && !value.isBlank()) return value;
        }
        return "";
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
