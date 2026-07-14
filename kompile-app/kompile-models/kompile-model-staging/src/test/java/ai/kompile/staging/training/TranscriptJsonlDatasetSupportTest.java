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

package ai.kompile.staging.training;

import ai.kompile.cli.common.util.JsonUtils;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TranscriptJsonlDatasetSupportTest {

    private static final ObjectMapper MAPPER = JsonUtils.standardMapper();

    @TempDir
    Path tempDir;

    @Test
    void recognizesCanonicalAliasesAndCompoundFileNames() {
        assertThat(TranscriptJsonlDatasetSupport.canonicalFormat("claude-code"))
                .isEqualTo(TranscriptJsonlDatasetSupport.CLAUDE_CODE_JSONL);
        assertThat(TranscriptJsonlDatasetSupport.canonicalFormat("open_code_jsonl"))
                .isEqualTo(TranscriptJsonlDatasetSupport.OPENCODE_JSONL);
        assertThat(TranscriptJsonlDatasetSupport.inferFormat(Path.of("session.claude.jsonl")))
                .isEqualTo(TranscriptJsonlDatasetSupport.CLAUDE_CODE_JSONL);
        assertThat(TranscriptJsonlDatasetSupport.inferFormat(Path.of("session.opencode.jsonl")))
                .isEqualTo(TranscriptJsonlDatasetSupport.OPENCODE_JSONL);
        assertThat(TranscriptJsonlDatasetSupport.inferFormat(Path.of("ordinary.jsonl"))).isNull();
    }

    @Test
    void detectsNativeRowsInOrdinaryJsonlFileNames() throws Exception {
        Path claude = tempDir.resolve("2f17d6c0-9f5e-4a10-a7d6.jsonl");
        writeJsonLines(claude, Map.of(
                "uuid", "message-1",
                "sessionId", "session-a",
                "type", "user",
                "message", Map.of("role", "user", "content", "Hello")));
        assertThat(TranscriptJsonlDatasetSupport.detectFormat(claude))
                .isEqualTo(TranscriptJsonlDatasetSupport.CLAUDE_CODE_JSONL);

        Path openCode = tempDir.resolve("export.jsonl");
        writeJsonLines(openCode, Map.of(
                "id", "message-1",
                "session_id", "session-a",
                "type", "user",
                "data", Map.of("text", "Hello")));
        assertThat(TranscriptJsonlDatasetSupport.detectFormat(openCode))
                .isEqualTo(TranscriptJsonlDatasetSupport.OPENCODE_JSONL);

        Path generic = tempDir.resolve("generic.jsonl");
        writeJsonLines(generic, Map.of("prompt", "Hello", "completion", "Hi"));
        assertThat(TranscriptJsonlDatasetSupport.detectFormat(generic)).isNull();
    }

    @Test
    void normalizesClaudeCodeHistoryIntoOneSamplePerAssistantResponse() throws Exception {
        Path transcript = tempDir.resolve("claude.jsonl");
        writeJsonLines(transcript,
                Map.of("type", "custom-title", "customTitle", "Training fixture"),
                Map.of("type", "system", "sessionId", "session-a", "content", "Use concise answers."),
                Map.of(
                        "type", "user",
                        "sessionId", "session-a",
                        "message", Map.of("role", "user", "content", "Inspect App.java")),
                Map.of(
                        "type", "assistant",
                        "sessionId", "session-a",
                        "message", Map.of(
                                "role", "assistant",
                                "content", List.of(
                                        Map.of("type", "thinking", "thinking", "I should read the file."),
                                        Map.of("type", "text", "text", "I'll inspect it."),
                                        Map.of("type", "tool_use", "name", "Read",
                                                "input", Map.of("file_path", "App.java"))))),
                Map.of(
                        "type", "user",
                        "sessionId", "session-a",
                        "message", Map.of(
                                "role", "user",
                                "content", List.of(
                                        Map.of("type", "tool_result", "content", "class App {}")))),
                Map.of(
                        "type", "assistant",
                        "sessionId", "session-a",
                        "message", Map.of("role", "assistant", "content", "The class is empty.")),
                Map.of(
                        "type", "user",
                        "sessionId", "session-b",
                        "message", Map.of("role", "user", "content", "Separate session")),
                Map.of(
                        "type", "assistant",
                        "sessionId", "session-b",
                        "message", Map.of("role", "assistant", "content", "Separate response")));

        TranscriptJsonlDatasetSupport.ScanResult result = TranscriptJsonlDatasetSupport.scan(
                transcript, TranscriptJsonlDatasetSupport.CLAUDE_CODE_JSONL, 10);

        assertThat(result.totalSamples()).isEqualTo(3);
        assertThat(result.sourceRecords()).isEqualTo(8);
        assertThat(result.rows()).hasSize(3);

        List<Map<String, String>> first = messages(result.rows().get(0));
        assertThat(first).extracting(message -> message.get("role"))
                .containsExactly("system", "user", "assistant");
        assertThat(first.get(2).get("content"))
                .contains("[thinking] I should read the file.")
                .contains("I'll inspect it.")
                .contains("[tool:Read] {\"file_path\":\"App.java\"}");

        List<Map<String, String>> second = messages(result.rows().get(1));
        assertThat(second).extracting(message -> message.get("role"))
                .containsExactly("system", "user", "assistant", "tool", "assistant");
        assertThat(second.get(3).get("content")).isEqualTo("[tool-result] class App {}");
        assertThat(second.get(4).get("content")).isEqualTo("The class is empty.");

        List<Map<String, String>> third = messages(result.rows().get(2));
        assertThat(result.rows().get(2).get("session_id")).isEqualTo("session-b");
        assertThat(third).extracting(message -> message.get("content"))
                .containsExactly("Separate session", "Separate response");
    }

    @Test
    void normalizesOpenCodeSessionMessageExportsAndStructuredParts() throws Exception {
        Path transcript = tempDir.resolve("opencode.jsonl");
        writeJsonLines(transcript,
                Map.of(
                        "id", "m1",
                        "session_id", "session-a",
                        "type", "user",
                        "time_created", 1,
                        "data", Map.of(
                                "text", "Fix the parser",
                                "files", List.of(Map.of("filename", "Parser.java")))),
                Map.of(
                        "id", "m2",
                        "session_id", "session-a",
                        "type", "assistant",
                        "time_created", 2,
                        "data", MAPPER.writeValueAsString(Map.of(
                                "content", List.of(
                                        Map.of("type", "reasoning", "text", "Inspect the implementation."),
                                        Map.of(
                                                "type", "tool",
                                                "tool", "read",
                                                "state", Map.of(
                                                        "status", "completed",
                                                        "input", Map.of("filePath", "Parser.java"),
                                                        "output", "source text")),
                                        Map.of("type", "text", "text", "I found the issue."))))),
                Map.of(
                        "id", "m3",
                        "session_id", "session-a",
                        "type", "shell",
                        "time_created", 3,
                        "data", Map.of("command", "mvn test", "output", "BUILD SUCCESS")),
                Map.of(
                        "id", "m4",
                        "session_id", "session-a",
                        "type", "user",
                        "time_created", 4,
                        "data", Map.of("text", "Apply the fix")),
                Map.of(
                        "id", "m5",
                        "session_id", "session-a",
                        "type", "assistant",
                        "time_created", 5,
                        "data", Map.of("content", List.of(Map.of("type", "text", "text", "Done.")))));

        TranscriptJsonlDatasetSupport.ScanResult result = TranscriptJsonlDatasetSupport.scan(
                transcript, TranscriptJsonlDatasetSupport.OPENCODE_JSONL, 10);

        assertThat(result.totalSamples()).isEqualTo(2);
        assertThat(result.rows()).hasSize(2);

        List<Map<String, String>> first = messages(result.rows().get(0));
        assertThat(first.get(0).get("content"))
                .contains("Fix the parser")
                .contains("[file:Parser.java]");
        assertThat(first.get(1).get("content"))
                .contains("[thinking] Inspect the implementation.")
                .contains("[tool:read] {\"filePath\":\"Parser.java\"}")
                .contains("[tool-result] source text")
                .contains("I found the issue.");

        List<Map<String, String>> second = messages(result.rows().get(1));
        assertThat(second).extracting(message -> message.get("role"))
                .containsExactly("user", "assistant", "tool", "user", "assistant");
        assertThat(second.get(2).get("content"))
                .contains("[shell] mvn test")
                .contains("BUILD SUCCESS");
        assertThat(second.get(4).get("content")).isEqualTo("Done.");
    }

    @Test
    void reportsMalformedNestedOpenCodeDataWithSourceLine() throws Exception {
        Path transcript = tempDir.resolve("broken.opencode.jsonl");
        Files.writeString(transcript, MAPPER.writeValueAsString(Map.of(
                "session_id", "s",
                "type", "assistant",
                "data", "{not-json")) + System.lineSeparator());

        assertThatThrownBy(() -> TranscriptJsonlDatasetSupport.scan(
                transcript, TranscriptJsonlDatasetSupport.OPENCODE_JSONL, 10))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("OPENCODE_JSONL")
                .hasMessageContaining(":1:");
    }

    @Test
    void limitsRetainedRowsWithoutLosingCountsOrTokenStats() throws Exception {
        Path transcript = tempDir.resolve("limited.claude.jsonl");
        writeJsonLines(transcript,
                Map.of("type", "user", "message", Map.of("role", "user", "content", "one two")),
                Map.of("type", "assistant", "message", Map.of("role", "assistant", "content", "three")),
                Map.of("type", "user", "message", Map.of("role", "user", "content", "four")),
                Map.of("type", "assistant", "message", Map.of("role", "assistant", "content", "five six")));

        TranscriptJsonlDatasetSupport.ScanResult result = TranscriptJsonlDatasetSupport.scan(
                transcript, TranscriptJsonlDatasetSupport.CLAUDE_CODE_JSONL, 1);

        assertThat(result.rows()).hasSize(1);
        assertThat(result.totalSamples()).isEqualTo(2);
        assertThat(result.totalTokenCount()).isPositive();
        assertThat(result.maxTokenCount()).isGreaterThanOrEqualTo(result.minTokenCount());
        assertThat(result.averageTokenCount()).isPositive();
    }

    @SafeVarargs
    private static void writeJsonLines(Path path, Map<String, ?>... rows) throws Exception {
        StringBuilder out = new StringBuilder();
        for (Map<String, ?> row : rows) {
            out.append(MAPPER.writeValueAsString(row)).append(System.lineSeparator());
        }
        Files.writeString(path, out);
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, String>> messages(Map<String, Object> row) {
        return (List<Map<String, String>>) row.get("messages");
    }
}
