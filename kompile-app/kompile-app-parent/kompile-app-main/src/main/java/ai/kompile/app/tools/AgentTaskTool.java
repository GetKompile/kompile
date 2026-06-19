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

package ai.kompile.app.tools;

import ai.kompile.app.services.agenttask.AgentTaskService;
import ai.kompile.app.services.agenttask.AgentTask;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Persistent per-session task list MCP tool for agents.
 *
 * Agents use this tool to track work items across compaction boundaries.
 * Tasks persist to disk organized by session, so they are never lost when
 * context compaction replaces tool call history with summaries.
 *
 * <p><b>Post-compaction recovery:</b> After compaction, agents should call
 * {@code list_agent_tasks} with their sessionId to re-discover pending work.
 * The sessionId (e.g. "cli-abc12345") does NOT change across compaction — only
 * the conversation context is compressed, not the session identity.
 *
 * <p>Cross-session queries (omit sessionId) are supported to discover tasks
 * from previous sessions that may still be pending.
 */
@Component
public class AgentTaskTool {

    private static final Logger logger = LoggerFactory.getLogger(AgentTaskTool.class);

    private final AgentTaskService taskService;

    @Autowired
    public AgentTaskTool(@Autowired(required = false) AgentTaskService taskService) {
        this.taskService = taskService;
        logger.info("AgentTaskTool initialized");
    }

    // ── Input DTOs ───────────────────────────────────────────────────────────

    public record ListTasksInput(String sessionId, String status, String assignee, String tag) {}
    public record CreateTaskInput(String sessionId, String title, String description,
                                  String assignee, String priority, String tags,
                                  String parentTaskId) {}
    public record UpdateTaskInput(Long taskId, String title, String description,
                                  String status, String assignee, String priority, String tags) {}
    public record CompleteTaskInput(Long taskId, String completionNote) {}
    public record DeleteTaskInput(Long taskId) {}
    public record GetTaskInput(Long taskId) {}
    public record TaskSummaryInput(String sessionId) {}
    public record ListSessionsInput() {}

    // ── Tool methods ─────────────────────────────────────────────────────────

    @Tool(name = "list_agent_tasks",
          description = "Lists persistent agent tasks. Agents SHOULD call this on startup and after " +
                        "compaction to recover awareness of pending work. Provide sessionId to scope " +
                        "to your session (recommended). Omit sessionId to see tasks across ALL sessions " +
                        "(useful for discovering pending work from prior sessions). " +
                        "Filter by status (pending/in_progress/completed/blocked), assignee, or tag.")
    public Map<String, Object> listTasks(ListTasksInput input) {
        if (taskService == null) return errorMap("AgentTaskService not available");
        try {
            List<AgentTask> tasks;
            if (input.sessionId() != null && !input.sessionId().isBlank()) {
                tasks = taskService.listForSession(input.sessionId(), input.status(),
                        input.assignee(), input.tag());
            } else {
                tasks = taskService.listAll(input.status(), input.assignee(), input.tag());
            }
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("status", "success");
            result.put("count", tasks.size());
            if (input.sessionId() != null) result.put("sessionId", input.sessionId());
            result.put("tasks", tasks.stream().map(this::taskToMap).collect(Collectors.toList()));
            return result;
        } catch (Exception e) {
            logger.error("Error listing agent tasks: {}", e.getMessage(), e);
            return errorMap(e.getMessage());
        }
    }

    @Tool(name = "create_agent_task",
          description = "Creates a persistent task that survives compaction. Provide sessionId (your " +
                        "chat session ID) so the task is scoped to your session. Fields: title (required), " +
                        "description, assignee (agent name), priority (low/medium/high/critical), " +
                        "tags (comma-separated), parentTaskId (for sub-tasks).")
    public Map<String, Object> createTask(CreateTaskInput input) {
        if (taskService == null) return errorMap("AgentTaskService not available");
        if (input.title() == null || input.title().isBlank()) return errorMap("title is required");
        try {
            List<String> tags = input.tags() != null
                    ? Arrays.stream(input.tags().split(",")).map(String::trim).filter(s -> !s.isEmpty()).collect(Collectors.toList())
                    : null;
            AgentTask task = taskService.create(
                    input.sessionId(), input.title(), input.description(), input.assignee(),
                    input.priority(), tags, input.parentTaskId());
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("status", "success");
            result.put("taskId", task.getId());
            result.put("sessionId", task.getSessionId());
            result.put("message", "Task created: " + task.getTitle());
            result.put("task", taskToMap(task));
            return result;
        } catch (Exception e) {
            logger.error("Error creating agent task: {}", e.getMessage(), e);
            return errorMap(e.getMessage());
        }
    }

    @Tool(name = "update_agent_task",
          description = "Updates a persistent agent task. Use to change status (pending -> in_progress -> " +
                        "completed/blocked), reassign, reprioritize, or update description. " +
                        "Only provided fields are changed; null fields are left as-is. " +
                        "Works by taskId across all sessions.")
    public Map<String, Object> updateTask(UpdateTaskInput input) {
        if (taskService == null) return errorMap("AgentTaskService not available");
        if (input.taskId() == null) return errorMap("taskId is required");
        try {
            List<String> tags = input.tags() != null
                    ? Arrays.stream(input.tags().split(",")).map(String::trim).filter(s -> !s.isEmpty()).collect(Collectors.toList())
                    : null;
            AgentTask task = taskService.update(input.taskId(), input.title(), input.description(),
                    input.status(), input.assignee(), input.priority(), tags);
            if (task == null) return errorMap("Task not found: " + input.taskId());
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("status", "success");
            result.put("message", "Task updated");
            result.put("task", taskToMap(task));
            return result;
        } catch (Exception e) {
            logger.error("Error updating agent task {}: {}", input.taskId(), e.getMessage(), e);
            return errorMap(e.getMessage());
        }
    }

    @Tool(name = "complete_agent_task",
          description = "Marks a task as completed with an optional completion note describing what was done.")
    public Map<String, Object> completeTask(CompleteTaskInput input) {
        if (taskService == null) return errorMap("AgentTaskService not available");
        if (input.taskId() == null) return errorMap("taskId is required");
        try {
            AgentTask task = taskService.complete(input.taskId(), input.completionNote());
            if (task == null) return errorMap("Task not found: " + input.taskId());
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("status", "success");
            result.put("message", "Task completed: " + task.getTitle());
            result.put("task", taskToMap(task));
            return result;
        } catch (Exception e) {
            logger.error("Error completing agent task {}: {}", input.taskId(), e.getMessage(), e);
            return errorMap(e.getMessage());
        }
    }

    @Tool(name = "delete_agent_task",
          description = "Permanently deletes a task from the persistent store.")
    public Map<String, Object> deleteTask(DeleteTaskInput input) {
        if (taskService == null) return errorMap("AgentTaskService not available");
        if (input.taskId() == null) return errorMap("taskId is required");
        try {
            boolean deleted = taskService.delete(input.taskId());
            if (!deleted) return errorMap("Task not found: " + input.taskId());
            return Map.of("status", "success", "message", "Task deleted", "taskId", input.taskId());
        } catch (Exception e) {
            logger.error("Error deleting agent task {}: {}", input.taskId(), e.getMessage(), e);
            return errorMap(e.getMessage());
        }
    }

    @Tool(name = "get_agent_task",
          description = "Gets full details of a single task by ID. Works across all sessions.")
    public Map<String, Object> getTask(GetTaskInput input) {
        if (taskService == null) return errorMap("AgentTaskService not available");
        if (input.taskId() == null) return errorMap("taskId is required");
        try {
            AgentTask task = taskService.get(input.taskId());
            if (task == null) return errorMap("Task not found: " + input.taskId());
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("status", "success");
            result.put("task", taskToMap(task));
            return result;
        } catch (Exception e) {
            logger.error("Error getting agent task {}: {}", input.taskId(), e.getMessage(), e);
            return errorMap(e.getMessage());
        }
    }

    @Tool(name = "agent_task_summary",
          description = "Returns a summary of agent tasks grouped by status. Provide sessionId to " +
                        "scope to one session, or omit for global summary across all sessions.")
    public Map<String, Object> taskSummary(TaskSummaryInput input) {
        if (taskService == null) return errorMap("AgentTaskService not available");
        try {
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("status", "success");
            result.put("summary", taskService.summary(input.sessionId()));
            return result;
        } catch (Exception e) {
            logger.error("Error getting task summary: {}", e.getMessage(), e);
            return errorMap(e.getMessage());
        }
    }

    @Tool(name = "list_task_sessions",
          description = "Lists all session IDs that have agent tasks. Useful after compaction or " +
                        "when starting a new session to find pending work from prior sessions.")
    public Map<String, Object> listSessions(ListSessionsInput input) {
        if (taskService == null) return errorMap("AgentTaskService not available");
        try {
            List<String> sessions = taskService.listSessions();
            return Map.of("status", "success", "sessions", sessions, "count", sessions.size());
        } catch (Exception e) {
            logger.error("Error listing task sessions: {}", e.getMessage(), e);
            return errorMap(e.getMessage());
        }
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private Map<String, Object> taskToMap(AgentTask task) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", task.getId());
        m.put("sessionId", task.getSessionId());
        m.put("title", task.getTitle());
        m.put("description", task.getDescription());
        m.put("status", task.getStatus());
        m.put("assignee", task.getAssignee());
        m.put("priority", task.getPriority());
        m.put("tags", task.getTags());
        m.put("parentTaskId", task.getParentTaskId());
        m.put("completionNote", task.getCompletionNote());
        m.put("createdAt", task.getCreatedAt());
        m.put("updatedAt", task.getUpdatedAt());
        m.put("completedAt", task.getCompletedAt());
        return m;
    }

    private Map<String, Object> errorMap(String message) {
        return Map.of("status", "error", "error", message);
    }
}
