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

package ai.kompile.cli.common.chat.sources.adapters;

import ai.kompile.cli.common.chat.sources.ChatAdapterSupport;
import ai.kompile.cli.common.chat.sources.ChatSessionSummary;
import ai.kompile.cli.common.chat.sources.ChatSourceAdapter;
import ai.kompile.cli.common.chat.sources.ChatTurn;
import ai.kompile.cli.common.chat.sources.SourceInfo;
import com.fasterxml.jackson.databind.JsonNode;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Adapter for the Pi coding agent ({@code @earendil-works/pi-coding-agent}).
 * <p>
 * Pi stores JSONL session files at {@code ~/.pi/agent/sessions/--encoded-path--/timestamp_uuid.jsonl}.
 * <p>
 * JSONL format:
 * <ul>
 *   <li>Line 1: session header — {@code {"type":"session","version":3,"id":"...","timestamp":"...","cwd":"..."}}</li>
 *   <li>Subsequent lines: entries with {@code type}, {@code id}, {@code parentId}, {@code timestamp} fields</li>
 *   <li>Message entry types: {@code user}, {@code assistant}, {@code toolResult}, {@code bashExecution},
 *       {@code branchSummary}, {@code compactionSummary}, {@code custom}, {@code leaf}, {@code label}</li>
 * </ul>
 * <p>
 * Project directory encoding: wraps the path with double dashes and replaces separators,
 * e.g. {@code /home/user/project} → {@code --home-user-project--}
 * <p>
 * Resume: {@code pi --continue} (most recent) or {@code pi --session <id>}
 */
public class PiAdapter implements ChatSourceAdapter {

    public static final String ID = "pi";

    /** Entry types that are not conversation content — tool results, internal bookkeeping, etc. */
    private static final Set<String> SUPPRESSED_ENTRY_TYPES = Set.of(
            "toolresult", "bashexecution", "leaf", "label",
            "branchsummary", "compactionsummary"
    );

    @Override
    public String id() {
        return ID;
    }

    @Override
    public String displayName() {
        return "Pi";
    }

    private static Path sessionsRoot() {
        return ChatAdapterSupport.userHome().resolve(".pi").resolve("agent").resolve("sessions");
    }

    @Override
    public SourceInfo discover() {
        Path dir = sessionsRoot();
        if (!Files.isDirectory(dir)) {
            return SourceInfo.unavailable(id(), displayName(), dir.toString(), "directory missing");
        }
        int count = countSessions(dir);
        return SourceInfo.available(id(), displayName(), dir.toString(), count);
    }

    @Override
    public List<ChatSessionSummary> list() throws IOException {
        Path dir = sessionsRoot();
        if (!Files.isDirectory(dir)) return Collections.emptyList();
        List<ChatSessionSummary> out = new ArrayList<>();
        try (Stream<Path> stream = Files.walk(dir, 2)) {
            stream.filter(Files::isRegularFile)
                    .filter(p -> p.toString().endsWith(".jsonl"))
                    .forEach(path -> {
                        try {
                            ChatSessionSummary summary = toSummary(path);
                            if (summary != null) out.add(summary);
                        } catch (Exception ignore) {}
                    });
        }
        out.sort((a, b) -> Long.compare(b.lastModifiedMillis(), a.lastModifiedMillis()));
        return out;
    }

    @Override
    public List<ChatTurn> readTurns(String sessionId) throws IOException {
        Optional<Path> file = findSessionFile(sessionId);
        if (file.isEmpty()) return Collections.emptyList();
        return parseJsonl(file.get());
    }

    @Override
    public Optional<Path> resolveWorkingDirectory(String sessionId) throws IOException {
        Optional<Path> file = findSessionFile(sessionId);
        if (file.isEmpty()) return Optional.empty();
        return readCwdFromHeader(file.get());
    }

    @Override
    public String resolveTitle(String sessionId) throws IOException {
        Optional<Path> file = findSessionFile(sessionId);
        if (file.isEmpty()) return sessionId;
        List<ChatTurn> turns = parseJsonl(file.get());
        for (ChatTurn turn : turns) {
            if ("user".equals(turn.role()) && turn.content() != null && !turn.content().isBlank()) {
                String title = turn.content().trim();
                if (title.length() > 80) title = title.substring(0, 77) + "...";
                return title;
            }
        }
        return sessionId;
    }

    // ─── Parsing ───────────────────────────────────────────────────────

    /**
     * Parse a Pi JSONL session file into conversation turns.
     * Skips the session header (line 1) and filters out tool/internal entries.
     */
    public static List<ChatTurn> parseJsonl(Path file) throws IOException {
        List<ChatTurn> out = new ArrayList<>();
        try (BufferedReader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            String line;
            boolean firstLine = true;
            while ((line = reader.readLine()) != null) {
                if (line.isBlank()) continue;
                // Skip session header (first line: type=session)
                if (firstLine) {
                    firstLine = false;
                    continue;
                }
                try {
                    JsonNode node = ChatAdapterSupport.MAPPER.readTree(line);
                    String entryType = node.path("type").asText("");

                    // Skip non-content entry types
                    if (SUPPRESSED_ENTRY_TYPES.contains(entryType.toLowerCase(Locale.ROOT))) {
                        continue;
                    }

                    // Pi stores messages inside a "message" field on the entry
                    JsonNode message = node.path("message");
                    if (message.isMissingNode() || message.isNull()) {
                        // Some entry types (leaf, label) have no message — already filtered above
                        continue;
                    }

                    String role = extractPiRole(message);
                    if (role == null || "tool".equals(role)) continue;

                    String content = extractPiContent(message);
                    if (content != null && !content.isBlank()) {
                        out.add(new ChatTurn(role, content));
                    }
                } catch (Exception ignore) {
                }
            }
        }
        return out;
    }

    /**
     * Extract role from a Pi message. Pi uses roles: user, assistant, toolResult,
     * bashExecution, branchSummary, compactionSummary, custom.
     */
    private static String extractPiRole(JsonNode message) {
        String role = message.path("role").asText(null);
        if (role == null) return null;
        String lower = role.toLowerCase(Locale.ROOT);
        if (lower.equals("user")) return "user";
        if (lower.equals("assistant")) return "assistant";
        // Map internal roles to null (skip) or to a normalized form
        if (lower.equals("toolresult") || lower.equals("bashexecution")) return "tool";
        if (lower.equals("custom")) return "user"; // custom messages are user-injected
        // branchSummary/compactionSummary are system-level, skip
        return null;
    }

    /**
     * Extract text content from a Pi message. Pi uses standard content block arrays
     * with {@code {"type":"text","text":"..."}} entries. Also supports plain string content.
     */
    private static String extractPiContent(JsonNode message) {
        // Try standard extractContent first — handles content arrays and plain strings
        String fromAdapter = ChatAdapterSupport.extractContent(message);
        if (fromAdapter != null && !fromAdapter.isBlank()) return fromAdapter;

        // Pi also stores text directly in a "text" field on some message types
        JsonNode text = message.path("text");
        if (text.isTextual() && !text.asText().isBlank()) return text.asText();

        // For bashExecution messages, the content is in "command" + "output"
        // (already filtered by role, but just in case)
        JsonNode command = message.path("command");
        if (command.isTextual()) return null; // bashExecution — skip

        return null;
    }

    // ─── Session discovery ─────────────────────────────────────────────

    /**
     * Find a session file by session ID. Searches all project directories.
     * Pi session filenames contain the session ID: {@code timestamp_sessionId.jsonl}
     */
    private Optional<Path> findSessionFile(String sessionId) throws IOException {
        Path dir = sessionsRoot();
        if (!Files.isDirectory(dir)) return Optional.empty();
        try (Stream<Path> stream = Files.walk(dir, 2)) {
            return stream
                    .filter(Files::isRegularFile)
                    .filter(p -> p.toString().endsWith(".jsonl"))
                    .filter(p -> {
                        String name = p.getFileName().toString();
                        // Filename format: timestamp_sessionId.jsonl
                        return name.contains(sessionId)
                                || matchesSessionHeader(p, sessionId);
                    })
                    .findFirst();
        }
    }

    /**
     * Check if a session file's header contains the given session ID.
     */
    private static boolean matchesSessionHeader(Path file, String sessionId) {
        try (BufferedReader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            String firstLine = reader.readLine();
            if (firstLine == null || firstLine.isBlank()) return false;
            JsonNode header = ChatAdapterSupport.MAPPER.readTree(firstLine);
            return sessionId.equals(header.path("id").asText(""));
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Read the working directory from the session header.
     */
    private static Optional<Path> readCwdFromHeader(Path file) {
        try (BufferedReader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            String firstLine = reader.readLine();
            if (firstLine == null || firstLine.isBlank()) return Optional.empty();
            JsonNode header = ChatAdapterSupport.MAPPER.readTree(firstLine);
            String cwd = header.path("cwd").asText(null);
            if (cwd != null && !cwd.isBlank()) return Optional.of(Path.of(cwd));
        } catch (Exception ignore) {
        }
        return Optional.empty();
    }

    /**
     * Encode a working directory path as a Pi project directory name.
     * Pi uses double-dash wrapping: {@code /home/user/project} → {@code --home-user-project--}
     */
    static String encodeProjectDir(String cwd) {
        String stripped = cwd.replaceAll("^[/\\\\]", "");
        String encoded = stripped.replaceAll("[/\\\\:]", "-");
        return "--" + encoded + "--";
    }

    /**
     * Find Pi project directories that match the given working directory.
     */
    static List<Path> findMatchingProjectDirs(Path sessionsRoot, String cwd) {
        String expected = encodeProjectDir(cwd);
        List<Path> matches = new ArrayList<>();
        try (Stream<Path> dirs = Files.list(sessionsRoot)) {
            dirs.filter(Files::isDirectory)
                    .filter(d -> d.getFileName().toString().equals(expected))
                    .forEach(matches::add);
        } catch (IOException ignore) {
        }
        return matches;
    }

    private ChatSessionSummary toSummary(Path path) {
        try (BufferedReader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
            String firstLine = reader.readLine();
            if (firstLine == null || firstLine.isBlank()) return null;
            JsonNode header = ChatAdapterSupport.MAPPER.readTree(firstLine);
            if (!"session".equals(header.path("type").asText(""))) return null;
            String sessionId = header.path("id").asText("");
            if (sessionId.isBlank()) return null;
            long lastMod = ChatAdapterSupport.lastModified(path);
            return new ChatSessionSummary(sessionId, id(), "(untitled)", id(),
                    0, lastMod);
        } catch (Exception e) {
            return null;
        }
    }

    private int countSessions(Path dir) {
        int count = 0;
        try (Stream<Path> stream = Files.walk(dir, 2)) {
            count = (int) stream.filter(Files::isRegularFile)
                    .filter(p -> p.toString().endsWith(".jsonl"))
                    .count();
        } catch (IOException ignore) {
        }
        return count;
    }
}
