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

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Aider stores chat history as Markdown in {@code <projectDir>/.aider.chat.history.md}.
 * User turns are prefixed with {@code #### }; assistant text fills between. Sessions
 * start with {@code # aider chat started at <timestamp>} headers, which we use as
 * session boundaries.
 *
 * <p>Discovery: env var {@code AIDER_CHAT_HISTORY_FILE}, then the current working
 * directory, then the user home directory, then optional pinned directories via
 * system property {@code kompile.chat.sources.aider.project-dirs}
 * (comma-separated).</p>
 */
public class AiderAdapter implements ChatSourceAdapter {

    public static final String ID = "aider";

    private static final String HISTORY_NAME = ".aider.chat.history.md";

    private static final Pattern SESSION_HEADER = Pattern.compile(
            "^# aider chat started at (.+)$");

    @Override
    public String id() {
        return ID;
    }

    @Override
    public String displayName() {
        return "Aider";
    }

    protected List<Path> historyFiles() {
        Set<Path> out = new LinkedHashSet<>();
        String envPath = System.getenv("AIDER_CHAT_HISTORY_FILE");
        if (envPath != null && !envPath.isBlank()) {
            Path p = Paths.get(envPath);
            if (Files.exists(p)) out.add(p);
        }
        String cwd = System.getProperty("user.dir");
        if (cwd != null) {
            Path p = Paths.get(cwd, HISTORY_NAME);
            if (Files.exists(p)) out.add(p);
        }
        Path home = ChatAdapterSupport.userHome().resolve(HISTORY_NAME);
        if (Files.exists(home)) out.add(home);
        String pinned = System.getProperty("kompile.chat.sources.aider.project-dirs");
        if (pinned != null && !pinned.isBlank()) {
            for (String dir : pinned.split(",")) {
                if (dir.isBlank()) continue;
                Path p = Paths.get(dir.trim(), HISTORY_NAME);
                if (Files.exists(p)) out.add(p);
            }
        }
        return new ArrayList<>(out);
    }

    @Override
    public SourceInfo discover() {
        List<Path> files = historyFiles();
        if (files.isEmpty()) {
            return SourceInfo.unavailable(id(), displayName(), HISTORY_NAME, "no .aider.chat.history.md found");
        }
        int total = 0;
        for (Path file : files) {
            try {
                total += splitSessions(file).size();
            } catch (IOException ignore) {
            }
        }
        return SourceInfo.available(id(), displayName(),
                files.get(0).getParent() == null ? "." : files.get(0).getParent().toString(), total);
    }

    @Override
    public List<ChatSessionSummary> list() throws IOException {
        List<Path> files = historyFiles();
        if (files.isEmpty()) return Collections.emptyList();
        List<ChatSessionSummary> out = new ArrayList<>();
        long modified = 0L;
        for (Path file : files) {
            long fileModified = ChatAdapterSupport.lastModified(file);
            if (fileModified > modified) modified = fileModified;
            List<AiderSession> sessions = splitSessions(file);
            String workingDir = file.getParent() == null ? null : file.getParent().toString();
            for (AiderSession s : sessions) {
                out.add(new ChatSessionSummary(
                        s.id, id(),
                        s.title == null ? "(aider)" : s.title,
                        id(), s.turns.size(), fileModified, workingDir));
            }
        }
        out.sort((a, b) -> Long.compare(b.lastModifiedMillis(), a.lastModifiedMillis()));
        return out;
    }

    @Override
    public List<ChatTurn> readTurns(String sessionId) throws IOException {
        List<Path> files = historyFiles();
        for (Path file : files) {
            for (AiderSession s : splitSessions(file)) {
                if (s.id.equals(sessionId)) return s.turns;
            }
        }
        return Collections.emptyList();
    }

    @Override
    public Optional<Path> resolveWorkingDirectory(String sessionId) throws IOException {
        List<Path> files = historyFiles();
        for (Path file : files) {
            for (AiderSession s : splitSessions(file)) {
                if (s.id.equals(sessionId)) {
                    return Optional.ofNullable(file.getParent());
                }
            }
        }
        return Optional.empty();
    }

    private static List<AiderSession> splitSessions(Path file) throws IOException {
        List<AiderSession> out = new ArrayList<>();
        String content = new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
        String[] lines = content.split("\n", -1);
        AiderSession current = new AiderSession();
        current.id = deriveDefaultSessionId(file, null, 0);
        StringBuilder assistantBuf = new StringBuilder();
        String pendingUser = null;
        int sessionIndex = 0;
        for (String raw : lines) {
            Matcher m = SESSION_HEADER.matcher(raw);
            if (m.matches()) {
                flushTurns(current, pendingUser, assistantBuf);
                if (!current.turns.isEmpty()) out.add(current);
                sessionIndex++;
                current = new AiderSession();
                current.title = m.group(1).trim();
                current.id = deriveDefaultSessionId(file, current.title, sessionIndex);
                pendingUser = null;
                assistantBuf.setLength(0);
                continue;
            }
            if (raw.startsWith("#### ")) {
                flushTurns(current, pendingUser, assistantBuf);
                pendingUser = raw.substring(5).trim();
                assistantBuf.setLength(0);
                continue;
            }
            if (raw.startsWith("#### >")) {
                flushTurns(current, pendingUser, assistantBuf);
                pendingUser = raw.substring(6).trim();
                assistantBuf.setLength(0);
                continue;
            }
            if (pendingUser != null) {
                if (assistantBuf.length() > 0) assistantBuf.append('\n');
                assistantBuf.append(raw);
            }
        }
        flushTurns(current, pendingUser, assistantBuf);
        if (!current.turns.isEmpty()) out.add(current);
        return out;
    }

    private static void flushTurns(AiderSession session, String pendingUser, StringBuilder assistantBuf) {
        if (pendingUser != null && !pendingUser.isBlank()) {
            session.turns.add(new ChatTurn("user", pendingUser));
        }
        String assistant = assistantBuf.toString().trim();
        if (!assistant.isBlank()) {
            session.turns.add(new ChatTurn("assistant", assistant));
        }
    }

    private static String deriveDefaultSessionId(Path file, String title, int sessionIndex) {
        String base = file.getParent() == null
                ? "aider"
                : ChatAdapterSupport.sanitize(file.getParent().getFileName() == null
                        ? "aider" : file.getParent().getFileName().toString());
        if (title != null && !title.isBlank()) {
            return base + "-" + ChatAdapterSupport.sanitize(title);
        }
        return base + "-" + sessionIndex;
    }

    private static final class AiderSession {
        String id;
        String title;
        final List<ChatTurn> turns = new ArrayList<>();
    }
}
