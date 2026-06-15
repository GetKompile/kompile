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

import ai.kompile.cli.common.KompileHome;
import ai.kompile.cli.common.chat.sources.ChatAdapterSupport;
import ai.kompile.cli.common.chat.sources.ChatSessionSummary;
import ai.kompile.cli.common.chat.sources.ChatSourceAdapter;
import ai.kompile.cli.common.chat.sources.ChatTurn;
import ai.kompile.cli.common.chat.sources.SourceInfo;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class KompileAdapter implements ChatSourceAdapter {

    public static final String ID = "kompile";

    @Override
    public String id() {
        return ID;
    }

    @Override
    public String displayName() {
        return "Kompile transcripts";
    }

    private Path conversationsDir() {
        return KompileHome.homeDirectory().toPath().resolve("conversations");
    }

    @Override
    public SourceInfo discover() {
        Path dir = conversationsDir();
        if (!Files.isDirectory(dir)) {
            return SourceInfo.unavailable(ID, displayName(), dir.toString(), "directory missing");
        }
        int count = 0;
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir, "*.txt")) {
            for (Path ignored : stream) count++;
        } catch (IOException e) {
            return SourceInfo.unavailable(ID, displayName(), dir.toString(), e.getMessage());
        }
        return SourceInfo.available(ID, displayName(), dir.toString(), count);
    }

    @Override
    public List<ChatSessionSummary> list() throws IOException {
        Path dir = conversationsDir();
        if (!Files.isDirectory(dir)) return Collections.emptyList();
        List<ChatSessionSummary> out = new ArrayList<>();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir, "*.txt")) {
            for (Path path : stream) {
                String name = path.getFileName().toString();
                String id = name.substring(0, name.length() - 4);
                Header header = readHeader(path);
                int count = countTurns(path);
                out.add(new ChatSessionSummary(
                        id, ID, header.title, header.agent, count,
                        ChatAdapterSupport.lastModified(path), header.workingDir));
            }
        }
        out.sort((a, b) -> Long.compare(b.lastModifiedMillis(), a.lastModifiedMillis()));
        return out;
    }

    @Override
    public List<ChatTurn> readTurns(String sessionId) throws IOException {
        String safe = ChatAdapterSupport.safeSessionId(sessionId)
                .orElseThrow(() -> new IOException("Invalid session id: " + sessionId));
        Path file = conversationsDir().resolve(safe + ".txt");
        if (!Files.isRegularFile(file)) {
            return Collections.emptyList();
        }
        return parseTranscript(file);
    }

    static List<ChatTurn> parseTranscript(Path file) throws IOException {
        List<ChatTurn> out = new ArrayList<>();
        try (BufferedReader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            String line;
            boolean inHeader = true;
            String currentRole = null;
            StringBuilder buffer = new StringBuilder();
            while ((line = reader.readLine()) != null) {
                if (inHeader) {
                    if (line.isBlank()) {
                        inHeader = false;
                    }
                    continue;
                }
                if (line.startsWith("[system]") || line.startsWith("[resumed")
                        || line.startsWith("[tool:") || line.startsWith("[subagent:")
                        || line.startsWith("[todo:") || line.startsWith("[agentic-step]")) {
                    continue;
                }
                if (line.startsWith("> ")) {
                    flush(out, currentRole, buffer);
                    currentRole = "user";
                    buffer.setLength(0);
                    buffer.append(line.substring(2));
                } else if (line.isBlank()) {
                    if (currentRole != null && buffer.length() > 0) {
                        flush(out, currentRole, buffer);
                        currentRole = (currentRole.equals("user")) ? "assistant" : null;
                        buffer.setLength(0);
                    }
                } else {
                    if (currentRole == null) currentRole = "assistant";
                    if (buffer.length() > 0) buffer.append('\n');
                    buffer.append(line);
                }
            }
            flush(out, currentRole, buffer);
        }
        return out;
    }

    private static void flush(List<ChatTurn> out, String role, StringBuilder buffer) {
        if (role == null) return;
        String text = buffer.toString().trim();
        if (text.isEmpty()) return;
        out.add(new ChatTurn(role, text));
    }

    private static Header readHeader(Path file) {
        Header h = new Header();
        try (BufferedReader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            String line;
            while ((line = reader.readLine()) != null && !line.isBlank()) {
                if (line.startsWith("Started:")) h.title = line.substring("Started:".length()).trim();
                else if (line.startsWith("Agent:")) h.agent = line.substring("Agent:".length()).trim();
                else if (line.startsWith("CWD:")) h.workingDir = line.substring("CWD:".length()).trim();
            }
        } catch (IOException ignore) {
        }
        return h;
    }

    private static int countTurns(Path file) {
        int count = 0;
        try (BufferedReader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            String line;
            boolean inHeader = true;
            while ((line = reader.readLine()) != null) {
                if (inHeader) {
                    if (line.isBlank()) inHeader = false;
                    continue;
                }
                if (line.startsWith("> ")) count++;
            }
        } catch (IOException ignore) {
        }
        return count * 2;
    }

    private static class Header {
        String title = "(untitled)";
        String agent = "";
        String workingDir;
    }
}
