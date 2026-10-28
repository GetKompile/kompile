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
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

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
    private static final List<String> LIST_STATUSES = List.of("all", "running", "completed", "failed", "killed");
    private static final List<String> LIST_SORT_KEYS = List.of("id", "start_time", "state", "kind", "description", "duration", "owner");
    private static final List<String> LIST_KINDS = List.of("all", "command", "judge", "enforcer", "shared", "mcp");
    private static final List<String> LIST_SCOPES = List.of("all", "local", "shared");

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
                "Actions: list (search/filter/sort/page local and shared processes via query/status/scope/kind, " +
                "sort_by/sort_dir and offset/limit; compact 20-row pages, max 100; include_details adds log previews), launch (start a background command), " +
                "kill (stop a local process by ID), output (diagnostic snapshot), stream (register a completion monitor; never waits or emits live logs), " +
                "status (detailed info plus recent output), monitor (wake this agent when one local process exits), " +
                "unmonitor (cancel a monitor), monitors (list active monitors), resource_status (system-pressure " +
                "auto-kill telemetry, momentum and last kill; configure via /resources monitor), cleanup (remove old local entries).";
    }

    @Override
    public JsonNode parameterSchema() {
        ObjectMapper om = JsonUtils.standardMapper();
        ObjectNode schema = om.createObjectNode();
        schema.put("type", "object");
        ObjectNode props = schema.putObject("properties");

        ObjectNode action = props.putObject("action");
        action.put("type", "string");
        action.put("description", "Action to perform: list, launch, kill, output, stream, status, monitor, unmonitor, monitors, resource_status, cleanup");
        action.putArray("enum").add("list").add("launch").add("kill")
                .add("output").add("stream").add("status").add("monitor")
                .add("unmonitor").add("monitors").add("resource_status").add("cleanup");

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
        followSeconds.put("description", "Legacy compatibility option; ignored. Running processes use completion monitors instead of following output.");

        ListQuery.addSchema(props, LIST_STATUSES, LIST_SORT_KEYS, "start_time", "desc");
        ListQuery.enumSchema(props, "scope", LIST_SCOPES, "all", "List only local processes, shared processes (including mirrors), or both.");
        ListQuery.enumSchema(props, "kind", LIST_KINDS, "all", "List process kind. Shared coordination rows and mirrored rows use shared.");
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
                return executeList(params);
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
            case "resource_status":
                return ToolResult.success(JsonUtils.standardMapper().valueToTree(processManager.resourceMonitorStatus()).toPrettyString());
            case "cleanup":
                return executeCleanup();
            default:
                return ToolResult.error("Unknown action: " + action +
                        ". Valid actions: list, launch, kill, output, stream, status, monitor, unmonitor, monitors, resource_status, cleanup");
        }
    }

    private record ListedProcess(String id, String owner, String ownerSession, String sourceId,
                                 String kind, long pid, String state, Instant startTime, Duration duration,
                                 Integer exitCode, String command, String description, Path outputFile,
                                 Map<String, String> metadata, boolean shared) { }

    private ToolResult executeList(JsonNode params) {
        final ListQuery query;
        final String scope;
        final String kind;
        try {
            query = ListQuery.parse(params, LIST_STATUSES, LIST_SORT_KEYS, "start_time", "desc");
            scope = ListQuery.choice(params, "scope", LIST_SCOPES, "all");
            kind = ListQuery.choice(params, "kind", LIST_KINDS, "all");
        } catch (IllegalArgumentException e) {
            return ToolResult.error(e.getMessage());
        }
        List<BackgroundProcessManager.ProcessEntry> local = processManager.listAll();
        List<ListedProcess> all = new ArrayList<>();
        for (BackgroundProcessManager.ProcessEntry entry : local) {
            syncProcessState(entry);
            boolean shared = entry.getKind() == BackgroundProcessManager.ProcessKind.SHARED;
            Map<String, String> metadata = entry.getMetadata();
            String owner = shared ? firstNonBlank(metadata.get("ownerAgent"), metadata.get("ownerSessionId"), "shared") : "local";
            all.add(new ListedProcess(entry.getId(), owner,
                    shared ? firstNonBlank(metadata.get("ownerSessionId")) : firstNonBlank(processManager.getSessionId()),
                    firstNonBlank(metadata.get("sharedProcessId"), entry.getId()), entry.getKind().label(),
                    entry.getPid(), entry.getState().name(), entry.getStartTime(), entry.getDuration(),
                    entry.getExitCode(), entry.getCommand(), entry.getDescription(), entry.getOutputFile(), metadata, shared));
        }
        for (ProcessCoordEntry entry : sharedProcessEntries(local)) {
            String ownerSession = firstNonBlank(entry.getSessionId());
            String id = ownerSession.isBlank() ? entry.getProcessId() : ownerSession + "/" + entry.getProcessId();
            all.add(new ListedProcess(id, firstNonBlank(entry.getAgentName(), ownerSession, "shared"), ownerSession,
                    entry.getProcessId(), "shared", entry.getPid(), firstNonBlank(entry.getState(), "RUNNING").toUpperCase(Locale.ROOT),
                    entry.getStartedAt(), entry.getDuration(), entry.getExitCode(), entry.getCommand(),
                    entry.getDescription(), pathOrNull(entry.getOutputFile()), Map.of(), true));
        }
        List<ListedProcess> matches = new ArrayList<>(all.stream()
                .filter(row -> scope.equals("all") || scope.equals(row.shared() ? "shared" : "local"))
                .filter(row -> kind.equals("all") || kind.equals(row.kind()))
                .filter(row -> query.status().equals("all") || (query.status().equals("running")
                        ? isRunningState(row.state()) : query.status().equalsIgnoreCase(row.state())))
                .filter(row -> query.matches(row.id(), row.sourceId(), row.owner(), row.ownerSession(), row.command(), row.description()))
                .toList());
        Comparator<ListedProcess> comparator = switch (query.sortBy()) {
            case "id" -> Comparator.comparing(ListedProcess::id);
            case "state" -> Comparator.comparing(ListedProcess::state);
            case "kind" -> Comparator.comparing(ListedProcess::kind);
            case "description" -> Comparator.comparing(row -> firstNonBlank(row.description(), row.command()), String.CASE_INSENSITIVE_ORDER);
            case "duration" -> Comparator.comparing(ListedProcess::duration);
            case "owner" -> Comparator.comparing(ListedProcess::owner, String.CASE_INSENSITIVE_ORDER);
            default -> Comparator.comparing(ListedProcess::startTime, Comparator.nullsFirst(Comparator.naturalOrder()));
        };
        if (query.sortDir().equals("desc")) comparator = comparator.reversed();
        matches.sort(comparator.thenComparing(ListedProcess::ownerSession).thenComparing(ListedProcess::id));
        List<ListedProcess> page = query.page(matches);
        StringBuilder sb = new StringBuilder();
        if (all.isEmpty()) sb.append("No tracked processes\n");
        else if (matches.isEmpty()) sb.append("No processes match the filters.\n");
        if (!page.isEmpty()) sb.append(String.format("%-12s %-10s %-9s %-8s %-10s %-12s %-6s %s\n",
                "ID", "OWNER", "KIND", "PID", "STATE", "DURATION", "EXIT", "DESCRIPTION / COMMAND"));
        for (ListedProcess row : page) {
            sb.append(String.format("%-12s %-10s %-9s %-8s %-10s %-12s %-6s %s\n", row.id(),
                    StringUtils.truncateToLength(row.owner(), 10), row.kind(), row.pid() > 0 ? row.pid() : "-",
                    row.state(), formatDuration(row.duration()), row.exitCode() != null ? row.exitCode() : "-",
                    StringUtils.truncateToLength(firstNonBlank(row.description(), row.command()), 100)));
            if (query.includeDetails()) {
                sb.append("  command: ").append(row.command()).append("\n");
                sb.append("  description: ").append(firstNonBlank(row.description())).append("\n");
                appendRecentOutputSummary(sb, row.id(), row.outputFile(), 2);
                if (!row.metadata().isEmpty()) sb.append("  ").append(formatMetadata(row.metadata())).append("\n");
            }
        }
        sb.append(query.summary(all.size(), matches.size(), page.size()));
        Map<String, Object> metadata = query.metadata(all.size(), matches.size(), page.size());
        metadata.put("running", all.stream().filter(row -> isRunningState(row.state())).count());
        metadata.put("shared", (int) all.stream().filter(ListedProcess::shared).count());
        return ToolResult.success("processes", sb.toString(), metadata);
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

        // Edit-coordination enforcement: a backgrounded shell file-write must not bypass
        // the coordinator locks the native edit tools honour.
        String lockRefusal = ShellEditLockGate.checkCommand(
                coordinator != null ? coordinator : context.getCoordinator(),
                EditCoordinatorTool.lockOwner(null, context),
                command, context.getWorkingDirectory());
        if (lockRefusal != null) {
            return ToolResult.error(lockRefusal);
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
            publishProcess(entry, context, resource.resourceClass(), monitorMessage);

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
                            "backgrounded", true,
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
        if (entry.getKind() == BackgroundProcessManager.ProcessKind.SHARED) {
            // A mirror follows the owner's true state; only the owning session can stop it.
            return ToolResult.error("Process " + processId + " belongs to "
                    + firstNonBlank(entry.getMetadata().get("ownerAgent"),
                    entry.getMetadata().get("ownerSessionId"), "another session")
                    + "; only its owning session can stop it");
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

        if (context != null && context.isAborted()) {
            return ToolResult.error("Process stream cancelled");
        }
        BackgroundProcessManager.ProcessEntry local = processManager.get(processId);
        if (local != null) {
            syncProcessState(local);
            if (!local.isRunning()) return executeOutput(params);
            // Streaming was a blocking polling loop and leaked logs into Main.
            // Waiting is always owned by a one-shot monitor, not an agent turn.
            return executeMonitor(params);
        }
        ProcessCoordEntry shared = findSharedProcess(processId);
        if (shared == null) return ToolResult.error("Process not found: " + processId);
        if (!isSharedProcessRunning(shared)) return executeOutput(params);
        return ToolResult.error("Process " + processId + " belongs to another session. "
                + "Inspect its live activity view; its owning session must monitor completion. "
                + "Do not poll or stream its transcript.");
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
            // A mirrored entry is another session's process, not one this session owns.
            String scope = entry.getKind() == BackgroundProcessManager.ProcessKind.SHARED ? "shared" : "local";

            StringBuilder sb = new StringBuilder();
            sb.append("Process: ").append(entry.getId()).append("\n");
            sb.append("  Scope:       ").append(scope).append("\n");
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
                            "scope", scope));
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
        BackgroundProcessManager.ProcessMonitor existing = processManager.getMonitor(processId);
        String message = params.path("monitor_message").asText(existing == null ? "" : existing.message());
        BackgroundProcessManager.ProcessMonitor monitor =
                existing != null && existing.message().equals(message) ? existing
                        : processManager.monitor(processId, message);
        if (monitor == null) {
            return ToolResult.error("Process " + processId
                    + " cannot be monitored or exited during registration");
        }
        if (coordinator != null) coordinator.updateProcessMonitor(processId, true, monitor.message());
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
        if (coordinator != null) coordinator.updateProcessMonitor(processId, false, null);
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

    private void publishProcess(BackgroundProcessManager.ProcessEntry entry, ToolContext context,
                                String resourceClass, String monitorMessage) {
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
        // Every launch is monitored. Publishing that lets the session that started
        // this one (a chat whose agent reaches this tool through the MCP server)
        // wake when the process ends, as a local monitor wakes this session.
        coordinator.updateProcessMonitor(entry.getId(), true, monitorMessage);
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
        // SharedProcessMirror rows already list these, keyed ownerSession/processId.
        Set<String> mirrored = new HashSet<>();
        if (localEntries != null) {
            for (BackgroundProcessManager.ProcessEntry entry : localEntries) {
                localIds.add(entry.getId());
                if (entry.getKind() == BackgroundProcessManager.ProcessKind.SHARED) {
                    mirrored.add(firstNonBlank(entry.getMetadata().get("ownerSessionId")) + "/"
                            + firstNonBlank(entry.getMetadata().get("sharedProcessId")));
                }
            }
        }
        String localSession = coordinator.getSessionId();
        List<ProcessCoordEntry> shared = new ArrayList<>();
        try {
            for (ProcessCoordEntry entry : coordinator.queryProcesses()) {
                if (entry == null || entry.getProcessId() == null) continue;
                boolean localMirror = firstNonBlank(entry.getSessionId()).equals(localSession)
                        && localIds.contains(entry.getProcessId());
                if (!localMirror && !mirrored.contains(firstNonBlank(entry.getSessionId()) + "/" + entry.getProcessId())) {
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
