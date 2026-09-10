package ai.kompile.cli.main.auth;

import org.jline.reader.LineReader;
import org.jline.terminal.Size;
import org.jline.terminal.impl.LineDisciplineTerminal;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.function.IntConsumer;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.*;

class AuthWizardTerminalPagingTest {
    @Test
    void pagesLargeListsAndSelectsGlobalNumber() throws Exception {
        var output = new ByteArrayOutputStream();
        try (var terminal = new LineDisciplineTerminal("auth-page-test", "xterm", output, StandardCharsets.UTF_8)) {
            terminal.setSize(new Size(48, 12));
            var script = new ScriptedReader("n", "n", "p", "8");
            var prompter = new AuthWizard.TerminalPrompter(terminal, script.reader);

            assertEquals(7, prompter.select("Choose provider", providers(60)));
            terminal.writer().flush();
            String rendered = output.toString(StandardCharsets.UTF_8);
            assertTrue(rendered.contains("Showing 1-6 of 60"));
            assertTrue(rendered.contains("Showing 7-12 of 60"));
            assertTrue(rendered.contains("Showing 13-18 of 60"));
            assertEquals(2, rendered.split("Showing 7-12 of 60", -1).length - 1);
            assertFalse(rendered.contains("Provider 60"));
            assertTrue(rendered.contains("\033[r"));
            assertTrue(rendered.contains("\033[?1000l"));
            assertEquals(4, script.calls);
        }
    }

    @Test
    void previousPageEndsBeforeTheRenderedPageAcrossGrowAndShrink() throws Exception {
        var grownOutput = new ByteArrayOutputStream();
        try (var terminal = new LineDisciplineTerminal(
                "auth-prev-grow-test", "xterm", grownOutput, StandardCharsets.UTF_8)) {
            terminal.setSize(new Size(32, 8));
            var script = new ScriptedReader("n", "n", "p", "q");
            script.beforeRead = call -> {
                if (call == 2) {
                    terminal.setSize(new Size(48, 14));
                }
            };
            assertEquals(-1, new AuthWizard.TerminalPrompter(terminal, script.reader)
                    .select("Choose", providers(60)));
            terminal.writer().flush();
            assertTrue(grownOutput.toString(StandardCharsets.UTF_8).contains("Showing 1-4 of 60"));
        }

        var shrunkOutput = new ByteArrayOutputStream();
        try (var terminal = new LineDisciplineTerminal(
                "auth-prev-shrink-test", "xterm", shrunkOutput, StandardCharsets.UTF_8)) {
            terminal.setSize(new Size(48, 14));
            var script = new ScriptedReader("n", "p", "q");
            script.beforeRead = call -> {
                if (call == 1) {
                    terminal.setSize(new Size(32, 8));
                }
            };
            assertEquals(-1, new AuthWizard.TerminalPrompter(terminal, script.reader)
                    .select("Choose", providers(60)));
            terminal.writer().flush();
            assertTrue(shrunkOutput.toString(StandardCharsets.UTF_8).contains("Showing 7-8 of 60"));
        }
    }

    @Test
    void invalidAmbiguousAndEmptyInputDoNotSilentlySelectFirstItem() throws Exception {
        try (var terminal = new LineDisciplineTerminal("auth-invalid-test", "xterm", new ByteArrayOutputStream(), StandardCharsets.UTF_8)) {
            terminal.setSize(new Size(80, 24));
            var script = new ScriptedReader("", "100", "provider", "Provider 20");
            assertEquals(19, new AuthWizard.TerminalPrompter(terminal, script.reader).select("Choose", providers(60)));
            assertEquals(4, script.calls);
        }
    }

    @Test
    void resizesWhilePagingAndClipsUntrustedMultilineLabels() throws Exception {
        var output = new ByteArrayOutputStream();
        try (var terminal = new LineDisciplineTerminal("auth-resize-test", "xterm", output, StandardCharsets.UTF_8)) {
            terminal.setSize(new Size(24, 14));
            var script = new ScriptedReader("n", "q");
            script.beforeRead = call -> terminal.setSize(new Size(24, 8));
            List<String> items = IntStream.rangeClosed(1, 60)
                    .mapToObj(i -> "Provider " + i + "\nextra\rline\t\b\033]52;c;clipboard\u0007"
                            + "\033D\033[31mINJECTED\u009b31m" + "x".repeat(60)).toList();
            assertEquals(-1, new AuthWizard.TerminalPrompter(terminal, script.reader)
                    .select("Choose\033]0;title\u0007", items));
            terminal.writer().flush();
            String rendered = output.toString(StandardCharsets.UTF_8);
            assertTrue(rendered.contains("Showing 9-10 of 60"));
            assertFalse(rendered.contains("\033]52"));
            assertFalse(rendered.contains("\033D"));
            assertFalse(rendered.contains("\033[31mINJECTED"));
            assertFalse(rendered.contains("\u0007"));
            assertFalse(rendered.contains("\u009b"));
            assertFalse(rendered.contains("\b"));
            for (String line : rendered.split("\r?\n")) {
                String plain = line.replaceAll("\u001B\\[[0-?]*[ -/]*[@-~]", "");
                assertTrue(plain.length() <= 23, () -> "Wrapped menu line: " + plain);
            }
        }
    }

    @Test
    void cancellationAndEmptyListReturnNoSelection() throws Exception {
        var output = new ByteArrayOutputStream();
        try (var terminal = new LineDisciplineTerminal("auth-cancel-test", "xterm", output, StandardCharsets.UTF_8)) {
            var script = new ScriptedReader((String) null);
            var prompter = new AuthWizard.TerminalPrompter(terminal, script.reader);
            assertEquals(-1, prompter.select("Choose", List.of()));
            assertEquals(0, script.calls);
            terminal.writer().flush();
            assertTrue(output.toString(StandardCharsets.UTF_8).contains("\033[?1000l"));
            assertEquals(-1, prompter.select("Choose", providers(2)));
        }
    }

    private static final class ScriptedReader {
        private int calls;
        private IntConsumer beforeRead = call -> {};
        private final LineReader reader;

        private ScriptedReader(String... input) {
            reader = (LineReader) Proxy.newProxyInstance(LineReader.class.getClassLoader(),
                    new Class<?>[]{LineReader.class}, (proxy, method, args) -> {
                        if (!method.getName().equals("readLine") || calls >= input.length) {
                            throw new AssertionError("Unexpected LineReader call: " + method.getName());
                        }
                        beforeRead.accept(calls);
                        return input[calls++];
                    });
        }
    }

    private static List<String> providers(int count) {
        return IntStream.rangeClosed(1, count).mapToObj(i -> "Provider " + i).toList();
    }
}
