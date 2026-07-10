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
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Create and update project-backed task lists for tracking work.
 * Comparable to OpenCode's TodoWriteTool with priority support.
 */
public class TodoWriteTool implements CliTool {

    private static final ObjectMapper OM = JsonUtils.standardMapper();
    private static final String PROJECT_TODO_MEMORY_FILE = "project-todos.json.md";

    /** Shared todo storage keyed by session ID, loaded from project memory on first use. */
    private static final Map<String, List<TodoItem>> TODOS = new ConcurrentHashMap<>();

    public static class TodoItem {
        public String id;
        public String subject;
        public String description;
        public String status; // pending, in_progress, completed, cancelled
        public String priority; // high, medium, low

        public TodoItem() {
        }

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
        return "Create or update tasks in the project's persisted todo list for tracking progress. " +
                "Actions: 'set' to replace the list, 'add' to create a new task, 'update' to change status/priority, " +
                "or 'delete' to remove. Todo state is persisted to project memory at .kompile/memory/project-todos.json.md. " +
                "Status values: pending, in_progress, completed, cancelled. " +
                "Priority values: high, medium, low (default: medium). " +
                "Use this to track multi-step work.";
    }

    @Override
    public JsonNode parameterSchema() {
        ObjectMapper om = JsonUtils.standardMapper();
        ObjectNode schema = om.createObjectNode();
        schema.put("type", "object");
        ObjectNode props = schema.putObject("properties");

        ObjectNode action = props.putObject("action");
        action.put("type", "string");
        action.put("description", "Action: 'set', 'add', 'update', or 'delete'");

        ObjectNode todos = props.putObject("todos");
        todos.put("type", "array");
        todos.put("description", "Full task list for action='set'. Items accept id, subject/content, description/task_description, status, priority.");

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
        List<TodoItem> todos = getTodos(context);

        synchronized (todos) {
            switch (action) {
                case "set": {
                    JsonNode todosNode = params.path("todos");
                    if (!todosNode.isArray()) return ToolResult.error("todos array is required for set");
                    List<TodoItem> replacement = parseTodos(todosNode);
                    todos.clear();
                    todos.addAll(replacement);
                    persistProjectTodos(context, todos);
                    return ToolResult.success("Set " + todos.size() + " task(s)");
                }
                case "add": {
                    String subject = params.path("subject").asText("");
                    if (subject.isEmpty()) return ToolResult.error("subject is required for add");
                    String taskDesc = params.path("task_description").asText("");
                    String priority = params.path("priority").asText("medium");
                    if (!isValidPriority(priority)) priority = "medium";
                    String status = params.path("status").asText("pending");
                    if (!isValidStatus(status)) status = "pending";
                    String id = nextTodoId(todos);
                    todos.add(new TodoItem(id, subject, taskDesc, status, priority));
                    persistProjectTodos(context, todos);
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
                            persistProjectTodos(context, todos);
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
                    if (removed) {
                        persistProjectTodos(context, todos);
                    }
                    return removed ? ToolResult.success("Deleted task #" + taskId)
                            : ToolResult.error("Task not found: " + taskId);
                }
                default:
                    return ToolResult.error("Unknown action: " + action + ". Use set, add, update, or delete.");
            }
        }
    }

    private static List<TodoItem> parseTodos(JsonNode todosNode) {
        List<TodoItem> parsed = new ArrayList<>();
        int index = 1;
        for (JsonNode node : todosNode) {
            // Accept plain-string items as subject-only tasks: todos: ["do x", "do y"].
            if (node.isTextual()) {
                String plain = node.asText().trim();
                if (!plain.isEmpty()) {
                    parsed.add(new TodoItem(String.valueOf(index), plain, "", "pending", "medium"));
                }
                index++;
                continue;
            }
            String subject = firstText(node, "subject", "content", "");
            if (subject.isBlank()) {
                index++;
                continue;
            }
            String id = text(node, "id", String.valueOf(index));
            String description = firstText(node, "description", "task_description", "");
            String status = text(node, "status", "pending");
            if (!isValidStatus(status)) status = "pending";
            String priority = text(node, "priority", "medium");
            if (!isValidPriority(priority)) priority = "medium";
            parsed.add(new TodoItem(id, subject, description, status, priority));
            index++;
        }
        return parsed;
    }

    private static String nextTodoId(List<TodoItem> todos) {
        int max = 0;
        for (TodoItem item : todos) {
            try {
                max = Math.max(max, Integer.parseInt(item.id));
            } catch (NumberFormatException ignored) {
                // Non-numeric IDs are allowed for imported lists; new local IDs remain numeric.
            }
        }
        return String.valueOf(max + 1);
    }

    private static boolean isValidStatus(String status) {
        return "pending".equals(status) || "in_progress".equals(status) ||
                "completed".equals(status) || "cancelled".equals(status);
    }

    private static boolean isValidPriority(String priority) {
        return "high".equals(priority) || "medium".equals(priority) || "low".equals(priority);
    }

    /** Get current todos for a session (used by TerminalRenderer and legacy tests). */
    public static List<TodoItem> getTodos(String sessionId) {
        return TODOS.getOrDefault(sessionId, List.of());
    }

    /** Get current todos for a tool context, loading persisted project memory on first use. */
    public static List<TodoItem> getTodos(ToolContext context) {
        return TODOS.computeIfAbsent(context.getSessionId(), ignored -> loadProjectTodos(context));
    }

    private static List<TodoItem> loadProjectTodos(ToolContext context) {
        Path file = projectTodoMemoryPath(context);
        if (!Files.isRegularFile(file)) {
            return new ArrayList<>();
        }
        try {
            JsonNode root = OM.readTree(file.toFile());
            JsonNode todosNode = root.has("todos") ? root.path("todos") : root;
            return parseTodos(todosNode);
        } catch (IOException e) {
            return new ArrayList<>();
        }
    }

    private static void persistProjectTodos(ToolContext context, List<TodoItem> todos) throws ToolExecutionException {
        Path file = projectTodoMemoryPath(context);
        try {
            Files.createDirectories(file.getParent());
            ObjectNode root = OM.createObjectNode();
            root.put("schema", "kompile-project-todos-v1");
            root.put("project", context.getWorkingDirectory().toAbsolutePath().normalize().toString());
            root.put("sessionId", context.getSessionId());
            root.put("updatedAt", Instant.now().toString());
            // Build the array by hand — valueToTree(TodoItem) needs bean reflection, which is
            // unavailable in the native image (TodoItem has no registered reflection config).
            ArrayNode todosArray = root.putArray("todos");
            for (TodoItem item : todos) {
                ObjectNode node = todosArray.addObject();
                node.put("id", item.id == null ? "" : item.id);
                node.put("subject", item.subject == null ? "" : item.subject);
                node.put("description", item.description == null ? "" : item.description);
                node.put("status", item.status == null ? "pending" : item.status);
                node.put("priority", item.priority == null ? "medium" : item.priority);
            }
            Files.writeString(file, OM.writerWithDefaultPrettyPrinter().writeValueAsString(root) + "\n", StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new ToolExecutionException("Failed to persist todo list to project memory: " + e.getMessage(), false);
        }
    }

    private static Path projectTodoMemoryPath(ToolContext context) {
        return context.getWorkingDirectory().resolve(".kompile").resolve("memory").resolve(PROJECT_TODO_MEMORY_FILE);
    }

    private static String firstText(JsonNode node, String first, String second, String fallback) {
        String value = text(node, first, "");
        return value.isEmpty() ? text(node, second, fallback) : value;
    }

    private static String text(JsonNode node, String field, String fallback) {
        JsonNode value = node.get(field);
        if (value == null || value.isNull()) {
            return fallback;
        }
        String text = value.asText("");
        return text.isBlank() ? fallback : text;
    }
}
