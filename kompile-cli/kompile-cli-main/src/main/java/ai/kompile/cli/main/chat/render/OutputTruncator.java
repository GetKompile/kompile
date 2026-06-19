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

package ai.kompile.cli.main.chat.render;

import ai.kompile.utils.FormatUtils;
import ai.kompile.cli.common.KompileHome;
import lombok.Getter;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.stream.Stream;

/**
 * Smart output truncation for tool results, comparable to OpenCode's truncate.ts.
 *
 * Thresholds: content within 2000 lines AND 50KB passes unchanged.
 * Beyond that, the full output is saved to a temp file and a preview is returned.
 */
public class OutputTruncator {

    private static final int MAX_LINES = 2000;
    private static final int MAX_BYTES = 50 * 1024; // 50KB
    private static final int PREVIEW_LINES = 50;
    private static final Duration CLEANUP_AGE = Duration.ofDays(7);

    private final Path truncationDir;

    public OutputTruncator() {
        this.truncationDir = KompileHome.homeDirectory().toPath().resolve("truncated-outputs");
    }

    /**
     * Truncate output if it exceeds thresholds.
     *
     * @param output the tool output
     * @param toolName the tool that produced this output
     * @return the (possibly truncated) output with guidance
     */
    public TruncationResult truncate(String output, String toolName) {
        if (output == null || output.isEmpty()) {
            return new TruncationResult(output, false, null);
        }

        int byteSize = output.getBytes().length;
        long lineCount = output.lines().count();

        if (lineCount <= MAX_LINES && byteSize <= MAX_BYTES) {
            return new TruncationResult(output, false, null);
        }

        // Save full output to temp file
        Path savedFile = saveFullOutput(output, toolName);

        // Build preview (head)
        StringBuilder preview = new StringBuilder();
        String[] lines = output.split("\n", -1);
        int previewCount = Math.min(PREVIEW_LINES, lines.length);
        int previewBytes = 0;

        for (int i = 0; i < previewCount; i++) {
            if (previewBytes + lines[i].length() > MAX_BYTES / 2) break;
            preview.append(lines[i]).append("\n");
            previewBytes += lines[i].length() + 1;
        }

        long truncatedLines = lineCount - previewCount;
        int truncatedBytes = byteSize - previewBytes;

        preview.append("\n... ").append(truncatedLines).append(" lines / ")
                .append(FormatUtils.formatBytes(truncatedBytes)).append(" truncated");

        if (savedFile != null) {
            preview.append("\nFull output saved to: ").append(savedFile);
        }

        preview.append("\nUse grep or read with offset/limit to access specific sections.");

        return new TruncationResult(preview.toString(), true, savedFile);
    }

    /**
     * Save full output to a timestamped file in the truncation directory.
     */
    private Path saveFullOutput(String output, String toolName) {
        try {
            Files.createDirectories(truncationDir);
            String timestamp = String.valueOf(System.currentTimeMillis());
            String safeName = toolName.replaceAll("[^a-zA-Z0-9]", "_");
            Path file = truncationDir.resolve(safeName + "-" + timestamp + ".txt");
            Files.writeString(file, output);
            return file;
        } catch (IOException e) {
            return null;
        }
    }

    /**
     * Clean up truncation files older than 7 days.
     */
    public void cleanupOldFiles() {
        if (!Files.exists(truncationDir)) return;

        Instant cutoff = Instant.now().minus(CLEANUP_AGE);
        try (Stream<Path> files = Files.list(truncationDir)) {
            files.forEach(file -> {
                try {
                    Instant modified = Files.getLastModifiedTime(file).toInstant();
                    if (modified.isBefore(cutoff)) {
                        Files.delete(file);
                    }
                } catch (IOException ignored) {}
            });
        } catch (IOException ignored) {}
    }


    /**
     * Result of truncation.
     */
    @Getter
    public static class TruncationResult {
        private final String output;
        private final boolean truncated;
        private final Path savedFile;

        public TruncationResult(String output, boolean truncated, Path savedFile) {
            this.output = output;
            this.truncated = truncated;
            this.savedFile = savedFile;
        }
    }
}
