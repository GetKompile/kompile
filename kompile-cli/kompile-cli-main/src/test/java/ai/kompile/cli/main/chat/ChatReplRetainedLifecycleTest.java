package ai.kompile.cli.main.chat;

import ai.kompile.cli.main.chat.config.ChatConfig;
import ai.kompile.cli.main.chat.config.DirectLlmClient;
import ai.kompile.cli.main.chat.harness.HarnessConfig;
import ai.kompile.cli.main.chat.mcp.McpBundleToolLoader;
import ai.kompile.cli.main.chat.render.TerminalRenderer;
import ai.kompile.cli.main.chat.tools.ToolRegistry;
import ai.kompile.cli.main.chat.tools.ToolRegistryFactory;
import org.jline.reader.LineReader;
import org.jline.terminal.Size;
import org.jline.terminal.impl.LineDisciplineTerminal;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.junit.jupiter.api.parallel.Resources;
import org.mockito.MockedConstruction;
import org.mockito.MockedStatic;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Real REPL initialization, detach, reattach and disposal; no fake Session lifecycle. */
@ResourceLock(Resources.SYSTEM_PROPERTIES)
@ResourceLock(Resources.SYSTEM_OUT)
@ResourceLock(Resources.SYSTEM_ERR)
class ChatReplRetainedLifecycleTest {
    @TempDir Path temporaryDirectory;

    @Test
    void twoRealReplsRetainTheirStateAndCloseWithoutClosingTheSharedTerminal() throws Exception {
        String previousHome = System.getProperty("user.home");
        String previousDirectory = System.getProperty("user.dir");
        Path home = Files.createDirectories(temporaryDirectory.resolve("home"));
        Path work = Files.createDirectories(temporaryDirectory.resolve("project"));
        Files.createDirectories(work.resolve(".kompile"));
        System.setProperty("user.home", home.toString());
        System.setProperty("user.dir", work.toString());
        try {
            exerciseLifecycle(home, work);
        } finally {
            restoreProperty("user.home", previousHome);
            restoreProperty("user.dir", previousDirectory);
        }
    }

    private void exerciseLifecycle(Path home, Path work) throws Exception {
        HarnessConfig disabled = new HarnessConfig();
        disabled.setEnabled(false);
        disabled.setJudgeEnabled(false);
        disabled.setJudgeGlobalEnabled(false);
        disabled.setPersistCrossSession(false);
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        McpBundleToolLoader firstMcp = mock(McpBundleToolLoader.class);
        McpBundleToolLoader secondMcp = mock(McpBundleToolLoader.class);
        when(firstMcp.dashboardConfig()).thenReturn(Optional.empty());
        when(secondMcp.dashboardConfig()).thenReturn(Optional.empty());

        // Isolate external setup, not ChatRepl, SessionLifecycleManager, JLine or the TUI.
        // The empty real registries cannot dispatch any process/network tools. Provider
        // constructors and project MCP discovery cannot contact services or launch children.
        try (MockedStatic<HarnessConfig> harness = mockStatic(HarnessConfig.class);
             MockedStatic<ToolRegistryFactory> tools = mockStatic(ToolRegistryFactory.class);
             MockedStatic<McpBundleToolLoader> mcp = mockStatic(McpBundleToolLoader.class);
             MockedConstruction<DirectLlmClient> clients = mockConstruction(DirectLlmClient.class);
             var terminal = new LineDisciplineTerminal(
                     "real-repl-retention", "xterm", output, StandardCharsets.UTF_8)) {
            harness.when(HarnessConfig::load).thenReturn(disabled);
            harness.when(() -> HarnessConfig.load(any())).thenReturn(disabled);
            tools.when(() -> ToolRegistryFactory.create(any(), anyString(), any(), any(),
                            any(), any(), any(), any(), isNull(), any(), any()))
                    .thenAnswer(invocation -> new ToolRegistry(invocation.getArgument(0)));
            mcp.when(() -> McpBundleToolLoader.loadInteractive(eq(work), any(), anyString()))
                    .thenReturn(firstMcp, secondMcp);
            terminal.setSize(new Size(100, 30));
            ScheduledLoopManager projectLoops = new ScheduledLoopManager(
                    prompt -> fail("No scheduled provider turn is expected"),
                    ScheduledLoopManager.stateFileForProject(work));
            try (OwnedRepl first = new OwnedRepl("retained-first", work, projectLoops);
                 OwnedRepl second = new OwnedRepl("retained-second", work, projectLoops)) {
                LineReader firstReader;
                Consumer<String> firstOutput;
                try (var ignored = first.ui.bind()) {
                    first.repl.initializeInteractive(terminal);
                    firstReader = first.ui.lineReaderRef;
                    assertNotNull(firstReader);
                    assertSame(terminal, firstReader.getTerminal());
                    assertTrue(first.repl.getTui().isTerminalAttached());
                    assertTrue(first.repl.submitInteractiveLine("/queue first queued work"));
                    firstReader.getBuffer().write("unfinished first draft");
                    firstReader.getHistory().add("first input history");
                    firstOutput = first.ui.contentOutput;
                    assertNotNull(firstOutput);
                    assertTrue(first.repl.handleProjectActivityCommand("agents"));
                    assertFalse(first.repl.getTui().isMainContentView());
                    first.repl.detachInteractive();
                    assertThrows(IllegalStateException.class, first.repl::readInteractiveLine);
                }
                assertTrue(first.repl.getTui().isStarted(), "hide must not dispose the TUI");
                assertFalse(first.repl.getTui().isTerminalAttached());
                assertNull(first.ui.lineReaderRef);
                assertNull(first.ui.terminalRef);

                LineReader secondReader;
                try (var ignored = second.ui.bind()) {
                    second.repl.initializeInteractive(terminal);
                    secondReader = second.ui.lineReaderRef;
                    assertNotNull(secondReader);
                    assertSame(terminal, secondReader.getTerminal());
                    assertNotSame(firstReader, secondReader);
                    assertTrue(secondReader.getHistory().isEmpty(), "input history is session-local");
                    secondReader.getBuffer().write("unfinished second draft");
                    assertEquals(List.of(), second.ui.queueSupplier.get());
                    // Invoke the callback installed by real initializeInteractive while
                    // the sibling is bound, as an asynchronous completion would do.
                    firstOutput.accept("first completion while hidden");
                    assertFalse(output.toString(StandardCharsets.UTF_8)
                            .contains("first completion while hidden"));
                    assertFalse(content(second).contains("first completion while hidden"));
                    second.repl.detachInteractive();
                }

                try (var ignored = first.ui.bind()) {
                    first.repl.attachInteractive();
                    assertSame(firstReader, first.ui.lineReaderRef, "switch must reuse the reader");
                    assertSame(terminal, first.ui.terminalRef);
                    assertEquals("unfinished first draft", firstReader.getBuffer().toString());
                    assertEquals("first input history", firstReader.getHistory().get(firstReader.getHistory().last()));
                    assertEquals(List.of("first queued work"), first.ui.queueSupplier.get());
                    assertFalse(first.repl.getTui().isMainContentView(), "activity view survives hiding");
                    assertTrue(first.repl.handleProjectActivityCommand("close"));
                    assertTrue(content(first).contains("first completion while hidden"));
                    assertThrows(IllegalStateException.class, () -> first.repl.initializeInteractive(terminal));
                    first.repl.detachInteractive();
                }
                try (var ignored = second.ui.bind()) {
                    second.repl.attachInteractive();
                    assertSame(secondReader, second.ui.lineReaderRef);
                    assertEquals("unfinished second draft", secondReader.getBuffer().toString());
                    // Dispose the hidden owner while its sibling still owns the terminal.
                    first.close();
                    assertSame(second.ui, ChatUiSession.current());
                    assertTrue(second.repl.getTui().isTerminalAttached());
                    assertSame(secondReader, second.ui.lineReaderRef);
                    firstOutput.accept("late first output after close");
                    assertFalse(content(second).contains("late first output after close"));
                    assertFalse(output.toString(StandardCharsets.UTF_8).contains("late first output after close"));
                    ChatCompleter.printAbove("second still usable");
                    assertTrue(content(second).contains("second still usable"));
                }
                assertFalse(first.repl.getTui().isStarted());
                first.ui.capture((Runnable) () ->
                        assertThrows(IllegalStateException.class, first.repl::attachInteractive)).run();
                second.close();
                second.close();
                assertFalse(second.repl.getTui().isStarted());
                assertTrue(first.ui.isClosed());
                assertTrue(second.ui.isClosed());
                assertTrue(Files.isRegularFile(home.resolve(".kompile/conversations/retained-first.txt")));
                assertTrue(Files.isRegularFile(home.resolve(".kompile/conversations/retained-second.txt")));
                assertEquals(2, clients.constructed().size());
                clients.constructed().forEach(client -> verify(client, times(1)).close());
                verify(firstMcp, atLeastOnce()).close();
                verify(secondMcp, atLeastOnce()).close();
                // Check input too: ByteArrayOutputStream.close() alone is a no-op,
                // so successful output would not prove the terminal remained open.
                byte[] input = "x\n".getBytes(StandardCharsets.UTF_8);
                terminal.processInputBytes(input, 0, input.length);
                assertEquals((int) 'x', terminal.reader().read(1_000));
                // Only the enclosing test owns terminal.close().
                terminal.writer().println("shared terminal remains open");
                terminal.writer().flush();
                assertTrue(output.toString(StandardCharsets.UTF_8).contains("shared terminal remains open"));
            } finally {
                projectLoops.shutdown();
            }
        }
    }

    private static String content(OwnedRepl owner) {
        return String.join("\n", owner.repl.getTui().getVisibleContentLines());
    }

    /** Owns only context binding/cleanup; every lifecycle operation uses the real REPL. */
    private static final class OwnedRepl implements AutoCloseable {
        final ChatUiSession ui = new ChatUiSession();
        final ChatRepl repl;

        OwnedRepl(String id, Path work, ScheduledLoopManager projectLoops) {
            try (var ignored = ui.bind()) {
                repl = new ChatRepl(null, null, id, false, "coder", false,
                        new ChatConfig("codex", null, "test-model", null), work,
                        new TerminalRenderer(true));
                repl.installRetainedOutput();
                repl.configureHost(projectLoops);
            }
        }

        @Override public void close() {
            try {
                ui.capture((Runnable) repl::close).run();
            } finally {
                ui.close();
            }
        }
    }

    private static void restoreProperty(String key, String value) {
        if (value == null) System.clearProperty(key);
        else System.setProperty(key, value);
    }
}
