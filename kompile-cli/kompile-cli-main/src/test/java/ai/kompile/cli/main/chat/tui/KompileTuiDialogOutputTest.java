package ai.kompile.cli.main.chat.tui;

import ai.kompile.cli.main.chat.BackgroundTaskManager;
import ai.kompile.cli.main.chat.MessageQueue;
import ai.kompile.cli.main.chat.render.TerminalRenderer;
import ai.kompile.cli.main.chat.tools.BackgroundProcessManager;
import org.jline.reader.EndOfFileException;
import org.jline.reader.LineReader;
import org.jline.reader.LineReaderBuilder;
import org.jline.reader.UserInterruptException;
import org.jline.terminal.Size;
import org.jline.terminal.impl.LineDisciplineTerminal;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.junit.jupiter.api.parallel.Resources;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

@ResourceLock(Resources.SYSTEM_OUT)
@ResourceLock(Resources.SYSTEM_ERR)
class KompileTuiDialogOutputTest {
    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void loginLinksRemainClickableAndCopyableAcrossWrappedRows(boolean modal) throws Exception {
        try (Fixture f = new Fixture()) {
            String url = "https://login.example/authorize?state=" + "a".repeat(95) + "&redirect_uri=http%3A%2F%2Flocalhost";
            f.tui.runCommandOutput(() -> {
                if (modal) f.tui.showTemporaryWindow("Provider authentication", List.of());
                System.out.println(url);
                return true;
            });
            String openLink = "\033]8;;" + url + "\033\\";
            List<String> visible = f.tui.getVisibleContentLines();
            List<String> fragments = visible.stream().filter(line -> line.contains(openLink)).toList();
            assertEquals(2, fragments.size(), "each wrapped fragment must target the complete login URL");
            assertTrue(fragments.stream().allMatch(line -> line.contains("\033]8;;\033\\")),
                    "hyperlinks must end before subsequent UI output");

            int start = modal ? 2 : 0;
            int firstRow = f.tui.scrollTop() - 1 + visible.indexOf(fragments.get(0));
            int lastCell = start + url.length() - 1;
            int lastRow = firstRow + lastCell / 99;
            assertTrue(f.tui.beginTranscriptSelection(start, firstRow));
            assertTrue(f.tui.dragTranscriptSelection(lastCell % 99, lastRow));
            assertTrue(f.tui.finishTranscriptSelection(lastCell % 99, lastRow));
            assertEquals(url, f.tui.getSelectedTranscriptText(), "copy must contain no wrapping, borders, or escape codes");
            assertEquals(2, f.tui.getVisibleContentLines().stream().filter(line -> line.contains(openLink)).count(),
                    "selection highlighting must preserve native links");
            if (modal) {
                f.tui.updateTemporaryWindow("Credentials", List.of("next page"));
                assertFalse(f.tui.hasTranscriptSelection());
                assertTrue(f.tui.beginTranscriptSelection(2, f.tui.scrollTop()));
                assertTrue(f.tui.finishTranscriptSelection(5, f.tui.scrollTop()));
                f.tui.closeTemporaryWindow();
                assertFalse(f.tui.hasTranscriptSelection());
            }
        }
    }

    @Test
    void authSelectionScrollsInDocumentDirectionAtBothEdges() throws Exception {
        try (Fixture f = new Fixture()) {
            f.tui.showTemporaryWindow("Credentials", java.util.stream.IntStream.range(1, 61)
                    .mapToObj(i -> "credential " + i).toList());
            int top = f.tui.scrollTop() - 1;
            int bottom = top + f.tui.getVisibleContentLines().size() - 1;
            assertTrue(f.tui.beginTranscriptSelection(2, top + 1));
            assertTrue(f.tui.dragTranscriptSelection(13, bottom));
            assertTrue(f.tui.finishTranscriptSelection(13, bottom));
            assertEquals(1, f.tui.getContentScrollOffset());
            assertTrue(f.tui.getSelectedTranscriptText().startsWith("credential 1"));
            assertFalse(f.tui.getSelectedTranscriptText().contains("PgUp"));

            assertTrue(f.tui.beginTranscriptSelection(13, bottom));
            assertTrue(f.tui.dragTranscriptSelection(2, top));
            assertTrue(f.tui.finishTranscriptSelection(2, top));
            assertEquals(0, f.tui.getContentScrollOffset());
            assertTrue(f.tui.getSelectedTranscriptText().startsWith("credential 1"));
            assertFalse(f.tui.getSelectedTranscriptText().contains("PgUp"));
        }
    }

    @ParameterizedTest
    @CsvSource({
            "Provider authentication, success", "Provider authentication, cancel",
            "Provider authentication, eof", "Provider authentication, error",
            "Resource configuration, success", "Resource configuration, cancel",
            "Resource configuration, eof", "Resource configuration, error",
            "Resume recent sessions, success", "Resume recent sessions, cancel",
            "Resume recent sessions, eof", "Resume recent sessions, error"
    })
    void dialogOutputDisappearsOnEveryExit(String title, String exit) throws Exception {
        try (Fixture f = new Fixture()) {
            PrintStream originalOut = System.out;
            PrintStream originalErr = System.err;
            f.tui.recordInScrollRegion("retained conversation");
            RuntimeException failure = switch (exit) {
                case "cancel" -> new UserInterruptException("");
                case "eof" -> new EndOfFileException();
                case "error" -> new IllegalStateException("dialog failed");
                default -> null;
            };
            java.util.function.BooleanSupplier dialog = () -> {
                f.tui.showTemporaryWindow(title, List.of("Choose an option"));
                try {
                    System.out.println("Authentication for this session:");
                    System.out.printf("  %2d  %s%n", 1, "test credential");
                    System.err.print("TRANSIENT WARNING");
                    System.out.print("TRANSIENT PROMPT");
                    System.out.flush();
                    assertTrue(f.tui.getContentViewLines().stream()
                            .anyMatch(line -> line.contains("TRANSIENT PROMPT")));
                    // Explicit chat/tool sinks remain durable even while a dialog owns stdout.
                    f.tui.recordInScrollRegion("background conversation");
                    f.tui.upsertMainTranscriptBlock("tool", "background tool result");
                    if (failure != null) throw failure;
                    return true;
                } finally {
                    f.tui.closeTemporaryWindow();
                }
            };
            if (failure == null) assertTrue(f.tui.runCommandOutput(dialog));
            else assertSame(failure, assertThrows(failure.getClass(),
                    () -> f.tui.runCommandOutput(dialog)));

            assertSame(originalOut, System.out);
            assertSame(originalErr, System.err);
            assertFalse(f.tui.isTemporaryWindowActive());
            assertEquals(List.of("retained conversation", "background conversation",
                    "background tool result"), f.tui.getContentViewLines());
            VirtualTerminal screen = new VirtualTerminal(30, 100);
            screen.feed(f.output.toString(StandardCharsets.UTF_8));
            assertFalse(screen.screenDump().contains("Authentication for this session"), screen::screenDump);
            assertFalse(screen.screenDump().contains("TRANSIENT"), screen::screenDump);
            assertFalse(screen.screenDump().contains("Choose an option"), screen::screenDump);
            assertTrue(screen.screenDump().contains("background tool result"), screen::screenDump);

            f.tui.runCommandOutput(() -> {
                System.out.println("next command result");
                return true;
            });
            assertEquals("next command result", f.tui.getContentViewLines().get(3));
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void pageChangesDiscardPartialOutputWithoutLosingChatOrActivityView(boolean activity) throws Exception {
        try (Fixture f = new Fixture()) {
            f.tui.recordInScrollRegion("chat before dialog");
            if (activity) f.tui.showActivityView("worker", "Worker", "worker view");
            String previousView = f.tui.getContentViewKey();
            List<String> previousLines = f.tui.getContentViewLines();
            f.tui.runCommandOutput(() -> {
                System.out.print("chat partial before dialog");
                f.tui.showTemporaryWindow("Provider authentication", List.of("First page"));
                System.out.print("OLD OUT PROMPT");
                System.err.print("OLD ERR PROMPT");
                f.tui.updateTemporaryWindow("Provider and model", List.of("Second page"));
                System.out.flush();
                System.err.flush();
                System.out.print("NEW OUT PROMPT");
                System.err.print("NEW ERR PROMPT");
                assertFalse(String.join("\n", f.tui.getContentViewLines()).contains("OLD"));
                assertTrue(String.join("\n", f.tui.getContentViewLines()).contains("NEW OUT PROMPT"));
                assertTrue(String.join("\n", f.tui.getContentViewLines()).contains("NEW ERR PROMPT"));
                f.tui.closeTemporaryWindow();
                System.out.flush();
                System.err.flush();
                f.tui.showTemporaryWindow("Resource configuration", List.of("Reopened page"));
                System.out.print("PROGRESS\r");
                f.tui.updateTemporaryWindow("Resume recent sessions", List.of("Final page"));
                System.out.println("FINAL PAGE");
                assertFalse(String.join("\n", f.tui.getContentViewLines()).contains("PROGRESS"));
                f.tui.closeTemporaryWindow();
                // Exercise direct byte writes and the transaction's final flush after close.
                f.tui.showTemporaryWindow("Provider authentication", List.of("Byte prompt"));
                System.out.write('X');
                f.tui.closeTemporaryWindow();
                return true;
            });
            assertEquals(previousView, f.tui.getContentViewKey());
            if (activity) assertEquals(previousLines, f.tui.getContentViewLines());
            f.tui.showMainView();
            assertEquals(List.of("chat before dialog", "chat partial before dialog"),
                    f.tui.getContentViewLines());
        }
    }

    @Test
    void nestedCommandTransactionsDoNotClearTheCurrentDialogPage() throws Exception {
        try (Fixture f = new Fixture()) {
            f.tui.runCommandOutput(() -> {
                f.tui.showTemporaryWindow("Provider authentication", List.of("Authentication"));
                System.out.println("OUTER INSTRUCTIONS");
                f.tui.runCommandOutput(() -> {
                    System.err.println("INNER INSTRUCTIONS");
                    return true;
                });
                System.out.println("CONTINUED INSTRUCTIONS");
                String page = String.join("\n", f.tui.getContentViewLines());
                assertTrue(page.contains("OUTER INSTRUCTIONS"));
                assertTrue(page.contains("INNER INSTRUCTIONS"));
                assertTrue(page.contains("CONTINUED INSTRUCTIONS"));
                f.tui.closeTemporaryWindow();
                return true;
            });
            assertTrue(f.tui.getContentViewLines().isEmpty());
        }
    }

    private static final class Fixture implements AutoCloseable {
        final ByteArrayOutputStream output = new ByteArrayOutputStream();
        final LineDisciplineTerminal terminal = new LineDisciplineTerminal(
                "dialog-test", "xterm", output, StandardCharsets.UTF_8);
        final BackgroundProcessManager processes = new BackgroundProcessManager("dialog-output-test");
        final MessageQueue queue = new MessageQueue("dialog-output-" + UUID.randomUUID());
        final KompileTui tui = new KompileTui(new BackgroundTaskManager(), processes, queue,
                new TerminalRenderer(true));

        Fixture() throws Exception {
            terminal.setSize(new Size(100, 30));
            LineReader reader = LineReaderBuilder.builder().terminal(terminal).build();
            tui.attachLineReader(reader);
            tui.start(terminal);
        }

        @Override
        public void close() throws Exception {
            tui.detachLineReader();
            tui.stop();
            queue.clear();
            processes.close();
            terminal.close();
        }
    }
}
