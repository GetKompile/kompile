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
import ai.kompile.cli.main.coordination.*;
import ai.kompile.utils.StringUtils;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * MCP tool for coordinating file edits, process awareness, and agent activity
 * across multiple concurrent agents. Delegates to {@link CoordinationStateManager}.
 *
 * <p>Actions: register_edit, release_edit, query_edits, query_processes,
 * register_agent, query_agents, publish_process, unpublish_process, status.
 */
public class EditCoordinatorTool implements CliTool {

    private final CoordinationStateManager coordinator;

    public EditCoordinatorTool(CoordinationStateManager coordinator) {
        this.coordinator = coordinator;
    }

    @Override
    public String id() { return "edit_coordinator"; }

    @Override
    public String description() {
        return "Coordinate file edits and agent activity when using multi_task or task with concurrent agents.\n\n"
                + "REQUIRED WORKFLOW when multiple agents may edit files:\n"
                + "1. register_agent — announce what you're working on\n"
                + "2. query_edits — check if target files are locked by another agent\n"
                + "3. register_edit — lock the file before editing (returns lock_id)\n"
                + "4. (do your edits)\n"
                + "5. release_edit — release the lock using the lock_id\n\n"
                + "Other actions: query_processes (see running background processes), "
                + "query_agents (see all active agents), publish_process/unpublish_process (track background work), "
                + "status (combined dashboard).";
    }

    @Override
    public JsonNode parameterSchema() {
        ObjectMapper om = JsonUtils.standardMapper();
        ObjectNode schema = om.createObjectNode();
        schema.put("type", "object");
        ObjectNode props = schema.putObject("properties");

        ObjectNode action = props.putObject("action");
        action.put("type", "string");
        action.put("description",
                "Action to perform: register_edit, release_edit, query_edits, query_processes, "
                        + "register_agent, query_agents, publish_process, unpublish_process, status");
        action.putArray("enum")
                .add("register_edit").add("release_edit")
                .add("query_edits").add("query_processes")
                .add("register_agent").add("query_agents")
                .add("publish_process").add("unpublish_process")
                .add("status");

        ObjectNode filePath = props.putObject("file_path");
        filePath.put("type", "string");
        filePath.put("description", "File path for register_edit or query_edits filter");

        ObjectNode editType = props.putObject("edit_type");
        editType.put("type", "string");
        editType.put("description", "Type of edit: 'edit' or 'write' (default: edit)");

        ObjectNode lockId = props.putObject("lock_id");
        lockId.put("type", "string");
        lockId.put("description", "Lock ID returned by register_edit, used for release_edit");

        ObjectNode task = props.putObject("task");
        task.put("type", "string");
        task.put("description", "What this agent is working on (for register_agent)");

        ObjectNode agentName = props.putObject("agent_name");
        agentName.put("type", "string");
        agentName.put("description", "Agent name (optional, for register_agent)");

        ObjectNode includeStale = props.putObject("include_stale");
        includeStale.put("type", "boolean");
        includeStale.put("description", "Include stale/expired entries in query results (default: false)");

        ObjectNode processId = props.putObject("process_id");
        processId.put("type", "string");
        processId.put("description", "Process ID for publish_process/unpublish_process");

        ObjectNode command = props.putObject("command");
        command.put("type", "string");
        command.put("description", "Command string for publish_process");

        ObjectNode procDescription = props.putObject("description");
        procDescription.put("type", "string");
        procDescription.put("description", "Description for publish_process");

        ObjectNode pid = props.putObject("pid");
        pid.put("type", "integer");
        pid.put("description", "OS process ID for publish_process");

        ObjectNode state = props.putObject("state");
        state.put("type", "string");
        state.put("description", "Process state for publish_process: RUNNING, COMPLETED, FAILED, KILLED");

        ObjectNode outputFile = props.putObject("output_file");
        outputFile.put("type", "string");
        outputFile.put("description", "Output file path for publish_process");

        schema.putArray("required").add("action");
        return schema;
    }

    @Override
    public String permissionKey() { return "edit_coordinator"; }

    @Override
    public ToolResult execute(JsonNode params, ToolContext context) throws ToolExecutionException {
        String action = params.path("action").asText("");
        if (action.isEmpty()) {
            return ToolResult.error("action is required");
        }

        switch (action) {
            case "register_edit":
                return executeRegisterEdit(params, context);
            case "release_edit":
                return executeReleaseEdit(params);
            case "query_edits":
                return executeQueryEdits(params);
            case "query_processes":
                return executeQueryProcesses();
            case "register_agent":
                return executeRegisterAgent(params);
            case "query_agents":
                return executeQueryAgents(params);
            case "publish_process":
                return executePublishProcess(params);
            case "unpublish_process":
                return executeUnpublishProcess(params);
            case "status":
                return executeStatus();
            default:
                return ToolResult.error("Unknown action: " + action
                        + ". Valid: register_edit, release_edit, query_edits, query_processes, "
                        + "register_agent, query_agents, publish_process, unpublish_process, status");
        }
    }

    private ToolResult executeRegisterEdit(JsonNode params, ToolContext context) throws ToolExecutionException {
        String filePath = params.path("file_path").asText("");
        if (filePath.isEmpty()) {
            return ToolResult.error("file_path is required for register_edit");
        }

        // Resolve to absolute path
        String absolutePath = context.resolvePath(filePath).toAbsolutePath().toString();
        String editType = params.path("edit_type").asText("edit");
        String agentName = params.path("agent_name").asText(null);

        EditLockResult result = coordinator.tryAcquireEditLock(absolutePath, editType, agentName);

        if (result.hasConflict()) {
            EditLockEntry conflict = result.getConflictEntry();
            StringBuilder sb = new StringBuilder();
            sb.append("CONFLICT: ").append(result.getConflictMessage()).append("\n");
            sb.append("  Held by: ").append(conflict.getAgentName())
                    .append(" (session: ").append(conflict.getSessionId()).append(")\n");
            sb.append("  Edit type: ").append(conflict.getEditType()).append("\n");
            sb.append("  Since: ").append(formatAge(conflict.getAcquiredAt()));

            return ToolResult.success("conflict", sb.toString(),
                    Map.of("status", "conflict",
                            "conflictSession", conflict.getSessionId(),
                            "conflictAgent", conflict.getAgentName()));
        }

        return ToolResult.success("acquired", "Edit lock acquired on " + absolutePath,
                Map.of("status", "acquired", "lockId", result.getLockId()));
    }

    private ToolResult executeReleaseEdit(JsonNode params) {
        String lockId = params.path("lock_id").asText("");
        if (lockId.isEmpty()) {
            return ToolResult.error("lock_id is required for release_edit");
        }

        boolean released = coordinator.releaseEditLock(lockId);
        if (released) {
            return ToolResult.success("released", "Edit lock " + lockId + " released");
        } else {
            return ToolResult.success("not_found", "Lock " + lockId + " not found (may have already expired)");
        }
    }

    private ToolResult executeQueryEdits(JsonNode params) {
        String filterFile = params.path("file_path").asText(null);

        List<EditLockEntry> edits;
        if (filterFile != null && !filterFile.isEmpty()) {
            edits = coordinator.queryEditsForFile(filterFile);
        } else {
            edits = coordinator.queryEdits();
        }

        if (edits.isEmpty()) {
            return ToolResult.success("No active file edits");
        }

        StringBuilder sb = new StringBuilder();
        sb.append(String.format("%-50s %-15s %-10s %-6s %s\n",
                "FILE", "AGENT", "SESSION", "TYPE", "AGE"));
        sb.append("-".repeat(100)).append("\n");

        for (EditLockEntry e : edits) {
            sb.append(String.format("%-50s %-15s %-10s %-6s %s\n",
                    StringUtils.truncate(e.getFilePath(), 50),
                    e.getAgentName(),
                    StringUtils.truncate(e.getSessionId(), 10),
                    e.getEditType(),
                    formatAge(e.getAcquiredAt())));
        }

        return ToolResult.success("edits", sb.toString(),
                Map.of("count", edits.size()));
    }

    private ToolResult executeQueryProcesses() {
        List<ProcessCoordEntry> processes = coordinator.queryProcesses();

        if (processes.isEmpty()) {
            return ToolResult.success("No active processes across agents");
        }

        StringBuilder sb = new StringBuilder();
        sb.append(String.format("%-10s %-15s %-10s %-30s %-10s %s\n",
                "PROC_ID", "AGENT", "SESSION", "COMMAND", "STATE", "DURATION"));
        sb.append("-".repeat(110)).append("\n");

        for (ProcessCoordEntry p : processes) {
            sb.append(String.format("%-10s %-15s %-10s %-30s %-10s %s\n",
                    p.getProcessId(),
                    p.getAgentName(),
                    StringUtils.truncate(p.getSessionId(), 10),
                    StringUtils.truncate(p.getCommand(), 30),
                    p.getState(),
                    formatAge(p.getStartedAt())));
        }

        return ToolResult.success("processes", sb.toString(),
                Map.of("count", processes.size()));
    }

    private ToolResult executeRegisterAgent(JsonNode params) {
        String task = params.path("task").asText("");
        if (task.isEmpty()) {
            return ToolResult.error("task is required for register_agent");
        }

        String agentName = params.path("agent_name").asText("unknown");
        int depth = 0;
        String depthEnv = System.getenv("KOMPILE_SUBAGENT_DEPTH");
        if (depthEnv != null) {
            try { depth = Integer.parseInt(depthEnv); } catch (NumberFormatException ignored) {}
        }

        coordinator.registerAgent(task, null, agentName, depth,
                ProcessHandle.current().pid());

        return ToolResult.success("registered",
                "Agent registered: " + agentName + " — " + task,
                Map.of("sessionId", coordinator.getSessionId()));
    }

    private ToolResult executeQueryAgents(JsonNode params) {
        boolean includeStale = params.path("include_stale").asBoolean(false);
        List<AgentEntry> agents = coordinator.queryAgents(includeStale);

        if (agents.isEmpty()) {
            return ToolResult.success("No active agents");
        }

        StringBuilder sb = new StringBuilder();
        sb.append(String.format("%-25s %-10s %-7s %-5s %-40s %s\n",
                "SESSION", "AGENT", "TYPE", "DEPTH", "TASK", "RUNNING"));
        sb.append("-".repeat(110)).append("\n");

        for (AgentEntry a : agents) {
            sb.append(String.format("%-25s %-10s %-7s %-5d %-40s %s\n",
                    StringUtils.truncate(a.getSessionId(), 25),
                    a.getAgentName(),
                    a.getAgentType(),
                    a.getDepth(),
                    StringUtils.truncate(a.getTask(), 40),
                    formatAge(a.getStartedAt())));
        }

        return ToolResult.success("agents", sb.toString(),
                Map.of("count", agents.size()));
    }

    private ToolResult executePublishProcess(JsonNode params) {
        String processId = params.path("process_id").asText("");
        String command = params.path("command").asText("");
        long pid = params.path("pid").asLong(0);

        if (processId.isEmpty() || command.isEmpty() || pid == 0) {
            return ToolResult.error("process_id, command, and pid are required for publish_process");
        }

        String description = params.path("description").asText(command);
        String state = params.path("state").asText("RUNNING");
        String outputFile = params.path("output_file").asText(null);
        String agentName = params.path("agent_name").asText("unknown");

        coordinator.publishProcess(processId, command, description, pid, state, outputFile, agentName);

        return ToolResult.success("published",
                "Process " + processId + " published to coordination state",
                Map.of("processId", processId));
    }

    private ToolResult executeUnpublishProcess(JsonNode params) {
        String processId = params.path("process_id").asText("");
        if (processId.isEmpty()) {
            return ToolResult.error("process_id is required for unpublish_process");
        }

        boolean removed = coordinator.unpublishProcess(processId);
        if (removed) {
            return ToolResult.success("removed", "Process " + processId + " removed from coordination state");
        } else {
            return ToolResult.success("not_found", "Process " + processId + " not found in coordination state");
        }
    }

    private ToolResult executeStatus() {
        String dashboard = coordinator.statusDashboard();
        return ToolResult.success("status", dashboard);
    }

    private static String formatAge(Instant since) {
        Duration d = Duration.between(since, Instant.now());
        long totalSeconds = d.getSeconds();
        if (totalSeconds < 60) return totalSeconds + "s";
        long minutes = totalSeconds / 60;
        long seconds = totalSeconds % 60;
        if (minutes < 60) return minutes + "m" + seconds + "s";
        long hours = minutes / 60;
        minutes = minutes % 60;
        return hours + "h" + minutes + "m";
    }

}
