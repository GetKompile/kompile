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

package ai.kompile.cli.main.chat;

import ai.kompile.cli.common.KompileHome;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Central registry of all agent sessions launched by the kompile CLI.
 * Stored at {@code ~/.kompile/sessions/registry.json}.
 * <p>
 * Tracks session metadata including project directory, conversation ID,
 * launch mode, agent type, and status. Used by {@code kompile resume-all}
 * to mass-resume previously started sessions.
 */
public class SessionRegistry {

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .enable(SerializationFeature.INDENT_OUTPUT);

    private static final Path SESSIONS_DIR = KompileHome.homeDirectory().toPath().resolve("sessions");
    private static final Path REGISTRY_FILE = SESSIONS_DIR.resolve("registry.json");

    private final List<SessionEntry> entries;

    public SessionRegistry() {
        this.entries = new ArrayList<>();
    }

    // ── Data model ──────────────────────────────────────────────────────────

    /**
     * A single tracked session entry.
     */
    public static class SessionEntry {
        private String kompileSessionId;
        private String conversationId;
        private String agent;
        private String projectDirectory;
        private String launchMode;
        private String startedAt;
        private String endedAt;
        private String status;
        private long pid;
        private String title;
        private Map<String, String> extra;

        public SessionEntry() {
            this.extra = new LinkedHashMap<>();
        }

        public SessionEntry(String kompileSessionId, String conversationId, String agent,
                            String projectDirectory, String launchMode, Instant startedAt, long pid) {
            this.kompileSessionId = kompileSessionId;
            this.conversationId = conversationId;
            this.agent = agent;
            this.projectDirectory = projectDirectory;
            this.launchMode = launchMode;
            this.startedAt = startedAt != null ? startedAt.toString() : Instant.now().toString();
            this.status = "running";
            this.pid = pid;
            this.extra = new LinkedHashMap<>();
        }

        // Getters and setters
        public String getKompileSessionId() { return kompileSessionId; }
        public void setKompileSessionId(String v) { this.kompileSessionId = v; }

        public String getConversationId() { return conversationId; }
        public void setConversationId(String v) { this.conversationId = v; }

        public String getAgent() { return agent; }
        public void setAgent(String v) { this.agent = v; }

        public String getProjectDirectory() { return projectDirectory; }
        public void setProjectDirectory(String v) { this.projectDirectory = v; }

        public String getLaunchMode() { return launchMode; }
        public void setLaunchMode(String v) { this.launchMode = v; }

        public String getStartedAt() { return startedAt; }
        public void setStartedAt(String v) { this.startedAt = v; }

        public String getEndedAt() { return endedAt; }
        public void setEndedAt(String v) { this.endedAt = v; }

        public String getStatus() { return status; }
        public void setStatus(String v) { this.status = v; }

        public long getPid() { return pid; }
        public void setPid(long v) { this.pid = v; }

        public String getTitle() { return title; }
        public void setTitle(String v) { this.title = v; }

        public Map<String, String> getExtra() { return extra; }
        public void setExtra(Map<String, String> v) { this.extra = v != null ? v : new LinkedHashMap<>(); }

        /**
         * Check if the process that ran this session is still alive.
         */
        public boolean isProcessAlive() {
            if (pid <= 0) return false;
            try {
                return ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false);
            } catch (Exception e) {
                return false;
            }
        }

        public ObjectNode toJson() {
            ObjectNode node = MAPPER.createObjectNode();
            node.put("kompileSessionId", kompileSessionId);
            node.put("conversationId", conversationId != null ? conversationId : "");
            node.put("agent", agent);
            node.put("projectDirectory", projectDirectory);
            node.put("launchMode", launchMode);
            node.put("startedAt", startedAt);
            node.put("endedAt", endedAt != null ? endedAt : "");
            node.put("status", status);
            node.put("pid", pid);
            node.put("title", title != null ? title : "");
            if (extra != null && !extra.isEmpty()) {
                ObjectNode extraNode = node.putObject("extra");
                extra.forEach(extraNode::put);
            }
            return node;
        }

        public static SessionEntry fromJson(ObjectNode node) {
            SessionEntry e = new SessionEntry();
            e.kompileSessionId = node.path("kompileSessionId").asText("");
            e.conversationId = node.path("conversationId").asText("");
            e.agent = node.path("agent").asText("");
            e.projectDirectory = node.path("projectDirectory").asText("");
            e.launchMode = node.path("launchMode").asText("");
            e.startedAt = node.path("startedAt").asText("");
            e.endedAt = node.path("endedAt").asText("");
            e.status = node.path("status").asText("unknown");
            e.pid = node.path("pid").asLong(0);
            e.title = node.path("title").asText("");
            e.extra = new LinkedHashMap<>();
            if (node.has("extra") && node.get("extra").isObject()) {
                node.get("extra").fields().forEachRemaining(
                        field -> e.extra.put(field.getKey(), field.getValue().asText("")));
            }
            return e;
        }

        @Override
        public String toString() {
            return String.format("[%s] %s %s @ %s (%s) — %s",
                    kompileSessionId, agent, launchMode, projectDirectory, status,
                    title != null && !title.isEmpty() ? title : "(untitled)");
        }
    }

    // ── Load / Save ─────────────────────────────────────────────────────────

    /**
     * Load the registry from disk. Creates the file if it doesn't exist.
     */
    public static SessionRegistry load() {
        SessionRegistry registry = new SessionRegistry();
        if (!Files.exists(REGISTRY_FILE)) {
            return registry;
        }

        try {
            String content = Files.readString(REGISTRY_FILE, StandardCharsets.UTF_8);
            ObjectNode root = (ObjectNode) MAPPER.readTree(content);
            if (root.has("sessions") && root.get("sessions").isArray()) {
                for (var node : root.get("sessions")) {
                    if (node.isObject()) {
                        registry.entries.add(SessionEntry.fromJson((ObjectNode) node));
                    }
                }
            }
        } catch (Exception e) {
            System.err.println("Warning: Could not load session registry: " + e.getMessage());
        }
        return registry;
    }

    /**
     * Save the registry to disk.
     */
    public void save() {
        try {
            Files.createDirectories(SESSIONS_DIR);
            ObjectNode root = MAPPER.createObjectNode();
            ArrayNode sessionsArray = root.putArray("sessions");
            for (SessionEntry entry : entries) {
                sessionsArray.add(entry.toJson());
            }
            Files.writeString(REGISTRY_FILE, MAPPER.writeValueAsString(root), StandardCharsets.UTF_8);
        } catch (IOException e) {
            System.err.println("Warning: Could not save session registry: " + e.getMessage());
        }
    }

    // ── Mutation ─────────────────────────────────────────────────────────────

    /**
     * Register a new session. Saves immediately.
     */
    public SessionEntry register(String kompileSessionId, String agent, String projectDirectory,
                                 String launchMode, long pid) {
        SessionEntry entry = new SessionEntry(
                kompileSessionId, null, agent, projectDirectory, launchMode, Instant.now(), pid);
        entries.add(entry);
        save();
        return entry;
    }

    /**
     * Update session status to "exited" and set end time. Saves immediately.
     */
    public void markExited(String kompileSessionId) {
        for (SessionEntry entry : entries) {
            if (kompileSessionId.equals(entry.kompileSessionId)) {
                entry.status = "exited";
                entry.endedAt = Instant.now().toString();
                break;
            }
        }
        save();
    }

    /**
     * Update the conversation ID (the agent's native session ID) after harvest.
     */
    public void setConversationId(String kompileSessionId, String conversationId) {
        for (SessionEntry entry : entries) {
            if (kompileSessionId.equals(entry.kompileSessionId)) {
                entry.conversationId = conversationId;
                break;
            }
        }
        save();
    }

    /**
     * Update the title for a session.
     */
    public void setTitle(String kompileSessionId, String title) {
        for (SessionEntry entry : entries) {
            if (kompileSessionId.equals(entry.kompileSessionId)) {
                entry.title = title;
                break;
            }
        }
        save();
    }

    /**
     * Refresh status of all entries — mark dead processes as exited.
     */
    public void refreshStatuses() {
        boolean changed = false;
        for (SessionEntry entry : entries) {
            if ("running".equals(entry.status) && !entry.isProcessAlive()) {
                entry.status = "exited";
                entry.endedAt = Instant.now().toString();
                changed = true;
            }
        }
        if (changed) save();
    }

    /**
     * Remove entries older than the given number of days.
     */
    public int pruneOlderThan(int days) {
        Instant cutoff = Instant.now().minus(java.time.Duration.ofDays(days));
        int before = entries.size();
        entries.removeIf(e -> {
            try {
                Instant started = Instant.parse(e.startedAt);
                return started.isBefore(cutoff);
            } catch (Exception ex) {
                return false;
            }
        });
        int removed = before - entries.size();
        if (removed > 0) save();
        return removed;
    }

    // ── Query ───────────────────────────────────────────────────────────────

    public List<SessionEntry> getAll() {
        return Collections.unmodifiableList(entries);
    }

    /**
     * Get all sessions that are resumable (exited with a conversation ID or kompile session).
     */
    public List<SessionEntry> getResumable() {
        refreshStatuses();
        return entries.stream()
                .filter(e -> "exited".equals(e.status))
                .filter(e -> (e.conversationId != null && !e.conversationId.isEmpty())
                        || (e.kompileSessionId != null && !e.kompileSessionId.isEmpty()))
                .collect(Collectors.toList());
    }

    /**
     * Filter sessions by agent name.
     */
    public List<SessionEntry> filterByAgent(String agent) {
        return entries.stream()
                .filter(e -> agent.equalsIgnoreCase(e.agent))
                .collect(Collectors.toList());
    }

    /**
     * Filter sessions by project directory.
     */
    public List<SessionEntry> filterByProject(String projectDir) {
        return entries.stream()
                .filter(e -> projectDir.equals(e.projectDirectory))
                .collect(Collectors.toList());
    }

    /**
     * Get a specific session by its kompile session ID.
     */
    public Optional<SessionEntry> get(String kompileSessionId) {
        return entries.stream()
                .filter(e -> kompileSessionId.equals(e.kompileSessionId))
                .findFirst();
    }

    /**
     * Get the most recent sessions, limited to count.
     */
    public List<SessionEntry> getRecent(int count) {
        return entries.stream()
                .sorted(Comparator.comparing((SessionEntry e) -> e.startedAt).reversed())
                .limit(count)
                .collect(Collectors.toList());
    }

    public int size() {
        return entries.size();
    }
}
