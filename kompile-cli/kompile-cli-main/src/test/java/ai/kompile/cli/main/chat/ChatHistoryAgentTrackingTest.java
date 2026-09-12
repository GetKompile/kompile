/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package ai.kompile.cli.main.chat;

import ai.kompile.cli.common.chat.sources.KompileTranscriptFormat;
import ai.kompile.cli.main.chat.tools.NativeResumeCoordinator;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A resumed transcript must record the agent it is CURRENTLY continued with,
 * not the vendor that created it. Resume listing, {@code --agent auto}
 * resolution, and activity views all read the last "Agent:" header line, so
 * every resume/vendor change has to append a fresh one.
 */
class ChatHistoryAgentTrackingTest {

    @TempDir
    Path tempDir;

    private String originalUserHome;

    @BeforeEach
    void saveUserHome() {
        originalUserHome = System.getProperty("user.home");
        System.setProperty("user.home", tempDir.toString());
    }

    @AfterEach
    void restoreUserHome() {
        System.setProperty("user.home", originalUserHome);
    }

    private Path transcriptPath(String sessionId) {
        return tempDir.resolve(".kompile").resolve("conversations").resolve(sessionId + ".txt");
    }

    private String lastRecordedAgent(Path transcript) throws Exception {
        String agent = "";
        for (String line : Files.readAllLines(transcript)) {
            if (line.startsWith("Agent:")) {
                agent = line.substring("Agent:".length()).trim();
            }
        }
        return agent;
    }

    @Test
    void resumeRecordsTheCurrentAgentWhenTheVendorChanges() throws Exception {
        ChatHistory first = new ChatHistory("vendor-switch-01");
        first.open("(local)", "claude", false, tempDir);
        first.logUserMessage("started with claude");
        first.logAssistantMessage("claude reply", 0, 0);
        first.close();

        assertEquals("claude", lastRecordedAgent(transcriptPath("vendor-switch-01")));

        // Resume the same transcript with a different vendor.
        ChatHistory second = new ChatHistory("vendor-switch-01");
        second.open("(local)", "codex", false, tempDir);
        second.logUserMessage("continued with codex");
        second.close();

        Path transcript = transcriptPath("vendor-switch-01");
        assertEquals("codex", lastRecordedAgent(transcript),
                "resume must append the current agent after the [resumed] marker");
        assertEquals("codex", KompileTranscriptFormat.readHeader(transcript).agent(),
                "header reader must resolve the latest agent");

        List<ChatHistory.ConversationSummary> conversations = ChatHistory.listConversations();
        assertEquals(1, conversations.size());
        assertEquals("codex", conversations.get(0).agent(),
                "the resume listing must show the current vendor");
    }

    @Test
    void resumeWithoutVendorChangeDoesNotAppendDuplicateAgentLines() throws Exception {
        ChatHistory first = new ChatHistory("vendor-same-01");
        first.open("(local)", "qwen", false, tempDir);
        first.logUserMessage("first session");
        first.close();

        long linesBefore = Files.readAllLines(transcriptPath("vendor-same-01")).size();

        ChatHistory second = new ChatHistory("vendor-same-01");
        second.open("(local)", "qwen", false, tempDir);
        second.logUserMessage("second session");
        second.close();

        assertEquals(1, countAgentLines(transcriptPath("vendor-same-01")),
                "an unchanged vendor must not produce extra Agent: lines");
        assertTrue(Files.readAllLines(transcriptPath("vendor-same-01")).size() > linesBefore,
                "the resumed marker itself is still appended");
        assertEquals("qwen", lastRecordedAgent(transcriptPath("vendor-same-01")));
    }

    @Test
    void recordAgentTracksTheLatestVendorWithLastWinsSemantics() throws Exception {
        ChatHistory history = new ChatHistory("record-agent-01");
        history.open("(local)", "claude", false, tempDir);
        history.logUserMessage("original session");
        history.close();

        // Cross-agent resume paths record the new vendor on the source transcript.
        ChatHistory.recordAgent("record-agent-01", "gemini");
        assertEquals("gemini", lastRecordedAgent(transcriptPath("record-agent-01")));

        // Idempotent: re-recording the same vendor changes nothing.
        long linesAfterFirst = Files.readAllLines(transcriptPath("record-agent-01")).size();
        ChatHistory.recordAgent("record-agent-01", "gemini");
        assertEquals(linesAfterFirst, Files.readAllLines(transcriptPath("record-agent-01")).size());

        // Switching back to the ORIGINAL vendor must still be recorded —
        // last-wins, never "seen anywhere wins".
        ChatHistory.recordAgent("record-agent-01", "claude");
        assertEquals("claude", lastRecordedAgent(transcriptPath("record-agent-01")));
        assertEquals("claude",
                KompileTranscriptFormat.readHeader(transcriptPath("record-agent-01")).agent());

        List<ChatHistory.ConversationSummary> conversations = ChatHistory.listConversations();
        assertEquals(1, conversations.size());
        assertEquals("claude", conversations.get(0).agent());
    }

    @Test
    void recordAgentIgnoresBlankInputAndMissingTranscripts() throws Exception {
        ChatHistory.recordAgent(null, "claude");
        ChatHistory.recordAgent("  ", "claude");
        ChatHistory.recordAgent("record-agent-missing", null);
        ChatHistory.recordAgent("record-agent-missing", "   ");
        ChatHistory.recordAgent("record-agent-missing", "claude");

        assertTrue(Files.notExists(transcriptPath("record-agent-missing")),
                "no stub transcript may be created for a missing session");
    }

    @Test
    void decoratedAgentNamesResolveToTheirVendor() {
        assertEquals("claude", NativeResumeCoordinator.normalizeAgent("claude (emulated)"));
        assertEquals("codex", NativeResumeCoordinator.normalizeAgent("codex (enforcer)"));
        assertEquals("pi", NativeResumeCoordinator.normalizeAgent("pi-cli"));
        assertEquals("claude", NativeResumeCoordinator.normalizeAgent("claude-code"));
        assertEquals("qwen", NativeResumeCoordinator.normalizeAgent("Qwen"));
        assertEquals("", NativeResumeCoordinator.normalizeAgent(null));
    }

    private static long countAgentLines(Path transcript) throws Exception {
        return Files.readAllLines(transcript).stream()
                .filter(line -> line.startsWith("Agent:"))
                .count();
    }
}
