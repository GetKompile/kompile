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

import ai.kompile.cli.main.coordination.FileWatcherService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.List;
import java.util.Map;

/**
 * MCP tool for querying file activity and managing file change notifications.
 * Enables multi-agent coordination by tracking which agents have read which files
 * and notifying them when those files are modified by other agents.
 */
public class FileActivityTool implements CliTool {

    private final FileWatcherService watcher;

    public FileActivityTool(FileWatcherService watcher) {
        this.watcher = watcher;
    }

    @Override
    public String id() { return "file_activity"; }

    @Override
    public String description() {
        return "Track and query file activity across agents. Actions: "
             + "'status' shows watcher status, "
             + "'recent' lists recent file changes (optional limit param), "
             + "'notifications' gets pending notifications for files this agent has read "
             + "that were modified by other agents, "
             + "'track_read' records that this agent has read a file (for notification tracking), "
             + "'track_write' records that this agent will write a file, "
             + "'clear' clears consumed notifications.";
    }

    @Override
    public JsonNode parameterSchema() {
        ObjectMapper om = new ObjectMapper();
        ObjectNode schema = om.createObjectNode();
        schema.put("type", "object");
        ObjectNode props = schema.putObject("properties");

        ObjectNode action = props.putObject("action");
        action.put("type", "string");
        action.put("description", "Action: status, recent, notifications, track_read, track_write, clear");

        ObjectNode filePath = props.putObject("file_path");
        filePath.put("type", "string");
        filePath.put("description", "File path for track_read/track_write actions");

        ObjectNode limit = props.putObject("limit");
        limit.put("type", "integer");
        limit.put("description", "Max results for recent action (default 20)");

        schema.putArray("required").add("action");
        return schema;
    }

    @Override
    public String permissionKey() { return "read"; }

    @Override
    public ToolResult execute(JsonNode params, ToolContext context) throws ToolExecutionException {
        context.checkPermission(permissionKey(), "File activity tracking");

        String action = params.path("action").asText("");
        String agentId = context.getSessionId();

        switch (action) {
            case "status":
                return ToolResult.success("file_activity: status", watcher.statusSummary());

            case "recent": {
                int limit = params.path("limit").asInt(20);
                List<FileWatcherService.FileChangeEvent> events = watcher.getRecentEvents(limit);
                if (events.isEmpty()) {
                    return ToolResult.success("file_activity: recent", "No recent file changes.");
                }
                StringBuilder sb = new StringBuilder();
                sb.append("Recent file changes (").append(events.size()).append("):\n");
                for (FileWatcherService.FileChangeEvent e : events) {
                    sb.append("  ").append(e).append("\n");
                }
                return ToolResult.success("file_activity: recent", sb.toString(),
                    Map.of("count", events.size()));
            }

            case "notifications": {
                List<FileWatcherService.FileChangeEvent> pending = watcher.getPendingNotifications(agentId);
                if (pending.isEmpty()) {
                    return ToolResult.success("file_activity: notifications",
                        "No pending notifications - no files you've read were modified by other agents.");
                }
                StringBuilder sb = new StringBuilder();
                sb.append("WARNING: ").append(pending.size()).append(" file(s) you have read were modified by other agents:\n");
                for (FileWatcherService.FileChangeEvent e : pending) {
                    sb.append("  ").append(e.changeType).append(": ").append(e.filePath)
                      .append(" (by ").append(e.agentId).append(" at ").append(e.timestamp).append(")\n");
                }
                sb.append("\nYou should re-read these files before making changes to avoid conflicts.");
                return ToolResult.success("file_activity: notifications", sb.toString(),
                    Map.of("count", pending.size()));
            }

            case "track_read": {
                String filePath = params.path("file_path").asText("");
                if (filePath.isEmpty()) return ToolResult.error("file_path is required for track_read");
                watcher.recordRead(agentId, filePath);
                return ToolResult.success("file_activity: tracked", "Now tracking " + filePath + " for change notifications.");
            }

            case "track_write": {
                String filePath = params.path("file_path").asText("");
                if (filePath.isEmpty()) return ToolResult.error("file_path is required for track_write");
                watcher.recordWrite(agentId, filePath);
                return ToolResult.success("file_activity: tracked", "Recorded write intent for " + filePath);
            }

            case "clear": {
                List<FileWatcherService.FileChangeEvent> pending = watcher.getPendingNotifications(agentId);
                watcher.clearNotifications(agentId, pending);
                return ToolResult.success("file_activity: cleared", "Cleared " + pending.size() + " notifications.");
            }

            default:
                return ToolResult.error("Unknown action: " + action);
        }
    }
}
