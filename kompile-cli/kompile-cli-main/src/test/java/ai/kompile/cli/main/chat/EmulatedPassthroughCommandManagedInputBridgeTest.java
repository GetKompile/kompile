package ai.kompile.cli.main.chat;

import ai.kompile.cli.main.chat.agent.AgentLaunchDefaults;
import ai.kompile.cli.main.chat.render.AsciiRenderer;
import ai.kompile.cli.main.chat.render.TerminalRenderer;
import ai.kompile.cli.main.chat.terminal.TerminalQueryStripResult;
import ai.kompile.cli.main.chat.terminal.TerminalQueryStripper;
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
    @org.junit.jupiter.api.io.TempDir
    java.nio.file.Path tempDir;

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
        TerminalQueryStripper stripper = newTerminalQueryStripper();
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
    void activePromptScrollRedrawClearsAllInputRows() throws Exception {
        EmulatedPassthroughCommand command = configuredIdleCommand();
        setField(command, "scrollBottom", 18);
        setField(command, "inputRows", 3);

        ByteArrayOutputStream output = new ByteArrayOutputStream();
        PrintStream originalOut = System.out;
        try (PrintStream capture = new PrintStream(output, true, StandardCharsets.UTF_8)) {
            System.setOut(capture);
            invokeBooleanArg(command, "drawFixedInputChrome", true);
        } finally {
            System.setOut(originalOut);
        }

        String rendered = output.toString(StandardCharsets.UTF_8);
        assertTrue(rendered.contains("\033[22;1H\033[2K"), "First input row should be cleared");
        assertTrue(rendered.contains("\033[23;1H\033[2K"), "Second input row should be cleared");
        assertTrue(rendered.contains("\033[24;1H\033[2K"), "Third input row should be cleared");
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
    @SuppressWarnings("unchecked")
    void emptyFilteredDecodedSnapshotDoesNotErasePreviousLiveBlock() throws Exception {
        EmulatedPassthroughCommand command = configuredIdleCommand();
        TerminalRenderer plainRenderer = new TerminalRenderer(false);
        setField(command, "renderer", plainRenderer);
        setField(command, "ascii", new AsciiRenderer(plainRenderer, 100));
        setField(command, "lastSentMessage", "echo only");

        ByteArrayOutputStream output = new ByteArrayOutputStream();
        PrintStream originalOut = System.out;
        try (PrintStream capture = new PrintStream(output, true, StandardCharsets.UTF_8)) {
            System.setOut(capture);
            invokeUpdateLiveDecoderScrollbackBlock(command, "KOMP_LIVE_RENDER_DONE", false);
            invokeUpdateLiveDecoderScrollbackBlock(command, "echo only", true);
        } finally {
            System.setOut(originalOut);
        }

        List<String> scrollback = (List<String>) getField(command, "scrollbackLines");
        assertTrue(String.join("\n", scrollback).contains("KOMP_LIVE_RENDER_DONE"),
                "An echo-only final snapshot must not erase the last displayable decoded answer");
    }

    @Test
    void decodedDisplayFiltersWrappedPromptEchoFragments() throws Exception {
        EmulatedPassthroughCommand command = configuredIdleCommand();
        String prompt = "Print the exact token made by joining KOMP, LIVE, RENDER, and DONE with underscores. No other text.";
        setField(command, "lastSentMessage", prompt);

        String decoded = "\033[2m┃\033[0m  Print the exact token made by joining KOMP, LIVE, RENDER, and DONE\n"
                        + "\033[2m┃\033[0m  with underscores. No other text.\n"
                        + "KOMP_LIVE_RENDER_DONE";

        String filtered = invokeStringArgReturn(command, "filterDecodedTuiTextForDisplay", decoded);
        String historyFiltered = invokeStringArgReturn(command, "filterDecodedTuiText", decoded);

        assertFalse(filtered.contains("Print the exact token"),
                "Wrapped user prompt fragments should be removed from decoded display output");
        assertFalse(filtered.contains("with underscores"),
                "Wrapped user prompt continuations should be removed from decoded display output");
        assertTrue(filtered.contains("KOMP_LIVE_RENDER_DONE"),
                "Filtering prompt echoes must preserve real assistant output");
        assertFalse(historyFiltered.contains("Print the exact token"),
                "Wrapped user prompt fragments should be removed before appending decoded history");
        assertTrue(historyFiltered.contains("KOMP_LIVE_RENDER_DONE"),
                "History filtering must preserve real assistant output");
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
            assertTrue(rendered.contains("/activity enter <id>"), "Activity rows should advertise inspect/enter handling");
            assertTrue(rendered.contains("logs <id>"), "Activity rows should advertise log browsing");
            assertTrue(rendered.contains("kill <id>"), "Activity rows should advertise kill handling");
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
        assertTrue(rendered.contains("Activity: " + key), "Enter on a selected activity should inspect it");
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
    void completedSubagentStaysEnterableWithDelegationDetails() throws Exception {
        EmulatedPassthroughCommand command = configuredBusyCommand("draft while busy");
        setField(command, "scrollBottom", 18);
        setField(command, "activityRows", 4);

        ByteArrayOutputStream output = new ByteArrayOutputStream();
        PrintStream originalOut = System.out;
        try (PrintStream capture = new PrintStream(output, true, StandardCharsets.UTF_8)) {
            System.setOut(capture);
            invokeToolUseArg(command, "trackToolActivityStart", new PassthroughStreamParser.ToolUse(
                    "task", "{\"agent\":\"claude\",\"role\":\"reviewer\",\"description\":\"review process manager\"}"));
            invokeTwoStringBooleanArg(command, "trackToolActivityComplete", "task", "subagent finished with recommendation", false);
            invokeNoArg(command, "drawFixedInputBox");
            String panel = output.toString(StandardCharsets.UTF_8);
            assertTrue(panel.contains("[agent:task-1]"), "Completed subagent should keep a stable activity id");
            assertTrue(panel.contains("agent completed"), "Completed subagent should stay visible with terminal status");

            output.reset();
            invokeStringArg(command, "handleActivitySlash", "enter agent:task-1");
        } finally {
            System.setOut(originalOut);
        }

        String rendered = output.toString(StandardCharsets.UTF_8);
        assertTrue(rendered.contains("Activity: agent:task-1"));
        assertTrue(rendered.contains("kind: agent · status: completed"));
        assertTrue(rendered.contains("agent=claude"));
        assertTrue(rendered.contains("role=reviewer"));
        assertTrue(rendered.contains("review process manager"));
        assertTrue(rendered.contains("subagent finished with recommendation"));
    }

    @Test
    void processStatusSlashInspectsActivityInsteadOfListing() throws Exception {
        EmulatedPassthroughCommand command = configuredBusyCommand("draft while busy");
        String key = (String) invokeTwoStringReturn(command, "startBackgroundActivity", "opencode response", "starting");

        ByteArrayOutputStream output = new ByteArrayOutputStream();
        PrintStream originalOut = System.out;
        try (PrintStream capture = new PrintStream(output, true, StandardCharsets.UTF_8)) {
            System.setOut(capture);
            invokeSlashCommand(command, "/process-status " + key);
        } finally {
            System.setOut(originalOut);
        }

        String rendered = output.toString(StandardCharsets.UTF_8);
        assertTrue(rendered.contains("Activity: " + key), "/process-status <id> should inspect the activity");
        assertTrue(rendered.contains("opencode response"));
    }

    @Test
    void jobsRemoveAndClearManageCompletedActivityRecords() throws Exception {
        EmulatedPassthroughCommand command = configuredBusyCommand("draft while busy");
        setField(command, "activityRows", 4);
        invokeToolUseArg(command, "trackToolActivityStart", new PassthroughStreamParser.ToolUse(
                "task", "{\"agent\":\"claude\",\"description\":\"first retained job\"}"));
        invokeTwoStringBooleanArg(command, "trackToolActivityComplete", "task", "first done", false);
        invokeToolUseArg(command, "trackToolActivityStart", new PassthroughStreamParser.ToolUse(
                "task", "{\"agent\":\"codex\",\"description\":\"second retained job\"}"));
        invokeTwoStringBooleanArg(command, "trackToolActivityComplete", "task", "second done", false);

        ByteArrayOutputStream output = new ByteArrayOutputStream();
        PrintStream originalOut = System.out;
        try (PrintStream capture = new PrintStream(output, true, StandardCharsets.UTF_8)) {
            System.setOut(capture);
            invokeSlashCommand(command, "/jobs-remove agent:task-1");
            invokeSlashCommand(command, "/jobs-clear");
        } finally {
            System.setOut(originalOut);
        }

        String rendered = output.toString(StandardCharsets.UTF_8);
        assertTrue(rendered.contains("removed agent:task-1"));
        assertTrue(rendered.contains("cleared 1 completed"));
    }

    @Test
    void enforceAliasRoutesToManagedEnforcerControl() throws Exception {
        EmulatedPassthroughCommand command = configuredBusyCommand("draft while busy");
        TerminalRenderer plainRenderer = new TerminalRenderer(false);
        setField(command, "renderer", plainRenderer);

        ByteArrayOutputStream output = new ByteArrayOutputStream();
        PrintStream originalOut = System.out;
        try (PrintStream capture = new PrintStream(output, true, StandardCharsets.UTF_8)) {
            System.setOut(capture);
            invokeSlashCommand(command, "/enforce status");
        } finally {
            System.setOut(originalOut);
        }

        String rendered = output.toString(StandardCharsets.UTF_8);
        assertTrue(rendered.contains("No enforcer active") || rendered.contains("Enforcer"),
                "/enforce should route to Kompile enforcer handling, not child-agent forwarding");
        assertFalse(rendered.contains("→ opencode /enforce"));
    }

    @Test
    void registryBackedSubagentEnterShowsOwnerHierarchyAndOutput() throws Exception {
        EmulatedPassthroughCommand command = configuredBusyCommand("draft while busy");
        setField(command, "scrollBottom", 18);
        setField(command, "activityRows", 4);
        setField(command, "workingDir", tempDir.toString());
        java.nio.file.Path outputPath = tempDir.resolve("subagent-output.log");
        java.nio.file.Files.writeString(outputPath, "boot\nsubagent line\n", StandardCharsets.UTF_8);

        ai.kompile.cli.mcp.stdio.TaskRecord record = new ai.kompile.cli.mcp.stdio.TaskRecord();
        record.setTaskId("task-child-1");
        record.setParentTaskId("task-parent-1");
        record.addChildTaskId("task-grandchild-1");
        record.setTaskType("task");
        record.setAgentName("codex");
        record.setRoleName("reviewer");
        record.setSubtaskName("inspect delegation");
        record.setPromptSummary("look at process transparency");
        record.setOutputPath(outputPath.toString());
        record.markRunning(-1L);
        new ai.kompile.cli.mcp.stdio.TaskRegistry(tempDir).create(record);

        ByteArrayOutputStream output = new ByteArrayOutputStream();
        PrintStream originalOut = System.out;
        try (PrintStream capture = new PrintStream(output, true, StandardCharsets.UTF_8)) {
            System.setOut(capture);
            invokeStringArg(command, "handleActivitySlash", "enter task-child-1");
        } finally {
            System.setOut(originalOut);
        }

        String rendered = output.toString(StandardCharsets.UTF_8);
        assertTrue(rendered.contains("Activity: task-child-1"));
        assertTrue(rendered.contains("agent: codex"));
        assertTrue(rendered.contains("role: reviewer"));
        assertTrue(rendered.contains("parent: task-parent-1"));
        assertTrue(rendered.contains("children: 1"));
        assertTrue(rendered.contains("output: " + outputPath));
        assertTrue(rendered.contains("subagent line"));
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
    void recentPromptMenuAnswerBypassesBusyQueueRace() throws Exception {
        EmulatedPassthroughCommand command = configuredBusyCommand("draft while current runs");
        setField(command, "agentBusy", true);
        setField(command, "agentDecoder", new ai.kompile.cli.main.chat.tui.CodexDecoder());
        setField(command, "agentStdin", new ByteArrayOutputStream());
        setField(command, "lastAwaitingAt", System.currentTimeMillis());

        assertTrue(invokeBooleanStringArg(command, "isRecentPromptAnswer", "1"),
                "A recent numbered dialog answer should not be queued as a busy draft");
        assertTrue(invokeBooleanStringArg(command, "isRecentPromptAnswer", "yes"),
                "A recent confirmation answer should not be queued as a busy draft");
        assertFalse(invokeBooleanStringArg(command, "isRecentPromptAnswer", "next real prompt"),
                "Normal follow-up text while busy should still go through the draft queue");
    }

    @Test
    void escapeForwardsToActiveChildWithoutCancelingTurnOrMirror() throws Exception {
        EmulatedPassthroughCommand command = configuredBusyCommand("draft while current runs");
        setField(command, "agentBusy", true);
        setField(command, "agentAwaitingInput", true);
        setField(command, "agentDecoder", new ai.kompile.cli.main.chat.tui.OpenCodeDecoder());
        ByteArrayOutputStream agentInput = new ByteArrayOutputStream();
        setField(command, "agentStdin", agentInput);
        ((AtomicBoolean) getField(command, "tuiTurnSawContent")).set(true);
        setField(command, "autoMirrorForDialog", true);
        setField(command, "mirrorRender", true);

        LineReader reader = LineReaderBuilder.builder()
                .terminal(terminal)
                .build();
        invokeLineReaderArg(command, "enableManagedSlashCompletion", reader);
        Widget escapeWidget = widgetForSequence((LineReaderImpl) reader, "\033");

        assertTrue(escapeWidget.apply());

        assertArrayEquals(new byte[]{0x1B}, agentInput.toByteArray(),
                "Escape should be forwarded to the child agent");
        assertFalse(((AtomicBoolean) getField(command, "cancelSignal")).get(),
                "Escape must not mark the Kompile turn as cancelled");
        assertTrue((Boolean) getField(command, "agentAwaitingInput"),
                "Kompile should wait for the child to redraw/close the dialog");
        assertTrue((Boolean) getField(command, "mirrorRender"),
                "Mirror should stay active until decoder state says the dialog is gone");
        assertTrue((Boolean) getField(command, "autoMirrorForDialog"));
    }

    @Test
    void ctrlCForwardsInterruptToChildWithoutKompileCancel() throws Exception {
        EmulatedPassthroughCommand command = configuredBusyCommand("draft while current runs");
        setField(command, "agentBusy", true);
        ByteArrayOutputStream agentInput = new ByteArrayOutputStream();
        setField(command, "agentStdin", agentInput);

        ByteArrayOutputStream output = new ByteArrayOutputStream();
        PrintStream originalOut = System.out;
        try (PrintStream capture = new PrintStream(output, true, StandardCharsets.UTF_8)) {
            System.setOut(capture);
            invokeNoArg(command, "handleSigint");
        } finally {
            System.setOut(originalOut);
        }

        assertArrayEquals(new byte[]{0x03}, agentInput.toByteArray(),
                "Ctrl+C should be delivered to the child agent/subprocess");
        assertFalse(((AtomicBoolean) getField(command, "cancelSignal")).get(),
                "Ctrl+C should not force-cancel Kompile's managed process when stdin is available");
    }

    @Test
    void ctrlBForwardsToNativeChildBackgroundingWhenSupported() throws Exception {
        EmulatedPassthroughCommand command = configuredBusyCommand("draft while current runs");
        setField(command, "agentBusy", true);
        setField(command, "agentDecoder", new ai.kompile.cli.main.chat.tui.ClaudeCodeDecoder());
        ByteArrayOutputStream agentInput = new ByteArrayOutputStream();
        setField(command, "agentStdin", agentInput);
        ((AtomicBoolean) getField(command, "tuiTurnSawContent")).set(true);

        LineReader reader = LineReaderBuilder.builder()
                .terminal(terminal)
                .build();
        invokeLineReaderArg(command, "enableManagedSlashCompletion", reader);
        Widget ctrlB = widgetForSequence((LineReaderImpl) reader, KeyMap.ctrl('B'));

        assertTrue(ctrlB.apply());

        assertArrayEquals(new byte[]{0x02}, agentInput.toByteArray(),
                "Native backgrounding agents should receive Ctrl+B directly");
        assertFalse(((AtomicBoolean) getField(command, "backgroundSignal")).get(),
                "Kompile must not also background a child-native background request");
    }

    @Test
    void ctrlBUsesKompileBackgroundingWhenChildDoesNotOwnIt() throws Exception {
        EmulatedPassthroughCommand command = configuredBusyCommand("draft while current runs");
        setField(command, "agentBusy", true);
        setField(command, "agentDecoder", new ai.kompile.cli.main.chat.tui.OpenCodeDecoder());
        ByteArrayOutputStream agentInput = new ByteArrayOutputStream();
        setField(command, "agentStdin", agentInput);
        ((AtomicBoolean) getField(command, "tuiTurnSawContent")).set(true);

        LineReader reader = LineReaderBuilder.builder()
                .terminal(terminal)
                .build();
        invokeLineReaderArg(command, "enableManagedSlashCompletion", reader);
        Widget ctrlB = widgetForSequence((LineReaderImpl) reader, KeyMap.ctrl('B'));

        ByteArrayOutputStream output = new ByteArrayOutputStream();
        PrintStream originalOut = System.out;
        try (PrintStream capture = new PrintStream(output, true, StandardCharsets.UTF_8)) {
            System.setOut(capture);
            assertTrue(ctrlB.apply());
        } finally {
            System.setOut(originalOut);
        }

        assertArrayEquals(new byte[0], agentInput.toByteArray(),
                "Non-native backgrounding agents should not receive Ctrl+B");
        assertTrue(((AtomicBoolean) getField(command, "backgroundSignal")).get(),
                "Kompile should own Ctrl+B for managed backgrounding when the child does not");
        assertFalse(((AtomicBoolean) getField(command, "cancelSignal")).get());
    }

    @Test
    void idlePersistentAgentDoesNotRenderAsActiveStatusBarWork() throws Exception {
        ai.kompile.cli.main.chat.tui.StatusBar statusBar = new ai.kompile.cli.main.chat.tui.StatusBar(
                new BackgroundTaskManager(),
                new ai.kompile.cli.main.chat.tools.BackgroundProcessManager("status-idle-agent"),
                new MessageQueue("status-idle-agent-" + System.nanoTime()),
                new TerminalRenderer(true));
        setObjectField(statusBar, "terminalHeight", 30);
        setObjectField(statusBar, "terminalWidth", 100);
        setObjectField(statusBar, "enabled", false);
        setObjectField(statusBar, "activeAgent", "claude");
        ai.kompile.cli.main.chat.tui.StatusBar.SubagentEntry entry =
                statusBar.registerSubagent("agent-claude", "agent", "managed subprocess");
        entry.setStatus("idle");

        Method method = ai.kompile.cli.main.chat.tui.StatusBar.class.getDeclaredMethod("buildStatusContent");
        method.setAccessible(true);
        String rendered = (String) method.invoke(statusBar);

        assertTrue(rendered.contains("claude"), "The passive active-agent label should remain visible");
        assertFalse(rendered.contains("managed subprocess"), "Idle persistent agents should not render as active work");
        assertFalse(rendered.contains("idle"), "Idle persistent agents should not keep an animated idle entry visible");
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
    void mirrorRepaintDoesNotBounceUnchangedActivePromptCursor() throws Exception {
        EmulatedPassthroughCommand command = configuredIdleCommand();
        LineReader reader = LineReaderBuilder.builder()
                .terminal(terminal)
                .build();
        ((LineReaderImpl) reader).getBuffer().write("queued followup");
        setField(command, "activeLineReader", reader);

        ByteArrayOutputStream output = new ByteArrayOutputStream();
        PrintStream originalOut = System.out;
        try (PrintStream capture = new PrintStream(output, true, StandardCharsets.UTF_8)) {
            System.setOut(capture);
            invokeLineReaderArg(command, "drawIdlePromptLine", reader);
            output.reset();
            terminalOutput.reset();

            ai.kompile.cli.main.chat.tui.VirtualTerminal vt =
                    new ai.kompile.cli.main.chat.tui.VirtualTerminal(30, 100);
            vt.feed("\033[1;1HCodex is drawing under Kompile");
            invokeMirrorVt(command, vt);
        } finally {
            System.setOut(originalOut);
        }

        String mirrored = terminalOutput.toString(StandardCharsets.UTF_8);
        assertTrue(mirrored.startsWith("\0337"), "Active prompt mirror blits should save the host cursor first");
        assertTrue(mirrored.contains("\033[?25l"), "Mirror blit should hide the cursor while painting child rows");
        assertTrue(mirrored.endsWith("\0338\033[?25h"), "Mirror blit should restore the prompt cursor and leave it visible");
        String promptLayer = output.toString(StandardCharsets.UTF_8);
        assertFalse(promptLayer.contains("\033[24;"),
                "Unchanged active prompt should not repaint or jump the cursor after every mirrored frame");
        assertFalse(promptLayer.contains("\033[?25h"),
                "Raw mirror save/restore should handle cursor visibility when the prompt is unchanged");
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
            AgentLaunchDefaults.Selection selection = AgentLaunchDefaults.resolve(provider, null, null, null);
            assertEquals(provider, built.get(0), provider + " should launch the interactive provider binary");
            assertEquals(AgentLaunchDefaults.commandArguments(
                            provider, selection.model(), selection.thinking(), AgentLaunchDefaults.LaunchMode.INTERACTIVE),
                    built.subList(1, built.size()),
                    provider + " should apply the configured interactive model and reasoning defaults");
            assertFalse(built.contains("--continue"), provider + " must not continue an active background session");
            assertFalse(built.contains("resume"), provider + " must not resume an active background session");
            assertFalse(built.contains("--resume"), provider + " must not resume an active background session");
            assertFalse(built.contains("--session"), provider + " must not target an active background session");
            assertFalse(built.contains("provider-session"), provider + " must not reuse an active background session id");
            assertFalse(built.contains("--fork"), provider + " must not rely on provider-specific fork flags");
            assertFalse(built.contains("next draft"), provider + " should receive the queued prompt over stdin, not argv");
        }
    }

    @Test
    void managedResumeUsesResolvedNativeSessionForEveryProvider() throws Exception {
        EmulatedPassthroughCommand command = configuredIdleCommand();
        setField(command, "resumeSessionId", "kompile-session");
        setField(command, "agentSessionId", "native-session");

        for (String provider : List.of("claude", "codex", "gemini", "qwen", "opencode", "pi")) {
            setField(command, "agent", provider);
            List<String> built = invokeBuildCommand(command, provider, "continue");
            List<String> resumeArgs = AgentLaunchDefaults.resumeArguments(provider, "native-session");
            assertEquals(resumeArgs,
                    built.subList(built.size() - resumeArgs.size(), built.size()),
                    provider + " should attach the managed child to the native session");
            assertFalse(built.contains("kompile-session"),
                    provider + " must not pass Kompile's synthetic session id to the provider");
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

    private static void setObjectField(Object target, String name, Object value) throws Exception {
        Field field = target.getClass().getDeclaredField(name);
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

    private static void invokeBooleanArg(Object target, String name, boolean value) throws Exception {
        Method method = EmulatedPassthroughCommand.class.getDeclaredMethod(name, boolean.class);
        method.setAccessible(true);
        method.invoke(target, value);
    }

    private static TerminalQueryStripper newTerminalQueryStripper() {
        return new TerminalQueryStripper();
    }

    private static StripResult stripTerminalChunk(TerminalQueryStripper stripper, String chunk) {
        byte[] bytes = chunk.getBytes(StandardCharsets.UTF_8);
        TerminalQueryStripResult result = stripper.strip(bytes, 0, bytes.length);
        return new StripResult(result.displayBytes(), result.queries());
    }

    private record StripResult(byte[] displayBytes, String queries) {}

    private static void invokeStringArg(Object target, String name, String value) throws Exception {
        Method method = EmulatedPassthroughCommand.class.getDeclaredMethod(name, String.class);
        method.setAccessible(true);
        method.invoke(target, value);
    }

    private static String invokeStringArgReturn(Object target, String name, String value) throws Exception {
        Method method = EmulatedPassthroughCommand.class.getDeclaredMethod(name, String.class);
        method.setAccessible(true);
        return (String) method.invoke(target, value);
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

    private static void invokeMirrorVt(Object target,
                                       ai.kompile.cli.main.chat.tui.VirtualTerminal vt) throws Exception {
        Method method = EmulatedPassthroughCommand.class.getDeclaredMethod("mirrorVtToScrollRegion",
                ai.kompile.cli.main.chat.tui.VirtualTerminal.class);
        method.setAccessible(true);
        method.invoke(target, vt);
    }

    private static void invokeUpdateLiveDecoderScrollbackBlock(Object target,
                                                               String decodedText,
                                                               boolean finalSnapshot) throws Exception {
        Method method = EmulatedPassthroughCommand.class.getDeclaredMethod("updateLiveDecoderScrollbackBlock",
                String.class, boolean.class);
        method.setAccessible(true);
        method.invoke(target, decodedText, finalSnapshot);
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

    private static Widget widgetForSequence(LineReaderImpl impl, String sequence) {
        Binding binding = impl.getKeyMaps().get(LineReader.EMACS).getBound(sequence);
        assertInstanceOf(Reference.class, binding);
        Widget widget = impl.getWidgets().get(((Reference) binding).name());
        assertNotNull(widget);
        return widget;
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

    private static String invokeSlashCommand(EmulatedPassthroughCommand target, String input) throws Exception {
        Method method = EmulatedPassthroughCommand.class.getDeclaredMethod("handleSlashCommand",
                String.class, LineReader.class, ChatHistory.class, ChatSessionMetrics.class);
        method.setAccessible(true);
        return (String) method.invoke(target, input, null,
                new ChatHistory("managed-slash-test-" + System.nanoTime()),
                new ChatSessionMetrics("managed-slash-test"));
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

    // ── BUG 9: status-line must show "responding" not "idle" mid-turn ────────────

    @Test
    void statusLineShowsRespondingNotIdleWhileAgentBusy() throws Exception {
        EmulatedPassthroughCommand command = configuredIdleCommand();
        // Simulate the flicker scenario: agentBusy=true but currentStatus was set to "idle"
        // because decoder.isResponding(vt) happened to be false between two decoder frames.
        setField(command, "agentBusy", true);
        setField(command, "currentStatus", "idle");

        ByteArrayOutputStream output = new ByteArrayOutputStream();
        PrintStream originalOut = System.out;
        try (PrintStream capture = new PrintStream(output, true, StandardCharsets.UTF_8)) {
            System.setOut(capture);
            invokeNoArg(command, "renderStatusLineLocked");
        } finally {
            System.setOut(originalOut);
        }

        String rendered = output.toString(StandardCharsets.UTF_8);
        assertFalse(rendered.contains("idle"),
                "Status line must not display 'idle' while agentBusy=true (BUG 9 regression)");
        assertTrue(rendered.contains("responding"),
                "Status line must show 'responding' while agentBusy=true even if currentStatus was 'idle'");
    }

    @Test
    void statusLinePreservesNonIdleStatusWhileAgentBusy() throws Exception {
        EmulatedPassthroughCommand command = configuredIdleCommand();
        setField(command, "agentBusy", true);
        setField(command, "currentStatus", "thinking");

        ByteArrayOutputStream output = new ByteArrayOutputStream();
        PrintStream originalOut = System.out;
        try (PrintStream capture = new PrintStream(output, true, StandardCharsets.UTF_8)) {
            System.setOut(capture);
            invokeNoArg(command, "renderStatusLineLocked");
        } finally {
            System.setOut(originalOut);
        }

        String rendered = output.toString(StandardCharsets.UTF_8);
        assertTrue(rendered.contains("thinking"),
                "Non-idle currentStatus must pass through unchanged while agentBusy=true");
    }

    // ── BUG 3: renderableDecodedDelta fallback must not re-emit full history ─────

    @Test
    void renderableDecodedDeltaReturnEmptyStringForDivergedContentToAvoidDuplication() throws Exception {
        EmulatedPassthroughCommand command = configuredIdleCommand();
        // Simulate mid-turn state: some content already accumulated in tuiFullText.
        setField(command, "tuiFullText", new StringBuilder("Part 1\nPart 2\n"));

        // Normal grow case: current appends cleanly to rendered — must still work.
        String grow = invokeRenderableDecodedDelta(command, "Part 1\n", "Part 1\nPart 2\n", true);
        assertEquals("Part 2\n", grow, "Normal append delta must be returned correctly");

        // Diverged case: current neither starts-with nor is contained by rendered.
        // Before the fix this returned the full 'current', causing duplicate accumulation.
        String diverged = invokeRenderableDecodedDelta(command,
                "Part 1\nPart 2\n",
                "INTRO\nPart 1\nPart 2\nPart 3\n",
                true);
        assertEquals("", diverged,
                "Diverged content must return '' to avoid re-emitting already-accumulated history (BUG 3 regression)");
    }

    @Test
    void renderableDecodedDeltaStillEmitsCurrentWhenPreviousIsBlank() throws Exception {
        EmulatedPassthroughCommand command = configuredIdleCommand();
        // First frame: nothing rendered yet; full current should be emitted.
        String first = invokeRenderableDecodedDelta(command, "", "Hello world\n", true);
        assertEquals("Hello world\n", first,
                "First frame with blank previous must still return full current content");
    }

    private static String invokeRenderableDecodedDelta(Object target,
                                                       String rendered,
                                                       String current,
                                                       boolean finalChunk) throws Exception {
        Method method = EmulatedPassthroughCommand.class.getDeclaredMethod(
                "renderableDecodedDelta", String.class, String.class, boolean.class);
        method.setAccessible(true);
        return (String) method.invoke(target, rendered, current, finalChunk);
    }

    // ── Slash-completion mid-buffer corruption fix ────────────────────────────
    // Regression: typing "/" at a non-zero buffer position (e.g. "a/") used to
    // corrupt the input line.  Root cause: refreshManagedSlashCompletion called
    // impl.callWidget(REDISPLAY) re-entrantly from inside SELF_INSERT dispatch.

    /**
     * When the buffer does NOT start with "/" the completion panel must stay
     * empty — the guard in buildSlashCompletionLines checks upToCursor.startsWith("/").
     * This is the first half of the "a/" bug: the panel guard is correct, but the
     * re-entrant REDISPLAY call after the guard is what caused corruption.
     */
    @Test
    void midBufferSlashLeavesCompletionPanelEmpty() throws Exception {
        EmulatedPassthroughCommand command = configuredIdleCommand();
        setField(command, "scrollBottom", 18);
        setField(command, "activityRows", 3);

        // Simulate: user typed "a" then "/"; buffer = "a/", cursor at 2.
        // updateSlashCompletionPanel must detect that "a/" does not start with "/"
        // and leave slashCompletionLines empty.
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        PrintStream originalOut = System.out;
        try (PrintStream capture = new PrintStream(output, true, StandardCharsets.UTF_8)) {
            System.setOut(capture);
            invokeStringIntArg(command, "updateSlashCompletionPanel", "a/", 2);
        } finally {
            System.setOut(originalOut);
        }

        @SuppressWarnings("unchecked")
        List<String> lines = (List<String>) getField(command, "slashCompletionLines");
        assertTrue(lines == null || lines.isEmpty(),
                "slashCompletionLines must be empty when buffer does not start with '/'");
        // No slash command suggestions should have been rendered.
        assertFalse(output.toString(StandardCharsets.UTF_8).contains("/help"),
                "Slash suggestions must not appear when '/' is not the first buffer character");
    }

    /**
     * A slash-only buffer "/" correctly opens the completion panel (sanity check
     * that the guard above doesn't over-suppress legitimate "/" completions).
     */
    @Test
    void leadingSlashOpensCompletionPanel() throws Exception {
        EmulatedPassthroughCommand command = configuredIdleCommand();
        setField(command, "scrollBottom", 18);
        setField(command, "activityRows", 3);

        ByteArrayOutputStream output = new ByteArrayOutputStream();
        PrintStream originalOut = System.out;
        try (PrintStream capture = new PrintStream(output, true, StandardCharsets.UTF_8)) {
            System.setOut(capture);
            invokeStringIntArg(command, "updateSlashCompletionPanel", "/", 1);
        } finally {
            System.setOut(originalOut);
        }

        @SuppressWarnings("unchecked")
        List<String> lines = (List<String>) getField(command, "slashCompletionLines");
        assertNotNull(lines);
        assertFalse(lines.isEmpty(), "slashCompletionLines must be non-empty for a leading '/' buffer");
        assertTrue(output.toString(StandardCharsets.UTF_8).contains("/help"),
                "Leading '/' must show slash command suggestions");
    }

    /**
     * The wrapped SELF_INSERT widget must NOT call callWidget(REDISPLAY) re-entrantly.
     * <p>
     * Before the fix, refreshManagedSlashCompletion() called
     * impl.callWidget(LineReader.REDISPLAY) after updating the panel. That call
     * happened INSIDE the SELF_INSERT dispatch, corrupting JLine's display state
     * mid-dispatch. The fix removes the re-entrant REDISPLAY call; JLine's own
     * post-dispatch redisplay handles input-line refresh.
     * <p>
     * We verify the fix by replacing the REDISPLAY widget with a counting stub,
     * invoking the wrapped SELF_INSERT, and asserting that the REDISPLAY count
     * remains zero (the panel update via redrawActivityPanelOnly goes to System.out
     * directly, not through callWidget).
     */
    @Test
    void selfInsertWrappedWidgetDoesNotIssueReentrantRedisplay() throws Exception {
        EmulatedPassthroughCommand command = configuredIdleCommand();
        setField(command, "scrollBottom", 18);
        setField(command, "activityRows", 3);

        LineReader reader = LineReaderBuilder.builder()
                .terminal(terminal)
                .build();
        invokeLineReaderArg(command, "enableManagedSlashCompletion", reader);

        LineReaderImpl impl = (LineReaderImpl) reader;

        // Replace REDISPLAY with a counting stub so we can detect re-entrant calls.
        AtomicInteger redisplayCount = new AtomicInteger(0);
        impl.getWidgets().put(LineReader.REDISPLAY, () -> {
            redisplayCount.incrementAndGet();
            return true;
        });

        // Invoke the wrapped SELF_INSERT widget (the one wrapSlashRefreshWidget installed).
        Widget selfInsert = impl.getWidgets().get(LineReader.SELF_INSERT);
        assertNotNull(selfInsert, "SELF_INSERT widget must exist after enableManagedSlashCompletion");

        ByteArrayOutputStream output = new ByteArrayOutputStream();
        PrintStream originalOut = System.out;
        try (PrintStream capture = new PrintStream(output, true, StandardCharsets.UTF_8)) {
            System.setOut(capture);
            // Simulate typing "/" into an otherwise empty buffer.
            // The wrapped widget calls original.apply() then refreshManagedSlashCompletion.
            // After the fix refreshManagedSlashCompletion must NOT call callWidget(REDISPLAY).
            selfInsert.apply();
        } finally {
            System.setOut(originalOut);
        }

        assertEquals(0, redisplayCount.get(),
                "wrapSlashRefreshWidget must not call callWidget(REDISPLAY) re-entrantly from inside widget dispatch");
    }

    /**
     * Typing a letter AFTER a "/" (mid-buffer slash) must not leave a stale
     * completion panel open, and must not trigger a re-entrant REDISPLAY.
     * Simulates: type "/", panel opens; type "a" to make buffer "/a"; type
     * another "/" to produce "/a/" — each step: panel empty and no REDISPLAY.
     */
    @Test
    void midBufferSlashAfterLetterNeverTriggersReentrantRedisplay() throws Exception {
        EmulatedPassthroughCommand command = configuredIdleCommand();
        setField(command, "scrollBottom", 18);
        setField(command, "activityRows", 3);

        LineReader reader = LineReaderBuilder.builder()
                .terminal(terminal)
                .build();
        invokeLineReaderArg(command, "enableManagedSlashCompletion", reader);

        LineReaderImpl impl = (LineReaderImpl) reader;

        AtomicInteger redisplayCount = new AtomicInteger(0);
        impl.getWidgets().put(LineReader.REDISPLAY, () -> {
            redisplayCount.incrementAndGet();
            return true;
        });

        Widget selfInsert = impl.getWidgets().get(LineReader.SELF_INSERT);
        assertNotNull(selfInsert);

        ByteArrayOutputStream output = new ByteArrayOutputStream();
        PrintStream originalOut = System.out;
        try (PrintStream capture = new PrintStream(output, true, StandardCharsets.UTF_8)) {
            System.setOut(capture);

            // Step 1: type "a" — buffer = "a", no completions, no re-entrant REDISPLAY.
            redisplayCount.set(0);
            selfInsert.apply();
            assertEquals(0, redisplayCount.get(),
                    "Typing a plain letter must not trigger re-entrant REDISPLAY");

            // Step 2: type "/" — buffer = "a/", upToCursor = "a/", no completions
            //   (because upToCursor does not start with "/").
            redisplayCount.set(0);
            selfInsert.apply();
            assertEquals(0, redisplayCount.get(),
                    "Typing '/' after a letter (mid-buffer slash) must not trigger re-entrant REDISPLAY");

            @SuppressWarnings("unchecked")
            List<String> lines = (List<String>) getField(command, "slashCompletionLines");
            assertTrue(lines == null || lines.isEmpty(),
                    "Panel must remain empty when '/' is not the first character");

        } finally {
            System.setOut(originalOut);
        }
    }
}
