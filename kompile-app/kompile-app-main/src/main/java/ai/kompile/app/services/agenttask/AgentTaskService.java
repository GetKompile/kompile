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

package ai.kompile.app.services.agenttask;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Persistent per-session task list service for agents.
 *
 * Tasks are stored as JSON files organized by session:
 * {@code ~/.kompile/agent-state/tasks/<sessionId>/task-<id>.json}
 *
 * This ensures tasks survive context compaction. When compaction replaces
 * tool call history with summaries, the agent loses awareness of what tasks
 * it created — but the tasks are still on disk. The agent just needs to call
 * {@code list_agent_tasks} with its sessionId to recover full state.
 *
 * Cross-session queries are supported (omit sessionId) so agents can also
 * see tasks from previous sessions that may still be pending.
 */
@Service
public class AgentTaskService {

    private static final Logger log = LoggerFactory.getLogger(AgentTaskService.class);

    private final Path baseDir;
    private final ObjectMapper mapper;
    // sessionId -> (taskId -> AgentTask)
    private final ConcurrentHashMap<String, ConcurrentHashMap<Long, AgentTask>> sessionTasks = new ConcurrentHashMap<>();
    private final AtomicLong idCounter = new AtomicLong(0);

    private static final String GLOBAL_SESSION = "_global";

    public AgentTaskService() {
        this.baseDir = Paths.get(System.getProperty("user.home"), ".kompile", "agent-state", "tasks");
        this.mapper = new ObjectMapper()
                .enable(SerializationFeature.INDENT_OUTPUT)
                .setSerializationInclusion(JsonInclude.Include.NON_NULL);
    }

    @PostConstruct
    public void init() {
        try {
            Files.createDirectories(baseDir);
            loadAllSessions();
            int total = sessionTasks.values().stream().mapToInt(Map::size).sum();
            log.info("AgentTaskService initialized with {} tasks across {} sessions from {}",
                    total, sessionTasks.size(), baseDir);
        } catch (IOException e) {
            log.error("Failed to initialize task storage at {}: {}", baseDir, e.getMessage(), e);
        }
    }

    // ── CRUD ─────────────────────────────────────────────────────────────────

    public AgentTask create(String sessionId, String title, String description,
                            String assignee, String priority, List<String> tags,
                            String parentTaskId) {
        String sid = normalizeSession(sessionId);
        long id = idCounter.incrementAndGet();
        AgentTask task = new AgentTask();
        task.setId(id);
        task.setSessionId(sid);
        task.setTitle(title);
        task.setDescription(description);
        task.setStatus("pending");
        task.setAssignee(assignee);
        task.setPriority(priority != null ? priority : "medium");
        task.setTags(tags != null ? new ArrayList<>(tags) : new ArrayList<>());
        task.setParentTaskId(parentTaskId);
        task.setCreatedAt(Instant.now().toString());
        task.setUpdatedAt(task.getCreatedAt());

        sessionTasks.computeIfAbsent(sid, k -> new ConcurrentHashMap<>()).put(id, task);
        persist(sid, task);
        return task;
    }

    public AgentTask get(long id) {
        for (ConcurrentHashMap<Long, AgentTask> map : sessionTasks.values()) {
            AgentTask task = map.get(id);
            if (task != null) return task;
        }
        return null;
    }

    public List<AgentTask> listForSession(String sessionId, String statusFilter,
                                           String assigneeFilter, String tagFilter) {
        String sid = normalizeSession(sessionId);
        ConcurrentHashMap<Long, AgentTask> map = sessionTasks.get(sid);
        if (map == null) return Collections.emptyList();
        return filterTasks(map.values().stream(), statusFilter, assigneeFilter, tagFilter);
    }

    public List<AgentTask> listAll(String statusFilter, String assigneeFilter, String tagFilter) {
        return filterTasks(
                sessionTasks.values().stream().flatMap(m -> m.values().stream()),
                statusFilter, assigneeFilter, tagFilter);
    }

    public AgentTask update(long id, String title, String description, String status,
                            String assignee, String priority, List<String> tags) {
        AgentTask task = get(id);
        if (task == null) return null;
        if (title != null) task.setTitle(title);
        if (description != null) task.setDescription(description);
        if (status != null) task.setStatus(status);
        if (assignee != null) task.setAssignee(assignee);
        if (priority != null) task.setPriority(priority);
        if (tags != null) task.setTags(new ArrayList<>(tags));
        task.setUpdatedAt(Instant.now().toString());
        if ("completed".equalsIgnoreCase(status) && task.getCompletedAt() == null) {
            task.setCompletedAt(Instant.now().toString());
        }
        persist(task.getSessionId(), task);
        return task;
    }

    public boolean delete(long id) {
        for (Map.Entry<String, ConcurrentHashMap<Long, AgentTask>> entry : sessionTasks.entrySet()) {
            AgentTask removed = entry.getValue().remove(id);
            if (removed != null) {
                try {
                    Files.deleteIfExists(taskFile(entry.getKey(), id));
                } catch (IOException e) {
                    log.warn("Failed to delete task file for id {}: {}", id, e.getMessage());
                }
                return true;
            }
        }
        return false;
    }

    public AgentTask complete(long id, String completionNote) {
        AgentTask task = get(id);
        if (task == null) return null;
        task.setStatus("completed");
        task.setCompletedAt(Instant.now().toString());
        task.setUpdatedAt(Instant.now().toString());
        if (completionNote != null) {
            task.setCompletionNote(completionNote);
        }
        persist(task.getSessionId(), task);
        return task;
    }

    public Map<String, Object> summary(String sessionId) {
        Stream<AgentTask> stream;
        if (sessionId != null && !sessionId.isBlank()) {
            ConcurrentHashMap<Long, AgentTask> map = sessionTasks.get(normalizeSession(sessionId));
            stream = map != null ? map.values().stream() : Stream.empty();
        } else {
            stream = sessionTasks.values().stream().flatMap(m -> m.values().stream());
        }
        List<AgentTask> all = stream.collect(Collectors.toList());

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("total", (long) all.size());
        result.put("sessionCount", sessionTasks.size());
        if (sessionId != null) result.put("sessionId", sessionId);

        all.stream()
                .collect(Collectors.groupingBy(
                        t -> t.getStatus() != null ? t.getStatus() : "unknown",
                        Collectors.counting()))
                .forEach(result::put);

        Map<String, Long> bySession = all.stream()
                .collect(Collectors.groupingBy(
                        t -> t.getSessionId() != null ? t.getSessionId() : GLOBAL_SESSION,
                        Collectors.counting()));
        result.put("bySession", bySession);

        return result;
    }

    public List<String> listSessions() {
        return new ArrayList<>(sessionTasks.keySet());
    }

    // ── Persistence ──────────────────────────────────────────────────────────

    private Path sessionDir(String sessionId) {
        return baseDir.resolve(sessionId);
    }

    private Path taskFile(String sessionId, long id) {
        return sessionDir(sessionId).resolve("task-" + id + ".json");
    }

    private void persist(String sessionId, AgentTask task) {
        try {
            Path dir = sessionDir(sessionId);
            Files.createDirectories(dir);
            mapper.writeValue(taskFile(sessionId, task.getId()).toFile(), task);
        } catch (IOException e) {
            log.error("Failed to persist task {}/{}: {}", sessionId, task.getId(), e.getMessage(), e);
        }
    }

    private void loadAllSessions() {
        try (var dirs = Files.list(baseDir)) {
            dirs.filter(Files::isDirectory).forEach(this::loadSession);
        } catch (IOException e) {
            log.debug("No existing task sessions found in {}: {}", baseDir, e.getMessage());
        }
        // Also load any legacy flat files (pre-session migration)
        loadLegacyFlat();
    }

    private void loadSession(Path sessionDir) {
        String sid = sessionDir.getFileName().toString();
        try (var files = Files.list(sessionDir)) {
            files.filter(p -> p.getFileName().toString().endsWith(".json"))
                    .forEach(p -> {
                        try {
                            AgentTask task = mapper.readValue(p.toFile(), AgentTask.class);
                            if (task.getSessionId() == null) task.setSessionId(sid);
                            sessionTasks.computeIfAbsent(sid, k -> new ConcurrentHashMap<>())
                                    .put(task.getId(), task);
                            if (task.getId() >= idCounter.get()) {
                                idCounter.set(task.getId());
                            }
                        } catch (IOException e) {
                            log.warn("Failed to load task from {}: {}", p, e.getMessage());
                        }
                    });
        } catch (IOException e) {
            log.debug("Failed to list tasks in {}: {}", sessionDir, e.getMessage());
        }
    }

    private void loadLegacyFlat() {
        try (var files = Files.list(baseDir)) {
            files.filter(p -> p.getFileName().toString().startsWith("task-") && p.getFileName().toString().endsWith(".json"))
                    .forEach(p -> {
                        try {
                            AgentTask task = mapper.readValue(p.toFile(), AgentTask.class);
                            String sid = task.getSessionId() != null ? task.getSessionId() : GLOBAL_SESSION;
                            task.setSessionId(sid);
                            sessionTasks.computeIfAbsent(sid, k -> new ConcurrentHashMap<>())
                                    .put(task.getId(), task);
                            if (task.getId() >= idCounter.get()) {
                                idCounter.set(task.getId());
                            }
                            // Migrate: persist into session subdir, delete flat file
                            persist(sid, task);
                            Files.deleteIfExists(p);
                        } catch (IOException e) {
                            log.warn("Failed to migrate legacy task {}: {}", p, e.getMessage());
                        }
                    });
        } catch (IOException e) {
            // ignore
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private String normalizeSession(String sessionId) {
        return (sessionId != null && !sessionId.isBlank()) ? sessionId : GLOBAL_SESSION;
    }

    private List<AgentTask> filterTasks(Stream<AgentTask> stream, String statusFilter,
                                         String assigneeFilter, String tagFilter) {
        return stream
                .filter(t -> statusFilter == null || statusFilter.equalsIgnoreCase(t.getStatus()))
                .filter(t -> assigneeFilter == null || assigneeFilter.equalsIgnoreCase(t.getAssignee()))
                .filter(t -> tagFilter == null || (t.getTags() != null && t.getTags().contains(tagFilter)))
                .sorted(Comparator.comparingLong(AgentTask::getId))
                .collect(Collectors.toList());
    }

    // ── Task model ───────────────────────────────────────────────────────────

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class AgentTask {
        private long id;
        private String sessionId;
        private String title;
        private String description;
        private String status; // pending, in_progress, completed, blocked
        private String assignee;
        private String priority; // low, medium, high, critical
        private List<String> tags;
        private String parentTaskId;
        private String completionNote;
        private String createdAt;
        private String updatedAt;
        private String completedAt;

        public long getId() { return id; }
        public void setId(long id) { this.id = id; }
        public String getSessionId() { return sessionId; }
        public void setSessionId(String sessionId) { this.sessionId = sessionId; }
        public String getTitle() { return title; }
        public void setTitle(String title) { this.title = title; }
        public String getDescription() { return description; }
        public void setDescription(String description) { this.description = description; }
        public String getStatus() { return status; }
        public void setStatus(String status) { this.status = status; }
        public String getAssignee() { return assignee; }
        public void setAssignee(String assignee) { this.assignee = assignee; }
        public String getPriority() { return priority; }
        public void setPriority(String priority) { this.priority = priority; }
        public List<String> getTags() { return tags; }
        public void setTags(List<String> tags) { this.tags = tags; }
        public String getParentTaskId() { return parentTaskId; }
        public void setParentTaskId(String parentTaskId) { this.parentTaskId = parentTaskId; }
        public String getCompletionNote() { return completionNote; }
        public void setCompletionNote(String completionNote) { this.completionNote = completionNote; }
        public String getCreatedAt() { return createdAt; }
        public void setCreatedAt(String createdAt) { this.createdAt = createdAt; }
        public String getUpdatedAt() { return updatedAt; }
        public void setUpdatedAt(String updatedAt) { this.updatedAt = updatedAt; }
        public String getCompletedAt() { return completedAt; }
        public void setCompletedAt(String completedAt) { this.completedAt = completedAt; }
    }
}
