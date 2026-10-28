package ai.kompile.cli.main.chat;

import ai.kompile.cli.main.chat.config.ChatConfig;
import ai.kompile.cli.main.chat.config.DirectLlmClient;
import ai.kompile.cli.main.chat.config.ProviderCompactionCapabilities;
import ai.kompile.cli.main.chat.harness.HarnessConfig;
import ai.kompile.cli.main.chat.mcp.McpBundleToolLoader;
import ai.kompile.cli.main.chat.mcp.McpDiagnostics;
import ai.kompile.cli.main.chat.render.TerminalRenderer;
import ai.kompile.cli.main.coordination.CoordinationStateManager;
import ai.kompile.cli.main.coordination.EditLockEntry;
import ai.kompile.cli.main.chat.tools.ToolRegistry;
import ai.kompile.cli.main.chat.tools.BackgroundProcessManager;
import ai.kompile.cli.main.chat.tools.ToolRegistryFactory;
import ai.kompile.cli.main.chat.tui.StatusBar;
import ai.kompile.utils.AnsiConstants;
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
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
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

    @Test
    void providerCloseFailureStillReleasesTheEndedChatsLocks() throws Exception {
        String previousHome = System.getProperty("user.home");
        String previousDirectory = System.getProperty("user.dir");
        Path home = Files.createDirectories(temporaryDirectory.resolve("home"));
        Path work = Files.createDirectories(temporaryDirectory.resolve("project"));
        Files.createDirectories(work.resolve(".kompile"));
        System.setProperty("user.home", home.toString());
        System.setProperty("user.dir", work.toString());
        try {
            exerciseLifecycle(home, work, true);
        } finally {
            restoreProperty("user.home", previousHome);
            restoreProperty("user.dir", previousDirectory);
        }
    }

    private void exerciseLifecycle(Path home, Path work) throws Exception {
        exerciseLifecycle(home, work, false);
    }

    private void exerciseLifecycle(Path home, Path work, boolean failProviderClose) throws Exception {
        List<CoordinationStateManager> coordinators = new ArrayList<>();
        List<BackgroundProcessManager> processes = new ArrayList<>();
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
             MockedConstruction<DirectLlmClient> clients = mockConstruction(DirectLlmClient.class,
                     (client, context) -> {
                         when(client.compactionCapabilities(nullable(String.class)))
                                 .thenReturn(ProviderCompactionCapabilities.generic());
                         when(client.contextBudgetLimits(any(), nullable(String.class)))
                                 .thenReturn(new ai.kompile.cli.main.chat.config.ModelContextResolver.ModelLimits(32_000, 4_000));
                     });
             var terminal = new LineDisciplineTerminal(
                     "real-repl-retention", "xterm", output, StandardCharsets.UTF_8)) {
            harness.when(HarnessConfig::load).thenReturn(disabled);
            harness.when(() -> HarnessConfig.load(any())).thenReturn(disabled);
            tools.when(() -> ToolRegistryFactory.create(any(), anyString(), any(), any(),
                            any(), any(), any(), any(), isNull(), any(), any()))
                    .thenAnswer(invocation -> {
                        processes.add(invocation.getArgument(5));
                        coordinators.add(invocation.getArgument(10));
                        return new ToolRegistry(invocation.getArgument(0));
                    });
            mcp.when(() -> McpBundleToolLoader.loadInteractive(eq(work), any(), anyString()))
                    .thenReturn(firstMcp, secondMcp);
            terminal.setSize(new Size(100, 30));
            ScheduledLoopManager projectLoops = new ScheduledLoopManager(
                    prompt -> fail("No scheduled provider turn is expected"),
                    ScheduledLoopManager.stateFileForProject(work));
            try (OwnedRepl first = new OwnedRepl("retained-first", work, projectLoops);
                 OwnedRepl second = new OwnedRepl("retained-second", work, projectLoops)) {
                String firstLock = coordinators.get(0).tryAcquireEditLock(
                        work.resolve("first.txt").toString(), "edit", "worker").getLockId();
                String secondLock = coordinators.get(1).tryAcquireEditLock(
                        work.resolve("second.txt").toString(), "edit", "worker").getLockId();
                assertEquals(2, coordinators.get(1).queryEdits().size());
                LineReader firstReader;
                Consumer<String> firstOutput;
                try (var ignored = first.ui.bind()) {
                    first.repl.initializeInteractive(terminal);
                    firstReader = first.ui.lineReaderRef;
                    assertNotNull(firstReader);
                    assertSame(terminal, firstReader.getTerminal());
                    assertTrue(first.repl.getTui().isTerminalAttached());
                    // A separate writer (the MCP topology) must surface without a model/tool callback.
                    new ai.kompile.cli.main.chat.activity.ConversationActivityService().record(
                            first.repl.getActivityIdentity(), "test.milestone.recorded", "", "first-milestone-fixture", null,
                            java.util.Map.of("source", "manual", "outcome", "failed", "failed", "1"));
                    awaitActivityFrame(output, "Test milestone · first-milestone-fixture");
                    assertTrue(content(first).contains("manual (agent-reported)"));
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
                    assertFalse(content(second).contains("first-milestone-fixture"), "milestone cards are session-local");
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
                    if (failProviderClose) {
                        doThrow(new IllegalStateException("provider cleanup failed"))
                                .when(clients.constructed().get(0)).close();
                        assertThrows(IllegalStateException.class, first::close);
                    } else {
                        first.close();
                    }
                    assertFalse(Files.exists(work.resolve(".kompile/coordination/edits/" + firstLock + ".lock.json")));
                    assertEquals(List.of(secondLock), coordinators.get(1).queryEdits().stream()
                            .map(EditLockEntry::getLockId).toList(), "sibling chat locks must remain");
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
                assertFalse(Files.exists(work.resolve(".kompile/coordination/edits/" + secondLock + ".lock.json")));
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
                // A deliberately failing provider may prevent later general cleanup.
                processes.forEach(BackgroundProcessManager::close);
                coordinators.forEach(CoordinationStateManager::shutdown);
                projectLoops.shutdown();
            }
        }
    }

    @Test
    void mcpBridgeLinesGoToTheProcessBrowserNotTheTranscript() throws Exception {
        String previousHome = System.getProperty("user.home");
        String previousDirectory = System.getProperty("user.dir");
        Path home = Files.createDirectories(temporaryDirectory.resolve("home"));
        Path work = Files.createDirectories(temporaryDirectory.resolve("project"));
        Files.createDirectories(work.resolve(".kompile"));
        System.setProperty("user.home", home.toString());
        System.setProperty("user.dir", work.toString());
        try {
            exerciseMcpLogRouting(work);
        } finally {
            restoreProperty("user.home", previousHome);
            restoreProperty("user.dir", previousDirectory);
        }
    }

    private void exerciseMcpLogRouting(Path work) throws Exception {
        HarnessConfig disabled = new HarnessConfig();
        disabled.setEnabled(false);
        disabled.setJudgeEnabled(false);
        disabled.setJudgeGlobalEnabled(false);
        disabled.setPersistCrossSession(false);
        McpBundleToolLoader bundle = mock(McpBundleToolLoader.class);
        when(bundle.dashboardConfig()).thenReturn(Optional.empty());
        try (MockedStatic<HarnessConfig> harness = mockStatic(HarnessConfig.class);
             MockedStatic<ToolRegistryFactory> tools = mockStatic(ToolRegistryFactory.class);
             MockedStatic<McpBundleToolLoader> mcp = mockStatic(McpBundleToolLoader.class);
             MockedConstruction<DirectLlmClient> clients = mockConstruction(DirectLlmClient.class,
                     (client, context) -> {
                         when(client.compactionCapabilities(nullable(String.class)))
                                 .thenReturn(ProviderCompactionCapabilities.generic());
                         when(client.contextBudgetLimits(any(), nullable(String.class)))
                                 .thenReturn(new ai.kompile.cli.main.chat.config.ModelContextResolver.ModelLimits(32_000, 4_000));
                     });
             var terminal = new LineDisciplineTerminal(
                     "mcp-log-routing", "xterm", new ByteArrayOutputStream(), StandardCharsets.UTF_8)) {
            harness.when(HarnessConfig::load).thenReturn(disabled);
            harness.when(() -> HarnessConfig.load(any())).thenReturn(disabled);
            tools.when(() -> ToolRegistryFactory.create(any(), anyString(), any(), any(),
                            any(), any(), any(), any(), isNull(), any(), any()))
                    .thenAnswer(invocation -> new ToolRegistry(invocation.getArgument(0)));
            // Bundle discovery reports while the REPL is still being constructed.
            mcp.when(() -> McpBundleToolLoader.loadInteractive(eq(work), any(), anyString()))
                    .thenAnswer(invocation -> {
                        McpDiagnostics.log("\u001B[33m[MCP] Skipping untrusted workspace MCP config\u001B[0m");
                        return bundle;
                    });
            terminal.setSize(new Size(100, 30));
            ScheduledLoopManager projectLoops = new ScheduledLoopManager(
                    prompt -> fail("No scheduled provider turn is expected"),
                    ScheduledLoopManager.stateFileForProject(work));
            ByteArrayOutputStream err = new ByteArrayOutputStream();
            try (OwnedRepl owned = new OwnedRepl("mcp-log-routing", work, projectLoops);
                 PrintStream capture = new PrintStream(err, true, StandardCharsets.UTF_8)) {
                StatusBar statusBar = owned.repl.getTui().getStatusBar();
                try (var ignored = owned.ui.bind()) {
                    owned.repl.initializeInteractive(terminal);
                    PrintStream routedErr = System.err;
                    System.setErr(capture);
                    try {
                        McpDiagnostics.log("[MCP] Injected kompile tools into the agent settings");
                    } finally {
                        System.setErr(routedErr);
                    }
                    String processes = AnsiConstants.stripAnsi(statusBar.renderProcessPanel());
                    assertTrue(processes.contains("MCP tool bridge log"), processes);
                    assertTrue(processes.contains("[MCP] Skipping untrusted workspace MCP config"), processes);
                    assertTrue(processes.contains("[MCP] Injected kompile tools"), processes);
                    assertFalse(content(owned).contains("[MCP]"), content(owned));
                    assertFalse(err.toString(StandardCharsets.UTF_8).contains("[MCP]"),
                            "an isolated chat never echoes MCP lines to stderr");
                    owned.repl.detachInteractive();
                }

                // Closing the chat drops its sink, so a late line keeps the stderr route.
                owned.close();
                PrintStream closedErr = System.err;
                System.setErr(capture);
                try {
                    owned.ui.capture((Runnable) () -> McpDiagnostics.log("[MCP] after close")).run();
                } finally {
                    System.setErr(closedErr);
                }
                assertTrue(err.toString(StandardCharsets.UTF_8).contains("[MCP] after close"));
                assertFalse(statusBar.renderProcessPanel().contains("after close"));
                verify(bundle, atLeastOnce()).close();
                assertEquals(1, clients.constructed().size());
            } finally {
                projectLoops.shutdown();
            }
        }
    }

    @Test
    void aRejectedLiveModelSelectionKeepsConfigLabelMetricsAndSavedModel() throws Exception {
        String previousHome = System.getProperty("user.home");
        Path home = Files.createDirectories(temporaryDirectory.resolve("model-home"));
        Path work = Files.createDirectories(temporaryDirectory.resolve("model-project"));
        System.setProperty("user.home", home.toString());
        HarnessConfig disabled = new HarnessConfig();
        disabled.setEnabled(false);
        disabled.setJudgeEnabled(false);
        disabled.setJudgeGlobalEnabled(false);
        disabled.setPersistCrossSession(false);
        McpBundleToolLoader bundle = mock(McpBundleToolLoader.class);
        when(bundle.dashboardConfig()).thenReturn(Optional.empty());
        try (MockedStatic<HarnessConfig> harness = mockStatic(HarnessConfig.class);
             MockedStatic<ToolRegistryFactory> tools = mockStatic(ToolRegistryFactory.class);
             MockedStatic<McpBundleToolLoader> mcp = mockStatic(McpBundleToolLoader.class)) {
            harness.when(HarnessConfig::load).thenReturn(disabled);
            harness.when(() -> HarnessConfig.load(any())).thenReturn(disabled);
            tools.when(() -> ToolRegistryFactory.create(any(), anyString(), any(), any(),
                            any(), any(), any(), any(), isNull(), any(), any()))
                    .thenAnswer(invocation -> new ToolRegistry(invocation.getArgument(0)));
            mcp.when(() -> McpBundleToolLoader.loadInteractive(eq(work), any(), anyString())).thenReturn(bundle);
            ChatConfig initial = new ChatConfig("anthropic", null, "sonnet", null);
            initial.setAuthenticationMethod("oauth");
            initial.setContextWindowTokens(200_000);
            initial.setMaxOutputTokens(4096);
            ScheduledLoopManager loops = new ScheduledLoopManager(prompt -> fail("No scheduled turn expected"),
                    ScheduledLoopManager.stateFileForProject(work));
            try (OwnedRepl owned = new OwnedRepl("model-ack-test", work, loops, initial)) {
                var fake = new ai.kompile.cli.main.chat.config.FakeClaudeCode(work.resolve("fake"), """
                        control() {
                          if [ "$2" = set_model ] && [[ "$3" == *'fable[1m]'* ]]; then
                            emit '{"type":"control_response","response":{"subtype":"error","request_id":"'"$1"'","error":"1m context is disabled"}}'
                          else
                            emit '{"type":"control_response","response":{"subtype":"success","request_id":"'"$1"'","response":{}}}'
                          fi
                        }
                        """);
                fake.install(owned.repl.getDirectClient(), work, null);
                var loop = (ai.kompile.cli.main.chat.agent.AgenticChatLoop)
                        org.springframework.test.util.ReflectionTestUtils.getField(owned.repl, "agenticLoop");
                var metrics = (ChatSessionMetrics)
                        org.springframework.test.util.ReflectionTestUtils.getField(owned.repl, "sessionMetrics");
                try (var ignored = owned.ui.bind()) {
                    assertEquals("ok", loop.chat("first", "model-ack-test", "coder", "default", false).strip());
                    owned.repl.refreshModelDisplay();
                    owned.repl.getChatConfig().saveLoadedOrGlobal();
                    String label = owned.repl.getTui().getTopBar().render(160);
                    ChatConfig invalid = owned.repl.getChatConfig().copy();
                    invalid.setModel("fable[1m]");
                    assertTrue(owned.repl.commitModelProviderSelection(invalid));
                    ai.kompile.cli.main.chat.config.FakeClaudeCode.await("the visible selection rejection",
                            () -> content(owned).contains("1m context is disabled"));
                    assertEquals("sonnet", owned.repl.getChatConfig().getModel());
                    assertEquals("sonnet", metrics.getModel());
                    assertEquals(label, owned.repl.getTui().getTopBar().render(160));
                    assertEquals("sonnet", ChatConfig.loadSession("model-ack-test").getModel());
                    assertFalse(content(owned).contains("Selected provider/model: Anthropic / fable[1m]"));
                    assertEquals("ok", loop.chat("after rejection", "model-ack-test", "coder", "default", false).strip());
                    assertEquals(1, fake.controls("set_model").size(), "no phantom switch back is needed");
                    ChatConfig valid = owned.repl.getChatConfig().copy();
                    valid.setModel("opus");
                    assertTrue(owned.repl.commitModelProviderSelection(valid));
                    ai.kompile.cli.main.chat.config.FakeClaudeCode.await("acknowledged model persisted",
                            () -> "opus".equals(ChatConfig.loadSession("model-ack-test").getModel()));
                    assertEquals("opus", owned.repl.getChatConfig().getModel());
                    assertEquals("opus", metrics.getModel());
                    assertTrue(owned.repl.getTui().getTopBar().render(160).contains("opus"));
                    assertEquals(1, fake.argv().size(), "the provider session survives selection");
                }
            } finally {
                loops.shutdown();
            }
        } finally {
            restoreProperty("user.home", previousHome);
        }
    }

    @Test
    void aCompletionEventDuringAClaudeTurnGoesIntoThatTurnAfterInteractiveInitialization() throws Exception {
        String previousHome = System.getProperty("user.home");
        String previousDirectory = System.getProperty("user.dir");
        Path home = Files.createDirectories(temporaryDirectory.resolve("injection-home"));
        Path work = Files.createDirectories(temporaryDirectory.resolve("injection-project"));
        Files.createDirectories(work.resolve(".kompile"));
        System.setProperty("user.home", home.toString());
        System.setProperty("user.dir", work.toString());
        HarnessConfig disabled = new HarnessConfig();
        disabled.setEnabled(false);
        disabled.setJudgeEnabled(false);
        disabled.setJudgeGlobalEnabled(false);
        disabled.setPersistCrossSession(false);
        McpBundleToolLoader bundle = mock(McpBundleToolLoader.class);
        when(bundle.dashboardConfig()).thenReturn(Optional.empty());
        try (MockedStatic<HarnessConfig> harness = mockStatic(HarnessConfig.class);
             MockedStatic<ToolRegistryFactory> tools = mockStatic(ToolRegistryFactory.class);
             MockedStatic<McpBundleToolLoader> mcp = mockStatic(McpBundleToolLoader.class);
             // The running Claude Code turn takes every delivery written into it.
             MockedConstruction<DirectLlmClient> clients = mockConstruction(DirectLlmClient.class,
                     (client, context) -> {
                         when(client.compactionCapabilities(nullable(String.class)))
                                 .thenReturn(ProviderCompactionCapabilities.generic());
                         when(client.contextBudgetLimits(any(), nullable(String.class)))
                                 .thenReturn(new ai.kompile.cli.main.chat.config.ModelContextResolver.ModelLimits(32_000, 4_000));
                         when(client.injectIntoClaudeTurn(anyString(), anyString())).thenReturn(true);
                     });
             var terminal = new LineDisciplineTerminal(
                     "running-turn-injection", "xterm", new ByteArrayOutputStream(), StandardCharsets.UTF_8)) {
            harness.when(HarnessConfig::load).thenReturn(disabled);
            harness.when(() -> HarnessConfig.load(any())).thenReturn(disabled);
            tools.when(() -> ToolRegistryFactory.create(any(), anyString(), any(), any(),
                            any(), any(), any(), any(), isNull(), any(), any()))
                    .thenAnswer(invocation -> new ToolRegistry(invocation.getArgument(0)));
            mcp.when(() -> McpBundleToolLoader.loadInteractive(eq(work), any(), anyString())).thenReturn(bundle);
            // A terminal with a width makes initializeInteractive rebuild the message handler.
            terminal.setSize(new Size(100, 30));
            ScheduledLoopManager loops = new ScheduledLoopManager(prompt -> fail("No scheduled turn expected"),
                    ScheduledLoopManager.stateFileForProject(work));
            try (OwnedRepl owned = new OwnedRepl("running-turn-injection", work, loops)) {
                try (var ignored = owned.ui.bind()) {
                    owned.repl.initializeInteractive(terminal);
                    // The process ends while a Claude Code turn runs.
                    owned.repl.setLlmBusy(true);
                    try {
                        owned.repl.messageHandler.handleExternalMessage("[System process completion]\n"
                                + "Process proc-001 finished with state completed and exit code 0.");
                    } finally {
                        owned.repl.setLlmBusy(false);
                    }
                    owned.repl.detachInteractive();
                }
                assertEquals(1, clients.constructed().size());
                verify(clients.constructed().get(0))
                        .injectIntoClaudeTurn(anyString(), contains("Process proc-001 finished"));
            } finally {
                loops.shutdown();
            }
        } finally {
            restoreProperty("user.home", previousHome);
            restoreProperty("user.dir", previousDirectory);
        }
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"mcp__kompile__task", "mcp__kompile__multi_task"})
    void openedDelegationActivityRendersLiveOutputWithoutReopening(String toolName) throws Exception {
        String previousHome = System.getProperty("user.home");
        String previousDirectory = System.getProperty("user.dir");
        Path home = Files.createDirectories(temporaryDirectory.resolve("activity-home"));
        Path work = Files.createDirectories(temporaryDirectory.resolve("activity-project"));
        System.setProperty("user.home", home.toString());
        System.setProperty("user.dir", work.toString());
        HarnessConfig disabled = new HarnessConfig();
        disabled.setEnabled(false);
        disabled.setJudgeGlobalEnabled(false);
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        var childListener = new java.util.concurrent.atomic.AtomicReference<ai.kompile.cli.main.chat.agent.SubagentRunner.LifecycleListener>();
        var childRunner = new ai.kompile.cli.main.chat.agent.SubagentRunner() {
            public String runSubagent(ai.kompile.cli.main.chat.agent.AgentConfig agent, String prompt,
                                      ai.kompile.cli.main.chat.tools.ToolContext context) {
                throw new AssertionError("No model call expected");
            }
            public void setLifecycleListener(LifecycleListener listener) { childListener.set(listener); }
        };
        var bundle = mock(McpBundleToolLoader.class);
        when(bundle.dashboardConfig()).thenReturn(Optional.empty());
        try (MockedStatic<HarnessConfig> harness = mockStatic(HarnessConfig.class);
             MockedStatic<ToolRegistryFactory> tools = mockStatic(ToolRegistryFactory.class);
             MockedStatic<McpBundleToolLoader> mcp = mockStatic(McpBundleToolLoader.class);
             MockedConstruction<DirectLlmClient> clients = mockConstruction(DirectLlmClient.class,
                     (client, context) -> {
                         when(client.compactionCapabilities(nullable(String.class)))
                                 .thenReturn(ProviderCompactionCapabilities.generic());
                         when(client.contextBudgetLimits(any(), nullable(String.class)))
                                 .thenReturn(new ai.kompile.cli.main.chat.config.ModelContextResolver.ModelLimits(32_000, 4_000));
                     });
             var terminal = new LineDisciplineTerminal("live-delegation-view", "xterm", output, StandardCharsets.UTF_8)) {
            harness.when(HarnessConfig::load).thenReturn(disabled);
            harness.when(() -> HarnessConfig.load(any())).thenReturn(disabled);
            tools.when(() -> ToolRegistryFactory.create(any(), anyString(), any(), any(),
                            any(), any(), any(), any(), isNull(), any(), any()))
                    .thenAnswer(invocation -> {
                        var registry = new ToolRegistry(invocation.getArgument(0));
                        registry.setSubagentRunner(childRunner);
                        return registry;
                    });
            mcp.when(() -> McpBundleToolLoader.loadInteractive(eq(work), any(), anyString())).thenReturn(bundle);
            terminal.setSize(new Size(120, 40));
            ScheduledLoopManager loops = new ScheduledLoopManager(prompt -> fail("No model turn expected"),
                    ScheduledLoopManager.stateFileForProject(work));
            try (OwnedRepl owned = new OwnedRepl("live-delegation", work, loops);
                 var ignored = owned.ui.bind()) {
                owned.repl.initializeInteractive(terminal);
                owned.repl.getTui().getStatusBar().stop();
                var loopField = ChatRepl.class.getDeclaredField("agenticLoop");
                loopField.setAccessible(true);
                Object loop = loopField.get(owned.repl);
                var listenerField = loop.getClass().getDeclaredField("toolActivityListener");
                listenerField.setAccessible(true);
                var listener = (ai.kompile.cli.main.chat.agent.AgenticChatLoop.ToolActivityListener) listenerField.get(loop);
                var panelField = ChatRepl.class.getDeclaredField("activityPanel");
                panelField.setAccessible(true);
                var panel = (StandardChatActivityPanel) panelField.get(owned.repl);
                listener.onToolStart("delegation", toolName, "{\"description\":\"inspect source\"}");
                openActivityRow(owned, panel, "tool:delegation");

                // Use the callback installed by ChatRepl, not a manual content-view refresh.
                listener.onToolOutput("delegation", toolName, "{}", "Assistant: inspecting source\n");
                awaitActivityFrame(output, "Assistant: inspecting source");
                listener.onToolOutput("sibling", toolName, "{}", "sibling-private-output\n");
                listener.onToolOutput("delegation", toolName, "{}", "Tool read: fixture evidence\n");
                awaitActivityFrame(output, "Tool read: fixture evidence");
                assertFalse(content(owned).contains("sibling-private-output"));
                listener.onToolComplete("delegation", toolName, "{}",
                        ai.kompile.cli.main.chat.tools.ToolResult.success("Finished child work"));
                awaitActivityFrame(output, "Finished child work");
                assertEquals("tool:delegation", owned.repl.getTui().getContentViewKey());
                assertTrue(content(owned).contains("Assistant: inspecting source"));

                // Native child rows use their lifecycle listener, not the invocation's output.
                childListener.get().onSubagentStart("native-child", "general", "Live child");
                openActivityRow(owned, panel, "subagent:native-child");
                childListener.get().onSubagentOutput("native-child", "Child prose before completion\n");
                awaitActivityFrame(output, "Child prose before completion");
                childListener.get().onSubagentActivity("native-child", "reading", "Child read tool evidence\n");
                awaitActivityFrame(output, "Child read tool evidence");
                childListener.get().onSubagentOutput("native-child", "More live child prose\n");
                awaitActivityFrame(output, "More live child prose");
                childListener.get().onSubagentEnd("native-child");
                assertEquals("subagent:native-child", owned.repl.getTui().getContentViewKey());
                var mainEntries = owned.repl.getTui().getClass().getDeclaredField("mainTranscriptEntries");
                mainEntries.setAccessible(true);
                for (Object entry : (java.util.Collection<?>) mainEntries.get(owned.repl.getTui())) {
                    var key = entry.getClass().getDeclaredField("key");
                    key.setAccessible(true);
                    assertFalse(String.valueOf(key.get(entry)).contains("native-child"),
                            "lifecycle events must not insert child transcript blocks into Main");
                }

                // MCP task/multi_task children arrive as shared process logs in this chat.
                var coordinator = new ai.kompile.cli.main.coordination.CoordinationStateManager(
                        work, "mcp-children", new com.fasterxml.jackson.databind.ObjectMapper());
                coordinator.registerAgent("fixture", "live-delegation", "kompile", 1, 0L, null, null);
                try (var children = new ai.kompile.cli.main.chat.tools.BackgroundProcessManager("mcp-children", work)) {
                    int count = toolName.endsWith("multi_task") ? 2 : 1;
                    var logs = new java.util.ArrayList<ai.kompile.cli.main.chat.tools.BackgroundProcessManager.ProcessEntry>();
                    for (int n = 0; n < count; n++) {
                        var log = children.registerVirtual(ai.kompile.cli.main.chat.tools.BackgroundProcessManager.ProcessKind.COMMAND,
                                "kompile", "MCP child " + n, java.util.Map.of("kind", "subagent"));
                        logs.add(log);
                        coordinator.publishProcess(log.getId(), log.getCommand(), log.getDescription(), -1L,
                                "RUNNING", log.getOutputFile().toString(), "kompile", null, "subagent",
                                log.getStartTime(), null, null);
                    }
                    var mirrorField = ChatRepl.class.getDeclaredField("sharedProcessMirror");
                    mirrorField.setAccessible(true);
                    var mirror = (SharedProcessMirror) mirrorField.get(owned.repl);
                    mirror.pollOnce();
                    String key = "process:shared-mcp-children-" + logs.get(0).getId();
                    openActivityRow(owned, panel, key);
                    children.appendVirtualOutputChunk(logs.get(0).getId(), "MCP live assistant prose\n");
                    awaitActivityFrame(output, "MCP live assistant prose");
                    if (count > 1) children.appendVirtualOutput(logs.get(1).getId(), "private sibling transcript");
                    children.appendVirtualOutput(logs.get(0).getId(), "MCP child tool call and result");
                    awaitActivityFrame(output, "MCP child tool call and result");
                    assertFalse(content(owned).contains("private sibling transcript"));
                    assertEquals(key, owned.repl.getTui().getContentViewKey());
                    for (var log : logs) {
                        children.complete(log.getId());
                        coordinator.updateProcessState(log.getId(), "COMPLETED", log.getEndTime(), log.getExitCode());
                    }
                    mirror.pollOnce();
                    assertEquals(key, panel.currentView().key());
                    assertTrue(content(owned).contains("MCP child tool call and result"));
                } finally {
                    coordinator.shutdown();
                }
            } finally {
                loops.shutdown();
            }
        } finally {
            restoreProperty("user.home", previousHome);
            restoreProperty("user.dir", previousDirectory);
        }
    }

    private static void openActivityRow(OwnedRepl owned, StandardChatActivityPanel panel, String key) {
        panel.clearSelection();
        for (int n = 0; n < panel.activityItems().size() + 1; n++) {
            assertTrue(panel.selectNext());
            assertTrue(owned.ui.lineReaderRef.getWidgets().get(LineReader.ACCEPT_LINE).apply());
            if (panel.currentView().key().equals(key)) break;
        }
        assertEquals(key, panel.currentView().key());
        assertEquals(key, owned.repl.getTui().getContentViewKey());
    }

    private static void awaitActivityFrame(ByteArrayOutputStream output, String text) throws Exception {
        long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(3);
        String screen;
        do {
            var frame = new ai.kompile.cli.main.chat.tui.VirtualTerminal(40, 120);
            frame.feed(output.toString(StandardCharsets.UTF_8));
            screen = frame.screenDump();
            if (screen.contains(text)) return;
            Thread.sleep(10);
        } while (System.nanoTime() < deadline);
        fail("Opened activity frame did not update with " + text + ":\n" + screen);
    }

    private static String content(OwnedRepl owner) {
        return String.join("\n", owner.repl.getTui().getVisibleContentLines());
    }

    /** Owns only context binding/cleanup; every lifecycle operation uses the real REPL. */
    private static final class OwnedRepl implements AutoCloseable {
        final ChatUiSession ui = new ChatUiSession();
        final ChatRepl repl;

        OwnedRepl(String id, Path work, ScheduledLoopManager projectLoops) {
            this(id, work, projectLoops, new ChatConfig("codex", null, "test-model", null));
        }

        OwnedRepl(String id, Path work, ScheduledLoopManager projectLoops, ChatConfig config) {
            try (var ignored = ui.bind()) {
                repl = new ChatRepl(null, null, id, false, "coder", false,
                        config, work,
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
