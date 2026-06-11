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
