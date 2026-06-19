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
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public class ClaudeCodeAdapter implements ChatSourceAdapter {

    public static final String ID = "claude-code";

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

    @Override
    public SourceInfo discover() {
        Path dir = rootDir();
        if (!Files.isDirectory(dir)) {
            return SourceInfo.unavailable(id(), displayName(), dir.toString(), "directory missing");
        }
        int count = countSessions(dir);
        return SourceInfo.available(id(), displayName(), dir.toString(), count);
    }

    @Override
    public List<ChatSessionSummary> list() throws IOException {
        Path dir = rootDir();
        if (!Files.isDirectory(dir)) return Collections.emptyList();
        List<ChatSessionSummary> out = new ArrayList<>();
        try (Stream<Path> stream = Files.walk(dir)) {
            stream.filter(Files::isRegularFile)
                    .filter(p -> p.toString().endsWith(".jsonl"))
                    .forEach(path -> out.add(toSummary(path)));
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
        try (BufferedReader reader = Files.newBufferedReader(file.get(), StandardCharsets.UTF_8)) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isBlank()) continue;
                try {
                    JsonNode node = ChatAdapterSupport.MAPPER.readTree(line);
                    JsonNode cwd = node.path("cwd");
                    if (cwd.isTextual()) return Optional.of(Path.of(cwd.asText()));
                } catch (Exception ignore) {
                }
            }
        }
        return Optional.empty();
    }

    protected Optional<Path> findSessionFile(String sessionId) throws IOException {
        Path dir = rootDir();
        if (!Files.isDirectory(dir)) return Optional.empty();
        String needle = sessionId.endsWith(".jsonl")
                ? sessionId.substring(0, sessionId.length() - 6) : sessionId;
        List<Path> files;
        try (Stream<Path> stream = Files.walk(dir)) {
            files = stream.filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().endsWith(".jsonl"))
                    .collect(Collectors.toList());
        }
        // Fast path: the on-disk file name is the session's slug or canonical UUID.
        for (Path p : files) {
            String fname = p.getFileName().toString();
            String base = fname.substring(0, fname.length() - 6);
            if (base.equals(needle) || fname.equals(sessionId)) {
                return Optional.of(p);
            }
        }
        // Fallback: a session is also addressable by the canonical sessionId, custom title or slug
        // carried inside the file, which may differ from the on-disk name.
        for (Path p : files) {
            SessionMeta meta = readMeta(p);
            if (needle.equals(meta.sessionId()) || needle.equals(meta.title()) || needle.equals(meta.slug())) {
                return Optional.of(p);
            }
        }
        return Optional.empty();
    }

    protected static List<ChatTurn> parseJsonl(Path file) throws IOException {
        List<ChatTurn> out = new ArrayList<>();
        try (BufferedReader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isBlank()) continue;
                try {
                    JsonNode node = ChatAdapterSupport.MAPPER.readTree(line);
                    String role = ChatAdapterSupport.extractRole(node);
                    String content = ChatAdapterSupport.extractContent(node);
                    if (role != null && content != null && !content.isBlank()) {
                        ArrayNode rawBlocks = extractRawContentBlocks(node);
                        out.add(new ChatTurn(role, content, null, rawBlocks));
                    }
                } catch (Exception ignore) {
                }
            }
        }
        return out;
    }

    private static ArrayNode extractRawContentBlocks(JsonNode node) {
        JsonNode msg = node.path("message");
        JsonNode contentNode = msg.isObject() ? msg.path("content") : node.path("content");
        if (contentNode.isArray()) {
            boolean hasStructured = false;
            for (JsonNode block : contentNode) {
                String type = block.path("type").asText("");
                if ("tool_use".equals(type) || "tool_result".equals(type)) {
                    hasStructured = true;
                    break;
                }
            }
            if (hasStructured) {
                return (ArrayNode) contentNode;
            }
        }
        return null;
    }

    private ChatSessionSummary toSummary(Path path) {
        SessionMeta meta = readMeta(path);
        String fname = path.getFileName().toString();
        String fallbackId = fname.endsWith(".jsonl") ? fname.substring(0, fname.length() - 6) : fname;
        // The canonical session id lives inside the file; the on-disk name may be a human-readable slug.
        String id = meta.sessionId() != null ? meta.sessionId() : fallbackId;
        int turns = 0;
        try {
            turns = parseJsonl(path).size();
        } catch (IOException ignore) {
        }
        return new ChatSessionSummary(id, id(), meta.title(), id(),
                turns, ChatAdapterSupport.lastModified(path), meta.workingDirectory());
    }

    /** Reads the canonical session id and display title (custom-title &gt; slug &gt; agent-name) from a file. */
    private SessionMeta readMeta(Path path) {
        String sessionId = null;
        String customTitle = null;
        String slug = null;
        String agentName = null;
        String cwd = null;
        try (BufferedReader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isBlank()) continue;
                try {
                    JsonNode node = ChatAdapterSupport.MAPPER.readTree(line);
                    if (sessionId == null) {
                        String sid = node.path("sessionId").asText(null);
                        if (sid != null && !sid.isBlank()) sessionId = sid;
                    }
                    if (cwd == null) {
                        String c = node.path("cwd").asText(null);
                        if (c != null && !c.isBlank()) cwd = c;
                    }
                    String type = node.path("type").asText("");
                    if ("custom-title".equals(type)) {
                        String ct = node.path("customTitle").asText(null);
                        if (ct != null && !ct.isBlank()) customTitle = ct;
                    } else if ("agent-name".equals(type)) {
                        String an = node.path("agentName").asText(null);
                        if (an != null && !an.isBlank()) agentName = an;
                    }
                    String s = node.path("slug").asText(null);
                    if (s != null && !s.isBlank()) slug = s;
                } catch (Exception ignore) {
                }
            }
        } catch (IOException ignore) {
        }
        String title = customTitle != null ? customTitle
                : slug != null ? slug
                : agentName != null ? agentName
                : "(untitled)";
        return new SessionMeta(sessionId, title, slug, cwd);
    }

    private record SessionMeta(String sessionId, String title, String slug, String workingDirectory) {
    }

    private int countSessions(Path dir) {
        int count = 0;
        try (Stream<Path> stream = Files.walk(dir)) {
            count = (int) stream.filter(Files::isRegularFile)
                    .filter(p -> p.toString().endsWith(".jsonl"))
                    .count();
        } catch (IOException ignore) {
        }
        return count;
    }
}
