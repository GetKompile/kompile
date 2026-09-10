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

import java.util.List;
import java.util.Map;

/**
 * Read the current transcript session's persisted todo/task list, or another
 * session's list via the optional {@code session_id} parameter.
 * Comparable to OpenCode's TodoReadTool.
 */
public class TodoReadTool implements CliTool {

    @Override
    public String id() { return "todoread"; }

    @Override
    public String description() {
        return "Read the current transcript session's task list persisted alongside its conversation. " +
                "Pass an older transcript session id via 'session_id' (or 'legacy' for pre-session-scoped project data) " +
                "to look up that session's tasks. Use this to check progress on multi-step work.";
    }

    @Override
    public String compactHint() {
        return "Read current transcript tasks; pass session_id for an older session or 'legacy'.";
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
        schema.putArray("required");
        return schema;
    }

    @Override
    public String permissionKey() { return "todoread"; }

    @Override
    public ToolResult execute(JsonNode params, ToolContext context) throws ToolExecutionException {
        context.checkPermission(permissionKey(), "Read todo list");

        String sessionId = TodoWriteTool.resolveSessionId(
                context, params.path("session_id").asText(""));
        List<TodoWriteTool.TodoItem> todos = TodoWriteTool.getTodos(
                sessionId, context.getWorkingDirectory());

        if (todos.isEmpty()) {
            return ToolResult.success("No tasks for session '" + sessionId + "'.");
        }

        StringBuilder sb = new StringBuilder();
        int pending = 0, inProgress = 0, completed = 0;

        for (TodoWriteTool.TodoItem item : todos) {
            String statusIcon;
            switch (item.status) {
                case "completed": statusIcon = "[x]"; completed++; break;
                case "in_progress": statusIcon = "[~]"; inProgress++; break;
                default: statusIcon = "[ ]"; pending++; break;
            }
            sb.append(String.format("%s #%s: %s", statusIcon, item.id, item.subject));
            if (item.description != null && !item.description.isEmpty()) {
                sb.append("\n    ").append(item.description);
            }
            sb.append("\n");
        }

        return ToolResult.success("Tasks",
                sb.toString().trim(),
                Map.of("total", todos.size(), "pending", pending,
                        "inProgress", inProgress, "completed", completed,
                        "sessionId", sessionId));
    }
}
