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

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Create and update session-scoped task lists for tracking work.
 * Comparable to OpenCode's TodoWriteTool with priority support.
 */
public class TodoWriteTool implements CliTool {

    /** Shared todo storage keyed by session ID. */
    private static final Map<String, List<TodoItem>> TODOS = new ConcurrentHashMap<>();

    public static class TodoItem {
        public String id;
        public String subject;
        public String description;
        public String status; // pending, in_progress, completed, cancelled
        public String priority; // high, medium, low

        public TodoItem(String id, String subject, String description, String status, String priority) {
            this.id = id;
            this.subject = subject;
            this.description = description;
            this.status = status;
            this.priority = priority;
        }
    }

    @Override
    public String id() { return "todowrite"; }

    @Override
    public String description() {
        return "Create or update tasks in the session's todo list for tracking progress. " +
                "Actions: 'add' to create a new task, 'update' to change status/priority, 'delete' to remove. " +
                "Status values: pending, in_progress, completed, cancelled. " +
                "Priority values: high, medium, low (default: medium). " +
                "Use this to track multi-step work.";
    }

    @Override
    public JsonNode parameterSchema() {
        ObjectMapper om = new ObjectMapper();
        ObjectNode schema = om.createObjectNode();
        schema.put("type", "object");
        ObjectNode props = schema.putObject("properties");

        ObjectNode action = props.putObject("action");
        action.put("type", "string");
        action.put("description", "Action: 'add', 'update', or 'delete'");

        ObjectNode taskId = props.putObject("task_id");
        taskId.put("type", "string");
        taskId.put("description", "Task ID (required for update/delete)");

        ObjectNode subject = props.putObject("subject");
        subject.put("type", "string");
        subject.put("description", "Task title (required for add)");

        ObjectNode taskDesc = props.putObject("task_description");
        taskDesc.put("type", "string");
        taskDesc.put("description", "Task description (optional)");

        ObjectNode status = props.putObject("status");
        status.put("type", "string");
        status.put("description", "Task status: pending, in_progress, completed, cancelled");

        ObjectNode priority = props.putObject("priority");
        priority.put("type", "string");
        priority.put("description", "Task priority: high, medium, low (default: medium)");

        schema.putArray("required").add("action");
        return schema;
    }

    @Override
    public String permissionKey() { return "todowrite"; }

    @Override
    public ToolResult execute(JsonNode params, ToolContext context) throws ToolExecutionException {
        context.checkPermission(permissionKey(), "Manage todo list");

        String action = params.path("action").asText("");
        String sessionId = context.getSessionId();
        List<TodoItem> todos = TODOS.computeIfAbsent(sessionId, k -> new ArrayList<>());

        switch (action) {
            case "add": {
                String subject = params.path("subject").asText("");
                if (subject.isEmpty()) return ToolResult.error("subject is required for add");
                String taskDesc = params.path("task_description").asText("");
                String priority = params.path("priority").asText("medium");
                if (!isValidPriority(priority)) priority = "medium";
                String id = String.valueOf(todos.size() + 1);
                todos.add(new TodoItem(id, subject, taskDesc, "pending", priority));
                return ToolResult.success("Added task #" + id + ": " + subject);
            }
            case "update": {
                String taskId = params.path("task_id").asText("");
                if (taskId.isEmpty()) return ToolResult.error("task_id is required for update");
                for (TodoItem item : todos) {
                    if (item.id.equals(taskId)) {
                        String oldStatus = item.status;
                        String status = params.path("status").asText("");
                        if (!status.isEmpty() && isValidStatus(status)) item.status = status;
                        String subject = params.path("subject").asText("");
                        if (!subject.isEmpty()) item.subject = subject;
                        String priority = params.path("priority").asText("");
                        if (!priority.isEmpty() && isValidPriority(priority)) item.priority = priority;
                        String desc = params.path("task_description").asText("");
                        if (!desc.isEmpty()) item.description = desc;
                        return ToolResult.success("Updated task #" + taskId +
                                (!status.isEmpty() ? " (" + oldStatus + " → " + item.status + ")" : ""));
                    }
                }
                return ToolResult.error("Task not found: " + taskId);
            }
            case "delete": {
                String taskId = params.path("task_id").asText("");
                if (taskId.isEmpty()) return ToolResult.error("task_id is required for delete");
                boolean removed = todos.removeIf(t -> t.id.equals(taskId));
                return removed ? ToolResult.success("Deleted task #" + taskId)
                        : ToolResult.error("Task not found: " + taskId);
            }
            default:
                return ToolResult.error("Unknown action: " + action + ". Use add, update, or delete.");
        }
    }

    private boolean isValidStatus(String status) {
        return "pending".equals(status) || "in_progress".equals(status) ||
                "completed".equals(status) || "cancelled".equals(status);
    }

    private boolean isValidPriority(String priority) {
        return "high".equals(priority) || "medium".equals(priority) || "low".equals(priority);
    }

    /** Get current todos for a session (used by TodoReadTool and TerminalRenderer). */
    public static List<TodoItem> getTodos(String sessionId) {
        return TODOS.getOrDefault(sessionId, List.of());
    }
}
