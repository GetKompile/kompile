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
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */

package ai.kompile.cli.common.chat.sources;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * Shared reader/writer for Kompile's human-readable transcript format.
 *
 * <p>Every physical message line is role-prefixed so multiline and
 * multi-paragraph content round-trips without relying on blank-line heuristics.
 * The reader remains compatible with legacy transcripts where only the first
 * user line was prefixed and assistant content was unprefixed.</p>
 */
public final class KompileTranscriptFormat {

    private static final String USER_PREFIX = "> ";
    private static final String ASSISTANT_PREFIX = "< ";

    private KompileTranscriptFormat() {
    }

    public static void writeTurn(PrintWriter writer, String role, String content) {
        if (writer == null) {
            return;
        }
        String normalizedRole = role == null ? "" : role.trim().toLowerCase(Locale.ROOT);
        String prefix = "user".equals(normalizedRole) ? USER_PREFIX : ASSISTANT_PREFIX;
        String normalizedContent = content == null
                ? ""
                : content.replace("\r\n", "\n").replace('\r', '\n');

        for (String line : normalizedContent.split("\n", -1)) {
            writer.println(prefix + line);
        }
        writer.println();
    }

    public static List<ChatTurn> readTurns(Path file) throws IOException {
        if (file == null || !Files.isRegularFile(file)) {
            return List.of();
        }

        List<ChatTurn> turns = new ArrayList<>();
        ParserState state = new ParserState();
        try (BufferedReader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (isNonMessageLine(line)) {
                    if (line.startsWith("[agent:")) {
                        state.startRole("assistant", turns);
                    }
                    continue;
                }

                if (isPrefixed(line, USER_PREFIX)) {
                    state.startRole("user", turns);
                    state.appendLine(unprefix(line, USER_PREFIX));
                    continue;
                }

                if (isPrefixed(line, ASSISTANT_PREFIX)) {
                    if ("assistant".equals(state.role) && state.pendingBlankLines > 0) {
                        state.flush(turns);
                    }
                    state.startRole("assistant", turns);
                    state.appendLine(unprefix(line, ASSISTANT_PREFIX));
                    continue;
                }

                if (line.isBlank()) {
                    if ("user".equals(state.role)) {
                        state.flush(turns);
                    } else if ("assistant".equals(state.role) && state.lineCount > 0) {
                        state.pendingBlankLines++;
                    }
                    continue;
                }

                if (state.role == null) {
                    state.startRole("assistant", turns);
                }
                state.appendPendingBlankLines();
                state.appendLine(line);
            }
        }
        state.flush(turns);
        return List.copyOf(turns);
    }

    public static int countTurns(Path file) throws IOException {
        return readTurns(file).size();
    }

    public static Header readHeader(Path file) throws IOException {
        String started = null;
        String agent = "";
        String workingDirectory = null;
        if (file == null || !Files.isRegularFile(file)) {
            return new Header(started, agent, workingDirectory);
        }

        try (BufferedReader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            String line;
            boolean metadataWindow = true;
            while ((line = reader.readLine()) != null) {
                if (line.startsWith("> ") || line.startsWith("< ")
                        || line.startsWith("[agent:")) {
                    metadataWindow = false;
                } else if (line.startsWith("[resumed")) {
                    metadataWindow = true;
                } else if (metadataWindow && line.startsWith("Started:")) {
                    started = line.substring("Started:".length()).trim();
                } else if (metadataWindow && line.startsWith("Agent:")) {
                    agent = line.substring("Agent:".length()).trim();
                } else if (metadataWindow && line.startsWith("CWD:")) {
                    workingDirectory = line.substring("CWD:".length()).trim();
                }
            }
        }
        return new Header(started, agent, workingDirectory);
    }

    public static Optional<Path> resolveWorkingDirectory(Path file) throws IOException {
        String value = readHeader(file).workingDirectory();
        if (value == null || value.isBlank()) {
            return Optional.empty();
        }
        try {
            return Optional.of(Path.of(value).toAbsolutePath().normalize());
        } catch (RuntimeException e) {
            throw new IOException("Invalid transcript working directory: " + value, e);
        }
    }

    private static boolean isPrefixed(String line, String prefix) {
        return line.startsWith(prefix) || line.equals(prefix.trim());
    }

    private static String unprefix(String line, String prefix) {
        return line.length() >= prefix.length() ? line.substring(prefix.length()) : "";
    }

    private static boolean isNonMessageLine(String line) {
        return line.startsWith("────")
                || line.startsWith("Started:")
                || line.startsWith("Server:")
                || line.startsWith("Agent:")
                || line.startsWith("RAG:")
                || line.startsWith("CWD:")
                || line.startsWith("[system]")
                || line.startsWith("[agent:")
                || line.startsWith("[resumed")
                || line.startsWith("[tool:")
                || line.startsWith("[subagent:")
                || line.startsWith("[todo:")
                || line.startsWith("[harvested:")
                || line.startsWith("  [") && (line.contains("docs retrieved")
                || line.contains("completed in"));
    }

    public record Header(String started, String agent, String workingDirectory) {
        public String title() {
            return started == null || started.isBlank() ? "(untitled)" : started;
        }
    }

    private static final class ParserState {
        private String role;
        private final StringBuilder content = new StringBuilder();
        private int lineCount;
        private int pendingBlankLines;

        private void startRole(String nextRole, List<ChatTurn> turns) {
            if (role != null && !role.equals(nextRole)) {
                flush(turns);
            }
            if (role == null) {
                role = nextRole;
            }
        }

        private void appendPendingBlankLines() {
            while (pendingBlankLines-- > 0) {
                appendLine("");
            }
            pendingBlankLines = 0;
        }

        private void appendLine(String line) {
            if (lineCount > 0) {
                content.append('\n');
            }
            content.append(line);
            lineCount++;
        }

        private void flush(List<ChatTurn> turns) {
            if (role != null && lineCount > 0 && !content.toString().isBlank()) {
                turns.add(new ChatTurn(role, content.toString()));
            }
            role = null;
            content.setLength(0);
            lineCount = 0;
            pendingBlankLines = 0;
        }
    }
}
