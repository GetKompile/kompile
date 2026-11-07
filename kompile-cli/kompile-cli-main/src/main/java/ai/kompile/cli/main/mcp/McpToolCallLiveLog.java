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

package ai.kompile.cli.main.mcp;

import ai.kompile.cli.main.coordination.CoordinationStateManager;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.UUID;

/**
 * Live log of one shell tool call running in this MCP server.
 *
 * <p>A client such as Claude Code receives an MCP call's output only in its final
 * result, and does not surface progress notifications. The parent chat already
 * mirrors processes this server publishes to coordination state and tails their
 * output files, so a call that prints is published the same way: on its first
 * line it gets a log file and a {@value #KIND} process entry, and its end marks
 * that entry terminal. A call that prints nothing publishes nothing.</p>
 */
final class McpToolCallLiveLog {

    /** Coordination kind of a call's log; the chat folds it into the call's tool row. */
    static final String KIND = "tool-call";

    private final CoordinationStateManager coordinator;
    private final Path directory;
    private final String processId;
    private final String command;
    private final String description;
    private final String agentName;
    private final Instant startedAt = Instant.now();

    private BufferedWriter writer;
    private Path file;
    private boolean published;
    private boolean finished;

    McpToolCallLiveLog(CoordinationStateManager coordinator, Path directory, String command,
                       String description, String agentName) {
        this.coordinator = coordinator;
        this.directory = directory;
        this.processId = "toolcall-" + UUID.randomUUID().toString().substring(0, 8);
        this.command = command == null ? "" : command;
        this.description = description == null || description.isBlank()
                ? "bash: " + this.command : description;
        this.agentName = agentName;
    }

    String processId() {
        return processId;
    }

    synchronized void append(String line) {
        // Output a reader thread delivers after the call ended belongs to no live row.
        if (finished || line == null) return;
        try {
            if (writer == null) {
                Files.createDirectories(directory);
                file = directory.resolve(processId + ".log");
                writer = Files.newBufferedWriter(file, StandardCharsets.UTF_8,
                        StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING,
                        StandardOpenOption.WRITE);
                // The mirror skips an entry whose log does not exist yet, so the file
                // is created before the entry becomes visible.
                coordinator.publishProcess(processId, command, description, -1L, "RUNNING",
                        file.toString(), agentName, null, KIND, startedAt, null, null);
                published = true;
            }
            writer.write(line);
            writer.newLine();
            // Flushed per line, as the progress log is, so the chat's tail follows it.
            writer.flush();
        } catch (IOException | RuntimeException failure) {
            // Observability only: the call itself must never fail because of its log.
            System.err.println("[MCP] Tool-call live log failed: " + failure.getMessage());
            closeWriter();
            finished = true;
            markTerminal(true);
        }
    }

    /** End the call's log; the entry stays published, terminal, so the row shows the outcome. */
    synchronized void finish(boolean failed) {
        if (finished) return;
        finished = true;
        closeWriter();
        markTerminal(failed);
    }

    /** Withdraw the entry and delete the log once the chat no longer needs it. */
    synchronized void discard() {
        finish(false);
        if (published) {
            try {
                coordinator.unpublishProcess(processId);
            } catch (RuntimeException ignored) {
                // A stale entry is pruned with this server's coordination session.
            }
            published = false;
        }
        if (file != null) {
            try {
                Files.deleteIfExists(file);
            } catch (IOException ignored) {
                // Lives in the transcript's log directory and goes with it.
            }
        }
    }

    synchronized boolean isPublished() {
        return published;
    }

    private void markTerminal(boolean failed) {
        if (!published) return;
        try {
            coordinator.updateProcessState(processId, failed ? "FAILED" : "COMPLETED",
                    Instant.now(), failed ? 1 : 0);
        } catch (RuntimeException failure) {
            System.err.println("[MCP] Tool-call live log state failed: " + failure.getMessage());
        }
    }

    private void closeWriter() {
        if (writer == null) return;
        try {
            writer.close();
        } catch (IOException ignored) {
            // Every line was flushed when written.
        }
        writer = null;
    }
}
