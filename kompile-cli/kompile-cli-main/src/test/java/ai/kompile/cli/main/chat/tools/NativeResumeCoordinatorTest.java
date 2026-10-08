/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */
package ai.kompile.cli.main.chat.tools;

import ai.kompile.cli.main.chat.ChatHistory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NativeResumeCoordinatorTest {

    @TempDir
    Path tempDir;

    @Test
    void recreatesMissingPiSessionAndIsIdempotent() throws Exception {
        String originalHome = System.getProperty("user.home");
        System.setProperty("user.home", tempDir.resolve("home").toString());
        try {
            ChatHistory history = new ChatHistory("restore-pi-session");
            history.open("(local)", "pi", false, tempDir);
            history.logUserMessage("restore this conversation");
            history.logAssistantMessage("conversation restored", 0, 0);
            history.close();

            List<ChatHistory.Turn> turns = List.of(
                    new ChatHistory.Turn("user", "restore this conversation"),
                    new ChatHistory.Turn("assistant", "conversation restored"));

            NativeResumeCoordinator.Result recreated = NativeResumeCoordinator.ensure(
                    "restore-pi-session", "pi", "missing-pi-id", turns, "pi", tempDir);

            assertEquals(NativeResumeCoordinator.Outcome.RECREATED, recreated.outcome());
            assertEquals("missing-pi-id", recreated.nativeSessionId());
            assertTrue(Files.isRegularFile(recreated.exportResult().getSessionPath()));

            NativeResumeCoordinator.Result present = NativeResumeCoordinator.ensure(
                    "restore-pi-session", "pi", "missing-pi-id", turns, "pi", tempDir);

            assertEquals(NativeResumeCoordinator.Outcome.PRESENT, present.outcome());
            assertEquals("missing-pi-id", present.nativeSessionId());
        } finally {
            System.setProperty("user.home", originalHome);
        }
    }

    @Test
    void missingCodexSessionFallsBackToFreshIdInsteadOfReusingStaleQueueIdentity() throws Exception {
        String originalHome = System.getProperty("user.home");
        String originalExecutable = System.getProperty("kompile.codex.executable");
        System.setProperty("user.home", tempDir.resolve("home-codex").toString());
        System.setProperty("kompile.codex.executable", "definitely-not-a-codex-binary");
        try {
            String staleId = "11111111-1111-4111-8111-111111111111";
            ChatHistory history = new ChatHistory("restore-codex-session");
            history.open("(local)", "codex", false, tempDir);
            history.logUserMessage("resume without the stale native queue");
            history.close();

            NativeResumeCoordinator.Result recreated = NativeResumeCoordinator.ensure(
                    "restore-codex-session",
                    "codex",
                    staleId,
                    List.of(new ChatHistory.Turn("user", "resume without the stale native queue")),
                    "codex",
                    tempDir);

            assertEquals(NativeResumeCoordinator.Outcome.RECREATED, recreated.outcome());
            assertNotEquals(staleId, recreated.nativeSessionId());
            assertTrue(Files.isRegularFile(recreated.exportResult().getSessionPath()));
            assertTrue(recreated.exportResult().getResumeCommand()
                    .endsWith(recreated.nativeSessionId()));
            assertEquals(recreated.nativeSessionId(),
                    ChatHistory.resolveNativeSessionId("restore-codex-session", "codex"));

            NativeResumeCoordinator.Result repeated = NativeResumeCoordinator.ensure(
                    "restore-codex-session",
                    "codex",
                    staleId,
                    List.of(new ChatHistory.Turn("user", "resume without the stale native queue")),
                    "codex",
                    tempDir);
            assertEquals(NativeResumeCoordinator.Outcome.PRESENT, repeated.outcome());
            assertEquals(recreated.nativeSessionId(), repeated.nativeSessionId(),
                    "A concurrent/stale caller must reuse the replacement recorded under the lock");
        } finally {
            System.setProperty("user.home", originalHome);
            if (originalExecutable == null) {
                System.clearProperty("kompile.codex.executable");
            } else {
                System.setProperty("kompile.codex.executable", originalExecutable);
            }
        }
    }

    /** A vendor switch hands the transcript to a framework as a new native session of its own. */
    @Test
    void seedStartsANewNativeSessionHoldingTheTranscript() throws Exception {
        String originalHome = System.getProperty("user.home");
        System.setProperty("user.home", tempDir.resolve("home-seed").toString());
        try {
            List<ChatHistory.Turn> turns = List.of(
                    new ChatHistory.Turn("user", "asked on another vendor"),
                    new ChatHistory.Turn("assistant", "answered on another vendor"));
            String first = NativeResumeCoordinator.seed("pi", turns, tempDir);
            String second = NativeResumeCoordinator.seed("PI", turns, tempDir);
            assertNotNull(first);
            assertNotEquals(first, second, "every switch-in starts its own session");
            assertTrue(Files.walk(tempDir.resolve("home-seed")).anyMatch(path -> Files.isRegularFile(path)
                    && path.getFileName().toString().contains(first)));

            assertThrows(java.io.IOException.class, () -> NativeResumeCoordinator.seed("pi", List.of(), tempDir));
            assertThrows(java.io.IOException.class, () -> NativeResumeCoordinator.seed("not-an-agent", turns, tempDir));
        } finally {
            System.setProperty("user.home", originalHome);
        }
    }

    @Test
    void generatedNativeIdIsRecordedForFutureResume() throws Exception {
        String originalHome = System.getProperty("user.home");
        System.setProperty("user.home", tempDir.resolve("home-generated").toString());
        try {
            ChatHistory history = new ChatHistory("restore-generated-session");
            history.open("(local)", "pi", false, tempDir);
            history.logUserMessage("generate a native id");
            history.close();

            NativeResumeCoordinator.Result recreated = NativeResumeCoordinator.ensure(
                    "restore-generated-session",
                    "pi",
                    null,
                    List.of(new ChatHistory.Turn("user", "generate a native id")),
                    "pi",
                    tempDir);

            assertNotNull(recreated.nativeSessionId());
            assertTrue(Files.readString(
                    Path.of(System.getProperty("user.home"), ".kompile", "conversations",
                            "restore-generated-session.txt"))
                    .contains("[harvested:" + recreated.nativeSessionId() + "]"));
            assertEquals(recreated.nativeSessionId(),
                    ChatHistory.resolveNativeSessionId("restore-generated-session", "pi"));
        } finally {
            System.setProperty("user.home", originalHome);
        }
    }
}
