/*
 *   Copyright 2025 Kompile Inc.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package ai.kompile.cli.main.chat.tools;

import ai.kompile.cli.main.chat.format.ConversationReader;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ResumeToolCompactTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @TempDir
    Path tempDir;

    @Test
    void resumeActionCanReturnCompactedConversation() throws Exception {
        String oldHome = System.getProperty("user.home");
        try {
            System.setProperty("user.home", tempDir.toString());
            writeClaudeSession("long-session", 12);

            ResumeTool tool = new ResumeTool(null, null, null, null, null, new ConversationReader());
            ToolResult result = tool.execute(MAPPER.readTree("""
                    {
                      "action": "resume",
                      "session_id": "long-session",
                      "source": "claude-code",
                      "target_agent": "codex",
                      "compact": true,
                      "compact_recent_turns": 2,
                      "compact_max_chars": 5000
                    }
                    """), null);

            assertFalse(result.isError(), result.getOutput());
            JsonNode output = MAPPER.readTree(result.getOutput());
            assertTrue(output.path("compact").asBoolean());
            assertTrue(output.path("compacted").asBoolean());
            assertEquals(24, output.path("original_message_count").asInt());
            assertTrue(output.path("message_count").asInt() <= 3);
            assertTrue(output.path("message_count").asInt() >= 2);
            assertEquals(output.path("message_count").asInt(), output.path("turns").size());
            assertTrue(output.path("conversation_history").asText().contains("compacted cross-agent resume context"));
            assertTrue(output.path("compact_chars_after").asInt() < output.path("compact_chars_before").asInt());
        } finally {
            System.setProperty("user.home", oldHome);
        }
    }

    private void writeClaudeSession(String sessionId, int pairs) throws Exception {
        Path project = tempDir.resolve(".claude")
                .resolve("projects")
                .resolve("-work-project");
        Files.createDirectories(project);

        StringBuilder jsonl = new StringBuilder();
        String detail = " implementation detail".repeat(80);
        for (int i = 0; i < pairs; i++) {
            jsonl.append("{\"type\":\"user\",\"message\":{\"role\":\"user\",\"content\":\"user request ")
                    .append(i)
                    .append(" about cross agent resume")
                    .append(detail)
                    .append("\"},\"cwd\":\"/work/project\",\"sessionId\":\"")
                    .append(sessionId)
                    .append("\"}\n");
            jsonl.append("{\"type\":\"assistant\",\"message\":{\"role\":\"assistant\",\"content\":\"assistant progress ")
                    .append(i)
                    .append(" with implementation notes")
                    .append(detail)
                    .append("\"},\"cwd\":\"/work/project\",\"sessionId\":\"")
                    .append(sessionId)
                    .append("\"}\n");
        }

        Files.writeString(project.resolve(sessionId + ".jsonl"), jsonl.toString(), StandardCharsets.UTF_8);
    }
}
