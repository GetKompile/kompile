package ai.kompile.cli.main.chat.mcp;

import ai.kompile.cli.main.chat.ChatUiSession;
import ai.kompile.cli.main.chat.tools.BackgroundProcessManager;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.junit.jupiter.api.parallel.Resources;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;

@ResourceLock(Resources.SYSTEM_ERR)
class McpDiagnosticsTest {

    @Test
    void lineGoesToTheCallersChatAndAnUnboundCallerIsTheLegacyChat() {
        List<String> legacy = new ArrayList<>();
        List<String> isolated = new ArrayList<>();
        ChatUiSession session = new ChatUiSession();
        Runnable legacyCleanup = McpDiagnostics.installSink(ChatUiSession.current(), legacy::add);
        // Registered last, so it must not capture unbound lines as "the newest chat".
        Runnable isolatedCleanup = McpDiagnostics.installSink(session, isolated::add);
        try {
            McpDiagnostics.log("[MCP] legacy line");
            try (var ignored = session.bind()) {
                McpDiagnostics.log("\u001B[33m[MCP] styled isolated line\u001B[0m");
            }
            assertEquals(List.of("[MCP] legacy line"), legacy);
            assertEquals(List.of("\u001B[33m[MCP] styled isolated line\u001B[0m"), isolated);
        } finally {
            isolatedCleanup.run();
            legacyCleanup.run();
            session.close();
        }
    }

    @Test
    void newestSinkOfTheOwnerWinsAndCleanupIsIdempotent() {
        List<String> first = new ArrayList<>();
        List<String> second = new ArrayList<>();
        ChatUiSession session = new ChatUiSession();
        Runnable firstCleanup = McpDiagnostics.installSink(session, first::add);
        Runnable secondCleanup = McpDiagnostics.installSink(session, second::add);
        try (var ignored = session.bind()) {
            McpDiagnostics.log("newest");
            secondCleanup.run();
            secondCleanup.run();
            McpDiagnostics.log("restored");
            assertEquals(List.of("newest"), second);
            assertEquals(List.of("restored"), first);
        } finally {
            secondCleanup.run();
            firstCleanup.run();
            session.close();
        }
    }

    @Test
    void unmatchedOrFailedSinksKeepTheStderrRoute() {
        ChatUiSession session = new ChatUiSession();
        ChatUiSession other = new ChatUiSession();
        List<String> otherLines = new ArrayList<>();
        Runnable otherCleanup = McpDiagnostics.installSink(other, otherLines::add);
        Runnable noOwner = McpDiagnostics.installSink(null, otherLines::add);
        Runnable noSink = McpDiagnostics.installSink(session, null);
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        PrintStream previous = System.err;
        try (PrintStream capture = new PrintStream(err, true, StandardCharsets.UTF_8);
             var ignored = session.bind()) {
            System.setErr(capture);
            McpDiagnostics.log(null);
            McpDiagnostics.log("  ");
            McpDiagnostics.log("no sink");
            Runnable failing = McpDiagnostics.installSink(session, line -> {
                throw new IllegalStateException("closing");
            });
            try {
                assertDoesNotThrow(() -> McpDiagnostics.log("failed sink"));
            } finally {
                failing.run();
            }
            McpDiagnostics.log("after cleanup");
            assertEquals(String.join(System.lineSeparator(),
                    "no sink", "failed sink", "after cleanup", ""),
                    err.toString(StandardCharsets.UTF_8));
            assertTrue(otherLines.isEmpty());
        } finally {
            System.setErr(previous);
            noSink.run();
            noOwner.run();
            otherCleanup.run();
            session.close();
            other.close();
        }
    }

    @Test
    void processLogKeepsOnePlainEntryAndEchoesOnlyWhileOffScreen(@TempDir Path workDir) throws Exception {
        // A project .kompile keeps the durable log inside the temp dir.
        Files.createDirectories(workDir.resolve(".kompile"));
        BackgroundProcessManager processes =
                new BackgroundProcessManager("mcp-process-log-" + System.nanoTime(), workDir);
        ChatUiSession session = new ChatUiSession();
        AtomicBoolean offScreen = new AtomicBoolean();
        Runnable cleanup = McpDiagnostics.installProcessLog(session, processes, offScreen::get);
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        PrintStream previous = System.err;
        try (PrintStream capture = new PrintStream(err, true, StandardCharsets.UTF_8);
             var ignored = session.bind()) {
            System.setErr(capture);
            McpDiagnostics.log("\u001B[32m[MCP] first\u001B[0m");
            McpDiagnostics.log("[MCP] second");

            List<BackgroundProcessManager.ProcessEntry> running = processes.listRunning();
            assertEquals(1, running.size());
            BackgroundProcessManager.ProcessEntry entry = running.get(0);
            assertEquals(BackgroundProcessManager.ProcessKind.MCP, entry.getKind());
            assertTrue(entry.isVirtual());
            assertEquals("MCP tool bridge log", entry.getDescription());
            assertEquals("[MCP] first\n[MCP] second", processes.readOutput(entry.getId(), 10));
            assertEquals("", err.toString(StandardCharsets.UTF_8));

            // Stopped from the browser: the next line opens a fresh entry.
            assertTrue(processes.kill(entry.getId()));
            offScreen.set(true);
            McpDiagnostics.log("\u001B[33m[MCP] after stop\u001B[0m");

            List<BackgroundProcessManager.ProcessEntry> reopened = processes.listRunning();
            assertEquals(1, reopened.size());
            assertNotEquals(entry.getId(), reopened.get(0).getId());
            assertEquals("[MCP] after stop", processes.readOutput(reopened.get(0).getId(), 10));
            assertEquals("\u001B[33m[MCP] after stop\u001B[0m" + System.lineSeparator(),
                    err.toString(StandardCharsets.UTF_8));
        } finally {
            System.setErr(previous);
            cleanup.run();
            session.close();
            processes.close();
        }
    }

    @Test
    void closedProcessLogSendsLaterLinesToStderrWithoutRecreatingItsDirectory(@TempDir Path workDir)
            throws Exception {
        // A chat whose manager is closed but whose sink is still registered (a test that
        // never closes its chat) must not bring back a log directory deleted with it.
        Path projectKompile = Files.createDirectories(workDir.resolve(".kompile"));
        BackgroundProcessManager processes =
                new BackgroundProcessManager("mcp-closed-log-" + System.nanoTime(), workDir);
        ChatUiSession session = new ChatUiSession();
        Runnable cleanup = McpDiagnostics.installProcessLog(session, processes, () -> false);
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        PrintStream previous = System.err;
        try (PrintStream capture = new PrintStream(err, true, StandardCharsets.UTF_8);
             var ignored = session.bind()) {
            System.setErr(capture);
            McpDiagnostics.log("[MCP] while open");
            processes.close();
            try (var walk = Files.walk(projectKompile)) {
                walk.sorted((a, b) -> b.compareTo(a)).forEach(path -> path.toFile().delete());
            }
            assertFalse(Files.exists(projectKompile), "precondition: the log directory is gone");

            McpDiagnostics.log("[MCP] after close");

            assertEquals("[MCP] after close" + System.lineSeparator(), err.toString(StandardCharsets.UTF_8));
            assertFalse(Files.exists(projectKompile));
        } finally {
            System.setErr(previous);
            cleanup.run();
            session.close();
            processes.close();
        }
    }
}
