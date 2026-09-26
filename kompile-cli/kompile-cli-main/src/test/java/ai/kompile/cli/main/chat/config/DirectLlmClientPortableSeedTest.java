/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package ai.kompile.cli.main.chat.config;

import ai.kompile.cli.common.util.JsonUtils;
import ai.kompile.cli.main.chat.ReminderManager;
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
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A fresh Claude Code session (reconnect, route switch, resume, compaction) is seeded
 * with the portable conversation history. The seed restores what was said, not the
 * per-turn reminder block or injected memory context, and never outgrows the context
 * window. The fake {@code claude} copies each turn's prompt file for inspection.
 */
@ResourceLock(Resources.SYSTEM_PROPERTIES)
class DirectLlmClientPortableSeedTest {
    private static final String SEED_HEADER = "[Portable conversation context restored by Kompile]";

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
        assertTrue(first.contains("Plan before making changes."), first);
        assertFalse(first.contains(SEED_HEADER), "an empty history seeds nothing\n" + first);
        String reseeded = prompt(2);
        assertTrue(reseeded.contains(SEED_HEADER), reseeded);
        assertTrue(reseeded.contains("[user]\nwhat is in NOTES.md?"), reseeded);
        assertTrue(reseeded.contains("[assistant]\ndone"), reseeded);
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
        assertTrue(prompt.contains("earlier messages omitted to fit the context window"), prompt);
        assertFalse(prompt.contains("message-00"), "the oldest message must be dropped\n" + prompt);
        int previous = prompt.indexOf("message-08");
        int newest = prompt.indexOf("message-09");
        assertTrue(previous >= 0 && previous < newest,
                "the newest messages must be kept in order\n" + prompt);
        assertTrue(prompt.endsWith("next question"), prompt);
        assertTrue(prompt.length() <= window * 4,
                "prompt of " + prompt.length() + " chars must fit a " + window + "-token window");
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

    /** Installs a fake {@code claude} that copies the prompt file of turn N to prompt-N.txt. */
    private void installFakeClaude(DirectLlmClient client) throws Exception {
        Path fake = home.resolve("fake-claude");
        Files.writeString(fake, "#!/usr/bin/env bash\n"
                + "cat > /dev/null 2>&1 || true\n"
                + "PROMPT_PATH=$(printf '%s\\n' \"$*\" | grep -oE '/[^ ]+\\.txt' | head -1)\n"
                + "N=$(( $(ls '" + home + "' | grep -c '^prompt-') + 1 ))\n"
                + "cat \"$PROMPT_PATH\" > '" + home + "/prompt-'$N'.txt'\n"
                + "echo '{\"type\":\"system\",\"subtype\":\"init\",\"session_id\":\"s\"}'\n"
                + "printf '%s\\n' '{\"type\":\"stream_event\",\"event\":{\"type\":\"content_block_delta\","
                + "\"delta\":{\"type\":\"text_delta\",\"text\":\"done\"}}}'\n"
                + "printf '%s\\n' '{\"type\":\"result\",\"subtype\":\"success\","
                + "\"usage\":{\"input_tokens\":1,\"output_tokens\":1}}'\n"
                + "exit 0\n", StandardCharsets.UTF_8);
        Files.setPosixFilePermissions(fake, Set.of(PosixFilePermission.OWNER_READ,
                PosixFilePermission.OWNER_WRITE, PosixFilePermission.OWNER_EXECUTE));
        Field field = DirectLlmClient.class.getDeclaredField("claudeServeClient");
        field.setAccessible(true);
        field.set(client, new ClaudeCliClient(home, "claude-native-session", fake.toString()));
    }

    private String prompt(int turn) throws Exception {
        return Files.readString(home.resolve("prompt-" + turn + ".txt"), StandardCharsets.UTF_8);
    }
}
