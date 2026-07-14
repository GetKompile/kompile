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
import ai.kompile.staging.web.dto.DatasetInfo;
import ai.kompile.staging.web.dto.DatasetStats;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class DatasetServiceTest {

    private final ObjectMapper objectMapper = JsonUtils.standardMapper();

    @TempDir
    Path tempDir;

    private DatasetService service;

    @BeforeEach
    void setUp() {
        service = new DatasetService(objectMapper);
        ReflectionTestUtils.setField(service, "datasetsDir", tempDir.toString());
    }

    @Test
    void uploadsPreviewsAndComputesStatsForClaudeCodeJsonl() throws Exception {
        String content = jsonLines(
                Map.of(
                        "type", "user",
                        "sessionId", "claude-session",
                        "message", Map.of("role", "user", "content", "First request")),
                Map.of(
                        "type", "assistant",
                        "sessionId", "claude-session",
                        "message", Map.of("role", "assistant", "content", "First response")),
                Map.of(
                        "type", "user",
                        "sessionId", "claude-session",
                        "message", Map.of("role", "user", "content", "Second request")),
                Map.of(
                        "type", "assistant",
                        "sessionId", "claude-session",
                        "message", Map.of("role", "assistant", "content", "Second response")));

        DatasetInfo info = upload("Claude history", "claude-code", "claude.jsonl", content);

        assertThat(info.getFormat()).isEqualTo(TranscriptJsonlDatasetSupport.CLAUDE_CODE_JSONL);
        assertThat(info.getTotalSamples()).isEqualTo(2);

        List<Map<String, Object>> preview = service.previewDataset(info.getId(), 10);
        assertThat(preview).hasSize(2);
        assertThat(preview.get(0))
                .containsEntry("format", TranscriptJsonlDatasetSupport.CLAUDE_CODE_JSONL)
                .containsEntry("session_id", "claude-session");

        DatasetStats stats = service.computeStats(info.getId());
        assertThat(stats.getTotalSamples()).isEqualTo(2);
        assertThat(stats.getTrainSamples()).isEqualTo(1);
        assertThat(stats.getValSamples()).isEqualTo(1);
        assertThat(stats.getAvgTokenLength()).isPositive();
    }

    @Test
    void uploadsOpenCodeJsonlAndCountsOnlyAssistantResponses() throws Exception {
        String content = jsonLines(
                Map.of(
                        "id", "1",
                        "session_id", "open-session",
                        "type", "user",
                        "data", Map.of("text", "Run the tests")),
                Map.of(
                        "id", "2",
                        "session_id", "open-session",
                        "type", "shell",
                        "data", Map.of("command", "mvn test", "output", "ok")),
                Map.of(
                        "id", "3",
                        "session_id", "open-session",
                        "type", "assistant",
                        "data", Map.of("content", List.of(
                                Map.of("type", "text", "text", "Tests pass.")))));

        DatasetInfo info = upload(
                "OpenCode history",
                TranscriptJsonlDatasetSupport.OPENCODE_JSONL,
                "opencode.jsonl",
                content);

        assertThat(info.getFormat()).isEqualTo(TranscriptJsonlDatasetSupport.OPENCODE_JSONL);
        assertThat(info.getTotalSamples()).isEqualTo(1);
        assertThat(service.previewDataset(info.getId(), 10)).hasSize(1);
    }

    @Test
    void keepsOrdinaryChatMlOnTheGenericJsonlServicePath() throws Exception {
        String content = jsonLines(Map.of(
                "messages", List.of(
                        Map.of("role", "user", "content", "What is LoRA?"),
                        Map.of("role", "assistant", "content", "A low-rank adapter."))));

        DatasetInfo info = upload("Generic ChatML", "JSONL", "chatml.jsonl", content);

        assertThat(info.getFormat()).isEqualTo("JSONL");
        assertThat(info.getTotalSamples()).isEqualTo(1);

        List<Map<String, Object>> preview = service.previewDataset(info.getId(), 10);
        assertThat(preview).hasSize(1);
        assertThat(preview.get(0))
                .containsKey("messages")
                .doesNotContainKeys("session_id", "source_line");

        DatasetStats stats = service.computeStats(info.getId());
        assertThat(stats.getTotalSamples()).isEqualTo(1);
    }

    private DatasetInfo upload(String name, String format, String filename, String content) {
        MockMultipartFile file = new MockMultipartFile(
                "file",
                filename,
                "application/x-ndjson",
                content.getBytes(StandardCharsets.UTF_8));
        return service.uploadDataset(
                name,
                format,
                "CAUSAL_LM",
                "text",
                "",
                null,
                null,
                0.9,
                file);
    }

    @SafeVarargs
    private final String jsonLines(Map<String, ?>... rows) throws Exception {
        StringBuilder out = new StringBuilder();
        for (Map<String, ?> row : rows) {
            out.append(objectMapper.writeValueAsString(row)).append(System.lineSeparator());
        }
        return out.toString();
    }
}
