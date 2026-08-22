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
import java.nio.file.Files;
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
    void piResumeLoadsBundledExtensionAfterExecutable() throws Exception {
        String previousAdapter = System.getProperty("kompile.pi.adapter.path");
        Path adapter = tempDir.resolve("pi-adapter");
        Files.createDirectories(adapter);
        try {
            System.setProperty("kompile.pi.adapter.path", adapter.toString());
            ConversationExporter.ExportResult exportResult = new ConversationExporter.ExportResult(
                    "resume-session",
                    "pi",
                    tempDir.resolve("session.jsonl"),
                    "pi --session resume-session",
                    tempDir);

            List<String> args = buildAgentCommand("pi", exportResult, true);

            assertEquals("pi", args.get(0));
            assertEquals("-e", args.get(1));
            assertEquals(adapter.toString(), args.get(2));
            assertTrue(args.contains("--session"));
            assertFalse(args.contains("--resume"));
        } finally {
            if (previousAdapter == null) {
                System.clearProperty("kompile.pi.adapter.path");
            } else {
                System.setProperty("kompile.pi.adapter.path", previousAdapter);
            }
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
    void automaticResumeTargetKeepsLiteralStandardChatInKompile() {
        assertTrue(ResumeCommand.shouldResumeStandardChat(
                "123e4567-e89b-12d3-a456-426614174000", "auto", "coder", null));
        assertTrue(ResumeCommand.shouldResumeStandardChat(
                "cli-standard", "auto", "coder", null));
        assertTrue(ResumeCommand.shouldResumeStandardChat(
                "cli-standard", "kompile", "", null));
        assertTrue(ResumeCommand.shouldResumeStandardChat(
                "fpna-20260809-0826", "auto", "coder", null));
        assertFalse(ResumeCommand.shouldResumeStandardChat(
                "cli-standard", "claude", "coder", null));
        assertFalse(ResumeCommand.shouldResumeStandardChat(
                "cli-wrapper", "auto", "codex", null));
        assertFalse(ResumeCommand.shouldResumeStandardChat(
                "cli-wrapper", "auto", "coder", "native-session"));
        assertFalse(ResumeCommand.shouldResumeStandardChat(
                (ChatHistory.ConversationSummary) null, "auto", null),
                "an external UUID without a Kompile transcript must remain eligible for native resume");
        assertTrue(ResumeCommand.shouldResumeStandardChat(
                new ChatHistory.ConversationSummary(
                        "123e4567-e89b-12d3-a456-426614174000",
                        "saved standard chat", "", "coder", 0L),
                "auto", null));
        assertEquals("claude", ResumeCommand.effectiveTargetAgent("auto"));
        assertEquals("codex", ResumeCommand.effectiveTargetAgent("codex"));
    }

    @Test
    void standardChatAppearsInListUnderKompileAgent() throws Exception {
        String originalHome = System.getProperty("user.home");
        PrintStream originalOut = System.out;
        ByteArrayOutputStream captured = new ByteArrayOutputStream();
        System.setProperty("user.home", tempDir.resolve("standard-list-home").toString());
        System.setOut(new PrintStream(captured, true, StandardCharsets.UTF_8));
        try {
            ChatHistory history = new ChatHistory("custom-standard-list");
            history.open("(local)", null, false);
            history.logUserMessage("literal standard transcript");
            history.close();

            int exitCode = new CommandLine(new ResumeCommand()).execute(
                    "--list", "--filter-source", "kompile", "--filter-agent", "kompile");

            String output = captured.toString(StandardCharsets.UTF_8);
            assertEquals(0, exitCode);
            assertTrue(output.contains("custom-standard-list"));
            assertTrue(output.contains("agent=kompile"));
            assertTrue(output.contains("literal standard transcript"));
        } finally {
            System.setOut(originalOut);
            System.setProperty("user.home", originalHome);
        }
    }

    @Test
    void listFlagScopesKompileSessionsToCurrentDirectory() throws Exception {
        String originalHome = System.getProperty("user.home");
        String originalUserDir = System.getProperty("user.dir");
        PrintStream originalOut = System.out;
        ByteArrayOutputStream captured = new ByteArrayOutputStream();
        Path home = tempDir.resolve("scoped-list-home");
        Path currentProject = tempDir.resolve("project-a").toAbsolutePath().normalize();
        Path otherProject = tempDir.resolve("project-b").toAbsolutePath().normalize();
        Files.createDirectories(currentProject);
        Files.createDirectories(otherProject);
        System.setProperty("user.home", home.toString());
        System.setProperty("user.dir", currentProject.toString());
        System.setOut(new PrintStream(captured, true, StandardCharsets.UTF_8));
        try {
            ChatHistory local = new ChatHistory("local-project-session");
            local.open("(local)", "coder", false, otherProject);
            local.logUserMessage("""
                    # Enforcer-Controlled Task
                    ## User Prompt
                    conversation before moving projects
                    Produce the response now
                    """);
            local.close();
            ChatHistory resumedLocally = new ChatHistory("local-project-session");
            resumedLocally.open("(local)", "coder", false, currentProject);
            resumedLocally.logUserMessage("local project conversation");
            resumedLocally.close();

            ChatHistory remote = new ChatHistory("other-project-session");
            remote.open("(local)", "coder", false, otherProject);
            remote.logUserMessage("other project conversation");
            remote.close();

            ChatHistory legacy = new ChatHistory("legacy-without-cwd");
            legacy.open("(local)", "coder", false, (Path) null);
            legacy.logUserMessage("legacy global conversation");
            legacy.close();

            Path relativeCwd = legacy.getTranscriptFile().resolveSibling("relative-cwd.txt");
            Files.writeString(relativeCwd, """
                    ──── Conversation: relative-cwd ────
                    Started: 2026-08-22 12:00:00
                    Server:  (local)
                    Agent:   coder
                    RAG:     disabled
                    CWD:     .

                    ──────────────────────────────────

                    > relative cwd conversation
                    """, StandardCharsets.UTF_8);

            assertEquals(List.of("local-project-session"),
                    ChatHistory.listResumableConversations(currentProject).stream()
                            .map(ChatHistory.ConversationSummary::sessionId)
                            .toList());
            assertEquals(4, ChatHistory.listResumableConversations().size(),
                    "The unscoped API must retain global and legacy explicit-ID discovery");
            assertTrue(ChatHistory.resolveWorkingDirectory("relative-cwd").isEmpty(),
                    "Relative legacy CWD metadata must not be attributed to the caller's project");

            int exitCode = new CommandLine(new ResumeCommand()).execute(
                    "--list", "--filter-source", "kompile");

            String output = captured.toString(StandardCharsets.UTF_8);
            assertEquals(0, exitCode);
            assertTrue(output.contains("local-project-session"));
            assertFalse(output.contains("other-project-session"));
            assertFalse(output.contains("legacy-without-cwd"));
            assertFalse(output.contains("relative-cwd"));
            assertEquals(otherProject,
                    ChatHistory.resolveWorkingDirectory("other-project-session").orElseThrow());
        } finally {
            System.setOut(originalOut);
            System.setProperty("user.home", originalHome);
            System.setProperty("user.dir", originalUserDir);
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

            ChatHistory subagent = new ChatHistory("subagent-opencode-hidden");
            subagent.open("(local)", "opencode", false);
            subagent.logUserMessage("internal delegated work");
            subagent.close();

            ChatHistory legacySubagent = new ChatHistory("test-logsubagent-opencode-hidden");
            legacySubagent.open("(local)", "opencode", false);
            legacySubagent.logUserMessage("legacy delegated work");
            legacySubagent.close();

            int exitCode = new CommandLine(new ResumeCommand()).execute(
                    "--list", "--filter-source", "kompile", "--filter-agent", "opencode");

            String output = captured.toString(StandardCharsets.UTF_8);
            assertEquals(0, exitCode);
            assertTrue(output.contains("Saved conversations:"));
            assertTrue(output.contains("resume-list-session"));
            assertTrue(output.contains("agent=opencode"));
            assertTrue(output.contains("hello resume list"));
            assertFalse(output.contains("subagent-opencode-hidden"));
            assertFalse(output.contains("internal delegated work"));
            assertFalse(output.contains("test-logsubagent-opencode-hidden"));
            assertFalse(output.contains("legacy delegated work"));
        } finally {
            System.setOut(originalOut);
            System.setProperty("user.home", originalHome);
        }
    }

    @Test
    void viewFlagParsesAndRendersKompileTranscript() throws Exception {
        String originalHome = System.getProperty("user.home");
        PrintStream originalOut = System.out;
        ByteArrayOutputStream captured = new ByteArrayOutputStream();
        System.setProperty("user.home", tempDir.toString());
        System.setOut(new PrintStream(captured, true, StandardCharsets.UTF_8));
        try {
            ChatHistory history = new ChatHistory("resume-view-session");
            history.open("(local)", "coder", false);
            history.logUserMessage("prior user question");
            history.logAssistantMessage("previous **answer** with `code`", 0, 0);
            history.close();

            int exitCode = new CommandLine(new ResumeCommand()).execute(
                    "--session-id", "resume-view-session", "--view");

            String output = captured.toString(StandardCharsets.UTF_8);
            assertEquals(0, exitCode);
            assertTrue(output.contains("You:"));
            assertTrue(output.contains("Assistant:"));
            assertTrue(output.contains("previous"));
            assertFalse(output.contains("> prior user question"));
            assertFalse(output.contains("< previous **answer** with `code`"));
        } finally {
            System.setOut(originalOut);
            System.setProperty("user.home", originalHome);
        }
    }

    private List<String> buildAgentCommand(String agent, ConversationExporter.ExportResult exportResult) throws Exception {
        return buildAgentCommand(agent, exportResult, false);
    }

    private List<String> buildAgentCommand(String agent, ConversationExporter.ExportResult exportResult,
                                           boolean toolsInjected) throws Exception {
        ResumeCommand command = new ResumeCommand();
        Method buildAgentCommand = ResumeCommand.class.getDeclaredMethod(
                "buildAgentCommand",
                String.class,
                ConversationExporter.ExportResult.class,
                boolean.class);
        buildAgentCommand.setAccessible(true);

        @SuppressWarnings("unchecked")
        List<String> args = (List<String>) buildAgentCommand.invoke(command, agent, exportResult, toolsInjected);
        return args;
    }
}
