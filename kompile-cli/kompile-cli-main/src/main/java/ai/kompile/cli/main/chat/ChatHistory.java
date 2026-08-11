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
import ai.kompile.cli.common.chat.sources.ChatTurn;
import ai.kompile.cli.common.chat.sources.KompileTranscriptFormat;

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

    // Deferred header fields — stored on open(), written on first actual content
    private boolean opened;
    private String pendingServerUrl;
    private String pendingAgentName;
    private boolean pendingRagEnabled;
    private Path pendingWorkingDirectory;
    private boolean writeFailureReported;
    private final List<String> harvestedSourceIds = new ArrayList<>();

    public ChatHistory(String sessionId) {
        this.sessionId = sessionId;
        this.conversationsDir = KompileHome.homeDirectory().toPath().resolve("conversations");
        this.transcriptFile = conversationsDir.resolve(sessionId + ".txt");
    }

    /**
     * Marks this history as open but does NOT create the file yet.
     * The file is created lazily on the first actual content write,
     * preventing empty stub files from accumulating.
     */
    public synchronized void open(String serverUrl, String agentName, boolean ragEnabled) throws IOException {
        open(serverUrl, agentName, ragEnabled, Path.of(System.getProperty("user.dir")));
    }

    /**
     * Opens a transcript with an explicit project directory for scoped app sync.
     */
    public synchronized void open(String serverUrl, String agentName, boolean ragEnabled,
                     Path workingDirectory) throws IOException {
        this.opened = true;
        this.pendingServerUrl = serverUrl;
        this.pendingAgentName = agentName;
        this.pendingRagEnabled = ragEnabled;
        this.pendingWorkingDirectory = workingDirectory == null
                ? null : workingDirectory.toAbsolutePath().normalize();
    }

    /**
     * Ensures the file and writer exist, writing the header on first call.
     * Called lazily before any content write.
     */
    private void ensureWriter() {
        if (writer != null) return;
        if (!opened) return;
        try {
            Files.createDirectories(conversationsDir);
            boolean isNew = !Files.exists(transcriptFile);

            writer = new PrintWriter(new BufferedWriter(
                    new OutputStreamWriter(
                            new FileOutputStream(transcriptFile.toFile(), true),
                            StandardCharsets.UTF_8)), true);

            if (isNew) {
                writer.println("──── Conversation: " + sessionId + " ────");
                writer.println("Started: " + TIMESTAMP_FMT.format(Instant.now()));
                writer.println("Server:  " + (pendingServerUrl != null ? pendingServerUrl : ""));
                writer.println("Agent:   " + (pendingAgentName != null ? pendingAgentName : ""));
                writer.println("RAG:     " + (pendingRagEnabled ? "enabled" : "disabled"));
                writer.println("CWD:     " + (pendingWorkingDirectory != null ? pendingWorkingDirectory : ""));
                writer.println();
                writer.println(SEPARATOR);
                writer.println();

                updateIndex(sessionId, pendingServerUrl, pendingAgentName);
            } else {
                writer.println();
                writer.println("[resumed " + TIMESTAMP_FMT.format(Instant.now()) + "]");
                if (pendingWorkingDirectory != null) {
                    writer.println("CWD:     " + pendingWorkingDirectory);
                }
                writer.println();
            }
        } catch (IOException e) {
            if (!writeFailureReported) {
                System.err.println("Warning: Could not write chat transcript "
                        + transcriptFile + ": " + e.getMessage());
                writeFailureReported = true;
            }
        }
    }

    /**
     * Logs a user message.
     */
    public synchronized void logUserMessage(String message) {
        ensureWriter();
        if (writer != null) {
            KompileTranscriptFormat.writeTurn(writer, "user", message);
        }
    }

    /**
     * Logs an assistant response from inline RAG chat.
     */
    public synchronized void logAssistantMessage(String answer, int docsRetrieved, long timeMs) {
        ensureWriter();
        if (writer != null) {
            KompileTranscriptFormat.writeTurn(writer, "assistant", answer);
            if (docsRetrieved > 0) {
                writer.printf("  [%d docs retrieved, %dms]%n%n", docsRetrieved, timeMs);
            }
        }
    }

    /**
     * Logs an agent streaming response (from /ask).
     */
    public synchronized void logAgentResponse(String agentName, String fullResponse, long durationMs) {
        ensureWriter();
        if (writer != null) {
            writer.println("[agent:" + agentName + "]");
            KompileTranscriptFormat.writeTurn(writer, "assistant", fullResponse);
            if (durationMs > 0) {
                writer.printf("  [completed in %dms]%n%n", durationMs);
            }
        }
    }

    /**
     * Logs a system event (slash commands, config changes, etc.).
     */
    public synchronized void logSystem(String event) {
        ensureWriter();
        if (writer != null) {
            writer.println("[system] " + event);
            writer.println();
        }
    }

    /**
     * Logs a tool call execution.
     */
    public synchronized void logToolCall(String toolName, boolean isError, long durationMs) {
        ensureWriter();
        if (writer != null) {
            String status = isError ? "error" : "ok";
            writer.printf("[tool:%s] %s (%dms)%n", toolName, status, durationMs);
        }
    }

    /**
     * Logs a subagent invocation.
     */
    public synchronized void logSubagent(String agentType, String description, long durationMs, boolean isError) {
        ensureWriter();
        if (writer != null) {
            String status = isError ? "error" : "complete";
            writer.printf("[subagent:%s] %s — %s (%dms)%n", agentType, description, status, durationMs);
        }
    }

    /**
     * Logs a todo task event.
     */
    public synchronized void logTodoEvent(String action, String taskId, String subject) {
        ensureWriter();
        if (writer != null) {
            writer.printf("[todo:%s] #%s %s%n", action, taskId, subject);
        }
    }

    /**
     * Logs an agentic chat loop step.
     */
    public synchronized void logAgenticStep(int step, int maxSteps, int toolCallCount) {
        ensureWriter();
        if (writer != null) {
            writer.printf("[agentic-step] %d/%d (%d tool calls)%n", step, maxSteps, toolCallCount);
        }
    }

    /**
     * Records that this session harvested content from an external agent session.
     * Written as a header-level metadata line so the resume tool can deduplicate.
     */
    public synchronized void logHarvestedSource(String externalSessionId) {
        if (externalSessionId == null || externalSessionId.isEmpty()) return;
        if (harvestedSourceIds.contains(externalSessionId)) return;
        harvestedSourceIds.add(externalSessionId);
        ensureWriter();
        if (writer != null) {
            writer.println("[harvested:" + externalSessionId + "]");
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
        for (ChatTurn turn : KompileTranscriptFormat.readTurns(transcriptFile)) {
            turns.add(new Turn(turn.role(), turn.content()));
        }
        return turns;
    }

    public synchronized void close() {
        if (writer != null) {
            writer.close();
            writer = null;
        }
    }

    /**
     * Lists all saved conversations from the index.
     */
    public static List<ConversationSummary> listConversations() {
        return listConversations(true);
    }

    /**
     * Lists user-resumable Kompile conversations while rejecting persisted
     * multi-task/subagent transcripts before any transcript file is opened.
     */
    public static List<ConversationSummary> listResumableConversations() {
        return listConversations(false);
    }

    private static List<ConversationSummary> listConversations(boolean includeSubagents) {
        Path dir = KompileHome.homeDirectory().toPath().resolve("conversations");
        List<ConversationSummary> results = new ArrayList<>();

        if (!Files.exists(dir)) {
            return results;
        }

        File[] candidates = dir.toFile().listFiles((d, name) ->
                name.endsWith(".txt")
                        && !name.equals("index.properties")
                        && (includeSubagents
                        || !name.toLowerCase(Locale.ROOT).contains("subagent-")));
        if (candidates == null) {
            return results;
        }

        // Read each mtime once. Calling File.lastModified() inside the sort comparator
        // turns a large transcript directory into an O(n log n) stat storm.
        List<TranscriptFile> files = Arrays.stream(candidates)
                .map(file -> new TranscriptFile(file, file.lastModified()))
                .sorted(Comparator.comparingLong(
                        TranscriptFile::lastModified).reversed())
                .toList();

        for (TranscriptFile transcript : files) {
            File file = transcript.file();
            String fileName = file.getName();
            String sid = fileName.substring(0, fileName.length() - ".txt".length());
            String title = "";
            List<String> harvested = new ArrayList<>();
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
                    } else if (line.startsWith("[harvested:") && line.endsWith("]")) {
                        harvested.add(line.substring(11, line.length() - 1));
                    } else if (line.startsWith("> ")) {
                        String candidate = line.substring(2).trim();
                        // Skip Claude Code internal command messages — not real user content
                        if (candidate.startsWith("<local-command-") || candidate.startsWith("<command-")) {
                            continue;
                        }
                        title = candidate;
                        // For enforcer sessions, the first user message is boilerplate.
                        // Read ahead to find the actual user prompt after "## User Prompt".
                        if (title.startsWith("# Enforcer-Controlled Task")) {
                            String userPrompt = extractEnforcerUserPrompt(reader);
                            if (userPrompt != null && !userPrompt.isEmpty()) {
                                title = userPrompt;
                            }
                        }
                        break;
                    }
                }
                // Skip empty sessions (header-only stubs with no user messages)
                if (title.isEmpty()) {
                    continue;
                }
                results.add(new ConversationSummary(
                        sid,
                        title.length() > 80 ? title.substring(0, 77) + "..." : title,
                        started,
                        agent,
                        transcript.lastModified(),
                        harvested
                ));
            } catch (IOException e) {
                // Skip unreadable files
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

    /**
     * Resolves the real underlying agent session id for a kompile-stored session.
     * <p>
     * Passthrough/managed sessions save their transcript under a synthetic kompile id
     * (e.g. {@code passthrough-1a2b3c4d}) and record the wrapped agent's own session via
     * {@code [harvested:<id>]} markers. Native resume ({@code claude --resume},
     * {@code codex resume}, {@code opencode --session}) must use the harvested id —
     * the synthetic kompile id means nothing to the agent.
     *
     * @param sessionId kompile session id whose transcript to inspect
     * @param agent the agent that ran the session, used to normalize
     *              file-name-derived ids; may be null
     * @return the most recently harvested native session id, or null if the transcript
     *         is missing or never recorded one
     */
    public static String resolveNativeSessionId(String sessionId, String agent) {
        if (sessionId == null || sessionId.isEmpty()) return null;
        Path file = KompileHome.homeDirectory().toPath()
                .resolve("conversations").resolve(sessionId + ".txt");
        return resolveNativeSessionIdFrom(file, agent);
    }

    /**
     * Transcript-file variant of {@link #resolveNativeSessionId(String, String)}.
     * Scans the whole file and keeps the LAST marker: the underlying agent session can
     * change across resumes, and each harvest appends a fresh marker.
     */
    static String resolveNativeSessionIdFrom(Path transcriptFile, String agent) {
        if (transcriptFile == null || !Files.exists(transcriptFile)) return null;
        String lastHarvested = null;
        try (BufferedReader reader = new BufferedReader(
                new FileReader(transcriptFile.toFile(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.startsWith("[harvested:") && line.endsWith("]")) {
                    lastHarvested = line.substring("[harvested:".length(), line.length() - 1);
                }
            }
        } catch (IOException e) {
            return null;
        }
        return normalizeNativeSessionId(lastHarvested, agent);
    }

    /**
     * Normalizes a harvested id to the form the agent's native resume accepts.
     * Harvested ids are usually session FILE names: codex logs are named
     * {@code rollout-<timestamp>-<uuid>.jsonl} while {@code codex resume} takes the
     * bare uuid; the other agents name the file by the session id itself.
     */
    public static String normalizeNativeSessionId(String harvestedId, String agent) {
        if (harvestedId == null || harvestedId.isEmpty()) return null;
        String lowerAgent = agent == null ? "" : agent.toLowerCase();
        if (harvestedId.startsWith("rollout-")
                && (lowerAgent.isEmpty() || lowerAgent.contains("codex"))) {
            // rollout-2025-06-27T10-30-00-<uuid> → the timestamp is 20 chars before the uuid
            String trimmed = harvestedId.substring("rollout-".length());
            return trimmed.length() > 20 ? trimmed.substring(20) : trimmed;
        }
        return harvestedId;
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

    /**
     * Reads ahead in the transcript to find the actual user prompt inside an enforcer message.
     * The enforcer wraps the real prompt under a "## User Prompt" heading.
     */
    private static String extractEnforcerUserPrompt(BufferedReader reader) throws IOException {
        String line;
        boolean foundUserPromptHeader = false;
        while ((line = reader.readLine()) != null) {
            if (line.startsWith("## User Prompt")) {
                foundUserPromptHeader = true;
                continue;
            }
            if (foundUserPromptHeader) {
                String trimmed = line.trim();
                // Skip blank lines right after the header
                if (trimmed.isEmpty()) continue;
                // Stop at the next section header or end marker
                if (trimmed.startsWith("## ") || trimmed.startsWith("Produce the response now")) break;
                return trimmed;
            }
            // Stop scanning if we hit the end of the user message block
            if (line.isEmpty() && !foundUserPromptHeader) {
                // Blank line before finding ## User Prompt — keep scanning (enforcer messages are multi-line)
            }
        }
        return null;
    }

    public Path getTranscriptFile() {
        return transcriptFile;
    }

    private record TranscriptFile(File file, long lastModified) {
    }

    public static record Turn(String role, String content, com.fasterxml.jackson.databind.node.ArrayNode rawContentBlocks) {
        /** Convenience constructor for plain-text turns (no structured blocks). */
        public Turn(String role, String content) {
            this(role, content, null);
        }
    }

    public static record ConversationSummary(
            String sessionId,
            String title,
            String started,
            String agent,
            long lastModified,
            List<String> harvestedSourceIds
    ) {
        public ConversationSummary(String sessionId, String title, String started, String agent, long lastModified) {
            this(sessionId, title, started, agent, lastModified, List.of());
        }
    }
}
