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
import ai.kompile.cli.common.chat.sources.KompileTranscriptFormat;
import ai.kompile.cli.common.chat.sources.SourceInfo;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

public class KompileAdapter implements ChatSourceAdapter {

    public static final String ID = "kompile";

    private final Path conversationsDirectory;

    public KompileAdapter() {
        this(KompileHome.homeDirectory().toPath().resolve("conversations"));
    }

    public KompileAdapter(Path conversationsDirectory) {
        this.conversationsDirectory = conversationsDirectory.toAbsolutePath().normalize();
    }

    @Override
    public String id() {
        return ID;
    }

    @Override
    public String displayName() {
        return "Kompile transcripts";
    }

    private Path conversationsDir() {
        return conversationsDirectory;
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
                KompileTranscriptFormat.Header header = KompileTranscriptFormat.readHeader(path);
                int count = KompileTranscriptFormat.countTurns(path);
                out.add(new ChatSessionSummary(
                        id, ID, header.title(), header.agent(), count,
                        ChatAdapterSupport.lastModified(path), header.workingDirectory()));
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

    @Override
    public Optional<Path> resolveWorkingDirectory(String sessionId) throws IOException {
        String safe = ChatAdapterSupport.safeSessionId(sessionId)
                .orElseThrow(() -> new IOException("Invalid session id: " + sessionId));
        Path file = conversationsDir().resolve(safe + ".txt");
        return KompileTranscriptFormat.resolveWorkingDirectory(file);
    }

    static List<ChatTurn> parseTranscript(Path file) throws IOException {
        return KompileTranscriptFormat.readTurns(file);
    }
}
