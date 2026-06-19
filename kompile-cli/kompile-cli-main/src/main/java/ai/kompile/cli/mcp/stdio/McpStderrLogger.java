/*
 *   Copyright 2025 Kompile Inc.
 *
 *   Licensed under the Apache License, Version 2.0 (the "License");
 *   you may not use this file except in compliance with the License.
 *   You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 *   Unless required by applicable law or agreed to in writing, software
 *   distributed under the License is distributed on an "AS IS" BASIS,
 *   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *   See the License for the specific language governing permissions and
 *   limitations under the License.
 */

package ai.kompile.cli.mcp.stdio;

import ai.kompile.cli.common.KompileHome;

import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

/**
 * File-backed sink for MCP stdio diagnostic output.
 *
 * <p>MCP clients such as OpenCode/Crush capture a stdio server's {@code stderr} and may surface
 * it in the chat UI. To keep initialization warnings, skill-loading errors, and third-party
 * library chatter out of the UI, the MCP stdio server redirects {@code System.err} to this
 * logger early in {@code runInProcess()}. All diagnostics are written to
 * {@code ~/.kompile/logs/mcp-stderr.log} instead of the process stderr stream.</p>
 *
 * <p>The log is rotated when it exceeds {@value #MAX_LOG_SIZE} bytes. Writes are synchronized
 * so this sink is safe to share across threads.</p>
 */
public final class McpStderrLogger {

    private static final long MAX_LOG_SIZE = 5 * 1024 * 1024; // 5 MB
    private static final DateTimeFormatter TS_FMT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS")
                    .withZone(ZoneId.systemDefault());

    private final Path logFile;
    private final Object writeLock = new Object();
    private final PrintStream printStream;

    public McpStderrLogger() {
        this(KompileHome.homeDirectory().toPath().resolve("logs").resolve("mcp-stderr.log"));
    }

    public McpStderrLogger(Path logFile) {
        this.logFile = logFile;
        try {
            Files.createDirectories(logFile.getParent());
        } catch (IOException e) {
            // Best-effort; the PrintStream below will also fail safely if the dir is missing.
        }
        this.printStream = new PrintStream(new FileOutputSink(), true, StandardCharsets.UTF_8);
    }

    /** Returns the {@link PrintStream} that writes to the log file. */
    public PrintStream getPrintStream() {
        return printStream;
    }

    /** Returns the path to the log file (useful for status messages once logging is restored). */
    public Path getLogFile() {
        return logFile;
    }

    private void writeLine(String line) {
        String timestamp = TS_FMT.format(Instant.now());
        String entry = "[" + timestamp + "] " + line;
        if (!entry.endsWith("\n")) {
            entry += "\n";
        }
        synchronized (writeLock) {
            try {
                rotateIfNeeded();
                Files.writeString(logFile, entry,
                        StandardCharsets.UTF_8,
                        StandardOpenOption.CREATE,
                        StandardOpenOption.APPEND);
            } catch (IOException ignored) {
                // Logging failures must not break the MCP server.
            }
        }
    }

    private void rotateIfNeeded() throws IOException {
        if (Files.exists(logFile) && Files.size(logFile) > MAX_LOG_SIZE) {
            Path rotated = logFile.resolveSibling(logFile.getFileName() + ".1");
            Files.move(logFile, rotated, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private final class FileOutputSink extends OutputStream {
        private final StringBuilder buffer = new StringBuilder();

        @Override
        public void write(int b) {
            synchronized (buffer) {
                buffer.append((char) (b & 0xFF));
                if (b == '\n') {
                    flushBuffer();
                }
            }
        }

        @Override
        public void write(byte[] b, int off, int len) {
            synchronized (buffer) {
                String chunk = new String(b, off, len, StandardCharsets.UTF_8);
                buffer.append(chunk);
                int nl;
                while ((nl = buffer.indexOf("\n")) >= 0) {
                    String line = buffer.substring(0, nl);
                    buffer.delete(0, nl + 1);
                    writeLine(line);
                }
            }
        }

        @Override
        public void flush() {
            synchronized (buffer) {
                if (buffer.length() > 0) {
                    flushBuffer();
                }
            }
        }

        @Override
        public void close() {
            flush();
        }

        private void flushBuffer() {
            String line = buffer.toString();
            buffer.setLength(0);
            // Strip trailing newline if present; writeLine adds one.
            if (line.endsWith("\n")) {
                line = line.substring(0, line.length() - 1);
            } else if (line.endsWith("\r")) {
                line = line.substring(0, line.length() - 1);
            }
            writeLine(line);
        }
    }
}
