package ai.kompile.cli.main.chat.tui;

import org.jline.terminal.Terminal;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Real tmux text-grid qualification; does not claim outer-terminal pixel rendering. */
class KittyTmuxTest {
    @TempDir Path root;

    @Test void placeholderCellsKeepTheirColorsMarksAndPaneCoordinatesOnSplitAndResize() throws Exception {
        try {
            Process version = new ProcessBuilder("tmux", "-V").start();
            assertTrue(version.waitFor(3, TimeUnit.SECONDS));
            Assumptions.assumeTrue(version.exitValue() == 0, "tmux unavailable");
        } catch (IOException unavailable) {
            Assumptions.abort("tmux is optional for this integration test");
        }
        Path socket = root.resolve("test.sock");
        Terminal terminal = mock(Terminal.class);
        when(terminal.getenv("TMUX")).thenReturn(socket + ",1,0");
        String wire = new KittyImagePreview().render(
                new KittyImagePreview.Image(new byte[]{1, 2, 3}, "test", 10, 10),
                terminal, true, 3, 6, 3);
        String emitted = wire + "\033[9;1HINPUT-BOUNDARY";
        String java = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        String classes = Path.of(KittyTmuxTest.class.getProtectionDomain().getCodeSource().getLocation().toURI()).toString();
        try {
            String pane = tmux(socket, "new-session", "-d", "-s", "preview", "-x", "60", "-y", "12",
                    "-P", "-F", "#{pane_id}", java, "-cp", classes, Emitter.class.getName(), encoded(emitted)).strip();
            // All options below belong to this unique test socket, never an existing server.
            tmux(socket, "set-option", "-g", "status", "off");
            tmux(socket, "set-option", "-w", "-t", pane, "allow-passthrough", "on");
            when(terminal.getType()).thenReturn("tmux-256color");
            when(terminal.getenv("TMUX_PANE")).thenReturn(pane);
            // Exercise real server formatting and inherited pane permissions.
            KittyImagePreview.CapabilityCommands metadata = command -> {
                if (command.contains("list-clients")) return "xterm-kitty\tRGB,UTF-8\t1\n";
                try { return tmux(socket, command.subList(3, command.size()).toArray(String[]::new)); }
                catch (Exception failure) { throw new IOException(failure); }
            };
            assertTrue(KittyImagePreview.supported(terminal, "auto", metadata), () -> {
                try { return tmux(socket, "display-message", "-p", "-t", pane,
                        "#{session_id}|#{allow-passthrough}|#{version}"); }
                catch (Exception failure) { return failure.toString(); }
            });
            assertFalse(KittyImagePreview.supported(terminal, "auto"), "detached session has no receiving client");
            tmux(socket, "set-option", "-p", "-t", pane, "allow-passthrough", "off");
            assertFalse(KittyImagePreview.supported(terminal, "auto", metadata));
            tmux(socket, "set-option", "-p", "-t", pane, "allow-passthrough", "on");
            String captured = awaitImage(socket, pane);
            assertGrid(captured);
            assertTrue(captured.contains("\033[38;2;"), "tmux must retain numeric RGB IDs: " + captured);
            assertFalse(captured.contains("tmux;"));
            String sibling = tmux(socket, "split-window", "-h", "-p", "50", "-t", pane,
                    "-P", "-F", "#{pane_id}", java, "-cp", classes, Emitter.class.getName(), encoded("SIBLING")).strip();
            assertGrid(tmux(socket, "capture-pane", "-p", "-e", "-t", pane));
            assertFalse(tmux(socket, "capture-pane", "-p", "-e", "-t", sibling)
                    .codePoints().anyMatch(cp -> cp == KittyImagePreview.PLACEHOLDER));
            tmux(socket, "resize-window", "-t", "preview", "-x", "40", "-y", "12");
            assertGrid(tmux(socket, "capture-pane", "-p", "-e", "-t", pane));
        } finally {
            // Only the server created above is addressed. No broad tmux cleanup.
            tmuxBestEffortStop(socket);
        }
    }

    private static String encoded(String text) {
        return Base64.getEncoder().encodeToString(text.getBytes(StandardCharsets.UTF_8));
    }

    private static void assertGrid(String capture) {
        assertEquals(18, capture.codePoints().filter(cp -> cp == KittyImagePreview.PLACEHOLDER).count(), capture);
        var cells = java.util.regex.Pattern.compile("\\x{10EEEE}\\p{M}{3}").matcher(capture);
        assertEquals(18, cells.results().count(), "all three row/column/high-ID marks must survive");
        assertTrue(capture.contains("INPUT-BOUNDARY"), capture);
        List<String> lines = capture.lines().toList();
        assertTrue(lines.get(2).contains(new String(Character.toChars(KittyImagePreview.PLACEHOLDER))), capture);
        assertFalse(lines.get(8).codePoints().anyMatch(cp -> cp == KittyImagePreview.PLACEHOLDER));
    }

    private static String awaitImage(Path socket, String pane) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(4);
        String captured;
        do {
            captured = tmux(socket, "capture-pane", "-p", "-e", "-t", pane);
            if (captured.contains("INPUT-BOUNDARY")) return captured;
            Thread.sleep(20);
        } while (System.nanoTime() < deadline);
        fail("image emitter did not paint its pane: " + captured);
        return "";
    }

    private static String tmux(Path socket, String... arguments) throws Exception {
        List<String> command = new ArrayList<>(List.of("tmux", "-u", "-S", socket.toString(), "-f", "/dev/null"));
        command.addAll(List.of(arguments));
        ProcessBuilder builder = new ProcessBuilder(command).redirectErrorStream(true);
        builder.environment().remove("TMUX");
        Process process = builder.start();
        try {
            assertTrue(process.waitFor(5, TimeUnit.SECONDS), "tmux command timed out: " + command);
            String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            assertEquals(0, process.exitValue(), "tmux failed: " + command + "\n" + output);
            return output;
        } finally {
            if (process.isAlive()) process.destroyForcibly().waitFor(2, TimeUnit.SECONDS);
        }
    }

    private static void tmuxBestEffortStop(Path socket) throws Exception {
        Process process = new ProcessBuilder("tmux", "-S", socket.toString(), "kill-server")
                .redirectErrorStream(true).start();
        if (!process.waitFor(3, TimeUnit.SECONDS)) process.destroyForcibly().waitFor(2, TimeUnit.SECONDS);
    }

    /** Tiny bounded test-owned pane process; needs only the JDK on its classpath. */
    public static class Emitter {
        public static void main(String[] args) throws Exception {
            System.out.print(new String(Base64.getDecoder().decode(args[0]), StandardCharsets.UTF_8));
            System.out.flush();
            Thread.sleep(30_000);
        }
    }
}
