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

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.*;

/**
 * Persists chat conversations as plain-text transcript files.
 * <p>
 * Each session is stored at {@code ~/.kompile/conversations/<session-id>.txt}
 * with a human-readable format:
 * <pre>
 * ──── Conversation: cli-a1b2c3d4 ────
 * Started: 2025-06-15 14:30:00
 * Server:  http://localhost:8080
 * Agent:   claude
 * RAG:     enabled
 *
 * ──────────────────────────────────
 *
 * > What is kompile?
 *
 * Kompile is a comprehensive AI/ML platform combining CLI tools
 * for model conversion, pipeline building, and RAG app generation.
 *   [3 docs retrieved, 245ms]
 *
 * > /rag off
 *
 * [system] RAG disabled.
 *
 * > How are you?
 *
 * I'm doing well, thank you for asking!
 *
 * </pre>
 * <p>
 * An index file at {@code ~/.kompile/conversations/index.properties} tracks
 * session metadata (id, title, timestamp) for fast listing.
 */
public class ChatHistory {

    private static final DateTimeFormatter TIMESTAMP_FMT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
                    .withZone(ZoneId.systemDefault());

    private static final DateTimeFormatter FILE_TIMESTAMP_FMT =
            DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")
                    .withZone(ZoneId.systemDefault());

    private static final String SEPARATOR = "──────────────────────────────────";

    private final String sessionId;
    private final Path transcriptFile;
    private final Path conversationsDir;
    private PrintWriter writer;

    public ChatHistory(String sessionId) {
        this.sessionId = sessionId;
        this.conversationsDir = KompileHome.homeDirectory().toPath().resolve("conversations");
        this.transcriptFile = conversationsDir.resolve(sessionId + ".txt");
    }

    /**
     * Opens the transcript file for writing. Writes the header if this is a new conversation.
     */
    public void open(String serverUrl, String agentName, boolean ragEnabled) throws IOException {
        Files.createDirectories(conversationsDir);

        boolean isNew = !Files.exists(transcriptFile);

        writer = new PrintWriter(new BufferedWriter(
                new OutputStreamWriter(
                        new FileOutputStream(transcriptFile.toFile(), true),
                        StandardCharsets.UTF_8)), true);

        if (isNew) {
            writer.println("──── Conversation: " + sessionId + " ────");
            writer.println("Started: " + TIMESTAMP_FMT.format(Instant.now()));
            writer.println("Server:  " + serverUrl);
            writer.println("Agent:   " + agentName);
            writer.println("RAG:     " + (ragEnabled ? "enabled" : "disabled"));
            writer.println();
            writer.println(SEPARATOR);
            writer.println();

            // Update the index
            updateIndex(sessionId, serverUrl, agentName);
        } else {
            // Resuming - add a resume marker
            writer.println();
            writer.println("[resumed " + TIMESTAMP_FMT.format(Instant.now()) + "]");
            writer.println();
        }
    }

    /**
     * Logs a user message.
     */
    public void logUserMessage(String message) {
        if (writer != null) {
            writer.println("> " + message);
            writer.println();
        }
    }

    /**
     * Logs an assistant response from inline RAG chat.
     */
    public void logAssistantMessage(String answer, int docsRetrieved, long timeMs) {
        if (writer != null) {
            writer.println(answer);
            if (docsRetrieved > 0) {
                writer.printf("  [%d docs retrieved, %dms]%n", docsRetrieved, timeMs);
            }
            writer.println();
        }
    }

    /**
     * Logs an agent streaming response (from /ask).
     */
    public void logAgentResponse(String agentName, String fullResponse, long durationMs) {
        if (writer != null) {
            writer.println("[agent:" + agentName + "]");
            writer.println(fullResponse);
            if (durationMs > 0) {
                writer.printf("  [completed in %dms]%n", durationMs);
            }
            writer.println();
        }
    }

    /**
     * Logs a system event (slash commands, config changes, etc.).
     */
    public void logSystem(String event) {
        if (writer != null) {
            writer.println("[system] " + event);
            writer.println();
        }
    }

    /**
     * Logs a tool call execution.
     */
    public void logToolCall(String toolName, boolean isError, long durationMs) {
        if (writer != null) {
            String status = isError ? "error" : "ok";
            writer.printf("[tool:%s] %s (%dms)%n", toolName, status, durationMs);
        }
    }

    /**
     * Logs a subagent invocation.
     */
    public void logSubagent(String agentType, String description, long durationMs, boolean isError) {
        if (writer != null) {
            String status = isError ? "error" : "complete";
            writer.printf("[subagent:%s] %s — %s (%dms)%n", agentType, description, status, durationMs);
        }
    }

    /**
     * Logs a todo task event.
     */
    public void logTodoEvent(String action, String taskId, String subject) {
        if (writer != null) {
            writer.printf("[todo:%s] #%s %s%n", action, taskId, subject);
        }
    }

    /**
     * Logs an agentic chat loop step.
     */
    public void logAgenticStep(int step, int maxSteps, int toolCallCount) {
        if (writer != null) {
            writer.printf("[agentic-step] %d/%d (%d tool calls)%n", step, maxSteps, toolCallCount);
        }
    }

    /**
     * Reads the full transcript content for display during resume.
     */
    public String readTranscript() throws IOException {
        if (Files.exists(transcriptFile)) {
            return Files.readString(transcriptFile, StandardCharsets.UTF_8);
        }
        return null;
    }

    /**
     * Extracts the previous user messages from the transcript for server-side replay.
     * Returns list of (role, content) pairs.
     */
    public List<Turn> readTurns() throws IOException {
        List<Turn> turns = new ArrayList<>();
        if (!Files.exists(transcriptFile)) {
            return turns;
        }

        List<String> lines = Files.readAllLines(transcriptFile, StandardCharsets.UTF_8);
        StringBuilder currentContent = new StringBuilder();
        String currentRole = null;

        for (String line : lines) {
            // Skip header lines
            if (line.startsWith("────") || line.startsWith("Started:") ||
                    line.startsWith("Server:") || line.startsWith("Agent:") ||
                    line.startsWith("RAG:") || line.equals(SEPARATOR)) {
                continue;
            }

            // Skip system events and resume markers
            if (line.startsWith("[system]") || line.startsWith("[resumed")) {
                continue;
            }

            if (line.startsWith("> ")) {
                // Flush previous turn
                if (currentRole != null && currentContent.length() > 0) {
                    turns.add(new Turn(currentRole, currentContent.toString().trim()));
                }
                currentRole = "user";
                currentContent.setLength(0);
                currentContent.append(line.substring(2));
            } else if (currentRole != null && currentRole.equals("user") && line.isEmpty()) {
                // End of user message, start assistant
                if (currentContent.length() > 0) {
                    turns.add(new Turn("user", currentContent.toString().trim()));
                    currentContent.setLength(0);
                    currentRole = "assistant";
                }
            } else if (currentRole != null && currentRole.equals("assistant")) {
                if (line.startsWith("  [") && (line.contains("docs retrieved") || line.contains("completed in"))) {
                    // Metadata line - skip it from content
                    continue;
                }
                if (line.startsWith("[agent:")) {
                    // Agent prefix - skip
                    continue;
                }
                if (line.isEmpty() && currentContent.length() > 0) {
                    // End of assistant message
                    turns.add(new Turn("assistant", currentContent.toString().trim()));
                    currentContent.setLength(0);
                    currentRole = null;
                } else {
                    if (currentContent.length() > 0) {
                        currentContent.append("\n");
                    }
                    currentContent.append(line);
                }
            }
        }

        // Flush final turn
        if (currentRole != null && currentContent.length() > 0) {
            turns.add(new Turn(currentRole, currentContent.toString().trim()));
        }

        return turns;
    }

    public void close() {
        if (writer != null) {
            writer.close();
            writer = null;
        }
    }

    /**
     * Lists all saved conversations from the index.
     */
    public static List<ConversationSummary> listConversations() {
        Path dir = KompileHome.homeDirectory().toPath().resolve("conversations");
        List<ConversationSummary> results = new ArrayList<>();

        if (!Files.exists(dir)) {
            return results;
        }

        File[] files = dir.toFile().listFiles((d, name) -> name.endsWith(".txt") && !name.equals("index.properties"));
        if (files == null) return results;

        // Sort by modified time, newest first
        Arrays.sort(files, (a, b) -> Long.compare(b.lastModified(), a.lastModified()));

        for (File file : files) {
            String sid = file.getName().replace(".txt", "");
            String firstLine = "";
            String title = "";
            try (BufferedReader reader = new BufferedReader(new FileReader(file, StandardCharsets.UTF_8))) {
                // Read header to get metadata
                String line;
                String started = "";
                String agent = "";
                while ((line = reader.readLine()) != null) {
                    if (line.startsWith("Started:")) {
                        started = line.substring(8).trim();
                    } else if (line.startsWith("Agent:")) {
                        agent = line.substring(6).trim();
                    } else if (line.startsWith("> ")) {
                        title = line.substring(2).trim();
                        break;
                    }
                }
                results.add(new ConversationSummary(
                        sid,
                        title.length() > 80 ? title.substring(0, 77) + "..." : title,
                        started,
                        agent,
                        file.lastModified()
                ));
            } catch (IOException e) {
                results.add(new ConversationSummary(sid, "(unreadable)", "", "", file.lastModified()));
            }
        }

        return results;
    }

    /**
     * Checks if a conversation transcript exists for the given session ID.
     */
    public static boolean exists(String sessionId) {
        Path dir = KompileHome.homeDirectory().toPath().resolve("conversations");
        return Files.exists(dir.resolve(sessionId + ".txt"));
    }

    private void updateIndex(String sessionId, String serverUrl, String agentName) {
        try {
            Path indexFile = conversationsDir.resolve("index.properties");
            Properties props = new Properties();
            if (Files.exists(indexFile)) {
                try (Reader r = new FileReader(indexFile.toFile(), StandardCharsets.UTF_8)) {
                    props.load(r);
                }
            }
            props.setProperty(sessionId + ".created", TIMESTAMP_FMT.format(Instant.now()));
            props.setProperty(sessionId + ".server", serverUrl != null ? serverUrl : "local");
            props.setProperty(sessionId + ".agent", agentName != null ? agentName : "unknown");
            try (Writer w = new FileWriter(indexFile.toFile(), StandardCharsets.UTF_8)) {
                props.store(w, "Kompile chat conversation index");
            }
        } catch (IOException e) {
            // Best effort
        }
    }

    public Path getTranscriptFile() {
        return transcriptFile;
    }

    public static record Turn(String role, String content) {}

    public static record ConversationSummary(
            String sessionId,
            String title,
            String started,
            String agent,
            long lastModified
    ) {}
}
