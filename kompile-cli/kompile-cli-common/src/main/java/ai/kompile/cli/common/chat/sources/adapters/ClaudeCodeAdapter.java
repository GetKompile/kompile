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
import com.fasterxml.jackson.databind.node.ArrayNode;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.SeekableByteChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;

public class ClaudeCodeAdapter implements ChatSourceAdapter {

    public static final String ID = "claude-code";
    private static final String SESSION_INDEX = "sessions-index.json";
    private static final int SUMMARY_SCAN_LINES = 32;
    private static final int SUMMARY_TAIL_BYTES = 128 * 1024;

    @Override
    public String id() {
        return ID;
    }

    @Override
    public String displayName() {
        return "Claude Code";
    }

    protected Path rootDir() {
        return ChatAdapterSupport.userHome().resolve(".claude").resolve("projects");
    }

    protected Path historyFile() {
        Path parent = rootDir().getParent();
        return (parent == null ? rootDir() : parent).resolve("history.jsonl");
    }

    @Override
    public SourceInfo discover() {
        Path dir = rootDir();
        if (!Files.isDirectory(dir)) {
            return SourceInfo.unavailable(id(), displayName(), dir.toString(), "directory missing");
        }
        return SourceInfo.available(id(), displayName(), dir.toString(), countSessions(dir));
    }

    @Override
    public List<ChatSessionSummary> list() throws IOException {
        return list(-1, null);
    }

    @Override
    public List<ChatSessionSummary> list(int limit) throws IOException {
        return list(limit, null);
    }

    @Override
    public List<ChatSessionSummary> list(Path workingDirectory) throws IOException {
        return list(-1, workingDirectory == null
                ? null
                : workingDirectory.toAbsolutePath().normalize());
    }

    private List<ChatSessionSummary> list(int limit, Path workingDirectory) throws IOException {
        Path dir = rootDir();
        if (limit == 0 || !Files.isDirectory(dir)) {
            return Collections.emptyList();
        }

        List<Path> projects = projectDirectories(dir, workingDirectory);
        Map<String, IndexedSession> index = readSessionIndexes(projects);
        HistoryIndex history = readHistoryIndex(workingDirectory);
        List<SessionFile> files = rootSessionFiles(projects);
        files.sort((a, b) -> Long.compare(b.modifiedMillis(), a.modifiedMillis()));

        Map<String, ChatSessionSummary> unique = new LinkedHashMap<>();
        for (SessionFile sessionFile : files) {
            Path path = sessionFile.path();
            String fallbackId = sessionIdFromPath(path);
            IndexedSession indexed = indexedSession(path, SessionMeta.EMPTY, index);
            if (indexed.sidechainKnown() && indexed.sidechain()) {
                continue;
            }

            HistorySession historySession = history.find(indexed.sessionId(), fallbackId);

            // Claude's picker title metadata is appended throughout the transcript. Read a
            // bounded header and tail so recent ai-title/last-prompt records win without
            // reparsing multi-megabyte conversations.
            SessionMeta meta = readMeta(path, SUMMARY_SCAN_LINES);
            if (indexed == IndexedSession.EMPTY) {
                indexed = indexedSession(path, meta, index);
            }
            if (meta.sidechain() || indexed.sidechain()) {
                continue;
            }

            if (history.available() && historySession == null) {
                historySession = history.find(meta.sessionId(), indexed.sessionId(), fallbackId);
                if (historySession == null && meta.queuedPrompt()) {
                    continue;
                }
            }

            String sessionDirectory = firstNonBlank(
                    historySession == null ? null : historySession.projectPath(),
                    indexed.projectPath(),
                    meta.workingDirectory());
            if (workingDirectory != null
                    && !workingDirectoryMatches(sessionDirectory, workingDirectory)) {
                continue;
            }

            ChatSessionSummary summary = toSummary(
                    path, sessionFile.modifiedMillis(), meta, indexed, historySession);
            unique.putIfAbsent(summary.sessionId(), summary);
            if (limit >= 0 && unique.size() >= limit) {
                break;
            }
        }
        return new ArrayList<>(unique.values());
    }

    @Override
    public List<ChatTurn> readTurns(String sessionId) throws IOException {
        Optional<Path> file = findSessionFile(sessionId);
        if (file.isEmpty()) {
            return Collections.emptyList();
        }
        return parseJsonl(file.get());
    }

    @Override
    public Optional<Path> resolveWorkingDirectory(String sessionId) throws IOException {
        Optional<Path> file = findSessionFile(sessionId);
        if (file.isEmpty()) {
            return Optional.empty();
        }

        SessionMeta meta = readMeta(file.get());
        IndexedSession indexed = indexedSession(file.get(), meta, readSessionIndex(rootDir()));
        String directory = firstNonBlank(indexed.projectPath(), meta.workingDirectory());
        if (!directory.isBlank()) {
            return Optional.of(Path.of(directory));
        }
        return Optional.empty();
    }

    @Override
    public String resolveTitle(String sessionId) throws IOException {
        Optional<Path> file = findSessionFile(sessionId);
        if (file.isEmpty()) {
            return sessionId;
        }

        SessionMeta meta = readMeta(file.get());
        IndexedSession indexed = indexedSession(file.get(), meta, readSessionIndex(rootDir()));
        String title = preferredTitle(meta, indexed);
        return title.isBlank() || "(untitled)".equals(title) ? sessionId : title;
    }

    protected Optional<Path> findSessionFile(String sessionId) throws IOException {
        Path dir = rootDir();
        if (!Files.isDirectory(dir)) {
            return Optional.empty();
        }

        String needle = sessionId.endsWith(".jsonl")
                ? sessionId.substring(0, sessionId.length() - 6)
                : sessionId;

        // Native session IDs are transcript basenames. Resolve those first so resuming one UUID
        // never parses every transcript merely to discover that its filename already matched.
        for (Path path : directSessionFiles(dir, needle)) {
            SessionMeta meta = readMeta(path, SUMMARY_SCAN_LINES);
            IndexedSession indexed = indexedSession(
                    path, meta, readSessionIndexes(List.of(path.getParent())));
            if (!meta.sidechain() && !indexed.sidechain() && !hasTranscriptSidechainFlag(path)) {
                return Optional.of(path);
            }
        }

        // Aliases and slugs are not encoded in filenames, so they require metadata inspection.
        Map<String, IndexedSession> index = readSessionIndex(dir);
        for (Path path : listRootSessionFiles(dir)) {
            SessionMeta meta = readMeta(path);
            IndexedSession indexed = indexedSession(path, meta, index);
            if (meta.sidechain() || indexed.sidechain()) {
                continue;
            }
            if (needle.equals(meta.sessionId())
                    || needle.equals(preferredTitle(meta, indexed))
                    || needle.equals(meta.slug())) {
                return Optional.of(path);
            }
        }
        return Optional.empty();
    }

    private static List<Path> directSessionFiles(Path root, String sessionId) {
        if (sessionId == null || sessionId.isBlank()
                || sessionId.contains("/") || sessionId.contains("\\")) {
            return Collections.emptyList();
        }
        try (Stream<Path> projects = Files.list(root)) {
            return projects.filter(Files::isDirectory)
                    .map(project -> project.resolve(sessionId + ".jsonl"))
                    .filter(Files::isRegularFile)
                    .sorted(Comparator.comparingLong(
                            ChatAdapterSupport::lastModified).reversed())
                    .toList();
        } catch (IOException ignore) {
            return Collections.emptyList();
        }
    }

    protected List<Path> listRootSessionFiles(Path root) throws IOException {
        try (Stream<Path> stream = Files.walk(root)) {
            return stream.filter(Files::isRegularFile)
                    .filter(path -> isRootSessionFile(root, path))
                    .toList();
        }
    }

    protected static List<ChatTurn> parseJsonl(Path file) throws IOException {
        List<ChatTurn> out = new ArrayList<>();
        try (BufferedReader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isBlank()) {
                    continue;
                }
                try {
                    JsonNode node = ChatAdapterSupport.MAPPER.readTree(line);
                    String role = ChatAdapterSupport.extractRole(node);
                    String content = ChatAdapterSupport.extractContent(node);
                    if (role != null && content != null && !content.isBlank()) {
                        ArrayNode rawBlocks = extractRawContentBlocks(node);
                        out.add(new ChatTurn(role, content, extractTimestamp(node), rawBlocks));
                    }
                } catch (Exception ignore) {
                }
            }
        }
        return out;
    }

    private static ArrayNode extractRawContentBlocks(JsonNode node) {
        JsonNode message = node.path("message");
        JsonNode content = message.isObject() ? message.path("content") : node.path("content");
        if (content.isArray()) {
            for (JsonNode block : content) {
                String type = block.path("type").asText("");
                if ("tool_use".equals(type) || "tool_result".equals(type)) {
                    return (ArrayNode) content;
                }
            }
        }
        return null;
    }

    private static Instant extractTimestamp(JsonNode node) {
        JsonNode timestamp = node.path("timestamp");
        if (timestamp.isMissingNode() || timestamp.isNull()) {
            timestamp = node.path("message").path("timestamp");
        }
        if (timestamp.isNumber()) {
            long value = timestamp.asLong();
            return value < 100_000_000_000L
                    ? Instant.ofEpochSecond(value)
                    : Instant.ofEpochMilli(value);
        }
        if (timestamp.isTextual()) {
            String raw = timestamp.asText();
            try {
                return Instant.parse(raw);
            } catch (DateTimeParseException ignore) {
                try {
                    long value = Long.parseLong(raw);
                    return value < 100_000_000_000L
                            ? Instant.ofEpochSecond(value)
                            : Instant.ofEpochMilli(value);
                } catch (NumberFormatException ignored) {
                    return null;
                }
            }
        }
        return null;
    }

    private ChatSessionSummary toSummary(
            Path path,
            long fileModifiedMillis,
            SessionMeta meta,
            IndexedSession indexed,
            HistorySession history) {
        String fallbackId = sessionIdFromPath(path);
        String sessionId = firstNonBlank(meta.sessionId(), indexed.sessionId(), fallbackId);
        long modified = Math.max(fileModifiedMillis, indexed.modifiedMillis());
        String workingDirectory = firstNonBlank(
                history == null ? null : history.projectPath(),
                indexed.projectPath(),
                meta.workingDirectory());
        return new ChatSessionSummary(
                sessionId,
                id(),
                preferredTitle(meta, indexed, history),
                id(),
                indexed.messageCount(),
                modified,
                workingDirectory.isBlank() ? null : workingDirectory);
    }

    private SessionMeta readMeta(Path path) {
        return readMeta(path, -1);
    }

    private SessionMeta readMeta(Path path, int maxLines) {
        String sessionId = null;
        String customTitle = null;
        String aiTitle = null;
        String slug = null;
        String agentName = null;
        String firstUserText = null;
        String lastPrompt = null;
        String cwd = null;
        boolean queuedPrompt = false;
        boolean sidechain = false;

        try (BufferedReader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
            String line;
            int linesRead = 0;
            while ((line = reader.readLine()) != null) {
                if (maxLines >= 0 && linesRead++ >= maxLines) {
                    break;
                }
                if (line.isBlank()) {
                    continue;
                }
                try {
                    JsonNode node = ChatAdapterSupport.MAPPER.readTree(line);
                    sidechain = sidechain || node.path("isSidechain").asBoolean(false);
                    if (sessionId == null) {
                        String candidate = node.path("sessionId").asText(null);
                        if (candidate != null && !candidate.isBlank()) {
                            sessionId = candidate;
                        }
                    }
                    if (cwd == null) {
                        String candidate = node.path("cwd").asText(null);
                        if (candidate != null && !candidate.isBlank()) {
                            cwd = candidate;
                        }
                    }

                    String type = node.path("type").asText("");
                    if ("queue-operation".equals(type)
                            && "enqueue".equals(node.path("operation").asText(""))) {
                        queuedPrompt = true;
                    }
                    if (firstUserText == null && "user".equals(type)) {
                        String candidate = ChatAdapterSupport.extractContent(node);
                        if (candidate != null && !candidate.isBlank()
                                && !candidate.startsWith("[tool-result]")) {
                            firstUserText = summarizeTitle(candidate);
                        }
                    }
                    if ("custom-title".equals(type)) {
                        String candidate = node.path("customTitle").asText(null);
                        if (candidate != null && !candidate.isBlank()) {
                            customTitle = candidate;
                        }
                    } else if ("ai-title".equals(type)) {
                        String candidate = node.path("aiTitle").asText(null);
                        if (candidate != null && !candidate.isBlank()) {
                            aiTitle = candidate;
                        }
                    } else if ("last-prompt".equals(type)) {
                        String candidate = node.path("lastPrompt").asText(null);
                        if (candidate != null && !candidate.isBlank()) {
                            lastPrompt = summarizeTitle(candidate);
                        }
                    } else if ("agent-name".equals(type)) {
                        String candidate = node.path("agentName").asText(null);
                        if (candidate != null && !candidate.isBlank()) {
                            agentName = candidate;
                        }
                    }
                    String candidateSlug = node.path("slug").asText(null);
                    if (candidateSlug != null && !candidateSlug.isBlank()) {
                        slug = candidateSlug;
                    }
                } catch (Exception ignore) {
                }
            }
        } catch (IOException ignore) {
        }

        SessionMeta header = new SessionMeta(
                sessionId,
                customTitle,
                aiTitle,
                slug,
                agentName,
                firstUserText,
                lastPrompt,
                cwd,
                queuedPrompt,
                sidechain);
        return maxLines < 0 ? header : mergeMeta(header, readTailMeta(path));
    }

    private SessionMeta readTailMeta(Path path) {
        String customTitle = null;
        String aiTitle = null;
        String slug = null;
        String agentName = null;
        String lastPrompt = null;
        boolean sidechain = false;

        try (SeekableByteChannel channel = Files.newByteChannel(path, StandardOpenOption.READ)) {
            long size = channel.size();
            int length = (int) Math.min(size, SUMMARY_TAIL_BYTES);
            long start = size - length;
            channel.position(start);

            ByteBuffer buffer = ByteBuffer.allocate(length);
            while (buffer.hasRemaining()) {
                int bytesRead = channel.read(buffer);
                if (bytesRead <= 0) {
                    break;
                }
            }
            buffer.flip();
            String tail = StandardCharsets.UTF_8.decode(buffer).toString();
            if (start > 0) {
                int firstNewline = tail.indexOf('\n');
                if (firstNewline < 0) {
                    return SessionMeta.EMPTY;
                }
                tail = tail.substring(firstNewline + 1);
            }

            for (String line : tail.split("\\R")) {
                if (line.isBlank()) {
                    continue;
                }
                try {
                    JsonNode node = ChatAdapterSupport.MAPPER.readTree(line);
                    sidechain = sidechain || node.path("isSidechain").asBoolean(false);
                    String type = node.path("type").asText("");
                    if ("custom-title".equals(type)) {
                        String candidate = node.path("customTitle").asText(null);
                        if (candidate != null && !candidate.isBlank()) {
                            customTitle = candidate;
                        }
                    } else if ("ai-title".equals(type)) {
                        String candidate = node.path("aiTitle").asText(null);
                        if (candidate != null && !candidate.isBlank()) {
                            aiTitle = candidate;
                        }
                    } else if ("last-prompt".equals(type)) {
                        String candidate = node.path("lastPrompt").asText(null);
                        if (candidate != null && !candidate.isBlank()) {
                            lastPrompt = summarizeTitle(candidate);
                        }
                    } else if ("agent-name".equals(type)) {
                        String candidate = node.path("agentName").asText(null);
                        if (candidate != null && !candidate.isBlank()) {
                            agentName = candidate;
                        }
                    }
                    String candidateSlug = node.path("slug").asText(null);
                    if (candidateSlug != null && !candidateSlug.isBlank()) {
                        slug = candidateSlug;
                    }
                } catch (Exception ignore) {
                }
            }
        } catch (IOException ignore) {
        }

        return new SessionMeta(
                null,
                customTitle,
                aiTitle,
                slug,
                agentName,
                null,
                lastPrompt,
                null,
                false,
                sidechain);
    }

    private static SessionMeta mergeMeta(SessionMeta header, SessionMeta tail) {
        return new SessionMeta(
                firstNonBlank(header.sessionId(), tail.sessionId()),
                firstNonBlank(tail.customTitle(), header.customTitle()),
                firstNonBlank(tail.aiTitle(), header.aiTitle()),
                firstNonBlank(tail.slug(), header.slug()),
                firstNonBlank(tail.agentName(), header.agentName()),
                firstNonBlank(header.firstUserText(), tail.firstUserText()),
                firstNonBlank(tail.lastPrompt(), header.lastPrompt()),
                firstNonBlank(header.workingDirectory(), tail.workingDirectory()),
                header.queuedPrompt(),
                header.sidechain() || tail.sidechain());
    }

    private static String preferredTitle(SessionMeta meta, IndexedSession indexed) {
        return preferredTitle(meta, indexed, null);
    }

    private static String preferredTitle(
            SessionMeta meta,
            IndexedSession indexed,
            HistorySession history) {
        String title = firstNonBlank(
                meta.customTitle(),
                meta.aiTitle(),
                indexed.nativeEntry() ? indexed.summary() : null,
                meta.lastPrompt(),
                history == null ? null : history.lastPrompt(),
                indexed.summary(),
                indexed.firstPrompt(),
                meta.slug(),
                meta.agentName(),
                meta.firstUserText());
        return title.isBlank() ? "(untitled)" : title.replaceAll("\\s+", " ").trim();
    }

    private static String summarizeTitle(String content) {
        String normalized = content.replaceAll("\\s+", " ").trim();
        if (normalized.length() <= 120) {
            return normalized;
        }
        return normalized.substring(0, 117) + "...";
    }

    private static boolean hasTranscriptSidechainFlag(Path path) {
        try (BufferedReader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
            String line;
            int linesRead = 0;
            while ((line = reader.readLine()) != null && linesRead++ < 50) {
                if (line.isBlank()) {
                    continue;
                }
                try {
                    JsonNode node = ChatAdapterSupport.MAPPER.readTree(line);
                    JsonNode flag = node.get("isSidechain");
                    if (flag != null && !flag.isNull()) {
                        return flag.asBoolean(false);
                    }
                } catch (Exception ignore) {
                }
            }
        } catch (IOException ignore) {
        }
        return false;
    }

    private static boolean isRootSessionFile(Path root, Path path) {
        if (!path.getFileName().toString().endsWith(".jsonl")) {
            return false;
        }
        Path relative;
        try {
            relative = root.toAbsolutePath().normalize()
                    .relativize(path.toAbsolutePath().normalize());
        } catch (IllegalArgumentException e) {
            return false;
        }
        if (relative.getNameCount() != 2) {
            return false;
        }
        String fileName = path.getFileName().toString();
        return !fileName.startsWith("agent-")
                && !"subagents".equalsIgnoreCase(path.getParent().getFileName().toString());
    }

    private HistoryIndex readHistoryIndex(Path workingDirectory) {
        Path file = historyFile();
        if (!Files.isRegularFile(file)) {
            return HistoryIndex.UNAVAILABLE;
        }

        Map<String, HistorySession> sessions = new HashMap<>();
        try (BufferedReader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isBlank()) {
                    continue;
                }
                try {
                    JsonNode node = ChatAdapterSupport.MAPPER.readTree(line);
                    String sessionId = node.path("sessionId").asText(null);
                    String projectPath = node.path("project").asText(null);
                    if (sessionId == null || sessionId.isBlank()
                            || (workingDirectory != null
                            && !workingDirectoryMatches(projectPath, workingDirectory))) {
                        continue;
                    }

                    HistorySession previous = sessions.get(sessionId);
                    sessions.put(sessionId, new HistorySession(
                            firstNonBlank(
                                    node.path("display").asText(null),
                                    previous == null ? null : previous.lastPrompt()),
                            firstNonBlank(
                                    projectPath,
                                    previous == null ? null : previous.projectPath())));
                } catch (Exception ignore) {
                }
            }
            return new HistoryIndex(true, sessions);
        } catch (IOException ignore) {
            return HistoryIndex.UNAVAILABLE;
        }
    }

    private static String sessionIdFromPath(Path path) {
        String fileName = path.getFileName().toString();
        return fileName.endsWith(".jsonl")
                ? fileName.substring(0, fileName.length() - 6)
                : fileName;
    }

    private Map<String, IndexedSession> readSessionIndex(Path root) {
        return readSessionIndexes(projectDirectories(root, null));
    }

    private Map<String, IndexedSession> readSessionIndexes(List<Path> projects) {
        Map<String, IndexedSession> sessions = new HashMap<>();
        for (Path project : projects) {
            Path indexFile = project.resolve(SESSION_INDEX);
            if (!Files.isRegularFile(indexFile)) {
                continue;
            }
            try {
                JsonNode document = ChatAdapterSupport.MAPPER.readTree(indexFile.toFile());
                JsonNode entries = document.path("entries");
                if (entries.isArray()) {
                    for (JsonNode entry : entries) {
                        addIndexedSession(sessions, parseIndexedSession(entry, true));
                    }
                }
                document.fields().forEachRemaining(field -> {
                    JsonNode value = field.getValue();
                    if (value.isObject() && (value.has("id") || value.has("sessionId"))) {
                        addIndexedSession(sessions, parseIndexedSession(value, false));
                    }
                });
            } catch (Exception ignore) {
            }
        }
        return sessions;
    }

    private static List<Path> projectDirectories(Path root, Path workingDirectory) {
        if (!Files.isDirectory(root)) {
            return Collections.emptyList();
        }
        try (Stream<Path> projects = Files.list(root)) {
            return projects.filter(Files::isDirectory)
                    .filter(project -> workingDirectory == null
                            || projectDirectoryMatches(project, workingDirectory))
                    .toList();
        } catch (IOException ignore) {
            return Collections.emptyList();
        }
    }

    private static boolean projectDirectoryMatches(Path project, Path workingDirectory) {
        String encoded = encodeProjectDirectory(
                workingDirectory.toAbsolutePath().normalize().toString());
        String projectName = project.getFileName().toString();
        return projectName.equals(encoded);
    }

    static String encodeProjectDirectory(String directory) {
        return directory.replace('\\', '/')
                .replace(':', '-')
                .replace('/', '-');
    }

    private static List<SessionFile> rootSessionFiles(List<Path> projects) {
        List<SessionFile> files = new ArrayList<>();
        for (Path project : projects) {
            try (Stream<Path> entries = Files.list(project)) {
                entries.filter(Files::isRegularFile)
                        .filter(path -> {
                            String name = path.getFileName().toString();
                            return name.endsWith(".jsonl") && !name.startsWith("agent-");
                        })
                        .forEach(path -> files.add(new SessionFile(
                                path, ChatAdapterSupport.lastModified(path))));
            } catch (IOException ignore) {
            }
        }
        return files;
    }

    private static boolean workingDirectoryMatches(String sessionDirectory, Path workingDirectory) {
        if (sessionDirectory == null || sessionDirectory.isBlank()) {
            return false;
        }
        String session = sessionDirectory.replace('\\', '/');
        String requested = workingDirectory.toAbsolutePath().normalize().toString()
                .replace('\\', '/');
        return requested.equals(session);
    }

    private static void addIndexedSession(Map<String, IndexedSession> sessions, IndexedSession indexed) {
        if (indexed.sessionId() != null && !indexed.sessionId().isBlank()) {
            sessions.putIfAbsent(indexed.sessionId(), indexed);
        }
        if (indexed.fullPath() != null && !indexed.fullPath().isBlank()) {
            try {
                sessions.putIfAbsent(
                        Path.of(indexed.fullPath()).toAbsolutePath().normalize().toString(), indexed);
            } catch (Exception ignore) {
            }
        }
    }

    private static IndexedSession parseIndexedSession(JsonNode node, boolean nativeEntry) {
        String sessionId = firstNonBlank(
                node.path("sessionId").asText(null),
                node.path("id").asText(null));
        String firstPrompt = node.path("firstPrompt").asText(null);
        String summary = node.path("summary").asText(null);
        String fullPath = node.path("fullPath").asText(null);
        String projectPath = node.path("projectPath").asText(null);
        int messageCount = node.has("messageCount") ? node.path("messageCount").asInt(-1) : -1;
        long modified = parseIndexTimestamp(
                node.has("modified") ? node.get("modified") : node.get("updatedAt"));
        boolean sidechainKnown = node.has("isSidechain")
                && !node.path("isSidechain").isNull();
        boolean sidechain = node.path("isSidechain").asBoolean(false);
        return new IndexedSession(
                sessionId,
                firstPrompt,
                summary,
                fullPath,
                projectPath,
                messageCount,
                modified,
                sidechain,
                sidechainKnown,
                nativeEntry);
    }

    private static long parseIndexTimestamp(JsonNode value) {
        if (value == null || value.isNull() || value.isMissingNode()) {
            return 0L;
        }
        if (value.isNumber()) {
            long timestamp = value.asLong();
            return timestamp < 100_000_000_000L ? timestamp * 1000L : timestamp;
        }
        if (value.isTextual()) {
            try {
                return Instant.parse(value.asText()).toEpochMilli();
            } catch (Exception ignore) {
                try {
                    long timestamp = Long.parseLong(value.asText());
                    return timestamp < 100_000_000_000L ? timestamp * 1000L : timestamp;
                } catch (NumberFormatException ignored) {
                    return 0L;
                }
            }
        }
        return 0L;
    }

    private static IndexedSession indexedSession(
            Path path,
            SessionMeta meta,
            Map<String, IndexedSession> index) {
        IndexedSession indexed = meta == null || meta.sessionId() == null
                ? null
                : index.get(meta.sessionId());
        if (indexed == null) {
            String fileName = path.getFileName().toString();
            String fallbackId = fileName.substring(0, fileName.length() - 6);
            indexed = index.get(fallbackId);
        }
        if (indexed == null) {
            indexed = index.get(path.toAbsolutePath().normalize().toString());
        }
        return indexed == null ? IndexedSession.EMPTY : indexed;
    }

    private int countSessions(Path dir) {
        try {
            return list().size();
        } catch (IOException ignore) {
            return 0;
        }
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return "";
    }

    private record SessionMeta(
            String sessionId,
            String customTitle,
            String aiTitle,
            String slug,
            String agentName,
            String firstUserText,
            String lastPrompt,
            String workingDirectory,
            boolean queuedPrompt,
            boolean sidechain) {
        private static final SessionMeta EMPTY =
                new SessionMeta(
                        null, null, null, null, null, null, null, null, false, false);
    }

    private record IndexedSession(
            String sessionId,
            String firstPrompt,
            String summary,
            String fullPath,
            String projectPath,
            int messageCount,
            long modifiedMillis,
            boolean sidechain,
            boolean sidechainKnown,
            boolean nativeEntry) {
        private static final IndexedSession EMPTY =
                new IndexedSession(
                        null, null, null, null, null, -1, 0L, false, false, false);
    }

    private record HistorySession(String lastPrompt, String projectPath) {
    }

    private record HistoryIndex(boolean available, Map<String, HistorySession> sessions) {
        private static final HistoryIndex UNAVAILABLE =
                new HistoryIndex(false, Collections.emptyMap());

        private HistorySession find(String... sessionIds) {
            for (String sessionId : sessionIds) {
                if (sessionId != null && !sessionId.isBlank()) {
                    HistorySession session = sessions.get(sessionId);
                    if (session != null) {
                        return session;
                    }
                }
            }
            return null;
        }
    }

    private record SessionFile(Path path, long modifiedMillis) {
    }
}
