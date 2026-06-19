package ai.kompile.cli.main.chat;

import ai.kompile.cli.main.chat.render.AsciiRenderer;
import ai.kompile.cli.main.chat.render.TerminalRenderer;
import org.jline.keymap.KeyMap;
import org.jline.reader.Binding;
import org.jline.reader.LineReader;
import org.jline.reader.LineReaderBuilder;
import org.jline.reader.Reference;
import org.jline.reader.Widget;
import org.jline.reader.impl.LineReaderImpl;
import org.jline.terminal.Size;
import org.jline.terminal.Terminal;
import org.jline.terminal.impl.LineDisciplineTerminal;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.io.PrintStream;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.*;

class EmulatedPassthroughCommandManagedInputBridgeTest {

    private LineDisciplineTerminal terminal;
    private ByteArrayOutputStream terminalOutput;
    private PipedOutputStream keyboardPipe;

    @BeforeEach
    void setUp() throws Exception {
        terminalOutput = new ByteArrayOutputStream();
        terminal = new LineDisciplineTerminal("managed-passthrough-test", "xterm",
                terminalOutput, StandardCharsets.UTF_8);
        terminal.setSize(new Size(100, 30));

        keyboardPipe = new PipedOutputStream();
        PipedInputStream pipeIn = new PipedInputStream(keyboardPipe, 4096);
        Thread pumpThread = new Thread(() -> {
            try {
                byte[] buf = new byte[256];
                int n;
                while ((n = pipeIn.read(buf)) >= 0) {
                    terminal.processInputBytes(buf, 0, n);
                }
            } catch (IOException ignored) {
                // Pipe closed by test teardown.
            }
        }, "managed-input-bridge-test-pump");
        pumpThread.setDaemon(true);
        pumpThread.start();
    }

    @AfterEach
    void tearDown() throws Exception {
        if (keyboardPipe != null) {
            try { keyboardPipe.close(); } catch (IOException ignored) {}
        }
        if (terminal != null) {
            terminal.close();
        }
    }

    @Test
    void managedCancelBindingDoesNotBindRawEscape() throws Exception {
        LineReader reader = LineReaderBuilder.builder()
                .terminal(terminal)
                .build();
        Method bindCancelKey = EmulatedPassthroughCommand.class
                .getDeclaredMethod("bindCancelKey", LineReader.class);
        bindCancelKey.setAccessible(true);
        bindCancelKey.invoke(new EmulatedPassthroughCommand(), reader);

        KeyMap<Binding> keyMap = ((LineReaderImpl) reader).getKeyMaps().get(LineReader.EMACS);
        Binding rawEscape = keyMap.getBound("\033");
        if (rawEscape instanceof Reference ref) {
            assertNotEquals("cancel-emulated", ref.name(), "Raw Escape must not trigger managed cancel");
        }
        Binding ctrlG = keyMap.getBound(KeyMap.ctrl('G'));
        assertInstanceOf(Reference.class, ctrlG);
        assertEquals("cancel-emulated", ((Reference) ctrlG).name());
    }

    @Test
    void idleSigintHandlerRequestsShutdownWithoutDelegatingToNativeHandler() throws Exception {
        EmulatedPassthroughCommand command = configuredIdleCommand();
        Terminal.SignalHandler handler = (Terminal.SignalHandler) invokeNoArgReturn(command, "terminalSigintHandler");

        assertDoesNotThrow(() -> handler.handle(Terminal.Signal.INT));
        assertTrue(((AtomicBoolean) getField(command, "shutdownSignal")).get());
    }

    @Test
    void terminalStripperDropsMouseModesAndReportsBeforeDisplay() throws Exception {
        Object stripper = newTerminalQueryStripper();
        StripResult result = stripTerminalChunk(stripper,
                "a\033[?1002;1006h\033[31mred\033[<35;18;26M\033[?2004lZ");

        assertEquals("a\033[31mredZ", new String(result.displayBytes(), StandardCharsets.UTF_8));
        assertEquals("", result.queries());

        StripResult query = stripTerminalChunk(stripper, "\033[6n");
        assertArrayEquals(new byte[0], query.displayBytes());
        assertEquals("\033[6n", query.queries());
    }

    @Test
    void physicalArrowKeysBindToActivityAwareWidgets() throws Exception {
        EmulatedPassthroughCommand command = configuredIdleCommand();
        String key = (String) invokeTwoStringReturn(command, "startBackgroundActivity", "opencode response", "starting");
        LineReader reader = LineReaderBuilder.builder()
                .terminal(terminal)
                .build();
        invokeLineReaderArg(command, "enableManagedSlashCompletion", reader);

        LineReaderImpl impl = (LineReaderImpl) reader;
        KeyMap<Binding> keyMap = impl.getKeyMaps().get(LineReader.EMACS);
        assertReferenceBinding(keyMap.getBound("\033[B"), "activity-down");
        assertReferenceBinding(keyMap.getBound("\033OB"), "activity-down");
        assertReferenceBinding(keyMap.getBound("\033[A"), "activity-up");
        assertReferenceBinding(keyMap.getBound("\033OA"), "activity-up");

        Widget downWidget = impl.getWidgets().get(((Reference) keyMap.getBound("\033[B")).name());
        assertNotNull(downWidget);
        assertTrue(downWidget.apply(), "Physical Down arrow should enter the passive activity menu");
        assertTrue((Boolean) getField(command, "activityFocusActive"));
        assertEquals(key, getField(command, "selectedActivityId"));
    }

    @Test
    void scrollbackKeysBindToManagedViewportWidgets() throws Exception {
        EmulatedPassthroughCommand command = configuredIdleCommand();
        LineReader reader = LineReaderBuilder.builder()
                .terminal(terminal)
                .build();
        invokeLineReaderArg(command, "enableManagedSlashCompletion", reader);

        LineReaderImpl impl = (LineReaderImpl) reader;
        KeyMap<Binding> keyMap = impl.getKeyMaps().get(LineReader.EMACS);
        assertReferenceBinding(keyMap.getBound("\033[5~"), "scroll-page-up");
        assertReferenceBinding(keyMap.getBound("\033[6~"), "scroll-page-down");
        assertReferenceBinding(keyMap.getBound("\033[1;5H"), "scroll-top");
        assertReferenceBinding(keyMap.getBound("\033[1;5F"), "scroll-bottom");
        // X10 mouse reports (ESC[M…) route through the wheel widget so the wheel
        // scrolls the transcript instead of the host terminal's native scrollback.
        assertReferenceBinding(keyMap.getBound("\033[M"), "scroll-mouse-wheel");

        Binding plainHome = keyMap.getBound("\033[H");
        if (plainHome instanceof Reference ref) {
            assertNotEquals("scroll-top", ref.name(), "Plain Home must stay available for input editing");
        }
        Binding plainEnd = keyMap.getBound("\033[F");
        if (plainEnd instanceof Reference ref) {
            assertNotEquals("scroll-bottom", ref.name(), "Plain End must stay available for input editing");
        }
    }

    @Test
    void wheelCaptureEnablesOnlyWhileADecoderOwnsTheScreen() throws Exception {
        EmulatedPassthroughCommand command = configuredIdleCommand();

        // No decoder yet (raw passthrough / pre-launch) → the wheel stays with the
        // host terminal; Kompile must not grab it.
        terminalOutput.reset();
        invokeNoArg(command, "enableTranscriptMouse");
        assertFalse(terminalOutput.toString(StandardCharsets.UTF_8).contains("\033[?1000h"),
                "Mouse tracking must stay off until a decoder owns the screen");
        assertFalse((Boolean) getField(command, "transcriptMouseEnabled"));

        // Decoder-owned agent (OpenCode renders into Kompile's transcript) → capture wheel.
        setField(command, "agentDecoder", new ai.kompile.cli.main.chat.tui.OpenCodeDecoder());
        terminalOutput.reset();
        invokeNoArg(command, "enableTranscriptMouse");
        assertTrue(terminalOutput.toString(StandardCharsets.UTF_8).contains("\033[?1000h"),
                "Decoder-owned screen should enable real-terminal wheel capture");
        assertTrue((Boolean) getField(command, "transcriptMouseEnabled"));

        // Idempotent: re-enabling while already tracking must not re-emit the sequence.
        terminalOutput.reset();
        invokeNoArg(command, "enableTranscriptMouse");
        assertFalse(terminalOutput.toString(StandardCharsets.UTF_8).contains("\033[?1000h"),
                "Re-enabling while already tracking must not re-emit the enable sequence");

        // Disabling restores the terminal's native selection/scrollback.
        terminalOutput.reset();
        invokeNoArg(command, "disableTranscriptMouse");
        assertTrue(terminalOutput.toString(StandardCharsets.UTF_8).contains("\033[?1000l"),
                "Disable should turn real-terminal mouse tracking back off");
        assertFalse((Boolean) getField(command, "transcriptMouseEnabled"));
    }

    @Test
    void scrollbackCanReachBeginningStayStableAndReturnToBottom() throws Exception {
        EmulatedPassthroughCommand command = configuredIdleCommand();
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        PrintStream originalOut = System.out;
        try (PrintStream capture = new PrintStream(output, true, StandardCharsets.UTF_8)) {
            System.setOut(capture);
            for (int i = 1; i <= 35; i++) {
                invokeStringArg(command, "safePrintln", String.format("line-%02d", i));
            }

            output.reset();
            assertTrue(invokeBooleanNoArg(command, "scrollTranscriptToTop"));
            String top = output.toString(StandardCharsets.UTF_8);
            assertTrue(top.contains("line-01"), "Ctrl+Home should redraw from the first scrollback line");
            assertFalse(top.contains("line-35"), "Top viewport should not include live-bottom output");
            assertEquals(15, getField(command, "scrollViewportOffset"));

            output.reset();
            invokeStringArg(command, "safePrintln", "line-36");
            String stable = output.toString(StandardCharsets.UTF_8);
            assertTrue(stable.contains("line-01"), "New output must not yank a scrolled-up viewport to the bottom");
            assertFalse(stable.contains("line-36"), "New output should wait below the current scrolled viewport");
            assertEquals(16, getField(command, "scrollViewportOffset"));

            output.reset();
            assertTrue(invokeBooleanNoArg(command, "scrollTranscriptToBottom"));
            String bottom = output.toString(StandardCharsets.UTF_8);
            assertTrue(bottom.contains("line-36"), "Ctrl+End should return to live-bottom output");
            assertFalse(bottom.contains("line-01"), "Bottom viewport should no longer show the beginning");
            assertEquals(0, getField(command, "scrollViewportOffset"));
        } finally {
            System.setOut(originalOut);
        }
    }

    @Test
    void rewrappedStreamingDoesNotBloatHistoryAndStillRenders() throws Exception {
        // Regression: opencode rewraps streaming text as it grows, so line-based
        // history merge found no overlap and appended the whole screen every frame
        // — history grew unbounded, the turn never settled (busy forever), and the
        // churn meant nothing rendered. The current screen must be authoritative.
        EmulatedPassthroughCommand command = configuredIdleCommand();
        TerminalRenderer plainRenderer = new TerminalRenderer(false);
        setField(command, "renderer", plainRenderer);
        setField(command, "ascii", new AsciiRenderer(plainRenderer, 100));
        setField(command, "tuiFullText", new StringBuilder());
        setField(command, "tuiPendingText", new StringBuilder());
        setField(command, "tuiSpinnerStopped", new AtomicBoolean(true));
        setField(command, "lastSentMessage", "question");

        ai.kompile.cli.main.chat.tui.OpenCodeDecoder decoder = new ai.kompile.cli.main.chat.tui.OpenCodeDecoder();
        decoder.resetHistory();

        ByteArrayOutputStream output = new ByteArrayOutputStream();
        PrintStream originalOut = System.out;
        try (PrintStream capture = new PrintStream(output, true, StandardCharsets.UTF_8)) {
            System.setOut(capture);
            // Snapshot 1: response wrapped one way.
            ai.kompile.cli.main.chat.tui.VirtualTerminal vt1 = new ai.kompile.cli.main.chat.tui.VirtualTerminal(30, 100);
            vt1.feed("\033[1;1HThe answer is forty two and");
            vt1.feed("\033[2;1Hthat is the final result here");
            invokeDecoderScreen(command, decoder, vt1);
            // Snapshot 2: SAME text, rewrapped differently (streaming relayout).
            ai.kompile.cli.main.chat.tui.VirtualTerminal vt2 = new ai.kompile.cli.main.chat.tui.VirtualTerminal(30, 100);
            vt2.feed("\033[1;1HThe answer is forty two");
            vt2.feed("\033[2;1Hand that is the final result here");
            invokeDecoderScreen(command, decoder, vt2);
        } finally {
            System.setOut(originalOut);
        }

        // Rewrap must REPLACE, not append — history stays bounded (~2 lines), not 4+.
        assertTrue(decoder.history().size() <= 3,
                "rewrapped streaming bloated history: " + decoder.history().size() + " -> " + decoder.history());
        // And the content still renders.
        String rendered = output.toString(StandardCharsets.UTF_8);
        assertTrue(rendered.contains("final result here"),
                "decoded content should render, got: " + rendered);
    }

    @Test
    void decoderOwnedSnapshotsReplaceScrollableChildOutputBlock() throws Exception {
        EmulatedPassthroughCommand command = configuredIdleCommand();
        TerminalRenderer plainRenderer = new TerminalRenderer(false);
        setField(command, "renderer", plainRenderer);
        setField(command, "ascii", new AsciiRenderer(plainRenderer, 100));
        setField(command, "tuiFullText", new StringBuilder());
        setField(command, "tuiPendingText", new StringBuilder());
        setField(command, "tuiSpinnerStopped", new AtomicBoolean(true));
        setField(command, "lastSentMessage", "show output");

        AtomicReference<String> decoded = new AtomicReference<>(numberedLines(35));
        ai.kompile.cli.main.chat.tui.AgentTuiDecoder decoder = new ai.kompile.cli.main.chat.tui.AgentTuiDecoder() {
            @Override public String agentName() { return "fake"; }
            @Override public String extractContent(ai.kompile.cli.main.chat.tui.VirtualTerminal vt) { return decoded.get(); }
            @Override public String extractStreamingContent(ai.kompile.cli.main.chat.tui.VirtualTerminal vt) { return decoded.get(); }
            @Override public boolean renderRawTui() { return false; }
            @Override public String buildResponses(String rawChunk, ai.kompile.cli.main.chat.tui.VirtualTerminal vt) { return ""; }
            @Override public int[] contentRowRange(int totalRows) { return new int[]{0, totalRows - 1}; }
        };
        ai.kompile.cli.main.chat.tui.VirtualTerminal vt = new ai.kompile.cli.main.chat.tui.VirtualTerminal(30, 100);

        ByteArrayOutputStream output = new ByteArrayOutputStream();
        PrintStream originalOut = System.out;
        try (PrintStream capture = new PrintStream(output, true, StandardCharsets.UTF_8)) {
            System.setOut(capture);
            invokeDecoderScreen(command, decoder, vt);
            String live = output.toString(StandardCharsets.UTF_8);
            assertTrue(live.contains("line-35"), "Decoder-owned live output should render from the decoded snapshot");

            output.reset();
            assertTrue(invokeBooleanNoArg(command, "scrollTranscriptToTop"));
            String top = output.toString(StandardCharsets.UTF_8);
            assertTrue(top.contains("line-01"), "Ctrl+Home should reach the beginning of decoded child output");
            assertFalse(top.contains("line-35"), "Top decoded viewport should not include the live-bottom line");

            decoded.set(numberedLines(36));
            output.reset();
            invokeDecoderScreen(command, decoder, vt);
            String stable = output.toString(StandardCharsets.UTF_8);
            assertTrue(stable.contains("line-01"), "Decoded updates must not yank a scrolled viewport to the bottom");
            assertFalse(stable.contains("line-36"), "New decoded child output should stay below the current viewport");

            output.reset();
            assertTrue(invokeBooleanNoArg(command, "scrollTranscriptToBottom"));
            String bottom = output.toString(StandardCharsets.UTF_8);
            assertTrue(bottom.contains("line-36"), "Ctrl+End should return to the latest decoded child output");
        } finally {
            System.setOut(originalOut);
        }
    }

    @Test
    void physicalDownArrowIsConsumedWhenActivityListIsEmpty() throws Exception {
        EmulatedPassthroughCommand command = configuredIdleCommand();
        LineReader reader = LineReaderBuilder.builder()
                .terminal(terminal)
                .build();
        invokeLineReaderArg(command, "enableManagedSlashCompletion", reader);

        LineReaderImpl impl = (LineReaderImpl) reader;
        KeyMap<Binding> keyMap = impl.getKeyMaps().get(LineReader.EMACS);
        Widget downWidget = impl.getWidgets().get(((Reference) keyMap.getBound("\033[B")).name());

        assertNotNull(downWidget);
        assertTrue(downWidget.apply(), "Down from a fresh empty prompt should be consumed by the managed UI");
        assertFalse((Boolean) getField(command, "activityFocusActive"));
        assertEquals("", getField(command, "selectedActivityId"));
    }

    @Test
    void physicalDownArrowIsConsumedWhenBusyFlagsRemainSet() throws Exception {
        EmulatedPassthroughCommand command = configuredIdleCommand();
        setField(command, "agentBusy", true);
        setField(command, "busyInputActive", true);
        LineReader reader = LineReaderBuilder.builder()
                .terminal(terminal)
                .build();
        invokeLineReaderArg(command, "enableManagedSlashCompletion", reader);

        LineReaderImpl impl = (LineReaderImpl) reader;
        KeyMap<Binding> keyMap = impl.getKeyMaps().get(LineReader.EMACS);
        Widget downWidget = impl.getWidgets().get(((Reference) keyMap.getBound("\033[B")).name());

        assertNotNull(downWidget);
        assertTrue(downWidget.apply(), "Down should not fall through to JLine when the managed prompt is active");
        assertFalse((Boolean) getField(command, "activityFocusActive"));
        assertEquals("", getField(command, "selectedActivityId"));
    }

    @Test
    void readLineConsumesFirstDownFromFreshPromptWithoutBell() throws Exception {
        EmulatedPassthroughCommand command = configuredIdleCommand();
        LineReader reader = LineReaderBuilder.builder()
                .terminal(terminal)
                .build();
        invokeLineReaderArg(command, "enableManagedSlashCompletion", reader);

        AtomicReference<String> line = new AtomicReference<>();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread readThread = new Thread(() -> {
            try {
                line.set(reader.readLine("kompile> "));
            } catch (Throwable t) {
                failure.set(t);
            }
        }, "managed-readline-down-test");
        readThread.setDaemon(true);
        readThread.start();

        Thread.sleep(150L);
        terminalOutput.reset();
        writeBytes((byte) 27, (byte) '[', (byte) 'B', (byte) '\r');
        readThread.join(2_000L);

        assertFalse(readThread.isAlive(), "The prompt should still accept Enter after consuming Down");
        if (failure.get() != null) {
            fail(failure.get());
        }
        assertEquals("", line.get());
        assertFalse(terminalOutput.toString(StandardCharsets.UTF_8).contains("\u0007"),
                "Fresh Down from the managed prompt must not emit JLine's invalid-key bell");
    }

    @Test
    void fixedBusyUiKeepsSpinnerStatusWithoutQueueIndicator() throws Exception {
        EmulatedPassthroughCommand command = configuredBusyCommand("next draft");
        MessageQueue queue = new MessageQueue("managed-ui-" + System.nanoTime());
        queue.enqueue("pending draft");
        setField(command, "messageQueue", queue);

        ByteArrayOutputStream output = new ByteArrayOutputStream();
        PrintStream originalOut = System.out;
        try (PrintStream capture = new PrintStream(output, true, StandardCharsets.UTF_8)) {
            System.setOut(capture);
            invokeNoArg(command, "drawFixedInputBox");
        } finally {
            System.setOut(originalOut);
        }

        String rendered = output.toString(StandardCharsets.UTF_8);
        assertTrue(rendered.contains("kompile [opencode] · running"), "The fixed status row should remain visible");
        assertTrue(rendered.contains("pending draft"), "The pending message content should remain above the input box");
        assertTrue(rendered.contains("draft> next draft"), "Busy input should remain inside the input box");
        assertFalse(rendered.contains("queued ["), "The preview should not render a queued-ID indicator");
        assertFalse(rendered.contains(" queue 1"), "The status line should not render a queue-count indicator");
        assertFalse(rendered.contains("queued>"), "The busy input prefix should not look like a queue indicator");
    }

    @Test
    void slashAutocompleteRendersBelowInputStatusArea() throws Exception {
        EmulatedPassthroughCommand command = configuredIdleCommand();
        setField(command, "scrollBottom", 18);
        setField(command, "activityRows", 3);

        ByteArrayOutputStream output = new ByteArrayOutputStream();
        PrintStream originalOut = System.out;
        try (PrintStream capture = new PrintStream(output, true, StandardCharsets.UTF_8)) {
            System.setOut(capture);
            invokeStringIntArg(command, "updateSlashCompletionPanel", "/", 1);

            String rendered = output.toString(StandardCharsets.UTF_8);
            int completionRowIndex = rendered.indexOf("\033[27;1H\033[2K");
            int helpIndex = rendered.indexOf("/help");
            assertTrue(completionRowIndex >= 0, "Slash completions should render in the lower activity rows");
            assertTrue(helpIndex > completionRowIndex, "Slash command suggestions should render on the activity row");
            assertFalse(rendered.contains("\033[22;1H\033[2K"),
                    "Autocomplete refresh must not clear the JLine input row and drop the typed prefix");
            assertFalse(rendered.contains("kompile [opencode] · idle"),
                    "Autocomplete refresh should not redraw the whole fixed input/status area");

            output.reset();
            invokeStringIntArg(command, "updateSlashCompletionPanel", "plain text", 10);
            String cleared = output.toString(StandardCharsets.UTF_8);
            assertFalse(cleared.contains("/help"), "Non-slash input should clear the lower completion panel");
            assertFalse(cleared.contains("\033[22;1H\033[2K"),
                    "Clearing autocomplete must not clear the JLine input row either");
        } finally {
            System.setOut(originalOut);
        }
    }

    @Test
    void fixedActivityPanelRendersBackgroundSubagentsAndTodos() throws Exception {
        EmulatedPassthroughCommand command = configuredBusyCommand("draft while busy");
        setField(command, "scrollBottom", 18);
        setField(command, "activityRows", 3);

        ByteArrayOutputStream output = new ByteArrayOutputStream();
        PrintStream originalOut = System.out;
        try (PrintStream capture = new PrintStream(output, true, StandardCharsets.UTF_8)) {
            System.setOut(capture);
            invokeToolUseArg(command, "trackToolActivityStart", new PassthroughStreamParser.ToolUse(
                    "process", "{\"action\":\"launch\",\"command\":\"mvn test\",\"background\":true}"));
            invokeToolUseArg(command, "trackToolActivityStart", new PassthroughStreamParser.ToolUse(
                    "task", "{\"description\":\"scan module\"}"));
            invokeToolUseArg(command, "trackToolActivityStart", new PassthroughStreamParser.ToolUse(
                    "todo_write", "{\"todos\":[{\"content\":\"verify logs\",\"status\":\"in_progress\"},{\"content\":\"done item\",\"status\":\"completed\"}]}"));
            invokeNoArg(command, "drawFixedInputBox");
        } finally {
            System.setOut(originalOut);
        }

        String rendered = output.toString(StandardCharsets.UTF_8);
        assertTrue(rendered.contains("] bg running"), "Background process activity should be passively visible below the input bar");
        assertTrue(rendered.contains("mvn test"), "Background process rows should include the process command");
        assertTrue(rendered.contains("] agent running"), "Subagent activity should be passively visible below the input bar");
        assertTrue(rendered.contains("scan module"), "Subagent rows should include the task description");
        assertTrue(rendered.contains("  todos "), "Active todos should render below the input bar when there is room");
        assertTrue(rendered.contains("[*] verify logs"), "In-progress todo items should render as checklist rows");
        assertTrue(rendered.contains("[x] done item"), "Completed todo items should stay visible as checked items");
    }

    @Test
    void todoWriteActionsMaintainChecklistState() throws Exception {
        EmulatedPassthroughCommand command = configuredBusyCommand("draft while busy");
        setField(command, "scrollBottom", 18);
        setField(command, "activityRows", 3);

        ByteArrayOutputStream output = new ByteArrayOutputStream();
        PrintStream originalOut = System.out;
        try (PrintStream capture = new PrintStream(output, true, StandardCharsets.UTF_8)) {
            System.setOut(capture);
            invokeToolUseArg(command, "trackToolActivityStart", new PassthroughStreamParser.ToolUse(
                    "todowrite", "{\"action\":\"add\",\"subject\":\"wire checklist\",\"status\":\"pending\"}"));
            invokeTwoStringBooleanArg(command, "trackToolActivityComplete", "todowrite", "Added task #42: wire checklist", false);
            invokeToolUseArg(command, "trackToolActivityStart", new PassthroughStreamParser.ToolUse(
                    "todowrite", "{\"action\":\"update\",\"task_id\":\"42\",\"status\":\"in_progress\"}"));
            invokeToolUseArg(command, "trackToolActivityStart", new PassthroughStreamParser.ToolUse(
                    "todowrite", "{\"action\":\"add\",\"subject\":\"remove stale row\",\"status\":\"pending\"}"));
            invokeToolUseArg(command, "trackToolActivityStart", new PassthroughStreamParser.ToolUse(
                    "todowrite", "{\"action\":\"delete\",\"subject\":\"remove stale row\"}"));
            invokeTwoStringBooleanArg(command, "trackToolActivityComplete", "todowrite",
                    "Tasks\n[*] #42: wire checklist\n[x] #44: finished task", false);
            output.reset();
            invokeNoArg(command, "drawFixedInputBox");
        } finally {
            System.setOut(originalOut);
        }

        String rendered = output.toString(StandardCharsets.UTF_8);
        assertTrue(rendered.contains("  todos "), "Todo write calls should maintain a checklist row");
        assertTrue(rendered.contains("[*] wire checklist"), "Update should change the existing checklist item status");
        assertTrue(rendered.contains("[x] finished task"), "Full todowrite task-list output should replace the checklist state");
        assertFalse(rendered.contains("remove stale row"), "Delete should remove the checklist item");
    }

    @Test
    void activityPanelUpdatesLiveLogsForBackgroundsAndSubagents() throws Exception {
        EmulatedPassthroughCommand command = configuredBusyCommand("draft while busy");
        setField(command, "scrollBottom", 18);
        setField(command, "activityRows", 3);

        ByteArrayOutputStream output = new ByteArrayOutputStream();
        PrintStream originalOut = System.out;
        try (PrintStream capture = new PrintStream(output, true, StandardCharsets.UTF_8)) {
            System.setOut(capture);
            invokeTwoStringReturn(command, "startBackgroundActivity", "opencode response", "starting");
            invokeStringArg(command, "trackAssistantLog", "live token chunk");
            invokeToolUseArg(command, "trackToolActivityStart", new PassthroughStreamParser.ToolUse(
                    "task", "{\"description\":\"delegate checks\"}"));
            invokeTwoStringArg(command, "trackToolActivityLog", "task", "subagent log line");
            invokeNoArg(command, "drawFixedInputBox");
        } finally {
            System.setOut(originalOut);
        }

        String rendered = output.toString(StandardCharsets.UTF_8);
        assertTrue(rendered.contains("opencode response"), "The backgrounded agent response should remain visible while running");
        assertTrue(rendered.contains("live token chunk"), "Background response logs should update in the activity panel");
        assertTrue(rendered.contains("delegate checks"), "Running subagent should be visible while active");
        assertTrue(rendered.contains("subagent log line"), "Subagent log output should update in the activity panel");
    }

    @Test
    void activityPanelShowsManageableIdsAndMenuHint() throws Exception {
        EmulatedPassthroughCommand command = configuredBusyCommand("draft while busy");
        setField(command, "scrollBottom", 18);
        setField(command, "activityRows", 3);

        ByteArrayOutputStream output = new ByteArrayOutputStream();
        PrintStream originalOut = System.out;
        try (PrintStream capture = new PrintStream(output, true, StandardCharsets.UTF_8)) {
            System.setOut(capture);
            Object key = invokeTwoStringReturn(command, "startBackgroundActivity", "opencode response", "streaming");
            invokeToolUseArg(command, "trackToolActivityStart", new PassthroughStreamParser.ToolUse(
                    "task", "{\"description\":\"delegate checks\"}"));
            invokeNoArg(command, "drawFixedInputBox");

            String rendered = output.toString(StandardCharsets.UTF_8);
            assertTrue(rendered.contains("[" + key + "]"), "Background rows should expose stable ids");
            assertTrue(rendered.contains("/activity logs <id>"), "Activity rows should advertise log browsing");
            assertTrue(rendered.contains("/activity kill <id>"), "Activity rows should advertise kill handling");
        } finally {
            System.setOut(originalOut);
        }
    }

    @Test
    void activitySelectionHighlightsPassiveRowsAndEnterShowsLogs() throws Exception {
        EmulatedPassthroughCommand command = configuredIdleCommand();
        setField(command, "scrollBottom", 18);
        setField(command, "activityRows", 3);
        String key = (String) invokeTwoStringReturn(command, "startBackgroundActivity", "opencode response", "starting");
        invokeStringArg(command, "trackAssistantLog", "live selectable log");

        ByteArrayOutputStream output = new ByteArrayOutputStream();
        PrintStream originalOut = System.out;
        try (PrintStream capture = new PrintStream(output, true, StandardCharsets.UTF_8)) {
            System.setOut(capture);
            assertTrue(invokeBooleanNoArg(command, "selectNextActivityItem"));
            invokeNoArg(command, "drawFixedInputBox");
            String highlighted = output.toString(StandardCharsets.UTF_8);
            assertTrue(highlighted.contains("\033[7m  [" + key + "]"), "Down selection should highlight the activity row");

            output.reset();
            assertTrue(invokeBooleanNoArg(command, "openSelectedActivityLogs"));
        } finally {
            System.setOut(originalOut);
        }

        String rendered = output.toString(StandardCharsets.UTF_8);
        assertTrue(rendered.contains("Activity Logs: " + key), "Enter on a selected activity should open logs");
        assertTrue(rendered.contains("live selectable log"), "Selected activity logs should be shown");
    }

    @Test
    void selectedActivityKillStopsManagedBackgroundProcess() throws Exception {
        EmulatedPassthroughCommand command = configuredIdleCommand();
        Process process = new ProcessBuilder("sh", "-c", "sleep 30").start();
        invokeTwoStringProcessReturn(command, "startBackgroundActivity", "killable", "running", process);
        try {
            assertTrue(invokeBooleanNoArg(command, "selectNextActivityItem"));
            assertTrue(invokeBooleanNoArg(command, "killSelectedActivityItem"), "Delete on a selected activity should request kill");
            assertTrue(await(() -> !process.isAlive()), "The selected managed subprocess should be terminated");
        } finally {
            if (process.isAlive()) {
                process.destroyForcibly();
            }
        }
    }

    @Test
    void activityMenuShowsLogsBelowStatusAreaAndInScrollRegion() throws Exception {
        EmulatedPassthroughCommand command = configuredBusyCommand("draft while busy");
        setField(command, "scrollBottom", 18);
        setField(command, "activityRows", 3);
        String key = (String) invokeTwoStringReturn(command, "startBackgroundActivity", "opencode response", "starting");
        invokeStringArg(command, "trackAssistantLog", "live log line");

        ByteArrayOutputStream output = new ByteArrayOutputStream();
        PrintStream originalOut = System.out;
        try (PrintStream capture = new PrintStream(output, true, StandardCharsets.UTF_8)) {
            System.setOut(capture);
            invokeStringArg(command, "handleActivitySlash", "logs " + key);
        } finally {
            System.setOut(originalOut);
        }

        String rendered = output.toString(StandardCharsets.UTF_8);
        assertTrue(rendered.contains("Activity Logs: " + key), "Log command should print a readable log section");
        assertTrue(rendered.contains("live log line"), "Captured background logs should be shown on demand");
        assertTrue(rendered.contains("showing logs for " + key), "Lower menu should stay open after viewing logs");
    }

    @Test
    void activityKillStopsManagedBackgroundProcess() throws Exception {
        EmulatedPassthroughCommand command = configuredBusyCommand("draft while busy");
        Process process = new ProcessBuilder("sh", "-c", "sleep 30").start();
        String key = (String) invokeTwoStringProcessReturn(command, "startBackgroundActivity", "killable", "running", process);
        try {
            assertTrue(invokeBooleanStringArg(command, "killActivityItem", key), "Kill should succeed for owned background processes");
            assertTrue(await(() -> !process.isAlive()), "The managed subprocess should be terminated");
        } finally {
            if (process.isAlive()) {
                process.destroyForcibly();
            }
        }
    }

    @Test
    void safePrintlnRendersAgentOutputAndBusyInputTogether() throws Exception {
        EmulatedPassthroughCommand command = configuredBusyCommand("queued while busy");

        ByteArrayOutputStream output = new ByteArrayOutputStream();
        PrintStream originalOut = System.out;
        try (PrintStream capture = new PrintStream(output, true, StandardCharsets.UTF_8)) {
            System.setOut(capture);
            invokeStringArg(command, "safePrintln", "  agent says hello");
        } finally {
            System.setOut(originalOut);
        }

        String rendered = output.toString(StandardCharsets.UTF_8);
        assertTrue(rendered.contains("agent says hello"), "Agent output should render in the chat scroll region");
        assertTrue(rendered.contains("draft> queued while busy"), "The busy draft input should render in the input box");
        assertTrue(rendered.contains("kompile [opencode] · running"), "The fixed status row should remain visible below the input box");
    }

    @Test
    void responseSpinnerRendersInlineAtLastAgentMessage() throws Exception {
        EmulatedPassthroughCommand command = configuredBusyCommand("draft while waiting");

        ByteArrayOutputStream output = new ByteArrayOutputStream();
        PrintStream originalOut = System.out;
        try (PrintStream capture = new PrintStream(output, true, StandardCharsets.UTF_8)) {
            System.setOut(capture);
            TerminalRenderer.SpinnerHandle handle =
                    (TerminalRenderer.SpinnerHandle) invokeStringReturn(command, "startStatusSpinner", "opencode");
            Thread.sleep(180L);
            handle.stop();
        } finally {
            System.setOut(originalOut);
        }

        String rendered = output.toString(StandardCharsets.UTF_8);
        assertTrue(rendered.contains("running (opencode)"), "The spinner text should stay with the active response");
        assertTrue(rendered.contains("\033[20;1H\033[2K"), "The spinner should render in the chat scroll region");
        assertTrue(rendered.contains("kompile [opencode] · idle"), "Stopping the spinner should restore fixed status to idle");
    }

    @Test
    void backgroundRequestReleasesCurrentTurnWithoutCanceling() throws Exception {
        EmulatedPassthroughCommand command = configuredBusyCommand("draft while current runs");

        ByteArrayOutputStream output = new ByteArrayOutputStream();
        PrintStream originalOut = System.out;
        try (PrintStream capture = new PrintStream(output, true, StandardCharsets.UTF_8)) {
            System.setOut(capture);
            invokeNoArg(command, "requestAgentBackground");
        } finally {
            System.setOut(originalOut);
        }

        AtomicBoolean backgroundSignal = (AtomicBoolean) getField(command, "backgroundSignal");
        AtomicBoolean cancelSignal = (AtomicBoolean) getField(command, "cancelSignal");
        assertTrue(backgroundSignal.get(), "Backgrounding must release the current turn from busy input capture");
        assertFalse(cancelSignal.get(), "Backgrounding must not cancel the active agent process");
        assertTrue(output.toString(StandardCharsets.UTF_8).contains("Backgrounding current response"));
    }

    @Test
    void busyDraftTransfersToIdlePromptWhenTurnFinishes() throws Exception {
        EmulatedPassthroughCommand command = configuredBusyCommand("unfinished draft");

        invokeNoArg(command, "preserveBusyDraftForIdle");

        assertEquals("unfinished draft", invokeStringNoArg(command, "takePendingIdleDraft"));
        assertEquals("", invokeStringNoArg(command, "takePendingIdleDraft"),
                "The pending idle draft should be consumed once by the next prompt");
    }

    @Test
    void safePrintlnPreservesIdlePromptCursor() throws Exception {
        EmulatedPassthroughCommand command = configuredIdleCommand();

        ByteArrayOutputStream output = new ByteArrayOutputStream();
        PrintStream originalOut = System.out;
        try (PrintStream capture = new PrintStream(output, true, StandardCharsets.UTF_8)) {
            System.setOut(capture);
            invokeStringArg(command, "safePrintln", "  background output");
        } finally {
            System.setOut(originalOut);
        }

        String rendered = output.toString(StandardCharsets.UTF_8);
        assertTrue(rendered.startsWith("\0337"), "Idle output should save the prompt cursor before writing to the scroll region");
        assertTrue(rendered.endsWith("\0338"), "Idle output should restore the prompt cursor after redrawing the fixed input");
        assertTrue(rendered.contains("background output"));
    }

    @Test
    void decodedTuiTextFlushesCompleteMarkdownBlocksThroughChatRenderer() throws Exception {
        EmulatedPassthroughCommand command = configuredIdleCommand();
        TerminalRenderer plainRenderer = new TerminalRenderer(false);
        setField(command, "renderer", plainRenderer);
        setField(command, "ascii", new AsciiRenderer(plainRenderer, 100));
        setField(command, "tuiPendingText", new StringBuilder());

        StringBuilder fullText = new StringBuilder();
        AtomicBoolean spinnerStopped = new AtomicBoolean(true);
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        PrintStream originalOut = System.out;
        try (PrintStream capture = new PrintStream(output, true, StandardCharsets.UTF_8)) {
            System.setOut(capture);
            invokeEmitDecodedTuiText(command,
                    "[tool:read]\n{\"file_path\":\"src/App.java\"}\n",
                    fullText, null, spinnerStopped, false);
            assertEquals("", output.toString(StandardCharsets.UTF_8),
                    "Open markdown/tool blocks must not leak raw markers while streaming");

            invokeEmitDecodedTuiText(command,
                    "[/tool]\n\n```java\nSystem.out.println(\"ok\");\n",
                    fullText, null, spinnerStopped, false);
            assertEquals("", output.toString(StandardCharsets.UTF_8),
                    "Open code fences must stay buffered until the block is complete");

            invokeEmitDecodedTuiText(command, "```\n", fullText, null, spinnerStopped, true);
        } finally {
            System.setOut(originalOut);
        }

        String rendered = output.toString(StandardCharsets.UTF_8);
        assertFalse(rendered.contains("[tool:read]"), "Tool block markers should be consumed by markdown preprocessing");
        assertFalse(rendered.contains("[/tool]"), "Tool block markers should be consumed by markdown preprocessing");
        assertFalse(rendered.contains("```"), "Code fences should be rendered as a code block, not printed literally");
        assertTrue(rendered.contains("read"), "Rendered tool panel should keep the tool name visible");
        assertTrue(rendered.contains("file_path"), "Rendered tool panel should keep the tool input visible");
        assertTrue(rendered.contains("System.out.println"), "Rendered code block should keep the code visible");
        assertTrue(fullText.toString().contains("[tool:read]"), "History should retain the raw assistant text");
    }

    @Test
    void structuredTextChunksFlushMarkdownOnTurnComplete() throws Exception {
        EmulatedPassthroughCommand command = configuredIdleCommand();
        TerminalRenderer plainRenderer = new TerminalRenderer(false);
        setField(command, "renderer", plainRenderer);
        setField(command, "ascii", new AsciiRenderer(plainRenderer, 100));

        StringBuilder fullText = new StringBuilder();
        StringBuilder pendingText = new StringBuilder();
        java.util.ArrayList<String> toolCalls = new java.util.ArrayList<>();
        ChatSessionMetrics metrics = new ChatSessionMetrics("structured-markdown-test");
        AtomicBoolean spinnerStopped = new AtomicBoolean(true);
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        PrintStream originalOut = System.out;
        try (PrintStream capture = new PrintStream(output, true, StandardCharsets.UTF_8)) {
            System.setOut(capture);
            invokeProcessEvent(command,
                    new PassthroughStreamParser.TextChunk("```java\nSystem.out.println(\"ok\");\n"),
                    fullText, toolCalls, metrics, null, spinnerStopped, pendingText);
            assertEquals("", output.toString(StandardCharsets.UTF_8),
                    "Structured text chunks should wait for a flush boundary before rendering markdown");

            invokeProcessEvent(command,
                    new PassthroughStreamParser.TextChunk("```\n"),
                    fullText, toolCalls, metrics, null, spinnerStopped, pendingText);
            invokeProcessEvent(command,
                    new PassthroughStreamParser.TurnComplete(0, 0.0, 0),
                    fullText, toolCalls, metrics, null, spinnerStopped, pendingText);
        } finally {
            System.setOut(originalOut);
        }

        String rendered = output.toString(StandardCharsets.UTF_8);
        assertFalse(rendered.contains("```"), "Structured markdown code fences should render as a code block");
        assertTrue(rendered.contains("System.out.println"), "Structured markdown code block should keep the code visible");
        assertEquals(0, pendingText.length(), "Turn completion should clear buffered structured text");
        assertTrue(fullText.toString().contains("```java"), "History should retain raw structured text chunks");
    }

    @Test
    void safePrintlnRestoresActiveReadLinePromptAndQueuedBuffer() throws Exception {
        EmulatedPassthroughCommand command = configuredIdleCommand();
        setField(command, "agentBusy", true);
        LineReader reader = LineReaderBuilder.builder()
                .terminal(terminal)
                .build();
        ((LineReaderImpl) reader).getBuffer().write("queued followup");
        setField(command, "activeLineReader", reader);

        ByteArrayOutputStream output = new ByteArrayOutputStream();
        PrintStream originalOut = System.out;
        try (PrintStream capture = new PrintStream(output, true, StandardCharsets.UTF_8)) {
            System.setOut(capture);
            invokeStringArg(command, "safePrintln", "  streamed response line");
        } finally {
            System.setOut(originalOut);
        }

        String rendered = output.toString(StandardCharsets.UTF_8);
        assertTrue(rendered.contains("streamed response line"));
        assertTrue(rendered.contains("kompile "), "Streaming output must redraw the Kompile prompt");
        assertTrue(rendered.contains("[opencode]"), "Streaming output must redraw the current agent");
        assertTrue(rendered.contains("[busy]"), "Streaming output must preserve the busy prompt marker");
        assertTrue(rendered.contains("queued followup"), "Streaming output must keep typed queued input visible");
        assertTrue(rendered.contains("\033[?25l"), "Streaming output must hide the cursor while painting output rows");
        assertTrue(rendered.contains("\033[?25h"), "Streaming output must force the host cursor visible at the prompt");
        assertTrue(rendered.indexOf("\033[?25l") < rendered.indexOf("streamed response line"),
                "Cursor should be hidden before moving through the output row");
        assertTrue(rendered.lastIndexOf("\033[?25h") > rendered.indexOf("streamed response line"),
                "Cursor should be shown again only after output has been painted");
        assertFalse(rendered.endsWith("\0338"), "Active readLine output must not restore a stale saved cursor");

        output.reset();
        try (PrintStream capture = new PrintStream(output, true, StandardCharsets.UTF_8)) {
            System.setOut(capture);
            invokeStringArg(command, "safePrintln", "  next streamed response line");
        } finally {
            System.setOut(originalOut);
        }

        String secondRender = output.toString(StandardCharsets.UTF_8);
        assertTrue(secondRender.contains("next streamed response line"));
        assertFalse(secondRender.contains("\033[24;1H\033[2K"),
                "Unchanged active prompt must not be cleared on every streamed line");
        assertTrue(secondRender.contains("\033[?25l"), "The cursor must be hidden during every streamed paint");
        assertTrue(secondRender.contains("\033[?25h"), "The cursor must stay visible after every streamed line");
    }

    @Test
    void restoreIdlePromptCursorShowsCursorAndMovesToInputRow() throws Exception {
        EmulatedPassthroughCommand command = configuredIdleCommand();

        ByteArrayOutputStream output = new ByteArrayOutputStream();
        PrintStream originalOut = System.out;
        try (PrintStream capture = new PrintStream(output, true, StandardCharsets.UTF_8)) {
            System.setOut(capture);
            invokeNoArg(command, "restoreIdlePromptCursor");
        } finally {
            System.setOut(originalOut);
        }

        String rendered = output.toString(StandardCharsets.UTF_8);
        assertTrue(rendered.contains("\033[?25h"), "Idle handoff must force the host cursor visible");
        assertTrue(rendered.contains("\033[24;1H"), "Idle handoff must return to the first input row");
        assertTrue(rendered.contains("kompile "), "Idle handoff must redraw the visible Kompile prompt");
        assertTrue(rendered.contains("[opencode]"), "Idle handoff must redraw the current agent in the prompt");
        assertTrue(rendered.contains("> "), "Idle handoff must redraw the input marker");
    }

    @Test
    void backgroundFollowupsUseKompileManagedIsolatedProcessesForAllProviders() throws Exception {
        EmulatedPassthroughCommand command = configuredIdleCommand();
        setField(command, "firstMessageSent", true);
        setField(command, "agentSessionId", "provider-session");
        ((AtomicInteger) getField(command, "backgroundTurnCount")).set(1);

        for (String provider : List.of("claude", "codex", "gemini", "qwen", "opencode", "unknown-agent")) {
            setField(command, "agent", provider);
            assertTrue(invokeBooleanNoArg(command, "canDispatchQueuedMessageAfterBackground"),
                    provider + " should use Kompile-managed background dispatch");
            List<String> built = invokeBuildCommand(command, provider, "next draft");
            assertEquals(List.of(provider), built, provider + " should launch only the interactive provider binary");
            assertFalse(built.contains("--continue"), provider + " must not continue an active background session");
            assertFalse(built.contains("resume"), provider + " must not resume an active background session");
            assertFalse(built.contains("--resume"), provider + " must not resume an active background session");
            assertFalse(built.contains("--session"), provider + " must not target an active background session");
            assertFalse(built.contains("provider-session"), provider + " must not reuse an active background session id");
            assertFalse(built.contains("--fork"), provider + " must not rely on provider-specific fork flags");
            assertFalse(built.contains("next draft"), provider + " should receive the queued prompt over stdin, not argv");
        }
    }

    private EmulatedPassthroughCommand configuredBusyCommand(String inputBuffer) throws Exception {
        EmulatedPassthroughCommand command = new EmulatedPassthroughCommand();
        setField(command, "terminal", terminal);
        setField(command, "drawLock", new Object());
        setField(command, "agent", "opencode");
        setField(command, "messageQueue", new MessageQueue("managed-ui-" + System.nanoTime()));
        setField(command, "scrollBottom", 20);
        setField(command, "inputRows", 3);
        setField(command, "busyInputActive", true);
        setField(command, "busyInputBuffer", inputBuffer);
        setField(command, "currentStatus", "running");
        return command;
    }

    private EmulatedPassthroughCommand configuredIdleCommand() throws Exception {
        EmulatedPassthroughCommand command = new EmulatedPassthroughCommand();
        setField(command, "terminal", terminal);
        setField(command, "drawLock", new Object());
        setField(command, "agent", "opencode");
        setField(command, "messageQueue", new MessageQueue("managed-ui-" + System.nanoTime()));
        setField(command, "scrollBottom", 20);
        setField(command, "inputRows", 3);
        setField(command, "busyInputActive", false);
        setField(command, "busyInputBuffer", "");
        setField(command, "currentStatus", "idle");
        return command;
    }

    private static void setField(Object target, String name, Object value) throws Exception {
        Field field = EmulatedPassthroughCommand.class.getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }

    private static Object getField(Object target, String name) throws Exception {
        Field field = EmulatedPassthroughCommand.class.getDeclaredField(name);
        field.setAccessible(true);
        return field.get(target);
    }

    private static void invokeNoArg(Object target, String name) throws Exception {
        Method method = EmulatedPassthroughCommand.class.getDeclaredMethod(name);
        method.setAccessible(true);
        method.invoke(target);
    }

    private static Object invokeNoArgReturn(Object target, String name) throws Exception {
        Method method = EmulatedPassthroughCommand.class.getDeclaredMethod(name);
        method.setAccessible(true);
        return method.invoke(target);
    }

    private static Object newTerminalQueryStripper() throws Exception {
        Class<?> stripperClass = Class.forName(EmulatedPassthroughCommand.class.getName() + "$TerminalQueryStripper");
        Constructor<?> constructor = stripperClass.getDeclaredConstructor();
        constructor.setAccessible(true);
        return constructor.newInstance();
    }

    private static StripResult stripTerminalChunk(Object stripper, String chunk) throws Exception {
        byte[] bytes = chunk.getBytes(StandardCharsets.UTF_8);
        Method strip = stripper.getClass().getDeclaredMethod("strip", byte[].class, int.class, int.class);
        strip.setAccessible(true);
        Object rawResult = strip.invoke(stripper, bytes, 0, bytes.length);
        Method displayBytes = rawResult.getClass().getDeclaredMethod("displayBytes");
        Method queries = rawResult.getClass().getDeclaredMethod("queries");
        displayBytes.setAccessible(true);
        queries.setAccessible(true);
        return new StripResult((byte[]) displayBytes.invoke(rawResult), (String) queries.invoke(rawResult));
    }

    private record StripResult(byte[] displayBytes, String queries) {}

    private static void invokeStringArg(Object target, String name, String value) throws Exception {
        Method method = EmulatedPassthroughCommand.class.getDeclaredMethod(name, String.class);
        method.setAccessible(true);
        method.invoke(target, value);
    }

    private static void invokeEmitDecodedTuiText(Object target, String text, StringBuilder fullText,
                                                 TerminalRenderer.SpinnerHandle spinner,
                                                 AtomicBoolean spinnerStopped,
                                                 boolean finalChunk) throws Exception {
        Method method = EmulatedPassthroughCommand.class.getDeclaredMethod("emitDecodedTuiText",
                String.class, StringBuilder.class, TerminalRenderer.SpinnerHandle.class,
                AtomicBoolean.class, boolean.class);
        method.setAccessible(true);
        method.invoke(target, text, fullText, spinner, spinnerStopped, finalChunk);
    }

    private static void invokeProcessEvent(Object target, PassthroughStreamParser.PassthroughEvent event,
                                           StringBuilder fullText, List<String> toolCalls,
                                           ChatSessionMetrics metrics,
                                           TerminalRenderer.SpinnerHandle spinner,
                                           AtomicBoolean spinnerStopped,
                                           StringBuilder pendingText) throws Exception {
        Method method = EmulatedPassthroughCommand.class.getDeclaredMethod("processEvent",
                PassthroughStreamParser.PassthroughEvent.class, StringBuilder.class, List.class,
                ChatSessionMetrics.class, TerminalRenderer.SpinnerHandle.class,
                AtomicBoolean.class, StringBuilder.class);
        method.setAccessible(true);
        method.invoke(target, event, fullText, toolCalls, metrics, spinner, spinnerStopped, pendingText);
    }

    private static void invokeDecoderScreen(Object target,
                                            ai.kompile.cli.main.chat.tui.AgentTuiDecoder decoder,
                                            ai.kompile.cli.main.chat.tui.VirtualTerminal vt) throws Exception {
        Method method = EmulatedPassthroughCommand.class.getDeclaredMethod("processDecodedTuiScreen",
                ai.kompile.cli.main.chat.tui.AgentTuiDecoder.class,
                ai.kompile.cli.main.chat.tui.VirtualTerminal.class);
        method.setAccessible(true);
        method.invoke(target, decoder, vt);
    }

    private static String numberedLines(int count) {
        StringBuilder sb = new StringBuilder();
        for (int i = 1; i <= count; i++) {
            if (i > 1) sb.append('\n');
            sb.append(String.format("line-%02d", i));
        }
        return sb.toString();
    }

    private static void invokeLineReaderArg(Object target, String name, LineReader reader) throws Exception {
        Method method = EmulatedPassthroughCommand.class.getDeclaredMethod(name, LineReader.class);
        method.setAccessible(true);
        method.invoke(target, reader);
    }

    private static void assertReferenceBinding(Binding binding, String expectedName) {
        assertInstanceOf(Reference.class, binding);
        assertEquals(expectedName, ((Reference) binding).name());
    }

    private static void invokeStringIntArg(Object target, String name, String value, int cursor) throws Exception {
        Method method = EmulatedPassthroughCommand.class.getDeclaredMethod(name, String.class, int.class);
        method.setAccessible(true);
        method.invoke(target, value, cursor);
    }

    private static Object invokeStringReturn(Object target, String name, String value) throws Exception {
        Method method = EmulatedPassthroughCommand.class.getDeclaredMethod(name, String.class);
        method.setAccessible(true);
        return method.invoke(target, value);
    }

    private static Object invokeTwoStringReturn(Object target, String name, String first, String second) throws Exception {
        Method method = EmulatedPassthroughCommand.class.getDeclaredMethod(name, String.class, String.class);
        method.setAccessible(true);
        return method.invoke(target, first, second);
    }

    private static Object invokeTwoStringProcessReturn(Object target, String name, String first, String second, Process process) throws Exception {
        Method method = EmulatedPassthroughCommand.class.getDeclaredMethod(name, String.class, String.class, Process.class);
        method.setAccessible(true);
        return method.invoke(target, first, second, process);
    }

    private static boolean invokeBooleanStringArg(Object target, String name, String value) throws Exception {
        Method method = EmulatedPassthroughCommand.class.getDeclaredMethod(name, String.class);
        method.setAccessible(true);
        return (Boolean) method.invoke(target, value);
    }

    private static void invokeTwoStringArg(Object target, String name, String first, String second) throws Exception {
        Method method = EmulatedPassthroughCommand.class.getDeclaredMethod(name, String.class, String.class);
        method.setAccessible(true);
        method.invoke(target, first, second);
    }

    private static void invokeTwoStringBooleanArg(Object target, String name, String first, String second, boolean flag) throws Exception {
        Method method = EmulatedPassthroughCommand.class.getDeclaredMethod(name, String.class, String.class, boolean.class);
        method.setAccessible(true);
        method.invoke(target, first, second, flag);
    }

    private static void invokeToolUseArg(Object target, String name, PassthroughStreamParser.ToolUse toolUse) throws Exception {
        Method method = EmulatedPassthroughCommand.class.getDeclaredMethod(name, PassthroughStreamParser.ToolUse.class);
        method.setAccessible(true);
        method.invoke(target, toolUse);
    }

    private static String invokeStringNoArg(Object target, String name) throws Exception {
        Method method = EmulatedPassthroughCommand.class.getDeclaredMethod(name);
        method.setAccessible(true);
        return (String) method.invoke(target);
    }

    private static boolean invokeBooleanNoArg(Object target, String name) throws Exception {
        Method method = EmulatedPassthroughCommand.class.getDeclaredMethod(name);
        method.setAccessible(true);
        return (Boolean) method.invoke(target);
    }

    @SuppressWarnings("unchecked")
    private static List<String> invokeBuildCommand(Object target, String binary, String message) throws Exception {
        Method method = EmulatedPassthroughCommand.class.getDeclaredMethod("buildCommand", String.class, String.class);
        method.setAccessible(true);
        return (List<String>) method.invoke(target, binary, message);
    }

    private void writeBytes(byte... bytes) throws IOException {
        keyboardPipe.write(bytes);
        keyboardPipe.flush();
    }

    private static boolean await(BooleanSupplier condition) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 2_000L;
        while (System.currentTimeMillis() < deadline) {
            if (condition.getAsBoolean()) {
                return true;
            }
            Thread.sleep(20L);
        }
        return condition.getAsBoolean();
    }
}
