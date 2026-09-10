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

import ai.kompile.cli.common.KompileHome;
import ai.kompile.cli.common.util.JsonUtils;
import ai.kompile.cli.main.chat.TranscriptLogScope;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.*;

/**
 * Create and update transcript-session-backed task lists for tracking work.
 * Comparable to OpenCode's TodoWriteTool with priority support.
 */
public class TodoWriteTool implements CliTool {

    private static final ObjectMapper OM = JsonUtils.standardMapper();
    private static final String LEGACY_PROJECT_TODO_MEMORY_FILE = "project-todos.json.md";
    private static final String SESSION_TODO_SUFFIX = ".todos.json";
    private static final String DEFAULT_SESSION_ID = "default";
    /** Explicit lookup key for the single global task file written by older releases. */
    public static final String LEGACY_SESSION_ID = "legacy";

    /** Prevent overlapping JVM file locks; todo operations are tiny and infrequent. */
    private static final Object PROCESS_LOCK = new Object();

    private record TodoSessionKey(Path projectRoot, String sessionId) { }

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
        return "Create or update tasks in the current transcript session's persisted todo list for tracking progress. " +
                "Actions: 'set' to replace the list, 'add' to create a new task, 'update' to change status/priority, " +
                "or 'delete' to remove. Each transcript session is persisted separately alongside its conversation " +
                "under ~/.kompile/conversations/. " +
                "Status values: pending, in_progress, completed, cancelled. " +
                "Priority values: high, medium, low (default: medium). " +
                "Use this to track multi-step work.";
    }

    @Override
    public String compactHint() {
        return "Manage tasks for the current transcript session: set, add, update, or delete.";
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
        String sessionId = resolveSessionId(context, null);
        TodoSessionKey sessionKey = sessionKey(context.getWorkingDirectory(), sessionId);
        Path todoFile = sessionTodoPath(sessionId);
        try {
            Files.createDirectories(todoFile.getParent());
            Path lockFile = todoFile.resolveSibling("." + todoFile.getFileName() + ".lock");
            synchronized (PROCESS_LOCK) {
                try (FileChannel channel = FileChannel.open(lockFile,
                        StandardOpenOption.CREATE, StandardOpenOption.WRITE);
                     FileLock ignored = channel.lock()) {
                    List<TodoItem> todos = loadTodos(sessionKey);
                    return executeAction(action, params, context, sessionId, todos);
                }
            }
        } catch (IOException e) {
            throw new ToolExecutionException(
                    "Failed to persist todo list for session '" + sessionId + "': " + e.getMessage(), false);
        }
    }

    private static ToolResult executeAction(
            String action, JsonNode params, ToolContext context,
            String sessionId, List<TodoItem> todos) throws IOException {
        switch (action) {
                case "set": {
                    JsonNode todosNode = params.path("todos");
                    if (!todosNode.isArray()) return ToolResult.error("todos array is required for set");
                    List<TodoItem> replacement = parseTodos(todosNode);
                    todos.clear();
                    todos.addAll(replacement);
                    persistTodos(context, sessionId, todos);
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
                    persistTodos(context, sessionId, todos);
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
                            persistTodos(context, sessionId, todos);
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
                        persistTodos(context, sessionId, todos);
                    }
                    return removed ? ToolResult.success("Deleted task #" + taskId)
                            : ToolResult.error("Task not found: " + taskId);
                }
                default:
                    return ToolResult.error("Unknown action: " + action + ". Use set, add, update, or delete.");
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

    /** Resolve an explicit historical session, or default to the active transcript session. */
    static String resolveSessionId(ToolContext context, String explicitSessionId) {
        if (explicitSessionId != null && !explicitSessionId.isBlank()) {
            return explicitSessionId.trim();
        }
        String transcriptId = TranscriptLogScope.currentTranscriptId();
        if (transcriptId != null && !transcriptId.isBlank()) {
            return transcriptId.trim();
        }
        String contextSessionId = context == null ? null : context.getSessionId();
        return contextSessionId == null || contextSessionId.isBlank()
                ? DEFAULT_SESSION_ID : contextSessionId.trim();
    }

    /** Read the current transcript session's persisted tasks. */
    public static List<TodoItem> getTodos(ToolContext context) {
        return getTodos(resolveSessionId(context, null), context.getWorkingDirectory());
    }

    /** Read one transcript session's persisted tasks, including after process restart/resume. */
    public static List<TodoItem> getTodos(String sessionId, Path workingDirectory) {
        return loadTodos(sessionKey(workingDirectory, normalizeSessionId(sessionId)));
    }

    /** Compatibility overload; the working directory is used only for legacy project state. */
    public static List<TodoItem> getTodos(String sessionId) {
        return getTodos(sessionId, Path.of(System.getProperty("user.dir")));
    }

    private static TodoSessionKey sessionKey(Path workingDirectory, String sessionId) {
        Path projectRoot = workingDirectory == null
                ? Path.of(System.getProperty("user.dir"))
                : workingDirectory;
        return new TodoSessionKey(projectRoot.toAbsolutePath().normalize(), sessionId);
    }

    private static String normalizeSessionId(String sessionId) {
        return sessionId == null || sessionId.isBlank() ? DEFAULT_SESSION_ID : sessionId.trim();
    }

    private static List<TodoItem> loadTodos(TodoSessionKey sessionKey) {
        Path sessionFile = sessionTodoPath(sessionKey.sessionId());
        if (Files.isRegularFile(sessionFile)) {
            return loadTodoFile(sessionFile);
        }

        // The old format stored one global list. Never expose it to a new session by
        // default; it is available explicitly as "legacy", or under the session id
        // recorded in the old file when that metadata exists.
        Path legacyFile = legacyTodoMemoryPath(sessionKey.projectRoot());
        if (!Files.isRegularFile(legacyFile)) {
            return new ArrayList<>();
        }
        try {
            JsonNode root = OM.readTree(legacyFile.toFile());
            String recordedSessionId = root.path("sessionId").asText("").trim();
            if (LEGACY_SESSION_ID.equals(sessionKey.sessionId())
                    || sessionKey.sessionId().equals(recordedSessionId)) {
                JsonNode todosNode = root.has("todos") ? root.path("todos") : root;
                return parseTodos(todosNode);
            }
        } catch (IOException ignored) {
            // Malformed legacy state is treated as unavailable, matching prior behavior.
        }
        return new ArrayList<>();
    }

    private static List<TodoItem> loadTodoFile(Path file) {
        try {
            JsonNode root = OM.readTree(file.toFile());
            JsonNode todosNode = root.has("todos") ? root.path("todos") : root;
            return parseTodos(todosNode);
        } catch (IOException e) {
            return new ArrayList<>();
        }
    }

    private static void persistTodos(
            ToolContext context, String sessionId, List<TodoItem> todos) throws IOException {
        Path file = sessionTodoPath(sessionId);
        Files.createDirectories(file.getParent());
        ObjectNode root = OM.createObjectNode();
        root.put("schema", "kompile-session-todos-v2");
        root.put("project", context.getWorkingDirectory().toAbsolutePath().normalize().toString());
        root.put("sessionId", sessionId);
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

        Path temporary = Files.createTempFile(
                file.getParent(), "." + file.getFileName() + ".", ".tmp");
        try {
            Files.writeString(temporary,
                    OM.writerWithDefaultPrettyPrinter().writeValueAsString(root) + "\n",
                    StandardCharsets.UTF_8, StandardOpenOption.TRUNCATE_EXISTING);
            try {
                Files.move(temporary, file, StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    static Path sessionTodoPath(String sessionId) {
        String encodedSessionId = Base64.getUrlEncoder().withoutPadding().encodeToString(
                normalizeSessionId(sessionId).getBytes(StandardCharsets.UTF_8));
        return KompileHome.homeDirectory().toPath().resolve("conversations")
                .resolve(encodedSessionId + SESSION_TODO_SUFFIX)
                .toAbsolutePath().normalize();
    }

    private static Path legacyTodoMemoryPath(Path projectRoot) {
        return projectRoot.toAbsolutePath().normalize()
                .resolve(".kompile").resolve("memory").resolve(LEGACY_PROJECT_TODO_MEMORY_FILE);
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
