package ai.kompile.cli.main.chat;

import ai.kompile.cli.main.chat.render.TerminalRenderer;
import ai.kompile.cli.main.chat.tools.BackgroundProcessManager;
import ai.kompile.cli.main.chat.tui.KompileTui;
import ai.kompile.cli.main.chat.tui.VirtualTerminal;
import org.jline.reader.LineReader;
import org.jline.reader.LineReaderBuilder;
import org.jline.terminal.Attributes;
import org.jline.terminal.Size;
import org.jline.terminal.impl.LineDisciplineTerminal;
import org.jline.widget.AutosuggestionWidgets;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.*;

/** Exercises the screen across submission, not just the returned JLine buffer. */
class ChatInputSubmissionTtyTest {
    private static final int WIDTH = 72;
    private static final int HEIGHT = 24;
    private static final String PROMPT = "kompile > ";

    static List<String> submittedDrafts() {
        return List.of(
                "sent single-line message",
                "sent first row\nsent second row\nsent final row",
                "sent wrapped message " + "wrapping text ".repeat(14),
                "sent large paste " + "paste text ".repeat(130));
    }

    @ParameterizedTest(name = "submitted draft {index} clears before dispatch and the next prompt")
    @MethodSource("submittedDrafts")
    void submittedInputDoesNotRemainInTheEditor(String draft) throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        LineDisciplineTerminal terminal = new LineDisciplineTerminal(
                "submission-test", "xterm", output, StandardCharsets.UTF_8);
        terminal.setSize(new Size(WIDTH, HEIGHT));
        Attributes attributes = terminal.getAttributes();
        attributes.setLocalFlag(Attributes.LocalFlag.ECHO, false);
        attributes.setLocalFlag(Attributes.LocalFlag.ICANON, false);
        attributes.setInputFlag(Attributes.InputFlag.ICRNL, false);
        terminal.setAttributes(attributes);
        LineReader reader = LineReaderBuilder.builder().terminal(terminal).build();
        new AutosuggestionWidgets(reader).enable();
        BackgroundProcessManager processes = new BackgroundProcessManager("input-submission-test");
        MessageQueue queue = new MessageQueue("input-submission-" + UUID.randomUUID());
        KompileTui tui = new KompileTui(
                new BackgroundTaskManager(), processes, queue, new TerminalRenderer(true));
        ExecutorService inputThread = Executors.newSingleThreadExecutor();
        try {
            tui.attachLineReader(reader);
            ChatCompleter.setTerminalRef(reader, terminal);
            ChatCompleter.enableAutoTrigger(reader);
            tui.start(terminal);
            tui.recordInScrollRegion("retained transcript sentinel");
            await(output, () -> screen(output).screenDump().contains("retained transcript sentinel"));

            Future<String> submitted = inputThread.submit(() -> reader.readLine(PROMPT));
            await(output, () -> reader.isReading()
                    && screen(output).getRow(tui.inputTop() - 1).contains(PROMPT.stripTrailing()));
            // Bracketed paste covers embedded newlines, soft wrap, and compact-paste expansion.
            type(terminal, "\033[200~" + draft + "\033[201~");
            await(output, () -> screen(output).screenDump().contains(
                    draft.length() >= ChatCompleter.LARGE_PASTE_THRESHOLD ? "[Pasted" : "sent "));
            type(terminal, "\r");
            assertEquals(draft, submitted.get(5, TimeUnit.SECONDS));
            tui.clearSubmittedInput();

            // This is the dispatch gap: no next readLine and no model output yet.
            assertEmptyInputPane(tui, screen(output));
            assertTrue(screen(output).screenDump().contains("retained transcript sentinel"));

            // Mirror ChatRepl's ordinary-message path. Retain the user message once,
            // then let asynchronous output redraw it in the transcript, not the editor.
            tui.recordInScrollRegion("kompile> " + draft);
            tui.recordInScrollRegion("assistant response marker");
            await(output, () -> screen(output).screenDump().contains("assistant response marker"));
            assertEmptyInputPane(tui, screen(output));
            assertEquals(1L, tui.getContentViewLines().stream()
                    .filter(line -> line.startsWith("kompile> ")).count());
            assertTrue(String.join("\n", tui.getContentViewLines()).contains(draft),
                    "the retained message must include the expanded paste exactly once");

            tui.reestablishScrollRegion();
            Future<String> next = inputThread.submit(() -> reader.readLine(PROMPT));
            await(output, () -> reader.isReading()
                    && screen(output).getRow(tui.inputTop() - 1).contains(PROMPT.stripTrailing()));
            assertEquals("", reader.getBuffer().toString());
            VirtualTerminal freshPrompt = screen(output);
            assertEquals(PROMPT.strip(), freshPrompt.getRow(tui.inputTop() - 1).strip(),
                    freshPrompt::screenDump);
            for (int row = tui.inputTop(); row < tui.scrollBottom(); row++) {
                assertTrue(freshPrompt.getRow(row).isBlank(), freshPrompt::screenDump);
            }

            String nextDraft = "fresh draft";
            type(terminal, nextDraft);
            await(output, () -> nextDraft.equals(reader.getBuffer().toString())
                    && screen(output).getRow(tui.inputTop() - 1).contains(nextDraft));
            for (int i = 0; i < 5; i++) tui.recordInScrollRegion("async response " + i);
            await(output, () -> screen(output).screenDump().contains("async response 4"));
            VirtualTerminal editing = screen(output);
            assertEquals(PROMPT + nextDraft, editing.getRow(tui.inputTop() - 1).strip(),
                    editing::screenDump);
            type(terminal, "\r");
            assertEquals(nextDraft, next.get(5, TimeUnit.SECONDS),
                    "submission cleanup must never erase or prepend to the next draft");
            tui.clearSubmittedInput();
            assertEmptyInputPane(tui, screen(output));
        } finally {
            ChatCompleter.clearTerminalRef(reader);
            tui.detachLineReader();
            inputThread.shutdownNow();
            inputThread.awaitTermination(2, TimeUnit.SECONDS);
            tui.stop();
            processes.close();
            terminal.close();
            queue.clear();
        }
    }

    private static void type(LineDisciplineTerminal terminal, String keys) throws Exception {
        byte[] bytes = keys.getBytes(StandardCharsets.UTF_8);
        terminal.processInputBytes(bytes, 0, bytes.length);
    }

    private static VirtualTerminal screen(ByteArrayOutputStream output) {
        VirtualTerminal frame = new VirtualTerminal(HEIGHT, WIDTH);
        // Replay all bytes from startup, so old rows cannot disappear by resetting the fixture.
        frame.feed(output.toString(StandardCharsets.UTF_8));
        return frame;
    }

    private static void assertEmptyInputPane(KompileTui tui, VirtualTerminal frame) {
        for (int row = tui.inputTop() - 1; row < tui.scrollBottom(); row++) {
            assertTrue(frame.getRow(row).isBlank(),
                    "submitted text must be cleared from input row " + row + "\n" + frame.screenDump());
        }
    }

    private static void await(ByteArrayOutputStream output, BooleanSupplier condition)
            throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
        while (!condition.getAsBoolean() && System.nanoTime() < deadline) Thread.sleep(10);
        assertTrue(condition.getAsBoolean(),
                () -> "timed out waiting for the JLine/TUI frame\n" + screen(output).screenDump());
    }
}
