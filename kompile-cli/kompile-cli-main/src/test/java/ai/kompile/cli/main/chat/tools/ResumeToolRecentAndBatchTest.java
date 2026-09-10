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

package ai.kompile.cli.main.chat.tools;

import ai.kompile.cli.common.chat.sources.ChatSourceRegistry;
import ai.kompile.cli.main.chat.ChatHistory;
import ai.kompile.cli.main.chat.format.ConversationReader;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for the {@code resume} tool's {@code recent} and {@code resume_all}
 * actions — the batch crash-recovery surface. Sessions are planted as
 * kompile transcripts under an isolated {@code user.home}.
 */
class ResumeToolRecentAndBatchTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @TempDir
    Path tempDir;

    private String originalUserHome;
    private ChatSourceRegistry originalSourceRegistry;

    @BeforeEach
    void useIsolatedHome() {
        originalUserHome = System.getProperty("user.home");
        System.setProperty("user.home", tempDir.toString());
        originalSourceRegistry = ChatSourceRegistry.getInstance();
        ChatSourceRegistry.setInstance(ChatSourceRegistry.of(List.of()));
    }

    @AfterEach
    void restoreUserHome() {
        ChatSourceRegistry.setInstance(originalSourceRegistry);
        if (originalUserHome == null) {
            System.clearProperty("user.home");
        } else {
            System.setProperty("user.home", originalUserHome);
        }
    }

    @Test
    void recentListsSessionsNewestFirstWithConfiguredDefault() throws Exception {
        writeKompileTranscript("session-old", "Fix the parser bug");
        Thread.sleep(5);
        writeKompileTranscript("session-new", "Investigate the crash");

        ResumeTool tool = new ResumeTool(null, null, null, null, null, new ConversationReader());
        ToolResult result = tool.execute(MAPPER.readTree("{\"action\": \"recent\"}"), null);

        assertFalse(result.isError(), result.getOutput());
        JsonNode output = MAPPER.readTree(result.getOutput());
        assertEquals(10, output.path("limit").asInt(), "default limit is the configured 10");
        assertEquals(2, output.path("count").asInt());

        JsonNode conversations = output.path("conversations");
        assertEquals(2, conversations.size());
        assertEquals("session-new", conversations.get(0).path("session_id").asText(),
                "newest session first");
        assertEquals("session-old", conversations.get(1).path("session_id").asText());
        assertFalse(conversations.get(0).path("resume_command").asText().isEmpty(),
                "standard chat sessions expose a resume command");
    }

    @Test
    void recentHonorsExplicitLimit() throws Exception {
        for (int i = 1; i <= 5; i++) {
            writeKompileTranscript("session-" + i, "Session " + i);
            Thread.sleep(2);
        }

        ResumeTool tool = new ResumeTool(null, null, null, null, null, new ConversationReader());
        ToolResult result = tool.execute(
                MAPPER.readTree("{\"action\": \"recent\", \"limit\": 2}"), null);

        assertFalse(result.isError(), result.getOutput());
        JsonNode output = MAPPER.readTree(result.getOutput());
        assertEquals(2, output.path("limit").asInt());
        assertEquals(2, output.path("conversations").size());
        assertEquals("session-5", output.path("conversations").get(0).path("session_id").asText());

        Thread.sleep(5);
        writeKompileTranscript("session-6", "Session 6");
        ToolResult refreshed = tool.execute(
                MAPPER.readTree("{\"action\": \"recent\", \"limit\": 1}"), null);
        JsonNode refreshedOutput = MAPPER.readTree(refreshed.getOutput());
        assertEquals("session-6",
                refreshedOutput.path("conversations").get(0).path("session_id").asText(),
                "recent must rebuild its snapshot instead of reusing the prior cache");
    }

    @Test
    void resumeAllRestoresEverySessionInOneCall() throws Exception {
        writeKompileTranscript("batch-a", "First batch session");
        Thread.sleep(5);
        writeKompileTranscript("batch-b", "Second batch session");

        ResumeTool tool = new ResumeTool(null, null, null, null, null, new ConversationReader());
        ToolResult result = tool.execute(
                MAPPER.readTree("{\"action\": \"resume_all\"}"), null);

        assertFalse(result.isError(), result.getOutput());
        JsonNode output = MAPPER.readTree(result.getOutput());
        assertEquals(2, output.path("restored").asInt(), "both sessions restored in one call");
        assertEquals(0, output.path("failed").asInt());

        JsonNode sessions = output.path("sessions");
        assertEquals(2, sessions.size());
        for (JsonNode entry : sessions) {
            assertTrue(entry.has("payload"), "each entry carries a resume payload");
            JsonNode payload = entry.path("payload");
            assertTrue(payload.path("message_count").asInt() > 0,
                    "payload includes the conversation turns");
            assertTrue(payload.has("conversation_history"),
                    "payload includes serialized history like single resume");
        }
    }

    @Test
    void resumeAllHandlesSingleValidSession() throws Exception {
        writeKompileTranscript("good-session", "This one resumes fine");

        ResumeTool tool = new ResumeTool(null, null, null, null, null, new ConversationReader());
        ToolResult result = tool.execute(
                MAPPER.readTree("{\"action\": \"resume_all\", \"limit\": 5}"), null);

        assertFalse(result.isError(), "batch itself must not fail: " + result.getOutput());
        JsonNode output = MAPPER.readTree(result.getOutput());
        assertEquals(1, output.path("restored").asInt());
        assertEquals(0, output.path("failed").asInt());
    }

    /**
     * Plant a resumable kompile transcript under the isolated home using the
     * real ChatHistory writer so the format always matches what the tool reads.
     */
    private void writeKompileTranscript(String sessionId, String title) throws Exception {
        ChatHistory history = new ChatHistory(sessionId);
        history.open("(local)", "coder", false);
        history.logUserMessage(title + "\nuser question for " + sessionId);
        history.logAssistantMessage("assistant answer for " + sessionId, 0, 0);
        history.close();
    }
}
