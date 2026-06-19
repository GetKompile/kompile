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
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

public class AgyAdapter implements ChatSourceAdapter {

    public static final String ID = "agy";

    private static final Pattern SESSION_NAME = Pattern.compile(
            "^session-\\d{4}-\\d{2}-\\d{2}T\\d{2}-\\d{2}-(.+)\\.(json|jsonl)$");

    @Override
    public String id() {
        return ID;
    }

    @Override
    public String displayName() {
        return "Antigravity CLI";
    }

    protected Path rootDir() {
        return ChatAdapterSupport.userHome().resolve(".agy").resolve("tmp");
    }

    @Override
    public SourceInfo discover() {
        Path root = rootDir();
        if (!Files.isDirectory(root)) {
            return SourceInfo.unavailable(id(), displayName(), root.toString(), "directory missing");
        }
        int count = countChatFiles(root);
        return SourceInfo.available(id(), displayName(), root.toString(), count);
    }

    @Override
    public List<ChatSessionSummary> list() throws IOException {
        Path root = rootDir();
        if (!Files.isDirectory(root)) return Collections.emptyList();
        List<ChatSessionSummary> out = new ArrayList<>();
        try (Stream<Path> stream = Files.walk(root)) {
            stream.filter(Files::isRegularFile)
                    .filter(AgyAdapter::isChatFile)
                    .forEach(path -> {
                        String id = extractId(path.getFileName().toString());
                        if (id == null) id = path.getFileName().toString();
                        int turns = 0;
                        try {
                            turns = readFile(path).size();
                        } catch (IOException ignore) {
                        }
                        out.add(new ChatSessionSummary(id, id(),
                                "(agy)", id(), turns, ChatAdapterSupport.lastModified(path)));
                    });
        }
        out.sort((a, b) -> Long.compare(b.lastModifiedMillis(), a.lastModifiedMillis()));
        return out;
    }

    @Override
    public List<ChatTurn> readTurns(String sessionId) throws IOException {
        Optional<Path> match = findFile(sessionId);
        if (match.isEmpty()) return Collections.emptyList();
        return readFile(match.get());
    }

    @Override
    public Optional<Path> resolveWorkingDirectory(String sessionId) throws IOException {
        Optional<Path> match = findFile(sessionId);
        if (match.isEmpty()) return Optional.empty();
        Path file = match.get();
        if (file.getFileName().toString().endsWith(".jsonl")) return Optional.empty();
        try {
            JsonNode root = ChatAdapterSupport.MAPPER.readTree(file.toFile());
            String dir = root.path("workingDirectory").asText("");
            if (!dir.isBlank()) return Optional.of(Path.of(dir).toAbsolutePath().normalize());
        } catch (Exception ignore) {
        }
        return Optional.empty();
    }

    protected Optional<Path> findFile(String sessionId) throws IOException {
        Path root = rootDir();
        if (!Files.isDirectory(root)) return Optional.empty();
        try (Stream<Path> stream = Files.walk(root)) {
            return stream.filter(Files::isRegularFile)
                    .filter(AgyAdapter::isChatFile)
                    .filter(p -> matchesSession(p, sessionId))
                    .findFirst();
        }
    }

    private static boolean matchesSession(Path p, String sessionId) {
        String name = p.getFileName().toString();
        if (name.equals(sessionId) || name.equals(sessionId + ".json")
                || name.equals(sessionId + ".jsonl")) return true;
        String extracted = extractId(name);
        if (sessionId.equals(extracted)) return true;
        if (extracted != null && (sessionId.startsWith(extracted) || extracted.startsWith(sessionId))) {
            return true;
        }
        return matchesStoredSessionId(p, sessionId);
    }

    private static boolean matchesStoredSessionId(Path file, String sessionId) {
        String name = file.getFileName().toString();
        try {
            if (name.endsWith(".jsonl")) {
                try (BufferedReader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        if (line.isBlank()) continue;
                        JsonNode node = ChatAdapterSupport.MAPPER.readTree(line);
                        String stored = node.path("sessionId").asText("");
                        if (!stored.isEmpty() && idsMatch(stored, sessionId)) return true;
                        if (node.path("type").asText("").equalsIgnoreCase("session_meta")) break;
                    }
                }
                return false;
            }
            JsonNode root = ChatAdapterSupport.MAPPER.readTree(file.toFile());
            String stored = root.path("sessionId").asText("");
            return !stored.isEmpty() && idsMatch(stored, sessionId);
        } catch (Exception e) {
            return false;
        }
    }

    private static boolean idsMatch(String stored, String requested) {
        return stored.equals(requested) || stored.startsWith(requested) || requested.startsWith(stored);
    }

    private static List<ChatTurn> readFile(Path file) throws IOException {
        String name = file.getFileName().toString();
        if (name.endsWith(".jsonl")) return readJsonl(file);
        return readJson(file);
    }

    private static List<ChatTurn> readJson(Path file) throws IOException {
        JsonNode root = ChatAdapterSupport.MAPPER.readTree(file.toFile());
        JsonNode messages = root.isArray() ? root : root.path("messages");
        List<ChatTurn> out = new ArrayList<>();
        if (messages.isArray()) {
            for (JsonNode msg : messages) {
                String role = ChatAdapterSupport.extractRole(msg);
                String content = ChatAdapterSupport.extractContent(msg);
                if (role != null && content != null && !content.isBlank()) {
                    out.add(new ChatTurn(role, content));
                }
            }
        }
        return out;
    }

    private static List<ChatTurn> readJsonl(Path file) throws IOException {
        List<ChatTurn> out = new ArrayList<>();
        try (BufferedReader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isBlank()) continue;
                try {
                    JsonNode node = ChatAdapterSupport.MAPPER.readTree(line);
                    if (node.path("type").asText("").equalsIgnoreCase("session_meta")) continue;
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

    private static boolean isChatFile(Path p) {
        String parent = p.getParent() == null ? "" : p.getParent().getFileName().toString();
        String name = p.getFileName().toString();
        if (!"chats".equals(parent)) return false;
        return name.endsWith(".json") || name.endsWith(".jsonl");
    }

    private static String extractId(String fileName) {
        Matcher m = SESSION_NAME.matcher(fileName);
        return m.matches() ? m.group(1) : null;
    }

    private static int countChatFiles(Path root) {
        try (Stream<Path> stream = Files.walk(root)) {
            return (int) stream.filter(Files::isRegularFile).filter(AgyAdapter::isChatFile).count();
        } catch (IOException ignore) {
            return 0;
        }
    }
}
