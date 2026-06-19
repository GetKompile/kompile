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
import ai.kompile.cli.common.util.JsonUtils;
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

    private static final ObjectMapper MAPPER = JsonUtils.newStandardMapper()
            .enable(SerializationFeature.INDENT_OUTPUT);

    private static final Path SESSIONS_DIR = KompileHome.homeDirectory().toPath().resolve("sessions");
    private static final Path REGISTRY_FILE = SESSIONS_DIR.resolve("registry.json");

    private final List<SessionEntry> entries;

    public SessionRegistry() {
        this.entries = new ArrayList<>();
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
        SessionEntry entry = SessionEntry.builder()
                .kompileSessionId(kompileSessionId)
                .agent(agent)
                .projectDirectory(projectDirectory)
                .launchMode(launchMode)
                .startedAt(Instant.now().toString())
                .status("running")
                .pid(pid)
                .extra(new LinkedHashMap<>())
                .build();
        entries.add(entry);
        save();
        return entry;
    }

    /**
     * Update session status to "exited" and set end time. Saves immediately.
     */
    public void markExited(String kompileSessionId) {
        for (SessionEntry entry : entries) {
            if (kompileSessionId.equals(entry.getKompileSessionId())) {
                entry.setStatus("exited");
                entry.setEndedAt(Instant.now().toString());
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
            if (kompileSessionId.equals(entry.getKompileSessionId())) {
                entry.setConversationId(conversationId);
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
            if (kompileSessionId.equals(entry.getKompileSessionId())) {
                entry.setTitle(title);
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
            if ("running".equals(entry.getStatus()) && !entry.isProcessAlive()) {
                entry.setStatus("exited");
                entry.setEndedAt(Instant.now().toString());
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
                Instant started = Instant.parse(e.getStartedAt());
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
                .filter(e -> "exited".equals(e.getStatus()))
                .filter(e -> (e.getConversationId() != null && !e.getConversationId().isEmpty())
                        || (e.getKompileSessionId() != null && !e.getKompileSessionId().isEmpty()))
                .collect(Collectors.toList());
    }

    /**
     * Filter sessions by agent name.
     */
    public List<SessionEntry> filterByAgent(String agent) {
        return entries.stream()
                .filter(e -> agent.equalsIgnoreCase(e.getAgent()))
                .collect(Collectors.toList());
    }

    /**
     * Filter sessions by project directory.
     */
    public List<SessionEntry> filterByProject(String projectDir) {
        return entries.stream()
                .filter(e -> projectDir.equals(e.getProjectDirectory()))
                .collect(Collectors.toList());
    }

    /**
     * Get a specific session by its kompile session ID.
     */
    public Optional<SessionEntry> get(String kompileSessionId) {
        return entries.stream()
                .filter(e -> kompileSessionId.equals(e.getKompileSessionId()))
                .findFirst();
    }

    /**
     * Get the most recent sessions, limited to count.
     */
    public List<SessionEntry> getRecent(int count) {
        return entries.stream()
                .sorted(Comparator.comparing(SessionEntry::getStartedAt).reversed())
                .limit(count)
                .collect(Collectors.toList());
    }

    public int size() {
        return entries.size();
    }
}
