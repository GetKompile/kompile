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
import ai.kompile.cli.main.chat.ChatHistory;
import ai.kompile.cli.main.chat.ChatRepl.TimelineLifecycle;
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
    /** Immutable task snapshot for CLI/provider result rendering, including empty lists. */
    public static final String TASKS_METADATA = "todos";
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
        /** ISO-8601 instant of the last mutation of this item; null on legacy entries. */
        public String updatedBy;
        public String updatedAt;
        /** ISO-8601 instant of when this item entered the list; null on legacy entries. */
        public String createdAt;

        public TodoItem() {
        }

        public TodoItem(String id, String subject, String description, String status, String priority) {
            this(id, subject, description, status, priority, null, null);
        }

        public TodoItem(String id, String subject, String description, String status, String priority,
                        String updatedBy, String updatedAt) {
            this(id, subject, description, status, priority, updatedBy, updatedAt, null);
        }

        public TodoItem(String id, String subject, String description, String status, String priority,
                        String updatedBy, String updatedAt, String createdAt) {
            this.id = id;
            this.subject = subject;
            this.description = description;
            this.status = status;
            this.priority = priority;
            this.updatedBy = updatedBy;
            this.updatedAt = updatedAt;
            this.createdAt = createdAt;
        }
    }

    @Override
    public String id() { return "todowrite"; }

    @Override
    public String description() {
        return "Create or update tasks in the current transcript session's persisted todo list for tracking progress. " +
                "Actions: 'set' to initialize or synchronize the full list without dropping existing IDs, " +
                "'add' to append a new task, 'update' to change an existing task, " +
                "or 'delete' to remove. Each transcript session is persisted separately alongside its conversation " +
                "under ~/.kompile/conversations/. " +
                "Status values: pending, in_progress, completed, cancelled. " +
                "Mark the current task in_progress to highlight it in CLI chat; update it when work moves on. " +
                "Priority values: high, medium, low (default: medium). " +
                "Read todoread for real IDs; never invent task or session IDs. " +
                "For set, retain every existing task with its exact ID; omit IDs on new tasks. " +
                "Use add to append, update for individual changes, and delete with a real task_id to remove. " +
                "For multiple changes in one atomic call, use todo_apply_batch. " +
                "Invalid calls leave the list unchanged.";
    }

    @Override
    public String compactHint() {
        return "Manage tasks for the current transcript session. Prefer add/update with IDs from todoread. " +
                "set must retain all existing IDs; use delete for explicit removal. Invalid calls change nothing.";
    }

    @Override
    public JsonNode parameterSchema() {
        ObjectMapper om = JsonUtils.standardMapper();
        ObjectNode schema = om.createObjectNode();
        schema.put("type", "object");
        ObjectNode props = schema.putObject("properties");

        ObjectNode action = props.putObject("action");
        action.put("type", "string");
        action.put("description", "Action: 'set', 'add', 'update', or 'delete'. Prefer add/update over rewriting the list.");
        action.putArray("enum").add("set").add("add").add("update").add("delete");

        ObjectNode todos = props.putObject("todos");
        todos.put("type", "array");
        todos.put("description", "Full task list for set. Must retain every existing ID from todoread. " +
                "Omit IDs on new tasks; set cannot delete tasks. Items accept subject/content, " +
                "description/task_description, status, priority, or a plain task title.");
        ObjectNode alternatives = todos.putObject("items");
        ArrayNode itemTypes = alternatives.putArray("anyOf");
        itemTypes.addObject().put("type", "string").put("minLength", 1);
        ObjectNode item = itemTypes.addObject().put("type", "object");
        item.put("additionalProperties", false);
        ObjectNode itemProps = item.putObject("properties");
        for (String field : List.of("id", "subject", "content", "description", "task_description", "status", "priority")) {
            itemProps.putObject(field).put("type", "string");
        }
        itemProps.withObject("/status").putArray("enum")
                .add("pending").add("in_progress").add("completed").add("cancelled");
        itemProps.withObject("/priority").putArray("enum").add("high").add("medium").add("low");

        ObjectNode taskId = props.putObject("task_id");
        taskId.put("type", "string");
        taskId.put("description", "Exact existing task ID from todoread (required for update/delete); never invent an ID.");

        ObjectNode subject = props.putObject("subject");
        subject.put("type", "string");
        subject.put("description", "Task title (required for add)");

        ObjectNode taskDesc = props.putObject("task_description");
        taskDesc.put("type", "string");
        taskDesc.put("description", "Task description (optional)");

        ObjectNode status = props.putObject("status");
        status.put("type", "string");
        status.put("description", "Task status: pending, in_progress, completed, cancelled. " +
                "Use in_progress for the current task to highlight it in CLI chat.");

        ObjectNode priority = props.putObject("priority");
        priority.put("type", "string");
        priority.put("description", "Task priority: high, medium, low (default: medium)");

        ObjectNode session = props.putObject("session_id");
        session.put("type", "string");
        session.put("description", "Optional existing target transcript session; omit to use the current session. Unknown targets are rejected.");
        status.putArray("enum").add("pending").add("in_progress").add("completed").add("cancelled");
        priority.putArray("enum").add("high").add("medium").add("low");
        schema.put("additionalProperties", false);
        schema.putArray("required").add("action");
        return schema;
    }

    @Override
    public String permissionKey() { return "todowrite"; }

    @Override
    public ToolResult execute(JsonNode params, ToolContext context) throws ToolExecutionException {
        return executeMutation(params, context, false);
    }

    ToolResult executeBatch(JsonNode params, ToolContext context) throws ToolExecutionException {
        return executeMutation(params, context, true);
    }

    private ToolResult executeMutation(JsonNode params, ToolContext context, boolean batch)
            throws ToolExecutionException {
        context.checkPermission(permissionKey(), "Manage todo list");

        try {
            if (batch) validateBatchRequest(params);
            else validateRequest(params);
        } catch (IllegalArgumentException e) {
            return rejected(e.getMessage());
        }
        String action = batch ? "apply_batch" : params.path("action").asText();
        String sessionId = resolveSessionId(context, params.path("session_id").asText(null));
        TodoSessionKey sessionKey = sessionKey(context.getWorkingDirectory(), sessionId);
        Path todoFile = sessionTodoPath(sessionId);
        try {
            Files.createDirectories(todoFile.getParent());
            Path lockFile = todoFile.resolveSibling("." + todoFile.getFileName() + ".lock");
            synchronized (PROCESS_LOCK) {
                try (FileChannel channel = FileChannel.open(lockFile,
                        StandardOpenOption.CREATE, StandardOpenOption.WRITE);
                     FileLock ignored = channel.lock()) {
                    List<TodoItem> todos;
                    try {
                        todos = loadTodosForWrite(sessionKey);
                    } catch (IOException | IllegalArgumentException e) {
                        return rejected("Cannot safely read persisted todos: " + e.getMessage());
                    }
                    if (params.has("session_id") && !sessionId.equals(resolveSessionId(context, null))
                            && !Files.isRegularFile(todoFile) && todos.isEmpty() && !transcriptExists(sessionId)) {
                        return rejected("Unknown target session_id: " + sessionId);
                    }
                    Map<String, String> before = todoStates(todos);
                    ToolResult result;
                    try {
                        result = batch ? applyBatch(params.path("operations"), todos)
                                : executeAction(action, params, todos);
                    } catch (IllegalArgumentException e) {
                        return rejected(e.getMessage());
                    }
                    // Mutations are staged in memory; rejected batches never reach the save.
                    if (!result.isError()) {
                        persistTodos(context, sessionId, todos);
                        recordTransitions(context, sessionId, action, before, todoStates(todos));
                        return withTodoSnapshot(result, sessionId, todos);
                    }
                    return result;
                }
            }
        } catch (IOException e) {
            throw new ToolExecutionException(
                    "Failed to persist todo list for session '" + sessionId + "': " + e.getMessage(), false);
        }
    }

    private static ToolResult applyBatch(JsonNode operations, List<TodoItem> todos) {
        List<String> results = new ArrayList<>();
        for (JsonNode operation : operations) {
            ToolResult result = executeAction(operation.path("action").asText(), operation, todos);
            if (result.isError()) {
                return rejected("Batch operation " + (results.size() + 1) + " failed: " + result.getOutput());
            }
            results.add(result.getOutput());
        }
        return ToolResult.success("Applied " + results.size() + " todo operation(s)",
                String.join("\n", results));
    }

    private static ToolResult executeAction(
            String action, JsonNode params, List<TodoItem> todos) {
        switch (action) {
                case "set": {
                    List<TodoItem> replacement = validatedReplacement(params.path("todos"), todos);
                    todos.clear();
                    todos.addAll(replacement);
                    return ToolResult.success("Set " + todos.size() + " task(s)");
                }
                case "add": {
                    String subject = params.path("subject").asText("");
                    String taskDesc = params.path("task_description").asText("");
                    String priority = params.path("priority").asText("medium");
                    String status = params.path("status").asText("pending");
                    String id = nextTodoId(todos);
                    TodoItem added = new TodoItem(id, subject, taskDesc, status, priority);
                    added.createdAt = Instant.now().toString();
                    todos.add(added);
                    return ToolResult.success("Added task #" + id + ": " + subject);
                }
                case "update": {
                    String taskId = params.path("task_id").asText("");
                    if (taskId.isEmpty()) return ToolResult.error("task_id is required for update");
                    for (TodoItem item : todos) {
                        if (item.id.equals(taskId)) {
                            TodoItem before = new TodoItem(item.id, item.subject, item.description,
                                    item.status, item.priority);
                            String oldStatus = item.status;
                            String status = params.path("status").asText("");
                            if (!status.isEmpty() && isValidStatus(status)) item.status = status;
                            String subject = params.path("subject").asText("");
                            if (!subject.isEmpty()) item.subject = subject;
                            String priority = params.path("priority").asText("");
                            if (!priority.isEmpty() && isValidPriority(priority)) item.priority = priority;
                            String desc = params.path("task_description").asText("");
                            if (params.has("task_description")) item.description = desc;
                            // Preserve legacy backfill without giving a modern no-op an update stamp.
                            if (item.createdAt == null) {
                                item.createdAt = Instant.now().toString();
                                if (item.updatedAt == null) item.updatedAt = item.createdAt;
                            }
                            stampMutation(item, before, "model");
                            return ToolResult.success("Updated task #" + taskId +
                                    (!status.isEmpty() ? " (" + oldStatus + " → " + item.status + ")" : ""));
                        }
                    }
                    return rejected("Task not found: " + taskId);
                }
                case "delete": {
                    String taskId = params.path("task_id").asText("");
                    if (taskId.isEmpty()) return ToolResult.error("task_id is required for delete");
                    boolean removed = todos.removeIf(t -> t.id.equals(taskId));
                    return removed ? ToolResult.success("Deleted task #" + taskId)
                            : rejected("Task not found: " + taskId);
                }
                default:
                    return ToolResult.error("Unknown action: " + action + ". Use set, add, update, or delete.");
        }
    }

    /** Stamp only changes to persisted task content, never a repeated synchronization. */
    private static void stampMutation(TodoItem item, TodoItem before, String updatedBy) {
        if (!Objects.equals(item.subject, before.subject)
                || !Objects.equals(item.description, before.description)
                || !Objects.equals(item.status, before.status)
                || !Objects.equals(item.priority, before.priority)) {
            item.updatedBy = updatedBy;
            item.updatedAt = Instant.now().toString();
        }
    }

    private static boolean transcriptExists(String sessionId) {
        // Transcript filenames use raw IDs, unlike the encoded todo filenames.
        if (sessionId.contains("/") || sessionId.contains("\\") || sessionId.indexOf('\0') >= 0) return false;
        return Files.isRegularFile(new ChatHistory(sessionId).getTranscriptFile());
    }

    private static ToolResult rejected(String reason) {
        return ToolResult.error(reason + ". No tasks were changed. Read todoread for the current list and exact IDs; " +
                "use add to append, update with a real task_id, or delete for explicit removal.");
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalArgumentException(message);
    }

    private static void validateFields(JsonNode node, Set<String> allowed) {
        require(node != null && node.isObject(), "Expected a todo object");
        node.fieldNames().forEachRemaining(field -> {
            require(allowed.contains(field), "Unexpected field: " + field);
            if (!"todos".equals(field)) {
                require(node.get(field).isTextual(), field + " must be a string");
                if (!"description".equals(field) && !"task_description".equals(field)) {
                    require(!node.get(field).asText().isBlank(), field + " must not be blank");
                }
            }
        });
        if (node.has("status")) require(isValidStatus(node.get("status").asText()),
                "Invalid status; use pending, in_progress, completed, or cancelled");
        if (node.has("priority")) require(isValidPriority(node.get("priority").asText()),
                "Invalid priority; use high, medium, or low");
        if (node.has("subject") && node.has("content")) require(node.get("subject").equals(node.get("content")),
                "Conflicting subject and content");
        if (node.has("description") && node.has("task_description")) {
            require(node.get("description").equals(node.get("task_description")), "Conflicting description aliases");
        }
    }

    private static void validateBatchRequest(JsonNode params) {
        require(params != null && params.isObject(), "Expected a todo batch object");
        params.fieldNames().forEachRemaining(field -> require(
                Set.of("operations", "session_id").contains(field), "Unexpected field: " + field));
        if (params.has("session_id")) require(params.get("session_id").isTextual()
                && !params.get("session_id").asText().isBlank(), "session_id must be a nonblank string");
        JsonNode operations = params.path("operations");
        require(operations.isArray() && !operations.isEmpty(), "operations must be a non-empty array");
        int index = 0;
        for (JsonNode operation : operations) {
            index++;
            try {
                require(operation.isObject(), "Expected an operation object");
                require(!operation.has("session_id"), "Set session_id on the batch, not individual operations");
                require(Set.of("add", "update", "delete").contains(operation.path("action").asText()),
                        "Batch action must be add, update, or delete");
                validateRequest(operation);
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException("Batch operation " + index + ": " + e.getMessage());
            }
        }
    }

    private static void validateRequest(JsonNode params) {
        require(params != null && params.isObject(), "Expected a todowrite object");
        String action = params.path("action").asText("");
        Set<String> allowed = switch (action) {
            case "set" -> Set.of("action", "session_id", "todos");
            case "add" -> Set.of("action", "session_id", "subject", "task_description", "status", "priority");
            case "update" -> Set.of("action", "session_id", "task_id", "subject", "task_description", "status", "priority");
            case "delete" -> Set.of("action", "session_id", "task_id");
            default -> throw new IllegalArgumentException("Unknown action: " + action + ". Use set, add, update, or delete");
        };
        validateFields(params, allowed);
        switch (action) {
            case "set" -> require(params.path("todos").isArray(), "todos array is required for set");
            case "add" -> require(params.has("subject"), "subject is required for add");
            case "update", "delete" -> require(params.has("task_id"), "task_id is required for " + action);
        }
        if ("update".equals(action)) require(params.has("subject") || params.has("task_description")
                || params.has("status") || params.has("priority"), "update requires at least one task field");
    }

    /** Validate every item before allowing even one mutation; never silently drop input. */
    private static List<TodoItem> validatedReplacement(JsonNode nodes, List<TodoItem> existing) {
        validateTodoArray(nodes);
        Map<String, TodoItem> previous = new LinkedHashMap<>();
        existing.forEach(item -> previous.put(item.id, item));
        Set<String> explicitIds = new LinkedHashSet<>();
        for (JsonNode node : nodes) {
            if (node.has("id")) {
                String id = node.get("id").asText();
                require(explicitIds.add(id), "Duplicate task ID: " + id);
                require(existing.isEmpty() || previous.containsKey(id),
                        "Unknown task ID in set: " + id + "; omit IDs on new tasks");
            }
        }
        Set<String> missing = new LinkedHashSet<>(previous.keySet());
        missing.removeAll(explicitIds);
        require(missing.isEmpty(), "set would remove existing task IDs: " + missing + "; retain them or use delete");

        List<TodoItem> replacement = new ArrayList<>();
        // Reserve explicit IDs first, so generated IDs cannot collide with later items.
        List<TodoItem> allocated = new ArrayList<>(existing);
        explicitIds.forEach(id -> allocated.add(new TodoItem(id, "", "", "pending", "medium")));
        for (JsonNode node : nodes) {
            String id = node.has("id") ? node.get("id").asText() : nextTodoId(allocated);
            TodoItem old = previous.get(id);
            TodoItem item = new TodoItem(id,
                    node.isTextual() ? node.asText().trim() : firstText(node, "subject", "content", ""),
                    firstText(node, "description", "task_description", old == null ? "" : old.description),
                    text(node, "status", old == null ? "pending" : old.status),
                    text(node, "priority", old == null ? "medium" : old.priority),
                    old == null ? null : old.updatedBy,
                    old == null ? null : old.updatedAt,
                    // New items are stamped now; existing items keep their original creation time.
                    old == null ? Instant.now().toString() : old.createdAt);
            // An explicit empty description is a valid edit, not an omitted field.
            if (node.has("description")) item.description = node.get("description").asText();
            else if (node.has("task_description")) item.description = node.get("task_description").asText();
            if (old != null) stampMutation(item, old, "model");
            replacement.add(item);
            allocated.add(item);
        }
        return replacement;
    }

    private static void validateTodoArray(JsonNode nodes) {
        require(nodes != null && nodes.isArray(), "todos must be an array");
        Set<String> ids = new LinkedHashSet<>();
        for (JsonNode node : nodes) {
            if (node.isTextual()) {
                require(!node.asText().isBlank(), "Todo title must not be blank");
            } else {
                validateFields(node, Set.of("id", "subject", "content", "description", "task_description",
                        "status", "priority", "updated_by", "updated_at", "created_at"));
                require(node.has("subject") || node.has("content"), "Todo subject/content is required");
                if (node.has("id")) require(ids.add(node.get("id").asText()), "Duplicate task ID: " + node.get("id").asText());
            }
        }
    }

    /** Writes must not treat unreadable or malformed state as an empty list. */
    private static List<TodoItem> loadTodosForWrite(TodoSessionKey key) throws IOException {
        Path file = sessionTodoPath(key.sessionId());
        if (!Files.exists(file)) {
            file = legacyTodoMemoryPath(key.projectRoot());
            if (!Files.exists(file)) return new ArrayList<>();
            JsonNode legacy = OM.readTree(file.toFile());
            require(legacy != null, "Empty legacy todo file");
            if (!LEGACY_SESSION_ID.equals(key.sessionId())
                    && !key.sessionId().equals(legacy.path("sessionId").asText())) return new ArrayList<>();
        }
        JsonNode root = OM.readTree(file.toFile());
        require(root != null, "Empty todo file");
        JsonNode nodes = root.has("todos") ? root.get("todos") : root;
        validateTodoArray(nodes);
        List<TodoItem> todos = parseTodos(nodes);
        require(todos.stream().map(item -> item.id).distinct().count() == todos.size(), "Duplicate persisted task IDs");
        return todos;
    }

    static ToolResult withTodoSnapshot(ToolResult result, String sessionId, List<TodoItem> todos) {
        Map<String, Object> metadata = new LinkedHashMap<>(result.getMetadata());
        metadata.put("sessionId", sessionId);
        // Copy while holding the write lock; later updates must not alter retained results.
        metadata.put(TASKS_METADATA, OM.valueToTree(todos));
        return new ToolResult(result.getTitle(), result.getOutput(), metadata, result.isError());
    }

    private static Map<String, String> todoStates(List<TodoItem> todos) {
        Map<String, String> states = new LinkedHashMap<>();
        todos.forEach(todo -> states.put(todo.id, todo.status == null ? "" : todo.status));
        return states;
    }

    private static void recordTransitions(ToolContext context, String targetSession, String action,
                                          Map<String, String> before, Map<String, String> after) {
        TimelineLifecycle.ProducerContext producer = TimelineLifecycle.producer(targetSession,
                resolveSessionId(context, null), context.getWorkingDirectory());
        Set<String> ids = new LinkedHashSet<>(before.keySet());
        ids.addAll(after.keySet());
        Path snapshot = sessionTodoPath(targetSession);
        for (String id : ids) {
            String oldState = before.get(id);
            String newState = after.get(id);
            if (!Objects.equals(oldState, newState)) producer.recordSaved("todo.changed", id,
                    snapshot, snapshot.getParent(), Map.of("action", action,
                            "before", oldState == null ? "absent" : oldState,
                            "after", newState == null ? "absent" : newState, "basis", "snapshot-saved"));
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
            TodoItem item = new TodoItem(id, subject, description, status, priority);
            if (node.hasNonNull("updated_by")) item.updatedBy = node.get("updated_by").asText();
            if (node.hasNonNull("updated_at")) item.updatedAt = node.get("updated_at").asText();
            if (node.hasNonNull("created_at")) item.createdAt = node.get("created_at").asText();
            parsed.add(item);
            index++;
        }
        return parsed;
    }

    private static String nextTodoId(List<TodoItem> todos) {
        java.math.BigInteger max = java.math.BigInteger.ZERO;
        for (TodoItem item : todos) {
            try {
                max = max.max(new java.math.BigInteger(item.id));
            } catch (NumberFormatException ignored) {
                // Non-numeric IDs are allowed for imported lists; new local IDs remain numeric.
            }
        }
        return max.add(java.math.BigInteger.ONE).toString();
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

    /**
     * Harness-originated task change, applied with the same locking and validation as a model
     * write but without a permission prompt. The current turn cannot be auto-started while
     * another task is already in_progress, and auto-start is a no-op on an empty list.
     * Returns the mutated list, or the untouched list with a reason when no change applied.
     */
    public static HarnessMutationResult applyHarnessMutation(HarnessMutation mutation, Path projectRoot) {
        String sessionId = normalizeSessionId(mutation.sessionId());
        TodoSessionKey sessionKey = sessionKey(projectRoot, sessionId);
        Path todoFile = sessionTodoPath(sessionId);
        try {
            Files.createDirectories(todoFile.getParent());
            Path lockFile = todoFile.resolveSibling("." + todoFile.getFileName() + ".lock");
            synchronized (PROCESS_LOCK) {
                try (FileChannel channel = FileChannel.open(lockFile,
                        StandardOpenOption.CREATE, StandardOpenOption.WRITE);
                     FileLock ignored = channel.lock()) {
                    List<TodoItem> todos = loadTodosForWrite(sessionKey);
                    Map<String, String> before = todoStates(todos);
                    String reason = switch (mutation.kind()) {
                        case AUTO_START_FIRST -> autoStartFirst(mutation, todos);
                    };
                    if (reason != null) {
                        return new HarnessMutationResult(todos, false, reason);
                    }
                    persistTodos(projectRoot, sessionId, todos);
                    recordHarnessTransitions(sessionId, projectRoot, before, todoStates(todos));
                    return new HarnessMutationResult(todos, true, reason);
                }
            }
        } catch (IOException | IllegalArgumentException e) {
            return new HarnessMutationResult(null, false,
                    "harness todo mutation failed: " + e.getMessage());
        }
    }

    private static String autoStartFirst(HarnessMutation mutation, List<TodoItem> todos) {
        boolean anyInProgress = todos.stream().anyMatch(t -> "in_progress".equals(t.status));
        if (anyInProgress) return "a task is already in_progress";
        for (TodoItem item : todos) {
            if ("pending".equals(item.status)) {
                TodoItem before = new TodoItem(item.id, item.subject, item.description,
                        item.status, item.priority);
                item.status = "in_progress";
                stampMutation(item, before, "harness");
                return null;
            }
        }
        return "no pending task to start";
    }

    private static void recordHarnessTransitions(
            String sessionId, Path projectRoot, Map<String, String> before, Map<String, String> after) {
        TimelineLifecycle.ProducerContext producer = TimelineLifecycle.producer(
                sessionId, sessionId, projectRoot);
        Set<String> ids = new LinkedHashSet<>(before.keySet());
        ids.addAll(after.keySet());
        Path snapshot = sessionTodoPath(sessionId);
        for (String id : ids) {
            String oldState = before.get(id);
            String newState = after.get(id);
            if (!Objects.equals(oldState, newState)) producer.recordSaved("todo.changed", id,
                    snapshot, snapshot.getParent(), Map.of("action", "harness-auto",
                            "before", oldState == null ? "absent" : oldState,
                            "after", newState == null ? "absent" : newState, "basis", "harness"));
        }
    }

    /** The kinds of task change the harness itself may apply. */
    public enum HarnessMutationKind { AUTO_START_FIRST }

    public record HarnessMutation(String sessionId, HarnessMutationKind kind) { }

    public record HarnessMutationResult(List<TodoItem> todos, boolean applied, String reason) { }

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
        persistTodos(context.getWorkingDirectory(), sessionId, todos);
    }

    private static void persistTodos(
            Path projectRoot, String sessionId, List<TodoItem> todos) throws IOException {
        Path file = sessionTodoPath(sessionId);
        Files.createDirectories(file.getParent());
        ObjectNode root = OM.createObjectNode();
        root.put("schema", "kompile-session-todos-v2");
        root.put("project", projectRoot.toAbsolutePath().normalize().toString());
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
            if (item.updatedBy != null) node.put("updated_by", item.updatedBy);
            if (item.updatedAt != null) node.put("updated_at", item.updatedAt);
            if (item.createdAt != null) node.put("created_at", item.createdAt);
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
