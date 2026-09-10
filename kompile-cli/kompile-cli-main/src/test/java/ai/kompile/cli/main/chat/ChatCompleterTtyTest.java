package ai.kompile.cli.main.chat;

import ai.kompile.cli.common.mcp.McpSseClient;
import ai.kompile.cli.main.chat.render.TerminalRenderer;
import ai.kompile.cli.main.chat.tools.BackgroundProcessManager;
import ai.kompile.cli.main.chat.tui.KompileTui;
import ai.kompile.cli.main.chat.tui.VirtualTerminal;
import org.jline.reader.*;
import org.jline.keymap.KeyMap;
import org.jline.reader.impl.LineReaderImpl;
import org.jline.terminal.Attributes;
import org.jline.terminal.Size;
import org.jline.terminal.Terminal;
import org.jline.terminal.impl.LineDisciplineTerminal;
import org.jline.widget.AutosuggestionWidgets;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.LockSupport;

import static org.junit.jupiter.api.Assertions.*;

/**
 * TTY integration test for {@link ChatCompleter}.
 * <p>
 * Uses a {@link LineDisciplineTerminal} with xterm capabilities so JLine's
 * full completion rendering pipeline (cursor movement, candidate listing,
 * inline completion) is exercised. Keystrokes are fed through a pipe and
 * terminal output is captured from the master side.
 */
class ChatCompleterTtyTest {

    private PipedOutputStream keyboardPipe;
    private ByteArrayOutputStream terminalOutput;
    private LineDisciplineTerminal terminal;
    private LineReader reader;

    /** TAB character (triggers completion). */
    private static final char TAB = '\t';
    /** Enter/Return. */
    private static final char CR = '\r';

    @BeforeEach
    void setUp() throws Exception {
        terminalOutput = new ByteArrayOutputStream();

        // LineDisciplineTerminal with xterm type gives us proper cursor movement,
        // line editing, and completion rendering — unlike DumbTerminal which ignores TAB.
        terminal = new LineDisciplineTerminal("test", "xterm", terminalOutput,
                StandardCharsets.UTF_8);
        terminal.setSize(new Size(120, 40));

        // Set raw-ish attributes so JLine handles line discipline
        Attributes attrs = terminal.getAttributes();
        attrs.setLocalFlag(Attributes.LocalFlag.ECHO, false);
        attrs.setLocalFlag(Attributes.LocalFlag.ICANON, false);
        attrs.setInputFlag(Attributes.InputFlag.ICRNL, false);
        terminal.setAttributes(attrs);

        List<McpSseClient.ToolInfo> tools = List.of(
                new McpSseClient.ToolInfo("read", "Read a file", null),
                new McpSseClient.ToolInfo("write", "Write a file", null),
                new McpSseClient.ToolInfo("grep", "Search contents", null),
                new McpSseClient.ToolInfo("bash", "Run shell cmd", null)
        );
        Set<String> skills = new LinkedHashSet<>(List.of("commit", "review", "fix"));
        Set<String> agents = new LinkedHashSet<>(List.of("coder", "planner"));
        Set<String> roles = new LinkedHashSet<>(List.of("senior-dev", "architect"));

        ChatCompleter completer = new ChatCompleter(
                () -> tools,
                () -> skills,
                () -> agents,
                () -> roles
        );

        reader = LineReaderBuilder.builder()
                .terminal(terminal)
                .completer(completer)
                .option(LineReader.Option.AUTO_LIST, true)
                .option(LineReader.Option.LIST_AMBIGUOUS, true)
                .option(LineReader.Option.AUTO_MENU, true)
                .build();

        // Enable autosuggestion (fish-style history hints)
        try {
            new AutosuggestionWidgets(reader).enable();
        } catch (Exception e) {
            // ok if unsupported
        }

        // Get the pipe for feeding keystrokes into the terminal's slave side
        keyboardPipe = new PipedOutputStream();
        PipedInputStream pipeIn = new PipedInputStream(keyboardPipe, 4096);
        // Pump bytes from pipe into the terminal's slave input
        Thread pumpThread = new Thread(() -> {
            try {
                byte[] buf = new byte[256];
                int n;
                while ((n = pipeIn.read(buf)) >= 0) {
                    terminal.processInputBytes(buf, 0, n);
                }
            } catch (IOException e) {
                // pipe closed
            }
        }, "tty-input-pump");
        pumpThread.setDaemon(true);
        pumpThread.start();
    }

    @AfterEach
    void tearDown() throws Exception {
        if (keyboardPipe != null) {
            try { keyboardPipe.close(); } catch (Exception ignored) {}
        }
        if (terminal != null) terminal.close();
    }

    // ========================================================================
    // Tab completion rendering tests
    // ========================================================================

    @Test
    void tabAfterSlashHelpCompletesFullCommand() throws Exception {
        // /hel<TAB> — "help" is the only command starting with "hel", should complete
        String output = readLineOutputSanitized("/hel" + TAB + CR);
        assertTrue(output.contains("help"),
                "should complete /hel → /help, output: " + output);
    }

    @Test
    void tabAfterSlashQueueDashShowsQueueSubcommands() throws Exception {
        // /queue-<TAB> — multiple matches, JLine should list them
        String output = readLineOutputSanitized("/queue-" + TAB + CR);
        boolean hasQueueCandidate =
                output.contains("queue-send") ||
                output.contains("queue-clear") ||
                output.contains("queue-remove") ||
                output.contains("queue-status");
        assertTrue(hasQueueCandidate,
                "/queue-<TAB> should show queue subcommands, output: " + output);
    }

    @Test
    void tabAfterToolSpaceShowsToolNames() throws Exception {
        // /tool <TAB> — list all tool names
        String output = readLineOutputSanitized("/tool " + TAB + CR);
        boolean hasToolName =
                output.contains("read") ||
                output.contains("write") ||
                output.contains("grep") ||
                output.contains("bash");
        assertTrue(hasToolName,
                "/tool <TAB> should list tool names, output: " + output);
    }

    @Test
    void tabAfterToolPrefixNarrows() throws Exception {
        // /tool re<TAB> — only "read" matches, should complete
        String output = readLineOutputSanitized("/tool re" + TAB + CR);
        assertTrue(output.contains("read"),
                "/tool re<TAB> should complete to 'read', output: " + output);
    }

    @Test
    void tabAfterJudgeSpaceShowsSubArgs() throws Exception {
        String output = readLineOutputSanitized("/judge " + TAB + CR);
        boolean hasSubArg =
                output.contains("on") ||
                output.contains("off") ||
                output.contains("rules") ||
                output.contains("score");
        assertTrue(hasSubArg,
                "/judge <TAB> should show sub-arguments, output: " + output);
    }

    @Test
    void tabAfterModeSpaceShowsModes() throws Exception {
        String output = readLineOutputSanitized("/mode " + TAB + CR);
        boolean hasModes =
                output.contains("standard") ||
                output.contains("passthrough") ||
                output.contains("plan");
        assertTrue(hasModes,
                "/mode <TAB> should show mode options, output: " + output);
    }

    @Test
    void tabAfterAgentSpaceShowsAgentNames() throws Exception {
        String output = readLineOutputSanitized("/agent " + TAB + CR);
        boolean hasAgents = output.contains("coder") || output.contains("planner");
        assertTrue(hasAgents,
                "/agent <TAB> should show agent names, output: " + output);
    }

    @Test
    void tabAfterRoleSpaceShowsRoleNames() throws Exception {
        String output = readLineOutputSanitized("/role " + TAB + CR);
        boolean hasRoles = output.contains("senior-dev") || output.contains("architect");
        assertTrue(hasRoles,
                "/role <TAB> should show role names, output: " + output);
    }

    @Test
    void tabOnNonSlashInputProducesNoCompletions() throws Exception {
        String output = readLineOutputSanitized("hello" + TAB + CR);
        // No completions — input should pass through unchanged
        assertTrue(output.contains("hello"),
                "non-slash input should remain, output: " + output);
        // Should NOT contain any slash commands
        assertFalse(output.contains("/help"));
        assertFalse(output.contains("/tools"));
    }

    @Test
    void tabAfterRagSpaceShowsOnOff() throws Exception {
        // "on" and "off" share common prefix "o" — JLine completes to "o" then needs 2nd TAB.
        // Double-TAB to force the listing.
        String output = readLineOutputSanitized("/rag " + TAB + "" + TAB + CR);
        // After double TAB, either the listing shows "on"/"off" or it inserted "o"
        boolean hasRagArgs = output.contains("on") || output.contains("off");
        assertTrue(hasRagArgs,
                "/rag <TAB><TAB> should show on/off, output: " + output);
    }

    @Test
    void tabAfterSlashExUniqueCompletesToExit() throws Exception {
        // /exi<TAB> — only /exit matches
        String output = readLineOutputSanitized("/exi" + TAB + CR);
        assertTrue(output.contains("exit"),
                "/exi<TAB> should complete to /exit, output: " + output);
    }

    @Test
    void tabAfterJobsShowsJobCommands() throws Exception {
        // /jo<TAB> — matches /jobs, /jobs-remove, /jobs-clear
        String output = readLineOutputSanitized("/jo" + TAB + CR);
        assertTrue(output.contains("jobs"),
                "/jo<TAB> should complete to jobs commands, output: " + output);
    }

    // ========================================================================
    // Standard-chat post display regressions
    // ========================================================================

    @Test
    void slashAutocompleteUsesReservedRowsWithoutScrollingTheTranscript() throws Exception {
        terminal.setSize(new Size(72, 24));
        BackgroundTaskManager tasks = new BackgroundTaskManager();
        BackgroundProcessManager processes =
                new BackgroundProcessManager("chat-completer-managed-menu-test");
        MessageQueue queue = new MessageQueue(
                "chat-completer-managed-menu-" + UUID.randomUUID());
        queue.clear();
        KompileTui tui = new KompileTui(
                tasks, processes, queue, new TerminalRenderer(true));
        tui.setReservedRowsCalculator(StandardChatActivityPanel::reservedRowsForTerminal);
        StandardChatActivityPanel activityPanel = new StandardChatActivityPanel(
                tasks, processes, tui.getStatusBar(), tui::getReservedMiddleRows);
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            processes.registerVirtual(
                    BackgroundProcessManager.ProcessKind.COMMAND,
                    "background", "ACTIVITY SENTINEL", Map.of());
            tui.attachLineReader(reader);
            ChatCompleter.setTerminalRef(reader, terminal);
            ChatCompleter.setContentRedraw(tui::redrawContentView);
            ChatCompleter.setCompletionDisplay(activityPanel::updateCompletions);
            ChatCompleter.enableAutoTrigger(reader);
            tui.start(terminal);
            tui.recordInScrollRegion("TRANSCRIPT SENTINEL");
            activityPanel.refresh();

            Future<String> line = executor.submit(() -> reader.readLine("kompile > "));
            Thread.sleep(100);
            VirtualTerminal frame = new VirtualTerminal(24, 72);
            frame.feed(terminalOutput.toString(StandardCharsets.UTF_8));
            int transcriptTop = tui.scrollTop() - 1;
            int inputRow = tui.inputTop() - 1;
            assertTrue(frame.getRow(transcriptTop).contains("TRANSCRIPT SENTINEL"),
                    frame::screenDump);
            assertTrue(frame.getRow(inputRow).contains("kompile >"), frame::screenDump);

            terminalOutput.reset();
            keyboardPipe.write('/');
            keyboardPipe.flush();
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
            while ((!activityPanel.hasCompletions()
                    || !terminalOutput.toString(StandardCharsets.UTF_8).contains("/help"))
                    && System.nanoTime() < deadline) {
                Thread.sleep(10);
            }
            assertTrue(activityPanel.hasCompletions(), "typing / must open managed completion");
            frame.feed(terminalOutput.toString(StandardCharsets.UTF_8));

            assertTrue(frame.getRow(transcriptTop).contains("TRANSCRIPT SENTINEL"),
                    () -> "autocomplete must not replace the transcript head\n" + frame.screenDump());
            for (int row = transcriptTop; row < inputRow; row++) {
                assertFalse(frame.getRow(row).contains("/help"),
                        () -> "candidates must stay outside transcript rows\n" + frame.screenDump());
            }
            assertTrue(frame.getRow(inputRow).contains("kompile > /"), frame::screenDump);
            boolean helpBelowInput = false;
            for (int row = inputRow + 1; row < 24; row++) {
                helpBelowInput |= frame.getRow(row).contains("/help");
            }
            assertTrue(helpBelowInput,
                    () -> "slash candidates must use the reserved lower panel\n" + frame.screenDump());
            assertFalse(reader.isSet(LineReader.Option.AUTO_LIST));
            assertFalse(reader.isSet(LineReader.Option.LIST_AMBIGUOUS));
            assertFalse(reader.isSet(LineReader.Option.AUTO_MENU));

            // Ambiguous Tab completion must also stay in the managed rows rather
            // than invoking JLine's scrolling candidate list.
            terminalOutput.reset();
            keyboardPipe.write(TAB);
            keyboardPipe.flush();
            Thread.sleep(100);
            frame.feed(terminalOutput.toString(StandardCharsets.UTF_8));
            for (int row = transcriptTop; row < inputRow; row++) {
                assertFalse(frame.getRow(row).contains("/help"), frame::screenDump);
            }
            assertTrue(frame.getRow(inputRow).contains("kompile > /"), frame::screenDump);

            terminalOutput.reset();
            keyboardPipe.write("zzz".getBytes(StandardCharsets.UTF_8));
            keyboardPipe.flush();
            deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
            while (activityPanel.hasCompletions() && System.nanoTime() < deadline) {
                Thread.sleep(10);
            }
            assertFalse(activityPanel.hasCompletions());
            Thread.sleep(100);
            frame.feed(terminalOutput.toString(StandardCharsets.UTF_8));
            for (int row = inputRow + 1; row < 24; row++) {
                assertFalse(frame.getRow(row).contains("/help"),
                        () -> "stale candidates must be cleared from the lower panel\n"
                                + frame.screenDump());
            }
            assertTrue(frame.screenDump().contains("ACTIVITY SENTINEL"),
                    () -> "normal activity rows must return after completion closes\n"
                            + frame.screenDump());

            keyboardPipe.write(CR);
            keyboardPipe.flush();
            assertEquals("/zzz", line.get(5, TimeUnit.SECONDS));
        } finally {
            queue.clear();
            ChatCompleter.clearTerminalRef(reader);
            tui.detachLineReader();
            executor.shutdownNow();
            tui.stop();
            processes.close();
        }
    }

    @Test
    void largeBracketedPasteStaysCompactUntilSubmission() throws Exception {
        ChatCompleter.enableAutoTrigger(reader);
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            String pasted = "x".repeat(1_200);
            Future<String> line = executor.submit(() -> reader.readLine("kompile > "));
            Thread.sleep(75);
            terminalOutput.reset();

            keyboardPipe.write("\033[200~".getBytes(StandardCharsets.UTF_8));
            keyboardPipe.write(pasted.getBytes(StandardCharsets.UTF_8));
            keyboardPipe.write("\033[201~".getBytes(StandardCharsets.UTF_8));
            keyboardPipe.flush();

            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
            while (!reader.getBuffer().toString().contains("[Pasted 1,200 characters]")
                    && System.nanoTime() < deadline) {
                Thread.sleep(10);
            }
            assertEquals("[Pasted 1,200 characters]", reader.getBuffer().toString());
            assertTrue(terminalOutput.toString(StandardCharsets.UTF_8)
                    .contains("[Pasted 1,200 characters]"));

            keyboardPipe.write(CR);
            keyboardPipe.flush();
            assertEquals(pasted, line.get(5, TimeUnit.SECONDS),
                    "ACCEPT_LINE must expand the compact token to the exact pasted text");
        } finally {
            ChatCompleter.clearTerminalRef(reader);
            executor.shutdownNow();
        }
    }

    @Test
    void transcriptMouseEnablesScrollingAndSelectionThenReleasesOnDetach() {
        terminalOutput.reset();
        ChatRepl.enableTranscriptMouse(terminal);
        ChatRepl.enableTranscriptMouse(terminal); // Prompt re-entry restores wheel AND drag reports.
        ChatRepl.disableTranscriptMouse(terminal);
        String output = terminalOutput.toString(StandardCharsets.UTF_8);
        assertEquals("\033[?1000l\033[?1003l\033[?1002h\033[?1006h".repeat(2)
                + "\033[?1000l\033[?1002l\033[?1003l\033[?1006l", output);
        ChatRepl.enableTranscriptMouse(null);
        ChatRepl.disableTranscriptMouse(null);
    }

    @Test
    void rightClickPastesClipboardThroughManagedMouseWithoutExpandingLargeText() throws Exception {
        BackgroundTaskManager tasks = new BackgroundTaskManager();
        BackgroundProcessManager processes =
                new BackgroundProcessManager("chat-completer-right-click-paste-test");
        MessageQueue queue = new MessageQueue(
                "chat-completer-right-click-paste-" + UUID.randomUUID());
        queue.clear();
        KompileTui tui = new KompileTui(
                tasks, processes, queue, new TerminalRenderer(true));
        StandardChatActivityPanel activityPanel = new StandardChatActivityPanel(
                tasks, processes, tui.getStatusBar(), () -> 3);
        ExecutorService executor = Executors.newSingleThreadExecutor();
        String pasted = "clipboard ".repeat(150);
        try {
            tui.attachLineReader(reader);
            ChatCompleter.setTerminalRef(reader, terminal);
            ChatCompleter.setContentRedraw(tui::redrawContentView);
            ChatCompleter.enableAutoTrigger(reader);
            ChatRepl.bindStandardChatActivityKeys(
                    (LineReaderImpl) reader, queue, activityPanel, tui,
                    ignored -> { }, () -> pasted);
            tui.start(terminal);

            Future<String> line = executor.submit(() -> reader.readLine("kompile > "));
            Thread.sleep(75);
            terminalOutput.reset();
            // X10 Button3 press at x=1,y=1. Mouse tracking would normally consume
            // this and prevent the terminal emulator's native context-menu paste.
            keyboardPipe.write("\033[M\"!!".getBytes(StandardCharsets.UTF_8));
            keyboardPipe.flush();

            int chars = pasted.codePointCount(0, pasted.length());
            String token = "[Pasted " + String.format(Locale.ROOT, "%,d", chars)
                    + " characters]";
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
            while (!token.equals(reader.getBuffer().toString())
                    && System.nanoTime() < deadline) {
                Thread.sleep(10);
            }
            assertEquals(token, reader.getBuffer().toString());
            assertTrue(terminalOutput.toString(StandardCharsets.UTF_8).contains(token));

            keyboardPipe.write(" tail".getBytes(StandardCharsets.UTF_8));
            keyboardPipe.write(CR);
            keyboardPipe.flush();
            assertEquals(pasted + " tail", line.get(5, TimeUnit.SECONDS));
        } finally {
            queue.clear();
            ChatCompleter.clearTerminalRef(reader);
            tui.detachLineReader();
            executor.shutdownNow();
            tui.stop();
            processes.close();
        }
    }

    @Test
    void sgrDragHighlightsRightClickCopiesAndWheelStillScrolls() throws Exception {
        BackgroundTaskManager tasks = new BackgroundTaskManager();
        BackgroundProcessManager processes =
                new BackgroundProcessManager("chat-completer-selection-copy-test");
        MessageQueue queue = new MessageQueue(
                "chat-completer-selection-copy-" + UUID.randomUUID());
        queue.clear();
        KompileTui tui = new KompileTui(
                tasks, processes, queue, new TerminalRenderer(true));
        StandardChatActivityPanel activityPanel = new StandardChatActivityPanel(
                tasks, processes, tui.getStatusBar(), () -> 3);
        ExecutorService executor = Executors.newSingleThreadExecutor();
        AtomicReference<String> copied = new AtomicReference<>();
        try {
            tui.attachLineReader(reader);
            ChatCompleter.setTerminalRef(reader, terminal);
            ChatCompleter.setContentRedraw(tui::redrawContentView);
            ChatCompleter.enableAutoTrigger(reader);
            ChatRepl.bindStandardChatActivityKeys(
                    (LineReaderImpl) reader, queue, activityPanel, tui,
                    ignored -> { }, () -> "paste me", copied::set);
            tui.start(terminal);
            ChatRepl.enableTranscriptMouse(terminal);
            tui.recordInScrollRegion("alpha");
            tui.recordInScrollRegion("bravo");

            Future<String> line = executor.submit(() -> reader.readLine("kompile > "));
            Thread.sleep(75);
            terminalOutput.reset();

            // SGR Button1 press, drag, and release across the first two transcript rows.
            keyboardPipe.write("\033[<0;1;4M".getBytes(StandardCharsets.UTF_8));
            keyboardPipe.write("\033[<32;5;5M".getBytes(StandardCharsets.UTF_8));
            keyboardPipe.write("\033[<0;5;5m".getBytes(StandardCharsets.UTF_8));
            keyboardPipe.flush();

            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
            while (!"alpha\nbravo".equals(tui.getSelectedTranscriptText())
                    && System.nanoTime() < deadline) {
                Thread.sleep(10);
            }
            assertEquals("alpha\nbravo", tui.getSelectedTranscriptText());

            VirtualTerminal selectedFrame = new VirtualTerminal(40, 120);
            selectedFrame.feed(terminalOutput.toString(StandardCharsets.UTF_8));
            assertTrue(selectedFrame.getStyledRow(tui.scrollTop() - 1).contains(";7"),
                    () -> "first selected row must use inverse video\n" + selectedFrame.screenDump());
            assertTrue(selectedFrame.getStyledRow(tui.scrollTop()).contains(";7"),
                    () -> "second selected row must use inverse video\n" + selectedFrame.screenDump());

            // Button3 on the transcript copies the retained selection.
            keyboardPipe.write("\033[<2;1;4M\033[<2;1;4m"
                    .getBytes(StandardCharsets.UTF_8));
            keyboardPipe.flush();
            deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
            while (copied.get() == null && System.nanoTime() < deadline) Thread.sleep(10);
            assertEquals("alpha\nbravo", copied.get());
            assertEquals("", reader.getBuffer().toString(),
                    "copying transcript text must not modify the composer");

            // Copying must not turn off wheel scrolling in the same prompt.
            for (int i = 0; i < 80; i++) tui.recordInScrollRegion("history " + i);
            assertEquals(0, tui.getContentScrollOffset());
            keyboardPipe.write("\033[<64;1;4M".getBytes(StandardCharsets.UTF_8));
            keyboardPipe.flush();
            deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
            while (tui.getContentScrollOffset() == 0 && System.nanoTime() < deadline) {
                Thread.sleep(10);
            }
            assertEquals(3, tui.getContentScrollOffset(), "wheel up must scroll after copying");
            keyboardPipe.write("\033[<65;1;4M".getBytes(StandardCharsets.UTF_8));
            keyboardPipe.flush();
            deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
            while (tui.getContentScrollOffset() != 0 && System.nanoTime() < deadline) {
                Thread.sleep(10);
            }
            assertEquals(0, tui.getContentScrollOffset(), "wheel down must return to the bottom");

            // Button3 on the input row keeps the existing managed paste behavior.
            String inputPaste = String.format(Locale.ROOT,
                    "\033[<2;1;%dM\033[<2;1;%dm", tui.scrollBottom(), tui.scrollBottom());
            keyboardPipe.write(inputPaste.getBytes(StandardCharsets.UTF_8));
            keyboardPipe.flush();
            deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
            while (!"paste me".equals(reader.getBuffer().toString())
                    && System.nanoTime() < deadline) {
                Thread.sleep(10);
            }
            assertEquals("paste me", reader.getBuffer().toString());

            keyboardPipe.write(CR);
            keyboardPipe.flush();
            assertEquals("paste me", line.get(5, TimeUnit.SECONDS));
        } finally {
            queue.clear();
            ChatCompleter.clearTerminalRef(reader);
            tui.detachLineReader();
            executor.shutdownNow();
            tui.stop();
            processes.close();
        }
    }

    @Test
    void emptyQueueDoesNotReservePermanentPostRows() {
        ChatCompleter.setQueueSupplier(List::of);
        assertEquals("", ChatCompleter.buildPostWithQueue());
    }

    @Test
    void queuedMessagesDoNotOccupyTheInputPostArea() {
        ChatCompleter.setQueueSupplier(() -> List.of("fix the crawl"));
        assertEquals("", ChatCompleter.buildPostWithQueue());
        ChatCompleter.setQueueSupplier(null);
    }

    @Test
    void interruptedActivityClearsWhenTheNextMessageStarts() throws Exception {
        ChatCompleter.setTerminalRef(reader, terminal);
        ChatCompleter.enableAutoTrigger(reader);
        ChatCompleter.markInterrupted();
        assertEquals("Interrupted by user", ChatCompleter.getActivity());

        try {
            assertEquals("h", readLineResult("h" + CR));
            assertNull(ChatCompleter.getActivity(),
                    "typing the next message should clear the transient interruption marker");
        } finally {
            ChatCompleter.clearTerminalRef(reader);
        }
    }

    // ========================================================================
    // Autosuggestion widget verification
    // ========================================================================

    @Test
    void autosuggestionWidgetCanBeEnabled() {
        LineReaderImpl impl = (LineReaderImpl) reader;
        assertNotNull(impl.getKeyMaps());
    }

    @Test
    void lineReaderOptionsAreSet() {
        assertTrue(reader.isSet(LineReader.Option.AUTO_LIST));
        assertTrue(reader.isSet(LineReader.Option.LIST_AMBIGUOUS));
        assertTrue(reader.isSet(LineReader.Option.AUTO_MENU));
    }

    @Test
    void readLineReturnsCompletedValue() throws Exception {
        // /hel<TAB><CR> — JLine completes to "/help" then appends a space (complete=true),
        // so the returned line is "/help " with trailing space.
        String result = readLineResult("/hel" + TAB + CR);
        assertTrue(result.startsWith("/help"),
                "readLine should return the completed value, got: '" + result + "'");
    }

    // ========================================================================
    // Candidate description rendering in output
    // ========================================================================

    @Test
    void queueCompletionShowsDescriptions() throws Exception {
        // When listing queue commands, descriptions should appear in parentheses
        String output = readLineOutputSanitized("/queue-" + TAB + CR);
        boolean hasDescriptions =
                output.contains("Clear all queued messages") ||
                output.contains("Send next queued message") ||
                output.contains("Remove a queued message") ||
                output.contains("Show queue status") ||
                output.contains("Send all queued messages");
        assertTrue(hasDescriptions,
                "/queue-<TAB> should show command descriptions, output: " + output);
    }

    @Test
    void toolCompletionShowsDescriptions() throws Exception {
        String output = readLineOutputSanitized("/tool " + TAB + CR);
        boolean hasDescriptions =
                output.contains("Read a file") ||
                output.contains("Write a file") ||
                output.contains("Search contents") ||
                output.contains("Run shell cmd");
        assertTrue(hasDescriptions,
                "/tool <TAB> should show tool descriptions, output: " + output);
    }

    @Test
    void fullChatHotkeySetupPreservesOrdinaryTyping() throws Exception {
        LineReaderImpl impl = (LineReaderImpl) reader;
        ChatCompleter.enableAutoTrigger(reader);
        for (KeyMap<Binding> keyMap : impl.getKeyMaps().values()) {
            if (keyMap != null) {
                keyMap.setAmbiguousTimeout(80L);
            }
        }
        ChatRepl.bindCancelKey(impl.getKeyMaps(), "\033");
        ChatRepl.bindModeSwitchingHotkeys(impl.getKeyMaps().get(LineReader.EMACS));

        ChatCompleter.setContentRedraw(() -> {
            terminal.writer().print("R");
            terminal.writer().flush();
        });
        try {
            assertEquals("abcdefghijklmnopqrstuvwxyz", readLineResult("abcdefghijklmnopqrstuvwxyz" + CR),
                    "chat hotkey registration must never swallow printable input");
        } finally {
            ChatCompleter.setContentRedraw(null);
        }

    }

    @Test
    void realAnsiTuiRedrawDuringTypingPreservesPrintableInput() throws Exception {
        BackgroundProcessManager processes =
                new BackgroundProcessManager("chat-completer-real-tui-test");
        KompileTui tui = new KompileTui(
                new BackgroundTaskManager(), processes,
                new MessageQueue("chat-completer-real-tui-queue"),
                new TerminalRenderer(true));
        PrintStream previousOut = System.out;
        try {
            // KompileTui uses the process-wide writer for cursor-addressed redraws;
            // route it into the same xterm capture used by the LineReader.
            System.setOut(new PrintStream(terminalOutput, true, StandardCharsets.UTF_8));
            tui.start(terminal);
            tui.showActivityView("process:live", "live process", "one\ntwo\nthree");
            ChatCompleter.setTerminalRef(reader, terminal);
            ChatCompleter.setContentRedraw(tui::redrawContentView);
            ChatCompleter.enableAutoTrigger(reader);
            for (KeyMap<Binding> keyMap : ((LineReaderImpl) reader).getKeyMaps().values()) {
                if (keyMap != null) {
                    keyMap.setAmbiguousTimeout(80L);
                }
            }
            ChatRepl.bindCancelKey(((LineReaderImpl) reader).getKeyMaps(), "\\033");
            assertEquals("typed", readLineResult("typed" + CR),
                    "cursor-addressed TUI redraw must not swallow printable input");
        } finally {
            ChatCompleter.clearTerminalRef(reader);
            tui.stop();
            System.setOut(previousOut);
            processes.close();
        }
    }

    @Test
    void realAnsiTuiStreamedOutputDuringTypingPreservesPrintableInput() throws Exception {
        BackgroundProcessManager processes =
                new BackgroundProcessManager("chat-completer-real-tui-stream-test");
        KompileTui tui = new KompileTui(
                new BackgroundTaskManager(), processes,
                new MessageQueue("chat-completer-real-tui-stream-queue"),
                new TerminalRenderer(true));
        PrintStream previousOut = System.out;
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            System.setOut(new PrintStream(terminalOutput, true, StandardCharsets.UTF_8));
            tui.start(terminal);
            tui.showActivityView("process:live", "live process", "one\ntwo\nthree");
            ChatCompleter.setTerminalRef(reader, terminal);
            ChatCompleter.setContentRedraw(tui::redrawContentView);
            ChatCompleter.setContentOutput(tui::recordInScrollRegion);
            Future<String> line = executor.submit(() -> reader.readLine("> "));
            Thread.sleep(75);
            ChatCompleter.printAbove("streamed while editing");
            keyboardPipe.write("typed".getBytes(StandardCharsets.UTF_8));
            keyboardPipe.write(CR);
            keyboardPipe.flush();
            assertEquals("typed", line.get(5, TimeUnit.SECONDS),
                    "live TUI output must not swallow printable input");
        } finally {
            ChatCompleter.clearTerminalRef(reader);
            executor.shutdownNow();
            tui.stop();
            System.setOut(previousOut);
            processes.close();
        }
    }

    @Test
    void largeRepeatedActivityFramesPreservePrintableInput() throws Exception {
        BackgroundProcessManager processes =
                new BackgroundProcessManager("chat-completer-large-frame-test");
        KompileTui tui = new KompileTui(
                new BackgroundTaskManager(), processes,
                new MessageQueue("chat-completer-large-frame-queue"),
                new TerminalRenderer(true));
        PrintStream previousOut = System.out;
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            System.setOut(new PrintStream(terminalOutput, true, StandardCharsets.UTF_8));
            tui.attachLineReader(reader);
            tui.start(terminal);
            Future<String> line = executor.submit(() -> reader.readLine("kompile > "));
            Thread.sleep(75);
            VirtualTerminal frame = new VirtualTerminal(40, 120);
            frame.feed(terminalOutput.toString(StandardCharsets.UTF_8));
            assertTrue(frame.getRow(tui.inputTop() - 1).contains("kompile >"),
                    frame::screenDump);
            terminalOutput.reset();

            String largeContent = String.join("\n",
                    Collections.nCopies(200, "large tool result row"));
            for (int i = 0; i < 20; i++) {
                tui.showActivityView("process:large", "large result " + i, largeContent);
            }
            Thread.sleep(150);
            String redrawOutput = terminalOutput.toString(StandardCharsets.UTF_8);
            assertFalse(redrawOutput.contains("kompile > "),
                    "large async frames must not repaint an unchanged chat prompt");
            assertTrue(redrawOutput.contains("\0337\033[?25l"),
                    "large async frames must save the prompt cursor before painting");
            assertTrue(redrawOutput.contains("\0338\033[?25h"),
                    "large async frames must restore the prompt cursor after painting");
            frame.feed(redrawOutput);
            assertTrue(frame.getRow(tui.inputTop() - 1).contains("kompile >"),
                    frame::screenDump);

            keyboardPipe.write("typed".getBytes(StandardCharsets.UTF_8));
            keyboardPipe.write(CR);
            keyboardPipe.flush();
            assertEquals("typed", line.get(5, TimeUnit.SECONDS),
                    "coalesced full-frame redraws must not move the input anchor");
        } finally {
            tui.detachLineReader();
            executor.shutdownNow();
            tui.stop();
            System.setOut(previousOut);
            processes.close();
        }
    }

    @Test
    void multilineDraftRemainsVisibleAcrossAsyncTuiRedraw() throws Exception {
        terminal.setSize(new Size(72, 24));
        BackgroundProcessManager processes =
                new BackgroundProcessManager("chat-completer-multiline-redraw-test");
        MessageQueue queue = new MessageQueue(
                "chat-completer-multiline-redraw-queue-" + UUID.randomUUID());
        AtomicBoolean allowAsyncRedraw = new AtomicBoolean(false);
        KompileTui tui = new KompileTui(
                new BackgroundTaskManager(), processes, queue, new TerminalRenderer(true)) {
            @Override
            public void requestRedraw() {
                if (allowAsyncRedraw.get()) {
                    super.requestRedraw();
                }
            }
        };
        ExecutorService executor = Executors.newSingleThreadExecutor();
        String draft = "alpha draft row\nbeta draft row\ngamma draft row";
        try {
            tui.attachLineReader(reader);
            ChatCompleter.setTerminalRef(reader, terminal);
            ChatCompleter.enableAutoTrigger(reader);
            tui.start(terminal);

            Future<String> line = executor.submit(() -> reader.readLine("kompile > "));
            Thread.sleep(75);
            keyboardPipe.write("\033[200~".getBytes(StandardCharsets.UTF_8));
            keyboardPipe.write(draft.getBytes(StandardCharsets.UTF_8));
            keyboardPipe.write("\033[201~".getBytes(StandardCharsets.UTF_8));
            keyboardPipe.flush();

            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
            while (!draft.equals(reader.getBuffer().toString())
                    && System.nanoTime() < deadline) {
                Thread.sleep(10);
            }
            assertEquals(draft, reader.getBuffer().toString());
            Thread.sleep(75);

            VirtualTerminal frame = new VirtualTerminal(24, 72);
            frame.feed(terminalOutput.toString(StandardCharsets.UTF_8));
            assertTrue(frame.screenDump().contains("alpha draft row"), frame::screenDump);
            assertTrue(frame.screenDump().contains("beta draft row"), frame::screenDump);
            assertTrue(frame.screenDump().contains("gamma draft row"), frame::screenDump);

            terminalOutput.reset();
            allowAsyncRedraw.set(true);
            tui.recordInScrollRegion("async transcript update");
            deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
            while (!terminalOutput.toString(StandardCharsets.UTF_8)
                    .contains("async transcript update") && System.nanoTime() < deadline) {
                Thread.sleep(10);
            }
            Thread.sleep(75);

            frame.feed(terminalOutput.toString(StandardCharsets.UTF_8));
            assertTrue(frame.screenDump().contains("alpha draft row"),
                    () -> "async redraw must preserve the first input row\n" + frame.screenDump());
            assertTrue(frame.screenDump().contains("beta draft row"),
                    () -> "async redraw must preserve the middle input row\n" + frame.screenDump());
            assertTrue(frame.screenDump().contains("gamma draft row"),
                    () -> "async redraw must preserve the final input row\n" + frame.screenDump());
            assertEquals(draft, reader.getBuffer().toString(),
                    "redraw must not alter the multiline input buffer");

            keyboardPipe.write(CR);
            keyboardPipe.flush();
            assertEquals(draft, line.get(5, TimeUnit.SECONDS));
        } finally {
            queue.clear();
            ChatCompleter.clearTerminalRef(reader);
            tui.detachLineReader();
            executor.shutdownNow();
            tui.stop();
            processes.close();
        }
    }

    @Test
    void shrinkingMultilineDraftAfterAsyncRedrawDoesNotEraseTranscript() throws Exception {
        terminal.setSize(new Size(72, 24));
        BackgroundProcessManager processes =
                new BackgroundProcessManager("chat-completer-multiline-shrink-test");
        MessageQueue queue = new MessageQueue(
                "chat-completer-multiline-shrink-queue-" + UUID.randomUUID());
        AtomicBoolean allowAsyncRedraw = new AtomicBoolean(false);
        KompileTui tui = new KompileTui(
                new BackgroundTaskManager(), processes, queue, new TerminalRenderer(true)) {
            @Override
            public void requestRedraw() {
                if (allowAsyncRedraw.get()) {
                    super.requestRedraw();
                }
            }
        };
        ExecutorService executor = Executors.newSingleThreadExecutor();
        String retained = "alpha draft row";
        String draft = retained + "\nbeta draft row\ngamma draft row";
        String transcriptTail = "TRANSCRIPT TAIL MUST REMAIN SEPARATE";
        try {
            tui.attachLineReader(reader);
            ChatCompleter.setTerminalRef(reader, terminal);
            ChatCompleter.enableAutoTrigger(reader);
            tui.start(terminal);
            for (int i = 0; i < 40; i++) {
                tui.recordInScrollRegion("retained transcript row " + i);
            }

            Future<String> line = executor.submit(() -> reader.readLine("kompile > "));
            Thread.sleep(75);
            keyboardPipe.write("\033[200~".getBytes(StandardCharsets.UTF_8));
            keyboardPipe.write(draft.getBytes(StandardCharsets.UTF_8));
            keyboardPipe.write("\033[201~".getBytes(StandardCharsets.UTF_8));
            keyboardPipe.flush();

            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
            while (!draft.equals(reader.getBuffer().toString())
                    && System.nanoTime() < deadline) {
                Thread.sleep(10);
            }
            assertEquals(draft, reader.getBuffer().toString());
            Thread.sleep(75);

            VirtualTerminal frame = new VirtualTerminal(24, 72);
            frame.feed(terminalOutput.toString(StandardCharsets.UTF_8));
            terminalOutput.reset();

            assertEquals(4, tui.getInputRegionRows());
            assertEquals(tui.transcriptBottom() + 1, tui.inputSeparatorRow());
            assertEquals(tui.inputSeparatorRow() + 1, tui.inputTop(),
                    "a separator must keep transcript and input ownership disjoint");

            // Force a complete frame while JLine owns three physical rows. The
            // transcript tail and the editor must never be painted into the same
            // cells, including when Backspace later collapses the editor to one row.
            allowAsyncRedraw.set(true);
            tui.recordInScrollRegion(transcriptTail);
            deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
            while (!terminalOutput.toString(StandardCharsets.UTF_8).contains(transcriptTail)
                    && System.nanoTime() < deadline) {
                Thread.sleep(10);
            }
            Thread.sleep(75);
            frame.feed(terminalOutput.toString(StandardCharsets.UTF_8));
            int transcriptTailRow = tui.transcriptBottom() - 1;
            int inputTopRow = tui.inputTop() - 1;
            assertTrue(frame.getRow(transcriptTailRow).contains(transcriptTail),
                    () -> "multiline input must not cover transcript-owned rows during redraw\n"
                            + frame.screenDump());
            assertTrue(frame.getRow(inputTopRow).contains("kompile > " + retained),
                    () -> "the editor must start inside its dedicated pane\n" + frame.screenDump());
            terminalOutput.reset();

            Thread redraws = new Thread(() -> {
                for (int i = 0; i < 40; i++) {
                    tui.redrawBars();
                    LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(2));
                }
            }, "multiline-shrink-redraws");
            redraws.start();
            for (int i = retained.length(); i < draft.length(); i++) {
                keyboardPipe.write('\177');
                keyboardPipe.flush();
                LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(1));
            }
            redraws.join(2_000);
            assertFalse(redraws.isAlive(), "redraw driver must complete");
            deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
            while (!retained.equals(reader.getBuffer().toString())
                    && System.nanoTime() < deadline) {
                Thread.sleep(10);
            }
            assertEquals(retained, reader.getBuffer().toString());
            Thread.sleep(75);
            frame.feed(terminalOutput.toString(StandardCharsets.UTF_8));

            assertTrue(frame.getRow(transcriptTailRow).contains(transcriptTail),
                    () -> "shrinking input must not clear transcript-owned rows\n"
                            + frame.screenDump());
            assertTrue(frame.getRow(inputTopRow).contains("kompile > " + retained),
                    frame::screenDump);
            assertFalse(frame.screenDump().contains("beta draft row"), frame::screenDump);
            assertFalse(frame.screenDump().contains("gamma draft row"), frame::screenDump);

            keyboardPipe.write(CR);
            keyboardPipe.flush();
            assertEquals(retained, line.get(5, TimeUnit.SECONDS));
        } finally {
            queue.clear();
            ChatCompleter.clearTerminalRef(reader);
            tui.detachLineReader();
            executor.shutdownNow();
            tui.stop();
            processes.close();
        }
    }

    @Test
    void multilineDraftOverflowScrollsOnlyInsideInputPane() throws Exception {
        terminal.setSize(new Size(72, 24));
        BackgroundProcessManager processes =
                new BackgroundProcessManager("chat-completer-input-pane-overflow-test");
        MessageQueue queue = new MessageQueue(
                "chat-completer-input-pane-overflow-queue-" + UUID.randomUUID());
        KompileTui tui = new KompileTui(
                new BackgroundTaskManager(), processes, queue, new TerminalRenderer(true));
        ExecutorService executor = Executors.newSingleThreadExecutor();
        String transcriptTail = "TRANSCRIPT TAIL OUTSIDE INPUT PANE";
        String draft = String.join("\n", List.of(
                "input row one", "input row two", "input row three",
                "input row four", "input row five", "input row six"));
        try {
            tui.attachLineReader(reader);
            ChatCompleter.setTerminalRef(reader, terminal);
            ChatCompleter.enableAutoTrigger(reader);
            for (int i = 0; i < 20; i++) {
                tui.recordInScrollRegion("overflow transcript row " + i);
            }
            tui.recordInScrollRegion(transcriptTail);
            tui.start(terminal);

            Future<String> line = executor.submit(() -> reader.readLine("kompile > "));
            Thread.sleep(75);
            keyboardPipe.write("\033[200~".getBytes(StandardCharsets.UTF_8));
            keyboardPipe.write(draft.getBytes(StandardCharsets.UTF_8));
            keyboardPipe.write("\033[201~".getBytes(StandardCharsets.UTF_8));
            keyboardPipe.flush();

            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
            while (!draft.equals(reader.getBuffer().toString())
                    && System.nanoTime() < deadline) {
                Thread.sleep(10);
            }
            assertEquals(draft, reader.getBuffer().toString());
            Thread.sleep(75);

            VirtualTerminal frame = new VirtualTerminal(24, 72);
            frame.feed(terminalOutput.toString(StandardCharsets.UTF_8));
            assertTrue(frame.getRow(tui.transcriptBottom() - 1).contains(transcriptTail),
                    () -> "input overflow must not scroll the transcript\n" + frame.screenDump());
            assertTrue(frame.getRow(tui.inputSeparatorRow() - 1).contains("──"),
                    () -> "input pane must retain its visual separator\n" + frame.screenDump());
            for (int row = tui.scrollTop() - 1; row < tui.inputTop() - 1; row++) {
                assertFalse(frame.getRow(row).contains("input row"),
                        "input text escaped into transcript row " + row + "\n"
                                + frame.screenDump());
            }
            String inputPane = java.util.stream.IntStream
                    .rangeClosed(tui.inputTop() - 1, tui.scrollBottom() - 1)
                    .mapToObj(frame::getRow)
                    .collect(java.util.stream.Collectors.joining("\n"));
            assertTrue(inputPane.contains("input row six"),
                    () -> "the input tail must remain visible inside its pane\n"
                            + frame.screenDump());

            keyboardPipe.write(CR);
            keyboardPipe.flush();
            assertEquals(draft, line.get(5, TimeUnit.SECONDS));
        } finally {
            queue.clear();
            ChatCompleter.clearTerminalRef(reader);
            tui.detachLineReader();
            executor.shutdownNow();
            tui.stop();
            processes.close();
        }
    }

    @Test
    void wrappedDraftRemainsVisibleAcrossAsyncTuiRedraw() throws Exception {
        terminal.setSize(new Size(48, 20));
        BackgroundProcessManager processes =
                new BackgroundProcessManager("chat-completer-wrapped-redraw-test");
        MessageQueue queue = new MessageQueue(
                "chat-completer-wrapped-redraw-queue-" + UUID.randomUUID());
        AtomicBoolean allowAsyncRedraw = new AtomicBoolean(false);
        KompileTui tui = new KompileTui(
                new BackgroundTaskManager(), processes, queue, new TerminalRenderer(true)) {
            @Override
            public void requestRedraw() {
                if (allowAsyncRedraw.get()) {
                    super.requestRedraw();
                }
            }
        };
        ExecutorService executor = Executors.newSingleThreadExecutor();
        String draft = "wrapped-start " + "x".repeat(55) + " wrapped-end";
        try {
            tui.attachLineReader(reader);
            ChatCompleter.setTerminalRef(reader, terminal);
            ChatCompleter.enableAutoTrigger(reader);
            tui.start(terminal);

            Future<String> line = executor.submit(() -> reader.readLine("kompile > "));
            Thread.sleep(75);
            keyboardPipe.write(draft.getBytes(StandardCharsets.UTF_8));
            keyboardPipe.flush();

            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
            while (!draft.equals(reader.getBuffer().toString())
                    && System.nanoTime() < deadline) {
                Thread.sleep(10);
            }
            assertEquals(draft, reader.getBuffer().toString());
            Thread.sleep(75);

            VirtualTerminal frame = new VirtualTerminal(20, 48);
            frame.feed(terminalOutput.toString(StandardCharsets.UTF_8));
            assertTrue(frame.screenDump().contains("wrapped-start"), frame::screenDump);
            assertTrue(frame.screenDump().contains("wrapped-end"), frame::screenDump);

            terminalOutput.reset();
            allowAsyncRedraw.set(true);
            tui.recordInScrollRegion("wrapped draft transcript update");
            deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
            while (!terminalOutput.toString(StandardCharsets.UTF_8)
                    .contains("wrapped draft transcript update")
                    && System.nanoTime() < deadline) {
                Thread.sleep(10);
            }
            Thread.sleep(75);

            frame.feed(terminalOutput.toString(StandardCharsets.UTF_8));
            assertTrue(frame.screenDump().contains("wrapped-start"),
                    () -> "async redraw must preserve the first wrapped row\n"
                            + frame.screenDump());
            assertTrue(frame.screenDump().contains("wrapped-end"),
                    () -> "async redraw must preserve the final wrapped row\n"
                            + frame.screenDump());
            assertEquals(draft, reader.getBuffer().toString(),
                    "redraw must not alter the wrapped input buffer");

            keyboardPipe.write(CR);
            keyboardPipe.flush();
            assertEquals(draft, line.get(5, TimeUnit.SECONDS));
        } finally {
            queue.clear();
            ChatCompleter.clearTerminalRef(reader);
            tui.detachLineReader();
            executor.shutdownNow();
            tui.stop();
            processes.close();
        }
    }

    @Test
    void oversizedAnsiToolLineNeverWrapsIntoInputOrFixedRows() throws Exception {
        terminal.setSize(new Size(48, 20));
        BackgroundTaskManager tasks = new BackgroundTaskManager();
        BackgroundProcessManager processes =
                new BackgroundProcessManager("chat-completer-hard-input-boundary-test");
        MessageQueue queue = new MessageQueue(
                "chat-completer-hard-input-boundary-" + UUID.randomUUID());
        queue.clear();
        KompileTui tui = new KompileTui(
                tasks, processes, queue, new TerminalRenderer(true));
        tui.setReservedRowsCalculator((height, width) -> 2);
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            tui.attachLineReader(reader);
            ChatCompleter.setTerminalRef(reader, terminal);
            ChatCompleter.setContentRedraw(tui::redrawContentView);
            ChatCompleter.setContentOutput(tui::recordInScrollRegion);
            ChatCompleter.enableAutoTrigger(reader);
            tui.start(terminal);

            Future<String> line = executor.submit(() -> reader.readLine("kompile > "));
            Thread.sleep(75);
            keyboardPipe.write("draft".getBytes(StandardCharsets.UTF_8));
            keyboardPipe.flush();
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
            while (reader.getBuffer().length() < 5 && System.nanoTime() < deadline) {
                Thread.sleep(5);
            }

            VirtualTerminal frame = new VirtualTerminal(20, 48);
            frame.feed(terminalOutput.toString(StandardCharsets.UTF_8));
            int inputRow = tui.inputTop() - 1;
            assertTrue(frame.getRow(inputRow).contains("kompile > draft"), frame::screenDump);
            terminalOutput.reset();
            String oversized = "  ▸ Tool " + "\033[36m" + "x".repeat(2_000) + "\033[0m";
            ChatCompleter.printAbove(oversized);
            deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
            while (!terminalOutput.toString(StandardCharsets.UTF_8).contains("xxx")
                    && System.nanoTime() < deadline) {
                Thread.sleep(10);
            }

            frame.feed(terminalOutput.toString(StandardCharsets.UTF_8));
            assertTrue(frame.getRow(inputRow).contains("kompile > draft"),
                    () -> "oversized tool output must preserve the input row\n" + frame.screenDump());
            assertFalse(frame.getRow(inputRow).contains("xxx"),
                    "tool content must never auto-wrap into the input row");
            for (int row = tui.queueTop() - 1; row < 20; row++) {
                assertFalse(frame.getRow(row).contains("xxx"),
                        "tool output must not enter queue/activity/status rows");
            }
            assertEquals(inputRow, frame.getCursorRow());
            assertEquals("kompile > draft".length(), frame.getCursorCol());

            keyboardPipe.write(CR);
            keyboardPipe.flush();
            assertEquals("draft", line.get(5, TimeUnit.SECONDS));
        } finally {
            queue.clear();
            ChatCompleter.clearTerminalRef(reader);
            tui.detachLineReader();
            executor.shutdownNow();
            tui.stop();
            processes.close();
        }
    }

    @Test
    void viewportScrollingRepaintsContentAndRestoresCursorAfterPrompt() throws Exception {
        BackgroundTaskManager tasks = new BackgroundTaskManager();
        BackgroundProcessManager processes =
                new BackgroundProcessManager("chat-completer-scroll-cursor-test");
        MessageQueue queue = new MessageQueue("chat-completer-scroll-cursor-queue");
        KompileTui tui = new KompileTui(
                tasks, processes, queue, new TerminalRenderer(true)) {
            @Override
            public void requestRedraw() {
                // Exercise the input widget's synchronous repaint contract. A
                // later background frame must not be allowed to hide the race.
            }
        };
        AtomicInteger resizeEvents = new AtomicInteger();
        tui.addResizeListener(resizeEvents::incrementAndGet);
        StandardChatActivityPanel activityPanel = new StandardChatActivityPanel(
                tasks, processes, tui.getStatusBar(), () -> 3);
        PrintStream previousOut = System.out;
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            System.setOut(new PrintStream(terminalOutput, true, StandardCharsets.UTF_8));
            tui.attachLineReader(reader);
            tui.start(terminal);
            for (int i = 1; i <= 80; i++) {
                tui.recordInScrollRegion("retained line " + i);
            }
            ChatRepl.bindStandardChatActivityKeys(
                    (LineReaderImpl) reader, queue, activityPanel, tui);

            Future<String> line = executor.submit(() -> reader.readLine("kompile > "));
            Thread.sleep(75);
            terminalOutput.reset();
            keyboardPipe.write("\033[5~".getBytes(StandardCharsets.UTF_8));
            keyboardPipe.flush();

            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
            while (tui.getContentScrollOffset() == 0 && System.nanoTime() < deadline) {
                Thread.sleep(10);
            }
            assertTrue(tui.getContentScrollOffset() > 0,
                    "PageUp must move the managed transcript viewport");

            keyboardPipe.write("\033[6~".getBytes(StandardCharsets.UTF_8));
            keyboardPipe.flush();
            deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
            while (tui.getContentScrollOffset() != 0 && System.nanoTime() < deadline) {
                Thread.sleep(10);
            }
            assertEquals(0, tui.getContentScrollOffset(),
                    "PageDown must return a one-page scroll to the live tail");

            keyboardPipe.write("\033[5~".getBytes(StandardCharsets.UTF_8));
            keyboardPipe.flush();
            deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
            while (tui.getContentScrollOffset() == 0 && System.nanoTime() < deadline) {
                Thread.sleep(10);
            }

            keyboardPipe.write("\033[1;5F".getBytes(StandardCharsets.UTF_8));
            keyboardPipe.flush();
            deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
            while (tui.getContentScrollOffset() != 0 && System.nanoTime() < deadline) {
                Thread.sleep(10);
            }
            assertEquals(0, tui.getContentScrollOffset(),
                    "Ctrl+End must return the viewport to the live tail");

            terminalOutput.reset();
            keyboardPipe.write("\033[M`!!".getBytes(StandardCharsets.UTF_8));
            keyboardPipe.flush();
            deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
            while (tui.getContentScrollOffset() == 0 && System.nanoTime() < deadline) {
                Thread.sleep(10);
            }
            while (!terminalOutput.toString(StandardCharsets.UTF_8).contains("kompile > ")
                    && System.nanoTime() < deadline) {
                Thread.sleep(10);
            }

            assertTrue(tui.getContentScrollOffset() > 0,
                    "mouse wheel up must move the managed transcript viewport");
            assertFalse(tui.getVisibleContentLines().contains("retained line 80"),
                    "the scrolled viewport must no longer show the live tail");

            VirtualTerminal frame = new VirtualTerminal(40, 120);
            frame.feed(terminalOutput.toString(StandardCharsets.UTF_8));
            int controlY = tui.scrollToBottomControlY();
            int controlX = tui.scrollToBottomControlX();
            assertTrue(frame.getRow(controlY).contains("[↓ Scroll to bottom]"),
                    () -> "scrolled viewport must show the floating clickable notification\n"
                            + frame.screenDump());
            assertTrue(frame.getRow(tui.inputTop() - 1).contains("kompile >"),
                    () -> "the prompt must be repainted on the input row\n" + frame.screenDump());
            assertEquals(tui.inputTop() - 1, frame.getCursorRow(),
                    "viewport repaint must return the cursor to the input row");
            assertEquals("kompile > ".length(), frame.getCursorCol(),
                    "the text cursor must be restored after the prompt");

            int originalControlX = controlX;
            terminalOutput.reset();
            terminal.setSize(new Size(100, 40));
            deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
            while ((tui.getTerminalWidth() != 100 || resizeEvents.get() == 0)
                    && System.nanoTime() < deadline) {
                Thread.sleep(10);
            }
            assertEquals(100, tui.getTerminalWidth(),
                    "terminal size polling must detect resize without a WINCH callback");
            assertTrue(resizeEvents.get() > 0,
                    "detected resize must notify dependent rendering layers");
            // This fixture suppresses async frames; a real bound key drives the
            // synchronous JLine redraw after the size change.
            keyboardPipe.write("\033[5~".getBytes(StandardCharsets.UTF_8));
            keyboardPipe.flush();
            deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
            while (!terminalOutput.toString(StandardCharsets.UTF_8)
                    .contains("[↓ Scroll to bottom]") && System.nanoTime() < deadline) {
                Thread.sleep(10);
            }
            controlX = tui.scrollToBottomControlX();
            controlY = tui.scrollToBottomControlY();
            assertNotEquals(originalControlX, controlX,
                    "the floating notification must recenter after a terminal resize");
            VirtualTerminal resizedFrame = new VirtualTerminal(40, 100);
            resizedFrame.feed(terminalOutput.toString(StandardCharsets.UTF_8));
            assertTrue(resizedFrame.getRow(controlY).contains("[↓ Scroll to bottom]"),
                    () -> "resized viewport must repaint the moving notification\n"
                            + resizedFrame.screenDump());

            terminalOutput.reset();
            String floatingControlClick = String.format(Locale.ROOT,
                    "\033[<0;%d;%dM\033[<0;%d;%dm",
                    controlX + 1, controlY + 1, controlX + 1, controlY + 1);
            keyboardPipe.write(floatingControlClick.getBytes(StandardCharsets.UTF_8));
            keyboardPipe.flush();
            deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
            while (tui.getContentScrollOffset() != 0 && System.nanoTime() < deadline) {
                Thread.sleep(10);
            }
            assertEquals(0, tui.getContentScrollOffset(),
                    "clicking the floating notification must return to the live tail");
            assertTrue(tui.getVisibleContentLines().contains("retained line 80"));

            VirtualTerminal bottomFrame = new VirtualTerminal(40, 100);
            bottomFrame.feed(terminalOutput.toString(StandardCharsets.UTF_8));
            assertFalse(bottomFrame.getRow(controlY).contains("[↓ Scroll to bottom]"),
                    () -> "floating notification must disappear after reaching the live tail\n"
                            + bottomFrame.screenDump());
            assertTrue(bottomFrame.getRow(controlY).contains("retained line 80"),
                    () -> "hiding the notification must restore the transcript row beneath it\n"
                            + bottomFrame.screenDump());
            assertTrue(bottomFrame.getRow(tui.inputTop() - 1).contains("kompile >"),
                    () -> "click repaint must preserve the prompt row\n"
                            + bottomFrame.screenDump());
            assertEquals(tui.inputTop() - 1, bottomFrame.getCursorRow());
            assertEquals("kompile > ".length(), bottomFrame.getCursorCol());

            keyboardPipe.write(CR);
            keyboardPipe.flush();
            assertEquals("", line.get(5, TimeUnit.SECONDS));
        } finally {
            tui.detachLineReader();
            executor.shutdownNow();
            tui.stop();
            System.setOut(previousOut);
            processes.close();
        }
    }

    @Test
    void queuePaneSurvivesViewportScrollingAndLatestMessageEditsInPlace() throws Exception {
        BackgroundTaskManager tasks = new BackgroundTaskManager();
        BackgroundProcessManager processes =
                new BackgroundProcessManager("chat-completer-queue-pane-test");
        MessageQueue queue = new MessageQueue(
                "chat-completer-queue-pane-" + UUID.randomUUID());
        queue.clear();
        MessageQueue.QueuedMessage first = queue.enqueue("first upcoming instruction");
        MessageQueue.QueuedMessage second = queue.enqueue("second queued revision");
        queue.enqueue("third queued follow-up");
        MessageQueue.QueuedMessage latest = queue.enqueue("latest hidden queued edit");
        KompileTui tui = new KompileTui(
                tasks, processes, queue, new TerminalRenderer(true));
        tui.setReservedRowsCalculator((height, width) -> 3);
        StandardChatActivityPanel activityPanel = new StandardChatActivityPanel(
                tasks, processes, tui.getStatusBar(), tui::getReservedMiddleRows);
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            tui.attachLineReader(reader);
            tui.start(terminal);
            activityPanel.refresh();
            for (int i = 1; i <= 80; i++) {
                tui.recordInScrollRegion("retained queue test line " + i);
            }
            ChatRepl.bindStandardChatActivityKeys(
                    (LineReaderImpl) reader, queue, activityPanel, tui);

            Future<String> line = executor.submit(() -> reader.readLine("kompile > "));
            Thread.sleep(75);
            terminalOutput.reset();
            keyboardPipe.write("\033[5~".getBytes(StandardCharsets.UTF_8));
            keyboardPipe.flush();

            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
            while (tui.getContentScrollOffset() == 0 && System.nanoTime() < deadline) {
                Thread.sleep(10);
            }
            assertTrue(tui.getContentScrollOffset() > 0);

            VirtualTerminal frame = new VirtualTerminal(40, 120);
            frame.feed(terminalOutput.toString(StandardCharsets.UTF_8));
            StringBuilder queuePane = new StringBuilder();
            for (int row = tui.queueTop() - 1; row <= tui.queueBottom() - 1; row++) {
                queuePane.append(frame.getRow(row)).append('\n');
            }
            assertTrue(queuePane.toString().contains("upcoming"));
            assertTrue(queuePane.toString().contains(first.getId()));
            assertTrue(queuePane.toString().contains("first upcoming instruction"));
            assertTrue(queuePane.toString().contains(second.getId()));
            assertTrue(queuePane.toString().contains("second queued revision"));

            keyboardPipe.write("\033[A".getBytes(StandardCharsets.UTF_8));
            keyboardPipe.flush();
            deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
            while (!latest.getContent().equals(reader.getBuffer().toString())
                    && System.nanoTime() < deadline) {
                Thread.sleep(10);
            }
            assertEquals(latest.getContent(), reader.getBuffer().toString());
            assertEquals(4, queue.size(), "editing must keep the message visible in the queue");
            assertEquals(MessageQueue.QueuedMessage.QueuedMessageStatus.EDITING,
                    queue.get(latest.getId()).getStatus());

            deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
            while (!terminalOutput.toString(StandardCharsets.UTF_8).contains("editing")
                    && System.nanoTime() < deadline) {
                Thread.sleep(10);
            }
            frame.feed(terminalOutput.toString(StandardCharsets.UTF_8));
            queuePane.setLength(0);
            for (int row = tui.queueTop() - 1; row <= tui.queueBottom() - 1; row++) {
                queuePane.append(frame.getRow(row)).append('\n');
            }
            assertTrue(queuePane.toString().contains("editing"));
            assertTrue(queuePane.toString().contains(latest.getId()),
                    "an edited item beyond the normal preview must be pinned into view");

            keyboardPipe.write(CR);
            keyboardPipe.flush();
            assertEquals(latest.getContent(), line.get(5, TimeUnit.SECONDS));
        } finally {
            queue.cancelEdit(latest.getId());
            queue.clear();
            tui.detachLineReader();
            executor.shutdownNow();
            tui.stop();
            processes.close();
        }
    }

    @Test
    void codeIndexAlertStaysAboveTranscriptAndPreservesLiveDraft() throws Exception {
        BackgroundProcessManager processes =
                new BackgroundProcessManager("chat-completer-top-alert-test");
        MessageQueue queue = new MessageQueue(
                "chat-completer-top-alert-queue-" + UUID.randomUUID());
        KompileTui tui = new KompileTui(
                new BackgroundTaskManager(), processes, queue, new TerminalRenderer(true));
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            tui.attachLineReader(reader);
            tui.start(terminal);
            tui.recordInScrollRegion("retained transcript sentinel");

            Future<String> line = executor.submit(() -> reader.readLine("kompile > "));
            Thread.sleep(75);
            keyboardPipe.write("visible draft".getBytes(StandardCharsets.UTF_8));
            keyboardPipe.flush();
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
            while (!"visible draft".equals(reader.getBuffer().toString())
                    && System.nanoTime() < deadline) {
                Thread.sleep(10);
            }

            VirtualTerminal frame = new VirtualTerminal(40, 120);
            frame.feed(terminalOutput.toString(StandardCharsets.UTF_8));
            assertTrue(frame.getRow(tui.inputTop() - 1)
                    .contains("kompile > visible draft"), frame::screenDump);
            terminalOutput.reset();
            String warning = "[code-index] watcher unavailable; periodic refresh enabled";
            tui.showAlert(warning);
            deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
            while (!terminalOutput.toString(StandardCharsets.UTF_8).contains(warning)
                    && System.nanoTime() < deadline) {
                Thread.sleep(10);
            }

            frame.feed(terminalOutput.toString(StandardCharsets.UTF_8));
            assertTrue(frame.getRow(1).contains("watcher unavailable"), frame::screenDump);
            assertFalse(tui.getContentViewLines().stream().anyMatch(row -> row.contains("code-index")),
                    "alerts must never become retained transcript lines");
            String inputRow = frame.getRow(tui.inputTop() - 1);
            assertTrue(inputRow.contains("kompile > visible draft"), frame::screenDump);
            assertFalse(inputRow.contains("code-index"), frame::screenDump);
            assertEquals(tui.inputTop() - 1, frame.getCursorRow());
            assertEquals("kompile > visible draft".length(), frame.getCursorCol());

            keyboardPipe.write(CR);
            keyboardPipe.flush();
            assertEquals("visible draft", line.get(5, TimeUnit.SECONDS));
        } finally {
            tui.detachLineReader();
            executor.shutdownNow();
            tui.stop();
            processes.close();
        }
    }

    @Test
    void resizeClearsTranscriptRemnantsFromEntireInputPane() throws Exception {
        BackgroundProcessManager processes =
                new BackgroundProcessManager("chat-resize-input-test");
        MessageQueue queue = new MessageQueue("chat-resize-" + UUID.randomUUID());
        KompileTui tui = new KompileTui(
                new BackgroundTaskManager(), processes, queue, new TerminalRenderer(true));
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            tui.attachLineReader(reader);
            tui.start(terminal);
            tui.recordInScrollRegion("retained transcript");
            Future<String> line = executor.submit(() -> reader.readLine("kompile > ", null, "draft"));
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
            while ((!reader.isReading() || !"draft".equals(reader.getBuffer().toString()))
                    && System.nanoTime() < deadline) Thread.sleep(10);
            assertEquals("draft", reader.getBuffer().toString());
            Thread.sleep(100);

            // Shrink, grow, and change width alone. Seed the emulator with cells
            // left by terminal reflow: a fresh blank screen would hide this bug.
            for (Size size : List.of(new Size(72, 24), new Size(140, 48), new Size(60, 48))) {
                terminalOutput.reset();
                terminal.setSize(size);
                tui.handleResize();
                String clearedBottom = "\033[" + tui.scrollBottom() + ";1H\033[2K";
                deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
                while (!terminalOutput.toString(StandardCharsets.UTF_8).contains(clearedBottom)
                        && System.nanoTime() < deadline) Thread.sleep(10);
                // Take the snapshot only after the redraw widget releases JLine's lock.
                reader.callWidget(LineReader.REDISPLAY);
                VirtualTerminal frame = new VirtualTerminal(size.getRows(), size.getColumns());
                for (int row = tui.inputTop(); row <= tui.scrollBottom(); row++) {
                    frame.feed("\033[" + row + ";1Hstale transcript fragment");
                }
                frame.feed(terminalOutput.toString(StandardCharsets.UTF_8));
                assertTrue(frame.getRow(tui.inputTop() - 1).contains("kompile > draft"),
                        frame::screenDump);
                for (int row = tui.inputTop(); row < tui.scrollBottom(); row++) {
                    assertTrue(frame.getRow(row).isBlank(), frame::screenDump);
                }
                assertFalse(frame.screenDump().contains("stale transcript fragment"), frame::screenDump);
                assertEquals(tui.inputTop() - 1, frame.getCursorRow(), frame::screenDump);
                assertEquals("kompile > draft".length(), frame.getCursorCol(), frame::screenDump);
                assertEquals("draft", reader.getBuffer().toString());
                assertTrue(tui.getContentViewLines().contains("retained transcript"));
            }
            keyboardPipe.write(CR);
            keyboardPipe.flush();
            assertEquals("draft", line.get(5, TimeUnit.SECONDS));
        } finally {
            tui.detachLineReader();
            executor.shutdownNow();
            tui.stop();
            processes.close();
            queue.clear();
        }
    }

    @Test
    void burstToolOutputIsBatchedWithoutFlickeringThePrompt() throws Exception {
        BackgroundProcessManager processes =
                new BackgroundProcessManager("chat-completer-burst-redraw-test");
        MessageQueue queue = new MessageQueue(
                "chat-completer-burst-redraw-queue-" + UUID.randomUUID());
        KompileTui tui = new KompileTui(
                new BackgroundTaskManager(), processes, queue, new TerminalRenderer(true));
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            tui.attachLineReader(reader);
            tui.start(terminal);
            Future<String> line = executor.submit(() -> reader.readLine("kompile > "));
            Thread.sleep(75);
            keyboardPipe.write("draft".getBytes(StandardCharsets.UTF_8));
            keyboardPipe.flush();
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
            while (!"draft".equals(reader.getBuffer().toString())
                    && System.nanoTime() < deadline) {
                Thread.sleep(10);
            }

            String expectedPrompt = "kompile > draft";
            deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
            VirtualTerminal frame;
            String inputRow;
            do {
                frame = new VirtualTerminal(40, 120);
                frame.feed(terminalOutput.toString(StandardCharsets.UTF_8));
                inputRow = frame.getRow(tui.inputTop() - 1);
                if (inputRow.contains(expectedPrompt)) break;
                Thread.sleep(10);
            } while (System.nanoTime() < deadline);
            assertTrue(inputRow.contains(expectedPrompt), frame.screenDump());

            terminalOutput.reset();
            for (int i = 0; i < 200; i++) {
                tui.recordInScrollRegion("burst tool line " + i);
            }
            deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
            while ((!tui.getVisibleContentLines().contains("burst tool line 199")
                    || !terminalOutput.toString(StandardCharsets.UTF_8).contains("burst tool line 199"))
                    && System.nanoTime() < deadline) {
                Thread.sleep(10);
            }
            Thread.sleep(150);

            String output = terminalOutput.toString(StandardCharsets.UTF_8);
            assertTrue(tui.getVisibleContentLines().contains("burst tool line 199"));
            assertTrue(countOccurrences(output, "\033[1;1H") <= 8,
                    "200 output lines should be rendered in a small number of full frames");
            assertFalse(output.contains(expectedPrompt),
                    "routine async frames must not repaint an unchanged prompt");
            assertFalse(output.contains("\033[" + tui.scrollBottom() + ";1H\033[2K"),
                    "routine async frames must not erase JLine's input row");
            assertTrue(output.contains("\0337\033[?25l"),
                    "async frames must save the prompt cursor before painting");
            assertTrue(output.contains("\0338\033[?25h"),
                    "async frames must restore the prompt cursor after painting");

            frame.feed(output);
            assertTrue(frame.getRow(tui.inputTop() - 1).contains(expectedPrompt),
                    frame.screenDump());
            assertEquals(tui.inputTop() - 1, frame.getCursorRow());
            assertEquals("kompile > draft".length(), frame.getCursorCol());

            keyboardPipe.write(CR);
            keyboardPipe.flush();
            assertEquals("draft", line.get(5, TimeUnit.SECONDS));
        } finally {
            tui.detachLineReader();
            executor.shutdownNow();
            tui.stop();
            processes.close();
        }
    }

    private static int countOccurrences(String value, String needle) {
        int count = 0;
        for (int at = 0; (at = value.indexOf(needle, at)) >= 0; at += needle.length()) count++;
        return count;
    }

    @Test
    void incrementalTypingRemainsFullyVisibleAcrossTuiRedraws() throws Exception {
        BackgroundTaskManager tasks = new BackgroundTaskManager();
        BackgroundProcessManager processes =
                new BackgroundProcessManager("chat-completer-incremental-input-test");
        MessageQueue queue = new MessageQueue("chat-completer-incremental-input-queue");
        KompileTui tui = new KompileTui(
                tasks, processes, queue, new TerminalRenderer(true));
        StandardChatActivityPanel activityPanel = new StandardChatActivityPanel(
                tasks, processes, tui.getStatusBar(), () -> 3);
        PrintStream previousOut = System.out;
        ByteArrayOutputStream redirectedStdout = new ByteArrayOutputStream();
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            System.setOut(new PrintStream(redirectedStdout, true, StandardCharsets.UTF_8));
            tui.attachLineReader(reader);
            ChatCompleter.enableAutoTrigger(reader);
            ChatRepl.bindStandardChatActivityKeys(
                    (LineReaderImpl) reader, queue, activityPanel, tui);
            ChatCompleter.setActivity("Thinking");
            tui.start(terminal);

            Future<String> line = executor.submit(() -> reader.readLine("kompile > "));
            Thread.sleep(75);
            VirtualTerminal frame = new VirtualTerminal(40, 120);
            frame.feed(terminalOutput.toString(StandardCharsets.UTF_8));
            assertTrue(frame.getRow(tui.inputTop() - 1).contains("kompile >"),
                    frame::screenDump);
            terminalOutput.reset();

            String draft = "visible incremental draft";
            for (int i = 0; i < draft.length(); i++) {
                keyboardPipe.write(draft.charAt(i));
                keyboardPipe.flush();
                int expectedLength = i + 1;
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
                while (reader.getBuffer().length() < expectedLength
                        && System.nanoTime() < deadline) {
                    Thread.sleep(5);
                }
                // Keep input active across several animated status-bar frames.
                Thread.sleep(30);
            }

            String expectedRow = "kompile > " + draft;
            frame.feed(terminalOutput.toString(StandardCharsets.UTF_8));
            assertTrue(frame.getRow(tui.inputTop() - 1).contains(expectedRow),
                    () -> "the input row must retain the complete draft\n" + frame.screenDump());
            assertEquals(tui.inputTop() - 1, frame.getCursorRow(),
                    "typing redraws must keep the cursor on the input row");
            assertEquals(expectedRow.length(), frame.getCursorCol(),
                    "typing redraws must leave the cursor after the complete draft");
            assertFalse(redirectedStdout.toString(StandardCharsets.UTF_8).contains("\033["),
                    "live TUI cursor controls must use JLine's terminal writer, not System.out");

            keyboardPipe.write(CR);
            keyboardPipe.flush();
            assertEquals(draft, line.get(5, TimeUnit.SECONDS));
        } finally {
            ChatCompleter.setActivity(null);
            ChatCompleter.clearTerminalRef(reader);
            tui.detachLineReader();
            executor.shutdownNow();
            tui.stop();
            System.setOut(previousOut);
            processes.close();
        }
    }

    @Test
    void modelPickerStartsAtTheTopAndKeepsItsPageAcrossRedrawAndResize() throws Exception {
        terminal.setSize(new Size(64, 24));
        BackgroundProcessManager processes =
                new BackgroundProcessManager("model-picker-viewport-test");
        KompileTui tui = new KompileTui(new BackgroundTaskManager(), processes,
                new MessageQueue("model-picker-viewport-" + UUID.randomUUID()),
                new TerminalRenderer(true));
        try {
            tui.attachLineReader(reader);
            tui.setReservedRowsCalculator(StandardChatActivityPanel::reservedRowsForTerminal);
            tui.start(terminal);
            tui.recordInScrollRegion("retained chat before picker");
            List<String> choices = new ArrayList<>();
            for (int i = 1; i <= 40; i++) choices.add("choice-" + i);
            tui.showTemporaryWindow("Provider and model", choices);

            VirtualTerminal frame = new VirtualTerminal(24, 64);
            frame.feed(terminalOutput.toString(StandardCharsets.UTF_8));
            assertTrue(frame.getRow(tui.scrollTop() - 1).contains("Provider and model"),
                    () -> "the picker title must not be clipped off the top\n" + frame.screenDump());
            assertTrue(frame.getRow(tui.scrollTop()).contains("choice-1"),
                    () -> "a long picker must open at its first option, not the transcript tail\n"
                            + frame.screenDump());

            assertTrue(tui.pageContent(-1), "PageDown must reveal later options");
            List<String> page = tui.getVisibleContentLines();
            tui.recordInScrollRegion("background output while picking");
            tui.redrawBars();
            assertEquals(page, tui.getVisibleContentLines(), "background output must not move the picker");
            tui.runCommandOutput(() -> {
                System.out.println("authentication progress");
                assertEquals(page, tui.getVisibleContentLines(),
                        "captured command output must not jump a scrolled picker back to the start");
                return true;
            });

            terminal.setSize(new Size(64, 30));
            tui.handleResize();
            assertEquals(page.get(1), tui.getVisibleContentLines().get(1),
                    "resizing must keep the first visible option, not follow the list tail");
            tui.updateTemporaryWindow("Reasoning effort", List.of("effort-1", "effort-2"));
            assertTrue(tui.getVisibleContentLines().get(0).contains("Reasoning effort"));
            assertTrue(tui.getVisibleContentLines().get(1).contains("effort-1"),
                    "a new picker step must start at the top");
            tui.closeTemporaryWindow();
            assertEquals(List.of("retained chat before picker", "background output while picking",
                            "authentication progress"),
                    tui.getContentViewLines(), "picker frames must never enter the retained transcript");
        } finally {
            tui.stop();
            processes.close();
        }
    }

    @Test
    void modelPickerRepaintsAfterJlineHistoryNavigation() throws Exception {
        terminal.setSize(new Size(80, 24));
        BackgroundProcessManager processes =
                new BackgroundProcessManager("model-picker-jline-test");
        BackgroundTaskManager tasks = new BackgroundTaskManager();
        MessageQueue queue = new MessageQueue("model-picker-jline-" + UUID.randomUUID());
        KompileTui tui = new KompileTui(tasks, processes, queue,
                new TerminalRenderer(true)) {
            @Override public void requestRedraw() { /* Isolate the JLine-driven frame. */ }
        };
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            tui.attachLineReader(reader);
            ChatCompleter.setTerminalRef(reader, terminal);
            ChatCompleter.setContentRedraw(tui::redrawContentView);
            ChatCompleter.enableAutoTrigger(reader);
            StandardChatActivityPanel panel = new StandardChatActivityPanel(
                    tasks, processes, tui.getStatusBar(), tui::getReservedMiddleRows);
            ChatRepl.bindStandardChatActivityKeys((LineReaderImpl) reader, queue, panel, tui);
            tui.start(terminal);
            assertEquals("previous model", readLineResult("previous model" + CR));
            queue.enqueue("queued chat must not become picker input");
            ChatCompleter.setTemporaryWindowActive(true);
            tui.showTemporaryWindow("Provider and model", List.of("first model", "second model"));
            Future<String> picker = executor.submit(() -> reader.readLine("picker model: "));
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
            while (!terminalOutput.toString(StandardCharsets.UTF_8).contains("picker model: ")
                    && System.nanoTime() < deadline) Thread.sleep(10);
            keyboardPipe.write("\033[A".getBytes(StandardCharsets.UTF_8));
            keyboardPipe.flush();
            deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
            while (!"previous model".equals(reader.getBuffer().toString())
                    && System.nanoTime() < deadline) Thread.sleep(10);
            assertEquals("previous model", reader.getBuffer().toString(), "history widget must run");
            Thread.sleep(50);
            VirtualTerminal frame = new VirtualTerminal(24, 80);
            frame.feed(terminalOutput.toString(StandardCharsets.UTF_8));
            assertTrue(frame.getRow(tui.scrollTop() - 1).contains("Provider and model"),
                    () -> "JLine redisplay must restore the modal instead of erasing it\n" + frame.screenDump());
            assertTrue(frame.getRow(tui.inputTop() - 1).contains("picker model: previous model"),
                    () -> "history navigation must keep the picker input anchor\n" + frame.screenDump());
            keyboardPipe.write(CR);
            keyboardPipe.flush();
            assertEquals("previous model", picker.get(5, TimeUnit.SECONDS));
            assertEquals(MessageQueue.QueuedMessage.QueuedMessageStatus.PENDING,
                    queue.getAll().get(0).getStatus(), "picker history must not edit the chat queue");
        } finally {
            queue.clear();
            ChatCompleter.clearTerminalRef(reader);
            tui.detachLineReader();
            executor.shutdownNow();
            tui.stop();
            processes.close();
        }
    }

    @ParameterizedTest
    @ValueSource(ints = {40, 64, 120})
    void modelPickerStepsPreserveWrappedInputAndTranscript(int width) throws Exception {
        terminal.setSize(new Size(width, 24));
        BackgroundProcessManager processes = new BackgroundProcessManager("model-picker-steps-test");
        MessageQueue queue = new MessageQueue("model-picker-steps-" + UUID.randomUUID());
        KompileTui tui = new KompileTui(new BackgroundTaskManager(), processes,
                queue, new TerminalRenderer(true));
        ExecutorService executor = Executors.newSingleThreadExecutor();
        List<String> retained = new ArrayList<>(List.of("chat before picker"));
        try {
            tui.attachLineReader(reader);
            ChatCompleter.setTerminalRef(reader, terminal);
            ChatCompleter.setContentRedraw(tui::redrawContentView);
            ChatCompleter.setContentOutput(tui::recordInScrollRegion);
            ChatCompleter.enableAutoTrigger(reader);
            tui.setReservedRowsCalculator(StandardChatActivityPanel::reservedRowsForTerminal);
            tui.start(terminal);
            tui.recordInScrollRegion(retained.get(0));
            ChatCompleter.setTemporaryWindowActive(true);
            List<String> prompts = List.of(
                    "picker provider (number/name, Esc cancels): ",
                    "picker model (number/name, refresh, blank uses default, Esc cancels): ",
                    "picker thinking (number/name, blank keeps current, back, Esc cancels): ");
            for (int step = 0; step < prompts.size(); step++) {
                String title = "Picker step " + step;
                String answer = "picked-" + step;
                tui.updateTemporaryWindow(title, List.of("option-1", "option-2"));
                String prompt = prompts.get(step);
                Future<String> input = executor.submit(() -> reader.readLine(prompt));
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
                while (!((LineReaderImpl) reader).isReading() && System.nanoTime() < deadline) Thread.sleep(5);
                keyboardPipe.write(answer.getBytes(StandardCharsets.UTF_8));
                keyboardPipe.flush();
                deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
                while (!answer.equals(reader.getBuffer().toString()) && System.nanoTime() < deadline) Thread.sleep(5);
                assertEquals(answer, reader.getBuffer().toString());
                String background = "background during step " + step;
                retained.add(background);
                ChatCompleter.printAbove(background);
                Thread.sleep(100); // allow the real asynchronous frame to run with the nested prompt live
                VirtualTerminal frame = new VirtualTerminal(24, width);
                frame.feed(terminalOutput.toString(StandardCharsets.UTF_8));
                assertTrue(frame.getRow(tui.scrollTop() - 1).contains(title), frame::screenDump);
                assertTrue(frame.getRow(tui.scrollTop()).contains("option-1"), frame::screenDump);
                for (int previous = 0; previous < step; previous++) {
                    assertFalse(frame.screenDump().contains("Picker step " + previous), frame::screenDump);
                    assertFalse(frame.screenDump().contains("picked-" + previous), frame::screenDump);
                }
                StringBuilder inputPane = new StringBuilder();
                for (int row = tui.inputTop() - 1; row < tui.scrollBottom(); row++) {
                    inputPane.append(frame.getRow(row).stripTrailing());
                }
                assertTrue(inputPane.toString().contains(answer), frame::screenDump);
                assertFalse(frame.screenDump().contains(background), "background output must remain behind the modal");
                keyboardPipe.write(CR);
                keyboardPipe.flush();
                assertEquals(answer, input.get(5, TimeUnit.SECONDS));
            }
            tui.closeTemporaryWindow();
            assertEquals(retained, tui.getContentViewLines(), "all background output must survive the picker");
        } finally {
            ChatCompleter.clearTerminalRef(reader);
            tui.detachLineReader();
            executor.shutdownNow();
            tui.stop();
            processes.close();
            queue.clear();
        }
    }

    @Test
    void modelPickerRestorationClearsTheNestedPromptRow() throws Exception {
        BackgroundProcessManager processes =
                new BackgroundProcessManager("chat-completer-model-picker-test");
        KompileTui tui = new KompileTui(
                new BackgroundTaskManager(), processes,
                new MessageQueue("chat-completer-model-picker-queue"),
                new TerminalRenderer(true));
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            // The picker is a nested JLine readLine. Closing it must clear its
            // prompt row before the outer loop writes the normal kompile prompt.
            tui.attachLineReader(reader);
            tui.start(terminal);
            tui.showTemporaryWindow("Provider and model", List.of("Choose a model"));

            Future<String> picker = executor.submit(() -> reader.readLine("picker model: "));
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
            while (!terminalOutput.toString(StandardCharsets.UTF_8).contains("picker model: ")
                    && System.nanoTime() < deadline) {
                Thread.sleep(10);
            }
            keyboardPipe.write(CR);
            keyboardPipe.flush();
            assertEquals("", picker.get(5, TimeUnit.SECONDS));
            terminalOutput.reset();

            tui.closeTemporaryWindow();

            String clearPromptRow = "\033[" + tui.inputTop() + ";1H\033[2K";
            String output = terminalOutput.toString(StandardCharsets.UTF_8);
            assertTrue(output.contains(clearPromptRow),
                    "closing /model must synchronously clear the nested prompt row");
            assertFalse(output.contains("picker model: "),
                    "closing /model must not redisplay the finished nested prompt");

            terminalOutput.reset();
            Future<String> nextLine = executor.submit(() -> reader.readLine("kompile > "));
            deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
            while (!terminalOutput.toString(StandardCharsets.UTF_8).contains("kompile > ")
                    && System.nanoTime() < deadline) {
                Thread.sleep(10);
            }
            assertTrue(terminalOutput.toString(StandardCharsets.UTF_8).contains("kompile > "),
                    "the outer chat prompt must repaint after the picker closes");
            keyboardPipe.write("next".getBytes(StandardCharsets.UTF_8));
            keyboardPipe.write(CR);
            keyboardPipe.flush();
            assertEquals("next", nextLine.get(5, TimeUnit.SECONDS),
                    "the restored chat prompt must remain interactive");
        } finally {
            tui.detachLineReader();
            executor.shutdownNow();
            tui.stop();
            processes.close();
        }
    }

    @Test
    void acceptedSlashCompletionDoesNotLeakIntoTheNextPrompt() throws Exception {
        terminal.setSize(new Size(72, 40));
        BackgroundTaskManager tasks = new BackgroundTaskManager();
        BackgroundProcessManager processes =
                new BackgroundProcessManager("chat-completer-slash-handoff-test");
        MessageQueue queue = new MessageQueue(
                "chat-completer-slash-handoff-queue-" + UUID.randomUUID());
        queue.clear();
        KompileTui tui = new KompileTui(
                tasks, processes, queue, new TerminalRenderer(true));
        tui.setReservedRowsCalculator(StandardChatActivityPanel::reservedRowsForTerminal);
        StandardChatActivityPanel activityPanel = new StandardChatActivityPanel(
                tasks, processes, tui.getStatusBar(), tui::getReservedMiddleRows);
        ExecutorService executor = Executors.newSingleThreadExecutor();
        PrintStream originalOut = System.out;
        PrintStream originalErr = System.err;
        CountDownLatch lateWorkerReady = new CountDownLatch(1);
        CountDownLatch releaseLateWorker = new CountDownLatch(1);
        AtomicReference<Thread> lateWorker = new AtomicReference<>();
        try {
            tui.attachLineReader(reader);
            ChatCompleter.setTerminalRef(reader, terminal);
            ChatCompleter.setContentRedraw(tui::redrawContentView);
            ChatCompleter.setCompletionDisplay(activityPanel::updateCompletions);
            ChatCompleter.enableAutoTrigger(reader);
            tui.start(terminal);

            Future<String> command = executor.submit(() -> reader.readLine("kompile > "));
            Thread.sleep(75);
            keyboardPipe.write("/help".getBytes(StandardCharsets.UTF_8));
            keyboardPipe.flush();
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
            while (!activityPanel.hasCompletions() && System.nanoTime() < deadline) {
                Thread.sleep(10);
            }
            assertTrue(activityPanel.hasCompletions(),
                    "typing a slash command must open managed completion before acceptance");
            keyboardPipe.write(CR);
            keyboardPipe.flush();
            assertEquals("/help", command.get(5, TimeUnit.SECONDS));
            assertTrue(tui.runCommandOutput(() -> {
                tui.showTemporaryWindow("Provider authentication", List.of("Choose credentials"));
                System.out.println("AUTHORIZATION URL VISIBLE");
                assertTrue(tui.getContentViewLines().stream()
                                .anyMatch(line -> line.contains("AUTHORIZATION URL VISIBLE")),
                        "picker-owned command output must be visible while input is required");
                tui.updateTemporaryWindow(
                        "Provider authentication", List.of("Waiting for credentials"));
                assertFalse(tui.getContentViewLines().stream()
                                .anyMatch(line -> line.contains("AUTHORIZATION URL VISIBLE")),
                        "a replacement picker page must discard the previous page's instructions");
                VirtualTerminal pickerFrame = new VirtualTerminal(40, 72);
                pickerFrame.feed(terminalOutput.toString(StandardCharsets.UTF_8));
                assertFalse(pickerFrame.screenDump().contains("AUTHORIZATION URL VISIBLE"),
                        pickerFrame::screenDump);
                System.out.println("NEW PAGE INSTRUCTIONS");
                tui.redrawContentView();
                assertTrue(tui.getContentViewLines().stream()
                                .anyMatch(line -> line.contains("NEW PAGE INSTRUCTIONS")),
                        "ordinary redraws must retain the current page's instructions");
                tui.closeTemporaryWindow();
                tui.showTemporaryWindow("Provider and model", List.of("Choose another model"));
                assertFalse(tui.getContentViewLines().stream()
                                .anyMatch(line -> line.contains("NEW PAGE INSTRUCTIONS")),
                        "reopening a picker must not resurrect closed-page output");
                tui.closeTemporaryWindow();
                System.out.println("COMMAND OUTPUT ONE");
                System.err.println("COMMAND ERROR TWO");
                System.out.println("COMMAND OUTPUT THREE");
                System.out.print("PARTIAL UTF-8 ");
                System.out.flush();
                assertTrue(tui.getContentViewLines().stream()
                                .anyMatch(line -> line.equals("PARTIAL UTF-8 ")),
                        "a flushed interactive prompt must be visible before input blocks");
                System.out.print("π CONTINUATION");
                System.out.println();
                System.out.print("ORDER OUT PARTIAL");
                System.err.println("ORDER ERROR NEXT");
                System.out.println("ORDER OUT CONTINUATION");
                for (int progress = 0; progress < 50; progress++) {
                    System.out.print("PROGRESS " + progress + '\r');
                }
                System.out.println("PROGRESS FINAL");
                Thread worker = new Thread(
                        () -> System.out.println("COMMAND WORKER FIVE"),
                        "command-owned-output-worker");
                worker.start();
                try {
                    worker.join(2_000);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new AssertionError("command output worker was interrupted", e);
                }
                assertFalse(worker.isAlive(), "command output worker must finish");

                PrintStream capturedCommandOut = System.out;
                Thread delayed = new Thread(() -> {
                    lateWorkerReady.countDown();
                    try {
                        if (!releaseLateWorker.await(5, TimeUnit.SECONDS)) return;
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                    capturedCommandOut.println("LATE CLOSED TRANSACTION OUTPUT");
                }, "late-command-output-worker");
                delayed.setDaemon(true);
                lateWorker.set(delayed);
                delayed.start();
                try {
                    if (!lateWorkerReady.await(2, TimeUnit.SECONDS)) {
                        throw new AssertionError("late command output worker did not start");
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new AssertionError("late command output worker was interrupted", e);
                }
                return true;
            }));
            assertSame(originalOut, System.out, "slash dispatch must restore System.out");
            assertSame(originalErr, System.err, "slash dispatch must restore System.err");
            releaseLateWorker.countDown();
            lateWorker.get().join(2_000);
            assertFalse(lateWorker.get().isAlive(), "late command output worker must finish");
            assertFalse(tui.getContentViewLines().contains("LATE CLOSED TRANSACTION OUTPUT"),
                    "closed inherited transactions must not retain or append to the old TUI");

            IllegalStateException failure = assertThrows(IllegalStateException.class,
                    () -> tui.runCommandOutput(() -> {
                        System.err.println("COMMAND FAILURE FOUR");
                        throw new IllegalStateException("expected command failure");
                    }));
            assertEquals("expected command failure", failure.getMessage());
            assertSame(originalOut, System.out,
                    "failed slash dispatch must restore System.out");
            assertSame(originalErr, System.err,
                    "failed slash dispatch must restore System.err");
            List<String> retainedOutput = tui.getContentViewLines();
            assertTrue(retainedOutput.indexOf("COMMAND OUTPUT ONE")
                            < retainedOutput.indexOf("COMMAND ERROR TWO"),
                    "stdout/stderr must retain command-thread emission order");
            assertTrue(retainedOutput.indexOf("COMMAND ERROR TWO")
                            < retainedOutput.indexOf("COMMAND OUTPUT THREE"),
                    "all normal command output must remain ordered");
            assertTrue(retainedOutput.indexOf("COMMAND OUTPUT THREE")
                            < retainedOutput.indexOf("PARTIAL UTF-8 π CONTINUATION"),
                    "flush must not invent a transcript line boundary");
            assertTrue(retainedOutput.indexOf("PARTIAL UTF-8 π CONTINUATION")
                            < retainedOutput.indexOf("ORDER OUT PARTIAL"),
                    "partial stdout must retain its first-write position");
            assertTrue(retainedOutput.indexOf("ORDER OUT PARTIAL")
                            < retainedOutput.indexOf("ORDER ERROR NEXT"),
                    "switching streams must finalize the earlier partial block first");
            assertTrue(retainedOutput.indexOf("ORDER ERROR NEXT")
                            < retainedOutput.indexOf("ORDER OUT CONTINUATION"),
                    "stdout/stderr stream switches must preserve temporal order");
            assertTrue(retainedOutput.indexOf("ORDER OUT CONTINUATION")
                            < retainedOutput.indexOf("PROGRESS FINAL"),
                    "carriage-return progress must keep its temporal position");
            assertFalse(retainedOutput.stream()
                            .anyMatch(line -> line.startsWith("PROGRESS ")
                                    && !line.equals("PROGRESS FINAL")),
                    "carriage-return progress must replace one retained row");
            assertTrue(retainedOutput.indexOf("PROGRESS FINAL")
                            < retainedOutput.indexOf("COMMAND WORKER FIVE"),
                    "command-owned child output must join the same transaction");
            assertTrue(retainedOutput.indexOf("COMMAND WORKER FIVE")
                            < retainedOutput.indexOf("COMMAND FAILURE FOUR"),
                    "output emitted before a command failure must be retained");

            deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
            while (activityPanel.hasCompletions() && System.nanoTime() < deadline) {
                Thread.sleep(10);
            }
            assertFalse(activityPanel.hasCompletions(),
                    "ACCEPT_LINE must clear managed slash candidates");
            Thread.sleep(75); // exercise the redraw gap between command and next prompt

            VirtualTerminal frame = new VirtualTerminal(40, 72);
            frame.feed(terminalOutput.toString(StandardCharsets.UTF_8));
            terminalOutput.reset();
            Future<String> nextLine = executor.submit(() -> reader.readLine("kompile > "));
            deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
            while (!terminalOutput.toString(StandardCharsets.UTF_8).contains("kompile > ")
                    && System.nanoTime() < deadline) {
                Thread.sleep(10);
            }
            frame.feed(terminalOutput.toString(StandardCharsets.UTF_8));

            assertTrue(frame.getRow(tui.inputTop() - 1).contains("kompile >"),
                    () -> "the next slash-command prompt must return to its anchor\n"
                            + frame.screenDump());
            assertTrue(frame.screenDump().contains("COMMAND OUTPUT ONE"),
                    () -> "multiline slash stdout must survive the next prompt\n"
                            + frame.screenDump());
            assertTrue(frame.screenDump().contains("COMMAND ERROR TWO"),
                    () -> "slash stderr must survive the next prompt\n"
                            + frame.screenDump());
            assertTrue(frame.screenDump().contains("COMMAND OUTPUT THREE"),
                    () -> "all slash output must be retained in order\n"
                            + frame.screenDump());
            assertTrue(frame.screenDump().contains("PARTIAL UTF-8 π CONTINUATION"),
                    () -> "partial UTF-8 slash output must remain one line\n"
                            + frame.screenDump());
            assertTrue(frame.screenDump().contains("COMMAND WORKER FIVE"),
                    () -> "command-owned worker output must survive the next prompt\n"
                            + frame.screenDump());
            assertTrue(frame.screenDump().contains("PROGRESS FINAL"),
                    () -> "only the final carriage-return progress state should remain\n"
                            + frame.screenDump());
            assertTrue(frame.screenDump().contains("COMMAND FAILURE FOUR"),
                    () -> "slash output emitted before an error must survive cleanup\n"
                            + frame.screenDump());
            for (int row = tui.inputTop() - 1; row < tui.scrollBottom(); row++) {
                assertFalse(frame.getRow(row).contains("/help"),
                        "the accepted slash input must not remain in input row " + row
                                + "\n" + frame.screenDump());
            }
            assertFalse(frame.screenDump().contains("Show help information"),
                    () -> "slash candidates must not survive command acceptance\n"
                            + frame.screenDump());

            keyboardPipe.write("next".getBytes(StandardCharsets.UTF_8));
            keyboardPipe.write(CR);
            keyboardPipe.flush();
            assertEquals("next", nextLine.get(5, TimeUnit.SECONDS));
        } finally {
            releaseLateWorker.countDown();
            Thread delayed = lateWorker.get();
            if (delayed != null) delayed.join(2_000);
            queue.clear();
            ChatCompleter.clearTerminalRef(reader);
            tui.detachLineReader();
            executor.shutdownNow();
            System.setOut(originalOut);
            System.setErr(originalErr);
            tui.stop();
            processes.close();
        }
    }

    @Test
    void commandOutputTransactionsSerializeConcurrentCallsAndSupportNesting() throws Exception {
        BackgroundProcessManager processes =
                new BackgroundProcessManager("chat-command-output-serialization-test");
        KompileTui tui = new KompileTui(
                new BackgroundTaskManager(), processes,
                new MessageQueue("chat-command-output-serialization-queue-" + UUID.randomUUID()),
                new TerminalRenderer(true));
        BackgroundProcessManager otherProcesses =
                new BackgroundProcessManager("chat-command-output-serialization-other-test");
        KompileTui otherTui = new KompileTui(
                new BackgroundTaskManager(), otherProcesses,
                new MessageQueue("chat-command-output-serialization-other-queue-" + UUID.randomUUID()),
                new TerminalRenderer(true));
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch outerEntered = new CountDownLatch(1);
        CountDownLatch releaseOuter = new CountDownLatch(1);
        CountDownLatch contenderStarted = new CountDownLatch(1);
        AtomicReference<Thread> contenderThread = new AtomicReference<>();
        PrintStream originalOut = System.out;
        PrintStream originalErr = System.err;
        try {
            tui.attachLineReader(reader);
            tui.start(terminal);
            otherTui.start(terminal);

            Future<Boolean> outer = executor.submit(() -> tui.runCommandOutput(() -> {
                System.out.println("SERIAL OUTER START");
                assertTrue(tui.runCommandOutput(() -> {
                    System.err.println("SERIAL NESTED");
                    return true;
                }), "same-thread nested transactions must be reentrant");
                outerEntered.countDown();
                try {
                    if (!releaseOuter.await(5, TimeUnit.SECONDS)) {
                        throw new AssertionError("timed out waiting to release outer transaction");
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new AssertionError("outer transaction was interrupted", e);
                }
                System.out.println("SERIAL OUTER END");
                return true;
            }));
            assertTrue(outerEntered.await(5, TimeUnit.SECONDS));

            Future<Boolean> concurrent = executor.submit(() -> {
                contenderThread.set(Thread.currentThread());
                contenderStarted.countDown();
                return otherTui.runCommandOutput(() -> {
                    System.out.println("SERIAL CONCURRENT");
                    return true;
                });
            });
            assertTrue(contenderStarted.await(5, TimeUnit.SECONDS));
            long blockedDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
            while (contenderThread.get().getState() != Thread.State.BLOCKED
                    && !concurrent.isDone() && System.nanoTime() < blockedDeadline) {
                Thread.sleep(5);
            }
            assertEquals(Thread.State.BLOCKED, contenderThread.get().getState(),
                    "a second TUI must block on the process-wide output transaction monitor");

            releaseOuter.countDown();
            assertTrue(outer.get(5, TimeUnit.SECONDS));
            assertTrue(concurrent.get(5, TimeUnit.SECONDS));
            assertSame(originalOut, System.out,
                    "serialized transactions must restore the original System.out");
            assertSame(originalErr, System.err,
                    "serialized transactions must restore the original System.err");

            List<String> output = tui.getContentViewLines();
            assertTrue(output.indexOf("SERIAL OUTER START")
                            < output.indexOf("SERIAL NESTED"),
                    "nested output must follow its outer prefix");
            assertTrue(output.indexOf("SERIAL NESTED")
                            < output.indexOf("SERIAL OUTER END"),
                    "the outer stream must resume after nested restoration");
            assertTrue(otherTui.getContentViewLines().contains("SERIAL CONCURRENT"),
                    "the second TUI must receive its output after acquiring the monitor");
        } finally {
            releaseOuter.countDown();
            executor.shutdownNow();
            executor.awaitTermination(5, TimeUnit.SECONDS);
            System.setOut(originalOut);
            System.setErr(originalErr);
            tui.detachLineReader();
            otherTui.stop();
            tui.stop();
            otherProcesses.close();
            processes.close();
        }
    }

    @Test
    void streamedOutputUsesAuthoritativeContentSink() {
        List<String> received = new ArrayList<>();
        ChatCompleter.setContentOutput(received::add);
        try {
            ChatCompleter.printAbove("tool output chunk");
        } finally {
            ChatCompleter.setContentOutput(null);
        }
        assertEquals(List.of("tool output chunk"), received,
                "streamed output should be handed to the active transcript sink");
    }

    @Test
    void streamedOutputDuringReadPreservesPrintableTyping() throws Exception {
        List<String> received = new CopyOnWriteArrayList<>();
        ChatCompleter.setTerminalRef(reader, terminal);
        ChatCompleter.setContentOutput(received::add);
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<String> line = executor.submit(() -> reader.readLine("> "));
            Thread.sleep(75);
            ChatCompleter.printAbove("tool output while editing");
            keyboardPipe.write("typed".getBytes(StandardCharsets.UTF_8));
            keyboardPipe.write(CR);
            keyboardPipe.flush();
            assertEquals("typed", line.get(5, TimeUnit.SECONDS),
                    "background output must not swallow the active input buffer");
            assertEquals(List.of("tool output while editing"), received);
        } finally {
            ChatCompleter.setContentOutput(null);
            ChatCompleter.clearTerminalRef(reader);
            executor.shutdownNow();
        }
    }

    // ========================================================================
    // Helpers
    // ========================================================================

    /**
     * Sends keystrokes, reads the line, and returns sanitized terminal output.
     */
    private String readLineOutputSanitized(String keys) throws Exception {
        readLineInternal(keys);
        return sanitize(terminalOutput.toString(StandardCharsets.UTF_8));
    }

    /**
     * Sends keystrokes and returns the result of reader.readLine() (the final buffer value).
     */
    private String readLineResult(String keys) throws Exception {
        return readLineInternal(keys);
    }

    private String readLineInternal(String keys) throws Exception {
        ExecutorService executor = Executors.newSingleThreadExecutor();
        Future<String> future = executor.submit(() -> {
            try {
                return reader.readLine("> ");
            } catch (UserInterruptException | EndOfFileException e) {
                return "";
            }
        });

        // Small delay to let readLine start
        Thread.sleep(50);

        // Feed keystrokes
        keyboardPipe.write(keys.getBytes(StandardCharsets.UTF_8));
        keyboardPipe.flush();

        try {
            return future.get(5, TimeUnit.SECONDS);
        } catch (TimeoutException e) {
            future.cancel(true);
            return "";
        } finally {
            executor.shutdownNow();
            Thread.sleep(100); // let output flush
        }
    }

    /**
     * Strip ANSI escape sequences for cleaner assertion matching.
     */
    private static String sanitize(String s) {
        return s
                // CSI sequences: ESC [ ... letter
                .replaceAll("\033\\[[0-9;?]*[A-Za-z]", "")
                // OSC sequences: ESC ] ... BEL
                .replaceAll("\033\\][^\007]*\007", "")
                // Any remaining bare ESC
                .replaceAll("\033", "")
                // Collapse whitespace for easier matching
                .replaceAll("\\s+", " ")
                .trim();
    }
}
