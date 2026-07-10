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
package ai.kompile.cli.main.chat.tools;

import ai.kompile.cli.main.chat.format.ConversationExporter;
import ai.kompile.cli.main.chat.format.ConversationReader;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.Method;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ResumeToolCommandTest {

    @TempDir
    Path tempDir;

    @Test
    void codexResumePlacesBypassFlagBeforeResumeSubcommand() throws Exception {
        ConversationExporter.ExportResult exportResult = new ConversationExporter.ExportResult(
                "resume-session",
                "codex",
                tempDir.resolve("session.jsonl"),
                "codex resume resume-session",
                tempDir);

        List<String> args = buildAgentResumeCommand("codex", exportResult);

        assertTrue(args.contains("--dangerously-bypass-approvals-and-sandbox"));
        assertTrue(args.indexOf("--dangerously-bypass-approvals-and-sandbox") < args.indexOf("resume"));
        assertFalse(args.contains("--full-auto"));
        assertFalse(args.contains("--all"));
    }

    @Test
    void opencodeNativeResumeDoesNotAppendRunOnlyPermissionBypassFlag() throws Exception {
        ConversationExporter.ExportResult exportResult = new ConversationExporter.ExportResult(
                "resume-session",
                "opencode",
                tempDir.resolve("session.json"),
                "opencode --session resume-session",
                tempDir);

        List<String> args = buildAgentResumeCommand("opencode", exportResult);

        assertTrue(args.contains("opencode"));
        assertTrue(args.contains("--session"));
        assertTrue(args.contains("resume-session"));
        assertFalse(args.contains("--dangerously-skip-permissions"),
                "OpenCode TUI resume does not support --dangerously-skip-permissions");
    }

    @Test
    void codexResumeUsesStdioMcpEvenWhenSseUrlExists() throws Exception {
        assertNull(resolveResumeMcpSseUrl("codex"));
        assertEquals("stdio", mcpModeForResume("codex", "http://localhost:8080/mcp/sse"));
    }

    @Test
    void nonCodexResumeUsesSseMcpWhenUrlExists() throws Exception {
        assertEquals("sse", mcpModeForResume("claude", "http://localhost:8080/mcp/sse"));
        assertEquals("stdio", mcpModeForResume("claude", ""));
    }

    private List<String> buildAgentResumeCommand(String agent,
                                                 ConversationExporter.ExportResult exportResult) throws Exception {
        ResumeTool tool = new ResumeTool(null, null, null, null, null, new ConversationReader());
        Method method = ResumeTool.class.getDeclaredMethod(
                "buildAgentResumeCommand",
                String.class,
                ConversationExporter.ExportResult.class);
        method.setAccessible(true);

        @SuppressWarnings("unchecked")
        List<String> args = (List<String>) method.invoke(tool, agent, exportResult);
        return args;
    }

    private String resolveResumeMcpSseUrl(String agent) throws Exception {
        ResumeTool tool = new ResumeTool(null, null, null, null, null, new ConversationReader());
        Method method = ResumeTool.class.getDeclaredMethod("resolveResumeMcpSseUrl", String.class);
        method.setAccessible(true);
        return (String) method.invoke(tool, agent);
    }

    private String mcpModeForResume(String agent, String sseUrl) throws Exception {
        ResumeTool tool = new ResumeTool(null, null, null, null, null, new ConversationReader());
        Method method = ResumeTool.class.getDeclaredMethod("mcpModeForResume", String.class, String.class);
        method.setAccessible(true);
        return (String) method.invoke(tool, agent, sseUrl);
    }
}
