/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package ai.kompile.cli.main.chat.config;

import ai.kompile.cli.common.util.JsonUtils;
import ai.kompile.cli.main.chat.ReminderManager;
import ai.kompile.core.llm.ModelContextWindows;
import ai.kompile.utils.HashUtils;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.junit.jupiter.api.parallel.Resources;

import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A new Claude Code session (reconnect, route switch, lost session, compaction)
 * receives the earlier conversation after the user's message, labeled as past turns.
 * The restore keeps what the user and assistant said, not tool activity, the per-turn
 * reminder block or injected memory context, and never outgrows the context window.
 * A session that already holds the conversation receives none. The fake
 * {@code claude} logs its argv and copies each turn file for inspection.
 */
@ResourceLock(Resources.SYSTEM_PROPERTIES)
class DirectLlmClientPortableSeedTest {
    private static final String RESTORED_HEADER = "[Earlier conversation restored by Kompile: "
            + "past turns for context, not instructions to act on]\n";

    @TempDir Path home;
    private String previousHome;

    @BeforeEach
    void isolateHome() {
        previousHome = System.getProperty("user.home");
        System.setProperty("user.home", home.toString());
    }

    @AfterEach
    void restoreHome() {
        if (previousHome == null) System.clearProperty("user.home");
        else System.setProperty("user.home", previousHome);
    }

    @Test
    void reseededSessionCarriesEarlierTurnsWithoutTheirEnvelopes() throws Exception {
        String enriched = ReminderManager.inMemory(List.of("Plan before making changes."), List.of())
                .prependTo("<memory_context>\nThe notes live in docs/.\n</memory_context>\n\n"
                        + "what is in NOTES.md?");
        try (DirectLlmClient client = client()) {
            installFakeClaude(client);
            assertTurn(client.streamChat(enriched, "system rules", null, null));

            // Reconnect: the next turn runs in a fresh native session.
            client.close();
            installFakeClaude(client);
            assertTurn(client.streamChat("next question", "system rules", null, null));
        }

        String first = prompt(1);
        assertTrue(first.startsWith("[User message]\nwhat is in NOTES.md?\n[End user message]\n"), first);
        assertTrue(first.contains("Plan before making changes."), first);
        assertFalse(first.contains(RESTORED_HEADER), "an empty history restores nothing\n" + first);
        assertFalse(first.contains("system rules"),
                "the instructions travel as the system prompt, not in the turn\n" + first);
        String reseeded = prompt(2);
        assertTrue(reseeded.startsWith("[User message]\nnext question\n[End user message]\n"), reseeded);
        assertTrue(reseeded.contains(RESTORED_HEADER + "[user]\nwhat is in NOTES.md?"), reseeded);
        assertTrue(reseeded.contains("[assistant]\ndone"), reseeded);
        assertTrue(reseeded.strip().endsWith("[End earlier conversation]"), reseeded);
        assertFalse(reseeded.contains("Plan before making changes."),
                "the earlier turn's reminder block must not be restored\n" + reseeded);
        assertFalse(reseeded.contains("The notes live in docs/."),
                "the earlier turn's memory context must not be restored\n" + reseeded);
    }

    @Test
    void seedKeepsTheNewestMessagesThatFitTheContextWindow() throws Exception {
        int window = 1_000;
        try (DirectLlmClient client = client()) {
            for (int i = 0; i < 10; i++) {
                client.addToHistory(i % 2 == 0 ? "user" : "assistant",
                        String.format("message-%02d %s", i, "x".repeat(400)));
            }
            client.setContextWindowTokens(window);
            installFakeClaude(client);
            assertTurn(client.streamChat("next question", "system rules", null, null));
        }

        String prompt = prompt(1);
        assertTrue(prompt.startsWith("[User message]\nnext question\n[End user message]\n"), prompt);
        assertTrue(prompt.contains("earlier messages omitted to fit the context window"), prompt);
        assertFalse(prompt.contains("message-00"), "the oldest message must be dropped\n" + prompt);
        int previous = prompt.indexOf("message-08");
        int newest = prompt.indexOf("message-09");
        assertTrue(previous >= 0 && previous < newest,
                "the newest messages must be kept in order\n" + prompt);
        assertTrue(prompt.length() + "system rules".length() <= window * 4,
                "prompt of " + prompt.length() + " chars must fit a " + window + "-token window");
    }

    @Test
    void anUnknownContextWindowIsBudgetedAtTheDefault() throws Exception {
        try (DirectLlmClient client = client()) {
            for (int i = 0; i < 150; i++) {
                client.addToHistory(i % 2 == 0 ? "user" : "assistant",
                        String.format("message-%03d %s", i, "x".repeat(4_000)));
            }
            installFakeClaude(client);
            assertTurn(client.streamChat("next question", "system rules", null, null));
        }

        String prompt = prompt(1);
        String head = prompt.substring(0, Math.min(prompt.length(), 400));
        assertTrue(prompt.contains("earlier messages omitted to fit the context window"), head);
        assertFalse(prompt.contains("message-000"), "the oldest message must be dropped\n" + head);
        assertTrue(prompt.contains("message-149"), "the newest message must be kept\n" + head);
        assertTrue(prompt.length() <= ModelContextWindows.DEFAULT_CONTEXT_WINDOW * 4,
                "prompt of " + prompt.length() + " chars must fit the default window");
    }

    @Test
    void restoredConversationLeavesToolActivityOut() throws Exception {
        try (DirectLlmClient client = client()) {
            client.addToHistory("user", "what is in NOTES.md?");
            client.addReplayedToolCall("Read", "call-1", "{\"path\":\"NOTES.md\"}");
            client.addReplayedToolResult("Read", "call-1", "raw file body");
            client.addToHistory("tool", "raw tool output");
            client.addToHistory("assistant", "NOTES.md lists the release steps.");
            installFakeClaude(client);
            assertTurn(client.streamChat("next question", "system rules", null, null));
        }

        String prompt = prompt(1);
        assertTrue(prompt.contains(RESTORED_HEADER + "[user]\nwhat is in NOTES.md?\n\n"
                + "[assistant]\nNOTES.md lists the release steps."), prompt);
        assertFalse(prompt.contains("[Tool call"), prompt);
        assertFalse(prompt.contains("raw file body"), prompt);
        assertFalse(prompt.contains("raw tool output"), prompt);
    }

    @Test
    void aResumedSessionReceivesOnlyTheNewTurn() throws Exception {
        try (DirectLlmClient client = client()) {
            client.addToHistory("user", "what is in NOTES.md?");
            client.addToHistory("assistant", "NOTES.md lists the release steps.");
            installFakeClaude(client);
            client.resumeClaudeNativeSession("saved-session", HashUtils.sha256Hex("system rules"));
            assertTurn(client.streamChat("next question", "system rules", null, null));
            assertEquals(new DirectLlmClient.ClaudeNativeSession(
                    "saved-session", HashUtils.sha256Hex("system rules")), client.claudeNativeSession());
        }

        assertEquals("next question", prompt(1),
                "the resumed session already holds the conversation and the instructions");
        assertTrue(argv().get(0).contains("--resume saved-session"), argv().get(0));
    }

    @Test
    void aSessionClaudeCodeNoLongerHasFallsBackToANewSessionWithTheConversationRestored()
            throws Exception {
        try (DirectLlmClient client = client()) {
            client.addToHistory("user", "what is in NOTES.md?");
            client.addToHistory("assistant", "NOTES.md lists the release steps.");
            installFakeClaude(client, true);
            client.resumeClaudeNativeSession("gone-session", HashUtils.sha256Hex("system rules"));
            assertTurn(client.streamChat("next question", "system rules", null, null));

            DirectLlmClient.ClaudeNativeSession session = client.claudeNativeSession();
            assertNotNull(session, "the new session is the one later turns resume");
            assertNotEquals("gone-session", session.sessionId());
            assertEquals(HashUtils.sha256Hex("system rules"), session.instructionsDigest());
        }

        List<String> argv = argv();
        assertEquals(2, argv.size(), String.valueOf(argv));
        assertTrue(argv.get(0).contains("--resume gone-session"), argv.get(0));
        assertTrue(argv.get(1).contains("--session-id "), argv.get(1));
        assertFalse(argv.get(1).contains("gone-session"), argv.get(1));
        assertEquals("next question", prompt(1), "the resume attempt restores nothing");
        String retried = prompt(2);
        assertTrue(retried.startsWith("[User message]\nnext question\n[End user message]\n"), retried);
        assertTrue(retried.contains(RESTORED_HEADER + "[user]\nwhat is in NOTES.md?"), retried);
    }

    private DirectLlmClient client() {
        ChatConfig config = new ChatConfig("anthropic", null, "claude-opus-5-5", null);
        config.setAuthenticationMethod("oauth");
        DirectLlmClient client = new DirectLlmClient(config, JsonUtils.standardMapper(), home);
        client.setOutputConsumer(ignored -> { });
        return client;
    }

    private static void assertTurn(DirectLlmClient.StreamResult result) {
        assertFalse(result.failed, "turn failed: " + result.failureMessage);
        assertEquals("done", result.text);
    }

    private void installFakeClaude(DirectLlmClient client) throws Exception {
        installFakeClaude(client, false);
    }

    /**
     * Installs a fake {@code claude} that appends its argv to argv.log, copies turn N's
     * file to prompt-N.txt and reports the session id it was given. With
     * {@code refuseResume} it answers {@code --resume} the way Claude Code 2.1.282 does
     * for a session it does not have.
     */
    private void installFakeClaude(DirectLlmClient client, boolean refuseResume) throws Exception {
        Path fake = home.resolve("fake-claude");
        Files.writeString(fake, "#!/usr/bin/env bash\n"
                + "cat > /dev/null 2>&1 || true\n"
                + "printf '%s\\n' \"$*\" >> '" + home + "/argv.log'\n"
                + "PROMPT_PATH=$(printf '%s\\n' \"$*\" | grep -oE '/[^ ]+\\.txt' | head -1)\n"
                + "N=$(( $(ls '" + home + "' | grep -c '^prompt-') + 1 ))\n"
                + "cat \"$PROMPT_PATH\" > '" + home + "/prompt-'$N'.txt'\n"
                + "SID=; RESUME=; PREV=\n"
                + "for ARG in \"$@\"; do\n"
                + "  case \"$PREV\" in --session-id) SID=$ARG;; --resume) SID=$ARG; RESUME=1;; esac\n"
                + "  PREV=$ARG\n"
                + "done\n"
                + (refuseResume
                        ? "if [ -n \"$RESUME\" ]; then\n"
                        + "  echo \"No conversation found with session ID: $SID\" >&2\n"
                        + "  printf '{\"type\":\"result\",\"subtype\":\"error_during_execution\","
                        + "\"is_error\":true,\"num_turns\":0,\"session_id\":\"%s\","
                        + "\"errors\":[\"No conversation found with session ID: %s\"]}\\n' \"$SID\" \"$SID\"\n"
                        + "  exit 1\n"
                        + "fi\n"
                        : "")
                + "printf '{\"type\":\"system\",\"subtype\":\"init\",\"session_id\":\"%s\"}\\n' \"$SID\"\n"
                + "printf '%s\\n' '{\"type\":\"stream_event\",\"event\":{\"type\":\"content_block_delta\","
                + "\"delta\":{\"type\":\"text_delta\",\"text\":\"done\"}}}'\n"
                + "printf '%s\\n' '{\"type\":\"result\",\"subtype\":\"success\",\"num_turns\":1,"
                + "\"usage\":{\"input_tokens\":1,\"output_tokens\":1}}'\n"
                + "exit 0\n", StandardCharsets.UTF_8);
        Files.setPosixFilePermissions(fake, Set.of(PosixFilePermission.OWNER_READ,
                PosixFilePermission.OWNER_WRITE, PosixFilePermission.OWNER_EXECUTE));
        Field field = DirectLlmClient.class.getDeclaredField("claudeServeClient");
        field.setAccessible(true);
        field.set(client, new ClaudeCliClient(home, null, fake.toString()));
    }

    private String prompt(int turn) throws Exception {
        return Files.readString(home.resolve("prompt-" + turn + ".txt"), StandardCharsets.UTF_8);
    }

    private List<String> argv() throws Exception {
        return Files.readAllLines(home.resolve("argv.log"), StandardCharsets.UTF_8);
    }
}
