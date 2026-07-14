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

package ai.kompile.cli.common.chat.sources.adapters;

import ai.kompile.cli.common.chat.sources.ChatSessionSummary;
import ai.kompile.cli.common.chat.sources.ChatTurn;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ClaudeCodeAdapterTest {

    @TempDir
    Path tempDir;

    @Test
    void resolvesHumanReadableSlugToCanonicalSessionId() throws Exception {
        Path projects = tempDir.resolve("projects");
        Path project = projects.resolve("-tmp-project");
        Files.createDirectories(project);
        Files.writeString(project.resolve("consolidate-dsp-dispatch-state-machine.jsonl"), """
                {"type":"permission-mode","permissionMode":"bypassPermissions","sessionId":"8367e592-9ed2-4c55-ae22-9418bb72532c"}
                {"type":"user","message":{"role":"user","content":"Investigate DSP dispatch state machine"},"cwd":"/work/project","sessionId":"8367e592-9ed2-4c55-ae22-9418bb72532c"}
                {"type":"assistant","message":{"role":"assistant","content":"I found the state transition issue."},"cwd":"/work/project","sessionId":"8367e592-9ed2-4c55-ae22-9418bb72532c","slug":"consolidate-dsp-dispatch-state-machine"}
                """, StandardCharsets.UTF_8);

        ClaudeCodeAdapter adapter = new TestClaudeCodeAdapter(projects);

        List<ChatSessionSummary> summaries = adapter.list();
        assertEquals(1, summaries.size());
        assertEquals("8367e592-9ed2-4c55-ae22-9418bb72532c", summaries.get(0).sessionId());
        assertEquals("consolidate-dsp-dispatch-state-machine", summaries.get(0).title());
        assertEquals("/work/project", summaries.get(0).workingDirectory());

        List<ChatTurn> turns = adapter.readTurns("consolidate-dsp-dispatch-state-machine");
        assertEquals(2, turns.size());
        assertEquals("Investigate DSP dispatch state machine", turns.get(0).content());

        assertTrue(adapter.resolveWorkingDirectory("8367e592-9ed2-4c55-ae22-9418bb72532c").isPresent());
        assertEquals(Path.of("/work/project"),
                adapter.resolveWorkingDirectory("consolidate-dsp-dispatch-state-machine").orElseThrow());
    }

    @Test
    void resolvesCustomTitleToCanonicalSessionId() throws Exception {
        Path projects = tempDir.resolve("projects");
        Path project = projects.resolve("-tmp-project");
        Files.createDirectories(project);
        Files.writeString(project.resolve("9d0cbc43-189a-485c-b630-c5221f6d49d5.jsonl"), """
                {"type":"permission-mode","permissionMode":"bypassPermissions","sessionId":"9d0cbc43-189a-485c-b630-c5221f6d49d5"}
                {"type":"user","message":{"role":"user","content":"Consolidate the DSP dispatch state machine"},"cwd":"/work/project","sessionId":"9d0cbc43-189a-485c-b630-c5221f6d49d5"}
                {"type":"assistant","message":{"role":"assistant","content":"I mapped the dispatch states."},"cwd":"/work/project","sessionId":"9d0cbc43-189a-485c-b630-c5221f6d49d5"}
                {"type":"custom-title","customTitle":"consolidate-dsp-dispatch-state-machine","sessionId":"9d0cbc43-189a-485c-b630-c5221f6d49d5"}
                {"type":"agent-name","agentName":"consolidate-dsp-dispatch-state-machine","sessionId":"9d0cbc43-189a-485c-b630-c5221f6d49d5"}
                """, StandardCharsets.UTF_8);

        ClaudeCodeAdapter adapter = new TestClaudeCodeAdapter(projects);

        List<ChatSessionSummary> summaries = adapter.list();
        assertEquals(1, summaries.size());
        assertEquals("9d0cbc43-189a-485c-b630-c5221f6d49d5", summaries.get(0).sessionId());
        assertEquals("consolidate-dsp-dispatch-state-machine", summaries.get(0).title());

        List<ChatTurn> turns = adapter.readTurns("consolidate-dsp-dispatch-state-machine");
        assertEquals(2, turns.size());
        assertEquals("Consolidate the DSP dispatch state machine", turns.get(0).content());

        assertEquals(Path.of("/work/project"),
                adapter.resolveWorkingDirectory("consolidate-dsp-dispatch-state-machine").orElseThrow());
    }

    @Test
    void preservesRawContentBlocksForToolUseAndToolResult() throws Exception {
        Path projects = tempDir.resolve("projects");
        Path project = projects.resolve("-tmp-project");
        Files.createDirectories(project);

        // Simulate a real Claude Code session with tool_use and tool_result blocks
        String assistantWithToolUse = """
                {"type":"assistant","message":{"role":"assistant","content":[{"type":"text","text":"Let me check that file."},{"type":"tool_use","id":"toolu_abc123","name":"Read","input":{"file_path":"/tmp/test.txt"}}]},"sessionId":"test-session","cwd":"/tmp"}""";
        String toolResultUser = """
                {"type":"user","message":{"role":"user","content":[{"type":"tool_result","tool_use_id":"toolu_abc123","content":[{"type":"text","text":"file contents here"}]}]},"sessionId":"test-session","cwd":"/tmp"}""";
        String plainUser = """
                {"type":"user","message":{"role":"user","content":"Check the file"},"sessionId":"test-session","cwd":"/tmp"}""";
        String plainAssistant = """
                {"type":"assistant","message":{"role":"assistant","content":[{"type":"text","text":"Done."}]},"sessionId":"test-session","cwd":"/tmp"}""";

        Files.writeString(project.resolve("test-session.jsonl"),
                plainUser + "\n" + assistantWithToolUse + "\n" + toolResultUser + "\n" + plainAssistant + "\n",
                StandardCharsets.UTF_8);

        ClaudeCodeAdapter adapter = new TestClaudeCodeAdapter(projects);
        List<ChatTurn> turns = adapter.readTurns("test-session");

        assertEquals(4, turns.size());

        // Plain user message: no raw blocks
        assertNull(turns.get(0).rawContentBlocks(), "plain user should have null rawContentBlocks");

        // Assistant with tool_use: raw blocks preserved
        ArrayNode assistantBlocks = turns.get(1).rawContentBlocks();
        assertNotNull(assistantBlocks, "assistant with tool_use should have rawContentBlocks");
        assertEquals(2, assistantBlocks.size());
        assertEquals("text", assistantBlocks.get(0).get("type").asText());
        assertEquals("tool_use", assistantBlocks.get(1).get("type").asText());
        assertEquals("Read", assistantBlocks.get(1).get("name").asText());
        assertEquals("toolu_abc123", assistantBlocks.get(1).get("id").asText());

        // Tool result user message: raw blocks preserved
        ArrayNode toolResultBlocks = turns.get(2).rawContentBlocks();
        assertNotNull(toolResultBlocks, "tool_result user should have rawContentBlocks");
        assertEquals(1, toolResultBlocks.size());
        assertEquals("tool_result", toolResultBlocks.get(0).get("type").asText());
        assertEquals("toolu_abc123", toolResultBlocks.get(0).get("tool_use_id").asText());

        // Plain assistant: no raw blocks (no structured content)
        assertNull(turns.get(3).rawContentBlocks(), "plain assistant should have null rawContentBlocks");

        // Verify flattened content is still available for all turns
        assertTrue(turns.get(1).content().contains("[tool:Read]"));
        assertTrue(turns.get(2).content().contains("[tool-result]"));
    }

    @Test
    void derivesTitleAndTimestampFromRealMessageFields() throws Exception {
        Path projects = tempDir.resolve("projects");
        Path project = projects.resolve("-tmp-project");
        Files.createDirectories(project);
        Files.writeString(project.resolve("timestamped-session.jsonl"), """
                {"type":"user","message":{"role":"user","content":"Add Claude Code transcript imports"},"timestamp":"2026-07-10T10:15:30Z","cwd":"/work/project","sessionId":"timestamped-session"}
                {"type":"assistant","message":{"role":"assistant","content":"I will inspect the adapter."},"timestamp":"2026-07-10T10:15:31Z","cwd":"/work/project","sessionId":"timestamped-session"}
                """, StandardCharsets.UTF_8);

        ClaudeCodeAdapter adapter = new TestClaudeCodeAdapter(projects);

        ChatSessionSummary summary = adapter.list().get(0);
        assertEquals("Add Claude Code transcript imports", summary.title());
        assertEquals("Add Claude Code transcript imports", adapter.resolveTitle("timestamped-session"));

        List<ChatTurn> turns = adapter.readTurns("timestamped-session");
        assertEquals(Instant.parse("2026-07-10T10:15:30Z"), turns.get(0).timestamp());
        assertEquals(Instant.parse("2026-07-10T10:15:31Z"), turns.get(1).timestamp());
    }

    @Test
    void listHonorsLimitBeforeParsingSessionSummaries() throws Exception {
        Path projects = tempDir.resolve("projects");
        Path project = projects.resolve("-tmp-project");
        Files.createDirectories(project);

        for (int i = 1; i <= 3; i++) {
            Path file = project.resolve("session-" + i + ".jsonl");
            Files.writeString(file, """
                    {"type":"user","message":{"role":"user","content":"Session %d"},"sessionId":"session-%d"}
                    """.formatted(i, i), StandardCharsets.UTF_8);
            Files.setLastModifiedTime(file, FileTime.fromMillis(1_000L * i));
        }

        ClaudeCodeAdapter adapter = new TestClaudeCodeAdapter(projects);
        List<ChatSessionSummary> summaries = adapter.list(2);

        assertEquals(2, summaries.size());
        assertEquals("session-3", summaries.get(0).sessionId());
        assertEquals("session-2", summaries.get(1).sessionId());
        assertTrue(adapter.list(0).isEmpty());
    }

    @Test
    void deduplicatesRootTranscriptsWithTheSameCanonicalSessionId() throws Exception {
        Path projects = tempDir.resolve("projects");
        Path firstProject = projects.resolve("-work-first");
        Path secondProject = projects.resolve("-work-second");
        Files.createDirectories(firstProject);
        Files.createDirectories(secondProject);

        String sessionId = "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee";
        Path first = firstProject.resolve("first-copy.jsonl");
        Path second = secondProject.resolve("second-copy.jsonl");
        Files.writeString(first, """
                {"type":"user","message":{"role":"user","content":"First copy"},"sessionId":"%s"}
                """.formatted(sessionId), StandardCharsets.UTF_8);
        Files.writeString(second, """
                {"type":"user","message":{"role":"user","content":"Second copy"},"sessionId":"%s"}
                """.formatted(sessionId), StandardCharsets.UTF_8);
        Files.setLastModifiedTime(first, FileTime.fromMillis(1_000L));
        Files.setLastModifiedTime(second, FileTime.fromMillis(2_000L));

        ClaudeCodeAdapter adapter = new TestClaudeCodeAdapter(projects);
        List<ChatSessionSummary> summaries = adapter.list();

        assertEquals(1, summaries.size());
        assertEquals(sessionId, summaries.get(0).sessionId());
        assertEquals(1, adapter.discover().sessionCount());
    }

    @Test
    void usesNativeIndexTitleAndExcludesSidechainsAndNestedSubagents() throws Exception {
        Path projects = tempDir.resolve("projects");
        Path project = projects.resolve("-tmp-project");
        Path subagents = project.resolve("subagents");
        Files.createDirectories(subagents);

        String rootId = "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee";
        String sidechainId = "11111111-2222-3333-4444-555555555555";
        String unindexedSidechainId = "66666666-7777-8888-9999-000000000000";
        Path rootFile = project.resolve(rootId + ".jsonl");
        Path sidechainFile = project.resolve(sidechainId + ".jsonl");
        Path unindexedSidechainFile = project.resolve(unindexedSidechainId + ".jsonl");

        Files.writeString(rootFile, """
                {"type":"user","message":{"role":"user","content":"Raw JSONL prompt"},"cwd":"/work/project","sessionId":"%s"}
                """.formatted(rootId), StandardCharsets.UTF_8);
        Files.writeString(sidechainFile, """
                {"type":"user","message":{"role":"user","content":"Sidechain prompt"},"cwd":"/work/project","sessionId":"%s"}
                """.formatted(sidechainId), StandardCharsets.UTF_8);
        Files.writeString(unindexedSidechainFile, """
                {"type":"user","message":{"role":"user","content":"Unindexed sidechain prompt"},"cwd":"/work/project","sessionId":"%s","isSidechain":true}
                """.formatted(unindexedSidechainId), StandardCharsets.UTF_8);
        Files.writeString(subagents.resolve("agent-child.jsonl"), """
                {"type":"user","message":{"role":"user","content":"Nested subagent prompt"},"sessionId":"agent-child"}
                """, StandardCharsets.UTF_8);

        Files.writeString(project.resolve("sessions-index.json"), """
                {
                  "version": 1,
                  "entries": [
                    {
                      "sessionId": "%s",
                      "fullPath": "%s",
                      "firstPrompt": "Native Claude picker title",
                      "summary": "Generated summary",
                      "messageCount": 9,
                      "modified": "2026-07-14T01:02:03Z",
                      "projectPath": "/work/project",
                      "isSidechain": false
                    },
                    {
                      "sessionId": "%s",
                      "fullPath": "%s",
                      "firstPrompt": "Sidechain picker title",
                      "messageCount": 2,
                      "projectPath": "/work/project",
                      "isSidechain": true
                    }
                  ]
                }
                """.formatted(
                        rootId,
                        rootFile.toAbsolutePath(),
                        sidechainId,
                        sidechainFile.toAbsolutePath()),
                StandardCharsets.UTF_8);

        ClaudeCodeAdapter adapter = new TestClaudeCodeAdapter(projects);

        List<ChatSessionSummary> summaries = adapter.list();
        assertEquals(1, summaries.size());
        assertEquals(rootId, summaries.get(0).sessionId());
        assertEquals("Generated summary", summaries.get(0).title());
        assertEquals(9, summaries.get(0).messageCount());
        assertEquals("/work/project", summaries.get(0).workingDirectory());
        assertEquals(1, adapter.discover().sessionCount());
        assertTrue(adapter.readTurns("agent-child").isEmpty());
        assertTrue(adapter.readTurns(unindexedSidechainId).isEmpty());
    }

    @Test
    void matchesNativePickerTitlesAndExcludesPrintModeTaskSessions() throws Exception {
        Path projects = tempDir.resolve("projects");
        Path project = projects.resolve("-work-project");
        Files.createDirectories(project);

        String aiTitleId = "aaaaaaaa-1111-2222-3333-444444444444";
        String lastPromptId = "bbbbbbbb-1111-2222-3333-444444444444";
        String taskId = "cccccccc-1111-2222-3333-444444444444";
        String standaloneId = "dddddddd-1111-2222-3333-444444444444";

        StringBuilder aiTitleTranscript = new StringBuilder()
                .append("{\"type\":\"user\",\"message\":{\"role\":\"user\",\"content\":\"Raw injected prompt\"},")
                .append("\"cwd\":\"/work/project\",\"sessionId\":\"").append(aiTitleId).append("\"}\n");
        for (int i = 0; i < 40; i++) {
            aiTitleTranscript.append("{\"type\":\"assistant\",\"message\":{\"role\":\"assistant\",")
                    .append("\"content\":\"progress ").append(i).append("\"},\"sessionId\":\"")
                    .append(aiTitleId).append("\"}\n");
        }
        aiTitleTranscript.append("{\"type\":\"ai-title\",\"aiTitle\":\"Native generated title\",")
                .append("\"sessionId\":\"").append(aiTitleId).append("\"}\n");
        Files.writeString(project.resolve(aiTitleId + ".jsonl"),
                aiTitleTranscript, StandardCharsets.UTF_8);

        StringBuilder lastPromptTranscript = new StringBuilder()
                .append("{\"type\":\"user\",\"message\":{\"role\":\"user\",\"content\":\"# AGENTS.md injected prompt\"},")
                .append("\"cwd\":\"/work/project\",\"sessionId\":\"").append(lastPromptId).append("\"}\n");
        for (int i = 0; i < 40; i++) {
            lastPromptTranscript.append("{\"type\":\"assistant\",\"message\":{\"role\":\"assistant\",")
                    .append("\"content\":\"progress ").append(i).append("\"},\"sessionId\":\"")
                    .append(lastPromptId).append("\"}\n");
        }
        lastPromptTranscript.append("{\"type\":\"last-prompt\",\"lastPrompt\":\"Continue\",")
                .append("\"sessionId\":\"").append(lastPromptId).append("\"}\n");
        Files.writeString(project.resolve(lastPromptId + ".jsonl"),
                lastPromptTranscript, StandardCharsets.UTF_8);

        Files.writeString(project.resolve(taskId + ".jsonl"), """
                {"type":"ai-title","aiTitle":"Delegated task title","sessionId":"%s"}
                {"type":"queue-operation","operation":"enqueue","sessionId":"%s","content":"READ-ONLY investigation"}
                {"type":"user","message":{"role":"user","content":"READ-ONLY investigation"},"cwd":"/work/project","sessionId":"%s"}
                """.formatted(taskId, taskId, taskId), StandardCharsets.UTF_8);
        Files.writeString(project.resolve(standaloneId + ".jsonl"), """
                {"type":"user","message":{"role":"user","content":"Standalone visual smoke"},"cwd":"/work/project","sessionId":"%s"}
                {"type":"assistant","message":{"role":"assistant","content":"VISUAL-OK"},"cwd":"/work/project","sessionId":"%s"}
                """.formatted(standaloneId, standaloneId), StandardCharsets.UTF_8);

        Files.writeString(project.resolve("sessions-index.json"), """
                {
                  "version": 1,
                  "entries": [{
                    "sessionId": "%s",
                    "fullPath": "%s",
                    "firstPrompt": "Raw injected prompt",
                    "summary": "Stale indexed summary",
                    "projectPath": "/work/project",
                    "isSidechain": false
                  }],
                  "%s": {
                    "id": "%s",
                    "summary": "# AGENTS.md injected prompt",
                    "projectPath": "/work/project"
                  }
                }
                """.formatted(
                        aiTitleId,
                        project.resolve(aiTitleId + ".jsonl").toAbsolutePath(),
                        lastPromptId,
                        lastPromptId),
                StandardCharsets.UTF_8);

        Files.writeString(tempDir.resolve("history.jsonl"), """
                {"display":"Later interactive prompt","project":"/work/project","sessionId":"%s"}
                {"display":"Continue","project":"/work/project","sessionId":"%s"}
                """.formatted(aiTitleId, lastPromptId), StandardCharsets.UTF_8);

        ClaudeCodeAdapter adapter = new TestClaudeCodeAdapter(projects);
        List<ChatSessionSummary> summaries = adapter.list(Path.of("/work/project"));

        assertEquals(3, summaries.size());
        assertEquals("Native generated title", summaries.stream()
                .filter(summary -> aiTitleId.equals(summary.sessionId()))
                .findFirst().orElseThrow().title());
        assertEquals("Continue", summaries.stream()
                .filter(summary -> lastPromptId.equals(summary.sessionId()))
                .findFirst().orElseThrow().title());
        assertEquals("Standalone visual smoke", summaries.stream()
                .filter(summary -> standaloneId.equals(summary.sessionId()))
                .findFirst().orElseThrow().title());
        assertTrue(summaries.stream().noneMatch(summary -> taskId.equals(summary.sessionId())));
    }

    @Test
    void projectScopedListEnumeratesOnlyMatchingClaudeProjectDirectories() throws Exception {
        Path projects = tempDir.resolve("projects");
        Path matching = projects.resolve("-work-project");
        Path nestedProject = projects.resolve("-work-project-child");
        Path unrelated = projects.resolve("-other-project");
        Files.createDirectories(matching);
        Files.createDirectories(nestedProject);
        Files.createDirectories(unrelated);

        Files.writeString(matching.resolve("matching.jsonl"), """
                {"type":"user","message":{"role":"user","content":"Matching project"},"cwd":"/work/project","sessionId":"matching"}
                """, StandardCharsets.UTF_8);
        Files.writeString(nestedProject.resolve("nested.jsonl"), """
                {"type":"user","message":{"role":"user","content":"Nested project"},"cwd":"/work/project/child","sessionId":"nested"}
                """, StandardCharsets.UTF_8);
        Files.writeString(unrelated.resolve("unrelated.jsonl"), """
                {"type":"user","message":{"role":"user","content":"Unrelated project"},"cwd":"/other/project","sessionId":"unrelated"}
                """, StandardCharsets.UTF_8);

        ClaudeCodeAdapter adapter = new TestClaudeCodeAdapter(projects);

        List<ChatSessionSummary> summaries = adapter.list(Path.of("/work/project"));
        assertEquals(1, summaries.size());
        assertEquals("matching", summaries.get(0).sessionId());
        assertEquals("Matching project", summaries.get(0).title());
        assertEquals(-1, summaries.get(0).messageCount());
    }

    @Test
    void encodesWindowsDriveSeparatorLikeClaudeProjectDirectories() {
        assertEquals("C--work-project",
                ClaudeCodeAdapter.encodeProjectDirectory("C:\\work\\project"));
    }

    @Test
    void directUuidLookupDoesNotEnumerateOrParseUnrelatedTranscripts() throws Exception {
        Path projects = tempDir.resolve("projects");
        Path project = projects.resolve("-work-project");
        Files.createDirectories(project);
        String sessionId = "aaaaaaaa-1111-2222-3333-bbbbbbbbbbbb";
        Files.writeString(project.resolve(sessionId + ".jsonl"), """
                {"type":"user","message":{"role":"user","content":"Direct session"},"cwd":"/work/project","sessionId":"%s"}
                """.formatted(sessionId), StandardCharsets.UTF_8);

        ClaudeCodeAdapter adapter = new TestClaudeCodeAdapter(projects) {
            @Override
            protected List<Path> listRootSessionFiles(Path root) {
                throw new AssertionError("direct UUID lookup must not enumerate every transcript");
            }
        };

        assertEquals("Direct session", adapter.readTurns(sessionId).get(0).content());
    }

    @Test
    void indexWithoutSidechainFieldStillChecksBoundedTranscriptHeader() throws Exception {
        Path projects = tempDir.resolve("projects");
        Path project = projects.resolve("-work-project");
        Files.createDirectories(project);

        String sessionId = "aaaaaaaa-1111-2222-3333-bbbbbbbbbbbb";
        Path session = project.resolve(sessionId + ".jsonl");
        Files.writeString(session, """
                {"type":"user","message":{"role":"user","content":"Sidechain"},"cwd":"/work/project","sessionId":"%s","isSidechain":true}
                """.formatted(sessionId), StandardCharsets.UTF_8);
        Files.writeString(project.resolve("sessions-index.json"), """
                {"version":1,"entries":[{
                  "sessionId":"%s",
                  "fullPath":"%s",
                  "firstPrompt":"Indexed title",
                  "messageCount":1,
                  "projectPath":"/work/project"
                }]}
                """.formatted(sessionId, session.toAbsolutePath()), StandardCharsets.UTF_8);

        ClaudeCodeAdapter adapter = new TestClaudeCodeAdapter(projects);

        assertTrue(adapter.list().isEmpty());
    }

    private static class TestClaudeCodeAdapter extends ClaudeCodeAdapter {
        private final Path root;

        private TestClaudeCodeAdapter(Path root) {
            this.root = root;
        }

        @Override
        protected Path rootDir() {
            return root;
        }
    }
}
