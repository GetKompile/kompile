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

    /**
     * Native transcript IDs are transport-independent. Only an existing transcript created by
     * the old browser alias scheme is translated; new sessions never receive a web prefix.
     * Existing transcripts must belong to the selected directory before a client can resume them.
     */
    public String resolveSessionId(Path workingDirectory, String sessionId) throws IOException {
        String id = ChatAdapterSupport.safeSessionId(sessionId)
                .filter(value -> value.length() <= 256 && value.matches("[A-Za-z0-9_.-]+"))
                .orElseThrow(() -> new IOException("Invalid transcript session id"));
        Path directory = workingDirectory.toRealPath();
        if (!Files.isRegularFile(conversationsDir().resolve(id + ".txt"))) {
            String legacy = legacyBrowserSessionId(directory, id);
            if (Files.isRegularFile(conversationsDir().resolve(legacy + ".txt"))) id = legacy;
        }
        if (Files.isRegularFile(conversationsDir().resolve(id + ".txt"))) {
            Path recorded = resolveWorkingDirectory(id)
                    .orElseThrow(() -> new IOException("Transcript has no recorded working directory: " + sessionId));
            if (!recorded.toRealPath().equals(directory))
                throw new IOException("Transcript belongs to a different working directory: " + sessionId);
        }
        return id;
    }

    public String readTitle(String sessionId) throws IOException {
        String safe = ChatAdapterSupport.safeSessionId(sessionId)
                .orElseThrow(() -> new IOException("Invalid session id"));
        Path file = conversationsDir().resolve(safe + ".txt");
        String override = KompileTranscriptFormat.readTitleOverride(file);
        return override != null ? override : KompileTranscriptFormat.readHeader(file).title();
    }

    public String rename(Path workingDirectory, String sessionId, String title) throws IOException {
        String id = resolveSessionId(workingDirectory, sessionId);
        return KompileTranscriptFormat.writeTitleOverride(conversationsDir().resolve(id + ".txt"), title);
    }

    /** Compatibility only: never use this to allocate a new transcript. */
    public static String legacyBrowserSessionId(Path directory, String browserSessionId) {
        try {
            var digest = java.security.MessageDigest.getInstance("SHA-256");
            digest.update(directory.toAbsolutePath().normalize().toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
            digest.update((byte) 0);
            digest.update(browserSessionId.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            return "web-" + java.util.HexFormat.of().formatHex(digest.digest(), 0, 20);
        } catch (java.security.NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
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
