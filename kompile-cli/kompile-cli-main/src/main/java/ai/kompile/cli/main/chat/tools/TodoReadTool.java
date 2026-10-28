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

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * Read the current transcript session's persisted todo/task list, or another
 * session's list via the optional {@code session_id} parameter.
 * Comparable to OpenCode's TodoReadTool.
 */
public class TodoReadTool implements CliTool {

    private static final List<String> STATUSES = List.of("all", "pending", "in_progress", "completed", "cancelled");
    private static final List<String> SORT_KEYS = List.of("position", "id", "subject", "status", "priority", "created_at", "updated_at");
    private static final List<String> PRIORITIES = List.of("all", "high", "medium", "low");

    @Override
    public String id() { return "todoread"; }

    @Override
    public String description() {
        return "Search, filter, sort and page the current transcript session's persisted tasks. " +
                "Use query, status, priority or task_id to narrow results; sort_by/sort_dir control order. " +
                "Returns compact pages of 20 (limit max 100), with counts and nextOffset; include_details adds descriptions/timestamps. " +
                "Pass session_id for an older transcript (or 'legacy' for pre-session-scoped data). " +
                "Results are a page, not the full list: use todowrite add/update/delete or todo_apply_batch for edits, " +
                "not set with a partial page.";
    }

    @Override
    public String compactHint() {
        return "Search/page tasks: query/status/priority/task_id, sort_by/sort_dir, offset/limit (20 default, 100 max); session_id for history or 'legacy'.";
    }

    @Override
    public JsonNode parameterSchema() {
        ObjectMapper om = JsonUtils.standardMapper();
        ObjectNode schema = om.createObjectNode();
        schema.put("type", "object");
        ObjectNode props = schema.putObject("properties");
        ObjectNode session = props.putObject("session_id");
        session.put("type", "string");
        session.put("description", "Optional transcript session id. Default: the current transcript session. " +
                "Pass an older session's id (or 'legacy') to read that session's task list.");
        ListQuery.addSchema(props, STATUSES, SORT_KEYS, "position", "asc");
        ListQuery.enumSchema(props, "priority", PRIORITIES, "all", "Filter by task priority.");
        props.putObject("task_id").put("type", "string").put("description", "Exact task ID; combined with other filters.");
        schema.putArray("required");
        return schema;
    }

    @Override
    public String permissionKey() { return "todoread"; }

    @Override
    public ToolResult execute(JsonNode params, ToolContext context) throws ToolExecutionException {
        context.checkPermission(permissionKey(), "Read todo list");

        final ListQuery query;
        final String priority;
        final String taskId;
        final String sessionId;
        try {
            query = ListQuery.parse(params, STATUSES, SORT_KEYS, "position", "asc");
            priority = ListQuery.choice(params, "priority", PRIORITIES, "all");
            taskId = ListQuery.text(params, "task_id", "").trim();
            sessionId = TodoWriteTool.resolveSessionId(context, ListQuery.text(params, "session_id", ""));
        } catch (IllegalArgumentException e) {
            return ToolResult.error(e.getMessage());
        }
        List<TodoWriteTool.TodoItem> todos = TodoWriteTool.getTodos(sessionId, context.getWorkingDirectory());
        List<TodoWriteTool.TodoItem> matches = new ArrayList<>(todos.stream()
                .filter(item -> query.status().equals("all") || query.status().equals(item.status))
                .filter(item -> priority.equals("all") || priority.equals(item.priority))
                .filter(item -> taskId.isEmpty() || taskId.equals(item.id))
                .filter(item -> query.matches(item.id, item.subject, item.description, item.status, item.priority))
                .toList());
        Comparator<TodoWriteTool.TodoItem> comparator = switch (query.sortBy()) {
            case "id" -> Comparator.comparing(item -> item.id, TodoReadTool::compareIds);
            case "subject" -> Comparator.comparing(item -> item.subject, String.CASE_INSENSITIVE_ORDER);
            case "status" -> Comparator.comparingInt(item -> STATUSES.indexOf(item.status));
            case "priority" -> Comparator.comparingInt(item -> PRIORITIES.indexOf(item.priority));
            case "created_at" -> Comparator.comparing(item -> timestamp(item.createdAt));
            case "updated_at" -> Comparator.comparing(item -> timestamp(item.updatedAt));
            default -> null; // Keep persisted position, including imported non-numeric IDs.
        };
        if (comparator != null) {
            if (query.sortDir().equals("desc")) comparator = comparator.reversed();
            matches.sort(comparator.thenComparing(item -> item.id, TodoReadTool::compareIds));
        } else if (query.sortDir().equals("desc")) {
            java.util.Collections.reverse(matches);
        }
        List<TodoWriteTool.TodoItem> page = query.page(matches);
        StringBuilder sb = new StringBuilder();
        if (todos.isEmpty()) sb.append("No tasks for session '").append(sessionId).append("'.\n");
        else if (matches.isEmpty()) sb.append("No tasks match the filters.\n");
        for (TodoWriteTool.TodoItem item : page) {
            String statusIcon = switch (item.status) {
                case "completed" -> "[x]";
                case "in_progress" -> "[~]";
                case "cancelled" -> "[-]";
                default -> "[ ]";
            };
            sb.append(String.format("%s #%s: %s (%s)\n", statusIcon, item.id, item.subject, item.priority));
            if (query.includeDetails()) {
                if (item.description != null && !item.description.isEmpty()) sb.append("    ").append(item.description).append("\n");
                String timing = timingSuffix(item);
                if (!timing.isEmpty()) sb.append("    ").append(timing).append("\n");
            }
        }
        sb.append(query.summary(todos.size(), matches.size(), page.size()));
        Map<String, Object> metadata = query.metadata(todos.size(), matches.size(), page.size());
        metadata.put("pending", (int) todos.stream().filter(item -> "pending".equals(item.status)).count());
        metadata.put("inProgress", (int) todos.stream().filter(item -> "in_progress".equals(item.status)).count());
        metadata.put("completed", (int) todos.stream().filter(item -> "completed".equals(item.status)).count());
        metadata.put("cancelled", (int) todos.stream().filter(item -> "cancelled".equals(item.status)).count());
        // Snapshot ONLY the selected page; hidden full-list metadata would defeat pagination.
        List<TodoWriteTool.TodoItem> snapshot = page.stream().map(item -> query.includeDetails() ? item
                : new TodoWriteTool.TodoItem(item.id, item.subject, "", item.status, item.priority)).toList();
        return TodoWriteTool.withTodoSnapshot(ToolResult.success("Tasks", sb.toString(), metadata), sessionId, snapshot);
    }

    private static Instant timestamp(String value) {
        try {
            return Instant.parse(value);
        } catch (Exception ignored) {
            return Instant.MIN; // Legacy tasks without provenance sort before dated tasks ascending.
        }
    }

    private static int compareIds(String left, String right) {
        boolean leftNumeric = left.matches("[0-9]+");
        boolean rightNumeric = right.matches("[0-9]+");
        if (leftNumeric && rightNumeric) {
            int compared = new java.math.BigInteger(left).compareTo(new java.math.BigInteger(right));
            return compared != 0 ? compared : left.compareTo(right);
        }
        if (leftNumeric != rightNumeric) return leftNumeric ? -1 : 1;
        return left.compareTo(right);
    }

    /** Compact created/updated provenance line; empty when the item predates timestamps. */
    private static String timingSuffix(TodoWriteTool.TodoItem item) {
        String created = shortDate(item.createdAt);
        String updated = shortDate(item.updatedAt);
        if (created.isEmpty() && updated.isEmpty()) return "";
        String text = "created: " + (created.isEmpty() ? "unknown" : created);
        if (!updated.isEmpty() && !updated.equals(created)) {
            text += " | updated: " + updated;
        }
        return text;
    }

    /** "2026-07-06 14:03" from an ISO instant, tolerating absent or unparsable values. */
    private static String shortDate(String isoInstant) {
        if (isoInstant == null || isoInstant.isBlank()) return "";
        try {
            return java.time.ZonedDateTime.parse(isoInstant)
                    .format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"));
        } catch (Exception ignored) {
            return isoInstant;
        }
    }
}
