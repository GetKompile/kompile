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

import ai.kompile.cli.main.chat.format.ConversationExporter;
import ai.kompile.cli.main.chat.tools.CrossAgentResumeCompactor;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ResumeCommandTest {

    @TempDir
    Path tempDir;

    @Test
    void codexResumeUsesCurrentBypassFlag() throws Exception {
        ConversationExporter.ExportResult exportResult = new ConversationExporter.ExportResult(
                "resume-session",
                "codex",
                tempDir.resolve("session.jsonl"),
                "codex resume resume-session",
                tempDir);

        List<String> args = buildAgentCommand("codex", exportResult);

        assertTrue(args.contains("--dangerously-bypass-approvals-and-sandbox"));
        assertTrue(args.indexOf("--dangerously-bypass-approvals-and-sandbox") < args.indexOf("resume"));
        assertFalse(args.contains("--full-auto"));
        assertFalse(args.contains("--all"));
    }

    @Test
    void codexResumeUsesFlagOverride() throws Exception {
        String property = "kompile.agent.flags.codex.permissionBypass";
        System.setProperty(property, "--compat-mode no-prompts");
        try {
            ConversationExporter.ExportResult exportResult = new ConversationExporter.ExportResult(
                    "resume-session",
                    "codex",
                    tempDir.resolve("session.jsonl"),
                    "codex resume resume-session",
                    tempDir);

            List<String> args = buildAgentCommand("codex", exportResult);

            assertTrue(args.contains("--compat-mode"));
            assertTrue(args.contains("no-prompts"));
            assertFalse(args.contains("--dangerously-bypass-approvals-and-sandbox"));
            assertFalse(args.contains("--all"));
        } finally {
            System.clearProperty(property);
        }
    }

    @Test
    void opencodeResumeUsesSessionFlagWithoutPermissionBypass() throws Exception {
        ConversationExporter.ExportResult exportResult = new ConversationExporter.ExportResult(
                "resume-session",
                "opencode",
                tempDir.resolve("session.json"),
                "opencode --session resume-session",
                tempDir);

        List<String> args = buildAgentCommand("opencode", exportResult);

        assertTrue(args.contains("opencode"));
        assertTrue(args.contains("--session"));
        assertTrue(args.contains("resume-session"));
        assertFalse(args.contains("--dangerously-skip-permissions"),
                "OpenCode TUI resume does not support --dangerously-skip-permissions");
    }

    @Test
    void directResumeCompactsClaudeTranscriptBeforeCodexExport() {
        String previous = System.getProperty("kompile.codex.model");
        try {
            System.setProperty("kompile.codex.model", "gpt-4");
            List<ChatHistory.Turn> turns = new ArrayList<>();
            for (int i = 0; i < 30; i++) {
                turns.add(new ChatHistory.Turn(i % 2 == 0 ? "user" : "assistant",
                        "claude transcript turn " + i + " " + "payload ".repeat(1_000),
                        null));
            }
            turns.add(new ChatHistory.Turn("user", "latest claude request for codex", null));

            CrossAgentResumeCompactor.Result result = new ResumeCommand()
                    .compactForTargetAgent(turns, "codex", "claude-code", tempDir);

            assertTrue(result.compacted());
            assertTrue(result.tokensAfter() <= result.targetBudget().exportTokenBudget());
            assertTrue(result.turns().get(0).content().contains("[Compacted cross-agent resume context]"));
            assertEquals("latest claude request for codex",
                    result.turns().get(result.turns().size() - 1).content());
        } finally {
            if (previous == null) {
                System.clearProperty("kompile.codex.model");
            } else {
                System.setProperty("kompile.codex.model", previous);
            }
        }
    }

    @Test
    void listFlagPrintsConversationsAndExits() throws Exception {
        String originalHome = System.getProperty("user.home");
        PrintStream originalOut = System.out;
        ByteArrayOutputStream captured = new ByteArrayOutputStream();
        System.setProperty("user.home", tempDir.toString());
        System.setOut(new PrintStream(captured, true, StandardCharsets.UTF_8));
        try {
            ChatHistory history = new ChatHistory("resume-list-session");
            history.open("(local)", "opencode", false);
            history.logUserMessage("hello resume list");
            history.close();

            int exitCode = new CommandLine(new ResumeCommand()).execute(
                    "--list", "--filter-source", "kompile", "--filter-agent", "opencode");

            String output = captured.toString(StandardCharsets.UTF_8);
            assertEquals(0, exitCode);
            assertTrue(output.contains("Saved conversations:"));
            assertTrue(output.contains("resume-list-session"));
            assertTrue(output.contains("agent=opencode"));
            assertTrue(output.contains("hello resume list"));
        } finally {
            System.setOut(originalOut);
            System.setProperty("user.home", originalHome);
        }
    }

    private List<String> buildAgentCommand(String agent, ConversationExporter.ExportResult exportResult) throws Exception {
        ResumeCommand command = new ResumeCommand();
        Method buildAgentCommand = ResumeCommand.class.getDeclaredMethod(
                "buildAgentCommand",
                String.class,
                ConversationExporter.ExportResult.class,
                boolean.class);
        buildAgentCommand.setAccessible(true);

        @SuppressWarnings("unchecked")
        List<String> args = (List<String>) buildAgentCommand.invoke(command, agent, exportResult, false);
        return args;
    }
}
