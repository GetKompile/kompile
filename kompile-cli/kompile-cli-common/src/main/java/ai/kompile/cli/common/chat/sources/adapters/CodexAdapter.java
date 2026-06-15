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
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

public class CodexAdapter implements ChatSourceAdapter {

    public static final String ID = "codex";

    private static final Pattern ROLLOUT_NAME = Pattern.compile(
            "^rollout-\\d{4}-\\d{2}-\\d{2}T\\d{2}(?:-\\d{2}){2}-(.+)\\.jsonl(?:\\.zst)?$");

    @Override
    public String id() {
        return ID;
    }

    @Override
    public String displayName() {
        return "OpenAI Codex";
    }

    protected Path codexHome() {
        return ChatAdapterSupport.userHome().resolve(".codex");
    }

    protected Path sessionsDir() {
        return codexHome().resolve("sessions");
    }

    protected Path historyFile() {
        return codexHome().resolve("history.jsonl");
    }

    @Override
    public SourceInfo discover() {
        int count = 0;
        boolean hasSomething = false;
        Path sessions = sessionsDir();
        if (Files.isDirectory(sessions)) {
            hasSomething = true;
            try (Stream<Path> stream = Files.walk(sessions)) {
                count += (int) stream.filter(Files::isRegularFile)
                        .filter(p -> {
                            String n = p.getFileName().toString();
                            return n.endsWith(".jsonl") || n.endsWith(".jsonl.zst");
                        }).count();
            } catch (IOException ignore) {
            }
        }
        Path history = historyFile();
        if (Files.exists(history)) {
            hasSomething = true;
            try (BufferedReader reader = Files.newBufferedReader(history, StandardCharsets.UTF_8)) {
                Map<String, Boolean> ids = new LinkedHashMap<>();
                String line;
                while ((line = reader.readLine()) != null) {
                    if (line.isBlank()) continue;
                    try {
                        JsonNode node = ChatAdapterSupport.MAPPER.readTree(line);
                        String sid = node.path("session_id").asText(null);
                        if (sid != null) ids.put(sid, Boolean.TRUE);
                    } catch (Exception ignore) {
                    }
                }
                count += ids.size();
            } catch (IOException ignore) {
            }
        }
        if (!hasSomething) {
            return SourceInfo.unavailable(id(), displayName(), codexHome().toString(), "not found");
        }
        return SourceInfo.available(id(), displayName(), codexHome().toString(), count);
    }

    @Override
    public List<ChatSessionSummary> list() throws IOException {
        Map<String, ChatSessionSummary> out = new LinkedHashMap<>();
        Path sessions = sessionsDir();
        if (Files.isDirectory(sessions)) {
            try (Stream<Path> stream = Files.walk(sessions)) {
                stream.filter(Files::isRegularFile)
                        .filter(p -> {
                            String n = p.getFileName().toString();
                            return n.endsWith(".jsonl") || n.endsWith(".jsonl.zst");
                        })
                        .forEach(path -> {
                            String id = extractIdFromRollout(path.getFileName().toString());
                            if (id == null) id = path.getFileName().toString();
                            int turns = 0;
                            try {
                                turns = parseRollout(path).size();
                            } catch (IOException ignore) {
                            }
                            out.putIfAbsent(id, new ChatSessionSummary(
                                    id, id(), "(codex rollout)", id(),
                                    turns, ChatAdapterSupport.lastModified(path)));
                        });
            }
        }
        Path history = historyFile();
        if (Files.exists(history)) {
            for (Map.Entry<String, List<ChatTurn>> e : readHistoryGrouped(history).entrySet()) {
                out.putIfAbsent(e.getKey(), new ChatSessionSummary(
                        e.getKey(), id(), "(codex history)", id(),
                        e.getValue().size(), ChatAdapterSupport.lastModified(history)));
            }
        }
        List<ChatSessionSummary> list = new ArrayList<>(out.values());
        list.sort((a, b) -> Long.compare(b.lastModifiedMillis(), a.lastModifiedMillis()));
        return list;
    }

    @Override
    public List<ChatTurn> readTurns(String sessionId) throws IOException {
        Optional<Path> rollout = findRollout(sessionId);
        if (rollout.isPresent()) {
            return parseRollout(rollout.get());
        }
        Path history = historyFile();
        if (Files.exists(history)) {
            return readHistoryGrouped(history).getOrDefault(sessionId, Collections.emptyList());
        }
        return Collections.emptyList();
    }

    @Override
    public Optional<Path> resolveWorkingDirectory(String sessionId) throws IOException {
        Optional<Path> rollout = findRollout(sessionId);
        if (rollout.isEmpty()) return Optional.empty();
        try (BufferedReader reader = rolloutReader(rollout.get())) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isBlank()) continue;
                try {
                    JsonNode node = ChatAdapterSupport.MAPPER.readTree(line);
                    String type = node.path("type").asText("");
                    if ("session_meta".equals(type)) {
                        JsonNode cwd = node.path("payload").path("cwd");
                        if (cwd.isTextual()) return Optional.of(Path.of(cwd.asText()));
                    }
                } catch (Exception ignore) {
                }
            }
        }
        return Optional.empty();
    }

    protected Optional<Path> findRollout(String sessionId) throws IOException {
        Path sessions = sessionsDir();
        if (!Files.isDirectory(sessions)) return Optional.empty();
        try (Stream<Path> stream = Files.walk(sessions)) {
            return stream.filter(Files::isRegularFile)
                    .filter(p -> {
                        String name = p.getFileName().toString();
                        if (name.equals(sessionId) || name.equals(sessionId + ".jsonl")
                                || name.equals(sessionId + ".jsonl.zst")) return true;
                        String extracted = extractIdFromRollout(name);
                        return sessionId.equals(extracted);
                    })
                    .findFirst();
        }
    }

    protected static String extractIdFromRollout(String fileName) {
        Matcher m = ROLLOUT_NAME.matcher(fileName);
        return m.matches() ? m.group(1) : null;
    }

    protected static List<ChatTurn> parseRollout(Path file) throws IOException {
        List<ChatTurn> out = new ArrayList<>();
        try (BufferedReader reader = rolloutReader(file)) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isBlank()) continue;
                try {
                    JsonNode node = ChatAdapterSupport.MAPPER.readTree(line);
                    String type = node.path("type").asText("");
                    if (!"response_item".equalsIgnoreCase(type)) continue;
                    JsonNode payload = node.path("payload");
                    String role = ChatAdapterSupport.extractRole(payload);
                    String content = ChatAdapterSupport.extractContent(payload);
                    if (role != null && content != null && !content.isBlank()) {
                        out.add(new ChatTurn(role, content));
                    }
                } catch (Exception ignore) {
                }
            }
        }
        return out;
    }

    private static BufferedReader rolloutReader(Path file) throws IOException {
        String name = file.getFileName().toString().toLowerCase(Locale.ROOT);
        InputStream in = Files.newInputStream(file);
        if (name.endsWith(".zst")) {
            in = openZstdStream(in);
        }
        return new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
    }

    private static InputStream openZstdStream(InputStream raw) throws IOException {
        try {
            Class<?> cls = Class.forName("com.github.luben.zstd.ZstdInputStream");
            return (InputStream) cls.getConstructor(InputStream.class).newInstance(raw);
        } catch (Throwable t) {
            try {
                raw.close();
            } catch (IOException ignore) {
            }
            throw new IOException("zstd-jni unavailable; cannot read .zst rollout: " + t.getMessage(), t);
        }
    }

    private static Map<String, List<ChatTurn>> readHistoryGrouped(Path file) throws IOException {
        Map<String, List<ChatTurn>> out = new LinkedHashMap<>();
        try (BufferedReader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isBlank()) continue;
                try {
                    JsonNode node = ChatAdapterSupport.MAPPER.readTree(line);
                    String sid = node.path("session_id").asText(null);
                    String text = node.path("text").asText(null);
                    if (sid == null || text == null) continue;
                    out.computeIfAbsent(sid, k -> new ArrayList<>()).add(new ChatTurn("user", text));
                } catch (Exception ignore) {
                }
            }
        }
        return out;
    }
}
