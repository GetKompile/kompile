package ai.kompile.cli.main.chat.tui;

import ai.kompile.cli.main.chat.BackgroundTaskManager;
import ai.kompile.cli.main.chat.MessageQueue;
import ai.kompile.cli.main.chat.config.ChatUiConfig;
import ai.kompile.cli.main.chat.render.TerminalRenderer;
import ai.kompile.cli.main.chat.tools.BackgroundProcessManager;
import org.jline.terminal.Size;
import org.jline.terminal.Terminal;
import org.jline.terminal.impl.LineDisciplineTerminal;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class KittyImagePreviewTest {
    @TempDir Path root;
    private static final Pattern COMMAND = Pattern.compile("\033_G([^;\033]*)(?:;([^\033]*))?\033\\\\");

    @Test void chunksRoundTripAndDoNotRequestTerminalReplies() {
        byte[] bytes = new byte[12_345];
        new Random(42).nextBytes(bytes);
        KittyImagePreview renderer = new KittyImagePreview();
        String wire = renderer.render(new KittyImagePreview.Image(bytes, "test", 100, 100),
                mock(Terminal.class), true, 3, 30, 10);
        var matcher = COMMAND.matcher(wire);
        StringBuilder encoded = new StringBuilder();
        List<String> uploadHeaders = new ArrayList<>();
        while (matcher.find()) {
            assertTrue(matcher.group(1).contains("q=2"));
            String payload = matcher.group(2);
            if (payload == null) continue;
            assertTrue(payload.length() <= 4096);
            assertEquals(0, payload.length() % 4);
            encoded.append(payload);
            uploadHeaders.add(matcher.group(1));
        }
        assertArrayEquals(bytes, Base64.getDecoder().decode(encoded.toString()));
        assertTrue(uploadHeaders.size() > 1);
        assertTrue(uploadHeaders.get(0).startsWith("a=t,t=d,f=100,i="));
        for (int i = 1; i < uploadHeaders.size(); i++) {
            assertEquals("q=2,m=" + (i == uploadHeaders.size() - 1 ? "0" : "1"), uploadHeaders.get(i));
        }
        assertTrue(wire.contains(",p=1,c=30,r=10,C=1,q=2"));
        assertFalse(wire.contains("a=q"));
    }

    @Test void stableFramesReuseDataAndResizeOnlyMovesTheOwnedPlacement() {
        var renderer = new KittyImagePreview();
        var terminal = mock(Terminal.class);
        var image = new KittyImagePreview.Image(new byte[]{1, 2, 3}, "image", 1, 1);
        String first = renderer.render(image, terminal, true, 3, 120, 40);
        assertTrue(first.contains("c=80,r=20,C=1,q=2"));
        assertEquals("", renderer.render(image, terminal, true, 3, 120, 40));
        String resized = renderer.render(image, terminal, true, 4, 20, 6);
        assertTrue(resized.startsWith("\033[4;1H\033_Ga=p,"));
        assertTrue(resized.contains("c=20,r=6,C=1,q=2"));
        assertFalse(resized.contains("a=t"));
        String clear = renderer.clear();
        assertTrue(clear.startsWith("\033_Ga=d,d=I,i="));
        assertTrue(clear.endsWith(",q=2\033\\"));
        assertEquals("", renderer.clear());
        assertTrue(renderer.render(image, terminal, true, 3, 20, 5).contains("a=t"));
        assertTrue(renderer.render(image, terminal, true, 3, 20, 0).contains("a=d"));
    }

    @Test void disabledAndDumbOrMultiplexedTerminalsNeverReceiveGraphics() {
        Terminal terminal = mock(Terminal.class);
        when(terminal.getType()).thenReturn("xterm-kitty");
        assertFalse(KittyImagePreview.supported(terminal, "off"));
        for (String type : List.of("dumb", "screen-256color", "tmux-256color", "vt100")) {
            when(terminal.getType()).thenReturn(type);
            assertFalse(KittyImagePreview.supported(terminal, "kitty"));
        }
        assertFalse(KittyImagePreview.supported(null, "kitty"));
        when(terminal.getType()).thenReturn("xterm");
        when(terminal.getenv("TERM_PROGRAM")).thenReturn("kitty");
        assertTrue(KittyImagePreview.supported(terminal, "auto"));
        when(terminal.getenv("TMUX")).thenReturn("/tmp/tmux/socket");
        assertFalse(KittyImagePreview.supported(terminal, "auto"));
        assertTrue(KittyImagePreview.supported(terminal, "kitty"), "tmux requires explicit opt-in");
        for (String other : List.of("STY", "ZELLIJ")) {
            when(terminal.getenv(other)).thenReturn("nested");
            assertFalse(KittyImagePreview.supported(terminal, "kitty"));
            when(terminal.getenv(other)).thenReturn(null);
        }
        when(terminal.getType()).thenThrow(new IllegalStateException("closed"));
        assertFalse(KittyImagePreview.supported(terminal, "auto"));
    }

    @Test void autoTmuxChecksTheRunningServerAndEveryAttachedClient() {
        Terminal terminal = mock(Terminal.class);
        when(terminal.getType()).thenReturn("tmux-256color");
        when(terminal.getenv("TMUX")).thenReturn("/tmp/test,with-comma/socket,123,0");
        when(terminal.getenv("TMUX_PANE")).thenReturn("%7");
        List<List<String>> calls = new ArrayList<>();
        KittyImagePreview.CapabilityCommands commands = command -> {
            calls.add(command);
            return command.contains("display-message") ? "$2\t1\t3.3a\n"
                    : "xterm-kitty\tclipboard,RGB,UTF-8\t1\n";
        };
        assertTrue(KittyImagePreview.supported(terminal, "auto", commands));
        assertEquals(List.of("tmux", "-S", "/tmp/test,with-comma/socket", "display-message", "-p", "-t", "%7",
                "#{session_id}\t#{allow-passthrough}\t#{version}"), calls.get(0));
        assertTrue(calls.get(1).contains("$2"));
        assertTrue(calls.get(1).contains("#{client_termname}\t#{client_termfeatures}\t#{client_utf8}"));
        for (String clients : List.of("", "xterm-kitty\tRGB\t0", "xterm-kitty\tUTF-8\t1",
                "xterm-256color\tRGB\t1", "xterm-kitty\tRGB\t1\nxterm-256color\tRGB\t1", "malformed")) {
            assertFalse(KittyImagePreview.supported(terminal, "auto", command ->
                    command.contains("display-message") ? "$2\t1\t3.3a\n" : clients), clients);
        }
        for (String server : List.of("$2\toff\t3.3a", "$2\ton\t3.2a", "bad\ton\t3.3", "")) {
            assertFalse(KittyImagePreview.supported(terminal, "auto", command -> server), server);
        }
        assertTrue(KittyImagePreview.supported(terminal, "auto", command -> command.contains("display-message")
                ? "$2\tall\t3.6\n" : "xterm-ghostty\tRGB\t1\n"));
        when(terminal.getenv("TERM_PROGRAM")).thenReturn("kitty");
        when(terminal.getenv("TERM_PROGRAM_VERSION")).thenReturn("0.27.1");
        assertFalse(KittyImagePreview.supported(terminal, "auto", commands));
        when(terminal.getenv("TERM_PROGRAM_VERSION")).thenReturn("0.28.0");
        assertTrue(KittyImagePreview.supported(terminal, "auto", commands));
        assertFalse(KittyImagePreview.supported(terminal, "auto", command -> { throw new IOException("timeout"); }));
        calls.clear();
        assertTrue(KittyImagePreview.supported(terminal, "kitty", commands));
        assertFalse(KittyImagePreview.supported(terminal, "off", commands));
        assertTrue(calls.isEmpty(), "forced overrides must not invoke detection commands");
        assertFalse(KittyImagePreview.supported(terminal, "auto", command -> { throw new InterruptedException(); }));
        assertTrue(Thread.interrupted(), "interrupts must be restored, then cleared by this test");
    }

    @Test void autoZellijRequiresTheRunningBinaryVersionAndKnownHost() {
        Terminal terminal = mock(Terminal.class);
        when(terminal.getType()).thenReturn("xterm-256color");
        when(terminal.getenv("ZELLIJ")).thenReturn("0");
        when(terminal.getenv("TERM_PROGRAM")).thenReturn("kitty");
        for (String version : List.of("zellij 0.45.1", "zellij 0.46.0")) {
            assertTrue(KittyImagePreview.supported(terminal, "auto", zellijCommands("/proc/123/exe", version)));
        }
        assertFalse(KittyImagePreview.supported(terminal, "auto", zellijCommands("/proc/123/exe", "zellij 0.45.0")));
        assertFalse(KittyImagePreview.supported(terminal, "auto", zellijCommands("", "zellij 0.45.1")));
        when(terminal.getenv("TERM_PROGRAM")).thenReturn("unknown");
        assertFalse(KittyImagePreview.supported(terminal, "auto", zellijCommands("/proc/123/exe", "zellij 0.45.1")));
        assertTrue(KittyImagePreview.supported(terminal, "kitty", zellijCommands("", "")));
    }

    private static KittyImagePreview.CapabilityCommands zellijCommands(String binary, String version) {
        return new KittyImagePreview.CapabilityCommands() {
            @Override public String zellijExecutable() { return binary; }
            @Override public String run(List<String> command) {
                assertEquals(List.of(binary, "--version"), command);
                return version;
            }
        };
    }

    @Test void explicitZellijUsesNativePlacementsNotTmuxOrUnicodePlaceholders() {
        Terminal terminal = mock(Terminal.class);
        when(terminal.getType()).thenReturn("xterm-256color");
        when(terminal.getenv("ZELLIJ")).thenReturn("0");
        assertFalse(KittyImagePreview.supported(terminal, "auto"));
        assertTrue(KittyImagePreview.supported(terminal, "kitty"));
        String wire = new KittyImagePreview().render(
                new KittyImagePreview.Image(new byte[]{1, 2, 3}, "zellij", 1, 1),
                terminal, true, 3, 10, 4);
        assertTrue(wire.contains("a=p,i="));
        assertTrue(wire.contains(",C=1,q=2"));
        assertFalse(wire.contains("U=1"));
        assertFalse(wire.contains("\033Ptmux;"));
        assertEquals(0, wire.codePoints().filter(cp -> cp == KittyImagePreview.PLACEHOLDER).count());
    }

    @Test void tmuxWrapsEachGraphicsCommandButNeverPaneCursorOrPlaceholderText() {
        Terminal terminal = mock(Terminal.class);
        when(terminal.getenv("TMUX")).thenReturn("/tmp/test,1,0");
        byte[] bytes = new byte[12_345];
        new Random(42).nextBytes(bytes);
        var renderer = new KittyImagePreview();
        var image = new KittyImagePreview.Image(bytes, "tmux", 100, 100);
        String wire = renderer.render(image, terminal, true, 5, 3, 2);
        List<String> commands = new ArrayList<>();
        String text = unwrapTmux(wire, commands);
        assertFalse(text.contains("\033_G"), "all graphics must be inside DCS passthrough");
        assertEquals(6, text.codePoints().filter(cp -> cp == KittyImagePreview.PLACEHOLDER).count());
        assertTrue(text.startsWith("\033[5;1H"));
        assertTrue(text.contains("\033[6;1H"));
        assertTrue(text.endsWith("\033[0m"));
        StringBuilder encoded = new StringBuilder();
        int id = 0;
        for (int i = 0; i < commands.size(); i++) {
            String command = commands.get(i);
            assertTrue(command.startsWith("\033_G"));
            assertTrue(command.endsWith("\033\\"));
            assertTrue(command.contains("q=2"));
            assertFalse(command.contains("\033["), "CUP/SGR must be interpreted by tmux, not bypass it");
            var parsed = COMMAND.matcher(command);
            assertTrue(parsed.matches());
            if (parsed.group(2) != null) {
                assertTrue(parsed.group(2).length() <= 4096);
                encoded.append(parsed.group(2));
            }
            if (i == 0) {
                var imageId = Pattern.compile("i=([0-9]+)").matcher(command);
                assertTrue(imageId.find());
                id = Integer.parseInt(imageId.group(1));
            } else if (i < commands.size() - 1) {
                assertTrue(parsed.group(1).matches("q=2,m=[01]"));
            } else {
                assertTrue(command.contains("a=p,U=1,i=" + id + ",p=1,c=3,r=2,q=2"));
            }
        }
        assertArrayEquals(bytes, Base64.getDecoder().decode(encoded.toString()));
        assertTrue(text.contains("\033[0;38;2;" + ((id >>> 16) & 255) + ";"
                + ((id >>> 8) & 255) + ";" + (id & 255) + "m"));
        String zero = new String(Character.toChars(KittyImagePreview.PLACEHOLDER)) + "\u0305\u0305";
        assertTrue(text.contains(zero), "first cell must explicitly encode row0,column0");
        String one = new String(Character.toChars(KittyImagePreview.PLACEHOLDER)) + "\u030D\u030E";
        assertTrue(text.contains(one), "last cell must explicitly encode row1,column2");
        assertTrue(commands.size() > 2, "upload should be split across independent passthrough commands");

        List<String> repeatedCommands = new ArrayList<>();
        String repeatedText = unwrapTmux(renderer.render(image, terminal, true, 5, 3, 2), repeatedCommands);
        assertTrue(repeatedCommands.isEmpty(), "stable frames reuse the uploaded image and prototype");
        assertEquals(text, repeatedText, "placeholder text must be repainted after TUI line erasure");
        List<String> resizedCommands = new ArrayList<>();
        String resizedText = unwrapTmux(renderer.render(image, terminal, true, 8, 1, 1), resizedCommands);
        assertEquals(1, resizedCommands.size());
        assertTrue(resizedCommands.get(0).contains("c=1,r=1,q=2"));
        assertEquals(1, resizedText.codePoints().filter(cp -> cp == KittyImagePreview.PLACEHOLDER).count());
        List<String> deleted = new ArrayList<>();
        assertEquals("", unwrapTmux(renderer.clear(), deleted));
        assertEquals(List.of("\033_Ga=d,d=I,i=" + id + ",q=2\033\\"), deleted);
    }

    @Test void placeholderIdsKeepTheirHighByteIncludingTheLargestPositiveId() throws Exception {
        var field = KittyImagePreview.class.getDeclaredField("IDS");
        field.setAccessible(true);
        var ids = (java.util.concurrent.atomic.AtomicInteger) field.get(null);
        int prior = ids.get();
        Terminal terminal = mock(Terminal.class);
        when(terminal.getenv("TMUX")).thenReturn("/tmp/test,1,0");
        try {
            for (int id : List.of(33_554_474, Integer.MAX_VALUE)) {
                ids.set(id - 1);
                String wire = new KittyImagePreview().render(
                        new KittyImagePreview.Image(new byte[]{1, 2, 3}, "id", 1, 1),
                        terminal, true, 3, 1, 1);
                List<String> commands = new ArrayList<>();
                String text = unwrapTmux(wire, commands);
                assertTrue(commands.get(0).contains("i=" + id + ","));
                char high = id == Integer.MAX_VALUE ? '\u082C' : '\u030E';
                assertTrue(text.contains(new String(Character.toChars(KittyImagePreview.PLACEHOLDER))
                        + "\u0305\u0305" + high), "high ID bits must not be truncated to RGB");
            }
        } finally {
            ids.set(prior);
        }
    }

    /** Decode tmux's DCS envelope independently of production code. */
    private static String unwrapTmux(String wire, List<String> commands) {
        StringBuilder text = new StringBuilder();
        int offset = 0;
        while (offset < wire.length()) {
            if (!wire.startsWith("\033Ptmux;", offset)) {
                text.append(wire.charAt(offset++));
                continue;
            }
            offset += 7;
            StringBuilder command = new StringBuilder();
            boolean terminated = false;
            while (offset < wire.length()) {
                char ch = wire.charAt(offset++);
                if (ch != '\033') { command.append(ch); continue; }
                assertTrue(offset < wire.length());
                char next = wire.charAt(offset++);
                if (next == '\033') command.append('\033');
                else {
                    assertEquals('\\', next, "ESC must be doubled unless ending the DCS");
                    terminated = true;
                    break;
                }
            }
            assertTrue(terminated, "unterminated tmux envelope");
            commands.add(command.toString());
        }
        return text.toString();
    }

    @Test void preparationProducesBoundedPngWithoutChangingTheAttachment() throws Exception {
        Path path = image("photo.jpg", 1300, 900, "jpeg");
        byte[] original = Files.readAllBytes(path);
        var prepared = KittyImagePreview.read(path);
        assertEquals(1300, prepared.width());
        assertEquals(900, prepared.height());
        BufferedImage decoded = ImageIO.read(new ByteArrayInputStream(prepared.png()));
        assertTrue(decoded.getWidth() <= 512 && decoded.getHeight() <= 512);
        assertArrayEquals(original, Files.readAllBytes(path));
        assertTrue(prepared.png().length < 1_500_000);
    }

    @Test void invalidAndOversizedImagesAreRejectedBeforeRasterAllocation() throws Exception {
        Path invalid = root.resolve("bad.png");
        Files.writeString(invalid, "not an image");
        assertThrows(IOException.class, () -> KittyImagePreview.read(invalid));
        Path huge = image("huge.png", 2, 2, "png");
        byte[] png = Files.readAllBytes(huge);
        ByteBuffer.wrap(png).putInt(16, 100_000).putInt(20, 100_000);
        Files.write(huge, png);
        assertThrows(IOException.class, () -> KittyImagePreview.read(huge));
        Files.write(invalid, new byte[KittyImagePreview.MAX_FILE_BYTES + 1]);
        assertThrows(IOException.class, () -> KittyImagePreview.read(invalid));
    }

    @Test void viewCloseDeletesGraphicsAndRestoresTheRetainedTranscript() throws Exception {
        try (Fixture f = new Fixture()) {
            f.tui.recordInScrollRegion("retained message");
            assertTrue(f.tui.previewImage(image("preview.png", 30, 20, "png")));
            await(() -> f.wire().contains("a=p"));
            assertTrue(f.tui.isImagePreviewView());
            assertTrue(f.tui.getContentViewLines().stream().noneMatch(line -> line.contains("\033_G")));
            f.output.reset();
            f.tui.showMainView();
            await(() -> f.wire().contains("a=d,d=I"));
            assertTrue(f.tui.getContentViewLines().contains("retained message"));
            assertFalse(f.tui.isImagePreviewView());
        }
    }

    @Test void detachAndResizeCleanUpAndReuploadInsideTheNewViewport() throws Exception {
        try (Fixture f = new Fixture()) {
            assertTrue(f.tui.previewImage(image("preview.png", 30, 20, "png")));
            await(() -> f.wire().contains("a=p"));
            f.output.reset();
            f.terminal.setSize(new Size(40, 16));
            f.tui.handleResize();
            await(() -> f.wire().contains("a=p"));
            assertTrue(f.wire().contains("a=d,d=I"));
            var placed = Pattern.compile("c=([0-9]+),r=([0-9]+),C=1").matcher(f.wire());
            assertTrue(placed.find());
            assertTrue(Integer.parseInt(placed.group(1)) < 40);
            assertTrue(Integer.parseInt(placed.group(2)) < 16);
            f.output.reset();
            f.tui.detachTerminal();
            assertTrue(f.wire().contains("a=d,d=I"));
            f.output.reset();
            f.tui.attachTerminal(f.terminal);
            await(() -> f.wire().contains("a=t"));
        }
    }

    @Test void closedViewCannotBeReopenedByALateImageDecode() throws Exception {
        try (Fixture f = new Fixture()) {
            CountDownLatch release = new CountDownLatch(1);
            CountDownLatch started = new CountDownLatch(1);
            var field = KompileTui.class.getDeclaredField("imagePreviewExecutor");
            field.setAccessible(true);
            ExecutorService executor = (ExecutorService) field.get(f.tui);
            executor.submit(() -> {
                started.countDown();
                try { release.await(3, TimeUnit.SECONDS); }
                catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
            });
            assertTrue(started.await(2, TimeUnit.SECONDS));
            try {
                assertTrue(f.tui.previewImage(image("late.png", 30, 20, "png")));
                f.tui.showMainView();
                release.countDown();
                executor.submit(() -> { }).get(3, TimeUnit.SECONDS);
                assertTrue(f.tui.isMainContentView());
                assertFalse(f.wire().contains("a=t"));
            } finally {
                release.countDown();
            }
        }
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(booleans = {false, true})
    void aModalKeepsDecodeCompletionAndFailureBehindIt(boolean failure) throws Exception {
        try (Fixture f = new Fixture()) {
            CountDownLatch release = new CountDownLatch(1);
            CountDownLatch started = new CountDownLatch(1);
            var field = KompileTui.class.getDeclaredField("imagePreviewExecutor");
            field.setAccessible(true);
            ExecutorService executor = (ExecutorService) field.get(f.tui);
            executor.submit(() -> {
                started.countDown();
                try { release.await(3, TimeUnit.SECONDS); }
                catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
            });
            assertTrue(started.await(2, TimeUnit.SECONDS));
            try {
                Path path = failure ? root.resolve("missing.png") : image("modal.png", 30, 20, "png");
                assertTrue(f.tui.previewImage(path));
                f.tui.showTemporaryWindow("Picker", List.of("choice"));
                release.countDown();
                executor.submit(() -> { }).get(3, TimeUnit.SECONDS);
                assertTrue(f.tui.isTemporaryWindowActive());
                f.output.reset();
                f.tui.closeTemporaryWindow();
                if (failure) {
                    assertTrue(f.tui.isMainContentView());
                    assertTrue(f.tui.getCurrentAlert().contains("preview unavailable"));
                    assertFalse(f.wire().contains("a=t"));
                } else {
                    await(() -> f.wire().contains("a=p"));
                    assertTrue(f.tui.isImagePreviewView());
                }
            } finally {
                release.countDown();
            }
        }
    }

    @Test void unsupportedAndDecodeFailureLeaveChatUsable() throws Exception {
        try (Fixture f = new Fixture()) {
            ChatUiConfig config = ChatUiConfig.classic();
            config.set("images.protocol", "off");
            f.tui.setUiConfig(config);
            assertFalse(f.tui.previewImage(root.resolve("missing.png")));
            assertTrue(f.tui.isMainContentView());
            config.set("images.protocol", "kitty");
            f.tui.setUiConfig(config);
            assertTrue(f.tui.previewImage(root.resolve("missing.png")));
            await(() -> f.tui.isMainContentView() && f.tui.getCurrentAlert() != null
                    && f.tui.getCurrentAlert().contains("still attached"));
            assertTrue(f.tui.getCurrentAlert().contains("still attached"));
            assertFalse(f.wire().contains("a=t"));
        }
    }

    private Path image(String name, int width, int height, String format) throws IOException {
        Path path = root.resolve(name);
        BufferedImage raster = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        assertTrue(ImageIO.write(raster, format, path.toFile()));
        raster.flush();
        return path;
    }

    private static void await(BooleanSupplier condition) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(4);
        while (!condition.getAsBoolean() && System.nanoTime() < deadline) Thread.sleep(10);
        assertTrue(condition.getAsBoolean(), "preview did not reach the expected state");
    }

    private static final class Fixture implements AutoCloseable {
        final ByteArrayOutputStream output = new ByteArrayOutputStream();
        final LineDisciplineTerminal terminal = new LineDisciplineTerminal("preview", "xterm", output, StandardCharsets.UTF_8);
        final BackgroundProcessManager processes = new BackgroundProcessManager("preview-" + UUID.randomUUID());
        final KompileTui tui = new KompileTui(new BackgroundTaskManager(), processes,
                new MessageQueue("preview-" + UUID.randomUUID()), new TerminalRenderer(true));
        Fixture() throws Exception {
            terminal.setSize(new Size(100, 30));
            ChatUiConfig config = ChatUiConfig.classic();
            config.set("images.protocol", "kitty");
            tui.setUiConfig(config);
            tui.start(terminal);
        }
        String wire() { return output.toString(StandardCharsets.UTF_8); }
        @Override public void close() throws Exception {
            tui.stop();
            processes.close();
            terminal.close();
        }
    }
}
