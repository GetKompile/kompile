/*
 * Copyright 2026 Kompile Inc.
 * Licensed under the Apache License, Version 2.0.
 */
package ai.kompile.cli.main.chat;

import ai.kompile.cli.main.chat.config.ChatConfig;
import ai.kompile.cli.main.chat.tools.BackgroundProcessManager;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.junit.jupiter.api.parallel.Resources;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The {@code /model ladder} and {@code /model status} surface: the commands that
 * let a user express "if my primary runs out of quota, use these next".
 *
 * <p>These assert the persisted ladder file rather than log text alone, because
 * the point of the surface is that the choice survives the session.</p>
 */
class ChatModelLadderCommandTest {

    @TempDir
    Path tempDir;

    private String previousHome;
    private Path home;
    private Path project;

    private void isolate() throws Exception {
        previousHome = System.getProperty("user.home");
        home = tempDir.resolve("home");
        project = tempDir.resolve("project");
        Files.createDirectories(home.resolve(".kompile/conversations"));
        Files.createDirectories(project);
        System.setProperty("user.home", home.toString());
    }

    private void restore() {
        if (previousHome == null) System.clearProperty("user.home");
        else System.setProperty("user.home", previousHome);
        ChatCompleter.setActivity(null);
    }

    /** Run a router command with stdout captured, returning what it printed. */
    private String run(ChatRepl repl, ChatCommandRouter router, String command) {
        PrintStream original = System.out;
        ByteArrayOutputStream captured = new ByteArrayOutputStream();
        try {
            System.setOut(new PrintStream(captured, true, StandardCharsets.UTF_8));
            router.handleSlashCommand(command);
        } finally {
            System.setOut(original);
        }
        return captured.toString(StandardCharsets.UTF_8);
    }

    private ChatRepl newRepl(String session) {
        ChatConfig config = new ChatConfig("custom", null, "primary-model", "http://unused.invalid");
        return new ChatRepl(null, null, session, false, "default", false, config);
    }

    private static void close(ChatRepl repl) throws Exception {
        repl.close();
        field(repl, "processManager", BackgroundProcessManager.class).close();
    }

    @Test
    @ResourceLock(Resources.SYSTEM_PROPERTIES)
    void addWritesTheRungToTheSessionLadderFile() throws Exception {
        isolate();
        ChatRepl repl = null;
        try {
            String session = "ladder-add-" + System.nanoTime();
            repl = newRepl(session);
            ChatCommandRouter router = field(repl, "commandRouter", ChatCommandRouter.class);

            String printed = run(repl, router, "/model ladder add custom/backup-model");

            Path expected = SessionModelLadder.sessionPath(session);
            SessionModelLadder reloaded = SessionModelLadder.load(session, project);
            assertEquals(1, reloaded.rungs().size(),
                    "no persisted rung at " + expected
                            + " (exists=" + Files.exists(expected)
                            + ", printed=[" + printed + "])");
            assertEquals("backup-model", reloaded.rungs().get(0).model());
            assertEquals("custom", reloaded.rungs().get(0).provider());
        } finally {
            if (repl != null) close(repl);
            restore();
        }
    }

    @Test
    @ResourceLock(Resources.SYSTEM_PROPERTIES)
    void showListsRungsAndRefusesAnUnknownProvider() throws Exception {
        isolate();
        ChatRepl repl = null;
        try {
            String session = "ladder-show-" + System.nanoTime();
            repl = newRepl(session);
            ChatCommandRouter router = field(repl, "commandRouter", ChatCommandRouter.class);

            String empty = run(repl, router, "/model ladder");
            assertTrue(empty.contains("No rungs"), empty);

            String rejected = run(repl, router, "/model ladder add not-a-provider/model-x");
            assertTrue(rejected.contains("Unknown provider"), rejected);
            assertTrue(SessionModelLadder.load(session, project).isEmpty(),
                    "a rejected rung must not be persisted");

            run(repl, router, "/model ladder add custom/backup-model");
            String shown = run(repl, router, "/model ladder");
            assertTrue(shown.contains("backup-model"), shown);
        } finally {
            if (repl != null) close(repl);
            restore();
        }
    }

    @Test
    @ResourceLock(Resources.SYSTEM_PROPERTIES)
    void removeAndClearEditThePersistedLadder() throws Exception {
        isolate();
        ChatRepl repl = null;
        try {
            String session = "ladder-edit-" + System.nanoTime();
            repl = newRepl(session);
            ChatCommandRouter router = field(repl, "commandRouter", ChatCommandRouter.class);

            run(repl, router, "/model ladder add custom/a");
            run(repl, router, "/model ladder add custom/b");
            assertEquals(2, SessionModelLadder.load(session, project).rungs().size());

            run(repl, router, "/model ladder remove 1");
            SessionModelLadder afterRemove = SessionModelLadder.load(session, project);
            assertEquals(1, afterRemove.rungs().size());
            assertEquals("b", afterRemove.rungs().get(0).model());

            String outOfRange = run(repl, router, "/model ladder remove 9");
            assertTrue(outOfRange.contains("No rung"), outOfRange);

            run(repl, router, "/model ladder clear");
            assertTrue(SessionModelLadder.load(session, project).isEmpty());
        } finally {
            if (repl != null) close(repl);
            restore();
        }
    }

    @Test
    @ResourceLock(Resources.SYSTEM_PROPERTIES)
    void togglesPersistAcrossSessions() throws Exception {
        isolate();
        ChatRepl repl = null;
        try {
            String session = "ladder-toggle-" + System.nanoTime();
            repl = newRepl(session);
            ChatCommandRouter router = field(repl, "commandRouter", ChatCommandRouter.class);

            run(repl, router, "/model ladder off");
            assertFalse(SessionModelLadder.load(session, project).enabled());

            run(repl, router, "/model ladder on");
            assertTrue(SessionModelLadder.load(session, project).enabled());

            run(repl, router, "/model ladder ratelimit on");
            assertTrue(SessionModelLadder.load(session, project).failoverOnRateLimit());
        } finally {
            if (repl != null) close(repl);
            restore();
        }
    }

    @Test
    @ResourceLock(Resources.SYSTEM_PROPERTIES)
    void statusReportsTheActiveModel() throws Exception {
        isolate();
        ChatRepl repl = null;
        try {
            repl = newRepl("ladder-status-" + System.nanoTime());
            ChatCommandRouter router = field(repl, "commandRouter", ChatCommandRouter.class);

            String status = run(repl, router, "/model status");
            assertTrue(status.contains("primary-model"), status);
            assertTrue(status.contains("custom"), status);
        } finally {
            if (repl != null) close(repl);
            restore();
        }
    }

    @Test
    @ResourceLock(Resources.SYSTEM_PROPERTIES)
    void aSessionLadderOverridesTheProjectDefault() throws Exception {
        isolate();
        ChatRepl repl = null;
        try {
            // The project layer supplies one rung...
            Path projectLadder = project.resolve(".kompile/model-ladder.json");
            Files.createDirectories(projectLadder.getParent());
            Files.writeString(projectLadder, "{\"rungs\":[{\"model\":\"project-model\"}]}");

            // ...and adding this session's own rung replaces that layer entirely.
            String session = "ladder-layer-" + System.nanoTime();
            repl = newRepl(session);
            ChatCommandRouter router = field(repl, "commandRouter", ChatCommandRouter.class);
            run(repl, router, "/model ladder add custom/session-model");

            SessionModelLadder effective = SessionModelLadder.load(session, project);
            assertEquals(1, effective.rungs().size());
            assertEquals("session-model", effective.rungs().get(0).model(),
                    "the most specific layer is the session's, not the project's");
        } finally {
            if (repl != null) close(repl);
            restore();
        }
    }

    private static <T> T field(Object target, String name, Class<T> type) throws Exception {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return type.cast(field.get(target));
    }
}
