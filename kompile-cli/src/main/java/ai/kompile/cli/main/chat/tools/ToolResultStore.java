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

package ai.kompile.cli.main.chat.tools;

import ai.kompile.cli.common.KompileHome;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Persists every tool call result to disk so that agents can access them
 * after context compaction. Each session gets a directory under
 * {@code ~/.kompile/conversations/<session-id>/tool-results/}.
 * <p>
 * Files are named {@code <step>-<tool-name>.txt} and contain a header
 * with metadata followed by the full output.
 * <p>
 * An index file ({@code _index.txt}) tracks all saved results for quick lookup.
 */
public class ToolResultStore {

    private static final DateTimeFormatter TS_FMT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
                    .withZone(ZoneId.systemDefault());

    private final String sessionId;
    private final Path resultDir;
    private final AtomicInteger counter = new AtomicInteger(0);
    private final Map<String, Path> savedResults = new ConcurrentHashMap<>();

    public ToolResultStore(String sessionId) {
        this.sessionId = sessionId;
        this.resultDir = KompileHome.homeDirectory().toPath()
                .resolve("conversations")
                .resolve(sessionId)
                .resolve("tool-results");
    }

    /**
     * Save a tool call result to disk.
     *
     * @param toolName  the tool that was called
     * @param callId    the tool call ID
     * @param arguments the arguments passed to the tool (JSON string)
     * @param output    the full tool output
     * @param isError   whether the result is an error
     * @return the path to the saved file, or null on failure
     */
    public Path save(String toolName, String callId, String arguments, String output, boolean isError) {
        if (output == null || output.isEmpty()) return null;

        try {
            Files.createDirectories(resultDir);

            int step = counter.incrementAndGet();
            String safeName = toolName.replaceAll("[^a-zA-Z0-9_-]", "_");
            String fileName = String.format("%04d-%s.txt", step, safeName);
            Path file = resultDir.resolve(fileName);

            StringBuilder content = new StringBuilder();
            content.append("# Tool Result: ").append(toolName).append("\n");
            content.append("# Call ID: ").append(callId).append("\n");
            content.append("# Time: ").append(TS_FMT.format(Instant.now())).append("\n");
            content.append("# Status: ").append(isError ? "ERROR" : "OK").append("\n");
            if (arguments != null && !arguments.isEmpty()) {
                // Truncate very long arguments
                String args = arguments.length() > 500
                        ? arguments.substring(0, 500) + "..."
                        : arguments;
                content.append("# Arguments: ").append(args).append("\n");
            }
            content.append("# Lines: ").append(output.lines().count()).append("\n");
            content.append("# Size: ").append(output.length()).append(" chars\n");
            content.append("#\n");
            content.append("# Use `read` tool with this file path to access the full output.\n");
            content.append("# ────────────────────────────────────\n\n");
            content.append(output);

            Files.writeString(file, content.toString(), StandardCharsets.UTF_8);

            savedResults.put(callId, file);

            // Update index
            appendToIndex(step, toolName, callId, isError, output.lines().count(), file);

            return file;
        } catch (IOException e) {
            return null;
        }
    }

    /**
     * Get the path where a specific tool call result was saved.
     */
    public Path getResultFile(String callId) {
        return savedResults.get(callId);
    }

    /**
     * Get the directory containing all tool results for this session.
     */
    public Path getResultDir() {
        return resultDir;
    }

    /**
     * List all saved result files with their metadata.
     */
    public List<ResultEntry> listResults() {
        List<ResultEntry> entries = new ArrayList<>();
        Path indexFile = resultDir.resolve("_index.txt");
        if (!Files.exists(indexFile)) return entries;

        try {
            List<String> lines = Files.readAllLines(indexFile, StandardCharsets.UTF_8);
            for (String line : lines) {
                if (line.startsWith("#") || line.isBlank()) continue;
                String[] parts = line.split("\t", 5);
                if (parts.length >= 5) {
                    entries.add(new ResultEntry(
                            Integer.parseInt(parts[0]),
                            parts[1], // toolName
                            parts[2], // callId
                            parts[3].equals("ERROR"),
                            Path.of(parts[4])
                    ));
                }
            }
        } catch (Exception e) {
            // Best effort
        }
        return entries;
    }

    /**
     * Generate a summary of all saved results for injection into compacted context.
     */
    public String generateResultsSummary() {
        List<ResultEntry> results = listResults();
        if (results.isEmpty()) return "";

        StringBuilder sb = new StringBuilder();
        sb.append("Previous tool results saved to: ").append(resultDir).append("\n");
        sb.append("Use the `read` tool to access any of these files:\n\n");

        for (ResultEntry entry : results) {
            sb.append(String.format("  %04d  %-20s  %s  %s%n",
                    entry.step, entry.toolName,
                    entry.isError ? "ERROR" : "OK",
                    entry.file));
        }

        return sb.toString();
    }

    private void appendToIndex(int step, String toolName, String callId,
                                boolean isError, long lineCount, Path file) {
        try {
            Path indexFile = resultDir.resolve("_index.txt");
            String line = String.format("%d\t%s\t%s\t%s\t%s%n",
                    step, toolName, callId, isError ? "ERROR" : "OK", file);
            Files.writeString(indexFile, line, StandardCharsets.UTF_8,
                    java.nio.file.StandardOpenOption.CREATE,
                    java.nio.file.StandardOpenOption.APPEND);
        } catch (IOException e) {
            // Best effort
        }
    }

    public record ResultEntry(int step, String toolName, String callId, boolean isError, Path file) {}
}
