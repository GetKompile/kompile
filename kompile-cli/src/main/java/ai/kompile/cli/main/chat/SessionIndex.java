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
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * JSON index of all chat sessions (kompile + imported).
 * Stored at ~/.kompile/conversations/index.json
 * 
 * Provides fast lookup and filtering without scanning all transcript files.
 * Automatically syncs with transcript files on load.
 */
public class SessionIndex {

    private static final String INDEX_FILE = "index.json";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final Path indexFile;
    private final Path conversationsDir;
    private Map<String, SessionMetadata> sessions;
    private long lastLoaded;

    public SessionIndex() {
        this.conversationsDir = KompileHome.homeDirectory().toPath().resolve("conversations");
        this.indexFile = conversationsDir.resolve(INDEX_FILE);
        this.sessions = new LinkedHashMap<>();
        this.lastLoaded = 0;
    }

    /**
     * Load the index from disk. Syncs with actual transcript files.
     */
    public void load() {
        if (!Files.exists(indexFile)) {
            // Build index from existing transcripts
            rebuildFromTranscripts();
            return;
        }

        try {
            String content = Files.readString(indexFile, StandardCharsets.UTF_8);
            ObjectNode root = (ObjectNode) MAPPER.readTree(content);
            
            Map<String, SessionMetadata> loaded = new LinkedHashMap<>();
            if (root.has("sessions") && root.get("sessions").isArray()) {
                for (var node : root.get("sessions")) {
                    ObjectNode obj = (ObjectNode) node;
                    SessionMetadata meta = SessionMetadata.fromJson(obj);
                    loaded.put(meta.sessionId, meta);
                }
            }

            // Sync with actual files - remove entries for deleted transcripts
            Set<String> actualFiles = new HashSet<>();
            if (Files.exists(conversationsDir)) {
                try (var stream = Files.list(conversationsDir)) {
                    stream.filter(p -> p.toString().endsWith(".txt"))
                          .forEach(p -> actualFiles.add(p.getFileName().toString().replace(".txt", "")));
                }
            }

            // Keep only sessions that exist on disk
            sessions = loaded.entrySet().stream()
                    .filter(e -> actualFiles.contains(e.getKey()))
                    .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue, (a, b) -> a, LinkedHashMap::new));

            // Add any new transcripts not in index
            for (String fileId : actualFiles) {
                if (!sessions.containsKey(fileId)) {
                    SessionMetadata meta = readTranscriptMetadata(fileId);
                    if (meta != null) {
                        sessions.put(fileId, meta);
                    }
                }
            }

            lastLoaded = System.currentTimeMillis();
            save(); // Persist any changes

        } catch (Exception e) {
            // On error, rebuild from transcripts
            rebuildFromTranscripts();
        }
    }

    /**
     * Rebuild the index by scanning all transcript files.
     */
    private void rebuildFromTranscripts() {
        sessions = new LinkedHashMap<>();
        if (!Files.exists(conversationsDir)) {
            return;
        }

        try (Stream<Path> stream = Files.list(conversationsDir)) {
            stream.filter(p -> p.toString().endsWith(".txt"))
                  .sorted(Comparator.comparingLong((Path p) -> p.toFile().lastModified()).reversed())
                  .forEach(p -> {
                      String id = p.getFileName().toString().replace(".txt", "");
                      SessionMetadata meta = readTranscriptMetadata(id);
                      if (meta != null) {
                          sessions.put(id, meta);
                      }
                  });
        } catch (IOException e) {
            // Ignore
        }

        save();
    }

    /**
     * Read metadata from a transcript file header.
     */
    private SessionMetadata readTranscriptMetadata(String sessionId) {
        Path transcriptFile = conversationsDir.resolve(sessionId + ".txt");
        if (!Files.exists(transcriptFile)) {
            return null;
        }

        try (BufferedReader reader = Files.newBufferedReader(transcriptFile, StandardCharsets.UTF_8)) {
            String line;
            String started = "";
            String agent = "";
            String source = "kompile";
            String title = "";
            int messageCount = 0;

            while ((line = reader.readLine()) != null) {
                if (line.startsWith("──── Conversation:")) {
                    // Extract session ID from header
                } else if (line.startsWith("Started:")) {
                    started = line.substring(8).trim();
                } else if (line.startsWith("Agent:")) {
                    agent = line.substring(6).trim();
                } else if (line.startsWith("Server:")) {
                    String server = line.substring(7).trim();
                    if (server.startsWith("(imported from")) {
                        source = extractSource(server);
                    }
                } else if (line.startsWith("> ")) {
                    title = line.substring(2).trim();
                    messageCount++;
                } else if (line.startsWith("[agent:")) {
                    messageCount++;
                } else if (line.startsWith("[assistant]")) {
                    messageCount++;
                } else if (!line.trim().isEmpty() && !line.startsWith("──") && !line.startsWith("[system]") 
                           && !line.startsWith("[resumed") && !line.startsWith("[tool:") && !line.startsWith("[subagent:")
                           && !line.startsWith("[agentic-step]") && !line.startsWith("[todo:")) {
                    // Count non-empty, non-metadata lines as assistant content
                    if (messageCount > 0) {
                        // Already counting messages
                    }
                }

                // Stop after header section
                if (line.startsWith("──")) {
                    break;
                }
            }

            // Count remaining messages
            while ((line = reader.readLine()) != null) {
                if (line.startsWith("> ") || line.startsWith("[agent:") || line.startsWith("[assistant]")) {
                    messageCount++;
                }
            }

            return new SessionMetadata(
                    sessionId,
                    source,
                    sessionId.startsWith("imported-") ? sessionId.substring(sessionId.indexOf("-") + 1) : sessionId,
                    Instant.now(),
                    messageCount,
                    title,
                    transcriptFile.toFile().lastModified()
            );

        } catch (IOException e) {
            return null;
        }
    }

    private String extractSource(String serverLine) {
        if (serverLine.contains("claude-code")) return "claude-code";
        if (serverLine.contains("opencode")) return "opencode";
        if (serverLine.contains("codex")) return "codex";
        if (serverLine.contains("qwen")) return "qwen";
        return "imported";
    }

    /**
     * Save the index to disk.
     */
    public void save() {
        try {
            Files.createDirectories(conversationsDir);
            
            ObjectNode root = MAPPER.createObjectNode();
            ArrayNode sessionsArray = root.putArray("sessions");
            
            for (SessionMetadata meta : sessions.values()) {
                sessionsArray.add(meta.toJson());
            }

            Files.writeString(indexFile, MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(root), 
                             StandardCharsets.UTF_8);

        } catch (IOException e) {
            // Ignore save errors
        }
    }

    /**
     * Add or update a session in the index.
     */
    public void addSession(SessionMetadata metadata) {
        sessions.put(metadata.sessionId, metadata);
        save();
    }

    /**
     * Get metadata for a specific session.
     */
    public SessionMetadata getSession(String sessionId) {
        if (sessions.isEmpty() && lastLoaded == 0) {
            load();
        }
        return sessions.get(sessionId);
    }

    /**
     * List all sessions, optionally filtered by source.
     */
    public List<SessionMetadata> listSessions(String source) {
        if (sessions.isEmpty() && lastLoaded == 0) {
            load();
        }

        if (source == null || "all".equalsIgnoreCase(source)) {
            return new ArrayList<>(sessions.values());
        }

        return sessions.values().stream()
                .filter(m -> source.equalsIgnoreCase(m.source))
                .collect(Collectors.toList());
    }

    /**
     * Search sessions by title or content.
     */
    public List<SessionMetadata> search(String query) {
        if (sessions.isEmpty() && lastLoaded == 0) {
            load();
        }

        String queryLower = query.toLowerCase();
        return sessions.values().stream()
                .filter(m -> m.title.toLowerCase().contains(queryLower) ||
                            m.sessionId.toLowerCase().contains(queryLower))
                .collect(Collectors.toList());
    }

    /**
     * Get total session count.
     */
    public int getCount() {
        if (sessions.isEmpty() && lastLoaded == 0) {
            load();
        }
        return sessions.size();
    }

    /**
     * Get session count by source.
     */
    public Map<String, Integer> getCountBySource() {
        if (sessions.isEmpty() && lastLoaded == 0) {
            load();
        }

        Map<String, Integer> counts = new HashMap<>();
        for (SessionMetadata meta : sessions.values()) {
            counts.merge(meta.source, 1, Integer::sum);
        }
        return counts;
    }

    /**
     * Session metadata record.
     */
    public static record SessionMetadata(
            String sessionId,
            String source,          // "kompile", "claude-code", "opencode", "codex", "qwen"
            String originalId,      // Original session ID from source (for imported)
            Instant indexedAt,
            int messageCount,
            String title,
            long lastModified
    ) {
        public ObjectNode toJson() {
            ObjectMapper mapper = new ObjectMapper();
            ObjectNode node = mapper.createObjectNode();
            node.put("sessionId", sessionId);
            node.put("source", source);
            node.put("originalId", originalId);
            node.put("indexedAt", indexedAt != null ? indexedAt.toString() : "");
            node.put("messageCount", messageCount);
            node.put("title", title != null ? title : "");
            node.put("lastModified", lastModified);
            return node;
        }

        public static SessionMetadata fromJson(ObjectNode node) {
            return new SessionMetadata(
                    node.path("sessionId").asText(""),
                    node.path("source").asText("kompile"),
                    node.path("originalId").asText(""),
                    node.path("indexedAt").isTextual() ? Instant.parse(node.path("indexedAt").asText("")) : Instant.now(),
                    node.path("messageCount").asInt(0),
                    node.path("title").asText(""),
                    node.path("lastModified").asLong(System.currentTimeMillis())
            );
        }
    }
}
