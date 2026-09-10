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
 * limitations under the License.
 */

package ai.kompile.cli.main.chat.activity;

import ai.kompile.cli.common.util.JsonUtils;
import ai.kompile.cli.main.chat.ToolCallRecord;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ToolCallTailReaderTest {

    private static final String SESSION = "active-session";
    private final ObjectMapper mapper = JsonUtils.standardMapper();

    @Test
    void readsNewestPerSessionRecordsWithoutOpeningCombinedIndex(@TempDir Path tempDir)
            throws Exception {
        Path directory = Files.createDirectories(tempDir.resolve("tool-calls"));
        // A directory at this path makes any accidental combined-index file read fail loudly.
        Files.createDirectory(directory.resolve("all-tool-calls.jsonl"));
        writeLines(directory.resolve(SESSION + ".jsonl"), List.of(
                jsonRecord("1", "read"),
                jsonRecord("2", "grep"),
                jsonRecord("3", "process")));
        ToolCallTailReader reader = reader(directory, 4096, 4096, 10);

        ToolCallTailReader.TailResult result = reader.readRecent(SESSION, 2);

        assertTrue(result.reset());
        assertEquals(List.of("process", "grep"), result.records().stream()
                .map(ToolCallRecord::getToolName).toList());
        assertEquals(0, result.malformedLines());
    }

    @Test
    void waitsForPartialFinalJsonLineBeforePublishingIt(@TempDir Path tempDir)
            throws Exception {
        Path directory = Files.createDirectories(tempDir.resolve("tool-calls"));
        Path sessionFile = directory.resolve(SESSION + ".jsonl");
        String first = jsonRecord("1", "read");
        String second = jsonRecord("2", "write");
        int split = second.length() / 2;
        Files.writeString(sessionFile, first + "\n" + second.substring(0, split),
                StandardCharsets.UTF_8);
        ToolCallTailReader reader = reader(directory, 4096, 4096, 10);

        ToolCallTailReader.TailResult partial = reader.readRecent(SESSION, 10);
        assertEquals(List.of("read"), partial.records().stream()
                .map(ToolCallRecord::getToolName).toList());

        Files.writeString(sessionFile, second.substring(split) + "\n",
                StandardCharsets.UTF_8, StandardOpenOption.APPEND);
        ToolCallTailReader.TailResult completed = reader.readRecent(SESSION, 10);

        assertFalse(completed.reset());
        assertEquals(List.of("write", "read"), completed.records().stream()
                .map(ToolCallRecord::getToolName).toList());
        assertEquals(0, completed.malformedLines());
    }

    @Test
    void resetsWhenSessionFileIsTruncated(@TempDir Path tempDir) throws Exception {
        Path directory = Files.createDirectories(tempDir.resolve("tool-calls"));
        Path sessionFile = directory.resolve(SESSION + ".jsonl");
        String large = jsonRecord("1", "old-tool", "x".repeat(2000));
        Files.writeString(sessionFile, large + "\n", StandardCharsets.UTF_8);
        ToolCallTailReader reader = reader(directory, 4096, 4096, 10);
        assertEquals("old-tool", reader.readRecent(SESSION, 10).records().get(0).getToolName());

        Files.writeString(sessionFile, jsonRecord("2", "new-tool") + "\n",
                StandardCharsets.UTF_8,
                StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
        ToolCallTailReader.TailResult reset = reader.readRecent(SESSION, 10);

        assertTrue(reset.reset());
        assertEquals(List.of("new-tool"), reset.records().stream()
                .map(ToolCallRecord::getToolName).toList());
    }

    @Test
    void initialReadIsBoundedToTailWindow(@TempDir Path tempDir) throws Exception {
        Path directory = Files.createDirectories(tempDir.resolve("tool-calls"));
        Path sessionFile = directory.resolve(SESSION + ".jsonl");
        Files.writeString(sessionFile, ("not-json-but-old\n").repeat(10_000),
                StandardCharsets.UTF_8);
        Files.writeString(sessionFile,
                jsonRecord("1", "recent-a") + "\n" + jsonRecord("2", "recent-b") + "\n",
                StandardCharsets.UTF_8, StandardOpenOption.APPEND);
        ToolCallTailReader reader = reader(directory, 1024, 4096, 10);

        ToolCallTailReader.TailResult result = reader.readRecent(SESSION, 10);

        assertEquals(List.of("recent-b", "recent-a"), result.records().stream()
                .map(ToolCallRecord::getToolName).toList());
        assertTrue(result.malformedLines() < 100,
                "bounded initialization must not parse the large historical prefix");
    }

    @Test
    void oversizedSingleRecordIsReportedInsteadOfTriggeringUnboundedRead(@TempDir Path tempDir)
            throws Exception {
        Path directory = Files.createDirectories(tempDir.resolve("tool-calls"));
        Path sessionFile = directory.resolve(SESSION + ".jsonl");
        Files.writeString(sessionFile,
                jsonRecord("1", "oversized", "x".repeat(700_000)) + "\n",
                StandardCharsets.UTF_8);
        ToolCallTailReader reader = reader(directory, 1024, 4096, 10);

        ToolCallTailReader.TailResult result = reader.readRecent(SESSION, 10);

        assertTrue(result.records().isEmpty());
        assertEquals(1, result.malformedLines());
    }

    @Test
    void rejectsSessionIdsThatCouldEscapeToolCallDirectory(@TempDir Path tempDir) {
        ToolCallTailReader reader = reader(tempDir, 1024, 4096, 10);
        assertThrows(IllegalArgumentException.class,
                () -> reader.readRecent("../all-tool-calls", 10));
    }

    @Test
    void retainedCursorDropsRawArgumentsAndRedactsBoundedSummary(@TempDir Path tempDir)
            throws Exception {
        Path directory = Files.createDirectories(tempDir.resolve("tool-calls"));
        Path sessionFile = directory.resolve(SESSION + ".jsonl");
        ToolCallRecord secret = new ToolCallRecord(
                "secret-call", SESSION, "webfetch",
                "{\"api_key\":\"raw-secret-value\",\"payload\":\""
                        + "x".repeat(4_000) + "\"}",
                "api_key=summary-secret Authorization: Bearer bearer-secret "
                        + "y".repeat(1_000),
                Instant.parse("2026-09-02T00:00:01Z"),
                "mcp", "coder", false, 5L, "web", "/project");
        Files.writeString(sessionFile, mapper.writeValueAsString(secret) + "\n",
                StandardCharsets.UTF_8);
        ToolCallTailReader reader = reader(directory, 16 * 1024, 16 * 1024, 10);

        ToolCallRecord retained = reader.readRecent(SESSION, 10).records().get(0);

        assertTrue(retained.getToolInput().isEmpty());
        assertFalse(retained.getToolInputSummary().contains("summary-secret"));
        assertFalse(retained.getToolInputSummary().contains("bearer-secret"));
        assertTrue(retained.getToolInputSummary().contains("<redacted>"));
        assertTrue(retained.getToolInputSummary().length()
                <= ActivityToolText.MAX_SUMMARY_CHARS);
        assertTrue(retained.getSource().isEmpty());
        assertTrue(retained.getAgentName().isEmpty());
        assertTrue(retained.getCategory().isEmpty());
        assertNull(retained.getProjectDirectory());
    }

    private ToolCallTailReader reader(Path directory, int initialBytes,
                                      int incrementalBytes, int retainedRecords) {
        return new ToolCallTailReader(
                directory, mapper, initialBytes, incrementalBytes, retainedRecords);
    }

    private String jsonRecord(String suffix, String toolName) throws Exception {
        return jsonRecord(suffix, toolName, "input-" + suffix);
    }

    private String jsonRecord(String suffix, String toolName, String input) throws Exception {
        ToolCallRecord record = new ToolCallRecord(
                SESSION + "-" + suffix,
                SESSION,
                toolName,
                "{\"value\":\"" + input + "\"}",
                input,
                Instant.parse("2026-09-02T00:00:0" + Math.min(9, Integer.parseInt(suffix)) + "Z"),
                "test",
                "coder",
                false,
                10L,
                "general",
                "/project");
        return mapper.writeValueAsString(record);
    }

    private static void writeLines(Path file, List<String> lines) throws Exception {
        Files.writeString(file, String.join("\n", lines) + "\n", StandardCharsets.UTF_8);
    }
}
