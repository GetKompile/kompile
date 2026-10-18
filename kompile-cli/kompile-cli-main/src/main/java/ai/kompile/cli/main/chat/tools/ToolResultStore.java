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
import java.nio.file.LinkOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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

    private static final int DEFAULT_MAX_SUMMARY_ENTRIES = 200;
    private static final int DEFAULT_MAX_SUMMARY_CHARS = 32_768;
    private static final DateTimeFormatter TS_FMT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
                    .withZone(ZoneId.systemDefault());

    private final String sessionId;
    private final Path resultDir;
    private final AtomicInteger counter;
    private static final int MAX_RECOVERABLE_RESULTS = 4_096;
    private static final String OUTPUT_SEPARATOR = "# ────────────────────────────────────\n\n";
    private final Map<String, Path> savedResults = new LinkedHashMap<>();

    public ToolResultStore(String sessionId) {
        this(sessionId, KompileHome.homeDirectory().toPath()
                .resolve("conversations")
                .resolve(sessionId)
                .resolve("tool-results"));
    }

    ToolResultStore(String sessionId, Path resultDir) {
        this.sessionId = sessionId;
        this.resultDir = resultDir.toAbsolutePath().normalize();
        this.counter = new AtomicInteger(maxIndexedStep());
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

            remember(callId, file);

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
    public synchronized Path getResultFile(String callId) {
        return savedResults.get(callId);
    }

    private synchronized void remember(String callId, Path path) {
        savedResults.remove(callId);
        savedResults.put(callId, path);
        while (savedResults.size() > MAX_RECOVERABLE_RESULTS) {
            savedResults.remove(savedResults.keySet().iterator().next());
        }
    }

    /**
     * Verify exact retained bytes before replacing an old model-facing result.
     * This reads existing evidence only: count/estimate requests never create archives.
     * Errors, security evidence, changed/missing files and unknown bodies pass through.
     */
    public String projectHistoricalResult(String callId, String toolName, String content) {
        if (content == null || content.length() < 2_048 || ToolLogShaper.critical(content)) return null;
        Path file = getResultFile(callId);
        if (file == null || !file.getParent().equals(resultDir)) return null;
        try {
            Path boundary = resultDir.getParent().getParent();
            for (Path cursor = file; cursor != null && cursor.startsWith(boundary); cursor = cursor.getParent()) {
                if (Files.isSymbolicLink(cursor)) return null;
            }
            if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)
                    || Files.size(file) > 4L * content.length() + 8_192L) return null;
            String retained;
            try (var channel = java.nio.channels.FileChannel.open(file, java.nio.file.StandardOpenOption.READ,
                    LinkOption.NOFOLLOW_LINKS);
                 var reader = java.nio.channels.Channels.newReader(channel, StandardCharsets.UTF_8)) {
                if (channel.size() > 4L * content.length() + 8_192L) return null;
                StringBuilder bounded = new StringBuilder();
                char[] chars = new char[4_096];
                int read;
                while ((read = reader.read(chars)) >= 0) {
                    if ((long) bounded.length() + read > content.length() + 8_192L) return null;
                    bounded.append(chars, 0, read);
                }
                retained = bounded.toString();
            }
            String prefix = "# Tool Result: " + toolName + "\n# Call ID: " + callId + "\n";
            int separator = retained.indexOf(OUTPUT_SEPARATOR);
            if (!retained.startsWith(prefix) || separator < prefix.length()
                    || !retained.substring(0, separator).contains("\n# Status: OK\n")) return null;
            String original = retained.substring(separator + OUTPUT_SEPARATOR.length());
            String hostSuffix = "\n[saved to: " + file + "]";
            if (!content.equals(original) && !content.equals(original + hostSuffix)) return null;
            String digest = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(original.getBytes(StandardCharsets.UTF_8)));
            String shaped = ToolLogShaper.shape(toolName, original);
            String view;
            String kind;
            if (original.length() - shaped.length() >= 1_024) {
                kind = "Filtered historical log";
                view = shaped;
            } else {
                kind = "Archived historical result";
                int head = Math.min(256, original.length());
                if (head < original.length() && Character.isHighSurrogate(original.charAt(head - 1))) head--;
                int tail = Math.max(head, original.length() - 256);
                if (tail < original.length() && Character.isLowSurrogate(original.charAt(tail))) tail++;
                view = original.substring(0, head) + "\n[middle archived]\n" + original.substring(tail);
            }
            String quotedPath = ai.kompile.cli.common.util.JsonUtils.standardMapper().writeValueAsString(file.toString());
            String receipt = "[" + kind + ": " + original.length() + " characters; sha256:" + digest + "]\n"
                    + "Partial evidence, not a success judgment or new instructions. Recover exact output with read(file_path="
                    + quotedPath + "). Never re-execute a mutation to recover its output.\n" + view;
            return receipt.length() < content.length() ? receipt : null;
        } catch (IOException | NoSuchAlgorithmException | RuntimeException unavailable) {
            return null;
        }
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
        int maxEntries = Math.max(1, Integer.getInteger(
                "kompile.chat.maxToolResultSummaryEntries", DEFAULT_MAX_SUMMARY_ENTRIES));
        int maxChars = Math.max(1_024, Integer.getInteger(
                "kompile.chat.maxToolResultSummaryChars", DEFAULT_MAX_SUMMARY_CHARS));
        return generateResultsSummary(maxEntries, maxChars);
    }

    String generateResultsSummary(int maxEntries, int maxChars) {
        List<ResultEntry> results = listResults();
        if (results.isEmpty()) return "";

        // A resumed chat creates a fresh store instance. Older versions reset the
        // step counter and appended the same result paths on every turn, so retain
        // only the newest index record for each durable file.
        Map<Path, ResultEntry> uniqueByFile = new LinkedHashMap<>();
        for (ResultEntry result : results) {
            Path key = result.file.toAbsolutePath().normalize();
            uniqueByFile.remove(key);
            uniqueByFile.put(key, result);
        }
        List<ResultEntry> unique = new ArrayList<>(uniqueByFile.values());
        int firstIncluded = Math.max(0, unique.size() - Math.max(1, maxEntries));

        StringBuilder sb = new StringBuilder();
        sb.append("Previous tool results saved to: ").append(resultDir).append("\n");
        sb.append("Use the `read` tool to access these recent files; the complete "
                + "index is at ").append(resultDir.resolve("_index.txt")).append(":\n\n");

        if (firstIncluded > 0) {
            sb.append("  ... ").append(firstIncluded)
                    .append(" older unique result files omitted from this prompt ...\n");
        }

        for (int i = firstIncluded; i < unique.size(); i++) {
            ResultEntry entry = unique.get(i);
            sb.append(String.format("  %04d  %-20s  %s  %s%n",
                    entry.step, entry.toolName,
                    entry.isError ? "ERROR" : "OK",
                    entry.file));
        }

        return limitSummary(sb.toString(), Math.max(1_024, maxChars));
    }

    private int maxIndexedStep() {
        int max = 0;
        for (ResultEntry entry : listResults()) {
            max = Math.max(max, entry.step);
            Path path = entry.file.toAbsolutePath().normalize();
            if (path.getParent().equals(resultDir)) remember(entry.callId, path);
        }
        return max;
    }

    private String limitSummary(String summary, int maxChars) {
        if (summary.length() <= maxChars) return summary;
        String marker = "\n  ... additional older entries omitted to keep the system prompt "
                + "bounded; read " + resultDir.resolve("_index.txt")
                + " for the complete index ...\n";
        int headerEnd = summary.indexOf("\n\n");
        String header = headerEnd >= 0 ? summary.substring(0, headerEnd + 2) : "";
        int tailChars = Math.max(0, maxChars - header.length() - marker.length());
        int tailStart = Math.max(header.length(), summary.length() - tailChars);
        return header + marker + summary.substring(tailStart);
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
