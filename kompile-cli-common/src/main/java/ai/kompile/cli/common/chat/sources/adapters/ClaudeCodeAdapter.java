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
import java.util.Optional;
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
        String needle = sessionId.endsWith(".jsonl") ? sessionId : sessionId + ".jsonl";
        try (Stream<Path> stream = Files.walk(dir)) {
            return stream
                    .filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().equals(needle)
                            || p.getFileName().toString().equals(sessionId))
                    .findFirst();
        }
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
                        out.add(new ChatTurn(role, content));
                    }
                } catch (Exception ignore) {
                }
            }
        }
        return out;
    }

    private ChatSessionSummary toSummary(Path path) {
        String file = path.getFileName().toString();
        String id = file.endsWith(".jsonl") ? file.substring(0, file.length() - 6) : file;
        int turns = 0;
        try {
            turns = parseJsonl(path).size();
        } catch (IOException ignore) {
        }
        return new ChatSessionSummary(id, id(), "(untitled)", id(),
                turns, ChatAdapterSupport.lastModified(path));
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
