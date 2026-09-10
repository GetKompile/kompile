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
import ai.kompile.cli.main.project.LocalSubprocessWatchdog;
import ai.kompile.utils.FormatUtils;
import ai.kompile.utils.StringUtils;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Agent message-passing and coordination framework. The historical tool id remains
 * {@code edit_coordinator} for compatibility, while edit locks are now one capability
 * alongside peer messages, process presence, and system-wide high-memory activities.
 *
     * <p>Actions: register_edit, release_edit, query_edits, query_processes,
     * register_agent, query_agents, send_message, broadcast_message, read_messages,
     * ack_message, preflight_activity, query_activities, release_activity,
     * publish_process, unpublish_process, awareness, status.
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
        return "Agent message bus and coordination framework (legacy id: edit_coordinator). "
                + "Agents exchange durable direct/broadcast messages, publish work, coordinate files, "
                + "and inspect the user-wide high-memory activity lane.\n\n"
                + "REQUIRED WORKFLOW when multiple agents may edit files:\n"
                + "1. register_agent — announce what you're working on\n"
                + "2. query_edits — check if target files are locked by another agent\n"
                + "3. register_edit — lock the file before editing (returns lock_id)\n"
                + "4. (do your edits)\n"
                + "5. release_edit — release the lock using the lock_id\n\n"
                + "Editing SEVERAL files (e.g. before edit_batch/edit_patch)? Use register_edits with "
                + "file_paths to lock them all in ONE call (all-or-nothing unless allow_partial=true), "
                + "then release_edits with lock_ids when done.\n\n"
                + "High-memory builds, tests, crawls, model work, and indexing are preflighted "
                + "automatically by the harness; blocked launches install a one-shot resource watch. "
                + "preflight_activity is dry; watch_activity explicitly watches peer work and RAM/GPU capacity. "
                + "Native chat wakes automatically; external MCP agents must hold wait_for_activity open "
                + "and repeat it on timeout instead of ending their turn. query_activity_waits lists this "
                + "session's watches; cancel_activity_wait cancels one. Watches end on host shutdown "
                + "or after 24 hours, and never launch work or reserve capacity.\n\n"
                + "Other actions: awareness (one-call cross-agent/resource snapshot with risks and next steps), "
                + "query_processes (see running background processes), query_agents (see all active agents), "
                + "send_message/broadcast_message/read_messages/ack_message (durable project-local bus; cooperative "
                + "delivery does not wake an externally owned model turn), "
                + "query_activities/release_activity (system-wide high-memory reservations), "
                + "publish_process/unpublish_process (track background work), status (combined dashboard).";
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
                "Action to perform: register_edit, release_edit, register_edits, release_edits, "
                        + "query_edits, query_processes, "
                        + "register_agent, query_agents, send_message, broadcast_message, read_messages, ack_message, "
                        + "preflight_activity, watch_activity, wait_for_activity, query_activity_waits, cancel_activity_wait, "
                        + "query_activities, release_activity, "
                        + "publish_process, unpublish_process, awareness, status");
        action.putArray("enum")
                .add("register_edit").add("release_edit")
                .add("register_edits").add("release_edits")
                .add("query_edits").add("query_processes")
                .add("register_agent").add("query_agents")
                .add("send_message").add("broadcast_message").add("read_messages").add("ack_message")
                .add("preflight_activity").add("query_activities").add("release_activity")
                .add("watch_activity").add("wait_for_activity")
                .add("query_activity_waits").add("cancel_activity_wait")
                .add("publish_process").add("unpublish_process")
                .add("awareness").add("status");

        ObjectNode filePath = props.putObject("file_path");
        filePath.put("type", "string");
        filePath.put("description", "File path for register_edit or query_edits filter");

        ObjectNode filePaths = props.putObject("file_paths");
        filePaths.put("type", "array");
        filePaths.putObject("items").put("type", "string");
        filePaths.put("description", "File paths for register_edits — locks them all in one call");

        ObjectNode lockIds = props.putObject("lock_ids");
        lockIds.put("type", "array");
        lockIds.putObject("items").put("type", "string");
        lockIds.put("description", "Lock IDs for release_edits — releases them all in one call");

        ObjectNode allowPartial = props.putObject("allow_partial");
        allowPartial.put("type", "boolean");
        allowPartial.put("description",
                "register_edits: lock the conflict-free files even when others conflict "
                        + "(default false = all-or-nothing)");

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

        ObjectNode toolSessionId = props.putObject("tool_session_id");
        toolSessionId.put("type", "string");
        toolSessionId.put("description",
                "Tool-call/transcript session correlated with this coordination agent (defaults to current tool session)");

        ObjectNode roleName = props.putObject("role_name");
        roleName.put("type", "string");
        roleName.put("description", "Optional Kompile role or agent profile for register_agent");

        ObjectNode parentSessionId = props.putObject("parent_session_id");
        parentSessionId.put("type", "string");
        parentSessionId.put("description",
                "Optional parent coordination session for register_agent; inherited automatically by spawned agents");

        ObjectNode includeStale = props.putObject("include_stale");
        includeStale.put("type", "boolean");
        includeStale.put("description", "Include stale/expired entries in query results (default: false)");

        ObjectNode targetSessionId = props.putObject("target_session_id");
        targetSessionId.put("type", "string");
        targetSessionId.put("description", "Recipient coordination session ID for send_message");

        ObjectNode message = props.putObject("message");
        message.put("type", "string");
        message.put("description", "Message body for send_message (maximum 64 KiB UTF-8)");

        ObjectNode messageKind = props.putObject("message_kind");
        messageKind.put("type", "string");
        messageKind.put("description", "Message kind such as message, request, response, notice, or cancel");

        ObjectNode replyTo = props.putObject("reply_to");
        replyTo.put("type", "string");
        replyTo.put("description", "Optional message ID this delivery replies to");

        ObjectNode messageId = props.putObject("message_id");
        messageId.put("type", "string");
        messageId.put("description", "Message ID to remove from this session's mailbox with ack_message");

        ObjectNode maxResults = props.putObject("max_results");
        maxResults.put("type", "integer");
        maxResults.put("description", "Maximum pending messages returned by read_messages (default 20, maximum 100)");

        ObjectNode activityId = props.putObject("activity_id");
        activityId.put("type", "string");
        activityId.put("description", "System activity reservation ID for release_activity");

        props.putObject("tool_name").put("type", "string")
                .put("description", "preflight_activity/watch_activity: target tool name; use with tool_arguments to apply /resources rules");
        props.putObject("tool_arguments").put("type", "object")
                .put("description", "Exact arguments of the proposed tool call, including command for bash or action=launch for process");
        ObjectNode activityKind = props.putObject("activity_kind");
        activityKind.put("type", "string");
        activityKind.put("description", "preflight_activity/watch_activity kind: build, test, crawl, model, benchmark, or index");

        props.putObject("wait_id").put("type", "string")
                .put("description", "Resource watch id for wait_for_activity or cancel_activity_wait");
        props.putObject("timeout_seconds").put("type", "integer").put("default", 25)
                .put("minimum", 1).put("maximum", 300)
                .put("description", "Held wait timeout; repeat wait_for_activity on WAITING without relaunching work");

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
            case "register_edits":
                return executeRegisterEdits(params, context);
            case "release_edits":
                return executeReleaseEdits(params);
            case "query_edits":
                return executeQueryEdits(params);
            case "query_processes":
                return executeQueryProcesses();
            case "register_agent":
                return executeRegisterAgent(params, context);
            case "query_agents":
                return executeQueryAgents(params);
            case "send_message":
                return executeSendMessage(params);
            case "broadcast_message":
                return executeBroadcastMessage(params);
            case "read_messages":
                return executeReadMessages(params);
            case "ack_message":
                return executeAckMessage(params);
            case "preflight_activity":
                return executePreflightActivity(params, context);
            case "watch_activity":
            case "wait_for_activity":
            case "query_activity_waits":
            case "cancel_activity_wait":
                return executeActivityWait(action, params, context);
            case "query_activities":
                return executeQueryActivities();
            case "release_activity":
                return executeReleaseActivity(params);
            case "publish_process":
                return executePublishProcess(params);
            case "unpublish_process":
                return executeUnpublishProcess(params);
            case "awareness":
                return executeAwareness();
            case "status":
                return executeStatus();
            default:
                return ToolResult.error("Unknown action: " + action
                        + ". Valid: register_edit, release_edit, register_edits, release_edits, "
                        + "query_edits, query_processes, "
                        + "register_agent, query_agents, send_message, broadcast_message, read_messages, ack_message, "
                        + "preflight_activity, watch_activity, wait_for_activity, query_activity_waits, cancel_activity_wait, "
                        + "query_activities, release_activity, "
                        + "publish_process, unpublish_process, awareness, status");
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

    private ToolResult executeRegisterEdits(JsonNode params, ToolContext context) throws ToolExecutionException {
        JsonNode filePaths = params.path("file_paths");
        if (!filePaths.isArray() || filePaths.isEmpty()) {
            return ToolResult.error("file_paths (non-empty array) is required for register_edits");
        }
        List<String> absolutePaths = new ArrayList<>();
        for (JsonNode p : filePaths) {
            String raw = p.asText("");
            if (raw.isEmpty()) {
                return ToolResult.error("file_paths entries must be non-empty strings");
            }
            absolutePaths.add(context.resolvePath(raw).toAbsolutePath().toString());
        }
        String editType = params.path("edit_type").asText("edit");
        String agentName = params.path("agent_name").asText(null);
        boolean allowPartial = params.path("allow_partial").asBoolean(false);

        CoordinationStateManager.BatchAcquireResult batch =
                coordinator.tryAcquireEditLocks(absolutePaths, editType, agentName, allowPartial);

        StringBuilder sb = new StringBuilder();
        Map<String, Object> lockIds = new LinkedHashMap<>();
        for (Map.Entry<String, EditLockResult> e : batch.results().entrySet()) {
            EditLockResult r = e.getValue();
            if (r.isAcquired()) {
                sb.append("acquired  ").append(e.getKey()).append(" — lock_id ").append(r.getLockId()).append('\n');
                lockIds.put(e.getKey(), r.getLockId());
            } else if (r.hasConflict()) {
                EditLockEntry c = r.getConflictEntry();
                sb.append("CONFLICT  ").append(e.getKey())
                        .append(" — held by ").append(c != null ? c.getAgentName() : "unknown")
                        .append(" (session ").append(c != null ? c.getSessionId() : "?").append(")")
                        .append(c != null ? ", since " + formatAge(c.getAcquiredAt()) : "")
                        .append('\n');
            } else {
                sb.append("skipped   ").append(e.getKey()).append(" — ").append(r.getConflictMessage()).append('\n');
            }
        }
        if (batch.aborted()) {
            sb.append("\nBatch aborted (all-or-nothing): no locks were taken. Resolve the conflicts, "
                    + "retry, or pass allow_partial=true to lock the free files.");
        }
        String status = batch.aborted() ? "conflict"
                : batch.conflictCount() > 0 ? "partial" : "acquired";
        return ToolResult.success(status, sb.toString().stripTrailing(),
                Map.of("status", status,
                        "acquired", batch.acquiredCount(),
                        "conflicts", batch.conflictCount(),
                        "lockIds", lockIds));
    }

    private ToolResult executeReleaseEdits(JsonNode params) {
        JsonNode lockIds = params.path("lock_ids");
        if (!lockIds.isArray() || lockIds.isEmpty()) {
            return ToolResult.error("lock_ids (non-empty array) is required for release_edits");
        }
        List<String> ids = new ArrayList<>();
        for (JsonNode id : lockIds) {
            if (!id.asText("").isEmpty()) ids.add(id.asText());
        }
        Map<String, Boolean> released = coordinator.releaseEditLocks(ids);
        long releasedCount = released.values().stream().filter(Boolean::booleanValue).count();
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, Boolean> e : released.entrySet()) {
            sb.append(e.getValue() ? "released  " : "not_found ").append(e.getKey()).append('\n');
        }
        return ToolResult.success("released",
                sb.toString().stripTrailing()
                        + "\n" + releasedCount + "/" + released.size() + " locks released",
                Map.of("released", releasedCount, "requested", released.size()));
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
                    FormatUtils.formatDuration(p.getDuration()) + " resource=" + ResourcePolicy.processClass(coordinator.getProjectRoot(), p)));
        }

        return ToolResult.success("processes", sb.toString(),
                Map.of("count", processes.size()));
    }

    private ToolResult executeRegisterAgent(JsonNode params, ToolContext context) {
        String task = params.path("task").asText("");
        if (task.isEmpty()) {
            return ToolResult.error("task is required for register_agent");
        }

        String agentName = params.path("agent_name").asText("unknown");
        String parentSessionId = params.path("parent_session_id").asText("");
        if (parentSessionId.isBlank()) {
            parentSessionId = System.getenv("KOMPILE_PARENT_SESSION_ID");
        }
        if (parentSessionId != null && parentSessionId.isBlank()) parentSessionId = null;
        String toolSessionId = params.path("tool_session_id").asText("");
        if (toolSessionId.isBlank() && context != null) {
            toolSessionId = context.getSessionId();
        }
        String roleName = params.path("role_name").asText("");
        if (roleName.isBlank() && context != null && context.getAgent() != null) {
            roleName = context.getAgent().getRoleName();
            if (roleName == null || roleName.isBlank()) {
                roleName = context.getAgent().getName();
            }
        }
        int depth = 0;
        String depthEnv = System.getenv("KOMPILE_SUBAGENT_DEPTH");
        if (depthEnv != null) {
            try { depth = Integer.parseInt(depthEnv); } catch (NumberFormatException ignored) {}
        }

        coordinator.registerAgent(task, parentSessionId, agentName, depth,
                ProcessHandle.current().pid(), toolSessionId, roleName);

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

    private ToolResult executeSendMessage(JsonNode params) {
        String targetSessionId = params.path("target_session_id").asText("");
        String message = params.path("message").asText("");
        if (targetSessionId.isBlank() || message.isBlank()) {
            return ToolResult.error("target_session_id and message are required for send_message");
        }
        String kind = params.path("message_kind").asText("message");
        String replyTo = params.path("reply_to").asText(null);
        try {
            CoordinationMessage delivered = coordinator.sendMessage(
                    targetSessionId, kind, message, replyTo);
            return ToolResult.success("message queued",
                    "Message " + delivered.getMessageId() + " queued for " + targetSessionId
                            + ". Delivery is durable; the recipient reads it with read_messages or awareness.",
                    Map.of("messageId", delivered.getMessageId(),
                            "targetSessionId", delivered.getTargetSessionId(),
                            "kind", delivered.getKind()));
        } catch (Exception e) {
            return ToolResult.error("Could not queue coordination message: " + e.getMessage());
        }
    }

    private ToolResult executeBroadcastMessage(JsonNode params) {
        String message = params.path("message").asText("");
        if (message.isBlank()) {
            return ToolResult.error("message is required for broadcast_message");
        }
        String kind = params.path("message_kind").asText("notice");
        String replyTo = params.path("reply_to").asText(null);
        List<CoordinationMessage> delivered = coordinator.broadcastMessage(kind, message, replyTo);
        return ToolResult.success("message broadcast",
                "Broadcast queued for " + delivered.size() + " active peer(s). "
                        + "Delivery is durable but does not interrupt externally owned turns.",
                Map.of("delivered", delivered.size(), "kind", kind,
                        "messageIds", delivered.stream()
                                .map(CoordinationMessage::getMessageId).toList()));
    }

    private ToolResult executeReadMessages(JsonNode params) {
        int maxResults = params.path("max_results").asInt(20);
        List<CoordinationMessage> messages = coordinator.readMessages(maxResults);
        if (messages.isEmpty()) {
            return ToolResult.success("No pending coordination messages");
        }
        StringBuilder sb = new StringBuilder();
        sb.append("Pending coordination messages (acknowledge after processing):\n\n");
        for (CoordinationMessage message : messages) {
            sb.append("  ").append(message.getMessageId())
                    .append("  from=").append(message.getSenderSessionId())
                    .append("  kind=").append(message.getKind())
                    .append("  sent=").append(message.getSentAt()).append("\n");
            if (message.getReplyTo() != null) {
                sb.append("    reply_to: ").append(message.getReplyTo()).append("\n");
            }
            sb.append("    ").append(message.getMessage().replace("\n", "\n    ")).append("\n\n");
        }
        return ToolResult.success("coordination messages", sb.toString().stripTrailing(),
                Map.of("count", messages.size()));
    }

    private ToolResult executeAckMessage(JsonNode params) {
        String messageId = params.path("message_id").asText("");
        if (messageId.isBlank()) {
            return ToolResult.error("message_id is required for ack_message");
        }
        boolean acknowledged;
        try {
            acknowledged = coordinator.acknowledgeMessage(messageId);
        } catch (IllegalArgumentException e) {
            return ToolResult.error("Invalid message_id: " + e.getMessage());
        }
        return acknowledged
                ? ToolResult.success("message acknowledged", "Removed message " + messageId)
                : ToolResult.error("Message not found in this session's mailbox: " + messageId);
    }

    /** Used by the workflow gate only for a real, host-deliverable resource pause. */
    public boolean canPauseForActivity(String session, String waitId) {
        try {
            ActivityWaitRegistry.Snapshot wait = coordinator.activityWaits().get(session, waitId);
            return wait.wakeSupported() && (wait.state() == ActivityWaitRegistry.State.WAITING
                    || wait.state() == ActivityWaitRegistry.State.READY
                    || wait.state() == ActivityWaitRegistry.State.EXPIRED);
        } catch (IllegalArgumentException missing) {
            return false;
        }
    }

    private ToolResult executeActivityWait(String action, JsonNode params, ToolContext context) {
        String session = context == null || context.getSessionId() == null
                ? coordinator.getSessionId() : context.getSessionId();
        ActivityWaitRegistry waits = coordinator.activityWaits();
        try {
            if ("query_activity_waits".equals(action)) {
                List<ActivityWaitRegistry.Snapshot> snapshots = waits.list(session);
                return ToolResult.success("resource watches", snapshots.toString(),
                        Map.of("waits", snapshots, "count", snapshots.size()));
            }
            ActivityWaitRegistry.Snapshot wait;
            if ("watch_activity".equals(action)) {
                if (context != null && context.isAborted()) return ToolResult.error("Resource watch cancelled");
                if (params.has("tool_name")) {
                    if (!params.path("tool_arguments").isObject()) return ToolResult.error("tool_arguments must be an object");
                    wait = new HighMemoryToolCallGuard(coordinator).watchToolCall(
                            params.path("tool_name").asText(), params.get("tool_arguments"), context);
                } else {
                    wait = new HighMemoryToolCallGuard(coordinator).watch(
                            params.path("activity_kind").asText("build"),
                            params.path("description").asText("blocked high-memory work"), context);
                }
            } else {
                String id = params.path("wait_id").asText("");
                if (id.isBlank()) return ToolResult.error("wait_id is required for " + action);
                if ("cancel_activity_wait".equals(action)) {
                    wait = waits.cancel(session, id);
                } else {
                    int seconds = params.path("timeout_seconds").asInt(25);
                    if (seconds < 1 || seconds > 300) return ToolResult.error("timeout_seconds must be 1..300");
                    wait = waits.await(session, id, seconds * 1_000L,
                            () -> context != null && context.isAborted());
                }
            }
            String guidance = wait.state() == ActivityWaitRegistry.State.WAITING
                    ? (wait.wakeSupported() ? "\nYou may pause; this host will wake the agent."
                    : "\nCall wait_for_activity with this wait_id; repeat on WAITING. "
                      + "Do not end the external model turn expecting an unsolicited wake-up.") : "";
            return ToolResult.success("resource watch " + wait.state(), wait.notification() + guidance,
                    Map.of("resourceWaitId", wait.waitId(), "state", wait.state().name(),
                            "ready", wait.state() == ActivityWaitRegistry.State.READY,
                            "wakeSupported", wait.wakeSupported()));
        } catch (IllegalArgumentException | IllegalStateException failure) {
            return ToolResult.error(failure.getMessage());
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return ToolResult.error("Resource wait interrupted and cancelled; no work started");
        }
    }

    private ToolResult executePreflightActivity(JsonNode params, ToolContext context) {
        if (params.has("tool_name")) {
            if (!params.path("tool_arguments").isObject()) return ToolResult.error("tool_arguments must be an object");
            return new HighMemoryToolCallGuard(coordinator).inspectToolCall(
                    params.path("tool_name").asText(), params.get("tool_arguments"), context);
        }
        String kind = params.path("activity_kind").asText("build");
        String description = params.path("description").asText("manual high-memory preflight");
        return new HighMemoryToolCallGuard(coordinator).inspect(kind, description, context);
    }

    private ToolResult executeQueryActivities() {
        List<CoordinationActivity> activities = coordinator.queryHighMemoryActivities();
        if (activities.isEmpty()) {
            return ToolResult.success("No active high-memory activities");
        }
        StringBuilder output = new StringBuilder("System-wide high-memory activities:\n");
        for (CoordinationActivity activity : activities) {
            output.append("  - ").append(activity.getActivityId())
                    .append(" ").append(activity.getKind())
                    .append(" by ").append(firstNonBlank(activity.getAgentName(), activity.getSessionId()))
                    .append(" via ").append(activity.getToolName())
                    .append(" — ").append(activity.getDescription()).append('\n');
        }
        return ToolResult.success("high-memory activities", output.toString().stripTrailing(),
                Map.of("count", activities.size()));
    }

    private ToolResult executeReleaseActivity(JsonNode params) {
        String activityId = params.path("activity_id").asText("");
        if (activityId.isBlank()) {
            return ToolResult.error("activity_id is required for release_activity");
        }
        boolean released;
        try {
            released = coordinator.releaseHighMemoryActivity(activityId);
        } catch (IllegalArgumentException e) {
            return ToolResult.error("Invalid activity_id: " + e.getMessage());
        }
        return released
                ? ToolResult.success("activity released", "Released system activity " + activityId)
                : ToolResult.error("Activity is not owned by this session or no longer exists: " + activityId);
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

    private ToolResult executeAwareness() {
        List<AgentEntry> agents = coordinator.queryAgents();
        List<EditLockEntry> edits = coordinator.queryEdits();
        List<ProcessCoordEntry> processes = coordinator.queryProcesses();
        List<CoordinationMessage> messages = coordinator.readMessages(100);
        List<CoordinationActivity> activities = coordinator.queryHighMemoryActivities();
        Map<String, Object> capacity;
        try {
            capacity = LocalSubprocessWatchdog.get().sampleCapacityStatus();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            capacity = Map.of("wouldAdmit", false,
                    "reason", "capacity sample interrupted");
        }

        long runningProcesses = processes.stream()
                .filter(p -> "RUNNING".equalsIgnoreCase(p.getState()))
                .count();
        long buildProcesses = processes.stream()
                .filter(ProcessCoordEntry::isRunningState)
                .filter(p -> "high".equals(ResourcePolicy.processClass(coordinator.getProjectRoot(), p)))
                .count();

        StringBuilder sb = new StringBuilder();
        sb.append("Cross-agent awareness snapshot\n");
        sb.append("Time: ").append(Instant.now()).append("\n\n");

        sb.append("Agents active: ").append(agents.size()).append("\n");
        if (agents.isEmpty()) {
            sb.append("  (none)\n");
        } else {
            for (AgentEntry a : agents) {
                sb.append("  - ").append(a.getAgentName())
                        .append(" [").append(StringUtils.truncate(a.getSessionId(), 18)).append("]")
                        .append(" depth=").append(a.getDepth())
                        .append(" running ").append(formatAge(a.getStartedAt()))
                        .append(" — ").append(StringUtils.truncate(safe(a.getTask()), 90))
                        .append("\n");
            }
        }

        sb.append("\nFiles currently locked: ").append(edits.size()).append("\n");
        if (edits.isEmpty()) {
            sb.append("  (none)\n");
        } else {
            for (EditLockEntry e : edits) {
                sb.append("  - ").append(e.getEditType()).append(" ")
                        .append(e.getFilePath())
                        .append(" by ").append(e.getAgentName())
                        .append(" [").append(StringUtils.truncate(e.getSessionId(), 18)).append("]")
                        .append(" for ").append(formatAge(e.getAcquiredAt()))
                        .append("\n");
            }
        }

        sb.append("\nProcesses visible across agents: ").append(processes.size())
                .append(" (").append(runningProcesses).append(" running, ")
                .append(buildProcesses).append(" high-resource)\n");
        if (processes.isEmpty()) {
            sb.append("  (none)\n");
        } else {
            for (ProcessCoordEntry p : processes) {
                sb.append("  - ").append(p.getProcessId())
                        .append(" ").append(p.getState())
                        .append(" resource=").append(ResourcePolicy.processClass(coordinator.getProjectRoot(), p))
                        .append(" pid=").append(p.getPid())
                        .append(" by ").append(p.getAgentName())
                        .append(" [").append(StringUtils.truncate(p.getSessionId(), 18)).append("]")
                        .append(" for ").append(FormatUtils.formatDuration(p.getDuration()))
                        .append(" — ").append(StringUtils.truncate(safe(p.getDescription()), 90))
                        .append("\n");
            }
        }

        sb.append("\nPending coordination messages: ").append(messages.size()).append("\n");
        if (messages.isEmpty()) {
            sb.append("  (none)\n");
        } else {
            for (CoordinationMessage message : messages.stream().limit(5).toList()) {
                sb.append("  - ").append(message.getMessageId())
                        .append(" from ").append(StringUtils.truncate(message.getSenderSessionId(), 18))
                        .append(" [").append(message.getKind()).append("] — ")
                        .append(StringUtils.truncate(message.getMessage().replace('\n', ' '), 90))
                        .append("\n");
            }
            if (messages.size() > 5) {
                sb.append("  - ... and ").append(messages.size() - 5).append(" more\n");
            }
        }

        sb.append("\nSystem-wide high-memory activities: ").append(activities.size()).append("\n");
        if (activities.isEmpty()) {
            sb.append("  (none)\n");
        } else {
            for (CoordinationActivity activity : activities) {
                sb.append("  - ").append(activity.getKind())
                        .append(" ").append(activity.getActivityId())
                        .append(" by ").append(firstNonBlank(
                                activity.getAgentName(), activity.getSessionId(), "unknown"))
                        .append(" in ").append(activity.getProjectRoot())
                        .append(" — ").append(StringUtils.truncate(
                                safe(activity.getDescription()), 90)).append("\n");
            }
        }

        sb.append("\nHost memory/GPU preflight: ")
                .append(Boolean.TRUE.equals(capacity.get("wouldAdmit")) ? "available" : "constrained")
                .append(" — ").append(capacity.getOrDefault("reason", "current thresholds satisfied"))
                .append("\n");
        Object capacitySnapshot = capacity.get("capacity");
        if (capacitySnapshot != null) {
            sb.append("  ").append(capacitySnapshot).append("\n");
        }

        sb.append("\nCoordination guidance:\n");
        if (!edits.isEmpty()) {
            sb.append("  - Re-read and avoid editing locked files unless you own the listed lock.\n");
        }
        if (runningProcesses > 0) {
            sb.append("  - Check running process output before launching duplicate builds/tests.\n");
        }
        if (!activities.isEmpty()) {
            sb.append("  - Do not start another high-memory activity until the system lane is released.\n");
        }
        if (agents.size() > 1) {
            sb.append("  - Align with active agents' task scopes before overlapping edits.\n");
        }
        if (!messages.isEmpty()) {
            sb.append("  - Process pending messages, then acknowledge each with ack_message.\n");
        }
        if (edits.isEmpty() && runningProcesses == 0 && activities.isEmpty()
                && agents.size() <= 1 && messages.isEmpty()) {
            sb.append("  - No cross-agent contention detected.\n");
        }

        return ToolResult.success("awareness", sb.toString(),
                Map.of("agents", agents.size(), "edits", edits.size(),
                        "processes", processes.size(), "runningProcesses", runningProcesses,
                        "pendingMessages", messages.size(), "activities", activities.size(),
                        "capacity", capacity));
    }

    private ToolResult executeStatus() {
        String dashboard = coordinator.statusDashboard();
        return ToolResult.success("status", dashboard);
    }

    private static String safe(String text) {
        return text == null ? "" : text;
    }

    private static String firstNonBlank(String... values) {
        if (values == null) return null;
        for (String value : values) {
            if (value != null && !value.isBlank()) return value;
        }
        return null;
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
