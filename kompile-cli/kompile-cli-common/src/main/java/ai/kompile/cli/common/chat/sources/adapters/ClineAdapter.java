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

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;

/**
 * Cline (saoudrizwan.claude-dev) and the Roo Code fork (RooVeterinaryInc.roo-cline)
 * store each task under a per-task directory inside VSCode's globalStorage:
 * <pre>
 *   {configRoot}/Code/User/globalStorage/{extensionId}/tasks/<taskId>/
 *     api_conversation_history.json  (authoritative chat log)
 *     ui_messages.json               (UI-rendered messages)
 *     task_metadata.json             (optional title, workspace)
 * </pre>
 * Messages use the OpenAI-compatible {@code {role, content}} shape where content
 * can be a string or an array of content blocks.
 */
public class ClineAdapter implements ChatSourceAdapter {

    public static final String ID = "cline";

    private static final String[] EXTENSION_IDS = {
            "saoudrizwan.claude-dev",
            "RooVeterinaryInc.roo-cline"
    };

    private static final String[] CODE_VARIANTS = {
            "Code",
            "Code - Insiders",
            "Cursor",
            "VSCodium"
    };

    @Override
    public String id() {
        return ID;
    }

    @Override
    public String displayName() {
        return "Cline";
    }

    protected List<Path> taskRoots() {
        Set<Path> out = new LinkedHashSet<>();
        Path config = ChatAdapterSupport.userConfigDir();
        for (String variant : CODE_VARIANTS) {
            Path globalStorage = config.resolve(variant).resolve("User").resolve("globalStorage");
            if (!Files.isDirectory(globalStorage)) continue;
            for (String ext : EXTENSION_IDS) {
                Path tasks = globalStorage.resolve(ext).resolve("tasks");
                if (Files.isDirectory(tasks)) out.add(tasks);
            }
        }
        return new ArrayList<>(out);
    }

    @Override
    public SourceInfo discover() {
        List<Path> roots = taskRoots();
        if (roots.isEmpty()) {
            return SourceInfo.unavailable(id(), displayName(),
                    ChatAdapterSupport.userConfigDir().toString(), "no Cline/Roo tasks dir found");
        }
        int count = 0;
        for (Path root : roots) {
            count += countTasks(root);
        }
        return SourceInfo.available(id(), displayName(), roots.get(0).toString(), count);
    }

    @Override
    public List<ChatSessionSummary> list() throws IOException {
        List<Path> roots = taskRoots();
        if (roots.isEmpty()) return Collections.emptyList();
        List<ChatSessionSummary> out = new ArrayList<>();
        for (Path root : roots) {
            try (Stream<Path> stream = Files.list(root)) {
                stream.filter(Files::isDirectory).forEach(taskDir -> addSummary(taskDir, out));
            }
        }
        out.sort((a, b) -> Long.compare(b.lastModifiedMillis(), a.lastModifiedMillis()));
        return out;
    }

    @Override
    public List<ChatTurn> readTurns(String sessionId) throws IOException {
        Optional<Path> dir = findTaskDir(sessionId);
        if (dir.isEmpty()) return Collections.emptyList();
        return readConversation(dir.get());
    }

    @Override
    public Optional<Path> resolveWorkingDirectory(String sessionId) throws IOException {
        Optional<Path> dir = findTaskDir(sessionId);
        if (dir.isEmpty()) return Optional.empty();
        Path metadata = dir.get().resolve("task_metadata.json");
        if (!Files.exists(metadata)) return Optional.empty();
        try {
            JsonNode node = ChatAdapterSupport.MAPPER.readTree(metadata.toFile());
            JsonNode cwd = node.path("cwd");
            if (!cwd.isTextual()) cwd = node.path("workspace");
            if (!cwd.isTextual()) cwd = node.path("workingDirectory");
            if (cwd.isTextual()) return Optional.of(Path.of(cwd.asText()));
        } catch (Exception ignore) {
        }
        return Optional.empty();
    }

    protected Optional<Path> findTaskDir(String sessionId) {
        for (Path root : taskRoots()) {
            Path candidate = root.resolve(sessionId);
            if (Files.isDirectory(candidate)) return Optional.of(candidate);
        }
        return Optional.empty();
    }

    private static int countTasks(Path root) {
        try (Stream<Path> stream = Files.list(root)) {
            return (int) stream.filter(Files::isDirectory)
                    .filter(p -> Files.exists(p.resolve("api_conversation_history.json")))
                    .count();
        } catch (IOException ignore) {
            return 0;
        }
    }

    private void addSummary(Path taskDir, List<ChatSessionSummary> out) {
        Path conv = taskDir.resolve("api_conversation_history.json");
        if (!Files.exists(conv)) return;
        String taskId = taskDir.getFileName().toString();
        String title = readTitle(taskDir);
        int turnCount = 0;
        try {
            turnCount = readConversation(taskDir).size();
        } catch (IOException ignore) {
        }
        out.add(new ChatSessionSummary(taskId, id(),
                title == null ? "(cline)" : title,
                id(), turnCount, ChatAdapterSupport.lastModified(conv)));
    }

    private String readTitle(Path taskDir) {
        Path metadata = taskDir.resolve("task_metadata.json");
        if (Files.exists(metadata)) {
            try {
                JsonNode node = ChatAdapterSupport.MAPPER.readTree(metadata.toFile());
                for (String field : new String[]{"title", "task", "name"}) {
                    JsonNode t = node.path(field);
                    if (t.isTextual() && !t.asText().isBlank()) return t.asText();
                }
            } catch (Exception ignore) {
            }
        }
        Path ui = taskDir.resolve("ui_messages.json");
        if (Files.exists(ui)) {
            try {
                JsonNode node = ChatAdapterSupport.MAPPER.readTree(ui.toFile());
                if (node.isArray() && node.size() > 0) {
                    JsonNode first = node.get(0);
                    String text = ChatAdapterSupport.extractContent(first);
                    if (text != null && !text.isBlank()) {
                        return text.length() > 80 ? text.substring(0, 77) + "..." : text;
                    }
                }
            } catch (Exception ignore) {
            }
        }
        return null;
    }

    private static List<ChatTurn> readConversation(Path taskDir) throws IOException {
        Path conv = taskDir.resolve("api_conversation_history.json");
        if (!Files.exists(conv)) return Collections.emptyList();
        JsonNode root = ChatAdapterSupport.MAPPER.readTree(conv.toFile());
        List<ChatTurn> out = new ArrayList<>();
        if (!root.isArray()) return out;
        for (JsonNode msg : root) {
            String role = ChatAdapterSupport.extractRole(msg);
            String content = ChatAdapterSupport.extractContent(msg);
            if (role != null && content != null && !content.isBlank()) {
                out.add(new ChatTurn(role, content));
            }
        }
        return out;
    }
}
