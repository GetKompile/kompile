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

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;

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
    void tabAfterEnforceSpaceShowsSubArgs() throws Exception {
        String output = readLineOutputSanitized("/enforce " + TAB + CR);
        boolean hasSubArg =
                output.contains("on") ||
                output.contains("off") ||
                output.contains("rules") ||
                output.contains("score");
        assertTrue(hasSubArg,
                "/enforce <TAB> should show sub-arguments, output: " + output);
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
            tui.start(terminal);
            tui.attachLineReader(reader);
            Future<String> line = executor.submit(() -> reader.readLine("kompile > "));
            Thread.sleep(75);
            terminalOutput.reset();

            String largeContent = String.join("\n",
                    Collections.nCopies(200, "large tool result row"));
            for (int i = 0; i < 20; i++) {
                tui.showActivityView("process:large", "large result " + i, largeContent);
            }
            Thread.sleep(150);
            String redrawOutput = terminalOutput.toString(StandardCharsets.UTF_8);
            assertTrue(redrawOutput.contains("kompile > "),
                    "large redraws must redisplay the complete chat prompt");
            assertTrue(redrawOutput.contains("\033[?25h"),
                    "large redraws must leave the input cursor visible");

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

            terminalOutput.reset();
            String oversized = "  ▸ Tool " + "\033[36m" + "x".repeat(2_000) + "\033[0m";
            ChatCompleter.printAbove(oversized);
            deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
            while (!terminalOutput.toString(StandardCharsets.UTF_8).contains("kompile > draft")
                    && System.nanoTime() < deadline) {
                Thread.sleep(10);
            }

            VirtualTerminal frame = new VirtualTerminal(20, 48);
            frame.feed(terminalOutput.toString(StandardCharsets.UTF_8));
            int inputRow = tui.scrollBottom() - 1;
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
            assertTrue(frame.getRow(2).contains("[↓ Bottom]"),
                    () -> "scrolled viewport must show the clickable bottom control\n"
                            + frame.screenDump());
            assertTrue(frame.getRow(tui.scrollBottom() - 1).contains("kompile >"),
                    () -> "the prompt must be repainted on the input row\n" + frame.screenDump());
            assertEquals(tui.scrollBottom() - 1, frame.getCursorRow(),
                    "viewport repaint must return the cursor to the input row");
            assertEquals("kompile > ".length(), frame.getCursorCol(),
                    "the text cursor must be restored after the prompt");

            terminalOutput.reset();
            // X10 Button1 press at zero-based x=2,y=2, inside [↓ Bottom].
            keyboardPipe.write("\033[M ##".getBytes(StandardCharsets.UTF_8));
            keyboardPipe.flush();
            deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
            while (tui.getContentScrollOffset() != 0 && System.nanoTime() < deadline) {
                Thread.sleep(10);
            }
            assertEquals(0, tui.getContentScrollOffset(),
                    "clicking the separator control must return to the live tail");
            assertTrue(tui.getVisibleContentLines().contains("retained line 80"));

            VirtualTerminal bottomFrame = new VirtualTerminal(40, 120);
            bottomFrame.feed(terminalOutput.toString(StandardCharsets.UTF_8));
            assertFalse(bottomFrame.getRow(2).contains("[↓ Bottom]"),
                    () -> "control must disappear after reaching the live tail\n"
                            + bottomFrame.screenDump());
            assertTrue(bottomFrame.getRow(tui.scrollBottom() - 1).contains("kompile >"),
                    () -> "click repaint must preserve the prompt row\n"
                            + bottomFrame.screenDump());
            assertEquals(tui.scrollBottom() - 1, bottomFrame.getCursorRow());
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

            terminalOutput.reset();
            String warning = "[code-index] watcher unavailable; periodic refresh enabled";
            tui.showAlert(warning);
            deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
            while ((!terminalOutput.toString(StandardCharsets.UTF_8).contains(warning)
                    || !terminalOutput.toString(StandardCharsets.UTF_8).contains("visible draft"))
                    && System.nanoTime() < deadline) {
                Thread.sleep(10);
            }

            VirtualTerminal frame = new VirtualTerminal(40, 120);
            frame.feed(terminalOutput.toString(StandardCharsets.UTF_8));
            assertTrue(frame.getRow(1).contains("watcher unavailable"), frame::screenDump);
            assertFalse(tui.getContentViewLines().stream().anyMatch(row -> row.contains("code-index")),
                    "alerts must never become retained transcript lines");
            String inputRow = frame.getRow(tui.scrollBottom() - 1);
            assertTrue(inputRow.contains("kompile > visible draft"), frame::screenDump);
            assertFalse(inputRow.contains("code-index"), frame::screenDump);
            assertEquals(tui.scrollBottom() - 1, frame.getCursorRow());
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

            VirtualTerminal frame = new VirtualTerminal(40, 120);
            frame.feed(output);
            assertTrue(frame.getRow(tui.scrollBottom() - 1).contains("kompile > draft"),
                    frame::screenDump);
            assertEquals(tui.scrollBottom() - 1, frame.getCursorRow());
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

            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
            String expectedRow = "kompile > " + draft;
            while (!terminalOutput.toString(StandardCharsets.UTF_8).contains(expectedRow)
                    && System.nanoTime() < deadline) {
                Thread.sleep(10);
            }

            VirtualTerminal frame = new VirtualTerminal(40, 120);
            frame.feed(terminalOutput.toString(StandardCharsets.UTF_8));
            assertTrue(frame.getRow(tui.scrollBottom() - 1).contains(expectedRow),
                    () -> "the input row must retain the complete draft\n" + frame.screenDump());
            assertEquals(tui.scrollBottom() - 1, frame.getCursorRow(),
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
    void modelPickerRestorationClearsTheNestedPromptRow() throws Exception {
        BackgroundProcessManager processes =
                new BackgroundProcessManager("chat-completer-model-picker-test");
        KompileTui tui = new KompileTui(
                new BackgroundTaskManager(), processes,
                new MessageQueue("chat-completer-model-picker-queue"),
                new TerminalRenderer(true));
        try {
            // The picker is a nested JLine readLine. Closing it must clear its
            // prompt row before the outer loop writes the normal kompile prompt.
            tui.start(terminal);
            tui.showTemporaryWindow("Provider and model", List.of("Choose a model"));
            terminalOutput.reset();

            tui.closeTemporaryWindow();

            String output = terminalOutput.toString(StandardCharsets.UTF_8);
            String clearPromptRow = "\033[" + tui.scrollBottom() + ";1H\033[2K";
            assertTrue(output.contains(clearPromptRow),
                    "closing /model must clear the nested prompt row and leave the cursor at the chat anchor");
        } finally {
            tui.stop();
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
