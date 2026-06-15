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

class ResumeToolAliasResolutionTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @TempDir
    Path tempDir;

    @Test
    void resolveActionMapsClaudeSlugToCanonicalSessionId() throws Exception {
        String oldHome = System.getProperty("user.home");
        try {
            System.setProperty("user.home", tempDir.toString());
            writeClaudeSlugSession();

            ResumeTool tool = new ResumeTool(null, null, null, null, null, new ConversationReader());
            ToolResult result = tool.execute(MAPPER.readTree("""
                    {
                      "action": "resolve",
                      "session_id": "consolidate-dsp-dispatch-state-machine",
                      "source": "claude-code"
                    }
                    """), null);

            assertFalse(result.isError());
            JsonNode output = MAPPER.readTree(result.getOutput());
            assertEquals("8367e592-9ed2-4c55-ae22-9418bb72532c", output.path("resolved_session_id").asText());
            assertEquals("alias", output.path("matched_field").asText());
            assertEquals("consolidate-dsp-dispatch-state-machine", output.path("matched_value").asText());
        } finally {
            System.setProperty("user.home", oldHome);
        }
    }

    @Test
    void viewActionAcceptsClaudeSlugEvenWhenFileNameIsSlug() throws Exception {
        String oldHome = System.getProperty("user.home");
        try {
            System.setProperty("user.home", tempDir.toString());
            writeClaudeSlugSession();

            ResumeTool tool = new ResumeTool(null, null, null, null, null, new ConversationReader());
            ToolResult result = tool.execute(MAPPER.readTree("""
                    {
                      "action": "view",
                      "session_id": "consolidate-dsp-dispatch-state-machine",
                      "source": "claude-code"
                    }
                    """), null);

            assertFalse(result.isError(), result.getOutput());
            JsonNode output = MAPPER.readTree(result.getOutput());
            assertEquals("8367e592-9ed2-4c55-ae22-9418bb72532c", output.path("session_id").asText());
            assertEquals("claude-code", output.path("source").asText());
            assertEquals(2, output.path("message_count").asInt());
        } finally {
            System.setProperty("user.home", oldHome);
        }
    }

    @Test
    void resolveActionMapsClaudeCustomTitleToCanonicalSessionId() throws Exception {
        String oldHome = System.getProperty("user.home");
        try {
            System.setProperty("user.home", tempDir.toString());
            writeClaudeCustomTitleSession();

            ResumeTool tool = new ResumeTool(null, null, null, null, null, new ConversationReader());
            ToolResult result = tool.execute(MAPPER.readTree("""
                    {
                      "action": "resolve",
                      "session_id": "consolidate-dsp-dispatch-state-machine",
                      "source": "claude-code"
                    }
                    """), null);

            assertFalse(result.isError(), result.getOutput());
            JsonNode output = MAPPER.readTree(result.getOutput());
            assertEquals("9d0cbc43-189a-485c-b630-c5221f6d49d5", output.path("resolved_session_id").asText());
            assertEquals("alias", output.path("matched_field").asText());
            assertEquals("consolidate-dsp-dispatch-state-machine", output.path("matched_value").asText());
        } finally {
            System.setProperty("user.home", oldHome);
        }
    }

    @Test
    void viewActionAcceptsClaudeCustomTitleWhenFileNameIsCanonicalId() throws Exception {
        String oldHome = System.getProperty("user.home");
        try {
            System.setProperty("user.home", tempDir.toString());
            writeClaudeCustomTitleSession();

            ResumeTool tool = new ResumeTool(null, null, null, null, null, new ConversationReader());
            ToolResult result = tool.execute(MAPPER.readTree("""
                    {
                      "action": "view",
                      "session_id": "consolidate-dsp-dispatch-state-machine",
                      "source": "claude-code"
                    }
                    """), null);

            assertFalse(result.isError(), result.getOutput());
            JsonNode output = MAPPER.readTree(result.getOutput());
            assertEquals("9d0cbc43-189a-485c-b630-c5221f6d49d5", output.path("session_id").asText());
            assertEquals("claude-code", output.path("source").asText());
            assertEquals(2, output.path("message_count").asInt());
        } finally {
            System.setProperty("user.home", oldHome);
        }
    }

    @Test
    void interactiveResolverFallsBackToAllProjectsForFriendlyClaudeTitle() throws Exception {
        String oldHome = System.getProperty("user.home");
        String oldUserDir = System.getProperty("user.dir");
        try {
            System.setProperty("user.home", tempDir.toString());
            System.setProperty("user.dir", tempDir.resolve("different-project").toString());
            writeClaudeCustomTitleSession();

            ResumeTool tool = new ResumeTool(null, null, null, null, null, new ConversationReader());
            java.lang.reflect.Method method = ResumeTool.class.getDeclaredMethod("resolveSessionId", String.class);
            method.setAccessible(true);

            assertEquals("9d0cbc43-189a-485c-b630-c5221f6d49d5",
                    method.invoke(tool, "consolidate-dsp-dispatch-state-machine"));
        } finally {
            System.setProperty("user.home", oldHome);
            System.setProperty("user.dir", oldUserDir);
        }
    }

    private void writeClaudeSlugSession() throws Exception {
        Path project = tempDir.resolve(".claude")
                .resolve("projects")
                .resolve("-work-project");
        Files.createDirectories(project);
        Files.writeString(project.resolve("consolidate-dsp-dispatch-state-machine.jsonl"), """
                {"type":"permission-mode","permissionMode":"bypassPermissions","sessionId":"8367e592-9ed2-4c55-ae22-9418bb72532c"}
                {"type":"user","message":{"role":"user","content":"Investigate DSP dispatch state machine"},"cwd":"/work/project","sessionId":"8367e592-9ed2-4c55-ae22-9418bb72532c"}
                {"type":"assistant","message":{"role":"assistant","content":"I found the state transition issue."},"cwd":"/work/project","sessionId":"8367e592-9ed2-4c55-ae22-9418bb72532c","slug":"consolidate-dsp-dispatch-state-machine"}
                """, StandardCharsets.UTF_8);
    }

    private void writeClaudeCustomTitleSession() throws Exception {
        Path project = tempDir.resolve(".claude")
                .resolve("projects")
                .resolve("-work-project");
        Files.createDirectories(project);
        Files.writeString(project.resolve("9d0cbc43-189a-485c-b630-c5221f6d49d5.jsonl"), """
                {"type":"permission-mode","permissionMode":"bypassPermissions","sessionId":"9d0cbc43-189a-485c-b630-c5221f6d49d5"}
                {"type":"user","message":{"role":"user","content":"Consolidate the DSP dispatch state machine"},"cwd":"/work/project","sessionId":"9d0cbc43-189a-485c-b630-c5221f6d49d5"}
                {"type":"assistant","message":{"role":"assistant","content":"I mapped the dispatch states."},"cwd":"/work/project","sessionId":"9d0cbc43-189a-485c-b630-c5221f6d49d5"}
                {"type":"custom-title","customTitle":"consolidate-dsp-dispatch-state-machine","sessionId":"9d0cbc43-189a-485c-b630-c5221f6d49d5"}
                {"type":"agent-name","agentName":"consolidate-dsp-dispatch-state-machine","sessionId":"9d0cbc43-189a-485c-b630-c5221f6d49d5"}
                """, StandardCharsets.UTF_8);
    }
}
